package ru.mobile.mys.storage

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidecarTest {
    @Test fun roundTrip() {
        val f = File(createTempDirectory().toFile(), "vault.meta")
        val s = Sidecar(ByteArray(16) { it.toByte() }, opsLimit = 2, memLimit = 1L shl 20,
            failed = 3, lockoutUntil = 42, wipeEnabled = true, wipeThreshold = 5)
        s.write(f)
        val r = Sidecar.read(f)
        assertContentEquals(s.salt, r.salt)
        assertEquals(listOf(2L, 1L shl 20, 42L), listOf(r.opsLimit, r.memLimit, r.lockoutUntil))
        assertEquals(listOf(3, 5), listOf(r.failed, r.wipeThreshold))
        assertTrue(r.wipeEnabled)
    }

    @Test fun progressiveDelayCapped() {
        val s = Sidecar(ByteArray(16))
        assertFalse(s.registerFailure(1000))
        assertEquals(2000, s.lockRemaining(1000))
        s.registerFailure(1000)
        assertEquals(4000, s.lockRemaining(1000))
        repeat(60) { s.registerFailure(0) }
        assertEquals(Sidecar.LOCK_CAP_MS, s.lockRemaining(0))
        s.resetAttempts()
        assertEquals(0, s.lockRemaining(0))
    }

    @Test fun duressAtThreshold() {
        val s = Sidecar(ByteArray(16), wipeEnabled = true, wipeThreshold = 3)
        assertFalse(s.registerFailure(0))
        assertFalse(s.registerFailure(0))
        assertTrue(s.registerFailure(0))
    }
}
