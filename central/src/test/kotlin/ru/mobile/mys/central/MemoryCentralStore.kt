package ru.mobile.mys.central

/** CentralStore в памяти — для живых тестов. */
class MemoryCentralStore : CentralStore {
    class Row(val id: Long, val m: NewMessage) {
        var body: ByteArray? = m.body
        var status: String = m.status
        var wireSeq: Long? = m.wireSeq
        var direction: String = m.direction
        fun stored() = StoredMessage(id, m.conversationId, direction, body, status, wireSeq, m.mediaRef, m.kind, m.sender)
    }

    private val settings = HashMap<String, String>()
    private val convs = LinkedHashMap<Long, Pair<Long, String?>>() // conv -> (room, title)
    val rows = mutableListOf<Row>()
    private var next = 1L

    @Synchronized override fun setting(key: String) = settings[key]
    @Synchronized override fun setSetting(key: String, value: String) { settings[key] = value }
    @Synchronized override fun deleteSetting(key: String) { settings.remove(key) }
    @Synchronized override fun deleteSettings(prefix: String) { settings.keys.removeAll { it.startsWith(prefix) } }

    @Synchronized override fun conversationForRoom(roomId: Long, title: String?, create: Boolean): Long? =
        convs.entries.firstOrNull { it.value.first == roomId }?.key
            ?: if (create) next++.also { convs[it] = roomId to title } else null

    @Synchronized override fun roomOf(conversationId: Long) = convs.getValue(conversationId).first
    @Synchronized fun conversations() = convs.keys.toList()

    @Synchronized override fun wipeConversations() { rows.clear(); convs.clear() }

    @Synchronized override fun add(m: NewMessage): Long = next++.also { rows += Row(it, m) }
    @Synchronized override fun get(messageId: Long) = rows.find { it.id == messageId }?.stored()
    @Synchronized fun messages(conv: Long) = rows.filter { it.m.conversationId == conv }.map { it.stored() }

    @Synchronized override fun findByWire(conversationId: Long, wireSeq: Long) =
        rows.find { it.m.conversationId == conversationId && it.wireSeq == wireSeq }?.stored()

    @Synchronized override fun findOutByClientId(conversationId: Long, clientMsgId: String) =
        rows.find { it.m.conversationId == conversationId && it.direction == "out" && it.m.clientMsgId == clientMsgId }?.stored()

    @Synchronized override fun findUnconfirmedOutByBody(conversationId: Long, body: ByteArray) =
        rows.find { it.m.conversationId == conversationId && it.direction == "out" && it.wireSeq == null && it.body?.contentEquals(body) == true }?.stored()

    @Synchronized override fun markSent(messageId: Long, wireSeq: Long) { rows.first { it.id == messageId }.apply { this.wireSeq = wireSeq; status = "sent" } }
    @Synchronized override fun setStatus(messageId: Long, status: String) { rows.first { it.id == messageId }.status = status }
    @Synchronized override fun setBody(messageId: Long, body: ByteArray) { rows.first { it.id == messageId }.body = body }
    @Synchronized override fun delete(messageId: Long) { rows.removeAll { it.id == messageId } }
    @Synchronized override fun claimOwn(username: String) {
        rows.filter { it.direction == "in" && it.m.sender == username }.forEach { it.direction = "out"; it.status = "sent" }
    }
}
