package ru.mobile.mys.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.mobile.mys.R

/*
 * Дизайн-система десктопа (vsc_desktop/src/mys_ui/theme.py): токены DARK/LIGHT,
 * ГОСТ-шрифты, брутализм — жёсткие рамки, блочная смещённая тень, капс.
 */

class MysColors(
    val bg: Color, val surface: Color, val surface2: Color, val surface3: Color,
    val line: Color, val border2: Color, val text: Color, val text2: Color, val text3: Color,
    val accent: Color, val accentSoft: Color, val ink: Color, val warn: Color, val success: Color,
    val bubbleThem: Color, val bubbleThemText: Color, val field: Color, val activeBg: Color, val activeEdge: Color,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

val Dark = MysColors(
    bg = c(0x06112a), surface = c(0x0d1936), surface2 = c(0x0a1530), surface3 = c(0x182547),
    line = c(0xf3f3f1), border2 = c(0x2c354a), text = c(0xf3f3f1), text2 = c(0xb1b4b9), text3 = c(0x737985),
    accent = c(0x4d7bff), accentSoft = c(0x141e36), ink = c(0x06112a), warn = c(0xff6b73), success = c(0x4ade80),
    bubbleThem = c(0x0d1936), bubbleThemText = c(0xf3f3f1), field = c(0x0d1936), activeBg = c(0x4d7bff), activeEdge = c(0xf3f3f1),
)

val Light = MysColors(
    bg = c(0xf3f3f1), surface = c(0xffffff), surface2 = c(0xf3f3f1), surface3 = c(0xe9e9e5),
    line = c(0x06112a), border2 = c(0xd2d3d5), text = c(0x06112a), text2 = c(0x575e6e), text3 = c(0x868b96),
    accent = c(0x0040ff), accentSoft = c(0xe6ebfb), ink = c(0x06112a), warn = c(0xda4453), success = c(0x1a7f4f),
    bubbleThem = c(0xffffff), bubbleThemText = c(0x06112a), field = c(0xffffff), activeBg = c(0x06112a), activeEdge = c(0x0040ff),
)

val Gost = FontFamily(Font(R.font.gost_b))
val Mono = FontFamily(Font(R.font.mipgost))

val LocalColors = staticCompositionLocalOf { Dark }

val colors: MysColors @Composable get() = LocalColors.current

@Composable
fun MysTheme(dark: Boolean, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalColors provides if (dark) Dark else Light, content = content)

/** Текст ГОСТом; [upper] — капс с разрядкой, как заголовки десктопа. */
@Composable
fun T(
    text: String, modifier: Modifier = Modifier, size: Int = 16, color: Color = colors.text,
    bold: Boolean = false, upper: Boolean = false, mono: Boolean = false, maxLines: Int = Int.MAX_VALUE,
) = BasicText(
    if (upper) text.uppercase() else text, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    style = TextStyle(
        color = color, fontSize = size.sp, fontFamily = if (mono) Mono else Gost,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        letterSpacing = if (upper) 1.sp else 0.sp,
    ),
)

enum class Variant { Default, Primary, Ink, Danger }

private val SHADOW = 4.dp

/** Ширина переключателей настроек: правый край колонки ровный. */
val TOGGLE_W = 104.dp

/** Карточки и диалоги на планшете не растягиваются во всю ширину. */
val MAX_CARD = 480.dp

/** Высота кнопок и полей: всё в одной строке встаёт ровно. */
val CONTROL = 48.dp
val CONTROL_SMALL = 36.dp

private val PRESS = tween<Float>(90)
private val FADE = tween<Color>(180)

/**
 * Блочная тень: смещённый прямоугольник без размытия. [press] 0…1 «вдавливает»
 * лицо на место тени; тень при этом остаётся на месте.
 */
fun Modifier.blockShadow(color: Color, press: Float = 0f, offset: Dp = SHADOW): Modifier =
    padding(end = offset, bottom = offset)
        .offset { IntOffset((offset.toPx() * press).roundToInt(), (offset.toPx() * press).roundToInt()) }
        .drawBehind { val d = offset.toPx() * (1 - press); drawRect(color, Offset(d, d), size) }

@Composable
fun BrutalButton(
    text: String, modifier: Modifier = Modifier, variant: Variant = Variant.Default,
    enabled: Boolean = true, small: Boolean = false, onClick: () -> Unit,
) {
    val c = colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f, PRESS, label = "press")
    val (face, fg, border, shadow) = when (variant) {
        Variant.Primary -> listOf(c.accent, Color.White, c.line, c.line)
        Variant.Ink -> listOf(c.line, c.bg, c.line, c.accent)
        Variant.Danger -> listOf(c.surface, c.warn, c.warn, c.warn)
        Variant.Default -> listOf(c.surface, c.text, c.line, c.line)
    }
    val faceA by animateColorAsState(if (enabled) face else c.surface3, FADE, label = "face")
    val fgA by animateColorAsState(if (enabled) fg else c.text3, FADE, label = "fg")
    val h = if (small) CONTROL_SMALL else CONTROL
    Box(
        modifier
            .blockShadow(if (enabled) shadow else c.border2, press)
            .heightIn(min = h).widthIn(min = h) // короткие подписи («→», «+») — квадрат
            .background(faceA)
            .border(2.dp, if (enabled) border else c.border2)
            .clickable(source, indication = null, enabled = enabled, onClick = onClick)
            // значок в квадрате: широкому «@» иначе не хватало места и он становился «…»
            .padding(horizontal = if (text.length == 1) 4.dp else if (small) 10.dp else 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // одиночный знак («+», «→», «⚙») — значок, ему нужен крупный кегль
        val icon = text.length == 1
        T(text, size = if (icon) (if (small) 18 else 22) else if (small) 13 else 15, color = fgA, bold = true, upper = !icon, maxLines = 1)
    }
}

@Composable
fun BrutalField(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    password: Boolean = false, singleLine: Boolean = true, digits: Boolean = false, tag: String? = null,
    /** Видимый текст, но клавиатура «как для пароля»: без подсказок и запоминания слов (секретная фраза). */
    secret: Boolean = false,
    enabled: Boolean = true,
) {
    val c = colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val edge by animateColorAsState(if (focused) c.accent else c.line, FADE, label = "edge")
    BasicTextField(
        value, onChange,
        // та же тень, что у кнопок: края полей и кнопок совпадают в любой строке и колонке
        modifier.blockShadow(if (focused) c.accent else c.border2).heightIn(min = CONTROL)
            .background(c.field).border(2.dp, edge).padding(horizontal = 12.dp, vertical = 8.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        textStyle = TextStyle(color = c.text, fontSize = 18.sp, fontFamily = Gost),
        singleLine = singleLine,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            password && digits -> KeyboardType.NumberPassword
            password || secret -> KeyboardType.Password
            digits -> KeyboardType.Number
            else -> KeyboardType.Text
        }, autoCorrectEnabled = !(password || secret)),
        interactionSource = source,
        cursorBrush = SolidColor(c.accent),
        enabled = enabled,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) { if (value.isEmpty()) T(placeholder, size = 18, color = c.text3); inner() }
        },
    )
}

/** Шапка экрана: назад, заголовок с подзаголовком, действия справа; снизу — жёсткая линия. */
@Composable
fun TopBar(
    title: String, onBack: (() -> Unit)? = null, big: Boolean = false,
    subtitle: (@Composable () -> Unit)? = null, leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = colors
    Row(
        Modifier.fillMaxWidth().background(c.surface2).heightIn(min = 64.dp)
            .drawBehind { val w = 2.dp.toPx(); drawRect(c.line, Offset(0f, size.height - w), Size(size.width, w)) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        onBack?.let { BrutalButton("←", small = true, onClick = it) }
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            T(title, size = if (big) 26 else 20, bold = true, upper = big || subtitle == null, maxLines = 1)
            subtitle?.invoke()
        }
        actions()
    }
}

/** ПИН-код: цифровая клавиатура по умолчанию, «АБВ» — буквенная (старые буквенные пароли тоже вводятся). */
@Composable
fun PinField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, tag: String? = null) {
    var letters by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BrutalField(value, onChange, placeholder, Modifier.weight(1f), password = true, digits = !letters, tag = tag)
        BrutalButton(if (letters) "123" else "АБВ", Modifier.widthIn(min = 72.dp)) { letters = !letters }
    }
}

/** Строка настройки с переключателем ВКЛ/ВЫКЛ. */
@Composable
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    T(label, Modifier.weight(1f).padding(end = 12.dp))
    BrutalButton(if (on) "Вкл" else "Выкл", Modifier.widthIn(min = TOGGLE_W), variant = if (on) Variant.Primary else Variant.Default, small = true) { onChange(!on) }
}

/**
 * Диалог-оверлей в том же окне: системный Dialog — отдельное окно, оно не
 * наследует FLAG_SECURE (фразу и пароли можно было бы заснять).
 */
@Composable
fun Overlay(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = colors
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(160)) }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Color(0xCC000000)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = MAX_CARD).fillMaxWidth()
                .graphicsLayer { val k = 0.94f + 0.06f * shown.value; scaleX = k; scaleY = k }
                .blockShadow(c.accent, offset = 8.dp)
                .background(c.surface).border(2.dp, c.line)
                .clickable(remember { MutableInteractionSource() }, null) {} // клики внутри не закрывают
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T(title, size = 20, bold = true, upper = true)
            content()
        }
    }
}

/** Ввод одной строки в оверлее; null в [onDone] — отмена. */
@Composable
fun TextPrompt(
    title: String, placeholder: String, onDone: (String?) -> Unit,
    initial: String = "", password: Boolean = false, digits: Boolean = false, okLabel: String = "Готово",
    secret: Boolean = false,
) {
    var value by remember { mutableStateOf(initial) }
    Overlay(title, onDismiss = { onDone(null) }) {
        BrutalField(value, { value = it }, placeholder, Modifier.fillMaxWidth(), password = password, digits = digits, tag = "prompt", secret = secret)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            BrutalButton("Отмена") { onDone(null) }
            BrutalButton(okLabel, variant = Variant.Primary, enabled = value.isNotBlank()) { onDone(value) }
        }
    }
}
