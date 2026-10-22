package ru.mobile.mys.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import ru.mobile.mys.crypto.generateX25519Keypair
import ru.mobile.mys.crypto.ratchetInitBob
import ru.mobile.mys.crypto.serializeState

@RunWith(AndroidJUnit4::class)
class VaultP2PStoreTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "p2p-${System.nanoTime()}")
    private val vault = Vault.create(dir, "pw".toByteArray(), opsLimit = 1, memLimit = 8L shl 20)
    private val store = VaultP2PStore(vault)

    @After fun tearDown() { vault.close(); Vault.wipe(dir); dir.delete() }

    @Test fun conversationsAndPrs() {
        val id = store.conversationFor(byteArrayOf(1, 2))
        assertEquals(id, store.conversationFor(byteArrayOf(1, 2)))
        assertNull(store.roomAndPrs(id))
        store.setPrs(id, byteArrayOf(9))
        val (room, prs) = store.roomAndPrs(id)!!
        assertContentEquals(byteArrayOf(1, 2), room)
        assertContentEquals(byteArrayOf(9), prs)
        assertEquals("decentralized", vault.conversations.get(id)!!.mode)
    }

    @Test fun sessionStoreWritesVault() {
        val id = store.conversationFor(byteArrayOf(3))
        val s = store.session(id)
        val st = ratchetInitBob(ByteArray(32), generateX25519Keypair())
        s.saveRatchet(st)
        assertContentEquals(serializeState(st), serializeState(s.loadRatchet()!!))
        val out = s.addOutgoing("a".toByteArray(), "pending")
        s.setStatus(out, "sent")
        val inId = s.receive("файл".toByteArray(), st, "file", "x.txt", "text/plain")
        assertEquals(listOf("sent", "received"), vault.messages.list(id).map { it.status })
        assertEquals("x.txt", vault.messages.get(inId)!!.filename)
    }
}
