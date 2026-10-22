package ru.mobile.mys.p2p

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Два Kotlin-сервиса через настоящий /p2p из vsc_web: сигналинг прямого канала
 * идёт настоящим E2E-путём, а «WebRTC» подменён in-memory парой.
 */
class DirectServiceTest {
    private class Hub { val pair = InMemoryTransport.pair() }

    private class FakeLink(private val hub: Hub) : DirectLink {
        private lateinit var signal: (String) -> Unit
        private lateinit var opened: (Transport) -> Unit
        override fun start(initiator: Boolean, signal: (String) -> Unit, opened: (Transport) -> Unit, closed: () -> Unit) {
            this.signal = signal
            this.opened = opened
            if (initiator) signal("""{"t":"offer","sdp":"fake"}""")
        }
        override fun onSignal(json: String) {
            if ("offer" in json) { signal("""{"t":"answer","sdp":"fake"}"""); opened(hub.pair.second) }
            if ("answer" in json) opened(hub.pair.first)
        }
        override fun restart() {}
        override fun close() {}
    }

    @Test fun twoPeersUpgradeToDirectAndTalk() = runBlocking {
        val python = System.getenv("VSC_WEB_PYTHON")
        assumeTrue("нужен VSC_WEB_PYTHON", python != null)
        val root = File(System.getProperty("mys.root"))
        val tmp = createTempDirectory("mys-direct").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val web = ProcessBuilder(python, "app.py").directory(root.resolve("../vsc_web").canonicalFile)
            .redirectErrorStream(true).redirectOutput(tmp.resolve("web.log"))
            .apply {
                environment() += mapOf("PORT" to "$port", "DATABASE_URL" to "sqlite:///${tmp.resolve("w.db")}",
                    "SECRET_KEY" to "x", "SESSION_TYPE" to "null", "ROOMS_DIR" to tmp.resolve("rooms").path)
            }.start()
        val hub = Hub()
        val (sa, sb) = MemoryStore() to MemoryStore()
        val a = P2PService(sa, "ws://127.0.0.1:$port/p2p", ristretto, connectTimeoutMs = 30_000, directLinks = { FakeLink(hub) })
        val b = P2PService(sb, "ws://127.0.0.1:$port/p2p", ristretto, connectTimeoutMs = 30_000, directLinks = { FakeLink(hub) })
        val paths = CopyOnWriteArrayList<Boolean>()
        val watcher = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        watcher.launch { a.events.collect { if (it is P2PEvent.Path) paths += it.direct } }
        try {
            eventually(30_000) {
                runCatching { (URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection).responseCode == 200 }.getOrDefault(false)
            }
            val ca = async(Dispatchers.IO) { a.startSession("прямой канал") }
            val cb = async(Dispatchers.IO) { b.startSession("прямой канал") }
            val (ia, ib) = ca.await() to cb.await()
            eventually { a.isDirect(ia) && b.isDirect(ib) }
            a.send(ia, "привет по прямому")
            b.send(ib, "ответ по прямому")
            eventually {
                sb.messages(ib).any { it.direction == "in" && it.body.decodeToString() == "привет по прямому" } &&
                    sa.messages(ia).any { it.direction == "in" && it.body.decodeToString() == "ответ по прямому" }
            }
            eventually { paths.contains(true) }
            assertTrue(a.isDirect(ia))
        } catch (e: Throwable) {
            throw AssertionError("web.log:\n" + tmp.resolve("web.log").readText().takeLast(2000), e)
        } finally {
            a.stop(); b.stop(); watcher.cancel(); web.destroy()
        }
    }
}
