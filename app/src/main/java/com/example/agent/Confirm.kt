package com.example.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Подтверждение опасных действий: уведомление с кнопками «Да» / «Нет», ожидание до 60 секунд. */
object Confirm {
    private const val CHANNEL = "confirm"
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()
    private val ids = AtomicInteger(100)

    /** Блокирует вызывающий поток до ответа. Нет ответа или нет уведомлений — отказ. */
    fun ask(tool: String, source: String, details: String): Boolean {
        val app = App.instance ?: return false
        val id = ids.getAndIncrement()
        val f = CompletableFuture<Boolean>()
        pending[id] = f
        Chat.note("⏳ Нужно подтверждение: $tool\n$details")
        runCatching { show(app, id, tool, source, details) }
        val ok = try { f.get(60, TimeUnit.SECONDS) } catch (_: Throwable) { false }
        pending.remove(id)
        runCatching { app.getSystemService(NotificationManager::class.java).cancel(id) }
        Chat.note(if (ok) "✅ Разрешено: $tool" else "⛔ Отклонено или время вышло: $tool")
        return ok
    }

    fun answer(id: Int, yes: Boolean) { pending[id]?.complete(yes) }

    private fun show(app: Context, id: Int, tool: String, source: String, details: String) {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Подтверждения", NotificationManager.IMPORTANCE_HIGH))
        }
        fun pi(yes: Boolean) = PendingIntent.getBroadcast(
            app, id * 2 + if (yes) 1 else 0,
            Intent(app, ConfirmReceiver::class.java).putExtra("id", id).putExtra("yes", yes),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(app, 0, Intent(app, ChatActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Разрешить действие? $tool")
            .setContentText(details.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText("$source\n$details"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setTimeoutAfter(60_000)
            .setAutoCancel(true)
            .addAction(0, "Да", pi(true))
            .addAction(0, "Нет", pi(false))
            .build()
        nm.notify(id, n)
    }
}

class ConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Confirm.answer(i.getIntExtra("id", 0), i.getBooleanExtra("yes", false))
    }
}
