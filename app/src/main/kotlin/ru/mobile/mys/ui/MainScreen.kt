package ru.mobile.mys.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.lerp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ru.mobile.mys.storage.Message
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
fun MainScreen(mode: String, onMode: (String) -> Unit, onOpen: (Long) -> Unit, onSettings: () -> Unit, selected: Long? = null) {
    val c = colors
    val vault = AppGraph.vault ?: return
    val tick = AppGraph.tick.intValue
    var convs by remember { mutableStateOf(emptyList<Conversation>()) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var last by remember { mutableStateOf(emptyMap<Long, Message>()) }
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(mode, tick) {
        withContext(Dispatchers.IO) {
            convs = vault.conversations.list(mode)
            last = vault.messages.lastByConversation(mode)
        }
    }
    val central = AppGraph.central
    val p2p = mode == DECENTRALIZED

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Шапка: МЫС, вкладки режима, настройки, замок
            TopBar("МЫС", big = true) {
                BrutalButton("⚙", small = true, onClick = onSettings)
                BrutalButton("Замок", small = true, variant = Variant.Danger) { AppGraph.lock() }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrutalButton("P2P", Modifier.weight(1f), if (p2p) Variant.Primary else Variant.Default) { onMode(DECENTRALIZED) }
                BrutalButton("Центр", Modifier.weight(1f), if (!p2p) Variant.Primary else Variant.Default) { onMode(CENTRALIZED) }
            }
            if (!p2p && central?.session == null) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { LoginForm() }
                return@Column
            }
            // Свежие сверху; закреплённые — отдельной секцией над остальными
            val sorted = convs.sortedWith(
                compareByDescending<Conversation> { conv -> last[conv.id]?.let { it.sentAt ?: it.receivedAt } ?: conv.createdAt }.thenByDescending { it.id },
            )
            val (pinned, rest) = sorted.partition { it.pinned }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = if (p2p) 84.dp else 144.dp)) {
                    // У верхнего заголовка ключ постоянный: иначе при первом закреплении список держался бы
                    // за «Каналы» и новая секция появлялась бы выше экрана
                    fun section(title: String, rows: List<Conversation>, first: Boolean) {
                        item(key = if (first) "h:first" else "h:$title") { SectionHeader(title, rows.size, Modifier.animateItem()) }
                        items(rows, key = { it.id }) { conv ->
                            ConversationRow(conv, last[conv.id], p2p, conv.id == selected, Modifier.animateItem(), { onOpen(conv.id) }, { menuFor = conv })
                        }
                    }
                    if (pinned.isNotEmpty()) section("Закреплённые", pinned, first = true)
                    section(if (p2p) "Каналы" else "Комнаты", rest, first = pinned.isEmpty())
                    if (convs.isEmpty()) item(key = "empty") {
                        T(if (p2p) "Каналов пока нет — откройте новый по секретной фразе" else "Комнат пока нет — создайте или войдите по коду",
                            Modifier.padding(16.dp), color = c.text3)
                    }
                }
                // При прокрутке кнопки сворачиваются в квадраты в правом нижнем углу
                Actions(p2p, collapsed = list.canScrollBackward, Modifier.align(Alignment.BottomEnd),
                    onNew = { prompt = Prompt.New }, onJoin = { prompt = Prompt.Join })
            }
        }

        menuFor?.let { conv ->
            Overlay(conv.title ?: "Беседа", onDismiss = { menuFor = null }) {
                BrutalButton(if (conv.pinned) "Открепить" else "Закрепить", Modifier.fillMaxWidth()) {
                    menuFor = null
                    AppGraph.run { vault.conversations.setPinned(conv.id, !conv.pinned) }
                }
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

private val DAY = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val DATE = DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneId.systemDefault())

/** Время в списке: сегодня — часы, раньше — дата. */
private fun listTime(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(ms)
    return if (at.atZone(zone).toLocalDate() == LocalDate.now(zone)) DAY.format(at) else DATE.format(at)
}

@Composable
private fun SectionHeader(title: String, count: Int, modifier: Modifier) = Row(
    modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    T(title, size = 13, color = colors.text3, bold = true, upper = true)
    Spacer(Modifier.weight(1f))
    T(count.toString(), size = 13, color = colors.text3, mono = true)
}

/** Строка беседы: монограмма, название, превью последнего сообщения, время. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: Conversation, last: Message?, p2p: Boolean, selected: Boolean, modifier: Modifier,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val c = colors
    val title = conv.title ?: if (p2p) "Канал ${conv.id}" else "Комната ${conv.id}"
    Row(
        modifier.fillMaxWidth()
            .then(if (selected) Modifier.background(c.accentSoft).drawBehind { drawRect(c.accent, size = Size(4.dp.toPx(), size.height)) } else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .drawBehind { // разделитель от текста, не под монограммой
                val x = 72.dp.toPx()
                drawRect(c.border2, Offset(x, size.height - 1.dp.toPx()), Size(size.width - x - 16.dp.toPx(), 1.dp.toPx()))
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).background(if (selected) c.accent else c.surface).border(2.dp, c.line), contentAlignment = Alignment.Center) {
            T(title.trim().take(1).ifEmpty { "#" }, size = 20, bold = true, upper = true, color = if (selected) Color.White else c.text)
            if (p2p) { // статус канала — квадратик в углу монограммы
                val on = AppGraph.online[conv.id] == true
                Box(Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(12.dp).background(if (on) c.success else c.text3).border(2.dp, c.bg))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(title, Modifier.weight(1f), size = 18, bold = true, maxLines = 1)
                last?.let { m -> (m.sentAt ?: m.receivedAt)?.let { T(listTime(it), Modifier.padding(start = 8.dp), size = 12, color = c.text3, mono = true) } }
            }
            T(preview(last, p2p), size = 14, color = c.text2, maxLines = 1)
        }
    }
}

private fun preview(m: Message?, p2p: Boolean): String {
    if (m == null) return "Нет сообщений"
    val who = when {
        m.direction == "out" -> "Вы: "
        p2p -> "" // в P2P собеседник один — это и есть канал
        else -> m.sender?.let { "$it: " } ?: ""
    }
    val what = when (m.kind) {
        "image" -> "Изображение"
        "file" -> "Файл · ${m.filename ?: ""}"
        else -> m.body?.decodeToString()?.replace("```", "")?.replace('\n', ' ')?.trim() ?: ""
    }
    return who + what
}

/** «Новая комната» / «Войти по коду»: во всю ширину наверху списка, квадраты в углу при прокрутке. */
@Composable
private fun Actions(p2p: Boolean, collapsed: Boolean, modifier: Modifier, onNew: () -> Unit, onJoin: () -> Unit) {
    val f by animateFloatAsState(if (collapsed) 0f else 1f, tween(240), label = "actions")
    BoxWithConstraints(modifier.fillMaxWidth().background(colors.bg.copy(alpha = f)).padding(16.dp)) {
        val full = maxWidth
        val w = lerp(CONTROL + 4.dp, full, f) // + тень
        val wide = f > 0.6f
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BrutalButton(if (wide) (if (p2p) "+ Новый канал" else "+ Новая комната") else "+",
                Modifier.width(w).semantics { contentDescription = if (p2p) "Новый канал" else "Новая комната" }, Variant.Ink, onClick = onNew)
            if (!p2p) BrutalButton(if (wide) "Войти по коду" else "#",
                Modifier.width(w).semantics { contentDescription = "Войти по коду" }, onClick = onJoin)
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

    Column(Modifier.padding(16.dp).widthIn(max = MAX_CARD).fillMaxWidth().blockShadow(c.line).background(c.surface).border(2.dp, c.line).padding(18.dp),
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
