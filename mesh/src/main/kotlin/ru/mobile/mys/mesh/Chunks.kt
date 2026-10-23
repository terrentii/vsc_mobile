package ru.mobile.mys.mesh

import java.io.ByteArrayOutputStream

/** Кадр линка → куски по MTU: `u8 флаги (1 = последний) ‖ данные` (BLE). */
fun chunk(frame: ByteArray, size: Int): List<ByteArray> {
    require(size >= 2) { "кусок меньше 2 байт" }
    val step = size - 1
    val n = maxOf(1, (frame.size + step - 1) / step)
    return List(n) { i ->
        val part = frame.copyOfRange(i * step, minOf(frame.size, (i + 1) * step))
        byteArrayOf(if (i == n - 1) 1 else 0) + part
    }
}

/** Сборка кусков обратно в кадр; больше [MAX_FRAME] — ошибка (сосед мусорит). */
class Reassembler {
    private val buf = ByteArrayOutputStream()

    /** → готовый кадр или null, если ждём ещё кусков. */
    fun push(piece: ByteArray): ByteArray? {
        if (piece.isEmpty()) throw WireClosed("пустой кусок")
        buf.write(piece, 1, piece.size - 1)
        if (buf.size() > MAX_FRAME) throw WireClosed("кадр больше MAX_FRAME")
        if (piece[0].toInt() and 1 == 0) return null
        return buf.toByteArray().also { buf.reset() }
    }
}
