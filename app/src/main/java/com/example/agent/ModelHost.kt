package com.example.agent

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Держит Engine и Conversation. Один экземпляр на процесс. */
class ModelHost(private val ctx: Context) {

    private val mutex = Mutex()
    @Volatile private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var turns = 0
    private var lastUse = 0L
    private var dirty = false   // в диалоге картинки: контекст тяжёлый, следующий диалог новый

    val modelFile: File get() = File(ctx.getExternalFilesDir(null), "gemma-4-E2B-it.litertlm")

    val isReady get() = engine != null

    suspend fun init(): String = mutex.withLock {
        if (engine != null) return@withLock "уже загружена"
        require(modelFile.exists()) { "Нет файла модели: ${modelFile.path}" }

        withContext(Dispatchers.Default) {
            val e = try {
                build(Backend.GPU()).also { it.initialize() }
            } catch (t: Throwable) {
                Log.w(TAG, "GPU не взлетел, CPU: $t")
                build(Backend.CPU()).also { it.initialize() }
            }
            engine = e
            conversation = createConv(e, withTools = true)
            turns = 0
            dirty = false
            lastUse = SystemClock.elapsedRealtime()
        }
        "готово"
    }

    private fun build(main: Backend) = Engine(
        EngineConfig(
            modelPath = modelFile.path,
            backend = main,
            visionBackend = Backend.GPU(),
            audioBackend = Backend.CPU(),
            cacheDir = ctx.cacheDir.path,
        )
    )

    private fun createConv(e: Engine, withTools: Boolean): Conversation =
        e.createConversation(
            if (withTools) {
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
            } else {
                // Диалог для картинок и видео: без инструментов, чтобы модель не принимала вопрос за команду.
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "Ты помощник, который смотрит на переданные изображения или кадры видео и отвечает " +
                        "на вопрос пользователя. Ты видишь эти изображения. Отвечай по-русски, коротко и " +
                        "по существу. Никаких действий на телефоне ты не выполняешь."
                    ),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.4),
                )
            }
        )

    /** Текущий диалог или новый, если старый устарел, переполнен или нужен чистый (медиа). */
    private suspend fun obtainConversation(fresh: Boolean): Conversation = mutex.withLock {
        val e = checkNotNull(engine) { "Модель не инициализирована" }
        val now = SystemClock.elapsedRealtime()
        val stale = conversation != null &&
            (fresh || dirty || turns >= MAX_TURNS || now - lastUse > IDLE_MS)
        if (stale) {
            runCatching { conversation?.close() }
            conversation = null
            turns = 0
            dirty = false
        }
        val c = conversation
            ?: withContext(Dispatchers.Default) { createConv(e, withTools = !fresh) }.also { conversation = it }
        turns++
        lastUse = now
        dirty = fresh
        c
    }

    private suspend fun dropConversation() = mutex.withLock {
        runCatching { conversation?.close() }
        conversation = null
        turns = 0
        dirty = false
    }

    /** Кнопка «сбросить диалог»: модель забывает весь контекст. */
    suspend fun reset() { dropConversation() }

    /** Голос -> текст ответа потоком. */
    fun askWithAudio(
        pcm: ByteArray,
        hint: String = "Сейчас ${nowRu()}. Ответь на то, что сказал пользователь.",
    ): Flow<String> =
        stream(Contents.of(Content.AudioBytes(Wav.wrap(pcm)), Content.Text(hint)), fresh = false)

    /** Картинки/кадры + вопрос. Всегда в новом диалоге без инструментов. */
    fun askMedia(parts: List<Content>): Flow<String> =
        stream(Contents.of(*parts.toTypedArray()), fresh = true)

    fun askText(text: String): Flow<String> = stream(Contents.of(Content.Text(text)), fresh = false)

    private fun stream(c: Contents, fresh: Boolean): Flow<String> = flow {
        var attempt = 0
        while (true) {
            val conv = obtainConversation(fresh)
            var emitted = false
            try {
                conv.sendMessageAsync(c).collect { emitted = true; emit(it.toString()) }
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "ошибка генерации: $e")
                dropConversation()
                if (emitted || attempt++ >= 1) throw e
            }
        }
    }.flowOn(Dispatchers.Default)

    fun close() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null; engine = null
    }

    companion object {
        const val TAG = "ModelHost"
        const val MAX_TURNS = 3
        const val IDLE_MS = 2 * 60 * 1000L
    }
}

/** Синглтон процесса: сервисы и UI используют один и тот же движок. */
object AgentRuntime {
    @Volatile private var host: ModelHost? = null
    fun host(ctx: Context): ModelHost =
        host ?: synchronized(this) { host ?: ModelHost(ctx.applicationContext).also { host = it } }
}
