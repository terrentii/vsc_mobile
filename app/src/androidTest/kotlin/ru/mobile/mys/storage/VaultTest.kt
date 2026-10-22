package ru.mobile.mys.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.mobile.mys.crypto.generateX25519Keypair
import ru.mobile.mys.crypto.ratchetInitBob
import ru.mobile.mys.crypto.serializeState

@RunWith(AndroidJUnit4::class)
class VaultTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private val pw = "пароль".toByteArray()

    @Before fun setUp() { dir = File(ctx.cacheDir, "vault-${System.nanoTime()}") }
    @After fun tearDown() { Vault.wipe(dir); dir.delete() }

    // Быстрый KDF для тестов (8 МиБ, 1 проход); боевые параметры — по умолчанию.
    private fun create() = Vault.create(dir, pw, opsLimit = 1, memLimit = 8L shl 20)
    private fun open(password: ByteArray = pw, now: Long = 0) = Vault.open(dir, password, now)

    @Test fun createReopenPersists() {
        create().use { v ->
            v.conversations.add("decentralized", roomId = byteArrayOf(1, 2), title = "канал")
            v.settings.set("theme", "dark")
        }
        open().use { v ->
            val c = v.conversations.list("decentralized").single()
            assertContentEquals(byteArrayOf(1, 2), c.roomId)
            assertEquals("канал", c.title)
            assertEquals(c.id, v.conversations.getByRoomId(byteArrayOf(1, 2), "decentralized")!!.id)
            assertEquals("dark", v.settings.get("theme"))
        }
        assertFailsWith<VaultExists> { create() }
    }

    @Test fun fileIsEncrypted() {
        create().close()
        val head = File(dir, "vault.db").readBytes().copyOf(15).decodeToString()
        assertFalse(head.startsWith("SQLite format"))
    }

    @Test fun wrongPasswordLocksOut() {
        create().close()
        assertFailsWith<WrongPassword> { open("нет".toByteArray(), now = 0) }
        assertFailsWith<VaultLocked> { open(now = 1_000) }
        open(now = 10_000).close()
        assertEquals(0, Sidecar.read(File(dir, "vault.meta")).failed)
    }

    @Test fun duressWipesAtThreshold() {
        create().close()
        val meta = File(dir, "vault.meta")
        Sidecar.read(meta).apply { wipeEnabled = true; wipeThreshold = 2 }.write(meta)
        assertFailsWith<WrongPassword> { open("нет".toByteArray(), now = 0) }
        assertFailsWith<WrongPassword> { open("нет".toByteArray(), now = 10_000_000) }
        assertFalse(Vault.exists(dir))
    }

    @Test fun changePassword() {
        create().use { v ->
            assertFailsWith<WrongPassword> { v.changePassword("нет".toByteArray(), "x".toByteArray()) }
            v.changePassword(pw, "новый".toByteArray())
        }
        assertFailsWith<WrongPassword> { open(now = 0) }
        open("новый".toByteArray(), now = 10_000_000).close()
    }

    @Test fun receiveMessageStoresMessageAndRatchet() {
        create().use { v ->
            val c = v.conversations.add("decentralized")
            val st = ratchetInitBob(ByteArray(32), generateX25519Keypair())
            val id = v.receiveMessage(c, "привет".toByteArray(), st)
            val m = v.messages.get(id)!!
            assertEquals("привет", m.body.decodeToString())
            assertEquals(listOf("in", "received", "text"), listOf(m.direction, m.status, m.kind))
            assertContentEquals(serializeState(st), serializeState(v.ratchet.load(c)!!))
        }
    }

    @Test fun messagesAndPrsAndDelete() {
        create().use { v ->
            val c = v.conversations.add("decentralized", roomId = byteArrayOf(9))
            v.conversations.setPrs(c, byteArrayOf(7, 7))
            assertContentEquals(byteArrayOf(7, 7), v.conversations.get(c)!!.p2pPrs)
            val out = v.messages.add(c, "out", "a".toByteArray(), "pending")
            v.messages.add(c, "in", "файл".toByteArray(), "received", kind = "file", filename = "x.txt", mimeType = "text/plain")
            v.messages.setStatus(out, "sent")
            assertEquals(listOf("sent", "received"), v.messages.list(c).map { it.status })
            assertEquals("x.txt", v.messages.list(c)[1].filename)
            v.ratchet.save(c, ratchetInitBob(ByteArray(32), generateX25519Keypair()))
            v.conversations.delete(c)
            assertNull(v.conversations.get(c))
            assertTrue(v.messages.list(c).isEmpty())
            assertNull(v.ratchet.load(c))
        }
    }

    @Test fun settingsDeletePrefix() {
        create().use { v ->
            v.settings.set("central.cursor.1", "5")
            v.settings.set("central.cursor.2", "6")
            v.settings.set("central.token", "t")
            v.settings.deletePrefix("central.cursor.")
            assertNull(v.settings.get("central.cursor.1"))
            assertEquals("t", v.settings.get("central.token"))
        }
    }
}
