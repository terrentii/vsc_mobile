package ru.mobile.mys.central

class NewMessage(
    val conversationId: Long, val direction: String, val body: ByteArray?, val status: String,
    val kind: String = "text", val filename: String? = null, val mimeType: String? = null,
    val sender: String? = null, val wireSeq: Long? = null, val clientMsgId: String? = null,
    val mediaRef: String? = null, val replyAuthor: String? = null, val replySnippet: String? = null,
    /** Серверное время (epoch ms); null — локальные часы. */
    val timestamp: Long? = null,
)

class StoredMessage(
    val id: Long, val conversationId: Long, val direction: String, val body: ByteArray?,
    val status: String, val wireSeq: Long?, val mediaRef: String?, val kind: String, val sender: String?,
)

/** Хранилище «Центра» (vault в :app, память в тестах). Беседы режима "centralized". */
interface CentralStore {
    fun setting(key: String): String?
    fun setSetting(key: String, value: String)
    fun deleteSetting(key: String)
    fun deleteSettings(prefix: String)

    /** Беседа комнаты (room_id = строка серверного id); create=false — только найти. */
    fun conversationForRoom(roomId: Long, title: String?, create: Boolean = true): Long?
    fun roomOf(conversationId: Long): Long
    /** Стереть все беседы «Центра» с сообщениями. */
    fun wipeConversations()

    fun add(m: NewMessage): Long
    fun get(messageId: Long): StoredMessage?
    fun findByWire(conversationId: Long, wireSeq: Long): StoredMessage?
    fun findOutByClientId(conversationId: Long, clientMsgId: String): StoredMessage?
    fun findUnconfirmedOutByBody(conversationId: Long, body: ByteArray): StoredMessage?
    fun markSent(messageId: Long, wireSeq: Long)
    fun setStatus(messageId: Long, status: String)
    fun setBody(messageId: Long, body: ByteArray)
    fun delete(messageId: Long)

    /** Свои сообщения, сохранённые «входящими» (до переустановки, из веба) → исходящие. Логин на сервере уникален. */
    fun claimOwn(username: String)
}
