package ru.mobile.mys.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mobile.mys.AppGraph
import ru.mobile.mys.central.CentralService
import ru.mobile.mys.central.Member
import ru.mobile.mys.central.RoomMeta

private const val AVATAR_MAX = 5 * 1024 * 1024

/** Настройки комнаты «Центра» — разделы по роли и режиму, как /room/<код>/settings в вебе. */
@Composable
fun RoomSettingsScreen(conversationId: Long, onBack: () -> Unit, onGone: () -> Unit) {
    val c = colors
    val ctx = LocalContext.current
    val central = AppGraph.central ?: return
    var meta by remember { mutableStateOf(central.meta(conversationId)) }
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var privacy by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var busy by remember { mutableStateOf(true) }
    val me = central.session?.username

    fun op(block: suspend CentralService.() -> RoomMeta?) {
        busy = true
        AppGraph.scope.launch {
            try {
                val m = central.block()
                withContext(Dispatchers.Main) {
                    if (m == null) onGone() else { meta = m; m.memberList?.let { members = it } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppGraph.errors.tryEmit(AppGraph.centralMessage(e))
            } finally {
                busy = false
            }
        }
    }

    fun roomOp(action: suspend ru.mobile.mys.central.RestClient.(Long) -> RoomMeta?) = op { roomOp(conversationId, action) }

    LaunchedEffect(conversationId) {
        op { loadRoom(conversationId) }
        privacy = withContext(Dispatchers.IO) { runCatching { central.privacy() }.getOrNull() }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) AppGraph.run {
            val data = ctx.contentResolver.openInputStream(uri)?.use { readCapped(it, AVATAR_MAX + 1) } ?: return@run
            withContext(Dispatchers.Main) { roomOp { putAvatar(it, data) } }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val m = meta
        val title = m?.title ?: m?.name ?: "Комната"
        TopBar("Настройки", onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (m == null) { T(if (busy) "Загрузка…" else "Комната недоступна", color = c.text3); return@Column }
            val creator = m.role == "godfather"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoomAvatar(conversationId, m.avatarRev, title, 64.dp)
                Column {
                    T(title, size = 22, bold = true, maxLines = 1)
                    T(if (m.isDialog) "Диалог" else "Код ${m.code}", size = 13, color = c.text3, upper = true)
                }
            }

            val proposed = m.dialogProposedBy
            if (proposed != null && !m.isDialog) {
                if (proposed == me) {
                    T("Вы предложили ${m.peer} диалог — ждём ответа.", color = c.text2)
                    BrutalButton("Отозвать", small = true, enabled = !busy) { roomOp { dialogAction(it, "decline") } }
                } else {
                    T("$proposed ${if (m.role == "invited") "приглашает вас в диалог" else "предлагает сделать комнату диалогом"}.", color = c.text2)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BrutalButton("Принять", small = true, variant = Variant.Primary, enabled = !busy) { roomOp { dialogAction(it, "accept") } }
                        BrutalButton("Отклонить", small = true, enabled = !busy) { roomOp { dialogAction(it, "decline") } }
                    }
                }
            }

            if (m.isDialog) {
                Section("Моё название")
                T("Видите только вы; пусто — логин собеседника.", size = 13, color = c.text3)
                var own by remember(m.title) { mutableStateOf(if (m.title != m.peer) m.title ?: "" else "") }
                BrutalField(own, { own = it }, m.peer ?: "Название", Modifier.fillMaxWidth(), tag = "room_title")
                BrutalButton("Сохранить", Modifier.fillMaxWidth(), Variant.Primary, enabled = !busy) { roomOp { setTitle(it, own.trim()) } }
            } else if (creator && !m.isDirect) {
                Section("Общее")
                var name by remember(m.name) { mutableStateOf(m.name ?: "") }
                BrutalField(name, { name = it }, "Название (пусто — код)", Modifier.fillMaxWidth(), tag = "room_name")
                Toggle("Открытая — войти может любой с кодом", m.isOpen) { open -> roomOp { updateRoom(it, null, open) } }
                BrutalButton("Сохранить название", Modifier.fillMaxWidth(), enabled = !busy) { roomOp { updateRoom(it, name.trim(), null) } }
            }

            if (m.isDialog || creator) {
                Section(if (m.isDialog) "Моя аватарка диалога" else "Аватарка комнаты")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrutalButton("Выбрать картинку", small = true, enabled = !busy) { pick.launch("image/*") }
                    if (m.avatarRev > 0) BrutalButton("Убрать", small = true, enabled = !busy) { roomOp { deleteAvatar(it) } }
                }
                T("PNG, JPEG, WebP до 5 МБ; метаданные фото удаляются.", size = 13, color = c.text3)
            }

            if (!m.isDialog && !m.isDirect) {
                Section("Участники · ${members.size}")
                members.forEach { mem ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        T(mem.login, Modifier.weight(1f), maxLines = 1)
                        T(when (mem.role) { "godfather" -> "прародитель"; "invited" -> "приглашён"; else -> "участник" },
                            Modifier.padding(horizontal = 8.dp), size = 13, color = c.text3)
                        if (creator && mem.login != me) {
                            BrutalButton("✕", small = true, variant = Variant.Danger, enabled = !busy) { roomOp { removeMember(it, mem.login) } }
                        }
                    }
                }
                if (creator) {
                    var add by remember { mutableStateOf("") }
                    BrutalField(add, { add = it }, "Добавить по логину", Modifier.fillMaxWidth(), tag = "add_member")
                    BrutalButton("Добавить", Modifier.fillMaxWidth(), enabled = !busy && add.isNotBlank()) {
                        val who = add.trim()
                        add = ""
                        roomOp { addMember(it, who) }
                    }
                }
            }

            if (!m.isDirect) {
                Section("Диалог")
                val eligible = members.size == 2 && proposed == null && members.none { it.role == "invited" }
                when {
                    m.isDialog -> {
                        T("Закрытая комната только для вас и ${m.peer}: «прочитано», «печатает», «в сети».", size = 14, color = c.text2)
                        BrutalButton("Выключить диалог", Modifier.fillMaxWidth(), enabled = !busy) { roomOp { dialogAction(it, "disable") } }
                    }
                    eligible -> {
                        T("В комнате двое — её можно сделать личным диалогом; ${m.peer} должен согласиться.", size = 14, color = c.text2)
                        BrutalButton("Сделать диалогом", Modifier.fillMaxWidth(), Variant.Primary, enabled = !busy) { roomOp { dialogAction(it, "propose") } }
                    }
                    proposed == null -> T("Диалог возможен, когда в комнате ровно два зарегистрированных участника.", size = 14, color = c.text3)
                }

                privacy?.let { (read, online) ->
                    Section("Приватность (все диалоги)")
                    Toggle("Показывать, что я прочитал", read) { v -> privacy = v to online; AppGraph.run { central.setPrivacy(v, online) } }
                    Toggle("Показывать, что я в сети", online) { v -> privacy = read to v; AppGraph.run { central.setPrivacy(read, v) } }
                }
            }

            if (!creator && !m.isDirect) {
                BrutalButton("Покинуть комнату", Modifier.fillMaxWidth().padding(top = 12.dp), Variant.Danger, enabled = !busy) {
                    roomOp { leaveRoom(it) }
                }
            }
        }
    }
}

/** Не больше [cap] байт (readNBytes — только с API 33); лишнее отсечёт сервер как too_large. */
private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < cap) {
        val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@Composable
private fun Section(title: String) = T(title, Modifier.padding(top = 12.dp), size = 15, color = colors.text3, bold = true, upper = true)
