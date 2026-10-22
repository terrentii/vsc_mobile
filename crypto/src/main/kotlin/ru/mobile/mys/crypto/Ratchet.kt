package ru.mobile.mys.crypto

/* Double Ratchet — порт mys_crypto/ratchet.py (формат заголовка и состояния совпадает). */

class Header(val dh: ByteArray, val pn: Int, val n: Int) {
    fun serialize(): ByteArray = dh + pn.u32be() + n.u32be()

    companion object {
        const val SIZE = 40
        fun deserialize(blob: ByteArray) = Header(blob.copyOfRange(0, 32), blob.u32be(32), blob.u32be(36))
    }
}

/** Ключ словаря пропущенных: (DH пира, номер). Сравнение по содержимому массива. */
class SkippedId(val dh: ByteArray, val n: Int) {
    override fun equals(other: Any?) = other is SkippedId && n == other.n && dh.contentEquals(other.dh)
    override fun hashCode() = 31 * dh.contentHashCode() + n
}

/** Байтовые массивы внутри никогда не мутируются — только заменяются, поэтому copy() поверхностный по ним. */
class RatchetState(
    var dhs: KeyPair,
    var dhr: ByteArray?,
    var rk: ByteArray,
    var cks: ByteArray?,
    var ckr: ByteArray?,
    var ns: Int,
    var nr: Int,
    var pn: Int,
    val mkskipped: LinkedHashMap<SkippedId, ByteArray> = LinkedHashMap(),
) {
    fun copy() = RatchetState(dhs, dhr, rk, cks, ckr, ns, nr, pn, LinkedHashMap(mkskipped))

    internal fun assign(o: RatchetState) {
        dhs = o.dhs; dhr = o.dhr; rk = o.rk; cks = o.cks; ckr = o.ckr
        ns = o.ns; nr = o.nr; pn = o.pn
        mkskipped.clear(); mkskipped.putAll(o.mkskipped)
    }
}

const val MAX_SKIP = 1000
const val MAX_SKIP_SESSION = 2000

fun kdfRk(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
    val out = hkdf(dhOut, 64, rk, "mys-ratchet-rk".toByteArray())
    return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
}

/** Возвращает (новый ck, mk). */
fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> =
    hmacSha256(ck, byteArrayOf(2)) to hmacSha256(ck, byteArrayOf(1))

/** Возвращает (ключ AEAD 32 байта, nonce 12 байт). */
fun deriveMessageKeys(mk: ByteArray): Pair<ByteArray, ByteArray> {
    val out = hkdf(mk, 44, ByteArray(32), "mys-ratchet-msg".toByteArray())
    return out.copyOfRange(0, 32) to out.copyOfRange(32, 44)
}

fun ratchetInitAlice(sk: ByteArray, bobDhPub: ByteArray): RatchetState {
    val dhs = generateX25519Keypair()
    val (rk, cks) = kdfRk(sk, x25519Shared(dhs.first, bobDhPub))
    return RatchetState(dhs, bobDhPub, rk, cks, null, 0, 0, 0)
}

fun ratchetInitBob(sk: ByteArray, bobDhKeypair: KeyPair): RatchetState =
    RatchetState(bobDhKeypair, null, sk, null, null, 0, 0, 0)

fun ratchetEncrypt(state: RatchetState, plaintext: ByteArray, ad: ByteArray = EMPTY): Pair<Header, ByteArray> {
    val (ck, mk) = kdfCk(checkNotNull(state.cks) { "нет отправляющей цепочки" })
    state.cks = ck
    val header = Header(state.dhs.second, state.pn, state.ns)
    state.ns += 1
    val (key, nonce) = deriveMessageKeys(mk)
    return header to aeadEncrypt(key, nonce, plaintext, ad + header.serialize())
}

/** Атомарно: при любой ошибке состояние не меняется. */
fun ratchetDecrypt(state: RatchetState, header: Header, ciphertext: ByteArray, ad: ByteArray = EMPTY): ByteArray {
    val work = state.copy()
    val plaintext = decryptInto(work, header, ciphertext, ad)
    state.assign(work)
    return plaintext
}

private fun decryptInto(state: RatchetState, header: Header, ciphertext: ByteArray, ad: ByteArray): ByteArray {
    trySkipped(state, header, ciphertext, ad)?.let { return it }
    if (state.dhr?.contentEquals(header.dh) != true) {
        skipMessageKeys(state, header.pn)
        dhRatchet(state, header)
    }
    skipMessageKeys(state, header.n)
    val (ck, mk) = kdfCk(state.ckr!!)
    state.ckr = ck
    state.nr += 1
    val (key, nonce) = deriveMessageKeys(mk)
    return aeadDecrypt(key, nonce, ciphertext, ad + header.serialize())
}

private fun trySkipped(state: RatchetState, header: Header, ciphertext: ByteArray, ad: ByteArray): ByteArray? {
    val mk = state.mkskipped.remove(SkippedId(header.dh, header.n)) ?: return null
    val (key, nonce) = deriveMessageKeys(mk)
    return aeadDecrypt(key, nonce, ciphertext, ad + header.serialize())
}

private fun skipMessageKeys(state: RatchetState, until: Int) {
    var ckr = state.ckr ?: return
    check(state.nr + MAX_SKIP >= until) { "too many skipped messages" }
    while (state.nr < until) {
        val (ck, mk) = kdfCk(ckr)
        ckr = ck
        state.ckr = ck
        storeSkipped(state, SkippedId(state.dhr!!, state.nr), mk)
        state.nr += 1
    }
}

private fun storeSkipped(state: RatchetState, id: SkippedId, mk: ByteArray) {
    state.mkskipped[id] = mk
    val it = state.mkskipped.keys.iterator()
    while (state.mkskipped.size > MAX_SKIP_SESSION) { it.next(); it.remove() }
}

private fun dhRatchet(state: RatchetState, header: Header) {
    state.pn = state.ns
    state.ns = 0
    state.nr = 0
    state.dhr = header.dh
    kdfRk(state.rk, x25519Shared(state.dhs.first, header.dh)).let { (rk, ck) -> state.rk = rk; state.ckr = ck }
    state.dhs = generateX25519Keypair()
    kdfRk(state.rk, x25519Shared(state.dhs.first, header.dh)).let { (rk, ck) -> state.rk = rk; state.cks = ck }
}

private const val STATE_VERSION: Byte = 1

fun serializeState(state: RatchetState): ByteArray {
    val buf = java.io.ByteArrayOutputStream()
    fun put(b: ByteArray) = buf.write(b)
    fun putOpt(b: ByteArray?) { if (b == null) buf.write(0) else { buf.write(1); put(b) } }
    buf.write(STATE_VERSION.toInt())
    put(state.dhs.first); put(state.dhs.second)
    putOpt(state.dhr)
    put(state.rk)
    putOpt(state.cks); putOpt(state.ckr)
    put(state.ns.u32be()); put(state.nr.u32be()); put(state.pn.u32be())
    put(state.mkskipped.size.u32be())
    for ((id, mk) in state.mkskipped) { put(id.dh); put(id.n.u32be()); put(mk) }
    return buf.toByteArray()
}

fun deserializeState(blob: ByteArray): RatchetState {
    require(blob[0] == STATE_VERSION) { "unsupported ratchet state version" }
    var pos = 1
    fun take(n: Int) = blob.copyOfRange(pos, pos + n).also { pos += n }
    fun takeOpt(): ByteArray? = if (blob[pos++].toInt() == 0) null else take(32)
    fun takeInt() = blob.u32be(pos).also { pos += 4 }
    val dhs = take(32) to take(32)
    val dhr = takeOpt()
    val rk = take(32)
    val cks = takeOpt()
    val ckr = takeOpt()
    val ns = takeInt(); val nr = takeInt(); val pn = takeInt()
    val skipped = LinkedHashMap<SkippedId, ByteArray>()
    repeat(takeInt()) {
        val dh = take(32)
        val n = takeInt()
        skipped[SkippedId(dh, n)] = take(32)
    }
    return RatchetState(dhs, dhr, rk, cks, ckr, ns, nr, pn, skipped)
}
