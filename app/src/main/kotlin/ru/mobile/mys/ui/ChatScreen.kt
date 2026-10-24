package ru.mobile.mys.ui

import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.composed
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
fun ChatScreen(conversationId: Long, mode: String, onBack: (() -> Unit)?, onRoomSettings: (() -> Unit)? = null) {
    val c = colors
    val vault = AppGraph.vault ?: return
    val ctx = LocalContext.current
    val p2p = mode == DECENTRALIZED
    val tick = AppGraph.tick.intValue
    var messages by remember { mutableStateOf(emptyList<Message>()) }
    var title by remember { mutableStateOf("") }
    // черновик беседы переживает уход с экрана (живёт, пока открыто хранилище)
    var text by remember { mutableStateOf(AppGraph.drafts[conversationId] ?: "") }
    var reply by remember { mutableStateOf<ReplyRef?>(null) }
    var menuFor by remember { mutableStateOf<Pair<Message, Rect>?>(null) }
    var viewing by remember { mutableStateOf<Message?>(null) }
    var flash by remember { mutableStateOf<Long?>(null) }
    val bounds = remember { HashMap<Long, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Message?>(null) }
    var saving by remember { mutableStateOf<ByteArray?>(null) }
    val list = rememberLazyListState()

    LaunchedEffect(conversationId, tick) {
        withContext(Dispatchers.IO) {
            title = vault.conversations.get(conversationId)?.title ?: ""
            messages = vault.messages.list(conversationId)
        }
    }
    // Список перевёрнут (reverseLayout): низ — новейшее, и докачка картинок выше его не сдвигает.
    // Новое сообщение: если были внизу — остаёмся внизу.
    LaunchedEffect(messages.lastOrNull()?.id) { if (list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0) }
    val entries = remember(messages) { chatEntries(messages).asReversed() }
    // «↓ вниз»: показываем, когда ушли от низа; счётчик — новые входящие с тех пор
    val away by remember { derivedStateOf { list.firstVisibleItemIndex > 2 } }
    var seenUpTo by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(away, messages.lastOrNull()?.id) { if (!away) seenUpTo = messages.lastOrNull()?.id ?: 0L }
    val unread = if (away) messages.count { it.id > seenUpTo && it.direction == "in" } else 0
    fun jumpTo(target: Message) = scope.launch {
        val i = entries.indexOfFirst { it is MsgEntry && it.m.id == target.id }
        if (i < 0) return@launch
        list.animateScrollToItem(i)
        flash = target.id
        kotlinx.coroutines.delay(1_400)
        if (flash == target.id) flash = null
    }
    // «Центр»: комната глазами пользователя (диалог, своё название, аватар, собеседник)
    val meta = if (p2p) null else AppGraph.central?.metas?.collectAsState()?.value?.get(conversationId)
    val dialog = meta?.isDialog == true
    var lastTyping by remember { mutableLongStateOf(0L) }
    var lastRead by remember { mutableLongStateOf(0L) }
    // прочтение: открытый диалог на экране — отметить последнее входящее
    LaunchedEffect(messages.size, dialog) {
        if (!dialog || !AppGraph.foreground) return@LaunchedEffect
        val wire = messages.filter { it.direction == "in" }.mapNotNull { it.wireSeq }.maxOrNull() ?: return@LaunchedEffect
        if (wire > lastRead) { lastRead = wire; AppGraph.central?.markRead(conversationId, wire) }
    }

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
        AppGraph.drafts.remove(conversationId)
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
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

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            val shownTitle = meta?.title ?: title.ifEmpty { if (p2p) "Канал" else "Комната" }
            TopBar(
                shownTitle, onBack,
                subtitle = {
                    when {
                        p2p -> PeerStatus(conversationId)
                        dialog -> DialogStatus(conversationId, meta?.peerOnline == true)
                        else -> {
                            val code = AppGraph.central?.roomCode(conversationId)
                            SelectionContainer { T(if (code != null) "Код $code" else "Центр", size = 13, color = c.text2, upper = true) }
                        }
                    }
                },
                leading = if (p2p) null else ({ RoomAvatar(conversationId, meta?.avatarRev ?: 0, shownTitle, 40.dp) }),
            ) {
                if (!p2p && onRoomSettings != null) BrutalButton("⚙", small = true, onClick = onRoomSettings)
            }
            // «X предлагает диалог / приглашает»: принять или отклонить прямо из чата
            AnimatedVisibility(meta?.dialogProposedBy != null && !dialog, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                val m = meta ?: return@AnimatedVisibility
                val mine = m.dialogProposedBy == AppGraph.central?.session?.username
                Bar {
                    T(
                        when {
                            mine && m.pendingInvite -> "Ждём, пока ${m.peer} примет приглашение"
                            mine -> "Вы предложили ${m.peer} диалог"
                            m.role == "invited" -> "${m.dialogProposedBy} зовёт в диалог"
                            else -> "${m.dialogProposedBy} предлагает диалог"
                        },
                        Modifier.weight(1f), size = 14, color = c.text2,
                    )
                    fun act(action: String) = AppGraph.run {
                        if (AppGraph.central?.roomOp(conversationId) { dialogAction(it, action) } == null) {
                            withContext(Dispatchers.Main) { onBack?.invoke() }
                        }
                    }
                    if (!mine) BrutalButton("Принять", small = true, variant = Variant.Primary) { act("accept") }
                    BrutalButton(if (mine) "Отозвать" else "Нет", small = true) { act("decline") }
                }
            }
            AnimatedVisibility(p2p && AppGraph.online[conversationId] != true, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                ConnectBar(conversationId)
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Blueprint(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(), state = list, reverseLayout = true,
                        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(entries, key = { it.key }) { e ->
                            when (e) {
                                is DayEntry -> DaySeparator(e.day)
                                is MsgEntry -> {
                                    val m = e.m
                                    val wire = m.wireSeq
                                    SwipeToReply(enabled = !p2p && wire != null, onReply = {
                                        reply = ReplyRef(wire!!, m.sender ?: "вы", preview(m).take(60))
                                    }) {
                                        MessageRow(
                                            m, p2p, title,
                                            Modifier.onGloballyPositioned { bounds[m.id] = it.boundsInRoot() }
                                                .combinedClickable(onClick = {}, onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    bounds[m.id]?.let { b -> menuFor = m to b.translate(-origin) }
                                                }),
                                            Modifier.animateItem(),
                                            dialog = dialog, peerRead = meta?.peerRead ?: 0, highlight = flash == m.id,
                                            onQuote = { quoteTarget(messages, m, AppGraph.central?.session?.username)?.let { jumpTo(it) } },
                                            onImage = { viewing = m },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                ScrollDownButton(away, unread) { scope.launch { list.animateScrollToItem(0) } }
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
                val locked = meta?.pendingInvite == true
                BrutalField(text, {
                    text = it
                    if (it.isBlank()) AppGraph.drafts.remove(conversationId) else AppGraph.drafts[conversationId] = it
                    val now = System.currentTimeMillis()
                    if (dialog && now - lastTyping > 3_000) { lastTyping = now; AppGraph.central?.typing(conversationId) }
                }, if (locked) "Ждём принятия приглашения" else "Сообщение", Modifier.weight(1f).heightIn(max = 160.dp),
                    singleLine = false, tag = "message", enabled = !locked)
                BrutalButton("→", variant = Variant.Primary, enabled = text.isNotBlank()) { send() }
            }
        }

        menuFor?.let { (m, anchor) ->
            val wire = m.wireSeq
            val own = !p2p && m.direction == "out" && wire != null
            val actions = buildList {
                if (!p2p && wire != null) add(MenuAction("Ответить") { reply = ReplyRef(wire, m.sender ?: "вы", preview(m).take(60)) })
                if (m.kind == "text") add(MenuAction("Копировать") {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.body?.decodeToString() ?: ""))
                })
                if (m.kind == "image" && m.body != null) add(MenuAction("Открыть") { viewing = m })
                if (m.kind != "text") add(MenuAction("Сохранить файл") {
                    AppGraph.run {
                        saving = m.body ?: AppGraph.central?.fetchMedia(m.id)
                        withContext(Dispatchers.Main) { saveLauncher.launch(m.filename ?: "file") }
                    }
                })
                if (own && m.kind == "text") add(MenuAction("Изменить") { editing = m })
                if (own) add(MenuAction("Удалить у всех", danger = true) { AppGraph.run { AppGraph.central?.delete(m.id) } })
            }
            MessageMenu(anchor, m.direction == "out", actions, onDismiss = { menuFor = null }) {
                // копия пузыря ровно по его размеру — встаёт поверх оригинала
                Box(Modifier.width(with(LocalDensity.current) { anchor.width.toDp() })) {
                    MessageRow(m, p2p, title, Modifier.fillMaxWidth(), Modifier, dialog = dialog, peerRead = meta?.peerRead ?: 0)
                }
            }
        }
        viewing?.let { m ->
            PhotoViewer(m, onSave = {
                saving = m.body
                saveLauncher.launch(m.filename ?: "image.png")
            }, onDismiss = { viewing = null })
        }
        editing?.let { m ->
            TextPrompt("Изменить", "Текст", initial = m.body?.decodeToString() ?: "", onDone = { body ->
                editing = null
                if (body != null) AppGraph.run { AppGraph.central?.edit(m.id, body) }
            })
        }
    }
}

/** Диалог: «печатает…» (5 с после сигнала) либо «● в сети». */
@Composable
private fun DialogStatus(conversationId: Long, online: Boolean) {
    val c = colors
    val until = AppGraph.typing[conversationId] ?: 0L
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) { while (System.currentTimeMillis() < until) { now = System.currentTimeMillis(); delay(500) }; now = System.currentTimeMillis() }
    when {
        now < until -> T("печатает…", size = 13, color = c.accent)
        online -> T("● В сети", size = 13, color = c.success, upper = true)
        else -> T("Диалог", size = 13, color = c.text3, upper = true)
    }
}

@Composable
private fun PeerStatus(conversationId: Long) {
    val c = colors
    val on = AppGraph.online[conversationId] == true
    val path = when (AppGraph.path[conversationId]) { "direct" -> "напрямую"; "mesh" -> "через mesh"; else -> "через сервер" }
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
private fun MessageRow(
    m: Message, p2p: Boolean, peerName: String, modifier: Modifier, rowModifier: Modifier,
    dialog: Boolean = false, peerRead: Long = 0, highlight: Boolean = false,
    onQuote: () -> Unit = {}, onImage: () -> Unit = {},
) {
    val c = colors
    val out = m.direction == "out"
    // подсветка после перехода по цитате
    val tint by androidx.compose.animation.animateColorAsState(if (highlight) c.accent.copy(alpha = 0.35f) else Color.Transparent, tween(400), label = "flash")
    Box(rowModifier.fillMaxWidth(), contentAlignment = if (out) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier.widthIn(max = 300.dp).blockShadow(if (out) c.accent else c.line)
                .background(if (out) c.accentSoft else c.bubbleThem).background(tint).border(2.dp, c.line).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // P2P: название канала, если его переименовали, иначе «Собеседник»
            val author = if (out) "Вы" else if (p2p) peerName.ifEmpty { "Собеседник" } else m.sender ?: "?"
            val time = (m.sentAt ?: m.receivedAt)?.let { TIME.format(Instant.ofEpochMilli(it)) } ?: ""
            val status = when (m.status) { "pending" -> " · отправка"; "failed" -> " · не отправлено"; else -> "" }
            // диалог: имя не повторяем; у своих — • (на сервере) / •• акцентом (прочитано)
            val wire = m.wireSeq
            val read = dialog && out && wire != null && wire <= peerRead
            val dots = if (dialog && out && status.isEmpty()) (if (read) 2 else 1) else 0
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!dialog) T(author, Modifier.weight(1f, fill = false), size = 12, color = if (out) c.accent else c.text2, bold = true, upper = true, maxLines = 1)
                T("$time$status", size = 12, color = if (m.status == "failed") c.warn else c.text3, mono = true)
                if (dots > 0) ReadDots(dots, if (read) c.accent else c.text3)
            }
            m.replyAuthor?.let {
                // цитата: полоска слева, нажатие — к исходному сообщению
                Row(Modifier.clickable(onClick = onQuote).drawBehind { drawRect(c.accent, size = Size(3.dp.toPx(), size.height)) }.padding(start = 9.dp)) {
                    Column {
                        T(it, size = 12, color = c.accent, bold = true, maxLines = 1)
                        T(m.replySnippet ?: "", size = 13, color = c.text2, maxLines = 2)
                    }
                }
            }
            when (m.kind) {
                "image" -> MediaImage(m, onImage)
                "file" -> T("📎 ${m.filename ?: "файл"}${m.body?.let { " · ${it.size / 1024} КБ" } ?: ""}", color = c.bubbleThemText)
                else -> MessageText(m.body?.decodeToString() ?: "", if (out) c.text else c.bubbleThemText)
            }
        }
    }
}

/** Превью картинки; входящее вложение «Центра» без тела докачивается при показе. */
@Composable
private fun MediaImage(m: Message, onOpen: () -> Unit = {}) {
    val bytes = m.body
    if (bytes == null) {
        LaunchedEffect(m.id) { AppGraph.run { AppGraph.central?.fetchMedia(m.id) } }
        LoadingPlate("загрузка")
        return
    }
    val bitmap by produceState<ImageBitmap?>(null, m.id, bytes.size) { value = withContext(Dispatchers.Default) { decodePreview(bytes) } }
    // плашка → картинка с проявлением; своё фото, пока уходит, — с бегущей полосой
    androidx.compose.animation.Crossfade(bitmap, animationSpec = tween(320), label = "photo") { b ->
        if (b == null) LoadingPlate("")
        else Image(b, m.filename, Modifier.size(imageSize(b.width, b.height)).scanning(m.status == "pending").clickable(onClick = onOpen), contentScale = ContentScale.Crop)
    }
}

/** Точки прочтения: рисуем сами — в ГОСТе «•» выглядит как «×». */
@Composable
private fun ReadDots(n: Int, color: Color) = Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    repeat(n) { Box(Modifier.size(6.dp).background(color, androidx.compose.foundation.shape.CircleShape)) }
}

/** Заглушка фото, пока оно качается/декодируется: поле с бегущей полосой «Чертежа». */
@Composable
private fun LoadingPlate(label: String) {
    val c = colors
    Box(Modifier.size(PLATE).background(c.field).border(1.dp, c.line).scanning(true), contentAlignment = Alignment.Center) {
        if (label.isNotEmpty()) T(label, size = 13, color = c.text3, mono = true, upper = true)
    }
}

/** Полоса, бегущая слева направо, — идёт загрузка или отправка. */
private fun Modifier.scanning(on: Boolean): Modifier = if (!on) this else composed {
    val accent = colors.accent
    val x by rememberInfiniteTransition(label = "scan")
        .animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x")
    drawWithContent {
        drawContent()
        val band = size.width * 0.4f
        val start = x * size.width - band / 2
        drawRect(Brush.horizontalGradient(
            listOf(Color.Transparent, accent.copy(alpha = 0.28f), Color.Transparent), startX = start, endX = start + band,
        ))
    }
}

private val PLATE = DpSize(220.dp, 160.dp)

private val IMAGE_MAX_W = 280.dp
private val IMAGE_MAX_H = 360.dp
private val IMAGE_MIN_W = 160.dp // чтобы строка «автор · время» не сжималась

/** Размер превью по пропорциям картинки: пузырь облегает её, без полос по краям. */
internal fun imageSize(w: Int, h: Int): DpSize {
    if (w <= 0 || h <= 0) return DpSize(IMAGE_MAX_W, IMAGE_MAX_W)
    val aspect = w.toFloat() / h
    return if (aspect >= IMAGE_MAX_W / IMAGE_MAX_H) DpSize(IMAGE_MAX_W, IMAGE_MAX_W / aspect)
    else DpSize(maxOf(IMAGE_MAX_H * aspect, IMAGE_MIN_W), IMAGE_MAX_H) // очень узкие — лёгкая обрезка по высоте
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
    else -> m.body?.decodeToString()?.replace("```", "") ?: ""
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
