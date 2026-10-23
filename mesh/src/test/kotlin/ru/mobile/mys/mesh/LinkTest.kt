package ru.mobile.mys.mesh

import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test

class LinkTest {
    private suspend fun links(a: Wire, b: Wire) = kotlinx.coroutines.coroutineScope {
        listOf(async { Link.open(a, crypto) }, async { Link.open(b, crypto) }).awaitAll()
    }

    @Test fun handshakeAndExchange() = runBlocking {
        val (wa, wb) = MemoryWire.pair()
        val (la, lb) = links(wa, wb)
        la.send("ping".toByteArray())
        lb.send("pong".toByteArray())
        assertEquals("ping", lb.recv().decodeToString())
        assertEquals("pong", la.recv().decodeToString())
    }

    @Test fun garbageBreaksLink() = runBlocking {
        val (wa, wb) = MemoryWire.pair()
        val (_, lb) = links(wa, wb)
        wa.send(ByteArray(40))
        assertFailsWith<MeshProtocolException> { lb.recv() }
        Unit
    }

    @Test fun badMagicRejected() = runBlocking {
        val (wa, wb) = MemoryWire.pair()
        val opening = async { runCatching { Link.open(wb, crypto) } }
        wa.send("XXXX".toByteArray() + byteArrayOf(1) + ByteArray(32))
        assertTrue(opening.await().exceptionOrNull() is MeshProtocolException)
    }

    @Test fun oversizeAndCloseEndWire() = runBlocking {
        val (wa, wb) = MemoryWire.pair()
        wa.send(ByteArray(MAX_FRAME + 1))
        assertFailsWith<WireClosed> { wb.recv() }
        wa.close()
        assertFailsWith<WireClosed> { wb.recv() }
        assertFailsWith<WireClosed> { wa.recv() }
        Unit
    }

    @Test fun tcpWire() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = async(Dispatchers.IO) { TcpWire(server.accept()) }
            val client = TcpWire.connect("127.0.0.1", server.localPort)
            val (la, lb) = links(client, accepted.await())
            la.send("tcp".toByteArray())
            assertEquals("tcp", lb.recv().decodeToString())
            la.close()
            assertFailsWith<WireClosed> { lb.recv() }
        }
        Unit
    }
}
