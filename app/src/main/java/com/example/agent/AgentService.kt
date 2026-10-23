package com.example.agent

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

/**
 * Держит процесс живым и модель загруженной.
 * START_STICKY + foreground + (опционально) partial wake lock + watchdog от BootReceiver.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val n = notification("Загрузка модели…")
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)

        // Wake lock нужен только на время генерации; здесь держим для простоты отладки. Потом сузим.
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agent:model").apply { setReferenceCounted(false) }

        scope.launch {
            val msg = runCatching { AgentRuntime.host(this@AgentService).init() }
                .getOrElse { "Ошибка: ${it.message}" }
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification("Модель: $msg"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Смахнули из «недавних» — просим систему перезапустить сервис.
        val pi = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 2000, pi)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel(); wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Агент", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Агент работает")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        const val CHANNEL = "agent"; const val NOTIF_ID = 1
        fun start(ctx: Context) {
            val i = Intent(ctx, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        /** Исключение из экономии батареи. Откроет системный диалог. */
        fun requestIgnoreBattery(ctx: Context) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** После перезагрузки поднимаем сервис. (Сервис типа specialUse из BOOT_COMPLETED разрешён.) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) AgentService.start(c)
    }
}
