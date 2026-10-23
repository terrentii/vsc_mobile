package ru.mobile.mys.mesh

import java.io.File
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.mobile.mys.p2p.Role
import ru.mobile.mys.p2p.handshake

/** Kotlin-узел ↔ узлы десктопа (ретранслятор + собеседник) по TCP. Нужен DESKTOP_PYTHON, иначе пропуск. */
class MeshInteropTest {
    private val root = File(System.getProperty("mys.root"))
    private val python: String? = System.getenv("DESKTOP_PYTHON")
    private val desktopSrc = System.getenv("DESKTOP_SRC") ?: root.resolve("../vsc_desktop/src").canonicalPath

    @Test fun kotlinMeetsDesktopThroughRelay() = runBlocking {
        assumeTrue("нужен DESKTOP_PYTHON", python != null)
        val tmp = createTempDirectory("mys-mesh").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val prs = ByteArray(32) { (it * 7).toByte() }
        val proc = ProcessBuilder(python, root.resolve("tools/mesh_peer.py").path, desktopSrc, "$port", prs.hex(), tmp.path)
            .redirectErrorStream(true).redirectOutput(tmp.resolve("peer.log")).start()
        val node = MeshNode(crypto, MeshConfig(rreqRetryMs = 500))
        try {
            withTimeout(30_000) { while (!tmp.resolve("ready").exists()) delay(100) }
            node.attach(TcpWire.connect("127.0.0.1", port))
            val (role, t) = node.find(tagKey(prs), 60_000)
            val res = handshake(Adapter(t), prs, Role.valueOf(role.name), ristretto)
            t.send("привет".toByteArray())
            assertEquals("привет", t.recv().decodeToString())
            withTimeout(10_000) { while (!tmp.resolve("sk.txt").exists()) delay(100) }
            assertEquals(res.sk.hex(), tmp.resolve("sk.txt").readText())
        } finally {
            node.close()
            proc.destroy()
            println(tmp.resolve("peer.log").takeIf { it.exists() }?.readText())
        }
    }
}
