package ru.mobile.mys.crypto

internal val EMPTY = ByteArray(0)

internal fun Int.u32be(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte(),
)

internal fun ByteArray.u32be(off: Int): Int =
    ((this[off].toInt() and 0xff) shl 24) or
        ((this[off + 1].toInt() and 0xff) shl 16) or
        ((this[off + 2].toInt() and 0xff) shl 8) or
        (this[off + 3].toInt() and 0xff)

/** Лексикографическое сравнение как у Python `bytes` (Arrays.compareUnsigned — только API 33+). */
fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return a.size - b.size
}
