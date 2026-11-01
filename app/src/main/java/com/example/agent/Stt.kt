package com.example.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/** Общая офлайн-модель Vosk: для имени-активации и для расшифровки голосовых сообщений. */
object Stt {
    @Volatile private var model: Model? = null

    suspend fun model(ctx: Context, say: (String) -> Unit = {}): Model {
        model?.let { return it }
        val dir = VoskModel.ensure(ctx.applicationContext, say)
        return withContext(Dispatchers.IO) {
            synchronized(this@Stt) {
                model ?: run {
                    LibVosk.setLogLevel(LogLevel.WARNINGS)
                    Model(dir.path).also { model = it }
                }
            }
        }
    }

    /** Текст из записи 16 кГц mono PCM16. Пустая строка, если не удалось. */
    suspend fun transcribe(ctx: Context, pcm: ByteArray, say: (String) -> Unit = {}): String = try {
        val m = model(ctx, say)
        withContext(Dispatchers.Default) {
            val r = Recognizer(m, 16000f)
            try {
                var off = 0
                while (off < pcm.size) {
                    val n = minOf(8000, pcm.size - off)
                    r.acceptWaveForm(pcm.copyOfRange(off, off + n), n)
                    off += n
                }
                JSONObject(r.finalResult).optString("text")
            } finally {
                r.close()
            }
        }
    } catch (t: Throwable) {
        ""
    }
}
