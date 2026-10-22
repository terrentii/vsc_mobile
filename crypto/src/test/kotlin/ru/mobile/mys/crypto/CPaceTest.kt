package ru.mobile.mys.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CPaceTest {
    private val r = Ristretto(LazySodiumJava(SodiumJava()))

    @Test fun matchesDesktopWithFixedScalars() {
        for (v in Vectors.list("cpace")) {
            val (prs, sid, ci, ad) = listOf("prs", "sid", "ci", "ad").map { v.bytes(it) }
            assertEquals(v.getString("g"), cpaceGenerator(r, prs, sid, ci, ad).hex())
            val (a, ya) = cpaceMsg(r, prs, sid, ci, ad, y = v.bytes("ya"))
            val (b, yb) = cpaceMsg(r, prs, sid, ci, ad, y = v.bytes("yb"))
            assertEquals(v.getString("Ya"), ya.hex())
            assertEquals(v.getString("Yb"), yb.hex())
            assertEquals(v.getString("isk"), cpaceFinish(r, a, yb).hex())
            assertEquals(v.getString("isk"), cpaceFinish(r, b, ya).hex())
        }
    }

    @Test fun samePrsAgreesDifferentPrsDiffers() {
        val sid = ByteArray(16)
        val (a, ya) = cpaceMsg(r, "фраза".toByteArray(), sid)
        val (b, yb) = cpaceMsg(r, "фраза".toByteArray(), sid)
        assertEquals(cpaceFinish(r, a, yb).hex(), cpaceFinish(r, b, ya).hex())
        val (c, yc) = cpaceMsg(r, "другая".toByteArray(), sid)
        assertNotEquals(cpaceFinish(r, a, yc).hex(), cpaceFinish(r, c, ya).hex())
    }

    @Test fun identityPointRejected() {
        val (a, _) = cpaceMsg(r, "фраза".toByteArray(), ByteArray(16))
        assertFailsWith<RistrettoException> { cpaceFinish(r, a, ByteArray(32)) }
    }
}
