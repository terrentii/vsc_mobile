package ru.mobile.mys.storage

import ru.mobile.mys.central.CentralStore
import ru.mobile.mys.central.NewMessage
import ru.mobile.mys.central.StoredMessage

/** «Центр» в зашифрованном vault: беседы режима "centralized", room_id = строка серверного id. */
class VaultCentralStore(private val vault: Vault) : CentralStore {
    override fun setting(key: String) = vault.settings.get(key)
    override fun setSetting(key: String, value: String) = vault.settings.set(key, value)
    override fun deleteSetting(key: String) = vault.settings.delete(key)
    override fun deleteSettings(prefix: String) = vault.settings.deletePrefix(prefix)

    override fun conversationForRoom(roomId: Long, title: String?, create: Boolean): Long? {
        val key = roomId.toString().toByteArray()
        return vault.conversations.getByRoomId(key, MODE)?.id ?: if (create) vault.conversations.add(MODE, key, title) else null
    }

    override fun roomOf(conversationId: Long): Long =
        vault.conversations.get(conversationId)!!.roomId!!.decodeToString().toLong()

    override fun wipeConversations() = vault.conversations.list(MODE).forEach { vault.conversations.delete(it.id) }

    override fun add(m: NewMessage): Long = vault.messages.add(
        m.conversationId, m.direction, m.body, m.status, m.kind, m.filename, m.mimeType, m.sender,
        m.wireSeq, m.clientMsgId, m.mediaRef, m.replyAuthor, m.replySnippet, m.timestamp,
    )

    override fun claimOwn(username: String) = vault.messages.claimOwn(MODE, username)

    override fun forgetConversation(conversationId: Long) = vault.conversations.delete(conversationId) // с сообщениями

    override fun get(messageId: Long) = vault.messages.get(messageId)?.stored()
    override fun findByWire(conversationId: Long, wireSeq: Long) = vault.messages.findByWire(conversationId, wireSeq)?.stored()
    override fun findOutByClientId(conversationId: Long, clientMsgId: String) = vault.messages.findOutByClientId(conversationId, clientMsgId)?.stored()
    override fun findUnconfirmedOutByBody(conversationId: Long, body: ByteArray) = vault.messages.findUnconfirmedOutByBody(conversationId, body)?.stored()
    override fun markSent(messageId: Long, wireSeq: Long) = vault.messages.markSent(messageId, wireSeq)
    override fun setStatus(messageId: Long, status: String) = vault.messages.setStatus(messageId, status)
    override fun setBody(messageId: Long, body: ByteArray) = vault.messages.setBody(messageId, body)
    override fun delete(messageId: Long) = vault.messages.delete(messageId)

    private fun Message.stored() = StoredMessage(id, conversationId, direction, body, status, wireSeq, mediaRef, kind, sender)

    private companion object {
        const val MODE = "centralized"
    }
}
