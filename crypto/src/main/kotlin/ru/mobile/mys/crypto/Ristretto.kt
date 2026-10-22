package ru.mobile.mys.crypto

import com.goterl.lazysodium.interfaces.Ristretto255

class RistrettoException(message: String) : Exception(message)

/**
 * ristretto255 из libsodium. Реализацию `Native` даёт платформа:
 * `LazySodiumAndroid(SodiumAndroid())` в приложении, `LazySodiumJava(SodiumJava())` в тестах.
 */
class Ristretto(private val sodium: Ristretto255.Native) {
    fun fromHash(hash: ByteArray): ByteArray {
        require(hash.size == 64) { "from_hash ждёт 64 байта" }
        val out = ByteArray(32)
        if (!sodium.cryptoCoreRistretto255FromHash(out, hash)) throw RistrettoException("from_hash провалился")
        return out
    }

    /** scalar·point; отказ на identity/невалидной точке. */
    fun scalarmult(scalar: ByteArray, point: ByteArray): ByteArray {
        require(scalar.size == 32 && point.size == 32) { "неверная длина скаляра или точки" }
        val out = ByteArray(32)
        if (!sodium.cryptoScalarmultRistretto255(out, scalar, point)) {
            throw RistrettoException("scalarmult: identity или невалидная точка")
        }
        return out
    }

    fun scalarRandom(): ByteArray = ByteArray(32).also { sodium.cryptoCoreRistretto255ScalarRandom(it) }
}
