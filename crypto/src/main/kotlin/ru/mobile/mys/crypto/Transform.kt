package ru.mobile.mys.crypto

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

/*
 * МЫС-transform: обратимая ключевая биекция над байтами AEAD-шифротекста
 * (правила — vsc_desktop/CLAUDE.md). Порт mys_crypto/transform.py.
 *
 * Python берёт ChaCha20 из `cryptography` с 16-байтовым nonce = 4 байта счётчика
 * (LE) + 12 байт nonce; при нулевом nonce это RFC 7539 ChaCha20 с 12 нулевыми
 * байтами nonce и счётчиком 0 — ровно ChaCha7539Engine.
 */

private fun keystream(key: ByteArray, length: Int): ByteArray {
    if (length == 0) return EMPTY
    val subkey = hkdf(key, 32, EMPTY, "mys-transform-ks-key".toByteArray())
    val engine = ChaCha7539Engine()
    engine.init(true, ParametersWithIV(KeyParameter(subkey), ByteArray(12)))
    val out = ByteArray(length)
    engine.processBytes(ByteArray(length), 0, length, out, 0)
    return out
}

private fun buildSbox(key: ByteArray): Pair<IntArray, IntArray> {
    val stream = hkdf(key, 256, EMPTY, "mys-transform-sbox".toByteArray())
    val sbox = IntArray(256) { it }
    for (i in 255 downTo 1) {
        val j = (stream[255 - i].toInt() and 0xff) % (i + 1)
        val t = sbox[i]; sbox[i] = sbox[j]; sbox[j] = t
    }
    val inv = IntArray(256)
    for (index in 0 until 256) inv[sbox[index]] = index
    return sbox to inv
}

fun transformEncode(data: ByteArray, key: ByteArray): ByteArray {
    val ks = keystream(key, data.size)
    val (sbox, _) = buildSbox(key)
    return ByteArray(data.size) { i -> sbox[(data[i].toInt() + ks[i].toInt()) and 0xff].toByte() }
}

fun transformDecode(data: ByteArray, key: ByteArray): ByteArray {
    val ks = keystream(key, data.size)
    val (_, inv) = buildSbox(key)
    return ByteArray(data.size) { i -> (inv[data[i].toInt() and 0xff] - ks[i].toInt()).and(0xff).toByte() }
}
