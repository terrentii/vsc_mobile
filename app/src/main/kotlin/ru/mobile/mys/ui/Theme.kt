package ru.mobile.mys.ui

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
    bold: Boolean = false, upper: Boolean = false, mono: Boolean = false,
) = BasicText(
    if (upper) text.uppercase() else text, modifier,
    style = TextStyle(
        color = color, fontSize = size.sp, fontFamily = if (mono) Mono else Gost,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        letterSpacing = if (upper) 1.sp else 0.sp,
    ),
)

enum class Variant { Default, Primary, Ink, Danger }

private val SHADOW = 4.dp

/** Блочная тень: смещённый прямоугольник без размытия; нажатие «вдавливает» лицо на её место. */
fun Modifier.blockShadow(color: Color, pressed: Boolean = false, offset: Dp = SHADOW): Modifier =
    padding(end = offset, bottom = offset).then(
        if (pressed) Modifier.offset(offset, offset)
        else Modifier.drawBehind { drawRect(color, Offset(offset.toPx(), offset.toPx()), size) },
    )

@Composable
fun BrutalButton(
    text: String, modifier: Modifier = Modifier, variant: Variant = Variant.Default,
    enabled: Boolean = true, small: Boolean = false, onClick: () -> Unit,
) {
    val c = colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (face, fg, border, shadow) = when (variant) {
        Variant.Primary -> listOf(c.accent, Color.White, c.ink, c.line)
        Variant.Ink -> listOf(c.line, c.bg, c.line, c.accent)
        Variant.Danger -> listOf(c.surface, c.warn, c.warn, c.warn)
        Variant.Default -> listOf(c.surface, c.text, c.line, c.line)
    }
    Box(
        modifier
            .blockShadow(shadow, pressed && enabled)
            .background(if (enabled) face else c.surface3)
            .border(2.dp, border)
            .clickable(source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 10.dp else 16.dp, vertical = if (small) 6.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, size = if (small) 13 else 15, color = if (enabled) fg else c.text3, bold = true, upper = true) }
}

@Composable
fun BrutalField(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    password: Boolean = false, singleLine: Boolean = true, digits: Boolean = false, tag: String? = null,
) {
    val c = colors
    BasicTextField(
        value, onChange,
        modifier.background(c.field).border(1.dp, c.line).padding(12.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        textStyle = TextStyle(color = c.text, fontSize = 18.sp, fontFamily = Gost),
        singleLine = singleLine,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            password -> KeyboardType.Password
            digits -> KeyboardType.Number
            else -> KeyboardType.Text
        }),
        cursorBrush = SolidColor(c.accent),
        decorationBox = { inner -> Box { if (value.isEmpty()) T(placeholder, size = 18, color = c.text3); inner() } },
    )
}

/** Строка настройки с переключателем ВКЛ/ВЫКЛ. */
@Composable
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    T(label, Modifier.weight(1f).padding(end = 12.dp))
    BrutalButton(if (on) "Вкл" else "Выкл", variant = if (on) Variant.Primary else Variant.Default, small = true) { onChange(!on) }
}

/**
 * Диалог-оверлей в том же окне: системный Dialog — отдельное окно, оно не
 * наследует FLAG_SECURE (фразу и пароли можно было бы заснять).
 */
@Composable
fun Overlay(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = colors
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth()
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
) {
    var value by remember { mutableStateOf(initial) }
    Overlay(title, onDismiss = { onDone(null) }) {
        BrutalField(value, { value = it }, placeholder, Modifier.fillMaxWidth(), password = password, digits = digits, tag = "prompt")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            BrutalButton("Отмена") { onDone(null) }
            BrutalButton(okLabel, variant = Variant.Primary, enabled = value.isNotBlank()) { onDone(value) }
        }
    }
}
