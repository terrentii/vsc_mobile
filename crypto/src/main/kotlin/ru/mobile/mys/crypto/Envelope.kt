package ru.mobile.mys.crypto

/* Конверт: ratchet-шифротекст (header ‖ AEAD) под МЫС-transform. Порт mys_crypto/envelope.py. */

fun deriveTransformKey(sk: ByteArray): ByteArray = hkdf(sk, 32, EMPTY, "mys-transform-master".toByteArray())

fun seal(state: RatchetState, transformKey: ByteArray, plaintext: ByteArray, ad: ByteArray = EMPTY): ByteArray {
    val (header, ct) = ratchetEncrypt(state, plaintext, ad)
    return transformEncode(header.serialize() + ct, transformKey)
}

fun open(state: RatchetState, transformKey: ByteArray, blob: ByteArray, ad: ByteArray = EMPTY): ByteArray {
    val wire = transformDecode(blob, transformKey)
    require(wire.size >= Header.SIZE) { "конверт короче заголовка" }
    return ratchetDecrypt(state, Header.deserialize(wire), wire.copyOfRange(Header.SIZE, wire.size), ad)
}
