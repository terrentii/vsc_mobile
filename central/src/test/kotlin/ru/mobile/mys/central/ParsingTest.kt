package ru.mobile.mys.central

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.json.JSONObject

class ParsingTest {
    @Test fun roomWithAndWithoutOptionalFields() {
        val r = parseRoom(JSONObject("""{"id":7,"name":null,"is_direct":true,"updated_at":"t","code":"1234567890"}"""))
        assertEquals(listOf<Any?>(7L, null, true, "1234567890"), listOf(r.id, r.name, r.isDirect, r.code))
        assertNull(parseRoom(JSONObject("""{"id":1}""")).code)
    }

    @Test fun messageWithReplyAndMedia() {
        val m = parseMessage(JSONObject("""{"id":5,"room_id":2,"sender":"a","body":null,"created_at":"2026-01-01T00:00:00",
            "client_msg_id":null,"media":"0123_фото.png","reply_to":{"id":3,"sender":"b","body":"цитата"}}"""), 9)
        assertEquals(listOf<Any?>(5L, 2L, "a", "", null, "0123_фото.png"), listOf(m.id, m.roomId, m.sender, m.body, m.clientMsgId, m.media))
        assertEquals(listOf<Any?>(3L, "b", "цитата"), listOf(m.reply!!.id, m.reply!!.sender, m.reply!!.body))
        assertEquals(9L, parseMessage(JSONObject("""{"id":1,"sender":"x","body":"y","created_at":"t"}"""), 9).roomId)
    }

    @Test fun helpers() {
        assertEquals("wss://soufos.ru/ws", wsUrlFor("https://soufos.ru/"))
        assertEquals("ws://127.0.0.1:5000/ws", wsUrlFor("http://127.0.0.1:5000"))
        assertNull(wsUrlFor("ftp://x"))
        assertEquals(0L, epochMs("1970-01-01T00:00:00"))
        assertEquals(1_000L, epochMs("1970-01-01T00:00:01+00:00"))
        assertNull(epochMs("вчера"))
        assertEquals("фото.png", displayName("0123abcd_фото.png"))
        assertEquals("image", kindForFilename("a.JPEG"))
        assertEquals("file", kindForFilename("a.pdf"))
    }
}
