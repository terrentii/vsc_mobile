package ru.mobile.mys.storage

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Обёртка ключа БД AES-GCM ключом Android Keystore для разблокировки биометрией.
 *
 * Шифр из [encryptCipher]/[decryptCipher] передаётся в `BiometricPrompt.CryptoObject`;
 * после успешной аутентификации — в [seal]/[open]. Файл `vault.bio` = iv(12) ‖ ct.
 * `requireAuth = false` — только для тестов (ключ без биометрии).
 */
class BiometricKey(private val dir: File, private val requireAuth: Boolean = true) {
    private val file get() = File(dir, "vault.bio")

    fun isEnabled() = file.exists()

    /** Шифр для включения биометрии (создаёт новый ключ Keystore). */
    fun encryptCipher(): Cipher {
        createKey()
        return Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
    }

    fun seal(cipher: Cipher, dbKey: ByteArray) = file.writeBytes(cipher.iv + cipher.doFinal(dbKey))

    /** null — обёртки нет или ключ инвалидирован (сменились отпечатки): только пароль. */
    fun decryptCipher(): Cipher? {
        if (!file.exists()) return null
        val key = key() ?: run { disable(); return null }
        return try {
            Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, file.readBytes(), 0, IV_LEN)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable()
            null
        }
    }

    fun open(cipher: Cipher): ByteArray {
        val blob = file.readBytes()
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    fun disable() {
        file.delete()
        keyStore().deleteEntry(ALIAS)
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key() = keyStore().getKey(ALIAS, null) as SecretKey?

    private fun createKey() {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (requireAuth) {
                    setUserAuthenticationRequired(true)
                    setInvalidatedByBiometricEnrollment(true)
                    if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
                if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true)
            }
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        try {
            gen.init(spec(strongBox = true))
            gen.generateKey()
        } catch (e: ProviderException) { // StrongBoxUnavailableException (API 28+) — её предок
            gen.init(spec(strongBox = false))
            gen.generateKey()
        }
    }

    private companion object {
        const val ALIAS = "mys.vault.bio"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
