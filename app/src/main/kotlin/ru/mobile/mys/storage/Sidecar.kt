package ru.mobile.mys.storage

import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.Properties

/**
 * Открытый sidecar рядом с БД (аналог vault.meta.json десктопа): несекретные
 * параметры KDF и счётчики неверных попыток. Секретов здесь нет по построению.
 */
class Sidecar(
    val salt: ByteArray,
    val opsLimit: Long = 3,
    val memLimit: Long = 128L shl 20,
    var failed: Int = 0,
    var lockoutUntil: Long = 0,
    var wipeEnabled: Boolean = false,
    var wipeThreshold: Int = 10,
) {
    fun lockRemaining(now: Long): Long = maxOf(0L, lockoutUntil - now)

    /** Учесть неверный пароль. true — порог duress достигнут, vault нужно стереть. */
    fun registerFailure(now: Long): Boolean {
        failed += 1
        if (wipeEnabled && failed >= wipeThreshold) return true
        lockoutUntil = now + delayMs(failed)
        return false
    }

    fun resetAttempts() {
        failed = 0
        lockoutUntil = 0
    }

    /** Атомарная запись: tmp + fsync + rename. */
    fun write(file: File) {
        val p = Properties()
        p["format_version"] = "1"
        p["salt"] = Base64.getEncoder().encodeToString(salt)
        p["ops_limit"] = opsLimit.toString()
        p["mem_limit"] = memLimit.toString()
        p["failed"] = failed.toString()
        p["lockout_until"] = lockoutUntil.toString()
        p["wipe_enabled"] = wipeEnabled.toString()
        p["wipe_threshold"] = wipeThreshold.toString()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            p.store(out, null)
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "не удалось записать sidecar" }
    }

    companion object {
        const val SALT_LEN = 16
        const val LOCK_CAP_MS = 300_000L

        /** 2^failed секунд, не больше 5 минут — как в десктопе. */
        fun delayMs(failed: Int): Long = minOf(1000L shl minOf(failed, 30), LOCK_CAP_MS)

        fun read(file: File): Sidecar {
            val p = Properties().apply { file.inputStream().use { load(it) } }
            return Sidecar(
                salt = Base64.getDecoder().decode(p.getProperty("salt")),
                opsLimit = p.getProperty("ops_limit").toLong(),
                memLimit = p.getProperty("mem_limit").toLong(),
                failed = p.getProperty("failed").toInt(),
                lockoutUntil = p.getProperty("lockout_until").toLong(),
                wipeEnabled = p.getProperty("wipe_enabled").toBoolean(),
                wipeThreshold = p.getProperty("wipe_threshold").toInt(),
            )
        }
    }
}
