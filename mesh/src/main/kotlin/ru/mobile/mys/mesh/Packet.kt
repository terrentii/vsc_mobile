package ru.mobile.mys.mesh

const val MESH_VERSION = 1
const val RREQ = 1
const val RREP = 2
const val DATA = 3
const val RERR = 4
const val ID_LEN = 16
const val TAG_LEN = 32
const val NONCE_LEN = 16
const val HEADER_LEN = 4 + ID_LEN
const val MAX_DATA = 65_600
const val MAX_PACKET = HEADER_LEN + MAX_DATA
const val TTL_MAX = 8

private val EMPTY = ByteArray(0)

/** Неверный пакет/линк — соединение с этим соседом рвётся. */
class MeshProtocolException(message: String) : Exception(message)

/** Mesh-пакет: `version | type | ttl | hops | id(16) | body` (порт mys_mesh/packet.py). */
class Packet(
    val type: Int, val id: ByteArray, val ttl: Int = 0, val hops: Int = 0,
    val tag: ByteArray = EMPTY, val nonce: ByteArray = EMPTY, val routeId: ByteArray = EMPTY, val payload: ByteArray = EMPTY,
) {
    fun encode(): ByteArray {
        val body = when (type) {
            RREQ -> tag + nonce
            RREP -> routeId + nonce
            DATA -> payload
            else -> EMPTY
        }
        return byteArrayOf(MESH_VERSION.toByte(), type.toByte(), ttl.toByte(), hops.toByte()) + id + body
    }

    fun with(ttl: Int = this.ttl, hops: Int = this.hops) = Packet(type, id, ttl, hops, tag, nonce, routeId, payload)

    companion object {
        fun rreq(id: ByteArray, tag: ByteArray, nonce: ByteArray, ttl: Int) = Packet(RREQ, id, ttl, tag = tag, nonce = nonce)
        fun rrep(reqId: ByteArray, routeId: ByteArray, nonce: ByteArray) = Packet(RREP, reqId, routeId = routeId, nonce = nonce)
        fun data(routeId: ByteArray, payload: ByteArray) = Packet(DATA, routeId, payload = payload)
        fun rerr(routeId: ByteArray) = Packet(RERR, routeId)

        fun decode(raw: ByteArray, ttlMax: Int = TTL_MAX): Packet {
            if (raw.size !in HEADER_LEN..MAX_PACKET) throw MeshProtocolException("неверная длина пакета")
            val version = raw[0].toInt() and 0xff
            val type = raw[1].toInt() and 0xff
            val ttl = raw[2].toInt() and 0xff
            val hops = raw[3].toInt() and 0xff
            val id = raw.copyOfRange(4, HEADER_LEN)
            val body = raw.copyOfRange(HEADER_LEN, raw.size)
            if (version != MESH_VERSION) throw MeshProtocolException("неизвестная версия")
            if (type == RREQ) {
                if (body.size != TAG_LEN + NONCE_LEN || ttl !in 1..ttlMax) throw MeshProtocolException("неверный RREQ")
                return Packet(RREQ, id, ttl, hops, tag = body.copyOfRange(0, TAG_LEN), nonce = body.copyOfRange(TAG_LEN, body.size))
            }
            if (ttl != 0) throw MeshProtocolException("ttl только у RREQ")
            return when (type) {
                RREP -> {
                    if (body.size != ID_LEN + NONCE_LEN) throw MeshProtocolException("неверный RREP")
                    Packet(RREP, id, 0, hops, routeId = body.copyOfRange(0, ID_LEN), nonce = body.copyOfRange(ID_LEN, body.size))
                }
                DATA -> Packet(DATA, id, 0, hops, payload = body)
                RERR -> {
                    if (body.isNotEmpty()) throw MeshProtocolException("RERR с телом")
                    Packet(RERR, id, 0, hops)
                }
                else -> throw MeshProtocolException("неизвестный тип")
            }
        }
    }
}
