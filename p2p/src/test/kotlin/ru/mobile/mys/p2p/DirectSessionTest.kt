package ru.mobile.mys.p2p

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Сессия с relay и прямым транспортом (WebRTC подменён in-memory). */
class DirectSessionTest {
    private class Counting(val inner: Transport) : Transport {
        val sent = AtomicInteger()
        override suspend fun send(data: ByteArray) { sent.incrementAndGet(); inner.send(data) }
        override suspend fun recv() = inner.recv()
        override suspend fun close() = inner.close()
    }

    private class Side(val store: MemoryStore, val conv: Long, val session: Session, val relay: Counting) {
        val signals = CopyOnWriteArrayList<String>()
        val disconnected = AtomicBoolean(false)
        val directClosed = AtomicInteger()
        fun incoming() = store.messages(conv).filter { it.direction == "in" }.map { it.body.decodeToString() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun pair(): kotlin.Pair<Side, Side> = runBlocking {
        val prs = deriveRoomParams("прямой").second
        val (ta, tb) = InMemoryTransport.pair()
        val ha = async { handshake(ta, prs, Role.INITIATOR, ristretto) }
        val hb = async { handshake(tb, prs, Role.RESPONDER, ristretto) }
        fun side(t: InMemoryTransport, h: HandshakeResult): Side {
            val store = MemoryStore()
            val conv = store.conversationFor(byteArrayOf(1))
            val relay = Counting(t)
            lateinit var s: Side
            val session = openSession(store.session(conv), relay, h.sk, h.ratchetState,
                onDisconnect = { s.disconnected.set(true) }, onSignal = { s.signals += it },
                onDirectClosed = { s.directClosed.incrementAndGet() })
            s = Side(store, conv, session, relay)
            session.start(scope)
            return s
        }
        val a = side(ta, ha.await())
        val b = side(tb, hb.await())
        a.session.sendPrime()
        eventually { b.session.canSend }
        a to b
    }

    private fun attachDirect(a: Side, b: Side): kotlin.Pair<InMemoryTransport, InMemoryTransport> {
        val (da, db) = InMemoryTransport.pair()
        a.session.attachDirect(da)
        b.session.attachDirect(db)
        return da to db
    }

    @Test fun sendsGoOverDirectOnceAttached() = runBlocking {
        val (a, b) = pair()
        attachDirect(a, b)
        assertTrue(a.session.isDirect)
        val before = a.relay.sent.get()
        a.session.send("по прямому")
        b.session.send("и обратно")
        eventually { b.incoming() == listOf("по прямому") && a.incoming() == listOf("и обратно") }
        assertEquals(before, a.relay.sent.get())
        scope.cancel()
    }

    @Test fun directDropFallsBackToRelay() = runBlocking {
        val (a, b) = pair()
        val (_, db) = attachDirect(a, b)
        db.close() // прямой канал умер
        eventually { !a.session.isDirect && a.directClosed.get() == 1 }
        a.session.send("через сервер")
        eventually { b.incoming() == listOf("через сервер") }
        assertFalse(a.disconnected.get() || b.disconnected.get())
        scope.cancel()
    }

    @Test fun signalsReachCallbackAndAreNotStored() = runBlocking {
        val (a, b) = pair()
        a.session.sendSignal("""{"t":"offer","sdp":"v=0"}""")
        b.session.sendSignal("""{"t":"answer","sdp":"v=0"}""")
        eventually { b.signals.size == 1 && a.signals.size == 1 }
        assertEquals("""{"t":"offer","sdp":"v=0"}""", b.signals.single())
        assertTrue(a.store.messages(a.conv).isEmpty() && b.store.messages(b.conv).isEmpty())
        scope.cancel()
    }

    /** Ответчик подключил прямой раньше, чем инициатор получил answer: сигнал — только через relay. */
    @Test fun signalGoesViaRelayEvenWhenDirectAttached() = runBlocking {
        val (a, b) = pair()
        val (da, _) = InMemoryTransport.pair()
        b.session.attachDirect(InMemoryTransport.pair().second) // у b прямой есть, у a — ещё нет
        b.session.sendSignal("""{"t":"answer"}""")
        eventually { a.signals.size == 1 }
        da.close()
        scope.cancel()
    }

    @Test fun relayLossKeepsSessionWhileDirectAlive() = runBlocking {
        val (a, b) = pair()
        val (da, _) = attachDirect(a, b)
        a.relay.close() // relay у обоих рвётся
        delay(200)
        assertFalse(b.disconnected.get())
        a.session.send("живой по прямому")
        eventually { b.incoming() == listOf("живой по прямому") }
        da.close()
        eventually { b.disconnected.get() }
        scope.cancel()
    }
}
