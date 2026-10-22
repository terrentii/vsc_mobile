package ru.mobile.mys.p2p

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import ru.mobile.mys.crypto.RatchetState
import ru.mobile.mys.crypto.Ristretto
import ru.mobile.mys.crypto.deserializeState
import ru.mobile.mys.crypto.serializeState

object Vectors {
    val root: JSONObject by lazy { JSONObject(Vectors::class.java.getResource("/vectors.json")!!.readText()) }
}

val ristretto by lazy { Ristretto(LazySodiumJava(SodiumJava())) }

fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun JSONObject.bytes(key: String): ByteArray = getString(key).unhex()

/** Дуплекс без сети: close() одной стороны обрывает recv() другой (как у десктопа). */
class InMemoryTransport private constructor(
    private val inbox: Channel<ByteArray>,
    private val outbox: Channel<ByteArray>,
) : Transport {
    override suspend fun send(data: ByteArray) {
        try { outbox.send(data) } catch (e: ClosedSendChannelException) { throw TransportException("send в закрытый транспорт") }
    }

    override suspend fun recv(): ByteArray =
        try { inbox.receive() } catch (e: ClosedReceiveChannelException) { throw TransportException("транспорт закрыт") }

    override suspend fun close() {
        outbox.close()
        inbox.close()
    }

    companion object {
        fun pair(): kotlin.Pair<InMemoryTransport, InMemoryTransport> {
            val ab = Channel<ByteArray>(Channel.UNLIMITED)
            val ba = Channel<ByteArray>(Channel.UNLIMITED)
            return InMemoryTransport(ba, ab) to InMemoryTransport(ab, ba)
        }
    }
}

/** P2PStore в памяти; ratchet хранится сериализованным — как в БД. */
class MemoryStore : P2PStore {
    class Stored(val id: Long, val direction: String, val body: ByteArray, var status: String, val kind: String, val filename: String?)
    class Conv(val id: Long, val roomId: ByteArray) {
        var prs: ByteArray? = null
        var ratchet: ByteArray? = null
        val messages = mutableListOf<Stored>()
    }

    private var nextId = 1L
    private val convs = mutableListOf<Conv>()

    @Synchronized fun conv(id: Long) = convs.first { it.id == id }
    @Synchronized fun messages(id: Long) = conv(id).messages.toList()

    @Synchronized override fun conversationFor(roomId: ByteArray): Long =
        (convs.find { it.roomId.contentEquals(roomId) } ?: Conv(nextId++, roomId).also { convs += it }).id

    @Synchronized override fun setPrs(conversationId: Long, prs: ByteArray) { conv(conversationId).prs = prs }

    @Synchronized override fun roomAndPrs(conversationId: Long) =
        conv(conversationId).let { c -> c.prs?.let { c.roomId to it } }

    @Synchronized private fun add(conv: Long, direction: String, body: ByteArray, status: String, kind: String, filename: String?): Long {
        val m = Stored(nextId++, direction, body, status, kind, filename)
        conv(conv).messages += m
        return m.id
    }

    override fun session(conversationId: Long): SessionStore = object : SessionStore {
        val store = this@MemoryStore
        override fun loadRatchet(): RatchetState? = synchronized(store) { conv(conversationId).ratchet?.let(::deserializeState) }
        override fun saveRatchet(state: RatchetState) = synchronized(store) { conv(conversationId).ratchet = serializeState(state) }
        override fun addOutgoing(body: ByteArray, status: String, kind: String, filename: String?, mimeType: String?) =
            add(conversationId, "out", body, status, kind, filename)
        override fun setStatus(messageId: Long, status: String) =
            synchronized(store) { conv(conversationId).messages.first { it.id == messageId }.status = status }
        override fun receive(body: ByteArray, state: RatchetState, kind: String, filename: String?, mimeType: String?): Long =
            synchronized(store) { saveRatchet(state); add(conversationId, "in", body, "received", kind, filename) }
    }
}

/** Ждать условия (сеть/корутины асинхронны). */
suspend fun eventually(timeoutMs: Long = 10_000, check: () -> Boolean) = withTimeout(timeoutMs) {
    while (!check()) delay(20)
}
