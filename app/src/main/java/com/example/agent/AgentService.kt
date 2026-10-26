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
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannel(this)
        Notifs.agentAlive = true
        Notifs.model = "Запуск…"
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifs.ID, Notifs.build(this), type)

        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agent:model").apply { setReferenceCounted(false) }

        scope.launch { loadModelIfPresent() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DOWNLOAD) startDownload()
        return START_STICKY
    }

    private suspend fun loadModelIfPresent() {
        val host = AgentRuntime.host(this)
        if (!host.modelFile.exists()) {
            status("Модели нет. Нажмите «Скачать модель».")
            return
        }
        status("Загружаю модель…")
        status("Модель: " + runCatching { host.init() }.getOrElse { "ошибка: ${it.message}" })
    }

    private fun startDownload() {
        if (DownloadState.running) return
        val host = AgentRuntime.host(this)
        if (host.modelFile.exists()) { status("Модель уже скачана."); return }
        DownloadState.running = true
        wake?.acquire(3 * 60 * 60 * 1000L)
        scope.launch {
            try {
                var last = 0L
                val part = Downloader.download(host.modelFile) { done, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - last > 1000 && total > 0) {
                        last = now
                        status("Скачивание: ${done / 1048576} из ${total / 1048576} МБ (${done * 100 / total}%)")
                    }
                }
                status("Проверяю файл…")
                if (!Downloader.sha256Ok(part)) {
                    part.delete()
                    status("Файл повреждён. Нажмите «Скачать модель» ещё раз.")
                    return@launch
                }
                part.renameTo(host.modelFile)
                status("Загружаю модель…")
                status("Модель: " + host.init())
            } catch (t: Throwable) {
                status("Ошибка скачивания: ${t.message}. Нажмите «Скачать» для докачки.")
            } finally {
                DownloadState.running = false
                wake?.takeIf { it.isHeld }?.release()
            }
        }
    }

    private fun status(t: String) {
        DownloadState.text.value = t
        Notifs.model = t
        Notifs.post(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val pi = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 2000, pi)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel(); wake?.takeIf { it.isHeld }?.release()
        Notifs.agentAlive = false
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
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        const val CHANNEL = "agent"; const val NOTIF_ID = 1
        const val ACTION_DOWNLOAD = "com.example.agent.DOWNLOAD"

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, AgentService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun requestIgnoreBattery(ctx: Context) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) AgentService.start(c)
    }
}
