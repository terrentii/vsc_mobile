package ru.mobile.mys.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PrimitivesTest {
    @Test fun hkdfMatchesDesktop() {
        for (v in Vectors.list("hkdf")) {
            val out = hkdf(v.bytes("ikm"), v.getInt("len"), v.bytes("salt"), v.bytes("info"))
            assertEquals(v.getString("out"), out.hex())
        }
    }

    @Test fun x25519MatchesDesktop() {
        val v = Vectors.obj("x25519")
        val (aPriv, aPub) = x25519KeypairFromSeed(v.bytes("a_priv"))
        val (_, bPub) = x25519KeypairFromSeed(v.bytes("b_priv"))
        assertEquals(v.getString("a_pub"), aPub.hex())
        assertEquals(v.getString("b_pub"), bPub.hex())
        assertEquals(v.getString("shared"), x25519Shared(aPriv, bPub).hex())
        assertEquals(v.getString("shared"), x25519Shared(v.bytes("b_priv"), aPub).hex())
    }

    @Test fun randomX25519PairsAgree() {
        val a = generateX25519Keypair()
        val b = generateX25519Keypair()
        assertEquals(x25519Shared(a.first, b.second).hex(), x25519Shared(b.first, a.second).hex())
    }

    @Test fun aeadMatchesDesktopAndRejectsTamper() {
        val v = Vectors.obj("aead")
        val ct = aeadEncrypt(v.bytes("key"), v.bytes("nonce"), v.bytes("pt"), v.bytes("aad"))
        assertEquals(v.getString("ct"), ct.hex())
        assertEquals(v.getString("pt"), aeadDecrypt(v.bytes("key"), v.bytes("nonce"), ct, v.bytes("aad")).hex())
        ct[0] = (ct[0].toInt() xor 1).toByte()
        assertFails { aeadDecrypt(v.bytes("key"), v.bytes("nonce"), ct, v.bytes("aad")) }
    }

    @Test fun argon2idMatchesDesktop() {
        for (v in Vectors.list("argon2id")) {
            val out = argon2id(v.bytes("password"), v.bytes("salt"), v.getInt("len"),
                v.getInt("t"), v.getInt("m"), v.getInt("p"))
            assertEquals(v.getString("out"), out.hex())
        }
    }
}
