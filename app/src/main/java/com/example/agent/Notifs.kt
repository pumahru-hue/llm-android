package com.example.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

/** Одно общее уведомление на оба сервиса (одинаковый id). */
object Notifs {
    const val CHANNEL = "agent"
    const val ID = 1

    @Volatile var model = ""
    @Volatile var listen = ""          // пусто, пока прослушивание выключено
    @Volatile var agentAlive = false

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Агент", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    fun build(ctx: Context): Notification {
        val listening = listen.isNotEmpty()
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (listening) "Агент слушает" else "Агент работает")
            .setContentText(if (listening) listen else model)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(if (listening) "$listen\nМодель: $model" else model)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE)
            )
        if (listening) {
            val stop = PendingIntent.getService(
                ctx, 2,
                Intent(ctx, ListenService::class.java).setAction(ListenService.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(android.R.drawable.ic_media_pause, "Остановить", stop)
        }
        return b.build()
    }

    fun post(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx))
    }

    /** После остановки прослушивания возвращаем уведомление «Агент работает». */
    fun repostLater(ctx: Context) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ if (agentAlive) post(app) }, 800)
    }
}
