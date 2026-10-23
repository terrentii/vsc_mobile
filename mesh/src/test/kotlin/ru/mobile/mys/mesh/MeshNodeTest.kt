package ru.mobile.mys.mesh

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.mobile.mys.p2p.Role
import ru.mobile.mys.p2p.handshake

class MeshNodeTest {
    private val prs = ByteArray(32) { it.toByte() }
    private val key = tagKey(prs)

    private fun fast(relay: Boolean = true) = MeshConfig(relay = relay, rreqRetryMs = 200, sweepMs = 200)

    private suspend fun link(a: MeshNode, b: MeshNode): Pair<Int, Int> = coroutineScope {
        val (wa, wb) = MemoryWire.pair()
        val x = async { a.attach(wa) }
        val y = async { b.attach(wb) }
        x.await() to y.await()
    }

    private suspend fun chain(n: Int) = List(n) { MeshNode(crypto, fast()) }.also { ns -> ns.zipWithNext().forEach { (x, y) -> link(x, y) } }

    private suspend fun both(a: MeshNode, b: MeshNode, ms: Long = 10_000) = coroutineScope {
        listOf(async { a.find(key, ms) }, async { b.find(key, ms) }).awaitAll()
    }

    private suspend fun closeAll(ns: List<MeshNode>) = ns.forEach { it.close() }

    @Test fun chainFindsAndHandshakes() = runBlocking {
        val ns = chain(6)
        try {
            val (a, b) = both(ns.first(), ns.last())
            assertEquals(setOf(MeshRole.INITIATOR, MeshRole.RESPONDER), setOf(a.first, b.first))
            val (ha, hb) = coroutineScope {
                listOf(
                    async { handshake(Adapter(a.second), prs, Role.valueOf(a.first.name), ristretto) },
                    async { handshake(Adapter(b.second), prs, Role.valueOf(b.first.name), ristretto) },
                ).awaitAll()
            }
            assertEquals(ha.sk.hex(), hb.sk.hex())
            a.second.send("privet".toByteArray())
            assertEquals("privet", b.second.recv().decodeToString())
            b.second.send("otvet".toByteArray())
            assertEquals("otvet", a.second.recv().decodeToString())
            assertTrue(ns[2].stats().getValue("relayed_bytes") > 0)
        } finally { closeAll(ns) }
    }

    @Test fun gridRelaysEachRreqOnce() = runBlocking {
        val ns = List(9) { MeshNode(crypto, MeshConfig(rreqRetryMs = 60_000, sweepMs = 60_000)) }
        for (i in 0 until 9) {
            if (i % 3 < 2) link(ns[i], ns[i + 1])
            if (i < 6) link(ns[i], ns[i + 3])
        }
        try {
            assertFailsWith<TimeoutCancellationException> { ns[0].find(key, 500) }
            val counts = ns.map { it.stats().getValue("rreq_forwarded") }
            assertTrue(counts[0] == 0L && counts.drop(1).all { it == 1L }, "$counts")
        } finally { closeAll(ns) }
    }

    @Test fun rerouteAfterBreak() = runBlocking {
        val (a, b, c, d) = List(4) { MeshNode(crypto, fast()) }
        val (ab, _) = link(a, b)
        val (ac, _) = link(a, c)
        link(b, d)
        link(c, d)
        try {
            val (x, y) = both(a, d)
            a.detach(if (b.stats().getValue("routes") > 0) ab else ac)
            for (t in listOf(x.second, y.second)) assertFailsWith<MeshClosed> { withTimeout(5_000) { t.recv() } }
            val (x2, y2) = both(a, d)
            x2.second.send("x".toByteArray())
            assertEquals("x", y2.second.recv().decodeToString())
        } finally { closeAll(listOf(a, b, c, d)) }
    }

    @Test fun simultaneousSearchMakesOneRoute() = runBlocking {
        val a = MeshNode(crypto, fast())
        val b = MeshNode(crypto, fast())
        link(a, b)
        try {
            val (x, y) = both(a, b, 5_000)
            assertEquals(setOf(MeshRole.INITIATOR, MeshRole.RESPONDER), setOf(x.first, y.first))
            delay(500)
            assertEquals(1L, a.stats().getValue("routes"))
            assertEquals(1L, b.stats().getValue("routes"))
        } finally { closeAll(listOf(a, b)) }
    }

    @Test fun noPeerTimesOut() = runBlocking {
        val ns = chain(3)
        try { assertFailsWith<TimeoutCancellationException> { ns[0].find(key, 500) } } finally { closeAll(ns) }
        Unit
    }

    @Test fun floodIsRateLimited() = runBlocking {
        val a = MeshNode(crypto, fast())
        val b = MeshNode(crypto, fast())
        link(a, b)
        val (wa, wx) = MemoryWire.pair()
        val evil = coroutineScope {
            val att = async { a.attach(wa) }
            val l = async { Link.open(wx, crypto) }
            att.await()
            l.await()
        }
        try {
            repeat(500) {
                evil.send(Packet.rreq(Random.nextBytes(16), Random.nextBytes(32), Random.nextBytes(16), 8).encode())
            }
            delay(300)
            assertTrue(a.stats().getValue("rreq_forwarded") <= 15)
            val (x, y) = both(a, b, 5_000)
            assertEquals(setOf(MeshRole.INITIATOR, MeshRole.RESPONDER), setOf(x.first, y.first))
        } finally { closeAll(listOf(a, b)) }
    }

    @Test fun relayOffBlocksTransitButNotOwn() = runBlocking {
        val a = MeshNode(crypto, fast())
        val r = MeshNode(crypto, fast(relay = false))
        val b = MeshNode(crypto, fast())
        link(a, r)
        link(r, b)
        try {
            val res = coroutineScope {
                listOf(async { runCatching { a.find(key, 1_000) } }, async { runCatching { b.find(key, 1_000) } }).awaitAll()
            }
            assertTrue(res.all { it.exceptionOrNull() is TimeoutCancellationException }, "$res")
            val (x, y) = both(a, r, 5_000)
            assertEquals(setOf(MeshRole.INITIATOR, MeshRole.RESPONDER), setOf(x.first, y.first))
        } finally { closeAll(listOf(a, r, b)) }
    }
}

class MeshPeersTest {
    @Test fun peersRoundtripAndValidation() {
        val body = encodePeers(listOf("wss://a.ru/mesh", "ws://127.0.0.1:5/mesh", "http://bad", "wss://x y"))
        assertEquals(listOf("wss://a.ru/mesh", "ws://127.0.0.1:5/mesh"), decodePeers(body))
        for (bad in listOf("ftp://x", "wss://" + "a".repeat(300), List(17) { "wss://a" }.joinToString("\n"))) {
            assertFailsWith<MeshProtocolException> { Packet.decode(byteArrayOf(1, PEERS.toByte(), 0, 0) + ByteArray(16) + bad.toByteArray()) }
        }
    }

    @Test fun publicNodeSharesItsUrl() = runBlocking {
        val hub = MeshNode(crypto, MeshConfig(publicUrl = "wss://hub.example/mesh", rreqRetryMs = 200, sweepMs = 200))
        val client = MeshNode(crypto, MeshConfig(rreqRetryMs = 200, sweepMs = 200))
        val learned = mutableListOf<String>()
        client.onPeers = { learned += it }
        val (wa, wb) = MemoryWire.pair()
        try {
            coroutineScope { listOf(async { hub.attach(wa) }, async { client.attach(wb) }).awaitAll() }
            withTimeout(2_000) { while (learned.isEmpty()) delay(20) }
            assertEquals(listOf("wss://hub.example/mesh"), learned)
            assertEquals(learned, client.knownUrls)
        } finally { hub.close(); client.close() }
    }
}
