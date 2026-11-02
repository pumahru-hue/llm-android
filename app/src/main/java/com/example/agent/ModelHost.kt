package com.example.agent

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Держит Engine и один живой Conversation. Диалог модели — это кэш поверх истории чата (ChatStore):
 * если состав контекста изменился (сжатие, очистка, события), диалог пересобирается из истории.
 */
class ModelHost(private val ctx: Context) {

    private val mutex = Mutex()
    @Volatile private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var liveIds: List<Long> = emptyList()

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

    private fun createConv(e: Engine, initial: List<Message>): Conversation {
        val mcp = McpManager.tools(ctx).map { tool(it) }
        val phone = when {
            !Settings.toolsOn(ctx) -> emptyList()
            Settings.toolsImpl(ctx) == "kotlin" -> listOf(tool(PhoneTools(ctx)))
            else -> ToolRegistry.builtin(ctx).map { tool(it) }
        }
        val toolList = phone + mcp
        val prompt = Contents.of(Settings.effectivePrompt(ctx))
        val sampler = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
        val cfg = if (initial.isEmpty()) {
            ConversationConfig(
                systemInstruction = prompt,
                tools = toolList,
                samplerConfig = sampler,
            )
        } else {
            ConversationConfig(
                systemInstruction = prompt,
                initialMessages = initial,
                tools = toolList,
                samplerConfig = sampler,
            )
        }
        return e.createConversation(cfg)
    }

    /** Диалог, уже содержащий ровно эти сообщения; иначе пересобираем из истории. */
    suspend fun conversationFor(ids: List<Long>, initial: () -> List<Message>): Conversation = mutex.withLock {
        val e = checkNotNull(engine) { "Модель не инициализирована" }
        val cur = conversation
        if (cur != null && liveIds == ids) return@withLock cur
        runCatching { cur?.close() }
        conversation = null
        val msgs = initial()
        val c = withContext(Dispatchers.Default) { createConv(e, msgs) }
        conversation = c
        liveIds = ids
        c
    }

    suspend fun setLive(ids: List<Long>) = mutex.withLock { liveIds = ids }

    suspend fun dropConversation() = mutex.withLock {
        runCatching { conversation?.close() }
        conversation = null
        liveIds = emptyList()
    }

    fun close() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null; engine = null
    }

    companion object { const val TAG = "ModelHost" }
}

object AgentRuntime {
    @Volatile private var host: ModelHost? = null

    fun host(ctx: Context): ModelHost =
        host ?: synchronized(this) { host ?: ModelHost(ctx.applicationContext).also { host = it } }

    /** Промпт, инструменты или настройки изменились: диалог модели надо пересобрать. */
    fun invalidate(ctx: Context) {
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.Default).launch { host(app).dropConversation() }
    }
}
