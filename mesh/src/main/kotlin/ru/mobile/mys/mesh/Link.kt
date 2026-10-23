package ru.mobile.mys.mesh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Линк: временные X25519 → crypto_kx → secretstream (порт mys_mesh/link.py). */
class Link private constructor(private val wire: Wire, private val push: LinkCrypto.Pusher, private val pull: LinkCrypto.Puller) {
    private val sendLock = Mutex()

    suspend fun send(packet: ByteArray) = sendLock.withLock { wire.send(push.push(packet)) }

    suspend fun recv(): ByteArray {
        val c = wire.recv()
        return try { pull.pull(c) } catch (e: LinkCryptoException) { throw MeshProtocolException("линк: ${e.message}") }
    }

    suspend fun close() = wire.close()

    companion object {
        private val MAGIC = "MYSM".toByteArray()
        private const val VERSION = 1
        const val HANDSHAKE_TIMEOUT_MS = 10_000L

        suspend fun open(wire: Wire, crypto: LinkCrypto, timeoutMs: Long = HANDSHAKE_TIMEOUT_MS): Link = try {
            withTimeout(timeoutMs) {
                val (pk, sk) = crypto.keypair()
                wire.send(MAGIC + byteArrayOf(VERSION.toByte()) + pk)
                val hello = wire.recv()
                if (hello.size != 37 || !hello.copyOfRange(0, 4).contentEquals(MAGIC) || hello[4].toInt() != VERSION) {
                    throw MeshProtocolException("неверное приветствие линка")
                }
                val (rx, tx) = crypto.sessionKeys(pk, sk, hello.copyOfRange(5, 37))
                val push = crypto.Pusher(tx)
                wire.send(push.header)
                Link(wire, push, crypto.Puller(rx, wire.recv()))
            }
        } catch (e: Throwable) {
            wire.close()
            throw if (e is LinkCryptoException) MeshProtocolException(e.message ?: "линк") else e
        }
    }
}
