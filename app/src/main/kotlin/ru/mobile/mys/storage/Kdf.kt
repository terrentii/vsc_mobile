package ru.mobile.mys.storage

import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong
import ru.mobile.mys.sodium

/** Мастер-пароль → 32-байтовый ключ БД (Argon2id13 libsodium, память вне Java-кучи). */
internal fun deriveDbKey(password: ByteArray, sc: Sidecar): ByteArray {
    val out = ByteArray(32)
    val ok = sodium.cryptoPwHash(
        out, out.size, password, password.size, sc.salt,
        sc.opsLimit, NativeLong(sc.memLimit), PwHash.Alg.PWHASH_ALG_ARGON2ID13,
    )
    check(ok) { "Argon2id провалился (не хватает памяти?)" }
    return out
}
