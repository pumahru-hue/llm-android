package com.example.agent

import android.app.Service
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
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.util.Locale

object ListenState { val on = MutableStateFlow(false) }

/** Слушает микрофон, ищет имя ассистента (Vosk), команду отдаёт в единый чат. */
class ListenService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Chat.note("⚠ Сбой: $e") }
    )
    private var wake: PowerManager.WakeLock? = null
    private var loop: Job? = null
    private var speaker: Speaker? = null
    @Volatile private var busy = false
    @Volatile private var needReset = false

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannel(this)
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            Notifs.listen = "Подготовка…"
            ServiceCompat.startForeground(this, Notifs.ID, Notifs.build(this), type)
        } catch (t: Throwable) {
            Chat.note("⚠ Не удалось запустить прослушивание: ${t.message}")
            Notifs.listen = ""
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
                Chat.note("⚠ Прослушивание остановлено: ${t.message}")
            } finally {
                ListenState.on.value = false
                Level.level.value = 0f
                wake?.takeIf { it.isHeld }?.release()
                stopSelf()
            }
        }
    }

    private suspend fun runLoop() {
        if (norm(Settings.wakeName(this)).isBlank()) error("не задано имя ассистента (Настройки → Активация)")

        status("Готовлю распознавание…")
        val model = Stt.model(this) { status(it) }

        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate) * 2)
        check(ar.state == AudioRecord.STATE_INITIALIZED) { "микрофон недоступен" }

        var rec = Recognizer(model, rate.toFloat())
        val utt = ByteArrayOutputStream()
        val chunk = ByteArray(3200)                 // 100 мс
        val preRoll = 32000                         // 1 с перед речью
        var speechActive = false
        var awaiting = false
        var deadline = 0L
        var muted = false

        ar.startRecording()
        status("Слушаю. Имя: «${Settings.wakeName(this)}»")
        try {
            while (currentCoroutineContext().isActive) {
                val n = ar.read(chunk, 0, chunk.size)
                if (n < 0) { delay(100); continue }
                if (n == 0) continue
                if (busy) { Level.level.value = 0f; continue }
                if (Speaker.isBusy()) { muted = true; Level.level.value = 0f; continue }
                if (muted) {
                    muted = false
                    rec.close(); rec = Recognizer(model, rate.toFloat())
                    utt.reset(); speechActive = false
                }
                if (needReset) {
                    needReset = false
                    rec.close(); rec = Recognizer(model, rate.toFloat())
                    utt.reset(); speechActive = false
                    status("Слушаю. Имя: «${Settings.wakeName(this)}»")
                }
                if (awaiting && SystemClock.elapsedRealtime() > deadline) {
                    awaiting = false
                    status("Слушаю. Имя: «${Settings.wakeName(this)}»")
                }

                Level.level.value = Level.rms(chunk, n)

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

                    Level.heard.value = text
                    status("Слышал: ${text.take(60)}")
                    val words = norm(text).split(" ").filter { it.isNotEmpty() }
                    val nameWords = norm(Settings.wakeName(this)).split(" ").filter { it.isNotEmpty() }
                    val idx = findName(words, nameWords)
                    when {
                        awaiting -> { awaiting = false; dispatch(pcm, text) }
                        idx >= 0 && words.size - nameWords.size >= 2 -> dispatch(pcm, text)
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
            rec.close()   // общую модель Vosk не закрываем
        }
    }

    private fun dispatch(pcm: ByteArray, text: String) {
        busy = true
        scope.launch {
            try {
                val host = AgentRuntime.host(this@ListenService)
                if (!host.isReady) {
                    Chat.note("⚠ Модель Gemma ещё не загружена")
                    return@launch
                }
                val sp = speaker ?: return@launch
                status("Думаю…")
                val hint = "Сейчас ${nowRu()}. Пользователь обратился к тебе по имени " +
                    "«${Settings.wakeName(this@ListenService)}». Не повторяй имя, просто выполни просьбу."
                val user = ChatMsg(ChatStore.newId(), "user", text, kind = "voice")
                val speak = Settings.ttsMode(this@ListenService) != "never"
                Chat.ask(this@ListenService, user, Chat.voiceParts(pcm, hint), extraTokens = pcm.size / 32000 * 25)
                    .catch { }
                    .collect { c -> if (speak) sp.feed(c) }
                if (speak) sp.flush()
                status("Говорю…")
                delay(400)
                var t = 0
                while (sp.isSpeaking() && t < 600) { delay(200); t++ }   // до 2 минут
                delay(800)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Chat.note("⚠ Сбой обработки: $t")
            } finally {
                needReset = true
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
        Notifs.listen = t
        Notifs.post(this)
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        speaker?.shutdown()
        wake?.takeIf { it.isHeld }?.release()
        ListenState.on.value = false
        Notifs.listen = ""
        Notifs.repostLater(this)
        super.onDestroy()
    }

    companion object {
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
