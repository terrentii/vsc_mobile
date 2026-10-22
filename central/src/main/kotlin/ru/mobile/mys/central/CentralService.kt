package ru.mobile.mys.central

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

sealed interface CentralEvent {
    /** [messageId] 0 — «содержимое беседы изменилось» (правка/удаление). */
    class Message(val conversationId: Long, val messageId: Long) : CentralEvent
    /** synced | connected | disconnected | unauthorized */
    class State(val state: String) : CentralEvent
    class Error(val error: Throwable) : CentralEvent
}

/** Оркестратор «Центра» — порт mys_centralized/service.py на корутинах. */
class CentralService(
    private val store: CentralStore,
    // медленному серверу (ответ soufos.ru бывает 18–30 с) — 60 с на соединение и ответ; ping держит /ws и NAT
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build(),
    private val roomPollMs: Long = 20_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Синк, live-кадры, опрос, создание/вход — по одному: иначе гонка дедупа и бесед. */
    private val syncLock = Mutex()
    private var rest: RestClient? = null
    private var sync: SyncEngine? = null
    private var live: List<Job> = emptyList()
    private var knownRooms: Set<Long> = emptySet()
    private val _events = MutableSharedFlow<CentralEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: SharedFlow<CentralEvent> = _events

    @Volatile var session: Session? = null
        private set

    /** /ws прошёл ready (для тестов и индикатора связи). */
    @Volatile var liveConnected = false
        private set

    fun hasSavedSession() = Account.load(store) != null

    fun wipeOnLogout() = store.setting(Account.K_WIPE) == "1"

    fun setWipeOnLogout(value: Boolean) = store.setSetting(Account.K_WIPE, if (value) "1" else "0")

    suspend fun login(serverUrl: String, username: String, password: String, register: Boolean = false): Session {
        val r = RestClient(serverUrl, http = http)
        val s = if (register) r.register(username, password) else r.login(username, password)
        activate(r, s)
        return s
    }

    /** Восстановить сохранённую сессию; протухший токен — сессия стирается, AuthException наружу. */
    suspend fun resume(): Session? {
        val s = Account.load(store) ?: return null
        try {
            activate(RestClient(s.serverUrl, s.token, http), s)
        } catch (e: AuthException) {
            teardown()
            Account.clear(store)
            throw e
        }
        return s
    }

    suspend fun logout() {
        val r = rest
        teardown()
        runCatching { r?.logout() } // best-effort: токен на сервере
        Account.clear(store)
        if (wipeOnLogout()) Account.wipeCache(store)
    }

    suspend fun createRoom(name: String): Long = syncLock.withLock { engine().createRoom(name) }

    suspend fun joinRoom(code: String): Long = syncLock.withLock { engine().joinRoom(code) }

    fun roomCode(conversationId: Long): String? = store.setting(Account.CODE_PREFIX + store.roomOf(conversationId))

    /** Принудительный дотяг комнат и истории. */
    suspend fun resync() { syncLock.withLock { engine().syncAll() } }

    suspend fun send(conversationId: Long, text: String, reply: ReplyRef? = null) = engine().send(conversationId, text, reply)
    suspend fun edit(messageId: Long, body: String) = engine().edit(messageId, body)
    suspend fun delete(messageId: Long) = engine().delete(messageId)
    suspend fun sendFile(conversationId: Long, filename: String, mimeType: String, data: ByteArray) =
        engine().sendFile(conversationId, filename, mimeType, data)
    suspend fun fetchMedia(messageId: Long) = engine().fetchMedia(messageId)

    suspend fun stop() {
        teardown()
        scope.cancel()
    }

    private fun engine() = sync ?: throw IllegalStateException("нет активной сессии «Центра»")

    private suspend fun activate(r: RestClient, s: Session) {
        teardown()
        rest = r
        session = s
        Account.save(store, s)
        val engine = SyncEngine(store, r, onMessage = { c, m -> _events.tryEmit(CentralEvent.Message(c, m)) }, ownUsername = s.username)
        sync = engine
        knownRooms = syncLock.withLock { engine.syncAll() }.keys
        _events.tryEmit(CentralEvent.State("synced"))
        live = listOfNotNull(
            wsUrlFor(s.serverUrl)?.let { url -> scope.launch { wsLoop(url, s.token, engine) } },
            if (roomPollMs > 0) scope.launch { pollLoop(engine) } else null,
        )
    }

    private suspend fun wsLoop(url: String, token: String, engine: SyncEngine) {
        try {
            WsClient(url, token, http).events().collect { e ->
                when (e.optString("type")) {
                    "ready" -> {
                        // после каждого (пере)подключения добираем пропущенное за офлайн
                        runCatching { syncLock.withLock { engine.syncAll() } }
                        liveConnected = true
                        _events.tryEmit(CentralEvent.State("connected"))
                    }
                    "message" -> syncLock.withLock { engine.ingestWs(e) }
                    "message_edited" -> syncLock.withLock { engine.applyWsEdit(e) }?.let { _events.tryEmit(CentralEvent.Message(it, 0)) }
                    "message_deleted" -> syncLock.withLock { engine.applyWsDelete(e) }?.let { _events.tryEmit(CentralEvent.Message(it, 0)) }
                }
            }
        } catch (e: AuthException) {
            liveConnected = false
            _events.tryEmit(CentralEvent.State("unauthorized"))
            _events.tryEmit(CentralEvent.Error(e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            liveConnected = false
            _events.tryEmit(CentralEvent.State("disconnected"))
            _events.tryEmit(CentralEvent.Error(e))
        }
    }

    /** Сервер не пушит события о новых комнатах — дотягиваем список периодически. */
    private suspend fun pollLoop(engine: SyncEngine) {
        while (true) {
            delay(roomPollMs)
            try {
                val now = syncLock.withLock { engine.syncAll() }.keys
                if (now != knownRooms) {
                    knownRooms = now
                    _events.tryEmit(CentralEvent.State("synced"))
                }
            } catch (e: AuthException) {
                _events.tryEmit(CentralEvent.State("unauthorized"))
                _events.tryEmit(CentralEvent.Error(e))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // сетевой сбой опроса — переживём до следующего тика
            }
        }
    }

    private suspend fun teardown() {
        live.forEach { it.cancelAndJoin() }
        live = emptyList()
        liveConnected = false
        sync = null
        rest = null
        session = null
    }
}
