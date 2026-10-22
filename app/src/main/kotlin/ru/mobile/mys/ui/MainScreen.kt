package ru.mobile.mys.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mobile.mys.AppGraph
import ru.mobile.mys.CENTRALIZED
import ru.mobile.mys.DECENTRALIZED
import ru.mobile.mys.central.AuthException
import ru.mobile.mys.storage.Conversation

private enum class Prompt { New, Join, Rename }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(mode: String, onMode: (String) -> Unit, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val c = colors
    val vault = AppGraph.vault ?: return
    val tick = AppGraph.tick.intValue
    var convs by remember { mutableStateOf(emptyList<Conversation>()) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    LaunchedEffect(mode, tick) { convs = withContext(Dispatchers.IO) { vault.conversations.list(mode) } }
    val central = AppGraph.central
    val p2p = mode == DECENTRALIZED

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Шапка: МЫС, вкладки режима, настройки, замок
            Row(
                Modifier.fillMaxWidth().background(c.surface2).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                T("МЫС", size = 24, bold = true, upper = true)
                Spacer(Modifier.weight(1f))
                BrutalButton("⚙", small = true, onClick = onSettings)
                BrutalButton("Замок", small = true, variant = Variant.Danger) { AppGraph.lock() }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrutalButton("P2P", Modifier.weight(1f), if (p2p) Variant.Primary else Variant.Default) { onMode(DECENTRALIZED) }
                BrutalButton("Центр", Modifier.weight(1f), if (!p2p) Variant.Primary else Variant.Default) { onMode(CENTRALIZED) }
            }
            if (!p2p && central?.session == null) {
                LoginForm()
                return@Column
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                T(if (p2p) "Каналы" else "Комнаты", size = 22, bold = true, upper = true)
                Spacer(Modifier.weight(1f))
                T(convs.size.toString(), size = 22, color = c.text3)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(convs, key = { it.id }) { conv ->
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { onOpen(conv.id) }, onLongClick = { menuFor = conv })
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (p2p) {
                            val on = AppGraph.online[conv.id] == true
                            Box(Modifier.size(10.dp).background(if (on) c.success else c.text3))
                            Spacer(Modifier.size(12.dp))
                        }
                        T(conv.title ?: if (p2p) "Канал ${conv.id}" else "Комната ${conv.id}", size = 19)
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).size(1.dp).background(c.border2))
                }
            }
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BrutalButton(if (p2p) "+ Новый канал" else "+ Новая комната", Modifier.fillMaxWidth(), Variant.Ink) { prompt = Prompt.New }
                if (!p2p) BrutalButton("Войти по коду", Modifier.fillMaxWidth()) { prompt = Prompt.Join }
            }
        }

        menuFor?.let { conv ->
            Overlay(conv.title ?: "Беседа", onDismiss = { menuFor = null }) {
                BrutalButton("Переименовать", Modifier.fillMaxWidth()) { prompt = Prompt.Rename }
                BrutalButton("Удалить с телефона", Modifier.fillMaxWidth(), Variant.Danger) {
                    menuFor = null
                    AppGraph.run {
                        AppGraph.p2p?.stopSession(conv.id)
                        vault.conversations.delete(conv.id)
                    }
                }
            }
        }

        when (prompt) {
            Prompt.New -> if (p2p) {
                TextPrompt("Новый канал", "Секретная фраза", okLabel = "Открыть канал", password = true, onDone = { phrase ->
                    prompt = null
                    if (phrase != null) {
                        val id = AppGraph.p2p!!.resolveConversation(phrase)
                        AppGraph.connectP2P(id, phrase)
                        AppGraph.changed()
                        onOpen(id)
                    }
                })
            } else {
                TextPrompt("Новая комната", "Название", okLabel = "Создать", onDone = { name ->
                    prompt = null
                    if (name != null) AppGraph.run { central?.createRoom(name.trim()) }
                })
            }
            Prompt.Join -> TextPrompt("Вход в комнату", "Код комнаты (10 цифр)", digits = true, okLabel = "Войти", onDone = { code ->
                prompt = null
                val trimmed = code?.trim()
                when {
                    trimmed == null -> Unit
                    !(trimmed.length == 10 && trimmed.all(Char::isDigit)) -> AppGraph.errors.tryEmit("Код комнаты — ровно 10 цифр")
                    else -> AppGraph.run { central?.joinRoom(trimmed) }
                }
            })
            Prompt.Rename -> menuFor?.let { conv ->
                TextPrompt("Переименовать", "Название", initial = conv.title ?: "", onDone = { title ->
                    prompt = null
                    menuFor = null
                    if (title != null) AppGraph.run { vault.conversations.rename(conv.id, title.trim()) }
                })
            }
            null -> Unit
        }
    }
}

/** Вход/регистрация в «Центре». Неверный пароль при входе — 401, свой текст. */
@Composable
private fun LoginForm() {
    val c = colors
    var server by remember { mutableStateOf(AppGraph.serverUrl()) }
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun go(register: Boolean) {
        busy = true
        error = null
        AppGraph.scope.launch {
            try {
                val url = server.trim().trimEnd('/')
                AppGraph.setSetting(AppGraph.K_SERVER, url)
                AppGraph.central?.login(url, user.trim(), pw, register)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = if (e is AuthException) "Неверное имя или пароль" else AppGraph.centralMessage(e)
            } finally {
                busy = false
                AppGraph.changed()
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp).blockShadow(c.line).background(c.surface).border(2.dp, c.line).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T("Вход в «Центр»", size = 20, bold = true, upper = true)
        T("Аккаунт на сервере: комнаты синхронизируются с вебом и десктопом.", color = c.text2)
        BrutalField(server, { server = it }, "Сервер", Modifier.fillMaxWidth(), tag = "server")
        BrutalField(user, { user = it }, "Имя пользователя", Modifier.fillMaxWidth(), tag = "username")
        BrutalField(pw, { pw = it }, "Пароль", Modifier.fillMaxWidth(), password = true, tag = "central_password")
        error?.let { T(it, color = c.warn) }
        val ready = !busy && user.isNotBlank() && pw.isNotEmpty()
        BrutalButton("Войти", Modifier.fillMaxWidth(), Variant.Primary, enabled = ready) { go(false) }
        BrutalButton("Регистрация", Modifier.fillMaxWidth(), enabled = ready) { go(true) }
    }
}
