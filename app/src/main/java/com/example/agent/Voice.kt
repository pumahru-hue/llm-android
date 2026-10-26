package com.example.agent

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Запись с микрофона: 16 kHz, mono, PCM 16-bit. Push-to-talk: start() ... stop(). */
class Recorder {
    private val rate = 16000
    private var rec: AudioRecord? = null
    private var job: Job? = null
    private val buf = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        buf.reset(); rec = r; r.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val tmp = ByteArray(min)
            while (isActive) { val n = r.read(tmp, 0, tmp.size); if (n > 0) buf.write(tmp, 0, n) }
        }
    }

    suspend fun stop(): ByteArray {
        job?.cancelAndJoin()
        rec?.run { stop(); release() }
        rec = null
        return buf.toByteArray()
    }
}

/**
 * Озвучка системным TTS. Потоковая: говорим по предложениям, пока модель ещё генерирует.
 * Состояние «говорю» общее на всё приложение: Speaker.isBusy().
 */
class Speaker(ctx: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { st ->
        if (st == TextToSpeech.SUCCESS) {
            ready = true
            tts.language = Locale("ru", "RU")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finish(utteranceId)
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) = finish(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
            })
        }
    }
    private val pending = StringBuilder()

    fun feed(chunk: String) {
        pending.append(chunk)
        val re = Regex("(?<=[.!?…\n])\\s+")
        val parts = pending.toString().split(re)
        if (parts.size > 1) {
            parts.dropLast(1).forEach { say(it) }
            pending.clear(); pending.append(parts.last())
        }
    }

    fun flush() { if (pending.isNotBlank()) say(pending.toString()); pending.clear() }

    private fun say(t: String) {
        if (!ready || t.isBlank()) return
        val id = "u${counter.incrementAndGet()}"
        live.add(id)
        if (tts.speak(t.trim(), TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) finish(id)
    }

    fun isSpeaking(): Boolean = ready && tts.isSpeaking

    fun stop() { tts.stop(); live.clear(); lastEnd = SystemClock.elapsedRealtime() }

    fun shutdown() { tts.stop(); tts.shutdown(); live.clear() }

    companion object {
        private val live = ConcurrentHashMap.newKeySet<String>()
        private val counter = AtomicInteger()
        @Volatile private var lastEnd = 0L

        private fun finish(id: String?) {
            if (id != null) live.remove(id)
            lastEnd = SystemClock.elapsedRealtime()
        }

        /** true, пока что-то озвучивается, плюс «хвост» 0,9 с, чтобы не поймать эхо. */
        fun isBusy(): Boolean = live.isNotEmpty() || SystemClock.elapsedRealtime() - lastEnd < 900
    }
}
