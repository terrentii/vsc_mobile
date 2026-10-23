package ru.mobile.mys.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.mobile.mys.AppGraph
import ru.mobile.mys.DECENTRALIZED
import ru.mobile.mys.central.MAX_MEDIA_SIZE
import ru.mobile.mys.central.ReplyRef
import ru.mobile.mys.storage.Message

private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(conversationId: Long, mode: String, onBack: (() -> Unit)?) {
    val c = colors
    val vault = AppGraph.vault ?: return
    val ctx = LocalContext.current
    val p2p = mode == DECENTRALIZED
    val tick = AppGraph.tick.intValue
    var messages by remember { mutableStateOf(emptyList<Message>()) }
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<ReplyRef?>(null) }
    var menuFor by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var saving by remember { mutableStateOf<ByteArray?>(null) }
    val list = rememberLazyListState()

    LaunchedEffect(conversationId, tick) {
        withContext(Dispatchers.IO) {
            title = vault.conversations.get(conversationId)?.title ?: ""
            messages = vault.messages.list(conversationId)
        }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.scrollToItem(messages.size - 1) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val bytes = saving
        saving = null
        if (uri != null && bytes != null) AppGraph.run { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) AppGraph.run { sendFile(ctx, uri, conversationId, p2p) }
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty()) return
        text = ""
        val r = reply
        reply = null
        AppGraph.run {
            if (p2p) {
                val p = AppGraph.p2p ?: return@run
                if (!p.hasSession(conversationId)) error("Собеседник не на связи — нажмите «Выйти на связь»")
                p.send(conversationId, body)
            } else {
                AppGraph.central?.send(conversationId, body, r)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title.ifEmpty { if (p2p) "Канал" else "Комната" }, onBack, subtitle = {
                if (p2p) PeerStatus(conversationId) else {
                    val code = AppGraph.central?.roomCode(conversationId)
                    SelectionContainer { T(if (code != null) "Код $code" else "Центр", size = 13, color = c.text2, upper = true) }
                }
            })
            AnimatedVisibility(p2p && AppGraph.online[conversationId] != true, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                ConnectBar(conversationId)
            }

            Blueprint(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize(), state = list,
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        MessageRow(m, p2p, title, Modifier.combinedClickable(onClick = {}, onLongClick = { menuFor = m }), Modifier.animateItem())
                    }
                }
            }

            AnimatedVisibility(reply != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                var last by remember { mutableStateOf(reply) }
                reply?.let { last = it }
                last?.let { r -> Bar {
                    T("↩ ${r.sender}: ${r.snippet}", Modifier.weight(1f), size = 14, color = c.text2, maxLines = 1)
                    BrutalButton("✕", small = true) { reply = null }
                } }
            }
            // Строка ввода: всё высотой CONTROL и по нижнему краю — при многострочном тексте кнопки остаются внизу
            Row(
                Modifier.fillMaxWidth().background(c.surface2)
                    .drawBehind { drawRect(c.line, size = Size(size.width, 2.dp.toPx())) }
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrutalButton("+", Modifier.semantics { contentDescription = "Прикрепить файл" }) { pickLauncher.launch(arrayOf("*/*")) }
                BrutalField(text, { text = it }, "Сообщение", Modifier.weight(1f).heightIn(max = 160.dp), singleLine = false, tag = "message")
                BrutalButton("→", variant = Variant.Primary, enabled = text.isNotBlank()) { send() }
            }
        }

        menuFor?.let { m ->
            Overlay("Сообщение", onDismiss = { menuFor = null }) {
                val wire = m.wireSeq
                if (!p2p && wire != null) BrutalButton("Ответить", Modifier.fillMaxWidth()) {
                    reply = ReplyRef(wire, m.sender ?: "вы", preview(m).take(60))
                    menuFor = null
                }
                if (m.kind != "text") BrutalButton("Сохранить файл", Modifier.fillMaxWidth()) {
                    menuFor = null
                    AppGraph.run {
                        saving = m.body ?: AppGraph.central?.fetchMedia(m.id)
                        withContext(Dispatchers.Main) { saveLauncher.launch(m.filename ?: "file") }
                    }
                }
                if (!p2p && m.direction == "out" && wire != null) {
                    if (m.kind == "text") BrutalButton("Изменить", Modifier.fillMaxWidth()) { editing = m; menuFor = null }
                    BrutalButton("Удалить у всех", Modifier.fillMaxWidth(), Variant.Danger) {
                        menuFor = null
                        AppGraph.run { AppGraph.central?.delete(m.id) }
                    }
                }
                if (m.kind == "text") SelectionContainer { T(preview(m), color = c.text2) }
            }
        }
        editing?.let { m ->
            TextPrompt("Изменить", "Текст", initial = m.body?.decodeToString() ?: "", onDone = { body ->
                editing = null
                if (body != null) AppGraph.run { AppGraph.central?.edit(m.id, body) }
            })
        }
    }
}

@Composable
private fun PeerStatus(conversationId: Long) {
    val c = colors
    val on = AppGraph.online[conversationId] == true
    val path = if (AppGraph.direct[conversationId] == true) "напрямую" else "через сервер"
    T(if (on) "● В сети · $path" else "● Не в сети", size = 13, color = if (on) c.success else c.text3, upper = true)
}

/** Полоса-уведомление под шапкой: текст слева, кнопка справа. */
@Composable
private fun Bar(content: @Composable RowScope.() -> Unit) = Row(
    Modifier.fillMaxWidth().background(colors.accentSoft).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content,
)

/** Нет канала: «Выйти на связь» или окно ожидания пира с обратным отсчётом. */
@Composable
private fun ConnectBar(conversationId: Long) {
    val c = colors
    val deadline = AppGraph.waiting[conversationId]
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) { while (deadline != null) { now = System.currentTimeMillis(); delay(1_000) } }
    Bar {
        if (deadline != null) {
            val left = ((deadline - now) / 1000).coerceAtLeast(0)
            T("Ждём собеседника ${left / 60}:${"%02d".format(left % 60)}", Modifier.weight(1f), upper = true)
            BrutalButton("Отмена", small = true) { AppGraph.cancelConnect(conversationId) }
        } else {
            T("Собеседник не на связи", Modifier.weight(1f), color = c.text2)
            BrutalButton("Выйти на связь", small = true, variant = Variant.Primary) { AppGraph.connectP2P(conversationId, null) }
        }
    }
}

@Composable
private fun MessageRow(m: Message, p2p: Boolean, peerName: String, modifier: Modifier, rowModifier: Modifier) {
    val c = colors
    val out = m.direction == "out"
    Box(rowModifier.fillMaxWidth(), contentAlignment = if (out) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier.widthIn(max = 300.dp).blockShadow(if (out) c.accent else c.line)
                .background(if (out) c.accentSoft else c.bubbleThem).border(2.dp, c.line).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // P2P: название канала, если его переименовали, иначе «Собеседник»
            val author = if (out) "Вы" else if (p2p) peerName.ifEmpty { "Собеседник" } else m.sender ?: "?"
            val time = (m.sentAt ?: m.receivedAt)?.let { TIME.format(Instant.ofEpochMilli(it)) } ?: ""
            val status = when (m.status) { "pending" -> " · отправка"; "failed" -> " · не отправлено"; else -> "" }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                T(author, Modifier.weight(1f, fill = false), size = 12, color = if (out) c.accent else c.text2, bold = true, upper = true, maxLines = 1)
                T("$time$status", size = 12, color = if (m.status == "failed") c.warn else c.text3, mono = true)
            }
            m.replyAuthor?.let { T("↩ $it: ${m.replySnippet ?: ""}", size = 13, color = c.text2) }
            when (m.kind) {
                "image" -> MediaImage(m)
                "file" -> T("📎 ${m.filename ?: "файл"}${m.body?.let { " · ${it.size / 1024} КБ" } ?: ""}", color = c.bubbleThemText)
                else -> T(m.body?.decodeToString() ?: "", color = if (out) c.text else c.bubbleThemText)
            }
        }
    }
}

/** Превью картинки; входящее вложение «Центра» без тела докачивается при показе. */
@Composable
private fun MediaImage(m: Message) {
    val bytes = m.body
    if (bytes == null) {
        LaunchedEffect(m.id) { AppGraph.run { AppGraph.central?.fetchMedia(m.id) } }
        T("Загрузка изображения…", color = colors.text3)
        return
    }
    val bitmap by produceState<ImageBitmap?>(null, m.id, bytes.size) { value = withContext(Dispatchers.Default) { decodePreview(bytes) } }
    bitmap?.let { Image(it, m.filename, Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit) }
        ?: T(m.filename ?: "изображение", color = colors.text3)
}

private fun decodePreview(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

private fun preview(m: Message) = when (m.kind) {
    "image" -> "Изображение"
    "file" -> m.filename ?: "Файл"
    else -> m.body?.decodeToString() ?: ""
}

private suspend fun sendFile(ctx: android.content.Context, uri: Uri, conversationId: Long, p2p: Boolean) {
    val resolver = ctx.contentResolver
    var name = "file"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) {
            name = cur.getString(0) ?: name
            size = if (cur.isNull(1)) -1 else cur.getLong(1)
        }
    }
    require(size <= MAX_MEDIA_SIZE) { "Файл больше ${MAX_MEDIA_SIZE / (1024 * 1024)} МБ" }
    val data = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не удалось прочитать файл")
    require(data.size <= MAX_MEDIA_SIZE) { "Файл больше ${MAX_MEDIA_SIZE / (1024 * 1024)} МБ" }
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    if (p2p) AppGraph.p2p?.sendFile(conversationId, name, mime, data) else AppGraph.central?.sendFile(conversationId, name, mime, data)
}
