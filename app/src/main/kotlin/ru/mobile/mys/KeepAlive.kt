package ru.mobile.mys

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.launch

/**
 * Связь «Центра» без push-сервиса. При погасшем экране CPU засыпает: кадр /ws может
 * повиснуть необработанным, а тихо умершее соединение — не переподключиться до
 * пробуждения. Поэтому: короткий wakelock на каждый кадр, будильник раз в ~5 мин
 * (в Doze система растягивает до ~15) и досинхронизация при возврате сети.
 */
object KeepAlive {
    private const val INTERVAL_MS = 5 * 60_000L
    private var lock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** Не спать [ms] — успеть разобрать кадр, записать и показать уведомление. */
    @Synchronized
    fun brief(ctx: Context, ms: Long = 10_000) {
        val l = lock ?: ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mys:central").apply { setReferenceCounted(false) }
            .also { lock = it }
        l.acquire(ms) // с таймаутом: не держим дольше, даже если забыли отпустить
    }

    fun start(ctx: Context) {
        schedule(ctx)
        if (netCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = poke(ctx)
        }
        runCatching { ctx.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) }
            .onSuccess { netCallback = cb }
    }

    fun stop(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarm(ctx))
        netCallback?.let { runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        netCallback = null
    }

    private fun schedule(ctx: Context) {
        // неточный будильник, разрешённый в Doze: особого разрешения на точные не нужно
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL_MS, alarm(ctx),
        )
    }

    private fun alarm(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, Receiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun poke(ctx: Context, done: () -> Unit = {}) {
        val c = AppGraph.central ?: return done()
        brief(ctx, 30_000)
        AppGraph.scope.launch { try { c.poke() } finally { done() } }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (AppGraph.central == null) return // хранилище закрыто — будильник больше не ставим
            schedule(ctx)
            val pending = goAsync()
            poke(ctx) { pending.finish() }
        }
    }
}
