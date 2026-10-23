package ru.mobile.mys.mesh

import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

class ChunksTest {
    @Test fun roundtripAnySize() {
        val r = Reassembler()
        for (n in listOf(0, 1, 19, 20, 21, 513, 5000, MAX_FRAME)) {
            val frame = Random.nextBytes(n)
            val parts = chunk(frame, 20)
            assertEquals(maxOf(1, (n + 18) / 19), parts.size)
            parts.dropLast(1).forEach { assertNull(r.push(it)) }
            assertContentEquals(frame, r.push(parts.last()))
        }
    }

    @Test fun oversizedFrameRejected() {
        val r = Reassembler()
        assertFailsWith<WireClosed> { chunk(ByteArray(MAX_FRAME + 10), 512).forEach { r.push(it) } }
    }
}
