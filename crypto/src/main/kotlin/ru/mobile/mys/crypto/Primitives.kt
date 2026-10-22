package ru.mobile.mys.crypto

import java.security.SecureRandom
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519

/** Пара X25519: (private, public), по 32 байта — как кортеж в десктопе. */
typealias KeyPair = Pair<ByteArray, ByteArray>

private val rng = SecureRandom()

fun generateX25519Keypair(): KeyPair {
    val priv = ByteArray(32)
    X25519.generatePrivateKey(rng, priv)
    return x25519KeypairFromSeed(priv)
}

/** Детерминированная пара из 32-байтового seed (хендшейк выводит DH Боба из ISK). */
fun x25519KeypairFromSeed(seed: ByteArray): KeyPair {
    require(seed.size == 32) { "seed X25519 — 32 байта" }
    val pub = ByteArray(32)
    X25519.generatePublicKey(seed, 0, pub, 0)
    return seed.copyOf() to pub
}

fun x25519Shared(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
    val out = ByteArray(32)
    require(X25519.calculateAgreement(privateKey, 0, peerPublic, 0, out, 0)) {
        "X25519: нулевой общий секрет (невалидный ключ пира)"
    }
    return out
}

fun aeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
    chachaPoly(true, key, nonce, plaintext, aad)

/** Бросает InvalidCipherTextException при провале аутентификации. */
fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
    chachaPoly(false, key, nonce, ciphertext, aad)

private fun chachaPoly(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray): ByteArray {
    val c = ChaCha20Poly1305()
    c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
    val out = ByteArray(c.getOutputSize(input.size))
    var n = c.processBytes(input, 0, input.size, out, 0)
    n += c.doFinal(out, n)
    return out.copyOf(n)
}

fun hkdf(ikm: ByteArray, length: Int, salt: ByteArray, info: ByteArray): ByteArray {
    val gen = HKDFBytesGenerator(SHA256Digest())
    gen.init(HKDFParameters(ikm, salt, info))
    val out = ByteArray(length)
    gen.generateBytes(out, 0, length)
    return out
}

fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = HMac(SHA256Digest())
    mac.init(KeyParameter(key))
    mac.update(data, 0, data.size)
    return ByteArray(32).also { mac.doFinal(it, 0) }
}

fun sha512(data: ByteArray): ByteArray {
    val d = SHA512Digest()
    d.update(data, 0, data.size)
    return ByteArray(64).also { d.doFinal(it, 0) }
}

fun argon2id(
    password: ByteArray, salt: ByteArray, length: Int,
    timeCost: Int = 3, memoryCostKb: Int = 65536, parallelism: Int = 4,
): ByteArray {
    val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withIterations(timeCost)
        .withMemoryAsKB(memoryCostKb)
        .withParallelism(parallelism)
        .withSalt(salt)
        .build()
    val gen = Argon2BytesGenerator()
    gen.init(params)
    return ByteArray(length).also { gen.generateBytes(password, it) }
}
