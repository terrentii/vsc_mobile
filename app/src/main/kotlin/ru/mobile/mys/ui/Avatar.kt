package ru.mobile.mys.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.mobile.mys.AppGraph

/** Аватарка комнаты «Центра»: картинка (кэш сервиса по rev) или монограмма. */
@Composable
fun RoomAvatar(conversationId: Long, avatarRev: Int, title: String, size: Dp, selected: Boolean = false) {
    val c = colors
    val bitmap by produceState<ImageBitmap?>(null, conversationId, avatarRev) {
        value = if (avatarRev == 0) null else withContext(Dispatchers.IO) {
            runCatching { AppGraph.central?.avatar(conversationId) }.getOrNull()
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }
    Box(Modifier.size(size).background(if (selected) c.accent else c.surface).border(2.dp, c.line), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b, null, Modifier.size(size), contentScale = ContentScale.Crop)
        else T(title.trim().take(1).ifEmpty { "#" }, size = (size.value / 2.2f).toInt(), bold = true, upper = true,
            color = if (selected) androidx.compose.ui.graphics.Color.White else c.text)
    }
}
