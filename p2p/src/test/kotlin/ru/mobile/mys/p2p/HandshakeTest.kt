package ru.mobile.mys.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import ru.mobile.mys.crypto.deriveTransformKey
import ru.mobile.mys.crypto.open
import ru.mobile.mys.crypto.seal

class HandshakeTest {
    @Test fun derivationsMatchDesktop() {
        val v = Vectors.root.getJSONObject("handshake")
        assertEquals(v.getString("sid"), deriveSid(v.bytes("prs")).hex())
        assertEquals(v.getString("confirm_mac"), confirmMac(v.bytes("isk"), v.bytes("my_y"), v.bytes("peer_y")).hex())
        assertEquals(v.getString("confirm_mac"), confirmMac(v.bytes("isk"), v.bytes("peer_y"), v.bytes("my_y")).hex())
        val (sk, bob) = deriveSessionKeys(v.bytes("isk"))
        assertEquals(v.getString("sk"), sk.hex())
        assertEquals(v.getString("bob_pub"), bob.second.hex())
    }

    private fun run(prsA: ByteArray, prsB: ByteArray) = runBlocking {
        val (ta, tb) = InMemoryTransport.pair()
        listOf(
            async { runCatching { handshake(ta, prsA, Role.INITIATOR, ristretto) } },
            async { runCatching { handshake(tb, prsB, Role.RESPONDER, ristretto) } },
        ).awaitAll()
    }

    @Test fun samePhraseGivesWorkingRatchets() {
        val prs = deriveRoomParams("общая фраза").second
        val (a, b) = run(prs, prs).map { it.getOrThrow() }
        assertEquals(a.sk.hex(), b.sk.hex())
        val tk = deriveTransformKey(a.sk)
        assertEquals("привет", open(b.ratchetState, tk, seal(a.ratchetState, tk, "привет".toByteArray())).decodeToString())
    }

    @Test fun wrongPhraseFailsOnBothSides() {
        val res = run(deriveRoomParams("раз").second, deriveRoomParams("два").second)
        res.forEach { assertIs<PakeException>(it.exceptionOrNull()) }
    }
}
