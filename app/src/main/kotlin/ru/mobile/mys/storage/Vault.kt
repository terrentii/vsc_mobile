package ru.mobile.mys.storage

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import net.zetetic.database.sqlcipher.SQLiteDatabase
import ru.mobile.mys.crypto.RatchetState

sealed class VaultException(message: String) : Exception(message)
class VaultExists : VaultException("vault уже существует")
class WrongPassword : VaultException("неверный пароль")
class VaultLocked(val remainingMs: Long) : VaultException("vault заблокирован ещё на ${remainingMs / 1000} с")

/**
 * Зашифрованный vault (SQLCipher) в отдельном каталоге `dir`: `vault.db` + открытый
 * `vault.meta` (+ `vault.bio`, если включена биометрия). Порт mys_storage/vault.py.
 */
class Vault private constructor(private val db: SQLiteDatabase, private val dir: File, key: ByteArray) : Closeable {
    /** Сырой ключ БД — нужен только для обёртки биометрией (BiometricKey.seal). */
    internal var key: ByteArray = key
        private set

    val settings = SettingsRepo(db)
    val conversations = ConversationsRepo(db)
    val messages = MessagesRepo(db)
    val ratchet = RatchetRepo(db)

    /** Входящее сообщение и новое состояние ratchet — одной транзакцией. */
    fun receiveMessage(
        conversationId: Long, body: ByteArray, newState: RatchetState,
        kind: String = "text", filename: String? = null, mimeType: String? = null, wireSeq: Long? = null,
    ): Long = db.tx {
        messages.add(conversationId, "in", body, "received", kind, filename, mimeType, wireSeq = wireSeq)
            .also { ratchet.save(conversationId, newState) }
    }

    fun changePassword(old: ByteArray, new: ByteArray) {
        val sc = Sidecar.read(metaFile(dir))
        val check = deriveDbKey(old, sc)
        val same = MessageDigest.isEqual(check, key)
        check.fill(0)
        if (!same) throw WrongPassword()
        val fresh = Sidecar(randomSalt(), sc.opsLimit, sc.memLimit, wipeEnabled = sc.wipeEnabled, wipeThreshold = sc.wipeThreshold)
        val newKey = deriveDbKey(new, fresh)
        db.changePassword(rawKey(newKey))
        fresh.write(metaFile(dir))
        key.fill(0)
        key = newKey
    }

    override fun close() {
        db.close()
        key.fill(0)
    }

    companion object {
        init { System.loadLibrary("sqlcipher") }

        private fun dbFile(dir: File) = File(dir, "vault.db")
        private fun metaFile(dir: File) = File(dir, "vault.meta")
        private fun randomSalt() = ByteArray(Sidecar.SALT_LEN).also { SecureRandom().nextBytes(it) }

        /** SQLCipher raw key: `x'<hex>'` — без внутреннего PBKDF2, ключ уже из Argon2id. */
        private fun rawKey(key: ByteArray) = "x'${key.joinToString("") { "%02x".format(it) }}'".toByteArray()

        fun exists(dir: File) = dbFile(dir).exists() || metaFile(dir).exists()

        fun create(dir: File, password: ByteArray, opsLimit: Long = 3, memLimit: Long = 128L shl 20): Vault {
            if (exists(dir)) throw VaultExists()
            dir.mkdirs()
            val sc = Sidecar(randomSalt(), opsLimit, memLimit)
            val key = deriveDbKey(password, sc)
            val raw = rawKey(key)
            val db = SQLiteDatabase.openOrCreateDatabase(dbFile(dir), raw, null, null)
            raw.fill(0)
            migrate(db)
            sc.write(metaFile(dir))
            return Vault(db, dir, key)
        }

        fun open(dir: File, password: ByteArray, now: Long = System.currentTimeMillis()): Vault {
            val meta = metaFile(dir)
            val sc = Sidecar.read(meta)
            sc.lockRemaining(now).let { if (it > 0) throw VaultLocked(it) }
            val key = deriveDbKey(password, sc)
            val db = tryOpen(dir, key)
            if (db == null) {
                key.fill(0)
                if (sc.registerFailure(now)) wipe(dir) else sc.write(meta)
                throw WrongPassword()
            }
            sc.resetAttempts()
            sc.write(meta)
            migrate(db)
            return Vault(db, dir, key)
        }

        /** Разблокировка готовым ключом (из обёртки биометрии). */
        fun openWithKey(dir: File, key: ByteArray): Vault {
            val db = tryOpen(dir, key) ?: throw WrongPassword()
            migrate(db)
            return Vault(db, dir, key.copyOf())
        }

        /** Стереть весь каталог vault (duress или сброс). */
        fun wipe(dir: File) {
            // ponytail: перезапись нулями на флеше не гарантирует стирания (wear leveling);
            // защиту даёт шифрование, перезапись — best-effort, как в десктопе.
            dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                runCatching {
                    RandomAccessFile(f, "rw").use { raf ->
                        val zeros = ByteArray(64 * 1024)
                        var left = raf.length()
                        while (left > 0) {
                            val n = minOf(left, zeros.size.toLong()).toInt()
                            raf.write(zeros, 0, n)
                            left -= n
                        }
                        raf.fd.sync()
                    }
                }
                f.delete()
            }
        }

        /** Открыть существующую БД ключом; null — ключ не подошёл. Не создаёт файл. */
        private fun tryOpen(dir: File, key: ByteArray): SQLiteDatabase? {
            check(dbFile(dir).exists()) { "нет файла vault.db" }
            val raw = rawKey(key)
            return try {
                SQLiteDatabase.openDatabase(dbFile(dir).path, raw, null, SQLiteDatabase.OPEN_READWRITE, null, null).also { db ->
                    try {
                        db.select("SELECT count(*) FROM sqlite_master") { it.getLong(0) }
                    } catch (e: Exception) {
                        db.close()
                        throw e
                    }
                }
            } catch (e: Exception) {
                null
            } finally {
                raw.fill(0)
            }
        }
    }
}
