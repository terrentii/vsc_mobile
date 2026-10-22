package ru.mobile.mys

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Уведомления без содержимого: ни текста, ни отправителя — только факт. */
object Notifications {
    private const val CH_SERVICE = "service"
    private const val CH_MESSAGES = "messages"
    const val ID_SERVICE = 1
    private const val ID_MESSAGE = 2

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Связь", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun service(ctx: Context): Notification = NotificationCompat.Builder(ctx, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("МЫС на связи")
        .setOngoing(true)
        .setContentIntent(openApp(ctx))
        .build()

    fun newMessage(ctx: Context) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && android.os.Build.VERSION.SDK_INT >= 33) return
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Новое сообщение")
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // содержимого нет — скрывать нечего
            .setContentIntent(openApp(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_MESSAGE, n)
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
