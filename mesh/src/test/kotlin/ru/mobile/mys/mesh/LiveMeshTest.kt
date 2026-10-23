package ru.mobile.mys.mesh

import java.io.File
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Два Kotlin-клиента через настоящий /mesh vsc_web + mesh_daemon.py (VSC_WEB_PYTHON, иначе пропуск). */
class LiveMeshTest {
    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test fun twoPhonesMeetThroughPublicNode() = runBlocking {
        val python = System.getenv("VSC_WEB_PYTHON")
        assumeTrue("нужен VSC_WEB_PYTHON", python != null)
        val dir = File(System.getenv("VSC_WEB_DIR") ?: File("..").canonicalFile.resolve("../vsc_web").canonicalPath)
        val tmp = Files.createTempDirectory("mesh_live").toFile()
        val (webPort, meshPort) = freePort() to freePort()
        val url = "ws://127.0.0.1:$webPort/mesh"
        val env = mapOf("PORT" to "$webPort", "MESH_PORT" to "$meshPort", "MESH_PUBLIC_URL" to url,
            "DATABASE_URL" to "sqlite:///${tmp.resolve("web.db")}", "SECRET_KEY" to "mesh", "SESSION_TYPE" to "null",
            "ROOMS_DIR" to tmp.resolve("rooms").path)
        val procs = listOf("mesh_daemon.py", "app.py").map {
            ProcessBuilder(python, it).directory(dir).redirectErrorStream(true)
                .redirectOutput(tmp.resolve("$it.log")).apply { environment() += env }.start()
        }
        try {
            withTimeout(30_000) {
                while (runCatching { URL("http://127.0.0.1:$webPort/").openStream().close() }.isFailure) delay(200)
            }
            val http = OkHttpClient()
            val nodes = List(2) { MeshNode(crypto, MeshConfig(rreqRetryMs = 300, sweepMs = 300)) }
            val learned = mutableListOf<String>()
            nodes[0].onPeers = { learned += it }
            try {
                nodes.forEach { it.attach(WsWire.connect(http, url)) }
                val key = tagKey(ByteArray(32) { 5 })
                val (a, b) = coroutineScope { nodes.map { n -> async { n.find(key, 15_000) } }.awaitAll() }
                a.second.send("via public mesh".toByteArray())
                assertEquals("via public mesh", withTimeout(10_000) { b.second.recv() }.decodeToString())
                assertEquals(listOf(url), learned)
            } finally { nodes.forEach { it.close() } }
        } finally { procs.forEach { it.destroy() } }
    }
}
