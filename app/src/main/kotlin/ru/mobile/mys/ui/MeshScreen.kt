package ru.mobile.mys.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.mobile.mys.AppGraph
import ru.mobile.mys.radio.BleMesh
import ru.mobile.mys.radio.WifiDirectMesh

/** Раздел «Mesh»: переключатели, соседи по проводам, узлы; пока открыт — Wi-Fi Direct. */
@Composable
fun MeshScreen(onBack: () -> Unit) {
    val c = colors
    val ctx = AppGraph.appContext()
    var enabled by remember { mutableStateOf(AppGraph.setting(AppGraph.K_MESH, "1") == "1") }
    var relay by remember { mutableStateOf(AppGraph.setting(AppGraph.K_MESH_RELAY, "1") == "1") }
    var bt by remember { mutableStateOf(AppGraph.setting(AppGraph.K_MESH_BLE, "1") == "1") }
    var nodes by remember { mutableStateOf(AppGraph.setting(AppGraph.K_MESH_NODES, "")) }
    var note by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf("") }
    var wifi by remember { mutableStateOf<WifiDirectMesh?>(null) }
    var granted by remember { mutableStateOf(0) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted++
        if (bt) AppGraph.startBle()
    }
    LaunchedEffect(Unit) {
        val need = (BleMesh.permissions() + WifiDirectMesh.permissions()).distinct()
        if (AppGraph.mesh != null && !need.all { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            ask.launch(need.toTypedArray())
        } else if (bt) AppGraph.startBle()
    }
    // Wi-Fi Direct — только пока экран открыт
    DisposableEffect(granted) {
        val node = AppGraph.mesh
        val w = if (node != null && WifiDirectMesh.hasPermissions(ctx)) {
            WifiDirectMesh(ctx, node, AppGraph.scope).takeIf { runCatching { it.start() }.getOrDefault(false) }
        } else null
        wifi = w
        onDispose { w?.stop(); wifi = null }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val node = AppGraph.mesh
            stats = if (node == null) "Mesh выключен" else {
                val s = node.stats()
                listOf(
                    "Интернет-узлы: ${AppGraph.meshNet?.connected()?.size ?: 0}",
                    "Bluetooth: " + (AppGraph.ble?.let { "${it.neighbors} соседей" } ?: "выкл."),
                    "Wi-Fi Direct: " + (wifi?.let { "${it.neighbors} соседей" } ?: "нет доступа"),
                    "Всего соседей: ${s["neighbors"]} · маршрутов: ${s["routes"]}",
                    "Переслано для других: ${(s["relayed_bytes"] ?: 0) / 1024} КБ",
                ).joinToString("\n")
            }
            delay(1_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Mesh", onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            T("Каналы P2P идут через соседние телефоны и узлы, когда сервер недоступен.", color = c.text2)
            note?.let { T(it, color = c.accent) }
            Toggle("Mesh-сеть", enabled) {
                enabled = it
                AppGraph.setSetting(AppGraph.K_MESH, if (it) "1" else "0")
                note = "Применится после повторной разблокировки"
            }
            Toggle("Ретрансляция для других", relay) {
                relay = it
                AppGraph.setSetting(AppGraph.K_MESH_RELAY, if (it) "1" else "0")
                note = "Применится после повторной разблокировки"
            }
            Toggle("Bluetooth (в фоне)", bt) { bt = it; AppGraph.setBle(it) }
            T(stats, color = c.text2)

            T("Узлы mesh", Modifier.padding(top = 12.dp), size = 15, color = c.text3, bold = true, upper = true)
            T("Публичный узел сервера подключается сам. Свои — по одному wss://…/mesh в строке.", color = c.text3, size = 14)
            BrutalField(nodes, { nodes = it }, "wss://example.org/mesh", Modifier.fillMaxWidth(), singleLine = false)
            BrutalButton("Сохранить узлы", Modifier.fillMaxWidth()) {
                AppGraph.setSetting(AppGraph.K_MESH_NODES, nodes.trim())
                note = "Узлы сохранены"
            }
        }
    }
}
