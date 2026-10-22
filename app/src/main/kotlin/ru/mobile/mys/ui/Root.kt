package ru.mobile.mys.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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

private sealed interface Screen {
    data object Main : Screen
    data class Chat(val conversationId: Long, val mode: String) : Screen
    data object Settings : Screen
}

@Composable
fun Root() {
    Box(Modifier.fillMaxSize().background(colors.bg).systemBarsPadding().imePadding()) {
        if (AppGraph.vault == null) UnlockScreen() else Unlocked()
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
    when (val s = screen) {
        Screen.Main -> MainScreen(mode, onMode = { mode = it }, onOpen = { screen = Screen.Chat(it, mode) }, onSettings = { screen = Screen.Settings })
        is Screen.Chat -> ChatScreen(s.conversationId, s.mode, onBack = { screen = Screen.Main })
        Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Main })
    }
}

/** Последняя ошибка на 4 с внизу экрана. */
@Composable
private fun ErrorBanner(modifier: Modifier) {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { AppGraph.errors.collect { text = it } }
    LaunchedEffect(text) { if (text != null) { delay(4_000); text = null } }
    text?.let {
        Box(modifier.fillMaxWidth().padding(16.dp).blockShadow(colors.line).background(colors.surface).border(2.dp, colors.warn).padding(14.dp)) {
            T(it, color = colors.warn)
        }
    }
}
