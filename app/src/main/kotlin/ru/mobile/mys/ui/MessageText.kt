package ru.mobile.mys.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val FENCE = Regex("```([\\s\\S]*?)```")

/**
 * Текст → чередование (код?, фрагмент) по ```фенсам```, как в вебе (room.html renderText)
 * и десктопе: незакрытый фенс — обычный текст; у кода срезан один перевод строки по краям.
 */
fun splitCode(text: String): List<Pair<Boolean, String>> {
    val out = mutableListOf<Pair<Boolean, String>>()
    var pos = 0
    for (m in FENCE.findAll(text)) {
        if (m.range.first > pos) out += false to text.substring(pos, m.range.first)
        out += true to m.groupValues[1].removePrefix("\r\n").removePrefix("\n").removeSuffix("\n").removeSuffix("\r")
        pos = m.range.last + 1
    }
    if (pos < text.length) out += false to text.substring(pos)
    return out.ifEmpty { listOf(false to text) }
}

/** Тело сообщения: обычный текст и блоки кода. */
@Composable
fun MessageText(text: String, color: Color) {
    val parts = remember(text) { splitCode(text) }
    if (parts.size == 1 && !parts[0].first) return T(text, color = color)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((code, part) in parts) {
            if (code) CodeBlock(part) else part.trim('\n').takeIf { it.isNotEmpty() }?.let { T(it, color = color) }
        }
    }
}

/** Блок кода в стиле веба: шапка «//// CODE · КОПИРОВАТЬ», моноширинный текст с горизонтальной прокруткой. */
@Composable
private fun CodeBlock(code: String) {
    val c = colors
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_500); copied = false } }
    Column(Modifier.border(2.dp, c.line).background(c.bg)) {
        Row(Modifier.background(c.surface3).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("////", size = 12, color = c.accent, bold = true)
            T("code", Modifier.padding(start = 6.dp), size = 12, color = c.text3, bold = true, upper = true)
            Spacer(Modifier.width(24.dp).weight(1f, fill = false))
            T(if (copied) "Скопировано" else "Копировать", Modifier.padding(start = 16.dp).clickable {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code).apply {
                    // Android 13+: не показывать содержимое во всплывающем превью буфера
                    if (Build.VERSION.SDK_INT >= 33) description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                })
                copied = true
            }, size = 12, color = c.accent, bold = true, upper = true)
        }
        BasicText(
            code, Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
            style = TextStyle(color = c.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
            softWrap = false,
        )
    }
}
