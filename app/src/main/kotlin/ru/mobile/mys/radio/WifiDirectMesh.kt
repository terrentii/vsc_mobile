package ru.mobile.mys.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.mobile.mys.mesh.MeshNode
import ru.mobile.mys.mesh.TcpWire

/**
 * Mesh через Wi-Fi Direct — только пока открыт раздел «Mesh» (держит радио и мешает
 * обычному Wi-Fi). Поиск сервиса МЫС (DNS-SD), группа, TcpWire до владельца группы.
 */
@SuppressLint("MissingPermission") // разрешения проверяет вызывающий: [hasPermissions]
class WifiDirectMesh(private val ctx: Context, private val node: MeshNode, private val scope: CoroutineScope) {
    private val manager = ctx.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var jobs = listOf<Job>()
    private var listener: ServerSocket? = null
    @Volatile private var clientJob: Job? = null
    private val count = AtomicInteger()

    val neighbors: Int get() = count.get()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
            val ch = channel ?: return
            manager.requestConnectionInfo(ch) { info ->
                if (info == null || !info.groupFormed) return@requestConnectionInfo
                if (info.isGroupOwner) listen()
                else if (clientJob?.isActive != true) clientJob = scope.launch {
                    runCatching { hold(TcpWire.connect(info.groupOwnerAddress.hostAddress!!, PORT)) }
                }
            }
        }
    }

    fun start(): Boolean {
        val ch = manager?.initialize(ctx, Looper.getMainLooper(), null) ?: return false
        channel = ch
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        manager.addLocalService(ch, WifiP2pDnsSdServiceInfo.newInstance(NAME, TYPE, emptyMap()), null)
        manager.setDnsSdResponseListeners(ch, { instance, _, device ->
            if (instance == NAME && count.get() == 0) manager.connect(ch, WifiP2pConfig().apply { deviceAddress = device.deviceAddress }, null)
        }, null)
        manager.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), null)
        jobs = listOf(scope.launch {
            while (isActive) { // поиск гаснет сам — повторяем
                manager.discoverServices(ch, null)
                delay(30_000)
            }
        })
        return true
    }

    fun stop() {
        val ch = channel ?: return
        channel = null
        jobs.forEach { it.cancel() }
        clientJob?.cancel()
        runCatching { listener?.close() }
        listener = null
        runCatching { ctx.unregisterReceiver(receiver) }
        manager.clearServiceRequests(ch, null)
        manager.clearLocalServices(ch, null)
        manager.removeGroup(ch, null)
        if (Build.VERSION.SDK_INT >= 27) ch.close()
    }

    /** Владелец группы: принимать участников на [PORT]. */
    private fun listen() {
        if (listener != null) return
        val srv = runCatching { ServerSocket(PORT) }.getOrNull() ?: return
        listener = srv
        jobs = jobs + scope.launch(Dispatchers.IO) {
            while (isActive) {
                val s = runCatching { srv.accept() }.getOrNull() ?: break
                launch { hold(TcpWire(s.apply { tcpNoDelay = true })) }
            }
        }
    }

    private suspend fun hold(wire: TcpWire) {
        count.incrementAndGet()
        try { node.serve(wire) } finally { count.decrementAndGet(); runCatching { wire.close() } }
    }

    companion object {
        const val NAME = "mys-mesh"
        const val TYPE = "_mys._tcp"
        const val PORT = 7812

        fun permissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

        fun hasPermissions(ctx: Context) = permissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
