package ru.mobile.mys.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.mobile.mys.AppGraph
import ru.mobile.mys.DECENTRALIZED

private const val TWO_PANE_DP = 600
private val LIST_PANE = 360.dp

private sealed interface Screen {
    data object Main : Screen
    data class Chat(val conversationId: Long, val mode: String) : Screen
    data object Settings : Screen
}

@Composable
fun Root() {
    Box(Modifier.fillMaxSize().background(colors.bg).systemBarsPadding().imePadding()) {
        Crossfade(AppGraph.vault == null, label = "lock") { locked -> if (locked) UnlockScreen() else Unlocked() }
        ErrorBanner(Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Unlocked() {
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    var mode by rememberSaveable { mutableStateOf(DECENTRALIZED) }
    BackHandler(screen != Screen.Main) { screen = Screen.Main }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS) }
    // Планшет, развёрнутый складной, альбомная ориентация: список слева, чат справа
    if (LocalConfiguration.current.screenWidthDp >= TWO_PANE_DP) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(LIST_PANE).fillMaxHeight()) {
                MainScreen(mode, onMode = { mode = it }, onOpen = { screen = Screen.Chat(it, mode) },
                    onSettings = { screen = Screen.Settings }, selected = (screen as? Screen.Chat)?.conversationId)
            }
            Box(Modifier.width(2.dp).fillMaxHeight().background(colors.line))
            AnimatedContent(screen, Modifier.weight(1f), transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "pane") { s ->
                when (s) {
                    Screen.Main -> EmptyPane()
                    is Screen.Chat -> ChatScreen(s.conversationId, s.mode, onBack = null)
                    Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Main })
                }
            }
        }
        return
    }
    // Вглубь (чат, настройки) — въезд справа, назад — слева; старый экран слегка сдвигается и гаснет
    AnimatedContent(screen, transitionSpec = {
        val forward = targetState != Screen.Main
        val dir = if (forward) 1 else -1
        (slideInHorizontally(tween(260)) { it * dir / 3 } + fadeIn(tween(260))) togetherWith
            (slideOutHorizontally(tween(200)) { -it * dir / 6 } + fadeOut(tween(160)))
    }, label = "screen") { s -> when (s) {
        Screen.Main -> MainScreen(mode, onMode = { mode = it }, onOpen = { screen = Screen.Chat(it, mode) }, onSettings = { screen = Screen.Settings })
        is Screen.Chat -> ChatScreen(s.conversationId, s.mode, onBack = { screen = Screen.Main })
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Main })
    } }
}

/** Правая панель, пока ничего не открыто. */
@Composable
private fun EmptyPane() = Blueprint(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        T("МЫС", size = 40, bold = true, upper = true)
        T("Выберите канал или комнату слева", color = colors.text3)
    }
}

/** Последняя ошибка на 4 с внизу экрана. */
@Composable
private fun ErrorBanner(modifier: Modifier) {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { AppGraph.errors.collect { text = it } }
    LaunchedEffect(text) { if (text != null) { delay(4_000); text = null } }
    var last by remember { mutableStateOf("") }
    text?.let { last = it }
    AnimatedVisibility(text != null, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Box(Modifier.fillMaxWidth().padding(16.dp).blockShadow(colors.warn).background(colors.surface).border(2.dp, colors.warn).padding(14.dp)) {
            T(last, color = colors.warn)
        }
    }
}
