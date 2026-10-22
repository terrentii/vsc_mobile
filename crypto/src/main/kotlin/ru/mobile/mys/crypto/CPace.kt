package ru.mobile.mys.crypto

/*
 * CPace на ristretto255 — порт mys_crypto/pake.py (упрощённое lv-кодирование
 * транскрипта с DSI-метками МЫС; совместимость только с десктопом МЫС).
 */

private val DSI_GEN = "MYS-CPace-ristretto255-gen".toByteArray()
private val DSI_ISK = "MYS-CPace-ristretto255-ISK".toByteArray()

private fun lv(chunk: ByteArray): ByteArray {
    require(chunk.size <= 0xFFFF) { "поле CPace длиннее 65535 байт" }
    return byteArrayOf((chunk.size ushr 8).toByte(), chunk.size.toByte()) + chunk
}

/** Эфемерное состояние стороны между cpaceMsg и cpaceFinish. */
class CPaceState(val y: ByteArray, val bigY: ByteArray, val sid: ByteArray)

fun cpaceGenerator(r: Ristretto, prs: ByteArray, sid: ByteArray, ci: ByteArray = EMPTY, ad: ByteArray = EMPTY): ByteArray =
    r.fromHash(sha512(DSI_GEN + lv(prs) + lv(sid) + lv(ci) + lv(ad)))

/** `y` переопределяется только в тестах (фиксированные скаляры из векторов). */
fun cpaceMsg(
    r: Ristretto, prs: ByteArray, sid: ByteArray, ci: ByteArray = EMPTY, ad: ByteArray = EMPTY,
    y: ByteArray = r.scalarRandom(),
): Pair<CPaceState, ByteArray> {
    val bigY = r.scalarmult(y, cpaceGenerator(r, prs, sid, ci, ad))
    return CPaceState(y, bigY, sid) to bigY
}

/** 64-байтовый ISK; RistrettoException на identity/битой точке пира. */
fun cpaceFinish(r: Ristretto, state: CPaceState, peerY: ByteArray): ByteArray {
    val k = r.scalarmult(state.y, peerY)
    val (low, high) = if (compareUnsigned(state.bigY, peerY) <= 0) state.bigY to peerY else peerY to state.bigY
    return sha512(DSI_ISK + lv(state.sid) + lv(low) + lv(high) + lv(k))
}
