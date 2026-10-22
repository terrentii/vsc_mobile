package ru.mobile.mys.p2p

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Kotlin ↔ настоящий P2PService десктопа через настоящий /p2p из vsc_web.
 * Нужны DESKTOP_PYTHON и VSC_WEB_PYTHON (иначе пропуск); пути к соседним
 * репозиториям — DESKTOP_SRC / VSC_WEB_DIR, по умолчанию ../vsc_desktop/src, ../vsc_web.
 */
class InteropTest {
    private val root = File(System.getProperty("mys.root"))
    private val desktopPython: String? = System.getenv("DESKTOP_PYTHON")
    private val webPython: String? = System.getenv("VSC_WEB_PYTHON")
    private val desktopSrc = System.getenv("DESKTOP_SRC") ?: root.resolve("../vsc_desktop/src").canonicalPath
    private val webDir = File(System.getenv("VSC_WEB_DIR") ?: root.resolve("../vsc_web").canonicalPath)

    @Test fun kotlinJoinsSecond() = interop(kotlinFirst = false)

    @Test fun kotlinJoinsFirst() = interop(kotlinFirst = true)

    private fun interop(kotlinFirst: Boolean) = runBlocking {
        assumeTrue("нужны DESKTOP_PYTHON и VSC_WEB_PYTHON", desktopPython != null && webPython != null)
        val tmp = createTempDirectory("mys-interop").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val url = "ws://127.0.0.1:$port/p2p"
        val web = ProcessBuilder(webPython, "app.py").directory(webDir).redirectErrorStream(true)
            .redirectOutput(tmp.resolve("web.log"))
            .apply {
                environment() += mapOf(
                    "PORT" to "$port", "DATABASE_URL" to "sqlite:///${tmp.resolve("web.db")}",
                    "SECRET_KEY" to "interop", "SESSION_TYPE" to "null",
                )
            }.start()
        val phrase = "интероп ${System.nanoTime()}"
        fun startPeer() = ProcessBuilder(desktopPython, root.resolve("tools/desktop_peer.py").path, desktopSrc, url, phrase, tmp.path)
            .redirectErrorStream(true).redirectOutput(tmp.resolve("peer.log")).start()
        val store = MemoryStore()
        val service = P2PService(store, url, ristretto, connectTimeoutMs = 60_000)
        var peer: Process? = null
        try {
            waitHttp("http://127.0.0.1:$port/")
            if (!kotlinFirst) { peer = startPeer(); delay(2_000) }
            val conv = async(Dispatchers.IO) { service.startSession(phrase) }
            if (kotlinFirst) { delay(2_000); peer = startPeer() }
            val id = withTimeout(90_000) { conv.await() }
            assertEquals(if (kotlinFirst) Role.INITIATOR else Role.RESPONDER, service.roleOf(id))

            service.send(id, "привет")
            awaitReply(store, id, "echo: привет")
            val file = Random(42).nextBytes(200_000)
            service.sendFile(id, "data.bin", "application/octet-stream", file)
            awaitReply(store, id, "sha256:" + MessageDigest.getInstance("SHA-256").digest(file).hex())
            service.send(id, "bye")
            assertTrue(peer!!.waitFor(30, TimeUnit.SECONDS), "десктоп-пир не завершился")
            assertEquals(0, peer!!.exitValue())
        } catch (e: Throwable) {
            throw AssertionError("peer.log:\n${tmp.resolve("peer.log").readTextOrEmpty()}\nweb.log:\n${tmp.resolve("web.log").readTextOrEmpty().takeLast(3000)}", e)
        } finally {
            service.stop()
            peer?.destroy()
            web.destroy()
        }
    }

    private suspend fun awaitReply(store: MemoryStore, id: Long, text: String) = eventually(30_000) {
        store.messages(id).any { it.direction == "in" && it.body.decodeToString() == text }
    }

    private suspend fun waitHttp(url: String) = eventually(30_000) {
        runCatching {
            (URI(url).toURL().openConnection() as HttpURLConnection).run { connectTimeout = 500; responseCode == 200 }
        }.getOrDefault(false)
    }

    private fun File.readTextOrEmpty() = if (exists()) readText() else ""
}
