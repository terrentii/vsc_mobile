package ru.mobile.mys.mesh

import java.nio.ByteBuffer
import org.bouncycastle.crypto.digests.Blake2bDigest
import ru.mobile.mys.crypto.hkdf

/** Ключ меток из prs — не из room_id: room_id видит сервер. */
fun tagKey(prs: ByteArray): ByteArray = hkdf(prs, 32, ByteArray(0), "mys-mesh-tag-key".toByteArray())

/** Метка канала на час: ретрансляторы не могут связать канал между часами. */
fun tagForHour(key: ByteArray, hour: Long): ByteArray {
    val d = Blake2bDigest(key, 32, null, "mys-mesh-tag".toByteArray().copyOf(16))
    val data = ByteBuffer.allocate(8).putLong(hour).array()
    d.update(data, 0, data.size)
    return ByteArray(32).also { d.doFinal(it, 0) }
}

fun hourOf(unixMillis: Long): Long = Math.floorDiv(unixMillis, 3_600_000L)
