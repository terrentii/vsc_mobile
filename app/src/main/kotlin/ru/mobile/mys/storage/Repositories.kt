package ru.mobile.mys.storage

import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import ru.mobile.mys.crypto.RatchetState
import ru.mobile.mys.crypto.deserializeState
import ru.mobile.mys.crypto.serializeState

class Conversation(
    val id: Long, val mode: String, val roomId: ByteArray?, val title: String?,
    val createdAt: Long, val p2pPrs: ByteArray?,
)

class Message(
    val id: Long, val conversationId: Long, val direction: String, val body: ByteArray,
    val status: String, val kind: String, val filename: String?, val mimeType: String?,
    val sender: String?, val wireSeq: Long?, val clientMsgId: String?,
    val sentAt: Long?, val receivedAt: Long?,
)

class SettingsRepo internal constructor(private val db: SQLiteDatabase) {
    fun get(key: String): String? = db.select("SELECT value FROM settings WHERE key=?", key) { it.getString(0) }.firstOrNull()

    fun set(key: String, value: String) = db.execSQL(
        "INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        arrayOf(key, value),
    )

    fun delete(key: String) = db.execSQL("DELETE FROM settings WHERE key=?", arrayOf(key))

    fun deletePrefix(prefix: String) =
        db.execSQL("DELETE FROM settings WHERE substr(key, 1, ?)=?", arrayOf(prefix.length, prefix))
}

class ConversationsRepo internal constructor(private val db: SQLiteDatabase) {
    private val cols = "SELECT id, mode, room_id, title, created_at, p2p_prs FROM conversations"
    private fun row(c: Cursor) = Conversation(c.getLong(0), c.getString(1), c.blobOrNull(2), c.stringOrNull(3), c.getLong(4), c.blobOrNull(5))

    fun add(mode: String, roomId: ByteArray? = null, title: String? = null): Long = db.insertRow(
        "conversations", "mode" to mode, "room_id" to roomId, "title" to title,
        "created_at" to System.currentTimeMillis(),
    )

    fun get(id: Long): Conversation? = db.select("$cols WHERE id=?", id) { row(it) }.firstOrNull()

    fun getByRoomId(roomId: ByteArray, mode: String): Conversation? =
        db.select("$cols WHERE room_id=? AND mode=?", roomId, mode) { row(it) }.firstOrNull()

    fun list(mode: String? = null): List<Conversation> =
        if (mode == null) db.select("$cols ORDER BY id") { row(it) }
        else db.select("$cols WHERE mode=? ORDER BY id", mode) { row(it) }

    fun setPrs(id: Long, prs: ByteArray) = db.execSQL("UPDATE conversations SET p2p_prs=? WHERE id=?", arrayOf(prs, id))

    fun rename(id: Long, title: String) = db.execSQL("UPDATE conversations SET title=? WHERE id=?", arrayOf(title, id))

    /** Беседа вместе с сообщениями и состоянием ratchet. */
    fun delete(id: Long) = db.tx {
        db.execSQL("DELETE FROM messages WHERE conversation_id=?", arrayOf(id))
        db.execSQL("DELETE FROM ratchet_state WHERE conversation_id=?", arrayOf(id))
        db.execSQL("DELETE FROM conversations WHERE id=?", arrayOf(id))
    }
}

class MessagesRepo internal constructor(private val db: SQLiteDatabase) {
    private val cols = "SELECT id, conversation_id, direction, body, status, kind, filename, mime_type," +
        " sender, wire_seq, client_msg_id, sent_at, received_at FROM messages"

    private fun row(c: Cursor) = Message(
        c.getLong(0), c.getLong(1), c.getString(2), c.blobOrNull(3) ?: ByteArray(0), c.getString(4),
        c.getString(5), c.stringOrNull(6), c.stringOrNull(7), c.stringOrNull(8), c.longOrNull(9),
        c.stringOrNull(10), c.longOrNull(11), c.longOrNull(12),
    )

    fun add(
        conversationId: Long, direction: String, body: ByteArray, status: String,
        kind: String = "text", filename: String? = null, mimeType: String? = null,
        sender: String? = null, wireSeq: Long? = null, clientMsgId: String? = null,
    ): Long {
        val now = System.currentTimeMillis()
        return db.insertRow(
            "messages", "conversation_id" to conversationId, "direction" to direction, "body" to body,
            "status" to status, "kind" to kind, "filename" to filename, "mime_type" to mimeType,
            "sender" to sender, "wire_seq" to wireSeq, "client_msg_id" to clientMsgId,
            (if (direction == "out") "sent_at" else "received_at") to now,
        )
    }

    fun get(id: Long): Message? = db.select("$cols WHERE id=?", id) { row(it) }.firstOrNull()

    fun list(conversationId: Long): List<Message> = db.select("$cols WHERE conversation_id=? ORDER BY id", conversationId) { row(it) }

    fun setStatus(id: Long, status: String) = db.execSQL("UPDATE messages SET status=? WHERE id=?", arrayOf(status, id))

    fun deleteForConversation(conversationId: Long) = db.execSQL("DELETE FROM messages WHERE conversation_id=?", arrayOf(conversationId))
}

class RatchetRepo internal constructor(private val db: SQLiteDatabase) {
    fun save(conversationId: Long, state: RatchetState) = db.execSQL(
        "INSERT INTO ratchet_state(conversation_id, state_blob, updated_at) VALUES(?, ?, ?)" +
            " ON CONFLICT(conversation_id) DO UPDATE SET state_blob=excluded.state_blob, updated_at=excluded.updated_at",
        arrayOf(conversationId, serializeState(state), System.currentTimeMillis()),
    )

    fun load(conversationId: Long): RatchetState? =
        db.select("SELECT state_blob FROM ratchet_state WHERE conversation_id=?", conversationId) { deserializeState(it.getBlob(0)) }
            .firstOrNull()

    fun delete(conversationId: Long) = db.execSQL("DELETE FROM ratchet_state WHERE conversation_id=?", arrayOf(conversationId))
}
