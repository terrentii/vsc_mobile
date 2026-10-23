package ru.mobile.mys.mesh

import com.goterl.lazysodium.interfaces.KeyExchange
import com.goterl.lazysodium.interfaces.SecretStream

class LinkCryptoException(message: String) : Exception(message)

/** crypto_kx + secretstream XChaCha20-Poly1305 из libsodium (порт mys_crypto/linkcrypto.py). */
class LinkCrypto(private val kx: KeyExchange.Native, private val ss: SecretStream.Native) {
    fun keypair(): Pair<ByteArray, ByteArray> {
        val pk = ByteArray(KX_PK)
        val sk = ByteArray(KX_PK)
        if (!kx.cryptoKxKeypair(pk, sk)) throw LinkCryptoException("crypto_kx_keypair")
        return pk to sk
    }

    /** (rx, tx); «клиент» crypto_kx — сторона с меньшим pk (побайтово без знака). */
    fun sessionKeys(pk: ByteArray, sk: ByteArray, peer: ByteArray): Pair<ByteArray, ByteArray> {
        if (peer.size != KX_PK || peer.contentEquals(pk)) throw LinkCryptoException("неверный ключ соседа")
        val rx = ByteArray(KX_PK)
        val tx = ByteArray(KX_PK)
        val ok = if (compareUnsigned(pk, peer) < 0) kx.cryptoKxClientSessionKeys(rx, tx, pk, sk, peer)
        else kx.cryptoKxServerSessionKeys(rx, tx, pk, sk, peer)
        if (!ok) throw LinkCryptoException("crypto_kx отверг ключ соседа")
        return rx to tx
    }

    inner class Pusher(key: ByteArray) {
        private val state = SecretStream.State()
        val header = ByteArray(HEADERBYTES)

        init {
            if (!ss.cryptoSecretStreamInitPush(state, header, key)) throw LinkCryptoException("init_push")
        }

        fun push(m: ByteArray): ByteArray {
            val c = ByteArray(m.size + ABYTES)
            if (!ss.cryptoSecretStreamPush(state, c, m, m.size.toLong(), SecretStream.TAG_MESSAGE)) throw LinkCryptoException("push")
            return c
        }
    }

    inner class Puller(key: ByteArray, header: ByteArray) {
        private val state = SecretStream.State()

        init {
            if (header.size != HEADERBYTES || !ss.cryptoSecretStreamInitPull(state, header, key)) {
                throw LinkCryptoException("неверный заголовок потока")
            }
        }

        fun pull(c: ByteArray): ByteArray {
            if (c.size < ABYTES) throw LinkCryptoException("кадр короче ABYTES")
            val m = ByteArray(c.size - ABYTES)
            val tag = ByteArray(1)
            if (!ss.cryptoSecretStreamPull(state, m, tag, c, c.size.toLong())) throw LinkCryptoException("кадр не расшифрован")
            if (tag[0] != SecretStream.TAG_MESSAGE) throw LinkCryptoException("неожиданный тег потока")
            return m
        }
    }

    companion object {
        const val KX_PK = 32
        const val HEADERBYTES = 24
        const val ABYTES = 17
    }
}

internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return a.size - b.size
}
