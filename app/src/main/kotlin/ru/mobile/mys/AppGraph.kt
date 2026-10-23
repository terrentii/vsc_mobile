package ru.mobile.mys

import ru.mobile.mys.mesh.LinkCrypto
import ru.mobile.mys.mesh.MeshConfig
import ru.mobile.mys.mesh.MeshInternet
import ru.mobile.mys.mesh.MeshNode
import ru.mobile.mys.mesh.WsWire
import ru.mobile.mys.radio.BleMesh
import okhttp3.OkHttpClient
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import ru.mobile.mys.central.AuthException
import ru.mobile.mys.central.CentralEvent
import ru.mobile.mys.central.CentralService
import ru.mobile.mys.central.NetworkException
import ru.mobile.mys.central.ServerException
import ru.mobile.mys.crypto.Ristretto
import ru.mobile.mys.p2p.P2PEvent
import ru.mobile.mys.p2p.P2PService
import ru.mobile.mys.p2p.PakeException
import ru.mobile.mys.p2p.PeerUnavailable
import ru.mobile.mys.p2p.RendezvousException
import ru.mobile.mys.storage.Vault
import ru.mobile.mys.storage.VaultCentralStore
import ru.mobile.mys.storage.VaultP2PStore
import ru.mobile.mys.webrtc.WebRtcLink

const val DECENTRALIZED = "decentralized"
const val CENTRALIZED = "centralized"

/**
 * Состояние процесса: открытый vault и сервисы режимов. Живёт, пока жив процесс
 * (его держит [MysService]). UI читает Compose-состояние отсюда и перечитывает
 * vault по [tick].
 */
object AppGraph {
    const val DEFAULT_SERVER = "https://soufos.ru"
    const val K_SERVER = "app.server"
    const val K_BACKGROUND = "app.background"
    const val K_AUTOLOCK = "app.autolock_min"
    const val P2P_WAIT_MS = 5 * 60_000L
    // Ключи mesh — как у десктопа (mys_mesh/prefs.py).
    const val K_MESH = "mesh.enabled"
    const val K_MESH_RELAY = "mesh.relay"
    const val K_MESH_NODES = "mesh.nodes"
    const val K_MESH_PEERS = "mesh.peers"
    const val K_MESH_BLE = "mesh.ble"

    private lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var vault: Vault? by mutableStateOf(null)
        private set
    var p2p: P2PService? = null
        private set
    var central: CentralService? = null
        private set
    var mesh: MeshNode? = null
        private set
    var meshNet: MeshInternet? = null
        private set
    var ble: BleMesh? = null
        private set

    /** Растёт на любое изменение данных — экраны перечитывают vault. */
    val tick = mutableIntStateOf(0)

    /** P2P: беседы с живым каналом и сроки ожидания пира (epoch ms). */
    val online = mutableStateMapOf<Long, Boolean>()
    val waiting = mutableStateMapOf<Long, Long>()
    /** P2P: путь живого канала — "direct" (WebRTC) или "mesh"; нет записи — через сервер. */
    val path = mutableStateMapOf<Long, String>()
    /** «Центр», диалог: собеседник печатает — до этого момента (epoch ms). */
    val typing = mutableStateMapOf<Long, Long>()
    /** Хранилище только что создано — предложить вход по отпечатку (один раз). */
    var offerBiometric by mutableStateOf(false)

    /** Тексты ошибок для баннера. */
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    var dark by mutableStateOf(true)
        private set

    @Volatile var foreground = false
    @Volatile private var lastInteraction = SystemClock.elapsedRealtime()
    private var collectors: List<Job> = emptyList()
    private val connectJobs = ConcurrentHashMap<Long, Job>()

    val vaultDir: File get() = File(app.filesDir, "vault")

    fun init(ctx: Context) {
        app = ctx.applicationContext
        dark = prefs().getBoolean("dark", true)
    }

    fun changeTheme(value: Boolean) {
        dark = value
        prefs().edit().putBoolean("dark", value).apply()
    }

    // Тема — до разблокировки, поэтому вне vault; секретного в ней нет.
    private fun prefs() = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun changed() { tick.intValue++ }

    fun appContext(): Context = app

    fun setting(key: String, default: String): String = vault?.settings?.get(key) ?: default

    fun setSetting(key: String, value: String) { vault?.settings?.set(key, value) }

    fun serverUrl() = setting(K_SERVER, DEFAULT_SERVER)

    fun rendezvousUrl(server: String = serverUrl()): String {
        val s = server.trimEnd('/')
        return when {
            s.startsWith("https://") -> "wss://" + s.removePrefix("https://")
            s.startsWith("http://") -> "ws://" + s.removePrefix("http://")
            else -> s
        } + "/p2p"
    }

    /** STUN на хосте сервера (coturn, 3478/udp); в локалке и на эмуляторе хватает host-кандидатов. */
    private fun iceServers(server: String): List<String> {
        val host = server.substringAfter("://").substringBefore('/').substringBefore(':')
        val local = host == "localhost" || host.startsWith("127.") || host.startsWith("10.0.2.") || host.startsWith("192.168.")
        return if (local) emptyList() else listOf("stun:$host:3478")
    }

    fun backgroundEnabled() = setting(K_BACKGROUND, "1") == "1"

    fun setBackground(on: Boolean) {
        setSetting(K_BACKGROUND, if (on) "1" else "0")
        if (on) MysService.start(app) else MysService.stop(app)
    }

    fun autolockMinutes() = setting(K_AUTOLOCK, "10").toInt()

    /** Стартовые узлы: публичный узел нашего сервера + свои из настроек. */
    fun meshBootstrap(server: String = serverUrl()): List<String> =
        listOf(rendezvousUrl(server).removeSuffix("/p2p") + "/mesh") +
            setting(K_MESH_NODES, "").lines().map { it.trim() }.filter { it.startsWith("wss://") || it.startsWith("ws://") }

    /** Mesh-узел + интернет-связь с публичными узлами; выученные адреса — в vault. */
    private fun startMesh(v: Vault, server: String): MeshNode {
        val node = MeshNode(LinkCrypto(sodium, sodium), MeshConfig(relay = v.settings.get(K_MESH_RELAY) != "0"))
        node.learnUrls(v.settings.get(K_MESH_PEERS).orEmpty().lines().filter { it.isNotBlank() })
        node.onPeers = { runCatching { v.settings.set(K_MESH_PEERS, node.knownUrls.joinToString("\n")) } }
        val http = OkHttpClient()
        val net = MeshInternet(node, { meshBootstrap(server) }, { WsWire.connect(http, it) })
        net.start(scope)
        mesh = node
        meshNet = net
        if (v.settings.get(K_MESH_BLE) != "0") startBle()
        return node
    }

    /** Bluetooth-mesh (в фоне, пока жив процесс); без разрешений или с выключенным BT — молча нет. */
    fun startBle() {
        val node = mesh ?: return
        if (ble != null || !BleMesh.hasPermissions(app)) return
        ble = BleMesh(app, node, scope).takeIf { runCatching { it.start() }.getOrDefault(false) }
    }

    fun setBle(on: Boolean) {
        setSetting(K_MESH_BLE, if (on) "1" else "0")
        if (on) startBle() else { ble?.stop(); ble = null }
    }

    /** Vault открыт — поднять сервисы режимов и фон. */
    fun unlocked(v: Vault) {
        val server = v.settings.get(K_SERVER) ?: DEFAULT_SERVER
        val ice = iceServers(server)
        val node = if (v.settings.get(K_MESH) != "0") startMesh(v, server) else null
        val p = P2PService(
            VaultP2PStore(v), rendezvousUrl(server), Ristretto(sodium),
            directLinks = { WebRtcLink(app, ice) }, mesh = node,
        )
        val c = CentralService(VaultCentralStore(v))
        p2p = p
        central = c
        collectors = listOf(
            scope.launch {
                p.events.collect { e ->
                    when (e) {
                        is P2PEvent.State -> {
                            online[e.conversationId] = e.connected
                            if (!e.connected) path.remove(e.conversationId)
                        }
                        is P2PEvent.Path -> when {
                            e.mesh -> path[e.conversationId] = "mesh"
                            e.direct -> path[e.conversationId] = "direct"
                            else -> path.remove(e.conversationId)
                        }
                        is P2PEvent.Error -> errors.tryEmit(p2pMessage(e.error))
                        is P2PEvent.Message -> notifyIfBackground()
                    }
                    changed()
                }
            },
            scope.launch {
                c.events.collect { e ->
                    if (e is CentralEvent.Error) errors.tryEmit(centralMessage(e.error))
                    if (e is CentralEvent.Room && e.kind == "typing") typing[e.conversationId] = System.currentTimeMillis() + 5_000
                    if (e is CentralEvent.Message && e.messageId != 0L && v.messages.get(e.messageId)?.direction == "in") notifyIfBackground()
                    changed()
                }
            },
        )
        vault = v
        touch()
        if (c.hasSavedSession()) scope.launch {
            runCatching { c.resume() }.onFailure { errors.tryEmit(centralMessage(it)) }
            changed()
        }
        if (backgroundEnabled()) MysService.start(app)
    }

    /** Закрыть всё: сервисы, vault, фон. Job — дождаться закрытия vault. */
    fun lock(): Job? {
        val v = vault ?: return null
        val p = p2p
        val c = central
        val node = mesh
        ble?.stop()
        ble = null
        meshNet?.stop()
        meshNet = null
        mesh = null
        collectors.forEach { it.cancel() }
        connectJobs.values.forEach { it.cancel() }
        connectJobs.clear()
        p2p = null
        central = null
        vault = null
        online.clear()
        waiting.clear()
        path.clear()
        MysService.stop(app)
        return scope.launch {
            runCatching { p?.stop() }
            runCatching { node?.close() }
            runCatching { c?.stop() }
            v.close()
        }
    }

    fun touch() { lastInteraction = SystemClock.elapsedRealtime() }

    /** При возврате в приложение после бездействия — замок. */
    fun checkAutoLock() {
        if (vault == null) return
        val minutes = autolockMinutes()
        if (minutes > 0 && SystemClock.elapsedRealtime() - lastInteraction > minutes * 60_000L) lock()
    }

    /** Поднять P2P-канал (по фразе или реконнект); ждём пира до 5 минут, отмена — [cancelConnect]. */
    fun connectP2P(conversationId: Long, phrase: String?) {
        val p = p2p ?: return
        if (connectJobs[conversationId]?.isActive == true || p.hasSession(conversationId)) return
        waiting[conversationId] = System.currentTimeMillis() + P2P_WAIT_MS
        connectJobs[conversationId] = scope.launch {
            try {
                if (phrase != null) p.startSession(phrase, P2P_WAIT_MS) else p.reconnect(conversationId, P2P_WAIT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                errors.tryEmit(e.message ?: "не удалось выйти на связь") // нет сохранённой фразы
            } catch (e: Exception) {
                // сетевые/PAKE-ошибки уже пришли событием P2PEvent.Error
            } finally {
                waiting.remove(conversationId)
                connectJobs.remove(conversationId)
                changed()
            }
        }
    }

    fun cancelConnect(conversationId: Long) { connectJobs.remove(conversationId)?.cancel() }

    /** Запустить действие сервиса с ошибкой в баннер. */
    fun run(block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.tryEmit(centralMessage(e))
        } finally {
            changed()
        }
    }

    private fun notifyIfBackground() { if (!foreground) Notifications.newMessage(app) }

    fun p2pMessage(e: Throwable): String = when (e) {
        is PakeException -> "Неверная фраза или перехват — канал разорван"
        is PeerUnavailable -> "Собеседник не вышел на связь"
        is RendezvousException -> "Сервер связи недоступен"
        else -> e.message ?: e.javaClass.simpleName
    }

    fun centralMessage(e: Throwable): String = when (e) {
        is AuthException -> "Сессия истекла — войдите снова"
        is NetworkException -> "Нет связи с сервером"
        is ServerException -> when (e.code) {
            "not_found" -> "Комната с таким кодом не найдена"
            "forbidden" -> "Нет доступа: комната закрыта или личная"
            "bad_request" -> "Код комнаты — ровно 10 цифр"
            "rate_limited" -> "Слишком много попыток входа — подождите"
            "username_taken" -> "Это имя уже занято"
            "invalid_username" -> "Недопустимое имя пользователя"
            "weak_password" -> "Слишком простой пароль"
            "dialog" -> "В диалоге состав и открытость менять нельзя"
            "not_dialog" -> "Доступно только в диалоге"
            "not_eligible" -> "Диалог возможен только в комнате ровно на двух зарегистрированных"
            "no_proposal" -> "Предложение уже неактуально"
            "user_not_found" -> "Пользователь не найден"
            "self" -> "Нельзя выбрать самого себя"
            "creator" -> "Создатель не может покинуть комнату"
            "bad_image" -> "Нужна картинка PNG, JPEG или WebP"
            "too_large" -> "Картинка больше 5 МБ"
            "pending_invite" -> "Писать можно после принятия приглашения"
            else -> "Ошибка сервера: ${e.code}"
        }
        else -> e.message ?: e.javaClass.simpleName
    }
}
