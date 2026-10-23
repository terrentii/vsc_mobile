package ru.mobile.mys.central

import kotlin.test.assertEquals
import org.json.JSONObject
import org.junit.Test

/** Свои сообщения с сервера без локальной копии (переустановка, веб) — исходящие. */
class OwnMessagesTest {
    private fun frame(id: Long, sender: String) = JSONObject()
        .put("room_id", 7).put("id", id).put("sender", sender).put("body", "текст $id").put("created_at", "2026-10-23T10:00:00")

    @Test fun ownSenderIsStoredAsOutgoing() {
        val store = MemoryCentralStore()
        val engine = SyncEngine(store, RestClient("http://127.0.0.1:1"), ownUsername = "me")
        engine.ingestWs(frame(1, "me"))
        engine.ingestWs(frame(2, "other"))
        assertEquals(listOf("out" to "sent", "in" to "received"), store.rows.map { it.direction to it.status })
    }

    @Test fun claimOwnRepairsEarlierIncoming() {
        val store = MemoryCentralStore()
        SyncEngine(store, RestClient("http://127.0.0.1:1")).run { ingestWs(frame(1, "me")); ingestWs(frame(2, "other")) }
        store.claimOwn("me")
        assertEquals(listOf("out", "in"), store.rows.map { it.direction })
    }
}
