package ru.mobile.mys.central

import java.net.URLConnection
import java.util.UUID
import org.json.JSONObject

/** Ссылка на сообщение, на которое отвечаем: серверный id + готовая цитата. */
class ReplyRef(val wire: Long, val sender: String, val snippet: String)

/**
 * Синк «Центра» — порт mys_centralized/sync.py: комнаты ↔ беседы, история по
 * курсору, дедуп (серверный id → client_msg_id → тело собственного эха),
 * оптимистичная отправка, правка/удаление, медиа с ленивой докачкой.
 * Курсор двигает только [syncHistory]; live-кадры лишь персистятся.
 */
class SyncEngine(
    private val store: CentralStore,
    private val rest: RestClient,
    private val onMessage: (conversationId: Long, messageId: Long) -> Unit = { _, _ -> },
    private val pageLimit: Int = 200,
    private val ownUsername: String? = null,
) {
    suspend fun syncRooms(): Map<Long, Long> = rest.listRooms().associate { it.id to remember(it) }

    suspend fun createRoom(name: String): Long = remember(rest.createRoom(name))

    suspend fun joinRoom(code: String): Long = remember(rest.joinRoom(code))

    private fun remember(room: Room): Long {
        room.code?.let { store.setSetting(Account.CODE_PREFIX + room.id, it) }
        return store.conversationForRoom(room.id, room.name)!!
    }

    suspend fun syncHistory(roomId: Long): Long {
        val conv = store.conversationForRoom(roomId, null)!!
        var cursor = store.setting(Account.CURSOR_PREFIX + roomId)?.toLongOrNull()
        while (true) {
            val (msgs, next) = rest.getMessages(roomId, cursor, pageLimit)
            if (msgs.isEmpty()) break
            var high = cursor ?: 0
            for (m in msgs) {
                ingest(conv, m)
                high = maxOf(high, m.id)
            }
            if (high > 0) store.setSetting(Account.CURSOR_PREFIX + roomId, high.toString())
            cursor = next ?: break
        }
        return conv
    }

    suspend fun syncAll(): Map<Long, Long> = syncRooms().also { rooms -> rooms.keys.forEach { syncHistory(it) } }

    fun ingestWs(frame: JSONObject): Long? {
        val roomId = frame.getLong("room_id")
        return ingest(store.conversationForRoom(roomId, null)!!, parseMessage(frame, roomId))
    }

    /** Live-правка: тело по серверному id. → conv или null. */
    fun applyWsEdit(frame: JSONObject): Long? {
        val conv = store.conversationForRoom(frame.getLong("room_id"), null, create = false) ?: return null
        val row = store.findByWire(conv, frame.getLong("id")) ?: return null
        store.setBody(row.id, (frame.str("body") ?: "").toByteArray())
        return conv
    }

    fun applyWsDelete(frame: JSONObject): Long? {
        val conv = store.conversationForRoom(frame.getLong("room_id"), null, create = false) ?: return null
        val row = store.findByWire(conv, frame.getLong("id")) ?: return null
        store.delete(row.id)
        return conv
    }

    /** pending → POST (идемпотентно по client_msg_id) → sent / failed; UI оповещается на каждом шаге. */
    suspend fun send(conv: Long, body: String, reply: ReplyRef? = null): Long {
        val roomId = store.roomOf(conv)
        val cid = newClientId()
        val id = store.add(NewMessage(conv, "out", body.toByteArray(), "pending", clientMsgId = cid,
            replyAuthor = reply?.sender, replySnippet = reply?.snippet))
        onMessage(conv, id)
        val msg = try {
            rest.postMessage(roomId, body, cid, replyTo = reply?.wire)
        } catch (e: Exception) {
            store.setStatus(id, "failed")
            onMessage(conv, id)
            throw e
        }
        store.markSent(id, msg.id)
        onMessage(conv, id)
        return id
    }

    suspend fun edit(messageId: Long, body: String) {
        val row = store.get(messageId) ?: throw IllegalArgumentException("сообщение не найдено")
        rest.editMessage(row.wireSeq ?: throw IllegalStateException("сообщение ещё не подтверждено сервером"), body)
        store.setBody(messageId, body.toByteArray())
    }

    suspend fun delete(messageId: Long) {
        val row = store.get(messageId) ?: return
        rest.deleteMessage(row.wireSeq ?: throw IllegalStateException("сообщение ещё не подтверждено сервером"))
        store.delete(messageId)
    }

    /** Загрузить файл, затем сообщение со ссылкой; байты уже в памяти — сохраняем сразу. */
    suspend fun sendFile(conv: Long, filename: String, mimeType: String, data: ByteArray): Long {
        require(data.size <= MAX_MEDIA_SIZE) { "файл больше ${MAX_MEDIA_SIZE / (1024 * 1024)} МБ" }
        val roomId = store.roomOf(conv)
        val up = rest.uploadMedia(roomId, filename, data, mimeType)
        val cid = newClientId()
        val id = store.add(NewMessage(conv, "out", data, "pending", kindForFilename(filename), filename, mimeType,
            clientMsgId = cid, mediaRef = up.filename))
        val msg = try {
            rest.postMessage(roomId, "", cid, media = up.filename)
        } catch (e: Exception) {
            store.setStatus(id, "failed")
            throw e
        }
        store.markSent(id, msg.id)
        return id
    }

    /** Байты вложения: из кэша или докачать и закэшировать. */
    suspend fun fetchMedia(messageId: Long): ByteArray {
        val row = store.get(messageId) ?: throw IllegalArgumentException("сообщение не найдено")
        row.body?.let { return it }
        val ref = row.mediaRef ?: throw IllegalStateException("нет вложения")
        return rest.downloadMedia(store.roomOf(row.conversationId), ref).also { store.setBody(messageId, it) }
    }

    private fun ingest(conv: Long, msg: RemoteMessage): Long? {
        if (store.findByWire(conv, msg.id) != null) return null
        msg.clientMsgId?.let { cid ->
            store.findOutByClientId(conv, cid)?.let { pending ->
                if (pending.wireSeq == null) store.markSent(pending.id, msg.id) // своё эхо
                return null
            }
        }
        if (ownUsername != null && msg.sender == ownUsername) {
            store.findUnconfirmedOutByBody(conv, msg.body.toByteArray())?.let {
                store.markSent(it.id, msg.id) // эхо без client_msg_id (веб-путь)
                return null
            }
        }
        val ts = epochMs(msg.createdAt)
        val id = if (msg.media != null) {
            val name = displayName(msg.media)
            store.add(NewMessage(conv, "in", null, "received", kindForFilename(msg.media), name,
                URLConnection.guessContentTypeFromName(name), msg.sender, msg.id, mediaRef = msg.media,
                replyAuthor = msg.reply?.sender, replySnippet = msg.reply?.body, timestamp = ts))
        } else {
            store.add(NewMessage(conv, "in", msg.body.toByteArray(), "received", sender = msg.sender, wireSeq = msg.id,
                replyAuthor = msg.reply?.sender, replySnippet = msg.reply?.body, timestamp = ts))
        }
        onMessage(conv, id)
        return id
    }

    private fun newClientId() = UUID.randomUUID().toString().replace("-", "")
}
