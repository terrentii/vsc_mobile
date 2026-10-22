package ru.mobile.mys

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Foreground-сервис «на связи»: держит процесс (а с ним WS «Центра» и P2P-сессии
 * из [AppGraph]) живым, пока приложение свёрнуто.
 */
class MysService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifications.service(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifications.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(Notifications.ID_SERVICE, n)
        }
        // После гибели процесса vault закрыт — без пароля перезапуск бессмыслен.
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, MysService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, MysService::class.java))
    }
}
