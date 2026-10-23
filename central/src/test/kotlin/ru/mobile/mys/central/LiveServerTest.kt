package ru.mobile.mys.central

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/** Против настоящего vsc_web (VSC_WEB_PYTHON; VSC_WEB_DIR по умолчанию ../vsc_web). */
class LiveServerTest {
    companion object {
        private var web: Process? = null
        lateinit var url: String
        private val tmp = createTempDirectory("mys-central").toFile()

        @BeforeClass @JvmStatic fun startServer() {
            val python = System.getenv("VSC_WEB_PYTHON") ?: return
            val root = File(System.getProperty("mys.root"))
            val dir = File(System.getenv("VSC_WEB_DIR") ?: root.resolve("../vsc_web").canonicalPath)
            val port = ServerSocket(0).use { it.localPort }
            url = "http://127.0.0.1:$port"
            web = ProcessBuilder(python, "app.py").directory(dir).redirectErrorStream(true)
                .redirectOutput(tmp.resolve("web.log"))
                .apply {
                    environment() += mapOf(
                        "PORT" to "$port", "DATABASE_URL" to "sqlite:///${tmp.resolve("web.db")}",
                        "SECRET_KEY" to "central-test", "SESSION_TYPE" to "null",
                        "ROOMS_DIR" to tmp.resolve("rooms").path, "MEDIA_CLEANUP_THRESHOLD" to "1.0",
                    )
                }.start()
            runBlocking { eventually(30_000) { httpOk("$url/") } }
        }

        @AfterClass @JvmStatic fun stopServer() { web?.destroy() }

        private fun httpOk(u: String) = runCatching {
            (URI(u).toURL().openConnection() as HttpURLConnection).run { connectTimeout = 500; responseCode == 200 }
        }.getOrDefault(false)

        suspend fun eventually(timeoutMs: Long = 10_000, check: suspend () -> Boolean) = withTimeout(timeoutMs) {
            while (!check()) delay(50)
        }
    }

    private fun user() = "u" + UUID.randomUUID().toString().take(8)

    private fun live(block: suspend () -> Unit) = runBlocking {
        assumeTrue("нужен VSC_WEB_PYTHON", web != null)
        try { block() } catch (e: Throwable) {
            throw AssertionError("web.log:\n" + tmp.resolve("web.log").readText().takeLast(3000), e)
        }
    }

    private suspend fun service(store: MemoryCentralStore = MemoryCentralStore(), name: String = user()) =
        CentralService(store, roomPollMs = 0).also { it.login(url, name, "pw-$name", register = true) } to store

    @Test fun dialogInviteAcceptTypingReadTitleLeave() = live {
        val nameB = user()
        val (a, sa) = service()
        val (b, sb) = service(name = nameB)
        eventually { a.liveConnected && b.liveConnected }
        val convA = a.invite(nameB)
        assertTrue(a.meta(convA)!!.pendingInvite)
        // приглашение долетает живьём (room_updated по /ws)
        eventually(20_000) { b.metas.value.values.any { it.role == "invited" } }
        val convB = b.metas.value.entries.first { it.value.role == "invited" }.key
        assertTrue(b.roomOp(convB) { dialogAction(it, "accept") }!!.isDialog)
        eventually(20_000) { a.meta(convA)?.isDialog == true }

        val typed = java.util.concurrent.atomic.AtomicBoolean(false)
        val watch = kotlinx.coroutines.GlobalScope.launch {
            a.events.collect { if (it is CentralEvent.Room && it.kind == "typing" && it.conversationId == convA) typed.set(true) }
        }
        b.typing(convB)
        eventually(20_000) { typed.get() }
        watch.cancel()

        b.send(convB, "привет")
        eventually(20_000) { sa.messages(convA).any { it.wireSeq != null } }
        val wire = sa.messages(convA).mapNotNull { it.wireSeq }.max()
        a.markRead(convA, wire)
        eventually(20_000) { b.meta(convB)?.peerRead == wire }

        assertEquals("Друг", a.roomOp(convA) { setTitle(it, "Друг") }!!.title)
        assertEquals(nameB, a.meta(convA)!!.peer)
        assertTrue(b.meta(convB)!!.title != "Друг")
        assertEquals(null, b.roomOp(convB) { leaveRoom(it) })
        assertTrue(sb.conversations().none { it == convB })
        eventually(20_000) { a.meta(convA)?.isDialog == false }
    }

    @Test fun createJoinSendLiveEditDelete() = live {
        val (a, sa) = service()
        val (b, sb) = service()
        val convA = a.createRoom("проект")
        val code = a.roomCode(convA)!!
        assertEquals(10, code.length)
        val convB = b.joinRoom(code)
        assertEquals(code, b.roomCode(convB))
        eventually { b.liveConnected }

        val idA = a.send(convA, "привет")
        val mine = sa.get(idA)!!
        assertEquals("sent", mine.status)
        eventually { sb.messages(convB).any { it.body?.decodeToString() == "привет" } }
        val got = sb.messages(convB).single { it.direction == "in" }
        assertEquals(mine.wireSeq, got.wireSeq)

        a.edit(idA, "привет!")
        eventually { sb.get(got.id)?.body?.decodeToString() == "привет!" }
        a.delete(idA)
        eventually { sb.get(got.id) == null }
        assertNull(sa.get(idA))

        assertEquals("not_found", assertFailsWith<ServerException> { b.joinRoom("0000000000") }.code)
        a.stop(); b.stop()
    }

    @Test fun noDuplicatesAfterResyncAndOwnEcho() = live {
        val (a, sa) = service()
        val conv = a.createRoom("эхо")
        eventually { a.liveConnected }
        repeat(3) { a.send(conv, "сообщение $it") }
        delay(500) // эхо по /ws успевает прийти
        a.resync()
        a.resync()
        assertEquals(3, sa.messages(conv).size)
        assertTrue(sa.messages(conv).all { it.direction == "out" && it.status == "sent" && it.wireSeq != null })
        a.stop()
    }

    @Test fun mediaUploadAndLazyFetch() = live {
        val (a, _) = service()
        val (b, sb) = service()
        val convA = a.createRoom("медиа")
        val convB = b.joinRoom(a.roomCode(convA)!!)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(100_000) { it.toByte() }
        a.sendFile(convA, "снимок.png", "image/png", png)
        b.resync()
        val incoming = sb.messages(convB).single()
        assertEquals("image", incoming.kind)
        assertNull(incoming.body)
        assertContentEquals(png, b.fetchMedia(incoming.id))
        assertContentEquals(png, sb.get(incoming.id)!!.body)
        a.stop(); b.stop()
    }

    @Test fun resumeLogoutAndDeadToken() = live {
        val store = MemoryCentralStore()
        val name = user()
        val (a, _) = service(store, name)
        a.setWipeOnLogout(true)
        val conv = a.createRoom("комната")
        a.stop()

        val resumed = CentralService(store, roomPollMs = 0)
        assertEquals(name, resumed.resume()!!.username)
        assertTrue(conv in store.conversations()) // плюс личная комната, созданная регистрацией
        resumed.logout()
        assertNull(store.setting("central.token"))
        assertTrue(store.conversations().isEmpty()) // wipe_on_logout
        resumed.stop()

        store.setSetting("central.server_url", url)
        store.setSetting("central.username", name)
        store.setSetting("central.user_id", "1")
        store.setSetting("central.token", "deadbeef") // протухший токен
        val dead = CentralService(store, roomPollMs = 0)
        assertFailsWith<AuthException> { dead.resume() }
        assertNull(store.setting("central.token"))
        dead.stop()
    }
}
