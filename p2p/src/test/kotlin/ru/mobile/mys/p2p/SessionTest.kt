package ru.mobile.mys.p2p

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class SessionTest {
    private class Side(val store: MemoryStore, val conv: Long, val session: Session, val transport: InMemoryTransport)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val disconnected = AtomicBoolean(false)

    private fun pair(): kotlin.Pair<Side, Side> = runBlocking {
        val prs = deriveRoomParams("сессия").second
        val (ta, tb) = InMemoryTransport.pair()
        val ha = async { handshake(ta, prs, Role.INITIATOR, ristretto) }
        val hb = async { handshake(tb, prs, Role.RESPONDER, ristretto) }
        fun side(t: InMemoryTransport, h: HandshakeResult, room: Byte): Side {
            val store = MemoryStore()
            val conv = store.conversationFor(byteArrayOf(room))
            val s = openSession(store.session(conv), t, h.sk, h.ratchetState, onDisconnect = { disconnected.set(true) })
            s.start(scope)
            return Side(store, conv, s, t)
        }
        side(ta, ha.await(), 1) to side(tb, hb.await(), 2)
    }

    private fun Side.incoming() = store.messages(conv).filter { it.direction == "in" }

    @Test fun responderQueuesUntilPrimeThenBothWays() = runBlocking {
        val (alice, bob) = pair()
        bob.session.send("рано")
        assertEquals("pending", bob.store.messages(bob.conv).single().status)
        alice.session.sendPrime()
        eventually { alice.incoming().size == 1 }
        assertEquals("рано", alice.incoming()[0].body.decodeToString())
        assertEquals("sent", bob.store.messages(bob.conv).single().status)
        alice.session.send("привет")
        eventually { bob.incoming().size == 1 }
        assertEquals("привет", bob.incoming()[0].body.decodeToString())
        scope.cancel()
    }

    @Test fun fileArrivesWhole() = runBlocking {
        val (alice, bob) = pair()
        alice.session.sendPrime()
        eventually { bob.session.canSend }
        val data = Random(7).nextBytes(200_000)
        alice.session.sendFile("снимок.png", "image/png", data)
        eventually { bob.incoming().size == 1 }
        val got = bob.incoming()[0]
        assertContentEquals(data, got.body)
        assertEquals(listOf("image", "снимок.png"), listOf(got.kind, got.filename))
        scope.cancel()
    }

    @Test fun garbageIsDroppedSessionSurvives() = runBlocking {
        val (alice, bob) = pair()
        alice.transport.send(byteArrayOf(1, 2, 3))
        alice.transport.send(encodeMessage(Msg.Data(Random(1).nextBytes(80))))
        alice.transport.send(encodeMessage(Msg.Confirm(ByteArray(32))))
        alice.session.send("живой")
        eventually { bob.incoming().size == 1 }
        assertEquals("живой", bob.incoming()[0].body.decodeToString())
        scope.cancel()
    }

    @Test fun ratchetResumesFromStore() = runBlocking {
        val (alice, _) = pair()
        alice.session.send("один")
        val saved = alice.store.session(alice.conv).loadRatchet()!!
        val resumed = openSession(alice.store.session(alice.conv), InMemoryTransport.pair().first, ByteArray(32), saved)
        assertTrue(resumed.canSend)
        scope.cancel()
    }

    @Test fun peerCloseNotifiesDisconnect() = runBlocking {
        val (alice, _) = pair()
        alice.session.close()
        eventually { disconnected.get() }
        scope.cancel()
    }
}
