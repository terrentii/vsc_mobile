package ru.mobile.mys.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ProtocolTest {
    private val room = "room"
    private fun det(name: String) = Vectors.root.getJSONObject("frames").getString(name)

    @Test fun roomParamsMatchDesktop() {
        val list = Vectors.root.getJSONArray("room_params")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            val (roomId, prs) = deriveRoomParams(v.getString("phrase"))
            assertEquals(v.getString("room_id"), roomId.hex(), v.getString("phrase"))
            assertEquals(v.getString("prs"), prs.hex())
        }
    }

    @Test fun framesMatchDesktopAndRoundTrip() {
        val pakeFrame = det("pake").unhex()
        val y = (decodeMessage(pakeFrame) as Msg.Pake).y
        val roomId = (decodeMessage(det("hello").unhex()) as Msg.Hello).roomId
        val cases = mapOf(
            "hello" to Msg.Hello(roomId, listOf("127.0.0.1" to 5555, "192.168.1.2" to 40000)),
            "hello_empty" to Msg.Hello(roomId),
            "pair_initiator" to Msg.Pair(Role.INITIATOR),
            "pair_responder" to Msg.Pair(Role.RESPONDER, listOf("10.0.0.1" to 1)),
            "pake" to Msg.Pake(y),
            "pake_ad" to Msg.Pake(y, "ad".toByteArray()),
            "confirm" to Msg.Confirm((decodeMessage(det("confirm").unhex()) as Msg.Confirm).mac),
            "data" to Msg.Data((decodeMessage(det("data").unhex()) as Msg.Data).sealed),
            "relay" to Msg.Relay(pakeFrame),
            "punch" to Msg.Punch,
            "punch_ack" to Msg.PunchAck,
            "peer_left" to Msg.PeerLeft,
        )
        for ((name, msg) in cases) {
            assertEquals(det(name), encodeMessage(msg).hex(), name)
            assertEquals(det(name), encodeMessage(decodeMessage(det(name).unhex())).hex(), name)
        }
        assertEquals(32, roomId.size, room)
    }

    @Test fun pairParsesRoleAndCandidates() {
        val p = assertIs<Msg.Pair>(decodeMessage(det("pair_responder").unhex()))
        assertEquals(Role.RESPONDER, p.role)
        assertEquals(listOf("10.0.0.1" to 1), p.peerCandidates)
    }

    @Test fun malformedFramesRejected() {
        assertFailsWith<TransportException> { decodeMessage(byteArrayOf(8, 0, 0)) }
        assertFailsWith<TransportException> { decodeMessage(byteArrayOf(8, 0, 0, 0, 0, 9, 1)) }
        assertFailsWith<TransportException> { decodeMessage(byteArrayOf(42, 0, 0, 0, 0, 0)) }
        assertFailsWith<TransportException> { decodeMessage(encodeFrame(MsgType.PAKE, byteArrayOf(0, 5, 1))) }
        assertFailsWith<TransportException> { decodeMessage(encodeFrame(MsgType.PAIR, ByteArray(0))) }
    }

    @Test fun decodeFrameReportsConsumedForStream() {
        val a = encodeMessage(Msg.Confirm(byteArrayOf(1, 2)))
        val f = decodeFrame(a + encodeMessage(Msg.Punch))
        assertEquals(a.size, f.consumed)
        assertEquals(MsgType.CONFIRM, f.type)
    }
}
