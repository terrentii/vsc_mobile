package ru.mobile.mys.mesh

import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class LinkCryptoTest {
    private fun keys(): Pair<Pair<ByteArray, ByteArray>, Pair<ByteArray, ByteArray>> {
        val (apk, ask) = crypto.keypair()
        val (bpk, bsk) = crypto.keypair()
        return crypto.sessionKeys(apk, ask, bpk) to crypto.sessionKeys(bpk, bsk, apk)
    }

    @Test fun keysMatchCrosswise() {
        val (a, b) = keys()
        assertContentEquals(a.second, b.first)
        assertContentEquals(a.first, b.second)
    }

    @Test fun streamRoundtripRejectsReplayAndTamper() {
        val (a, b) = keys()
        val push = crypto.Pusher(a.second)
        val pull = crypto.Puller(b.first, push.header)
        val c1 = push.push("one".toByteArray())
        val c2 = push.push("two".toByteArray())
        assertContentEquals("one".toByteArray(), pull.pull(c1))
        assertFailsWith<LinkCryptoException> { pull.pull(c2.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }) }
    }

    @Test fun replayRejected() {
        val (a, b) = keys()
        val push = crypto.Pusher(a.second)
        val pull = crypto.Puller(b.first, push.header)
        val c = push.push("x".toByteArray())
        pull.pull(c)
        assertFailsWith<LinkCryptoException> { pull.pull(c) }
    }

    @Test fun ownKeyRejected() {
        val (pk, sk) = crypto.keypair()
        assertFailsWith<LinkCryptoException> { crypto.sessionKeys(pk, sk, pk) }
    }

    @Test fun malformedPacketsRejected() {
        val id = ByteArray(16)
        for (raw in listOf(
            ByteArray(0),
            byteArrayOf(2, RERR.toByte(), 0, 0) + id,
            byteArrayOf(1, 9, 0, 0) + id,
            byteArrayOf(1, RREQ.toByte(), 0, 0) + id + ByteArray(48),
            byteArrayOf(1, RREQ.toByte(), 9, 0) + id + ByteArray(48),
            byteArrayOf(1, DATA.toByte(), 1, 0) + id,
            byteArrayOf(1, RERR.toByte(), 0, 0) + id + byteArrayOf(1),
        )) assertFailsWith<MeshProtocolException> { Packet.decode(raw) }
    }
}
