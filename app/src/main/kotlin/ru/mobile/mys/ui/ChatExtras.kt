package ru.mobile.mys.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mobile.mys.storage.Message

// ── Разделители дат ─────────────────────────────────────────────────────────

internal sealed interface ChatEntry { val key: Any }
internal class MsgEntry(val m: Message) : ChatEntry { override val key: Any get() = m.id }
internal class DayEntry(val day: LocalDate) : ChatEntry { override val key: Any get() = "day-$day" }

internal fun dayOf(m: Message, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(m.sentAt ?: m.receivedAt ?: 0).atZone(zone).toLocalDate()

/** Сообщения по времени → с заголовком дня перед первым сообщением каждого дня. */
internal fun chatEntries(messages: List<Message>, zone: ZoneId = ZoneId.systemDefault()): List<ChatEntry> {
    val out = ArrayList<ChatEntry>(messages.size + 8)
    var last: LocalDate? = null
    for (m in messages) {
        val d = dayOf(m, zone)
        if (d != last) { out += DayEntry(d); last = d }
        out += MsgEntry(m)
    }
    return out
}

private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

internal fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
    today -> "Сегодня"
    today.minusDays(1) -> "Вчера"
    else -> "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}" + if (day.year != today.year) " ${day.year}" else ""
}

@Composable
internal fun DaySeparator(day: LocalDate) = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    val c = colors
    T(dayLabel(day), Modifier.background(c.surface).border(1.dp, c.line).padding(horizontal = 10.dp, vertical = 3.dp),
        size = 12, color = c.text2, mono = true, upper = true)
}

// ── Свайп влево — ответить ─────────────────────────────────────────────────

/** Сдвинуть сообщение влево до порога — ответ (с вибрацией на пороге). */
@Composable
internal fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    if (!enabled) { Box(Modifier.fillMaxWidth(), content = content); return }
    val c = colors
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val limit = with(LocalDensity.current) { 64.dp.toPx() }
    val shift = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().draggable(
            rememberDraggableState { d ->
                val v = (shift.value + d).coerceIn(-limit * 1.4f, 0f)
                scope.launch { shift.snapTo(v) }
                val now = v <= -limit
                if (now && !armed) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                armed = now
            },
            Orientation.Horizontal,
            onDragStopped = {
                if (armed) onReply()
                armed = false
                shift.animateTo(0f, spring(dampingRatio = 0.6f))
            },
        ),
    ) {
        val progress = (-shift.value / limit).coerceIn(0f, 1f)
        T("↩", Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
            .graphicsLayer { alpha = progress; val k = 0.6f + 0.4f * progress; scaleX = k; scaleY = k },
            size = 22, color = c.accent, bold = true)
        Box(Modifier.fillMaxWidth().offset { IntOffset(shift.value.roundToInt(), 0) }, content = content)
    }
}

// ── Меню сообщения: пузырь приподнят, фон затемнён ──────────────────────────

internal class MenuAction(val label: String, val danger: Boolean = false, val run: () -> Unit)

/**
 * [anchor] — где пузырь лежит в чате (в координатах экрана чата): копия пузыря
 * встаёт на то же место, меню — под ним (или над, если снизу не хватает места).
 */
@Composable
internal fun MessageMenu(
    anchor: Rect, out: Boolean, actions: List<MenuAction>, onDismiss: () -> Unit,
    bubble: @Composable () -> Unit,
) {
    val c = colors
    val density = LocalDensity.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(180)) }
    BackHandler(onBack = onDismiss)
    var areaH by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { areaH = it.height.toFloat() }
            .graphicsLayer { alpha = shown.value }
            .background(Color(0xB3000000))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
    ) {
        val menuH = with(density) { (ROW_H * actions.size + 8.dp).toPx() }
        val gap = with(density) { 8.dp.toPx() }
        val bubbleTop = anchor.top.coerceAtLeast(gap)
        val below = bubbleTop + anchor.height + gap + menuH < areaH - gap
        val menuTop = if (below) bubbleTop + anchor.height + gap else (bubbleTop - gap - menuH).coerceAtLeast(gap)
        val align = if (out) Alignment.TopEnd else Alignment.TopStart
        Box(
            Modifier.offset { IntOffset(anchor.left.roundToInt(), bubbleTop.roundToInt()) }
                .graphicsLayer { val k = 1f + 0.03f * shown.value; scaleX = k; scaleY = k }
                .clickable(remember { MutableInteractionSource() }, null) {},
        ) { bubble() }
        Column(
            Modifier.align(align).offset { IntOffset(0, menuTop.roundToInt()) }
                .padding(horizontal = 16.dp).width(MENU_W)
                .graphicsLayer { val k = 0.92f + 0.08f * shown.value; scaleX = k; scaleY = k; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (out) 1f else 0f, if (below) 0f else 1f) }
                .blockShadow(c.accent, offset = 6.dp).background(c.surface).border(2.dp, c.line).padding(vertical = 4.dp),
        ) {
            actions.forEach { a ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = ROW_H).clickable { onDismiss(); a.run() }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { T(a.label, size = 16, color = if (a.danger) c.warn else c.text, bold = a.danger) }
            }
        }
    }
}

private val ROW_H = 48.dp
private val MENU_W = 220.dp

// ── Кнопка «вниз» со счётчиком новых ───────────────────────────────────────

@Composable
internal fun BoxScope.ScrollDownButton(visible: Boolean, unread: Int, onClick: () -> Unit) {
    val c = colors
    AnimatedVisibility(visible, Modifier.align(Alignment.BottomEnd).padding(16.dp), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        Box {
            Box(
                Modifier.size(48.dp).blockShadow(c.line).background(c.surface).border(2.dp, c.line).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { T("↓", size = 22, bold = true) }
            if (unread > 0) T(
                if (unread > 99) "99+" else "$unread",
                Modifier.align(Alignment.TopStart).offset((-8).dp, (-8).dp).background(c.accent).padding(horizontal = 6.dp, vertical = 1.dp),
                size = 12, color = Color.White, bold = true, mono = true,
            )
        }
    }
}

// ── Фото на весь экран ─────────────────────────────────────────────────────

/** Просмотр: щипок и двойное нажатие — масштаб, смахнуть вниз/вверх — закрыть. */
@Composable
internal fun PhotoViewer(m: Message, onSave: () -> Unit, onDismiss: () -> Unit) {
    val bytes = m.body ?: return onDismiss()
    val bitmap by produceState<ImageBitmap?>(null, m.id) { value = withContext(Dispatchers.Default) { decodeFull(bytes) } }
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val drag = remember { Animatable(0f) }
    var chrome by remember { mutableStateOf(true) }
    val closeAt = with(LocalDensity.current) { 120.dp.toPx() }
    BackHandler(onBack = onDismiss)
    val fade = (1f - abs(drag.value) / (closeAt * 3)).coerceIn(0.3f, 1f)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade)).clickable(remember { MutableInteractionSource() }, null) {}) {
        bitmap?.let { b ->
            Image(
                b, m.filename, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { chrome = !chrome },
                            onDoubleTap = { scope.launch { if (scale.value > 1.01f) { pan = Offset.Zero; scale.animateTo(1f) } else scale.animateTo(2.5f) } },
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val move = event.calculatePan()
                                if (scale.value > 1.01f || zoom != 1f) {
                                    val s = (scale.value * zoom).coerceIn(1f, 5f)
                                    scope.launch { scale.snapTo(s) }
                                    val maxX = size.width * (s - 1) / 2
                                    val maxY = size.height * (s - 1) / 2
                                    pan = Offset((pan.x + move.x).coerceIn(-maxX, maxX), (pan.y + move.y).coerceIn(-maxY, maxY))
                                } else {
                                    scope.launch { drag.snapTo(drag.value + move.y) }
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } while (event.changes.any { it.pressed })
                            if (abs(drag.value) > closeAt) onDismiss()
                            else scope.launch { drag.animateTo(0f, spring()) }
                            if (scale.value <= 1.01f) pan = Offset.Zero
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale.value; scaleY = scale.value
                        translationX = pan.x; translationY = pan.y + drag.value
                    },
            )
        }
        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                BrutalButton("✕", small = true, onClick = onDismiss)
                T(m.filename ?: "Изображение", Modifier.weight(1f), size = 14, color = Color.White, maxLines = 1)
                BrutalButton("Сохранить", small = true, onClick = onSave)
            }
        }
    }
}

private fun decodeFull(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** Исходное сообщение для цитаты: ближайшее раньше [m] от того же автора с тем же началом. */
internal fun quoteTarget(messages: List<Message>, m: Message, own: String?): Message? {
    val author = m.replyAuthor ?: return null
    val snippet = (m.replySnippet ?: "").trim().take(40)
    return messages.lastOrNull { t ->
        t.id < m.id && (t.sender == author || (t.direction == "out" && (author == own || author == "вы"))) &&
            when (t.kind) {
                "image" -> snippet.isEmpty() || snippet.startsWith("Изображение")
                "file" -> snippet.isEmpty() || snippet == (t.filename ?: "Файл")
                else -> (t.body?.decodeToString() ?: "").trim().startsWith(snippet)
            }
    }
}
