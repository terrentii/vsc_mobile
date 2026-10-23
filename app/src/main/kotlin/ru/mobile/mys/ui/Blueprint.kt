package ru.mobile.mys.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private const val SCAN_MS = 20_000
private const val MAJOR_EVERY = 5 // жирная линия каждые 5 клеток, как на миллиметровке

/**
 * Фон чата «Чертёж»: ГОСТ-миллиметровка в кобальте и медленная «развёртка»
 * сверху вниз, подсвечивающая сетку. Обычная отрисовка Compose: работает
 * с API 26, AGSL (API 33+) не нужен.
 */
@Composable
fun Blueprint(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val accent = colors.accent
    val scan by rememberInfiniteTransition("blueprint").animateFloat(
        0f, 1f, infiniteRepeatable(tween(SCAN_MS, easing = LinearEasing)), "scan",
    )
    Box(
        modifier.drawWithCache {
            val step = 12.dp.toPx()
            val fine = Path()
            val major = Path()
            var i = 0
            var x = 0f
            while (x <= size.width) {
                (if (i++ % MAJOR_EVERY == 0) major else fine).run { moveTo(x, 0f); lineTo(x, size.height) }
                x += step
            }
            i = 0
            var y = 0f
            while (y <= size.height) {
                (if (i++ % MAJOR_EVERY == 0) major else fine).run { moveTo(0f, y); lineTo(size.width, y) }
                y += step
            }
            val line = Stroke(1.dp.toPx())
            val band = size.height * 0.12f
            fun glow(alpha: Float, at: Float) = Brush.verticalGradient(
                listOf(Color.Transparent, accent.copy(alpha = alpha), Color.Transparent), at - band, at + band,
            )
            onDrawBehind {
                drawPath(fine, accent.copy(alpha = 0.06f), style = line)
                drawPath(major, accent.copy(alpha = 0.12f), style = line)
                // развёртка заходит из-за верхнего края и уходит за нижний
                val at = scan * (size.height + 2 * band) - band
                drawRect(glow(0.07f, at))
                drawPath(fine, glow(0.18f, at), style = line)
                drawPath(major, glow(0.45f, at), style = line)
            }
        },
    ) { content() }
}
