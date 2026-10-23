package ru.mobile.mys.p2p

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import ru.mobile.mys.mesh.LinkCrypto
import ru.mobile.mys.mesh.MemoryWire
import ru.mobile.mys.mesh.MeshConfig
import ru.mobile.mys.mesh.MeshInternet
import ru.mobile.mys.mesh.MeshNode

/** Выбор пути: сервер недоступен → mesh через публичный узел; повтор узла с паузой. */
class MeshPathTest {
    private val sodium = LazySodiumJava(SodiumJava())
    private val crypto = LinkCrypto(sodium, sodium)
    private fun fast() = MeshConfig(rreqRetryMs = 200, sweepMs = 200)

    @Test fun serverDownGoesThroughMesh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hub = MeshNode(crypto, fast())
        val a = MeshNode(crypto, fast())
        val b = MeshNode(crypto, fast())
        val connect: suspend (String) -> MemoryWire = {
            val (mine, theirs) = MemoryWire.pair()
            scope.launch { hub.serve(theirs) }
            mine
        }
        val nets = listOf(a, b).map { MeshInternet(it, { listOf("wss://hub/mesh") }, connect, tickMs = 50).also { n -> n.start(scope) } }
        try {
            while (nets.any { it.connected().isEmpty() }) delay(20)
            val prs = ByteArray(32) { it.toByte() }
            val http = OkHttpClient()
            val down = "ws://127.0.0.1:1/p2p"
            val pa = async(Dispatchers.Default) { pair(http, down, a, ByteArray(32), prs, 10_000) }
            val pb = async(Dispatchers.Default) { pair(http, down, b, ByteArray(32), prs, 10_000) }
            val (x, y) = pa.await() to pb.await()
            assertTrue(x.mesh && y.mesh)
            assertEquals(setOf(Role.INITIATOR, Role.RESPONDER), setOf(x.role, y.role))
            val hx = async { handshake(x.transport, prs, x.role, ristretto) }
            val hy = async { handshake(y.transport, prs, y.role, ristretto) }
            assertEquals(hx.await().sk.hex(), hy.await().sk.hex())
            assertTrue(hub.stats().getValue("relayed_bytes") > 0)
        } finally {
            nets.forEach { it.stop() }
            listOf(a, b, hub).forEach { it.close() }
            scope.cancel()
        }
    }

    @Test fun unreachableNodeRetriesWithBackoff() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val node = MeshNode(crypto, fast())
        var calls = 0
        val net = MeshInternet(node, { listOf("wss://down/mesh") }, { calls++; throw java.io.IOException("нет сети") }, tickMs = 10)
        net.start(scope)
        delay(300)
        net.stop()
        node.close()
        scope.cancel()
        assertEquals(1, calls) // следующая попытка — не раньше чем через 2 с
    }

    @Test fun meshSearchTimeoutIsOrdinaryFailure() = runBlocking {
        val lonely = MeshNode(crypto, fast())
        try {
            val e = runCatching { pair(OkHttpClient(), "ws://127.0.0.1:1/p2p", lonely, ByteArray(32), ByteArray(32), 1_000) }.exceptionOrNull()
            assertTrue(e != null && e !is kotlinx.coroutines.CancellationException, "ошибка сервера, а не отмена: $e")
        } finally { lonely.close() }
    }
}
