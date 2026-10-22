package ru.mobile.mys.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class TransformTest {
    @Test fun encodeMatchesDesktop() {
        for (v in Vectors.list("transform")) {
            val enc = transformEncode(v.bytes("data"), v.bytes("key"))
            assertEquals(v.getString("enc"), enc.hex())
            assertEquals(v.getString("data"), transformDecode(enc, v.bytes("key")).hex())
        }
    }

    @Test fun decodeInvertsEncode() {
        val rnd = Random(42)
        for (size in listOf(0, 1, 40, 255, 256, 4097)) {
            val key = rnd.nextBytes(32)
            val data = rnd.nextBytes(size)
            assertEquals(data.hex(), transformDecode(transformEncode(data, key), key).hex())
        }
    }
}
