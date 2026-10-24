package com.example.agent

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Держит Engine и Conversation. Один экземпляр на процесс. */
class ModelHost(private val ctx: Context) {

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /** adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.example.agent/files/ */
    val modelFile: File get() = File(ctx.getExternalFilesDir(null), "gemma-4-E2B-it.litertlm")

    val isReady get() = conversation != null

    suspend fun init(): String = mutex.withLock {
        if (conversation != null) return@withLock "уже загружена"
        require(modelFile.exists()) { "Нет файла модели: ${modelFile.path}" }

        withContext(Dispatchers.Default) {
            // Сначала пробуем GPU, при ошибке откатываемся на CPU.
            val e = try {
                build(Backend.GPU()).also { it.initialize() }
            } catch (t: Throwable) {
                Log.w(TAG, "GPU не взлетел, CPU: $t")
                build(Backend.CPU()).also { it.initialize() }
            }
            engine = e
            conversation = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "Ты голосовой ассистент на телефоне. Отвечай по-русски, коротко и разговорно. " +
                        "Когда просят что-то сделать на телефоне (будильник, таймер, приложение, звонок, " +
                        "сообщение, фонарик, громкость, календарь, поиск), обязательно вызови подходящий " +
                        "инструмент, а потом одной фразой скажи, что сделано. " +
                        "Текущее время не угадывай, спроси у инструмента get_datetime."
                    ),
                    tools = listOf(tool(PhoneTools(ctx))),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                )
            )
        }
        "готово"
    }

    private fun build(main: Backend) = Engine(
        EngineConfig(
            modelPath = modelFile.path,
            backend = main,
            visionBackend = Backend.GPU(),
            audioBackend = Backend.CPU(),
            cacheDir = ctx.cacheDir.path,   // ускоряет повторную загрузку
        )
    )

    /** Голос -> текст ответа потоком. pcm: 16 kHz, mono, 16-bit (формат уточнить, см. README). */
    fun askWithAudio(pcm: ByteArray, hint: String = "Ответь на то, что сказал пользователь."): Flow<String> =
                stream(Contents.of(Content.AudioBytes(Wav.wrap(pcm)), Content.Text(hint)))

    fun askText(text: String): Flow<String> = stream(Contents.of(Content.Text(text)))

    private fun stream(c: Contents): Flow<String> {
        val conv = checkNotNull(conversation) { "Модель не инициализирована" }
        return conv.sendMessageAsync(c).map { it.toString() }
    }

    fun close() {
        conversation?.close(); engine?.close()
        conversation = null; engine = null
    }

    companion object { const val TAG = "ModelHost" }
}

/** Синглтон процесса: сервис и UI используют один и тот же движок. */
object AgentRuntime {
    @Volatile private var host: ModelHost? = null
    fun host(ctx: Context): ModelHost =
        host ?: synchronized(this) { host ?: ModelHost(ctx.applicationContext).also { host = it } }
}
