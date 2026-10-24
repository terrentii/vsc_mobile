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

/**
 * По умолчанию уведомления без содержимого — только факт. С настройкой «текст в
 * уведомлениях» — беседа и сообщение; на экране блокировки всё равно только факт.
 */
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

    class Preview(val conversationId: Long, val title: String, val text: String)

    fun newMessage(ctx: Context, preview: Preview? = null) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && android.os.Build.VERSION.SDK_INT >= 33) return
        val bare = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Новое сообщение")
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // содержимого нет — скрывать нечего
            .setContentIntent(openApp(ctx))
            .build()
        if (preview == null) {
            NotificationManagerCompat.from(ctx).notify(ID_MESSAGE, bare)
            return
        }
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(preview.title)
            .setContentText(preview.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview.text))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(bare) // экран блокировки — без текста
            .setContentIntent(openApp(ctx))
            .build()
        // своё уведомление на беседу: новое сообщение заменяет прежнее из той же беседы
        NotificationManagerCompat.from(ctx).notify(ID_MESSAGE + 1000 + (preview.conversationId % 100_000).toInt(), n)
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
