package ru.mobile.mys.mesh

import kotlin.test.assertEquals
import org.junit.Test

class VectorsTest {
    private val v = Vectors.root.getJSONObject("mesh")

    @Test fun tagKeyAndTags() {
        val key = tagKey(v.getString("prs").unhex())
        assertEquals(v.getString("tag_key"), key.hex())
        val tags = v.getJSONArray("tags")
        for (i in 0 until tags.length()) {
            val t = tags.getJSONObject(i)
            assertEquals(t.getString("tag"), tagForHour(key, t.getLong("hour")).hex())
        }
    }

    @Test fun packetsEncodeAndDecode() {
        val ps = v.getJSONArray("packets")
        for (i in 0 until ps.length()) {
            val j = ps.getJSONObject(i)
            val p = Packet(
                j.getInt("type"), j.getString("id").unhex(), j.getInt("ttl"), j.getInt("hops"),
                j.getString("tag").unhex(), j.getString("nonce").unhex(), j.getString("route_id").unhex(), j.getString("payload").unhex(),
            )
            assertEquals(j.getString("hex"), p.encode().hex())
            assertEquals(j.getString("hex"), Packet.decode(j.getString("hex").unhex()).encode().hex())
        }
    }

    @Test fun hourOfFloors() = assertEquals(2L, hourOf(7_200_500L))
}
