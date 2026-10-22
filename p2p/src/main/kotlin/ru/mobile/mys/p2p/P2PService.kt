package ru.mobile.mys.p2p

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import ru.mobile.mys.crypto.Ristretto

/**
 * Медленному серверу (рукопожатие soufos.ru бывает 18–30 с) — 60 с на соединение; ping каждые 20 с держит NAT/прокси
 * живыми, пока ждём пира минутами без кадров.
 */
fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build()

sealed interface P2PEvent {
    class Message(val conversationId: Long, val messageId: Long) : P2PEvent
    class State(val conversationId: Long, val connected: Boolean) : P2PEvent
    class Error(val conversationId: Long?, val error: Throwable) : P2PEvent
    /** Путь канала сменился: прямой (WebRTC) или через сервер. */
    class Path(val conversationId: Long, val direct: Boolean) : P2PEvent
}

/**
 * Оркестратор P2P — порт mys_decentralized/service.py: rendezvous → relay →
 * хендшейк → сессия, активные сессии по conversation_id, события для UI.
 */
class P2PService(
    private val store: P2PStore,
    private val rendezvousUrl: String,
    private val ristretto: Ristretto,
    private val http: OkHttpClient = defaultHttp(),
    private val connectTimeoutMs: Long = 300_000,
    // два обмена кадрами через relay; у перегруженного сервера кадр идёт ~12 с
    private val handshakeTimeoutMs: Long = 120_000,
    /** Фабрика прямого канала; null — только relay. */
    private val directLinks: (() -> DirectLink)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<Long, Session>()
    private val roles = ConcurrentHashMap<Long, Role>()
    private val links = ConcurrentHashMap<Long, DirectLink>()
    private val _events = MutableSharedFlow<P2PEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Сообщения уже в хранилище — события лишь будят UI. */
    val events: SharedFlow<P2PEvent> = _events

    fun hasSession(conversationId: Long) = sessions.containsKey(conversationId)

    fun roleOf(conversationId: Long): Role? = roles[conversationId]

    fun isDirect(conversationId: Long) = sessions[conversationId]?.isDirect == true

    /** Беседа по фразе без сети — сразу показать окно ожидания пира. */
    fun resolveConversation(phrase: String): Long = store.conversationFor(deriveRoomParams(phrase).first)

    /** Поднять канал по фразе; ждёт пира до [timeoutMs]. */
    suspend fun startSession(phrase: String, timeoutMs: Long = connectTimeoutMs): Long {
        val (roomId, prs) = deriveRoomParams(phrase)
        val id = store.conversationFor(roomId)
        store.setPrs(id, prs) // до сети: даже при таймауте канал реконнектится без фразы
        connect(id, roomId, prs, timeoutMs)
        return id
    }

    /** «Выйти на связь» без повторного ввода фразы. */
    suspend fun reconnect(conversationId: Long, timeoutMs: Long = connectTimeoutMs) {
        val (roomId, prs) = store.roomAndPrs(conversationId) ?: throw IllegalStateException(
            "для этого канала нет сохранённой фразы — откройте его заново через «+ Новый канал» с той же фразой",
        )
        connect(conversationId, roomId, prs, timeoutMs)
    }

    suspend fun send(conversationId: Long, text: String) = session(conversationId).send(text)

    suspend fun sendFile(conversationId: Long, filename: String, mimeType: String, data: ByteArray): Long =
        session(conversationId).sendFile(filename, mimeType, data)

    suspend fun stopSession(conversationId: Long) {
        roles.remove(conversationId)
        links.remove(conversationId)?.let { runCatching { it.close() } }
        sessions.remove(conversationId)?.let {
            it.close()
            _events.tryEmit(P2PEvent.State(conversationId, false))
        }
    }

    suspend fun stop() {
        for (id in sessions.keys.toList()) stopSession(id)
        scope.cancel()
    }

    private fun session(id: Long) = sessions[id] ?: throw NoSuchElementException("нет активной сессии для беседы $id")

    private suspend fun connect(id: Long, roomId: ByteArray, prs: ByteArray, timeoutMs: Long) {
        if (sessions.containsKey(id)) return // канал уже поднят
        var joined: Rendezvous? = null
        try {
            val rv = joinRendezvous(http, rendezvousUrl, roomId, timeoutMs).also { joined = it }
            val transport = RelayTransport(rv.ws)
            val result = withTimeoutOrNull(handshakeTimeoutMs) { handshake(transport, prs, rv.role, ristretto) }
                ?: throw PakeException("собеседник не завершил хендшейк")
            val link = directLinks?.invoke()
            val session = openSession(
                store.session(id), transport, result.sk, result.ratchetState,
                onMessage = { mid -> _events.tryEmit(P2PEvent.Message(id, mid)) },
                onDisconnect = { onDisconnect(id) },
                onSignal = { json -> link?.onSignal(json) },
                onDirectClosed = { _events.tryEmit(P2PEvent.Path(id, false)) },
            )
            sessions[id] = session
            roles[id] = rv.role
            if (link != null) startDirect(id, session, rv.role, link)
            session.start(scope)
            if (rv.role == Role.INITIATOR) runCatching { session.sendPrime() } // best-effort
            _events.tryEmit(P2PEvent.State(id, true))
        } catch (e: Exception) {
            joined?.ws?.close()
            if (e !is CancellationException) _events.tryEmit(P2PEvent.Error(id, e))
            throw e
        }
    }

    /**
     * Прямой канал поверх живой сессии. Обрыв — откат на relay (сессия жива);
     * INITIATOR повторяет offer через 10 с, затем с backoff до 2 мин.
     */
    private fun startDirect(id: Long, session: Session, role: Role, link: DirectLink) {
        links[id] = link
        var backoff = 10_000L
        link.start(
            initiator = role == Role.INITIATOR,
            signal = { json -> scope.launch { runCatching { session.sendSignal(json) } } },
            opened = { t ->
                if (sessions[id] === session) {
                    session.attachDirect(t)
                    backoff = 10_000L
                    _events.tryEmit(P2PEvent.Path(id, true))
                }
            },
            closed = {
                if (role == Role.INITIATOR) scope.launch {
                    delay(backoff)
                    backoff = minOf(backoff * 2, 120_000L)
                    if (sessions[id] === session) link.restart()
                }
            },
        )
    }

    private fun onDisconnect(id: Long) {
        links.remove(id)?.let { runCatching { it.close() } }
        if (sessions.remove(id) != null) {
            roles.remove(id)
            _events.tryEmit(P2PEvent.State(id, false))
        }
    }
}
