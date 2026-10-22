package ru.mobile.mys.p2p

import java.security.MessageDigest
import ru.mobile.mys.crypto.KeyPair
import ru.mobile.mys.crypto.RatchetState
import ru.mobile.mys.crypto.Ristretto
import ru.mobile.mys.crypto.RistrettoException
import ru.mobile.mys.crypto.compareUnsigned
import ru.mobile.mys.crypto.cpaceFinish
import ru.mobile.mys.crypto.cpaceMsg
import ru.mobile.mys.crypto.hkdf
import ru.mobile.mys.crypto.hmacSha256
import ru.mobile.mys.crypto.ratchetInitAlice
import ru.mobile.mys.crypto.ratchetInitBob
import ru.mobile.mys.crypto.x25519KeypairFromSeed

/*
 * Хендшейк — порт mys_decentralized/handshake.py: обмен PAKE{Y}, ISK из CPace,
 * обмен CONFIRM{HMAC(ISK, "mys-confirm" ‖ sort(Ya, Yb))}, затем sk и стартовый DH
 * Боба из ISK и ratchet по роли.
 */

class HandshakeResult(val sk: ByteArray, val ratchetState: RatchetState, val isk: ByteArray)

private val NONE = ByteArray(0)

fun deriveSid(prs: ByteArray): ByteArray = blake2b(prs, 16, "mys-cpace-sid")

fun confirmMac(isk: ByteArray, myY: ByteArray, peerY: ByteArray): ByteArray {
    val (low, high) = if (compareUnsigned(myY, peerY) <= 0) myY to peerY else peerY to myY
    return hmacSha256(isk, "mys-confirm".toByteArray() + low + high)
}

/** (sk — корень ratchet, стартовая DH-пара Боба). */
fun deriveSessionKeys(isk: ByteArray): kotlin.Pair<ByteArray, KeyPair> =
    hkdf(isk, 32, NONE, "mys-ratchet-root".toByteArray()) to
        x25519KeypairFromSeed(hkdf(isk, 32, NONE, "mys-ratchet-bob-dh".toByteArray()))

suspend fun handshake(transport: Transport, prs: ByteArray, role: Role, r: Ristretto): HandshakeResult {
    val (state, myY) = cpaceMsg(r, prs, deriveSid(prs))
    transport.send(encodeMessage(Msg.Pake(myY)))
    val peer = decodeMessage(transport.recv()) as? Msg.Pake ?: throw PakeException("ожидался PAKE на шаге обмена точками")
    val isk = try {
        cpaceFinish(r, state, peer.y)
    } catch (e: RistrettoException) {
        throw PakeException("невалидная точка пира", e)
    }
    val mac = confirmMac(isk, myY, peer.y)
    transport.send(encodeMessage(Msg.Confirm(mac)))
    val confirm = decodeMessage(transport.recv()) as? Msg.Confirm ?: throw PakeException("ожидался CONFIRM")
    if (!MessageDigest.isEqual(confirm.mac, mac)) throw PakeException("key-confirmation не сошёлся: неверная фраза или MITM")
    val (sk, bob) = deriveSessionKeys(isk)
    val ratchet = if (role == Role.INITIATOR) ratchetInitAlice(sk, bob.second) else ratchetInitBob(sk, bob)
    return HandshakeResult(sk, ratchet, isk)
}
