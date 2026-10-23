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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
    /** room_updated / room_removed / typing / read / presence; [login] — кто (для typing/read/presence). */
    class Room(val conversationId: Long, val kind: String, val login: String? = null) : CentralEvent
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
    private val _metas = MutableStateFlow<Map<Long, RoomMeta>>(emptyMap())
    private val avatars = java.util.concurrent.ConcurrentHashMap<Pair<Long, Int>, java.util.Optional<ByteArray>>()
    @Volatile private var ws: WsClient? = null

    /** conversationId → комната глазами пользователя (диалог, название, аватар, собеседник). */
    val metas: StateFlow<Map<Long, RoomMeta>> = _metas

    fun meta(conversationId: Long): RoomMeta? = _metas.value[conversationId]

    private fun putMeta(conversationId: Long, m: RoomMeta) = _metas.update { it + (conversationId to m) }

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

    // -- настройки комнат, диалоги, аватарки ----------------------------------

    /** Операция над комнатой беседы; null — комнаты у нас больше нет (стёрта локально). */
    suspend fun roomOp(conversationId: Long, op: suspend RestClient.(roomId: Long) -> RoomMeta?): RoomMeta? {
        val r = rest ?: throw IllegalStateException("нет активной сессии «Центра»")
        val m = r.op(store.roomOf(conversationId))
        if (m == null) {
            forget(conversationId)
            return null
        }
        syncLock.withLock { engine().rememberMeta(m) }
        _events.tryEmit(CentralEvent.Room(conversationId, "room_updated"))
        return m
    }

    /** Свежая мета с составом (для экрана настроек). */
    suspend fun loadRoom(conversationId: Long): RoomMeta? = try {
        roomOp(conversationId) { getRoom(it) }
    } catch (e: ServerException) {
        if (e.code == "forbidden" || e.code == "not_found") { forget(conversationId); null } else throw e
    }

    /** «Написать пользователю» → беседа (новая или уже существующая). */
    suspend fun invite(login: String): Long {
        val m = (rest ?: throw IllegalStateException("нет активной сессии «Центра»")).invite(login)
        val conv = syncLock.withLock { engine().rememberMeta(m).also { engine().syncHistory(m.id) } }
        _events.tryEmit(CentralEvent.State("synced"))
        return conv
    }

    /** PNG аватарки глазами пользователя; кэш по (беседа, rev). */
    suspend fun avatar(conversationId: Long): ByteArray? {
        val rev = meta(conversationId)?.avatarRev ?: 0
        if (rev == 0) return null
        val key = conversationId to rev
        avatars[key]?.let { return it.orElse(null) }
        val bytes = rest?.getAvatar(store.roomOf(conversationId))
        avatars[key] = java.util.Optional.ofNullable(bytes)
        return bytes
    }

    /** «Печатает…» собеседнику (не ждёт; без соединения — пропуск). */
    fun typing(conversationId: Long) {
        ws?.send(org.json.JSONObject().put("type", "typing").put("room_id", store.roomOf(conversationId)))
    }

    /** Отметка прочтения до wire (не ждёт; ошибки сети — молча). */
    fun markRead(conversationId: Long, wire: Long) {
        val r = rest ?: return
        scope.launch { runCatching { r.markRead(store.roomOf(conversationId), wire) } }
    }

    suspend fun privacy(): Pair<Boolean, Boolean> = (rest ?: throw IllegalStateException("нет сессии")).me()

    suspend fun setPrivacy(shareRead: Boolean, shareOnline: Boolean) =
        (rest ?: throw IllegalStateException("нет сессии")).setMe(shareRead, shareOnline)

    private fun forget(conversationId: Long) {
        val room = runCatching { store.roomOf(conversationId) }.getOrNull()
        store.forgetConversation(conversationId)
        room?.let { store.deleteSetting(Account.CURSOR_PREFIX + it); store.deleteSetting(Account.CODE_PREFIX + it) }
        _metas.update { it - conversationId }
        _events.tryEmit(CentralEvent.Room(conversationId, "room_removed"))
        _events.tryEmit(CentralEvent.State("synced"))
    }

    private suspend fun refreshRoom(roomId: Long, removed: Boolean) {
        val conv = store.conversationForRoom(roomId, null, create = false)
        if (!removed) {
            val m = try { rest?.getRoom(roomId) } catch (e: ServerException) { null }
            if (m != null) {
                val c = syncLock.withLock { engine().rememberMeta(m) }
                _events.tryEmit(CentralEvent.Room(c, "room_updated"))
                _events.tryEmit(CentralEvent.State("synced"))
                return
            }
        }
        conv?.let { forget(it) }
    }

    private fun peerEvent(kind: String, e: org.json.JSONObject) {
        val conv = store.conversationForRoom(e.getLong("room_id"), null, create = false) ?: return
        val login = e.optString("login")
        meta(conv)?.takeIf { it.peer == login }?.let { m ->
            when (kind) {
                "read" -> putMeta(conv, m.copy(peerRead = maxOf(m.peerRead ?: 0, e.optLong("message_id"))))
                "presence" -> putMeta(conv, m.copy(peerOnline = e.optBoolean("online")))
            }
        }
        _events.tryEmit(CentralEvent.Room(conv, kind, login))
    }

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
        store.claimOwn(s.username)
        val engine = SyncEngine(
            store, r, onMessage = { c, m -> _events.tryEmit(CentralEvent.Message(c, m)) }, ownUsername = s.username,
            onRoom = { c, m -> putMeta(c, m) },
        )
        sync = engine
        // Сеть упала на первом синке (разблокировка без связи, перезапуск сервера) — всё равно
        // поднимаем /ws и опрос: «ready» и опрос догонят историю, иначе «Центр» молчал бы до
        // повторной разблокировки (ни сообщений, ни уведомлений, ни «в сети»).
        knownRooms = try {
            syncLock.withLock { engine.syncAll() }.keys
        } catch (e: AuthException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.tryEmit(CentralEvent.Error(e))
            emptySet()
        }
        _events.tryEmit(CentralEvent.State("synced"))
        live = listOfNotNull(
            wsUrlFor(s.serverUrl)?.let { url -> scope.launch { wsLoop(url, s.token, engine) } },
            if (roomPollMs > 0) scope.launch { pollLoop(engine) } else null,
        )
    }

    private suspend fun wsLoop(url: String, token: String, engine: SyncEngine) {
        try {
            val client = WsClient(url, token, http).also { ws = it }
            client.events().collect { e ->
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
                    "room_updated", "room_removed" -> refreshRoom(e.getLong("room_id"), e.optString("type") == "room_removed")
                    "typing", "read", "presence" -> peerEvent(e.optString("type"), e)
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
        ws = null
        _metas.value = emptyMap()
    }
}
