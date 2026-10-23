package ru.mobile.mys.ui

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mobile.mys.AppGraph
import ru.mobile.mys.R
import ru.mobile.mys.storage.BiometricKey
import ru.mobile.mys.storage.Vault
import ru.mobile.mys.storage.VaultLocked
import ru.mobile.mys.storage.WrongPassword

private const val MIN_PIN = 4

@Composable
fun UnlockScreen() {
    val c = colors
    val dir = AppGraph.vaultDir
    val activity = androidx.activity.compose.LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(!Vault.exists(dir)) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        busy = true
        error = null
        scope.launch {
            error = withContext(Dispatchers.IO) { unlock(creating, pw, pw2) }
            creating = !Vault.exists(dir) // duress мог стереть хранилище
            busy = false
            pw = ""
            pw2 = ""
        }
    }

    // Отпечаток включён — запрос сам при каждом показе экрана; ПИН-код остаётся запасным
    LaunchedEffect(Unit) {
        if (!creating && BiometricKey(dir).isEnabled()) biometricUnlock(activity) { error = it }
    }
    // По центру, но прокручивается: в альбомной ориентации клавиатура иначе закрывает кнопку
    BoxWithConstraints(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).widthIn(max = MAX_CARD).fillMaxWidth().blockShadow(c.accent, offset = 8.dp).background(c.surface).border(2.dp, c.line).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(64.dp))
            T("МЫС", size = 40, bold = true, upper = true)
            T(if (creating) "Задайте ПИН-код: он шифрует всё на телефоне" else "Введите ПИН-код", color = c.text2)
            PinField(pw, { pw = it }, "ПИН-код", Modifier.fillMaxWidth(), tag = "password")
            if (creating) PinField(pw2, { pw2 = it }, "Повторите ПИН-код", Modifier.fillMaxWidth(), tag = "password2")
            error?.let { T(it, color = c.warn) }
            BrutalButton(
                if (busy) "Открываю…" else if (creating) "Создать хранилище" else "Открыть",
                Modifier.fillMaxWidth(), Variant.Primary, enabled = !busy && pw.isNotEmpty(),
            ) { submit() }
            if (!creating && BiometricKey(dir).isEnabled()) {
                BrutalButton("Отпечаток", Modifier.fillMaxWidth(), enabled = !busy) { biometricUnlock(activity) { error = it } }
            }
        }
      }
    }
}

/** Открыть/создать vault; текст ошибки или null. Argon2id идёт вне главного потока. */
private fun unlock(creating: Boolean, pw: String, pw2: String): String? = try {
    if (creating) {
        when {
            pw.length < MIN_PIN -> "ПИН-код — не короче $MIN_PIN символов"
            pw != pw2 -> "ПИН-коды не совпадают"
            else -> null.also {
                AppGraph.unlocked(Vault.create(AppGraph.vaultDir, pw.toByteArray()))
                AppGraph.offerBiometric = biometricAvailable()
            }
        }
    } else {
        AppGraph.unlocked(Vault.open(AppGraph.vaultDir, pw.toByteArray()))
        null
    }
} catch (e: WrongPassword) {
    "Неверный ПИН-код"
} catch (e: VaultLocked) {
    "Слишком много попыток — подождите ${e.remainingMs / 1000 + 1} с"
}

/** Есть ли сильный датчик с записанными отпечатками. */
private fun biometricAvailable(): Boolean = runCatching {
    androidx.biometric.BiometricManager.from(AppGraph.appContext()).canAuthenticate(BIOMETRIC_STRONG) ==
        androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
}.getOrDefault(false)

private fun biometricUnlock(activity: FragmentActivity, onError: (String) -> Unit) {
    val bio = BiometricKey(AppGraph.vaultDir)
    val cipher = bio.decryptCipher() ?: return onError("Отпечатки изменились — войдите по ПИН-коду")
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val unlocked = result.cryptoObject?.cipher ?: return
            AppGraph.scope.launch {
                val key = bio.open(unlocked)
                runCatching { AppGraph.unlocked(Vault.openWithKey(AppGraph.vaultDir, key)) }
                    .onFailure { AppGraph.errors.tryEmit("Не удалось открыть хранилище — войдите по ПИН-коду") }
                key.fill(0)
            }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED) onError(errString.toString())
        }
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder().setTitle("МЫС").setSubtitle("Разблокировка хранилища")
            .setNegativeButtonText("ПИН-код").setAllowedAuthenticators(BIOMETRIC_STRONG).build(),
        BiometricPrompt.CryptoObject(cipher),
    )
}

/** Включить биометрию: обернуть ключ открытого vault ключом Keystore после отпечатка. */
fun enableBiometric(activity: FragmentActivity, onDone: (String?) -> Unit) {
    val vault = AppGraph.vault ?: return
    val bio = BiometricKey(AppGraph.vaultDir)
    val cipher = runCatching { bio.encryptCipher() }.getOrElse { return onDone("Биометрия недоступна на этом устройстве") }
    BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            result.cryptoObject?.cipher?.let { bio.seal(it, vault.key) }
            onDone(null)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            bio.disable()
            onDone(errString.toString())
        }
    }).authenticate(
        BiometricPrompt.PromptInfo.Builder().setTitle("МЫС").setSubtitle("Вход по отпечатку")
            .setNegativeButtonText("Отмена").setAllowedAuthenticators(BIOMETRIC_STRONG).build(),
        BiometricPrompt.CryptoObject(cipher),
    )
}
