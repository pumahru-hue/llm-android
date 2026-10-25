package com.example.agent

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

object ListenState { val on = MutableStateFlow(false) }

/** «Суббота, 24 октября 2026 года, 19:23» */
fun nowRu(): String {
    val n = ZonedDateTime.now()
    val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
        "августа", "сентября", "октября", "ноября", "декабря")
    val wd = n.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))
    return "$wd, ${n.dayOfMonth} ${months[n.monthValue - 1]} ${n.year} года, " +
        "%02d:%02d".format(n.hour, n.minute)
}

class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wake: PowerManager.WakeLock? = null
    private var loop: Job? = null
    private var speaker: Speaker? = null
    @Volatile private var busy = false
    @Volatile private var needReset = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, notification("Подготовка…"), type)
        } catch (t: Throwable) {
            ToolLog.events.tryEmit("\n[не удалось запустить прослушивание: ${t.message}]\n")
            stopSelf()
            return
        }
        speaker = Speaker(this)
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agent:listen").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (speaker == null) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) {
            loop?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        ListenState.on.value = true
        wake?.acquire(12 * 60 * 60 * 1000L)
        loop = scope.launch {
            try {
                runLoop()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                ToolLog.events.tryEmit("\n[прослушивание остановлено: ${t.message}]\n")
            } finally {
                ListenState.on.value = false
                wake?.takeIf { it.isHeld }?.release()
                stopSelf()
            }
        }
    }

    private fun wakeName(): String =
        getSharedPreferences("agent", MODE_PRIVATE).getString("wake_name", "") ?: ""

    private suspend fun runLoop() {
        val nameCheck = norm(wakeName())
        if (nameCheck.isBlank()) error("не задано имя ассистента")

        val dir = VoskModel.ensure(this) { status(it) }
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val model = Model(dir.path)

        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate) * 2)
        check(ar.state == AudioRecord.STATE_INITIALIZED) { "микрофон недоступен" }

        var rec = Recognizer(model, rate.toFloat())
        val utt = ByteArrayOutputStream()
        val chunk = ByteArray(3200)                 // 100 мс
        val preRoll = 32000                         // 1 с «тишины» перед речью
        var speechActive = false
        var awaiting = false
        var deadline = 0L

        ar.startRecording()
        status("Слушаю. Имя: «${wakeName()}»")
        ToolLog.events.tryEmit("\n👂 Слушаю. Имя: «${wakeName()}»\n")
        try {
            while (isActive) {
                val n = ar.read(chunk, 0, chunk.size)
                if (n < 0) { delay(100); continue }
                if (n == 0 || busy) continue
                if (needReset) {
                    needReset = false
                    rec.close(); rec = Recognizer(model, rate.toFloat())
                    utt.reset(); speechActive = false
                    status("Слушаю. Имя: «${wakeName()}»")
                }
                if (awaiting && SystemClock.elapsedRealtime() > deadline) {
                    awaiting = false
                    status("Слушаю. Имя: «${wakeName()}»")
                }

                utt.write(chunk, 0, n)
                if (utt.size() > rate * 2 * 25) {   // защита: не больше 25 с
                    rec.close(); rec = Recognizer(model, rate.toFloat())
                    utt.reset(); speechActive = false
                    continue
                }

                if (rec.acceptWaveForm(chunk, n)) {
                    val text = JSONObject(rec.result).optString("text")
                    val pcm = utt.toByteArray()
                    utt.reset(); speechActive = false
                    if (text.isBlank()) continue

                    ToolLog.events.tryEmit("\n👂 слышу: $text\n")
                    val words = norm(text).split(" ").filter { it.isNotEmpty() }
                    val nameWords = norm(wakeName()).split(" ").filter { it.isNotEmpty() }
                    val idx = findName(words, nameWords)
                    when {
                        awaiting -> { awaiting = false; dispatch(pcm) }
                        idx >= 0 && words.size - nameWords.size >= 2 -> dispatch(pcm)
                        idx >= 0 -> {
                            awaiting = true
                            deadline = SystemClock.elapsedRealtime() + 8000
                            beep()
                            status("Слушаю команду…")
                            rec.close(); rec = Recognizer(model, rate.toFloat())
                            utt.reset(); speechActive = false
                        }
                    }
                } else {
                    speechActive = JSONObject(rec.partialResult).optString("partial").isNotBlank()
                    if (!speechActive && utt.size() > preRoll) {
                        val b = utt.toByteArray()
                        utt.reset(); utt.write(b, b.size - preRoll, preRoll)
                    }
                }
            }
        } finally {
            runCatching { ar.stop() }
            ar.release()
            rec.close()
            model.close()
        }
    }

    private fun dispatch(pcm: ByteArray) {
        busy = true
        scope.launch {
            try {
                val host = AgentRuntime.host(this@ListenService)
                if (!host.isReady) {
                    ToolLog.events.tryEmit("\n[модель Gemma ещё не загружена]\n")
                    return@launch
                }
                val sp = speaker ?: return@launch
                status("Думаю…")
                val hint = "Сейчас ${nowRu()}. Пользователь обратился к тебе по имени «${wakeName()}». " +
                    "Не повторяй имя, просто выполни просьбу."
                ToolLog.events.tryEmit("\n[аудио ${pcm.size / 32000.0} с]\nАгент: ")
                host.askWithAudio(pcm, hint)
                    .catch { ToolLog.events.tryEmit("\n[ошибка: ${it.message}]\n") }
                    .collect { c -> ToolLog.events.tryEmit(c); sp.feed(c) }
                sp.flush()
                delay(400)
                var t = 0
                while (sp.isSpeaking() && t < 600) { delay(200); t++ }   // до 2 минут
                delay(800)
            } finally {
                needReset = true
                busy = false
            }
        }
    }

    // ---------- сравнение имени ----------

    private fun norm(s: String) = s.lowercase(Locale("ru")).replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun similar(w: String, n: String): Boolean {
        if (w == n) return true
        val tol = when { n.length <= 3 -> 0; n.length <= 6 -> 1; else -> 2 }
        return lev(w, n) <= tol
    }

    private fun findName(words: List<String>, name: List<String>): Int {
        if (name.isEmpty()) return -1
        for (i in 0..(words.size - name.size)) {
            if (name.indices.all { similar(words[i + it], name[it]) }) return i
        }
        return -1
    }

    // ---------- служебное ----------

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 600)
        } catch (_: Throwable) {}
    }

    private fun status(t: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(t))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Прослушивание", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Слушаю по имени")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        speaker?.shutdown()
        wake?.takeIf { it.isHeld }?.release()
        ListenState.on.value = false
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "listen"
        const val NOTIF_ID = 2
        const val ACTION_STOP = "com.example.agent.STOP_LISTEN"

        fun start(ctx: Context) {
            val i = Intent(ctx, ListenService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ListenService::class.java).setAction(ACTION_STOP))
        }
    }
}
