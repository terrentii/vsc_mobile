package ru.mobile.mys.p2p

import java.io.ByteArrayOutputStream
import java.text.Normalizer
import org.bouncycastle.crypto.digests.Blake2bDigest
import ru.mobile.mys.crypto.hkdf

/*
 * Wire-протокол МЫС — порт mys_decentralized/protocol.py (байт-в-байт).
 * Кадр: u8 type | u8 flags | u32 length | payload (big-endian). Сервер видит
 * только room_id и непрозрачные кадры.
 */

typealias Candidate = kotlin.Pair<String, Int>

private val NO_SALT = ByteArray(0)

/** BLAKE2b с personalization; Python дополняет `person` нулями до 16 байт. */
internal fun blake2b(data: ByteArray, size: Int, person: String): ByteArray {
    val d = Blake2bDigest(null, size, null, person.toByteArray().copyOf(16))
    d.update(data, 0, data.size)
    return ByteArray(size).also { d.doFinal(it, 0) }
}

/** Фраза → (room_id, prs): room_id уходит серверу, prs (вход CPace) — нет. */
fun deriveRoomParams(phrase: String): kotlin.Pair<ByteArray, ByteArray> {
    // ponytail: trim() ≈ Python str.strip(); расходятся лишь на экзотических пробелах
    // (после NFKC почти все сводятся к U+0020). Векторы покрывают обычные случаи.
    val norm = Normalizer.normalize(phrase, Normalizer.Form.NFKC).trim().toByteArray()
    val seed = blake2b(norm, 32, "mys-phrase")
    return hkdf(seed, 32, NO_SALT, "mys-room-id".toByteArray()) to hkdf(seed, 32, NO_SALT, "mys-pake-prs".toByteArray())
}

enum class MsgType(val code: Int) {
    HELLO(1), PAIR(2), PUNCH(3), PUNCH_ACK(4), RELAY(5), PAKE(6), CONFIRM(7), DATA(8), PEER_LEFT(9);

    companion object {
        fun of(code: Int): MsgType? = entries.firstOrNull { it.code == code }
    }
}

/** Порядок важен: ordinal = байт на проводе. INITIATOR — Alice в ratchet. */
enum class Role { INITIATOR, RESPONDER }

internal class Writer {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = apply { require(v in 0..0xFF); out.write(v) }
    fun u16(v: Int) = apply { require(v in 0..0xFFFF); out.write(v ushr 8); out.write(v) }
    fun u32(v: Int) = apply { for (s in intArrayOf(24, 16, 8, 0)) out.write(v ushr s) }
    fun u64(v: Long) = apply { for (s in 56 downTo 0 step 8) out.write((v ushr s).toInt()) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun var8(b: ByteArray) = u8(b.size).bytes(b)
    fun var16(b: ByteArray) = u16(b.size).bytes(b)
    fun candidates(c: List<Candidate>) = apply {
        u8(c.size)
        for ((host, port) in c) var16(host.toByteArray()).u16(port)
    }
    fun done(): ByteArray = out.toByteArray()
}

internal class Reader(private val b: ByteArray) {
    private var pos = 0
    private fun need(n: Int) { if (n < 0 || pos + n > b.size) throw TransportException("усечённое поле") }
    fun u8(): Int { need(1); return b[pos++].toInt() and 0xff }
    fun u16(): Int = (u8() shl 8) or u8()
    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
    fun u64(): Long = (u32() shl 32) or u32()
    fun take(n: Int): ByteArray { need(n); return b.copyOfRange(pos, pos + n).also { pos += n } }
    fun rest(): ByteArray = take(b.size - pos)
    fun var8() = take(u8())
    fun var16() = take(u16())
    fun candidates(): List<Candidate> = List(u8()) { var16().decodeToString() to u16() }
}

sealed class Msg(val type: MsgType) {
    abstract fun payload(): ByteArray

    class Hello(val roomId: ByteArray, val candidates: List<Candidate> = emptyList()) : Msg(MsgType.HELLO) {
        override fun payload() = Writer().var16(roomId).candidates(candidates).done()
    }

    class Pair(val role: Role, val peerCandidates: List<Candidate> = emptyList()) : Msg(MsgType.PAIR) {
        override fun payload() = Writer().u8(role.ordinal).candidates(peerCandidates).done()
    }

    /** CPace-точка Y — открыта по дизайну PAKE. */
    class Pake(val y: ByteArray, val ad: ByteArray = ByteArray(0)) : Msg(MsgType.PAKE) {
        override fun payload() = Writer().var16(y).var16(ad).done()
    }

    class Confirm(val mac: ByteArray) : Msg(MsgType.CONFIRM) {
        override fun payload() = mac
    }

    class Data(val sealed: ByteArray) : Msg(MsgType.DATA) {
        override fun payload() = sealed
    }

    /** Кадр для пира целиком; сервер пересылает его, не читая. */
    class Relay(val inner: ByteArray) : Msg(MsgType.RELAY) {
        override fun payload() = inner
    }

    object Punch : Msg(MsgType.PUNCH) { override fun payload() = ByteArray(0) }
    object PunchAck : Msg(MsgType.PUNCH_ACK) { override fun payload() = ByteArray(0) }

    /** Сервер → оставшемуся: второй участник отключился. */
    object PeerLeft : Msg(MsgType.PEER_LEFT) { override fun payload() = ByteArray(0) }
}

class Frame(val type: MsgType, val flags: Int, val payload: ByteArray, val consumed: Int)

fun encodeFrame(type: MsgType, payload: ByteArray, flags: Int = 0): ByteArray =
    Writer().u8(type.code).u8(flags and 0xFF).u32(payload.size).bytes(payload).done()

/** Разобрать первый кадр из [buf]; `consumed` — его длина (хвост — следующий кадр). */
fun decodeFrame(buf: ByteArray): Frame {
    if (buf.size < 6) throw TransportException("усечённый заголовок кадра")
    val r = Reader(buf)
    val rawType = r.u8()
    val flags = r.u8()
    val length = r.u32()
    if (6 + length > buf.size) throw TransportException("неполный payload кадра")
    val type = MsgType.of(rawType) ?: throw TransportException("неизвестный тип кадра $rawType")
    return Frame(type, flags, r.take(length.toInt()), 6 + length.toInt())
}

fun encodeMessage(msg: Msg): ByteArray = encodeFrame(msg.type, msg.payload())

fun decodeMessage(buf: ByteArray): Msg {
    val f = decodeFrame(buf)
    val r = Reader(f.payload)
    return when (f.type) {
        MsgType.HELLO -> Msg.Hello(r.var16(), r.candidates())
        MsgType.PAIR -> {
            if (f.payload.isEmpty()) throw TransportException("пустой PAIR")
            val role = Role.entries.getOrNull(r.u8()) ?: throw TransportException("неизвестная роль")
            Msg.Pair(role, r.candidates())
        }
        MsgType.PAKE -> Msg.Pake(r.var16(), r.var16())
        MsgType.CONFIRM -> Msg.Confirm(f.payload)
        MsgType.DATA -> Msg.Data(f.payload)
        MsgType.RELAY -> Msg.Relay(f.payload)
        MsgType.PUNCH -> Msg.Punch
        MsgType.PUNCH_ACK -> Msg.PunchAck
        MsgType.PEER_LEFT -> Msg.PeerLeft
    }
}
