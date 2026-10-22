package ru.mobile.mys.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class RatchetTest {
    private val env = Vectors.obj("envelope")
    private val messages = env.getJSONArray("messages").objects()
    private val tk = env.bytes("tk")

    @Test fun kdfsMatchDesktop() {
        val rk = Vectors.obj("kdf_rk")
        val (rkOut, ckOut) = kdfRk(rk.bytes("rk"), rk.bytes("dh_out"))
        assertEquals(rk.getString("rk_out"), rkOut.hex())
        assertEquals(rk.getString("ck_out"), ckOut.hex())
        val ck = Vectors.obj("kdf_ck")
        val (newCk, mk) = kdfCk(ck.bytes("ck"))
        assertEquals(ck.getString("ck_out"), newCk.hex())
        assertEquals(ck.getString("mk"), mk.hex())
        val mkv = Vectors.obj("message_keys")
        val (key, nonce) = deriveMessageKeys(mkv.bytes("mk"))
        assertEquals(mkv.getString("key"), key.hex())
        assertEquals(mkv.getString("nonce"), nonce.hex())
    }

    @Test fun transformKeyMatchesDesktop() {
        assertEquals(env.getString("tk"), deriveTransformKey(env.bytes("sk")).hex())
    }

    @Test fun sealMatchesDesktopByteForByte() {
        val alice = deserializeState(env.bytes("alice"))
        for (m in messages) assertEquals(m.getString("blob"), seal(alice, tk, m.bytes("pt")).hex())
        assertEquals(env.getString("alice_after"), serializeState(alice).hex())
    }

    @Test fun opensDesktopBlobsInOrder() {
        val bob = deserializeState(env.bytes("bob"))
        for (m in messages) assertEquals(m.getString("pt"), open(bob, tk, m.bytes("blob")).hex())
    }

    @Test fun opensFromDesktopSkippedKeys() {
        // Состояние Боба из десктопа после приёма №3 первым: 0..2 лежат в mkskipped.
        val bob = deserializeState(Vectors.root.getString("state_with_skipped").unhex())
        assertEquals(3, bob.mkskipped.size)
        for (m in messages.take(3)) assertEquals(m.getString("pt"), open(bob, tk, m.bytes("blob")).hex())
        assertTrue(bob.mkskipped.isEmpty())
    }

    @Test fun stateSerializationRoundTrips() {
        val blob = Vectors.root.getString("state_with_skipped").unhex()
        assertEquals(blob.hex(), serializeState(deserializeState(blob)).hex())
    }

    @Test fun replayAndTamperRejectedWithoutStateChange() {
        val bob = deserializeState(env.bytes("bob"))
        val first = messages[1].bytes("blob")
        open(bob, tk, messages[0].bytes("blob"))
        open(bob, tk, first)
        val before = serializeState(bob).hex()
        assertFails { open(bob, tk, first) } // replay
        val bad = messages[2].bytes("blob").also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        assertFails { open(bob, tk, bad) }
        assertEquals(before, serializeState(bob).hex())
    }

    @Test fun conversationBothWaysWithFreshKeys() {
        val sk = ByteArray(32) { it.toByte() }
        val bobKp = generateX25519Keypair()
        val alice = ratchetInitAlice(sk, bobKp.second)
        val bob = ratchetInitBob(sk, bobKp)
        val t = deriveTransformKey(sk)
        assertEquals("a1", open(bob, t, seal(alice, t, "a1".toByteArray())).decodeToString())
        assertEquals("b1", open(alice, t, seal(bob, t, "b1".toByteArray())).decodeToString())
        val a2 = seal(alice, t, "a2".toByteArray())
        val a3 = seal(alice, t, "a3".toByteArray())
        assertEquals("a3", open(bob, t, a3).decodeToString()) // вне порядка
        assertEquals("a2", open(bob, t, a2).decodeToString())
    }

    @Test fun tooManySkippedRejected() {
        val sk = ByteArray(32)
        val bobKp = generateX25519Keypair()
        val alice = ratchetInitAlice(sk, bobKp.second)
        val bob = ratchetInitBob(sk, bobKp)
        val t = deriveTransformKey(sk)
        open(bob, t, seal(alice, t, "x".toByteArray()))
        repeat(MAX_SKIP + 1) { seal(alice, t, "skip".toByteArray()) }
        assertFails { open(bob, t, seal(alice, t, "late".toByteArray())) }
    }
}
