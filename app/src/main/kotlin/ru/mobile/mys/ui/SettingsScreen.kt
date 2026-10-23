package ru.mobile.mys.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import java.io.File
import kotlinx.coroutines.launch
import ru.mobile.mys.AppGraph
import ru.mobile.mys.storage.BiometricKey
import ru.mobile.mys.storage.Sidecar
import ru.mobile.mys.storage.WrongPassword

private val AUTOLOCK_STEPS = listOf(1, 5, 10, 30, 0)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = colors
    val activity = androidx.activity.compose.LocalActivity.current as FragmentActivity
    val vault = AppGraph.vault ?: return
    val central = AppGraph.central
    val meta = File(AppGraph.vaultDir, "vault.meta")
    var background by remember { mutableStateOf(AppGraph.backgroundEnabled()) }
    var bio by remember { mutableStateOf(BiometricKey(AppGraph.vaultDir).isEnabled()) }
    var autolock by remember { mutableStateOf(AppGraph.autolockMinutes()) }
    var server by remember { mutableStateOf(AppGraph.serverUrl()) }
    var wipe by remember { mutableStateOf(central?.wipeOnLogout() == true) }
    var duress by remember { mutableStateOf(Sidecar.read(meta).wipeEnabled) }
    var changingPassword by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    AppGraph.tick.intValue // перечитывать сессию «Центра» после выхода

    Column(Modifier.fillMaxSize()) {
        TopBar("Настройки", onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            note?.let { T(it, color = c.accent) }

            Section("Вид")
            Toggle("Тёмная тема", AppGraph.dark) { AppGraph.changeTheme(it) }

            Section("Безопасность")
            Toggle("Работать в фоне (уведомление «МЫС на связи»)", background) { background = it; AppGraph.setBackground(it) }
            Toggle("Вход по отпечатку", bio) { on ->
                if (on) enableBiometric(activity) { err -> bio = err == null; note = err ?: "Отпечаток включён" }
                else { BiometricKey(AppGraph.vaultDir).disable(); bio = false }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                T("Автоблокировка", Modifier.weight(1f))
                BrutalButton(if (autolock == 0) "Никогда" else "$autolock мин", Modifier.widthIn(min = TOGGLE_W), small = true) {
                    autolock = AUTOLOCK_STEPS[(AUTOLOCK_STEPS.indexOf(autolock) + 1) % AUTOLOCK_STEPS.size]
                    AppGraph.setSetting(AppGraph.K_AUTOLOCK, autolock.toString())
                }
            }
            Toggle("Стереть всё после 10 неверных ПИН-кодов", duress) { on ->
                Sidecar.read(meta).apply { wipeEnabled = on }.write(meta)
                duress = on
            }
            BrutalButton("Сменить ПИН-код", Modifier.fillMaxWidth()) { changingPassword = true }

            Section("Сервер")
            BrutalField(server, { server = it }, "https://soufos.ru", Modifier.fillMaxWidth(), tag = "settings_server")
            BrutalButton("Сохранить адрес", Modifier.fillMaxWidth()) {
                AppGraph.setSetting(AppGraph.K_SERVER, server.trim().trimEnd('/'))
                note = "Адрес сохранён — P2P подхватит его после повторной разблокировки"
            }

            Section("«Центр»")
            Toggle("Стирать историю при выходе из аккаунта", wipe) { wipe = it; central?.setWipeOnLogout(it) }
            central?.session?.let { s ->
                T("Вы вошли как ${s.username}", color = c.text2)
                BrutalButton("Выйти из аккаунта", Modifier.fillMaxWidth(), Variant.Danger) { AppGraph.run { central.logout() } }
            }

            BrutalButton("Заблокировать", Modifier.fillMaxWidth().padding(top = 12.dp), Variant.Danger) { AppGraph.lock() }
        }
    }

    if (changingPassword) PasswordChange(onDone = { msg -> changingPassword = false; msg?.let { note = it } })
}

@Composable
private fun Section(title: String) = T(title, Modifier.padding(top = 12.dp), size = 15, color = colors.text3, bold = true, upper = true)

/** Смена пароля: ключ БД меняется, поэтому обёртка отпечатка сбрасывается. */
@Composable
private fun PasswordChange(onDone: (String?) -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Overlay("Смена ПИН-кода", onDismiss = { onDone(null) }) {
        PinField(old, { old = it }, "Текущий ПИН-код", Modifier.fillMaxWidth())
        PinField(new, { new = it }, "Новый ПИН-код (≥ 4)", Modifier.fillMaxWidth())
        error?.let { T(it, color = colors.warn) }
        BrutalButton("Сменить", Modifier.fillMaxWidth(), Variant.Primary, enabled = old.isNotEmpty() && new.length >= 4) {
            val vault = AppGraph.vault ?: return@BrutalButton
            AppGraph.scope.launch {
                try {
                    vault.changePassword(old.toByteArray(), new.toByteArray())
                    BiometricKey(AppGraph.vaultDir).disable()
                    onDone("ПИН-код изменён; вход по отпечатку нужно включить заново")
                } catch (e: WrongPassword) {
                    error = "Текущий ПИН-код неверен"
                }
            }
        }
    }
}
