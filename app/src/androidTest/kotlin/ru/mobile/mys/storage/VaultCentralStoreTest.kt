package ru.mobile.mys.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import ru.mobile.mys.central.NewMessage

@RunWith(AndroidJUnit4::class)
class VaultCentralStoreTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "c-${System.nanoTime()}")
    private val vault = Vault.create(dir, "pw".toByteArray(), opsLimit = 1, memLimit = 8L shl 20)
    private val store = VaultCentralStore(vault)

    @After fun tearDown() { vault.close(); Vault.wipe(dir); dir.delete() }

    @Test fun roomsAndSettings() {
        val conv = store.conversationForRoom(42, "комната")!!
        assertEquals(conv, store.conversationForRoom(42, null))
        assertNull(store.conversationForRoom(43, null, create = false))
        assertEquals(42L, store.roomOf(conv))
        assertEquals("комната", vault.conversations.get(conv)!!.title)
        store.setSetting("central.cursor.42", "7")
        store.setSetting("central.code.42", "1234567890")
        store.deleteSettings("central.cursor.")
        assertNull(store.setting("central.cursor.42"))
        assertEquals("1234567890", store.setting("central.code.42"))
        store.wipeConversations()
        assertTrue(vault.conversations.list("centralized").isEmpty())
    }

    @Test fun messageLifecycle() {
        val conv = store.conversationForRoom(1, "r")!!
        val out = store.add(NewMessage(conv, "out", "тело".toByteArray(), "pending", clientMsgId = "c1", replyAuthor = "b", replySnippet = "цит"))
        assertEquals(out, store.findOutByClientId(conv, "c1")!!.id)
        assertEquals(out, store.findUnconfirmedOutByBody(conv, "тело".toByteArray())!!.id)
        store.markSent(out, 100)
        assertNull(store.findUnconfirmedOutByBody(conv, "тело".toByteArray()))
        assertEquals("sent", store.get(out)!!.status)
        assertEquals(out, store.findByWire(conv, 100)!!.id)
        assertEquals("цит", vault.messages.get(out)!!.replySnippet)

        val media = store.add(NewMessage(conv, "in", null, "received", "image", "a.png", "image/png", "b", 101, mediaRef = "x_a.png", timestamp = 5))
        assertNull(store.get(media)!!.body)
        assertEquals("x_a.png", store.get(media)!!.mediaRef)
        store.setBody(media, byteArrayOf(1, 2))
        assertContentEquals(byteArrayOf(1, 2), store.get(media)!!.body)
        assertEquals(5L, vault.messages.get(media)!!.receivedAt)
        store.delete(media)
        assertNull(store.get(media))
    }
}
