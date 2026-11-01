package com.example.agent

import android.content.Context
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** «Суббота, 24 октября 2026 года, 19:23» */
fun nowRu(): String {
    val n = ZonedDateTime.now()
    val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
        "августа", "сентября", "октября", "ноября", "декабря")
    val wd = n.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))
    return "$wd, ${n.dayOfMonth} ${months[n.monthValue - 1]} ${n.year} года, " +
        "%02d:%02d".format(n.hour, n.minute)
}

/** Сообщение чата. role: user | assistant | event | tool | note. kind: text | voice | photo | video. */
class ChatMsg(
    val id: Long,
    val role: String,
    var text: String,
    val kind: String = "text",
    val images: List<String> = emptyList(),
    val ts: Long = System.currentTimeMillis(),
    var inCtx: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("role", role).put("text", text).put("kind", kind)
        .put("images", JSONArray(images)).put("ts", ts).put("inCtx", inCtx)

    companion object {
        fun fromJson(o: JSONObject): ChatMsg {
            val arr = o.optJSONArray("images")
            val imgs = if (arr == null) emptyList() else List(arr.length()) { arr.getString(it) }
            return ChatMsg(o.getLong("id"), o.getString("role"), o.optString("text"),
                o.optString("kind", "text"), imgs, o.optLong("ts"), o.optBoolean("inCtx", true))
        }
    }
}

/** Хранилище чата: список в памяти + файл chat.jsonl + картинки в filesDir/chat. */
object ChatStore {
    class Event(val kind: Int, val index: Int)   // 0 добавлено, 1 изменено, 2 перерисовать всё

    val events = MutableSharedFlow<Event>(extraBufferCapacity = 256)

    private val items = ArrayList<ChatMsg>()
    private var nextId = 1L
    private var dir: File? = null
    private var file: File? = null
    private var loaded = false

    fun init(ctx: Context) {
        synchronized(this) {
            if (loaded) return
            dir = File(ctx.filesDir, "chat").apply { mkdirs() }
            file = File(ctx.filesDir, "chat.jsonl")
            file?.takeIf { it.exists() }?.readLines()?.forEach { l ->
                runCatching { items += ChatMsg.fromJson(JSONObject(l)) }
            }
            nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
            loaded = true
        }
    }

    fun snapshot(): List<ChatMsg> = synchronized(this) { ArrayList(items) }
    fun newId(): Long = synchronized(this) { nextId++ }
    fun last(): ChatMsg? = synchronized(this) { items.lastOrNull() }

    fun add(m: ChatMsg) {
        val i = synchronized(this) { items += m; items.size - 1 }
        save()
        events.tryEmit(Event(0, i))
    }

    fun changed(m: ChatMsg) {
        val i = synchronized(this) { items.indexOf(m) }
        if (i >= 0) events.tryEmit(Event(1, i))
    }

    fun clear() {
        synchronized(this) { items.clear() }
        dir?.listFiles()?.forEach { it.delete() }
        save()
        events.tryEmit(Event(2, 0))
    }

    fun resetUi() { events.tryEmit(Event(2, 0)) }

    fun saveImage(bytes: ByteArray): String {
        val name = "img_${System.nanoTime()}.jpg"
        File(dir, name).writeBytes(bytes)
        return name
    }

    fun imageFile(name: String) = File(dir, name)

    fun save() {
        synchronized(this) {
            runCatching { file?.writeText(items.joinToString("\n") { it.toJson().toString() }) }
        }
    }
}

/** Грубая оценка токенов: точное число зависит от токенизатора и сборки модели. */
object Tokens {
    private val CTX_ROLES = setOf("user", "assistant", "event")

    fun overhead(ctx: Context) = Settings.prompt(ctx).length / 3 + 900   // промпт + схемы инструментов

    fun of(ctx: Context, m: ChatMsg): Int {
        if (m.role !in CTX_ROLES) return 0
        return m.text.length / 3 + 6 + m.images.size * Settings.tokImage(ctx)
    }

    fun usage(ctx: Context): Int =
        overhead(ctx) + ChatStore.snapshot().filter { it.inCtx }.sumOf { of(ctx, it) }
}

/** Стратегии сжатия контекста: «start» убирает самое старое, «middle» держит первые K и последние. */
object Trimmer {
    private val CTX_ROLES = setOf("user", "assistant", "event")

    fun trim(ctx: Context, user: ChatMsg, extra: Int, factor: Double): Int {
        val budget = (Settings.ctxBudget(ctx) * factor).toInt()
        val live = ChatStore.snapshot().filter { it !== user && it.inCtx && it.role in CTX_ROLES }
        var total = Tokens.overhead(ctx) + extra + Tokens.of(ctx, user) + live.sumOf { Tokens.of(ctx, it) }
        if (total <= budget) return 0

        val keep = if (Settings.ctxStrategy(ctx) == "middle") Settings.keepFirst(ctx) else 0
        val protectLast = if (factor < 1.0) 0 else 2
        val removable = live.drop(keep).dropLast(protectLast)
        var removed = 0
        for (m in removable) {
            if (total <= budget) break
            m.inCtx = false
            total -= Tokens.of(ctx, m)
            removed++
        }
        if (removed > 0) {
            val where = if (keep > 0) "середина" else "начало"
            Chat.note("🧹 Контекст сжат: убрано сообщений — $removed ($where)")
        }
        return removed
    }
}

object Chat {
    private val genMutex = Mutex()
    val busy = MutableStateFlow(false)
    val usage = MutableStateFlow(0)

    fun note(text: String) {
        ChatStore.add(ChatMsg(ChatStore.newId(), "note", text, inCtx = false))
    }

    fun voiceParts(
        pcm: ByteArray,
        hint: String = "Сейчас ${nowRu()}. Ответь на то, что сказал пользователь.",
        media: List<Content> = emptyList(),
    ): List<Content> = listOf<Content>(Content.AudioBytes(Wav.wrap(pcm))) + media + Content.Text(hint)

    /**
     * Единый чат: сообщение пользователя попадает в историю, контекст при необходимости сжимается,
     * диалог модели пересобирается из истории, ответ стримится и тоже пишется в историю.
     * live — то, что реально уйдёт в модель (например, звук), а user — то, что видит чат (расшифровка).
     */
    fun ask(ctx: Context, user: ChatMsg, liveParts: List<Content>, extraTokens: Int = 0): Flow<String> = flow {
        val app = ctx.applicationContext
        val host = AgentRuntime.host(app)
        val pin = pinnedParts(app, user)
        val live = Contents.of(*(pin + liveParts).toTypedArray())
        val extra = extraTokens + pin.count { it is Content.ImageBytes } * Settings.tokImage(app)
        ChatStore.add(user)
        genMutex.withLock {
            busy.value = true
            val produced = ArrayList<ChatMsg>()
            try {
                var attempt = 0
                var factor = 1.0
                while (true) {
                    val removed = Trimmer.trim(app, user, extra, factor)
                    if (removed > 0) ChatStore.resetUi()

                    val hist = ChatStore.snapshot().filter {
                        it !== user && it.inCtx && (it.role == "user" || it.role == "assistant" || it.role == "event")
                    }
                    val ids = hist.map { it.id }
                    val conv = host.conversationFor(ids) { buildInitial(hist) }

                    var cur: ChatMsg? = null
                    var emitted = false
                    try {
                        conv.sendMessageAsync(live).collect { m ->
                            val chunk = m.toString()
                            if (chunk.isEmpty()) return@collect
                            emitted = true
                            val c = cur?.takeIf { ChatStore.last() === it }
                                ?: ChatMsg(ChatStore.newId(), "assistant", "").also {
                                    produced += it
                                    ChatStore.add(it)
                                }
                            cur = c
                            c.text += chunk
                            ChatStore.changed(c)
                            emit(chunk)
                        }
                        host.setLive(ids + user.id + produced.map { it.id })
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        host.dropConversation()
                        if (emitted || attempt++ >= 1) {
                            note("⚠ Ошибка модели: ${e.message}")
                            throw e
                        }
                        factor *= 0.5
                    }
                }
            } finally {
                ChatStore.save()
                busy.value = false
                usage.value = Tokens.usage(app)
            }
        }
    }

    /** Последняя картинка/видео из контекста: прикладываем заново к следующим репликам. */
    private fun pinnedParts(ctx: Context, user: ChatMsg): List<Content> {
        val turns = Settings.pinTurns(ctx)
        if (turns <= 0 || user.images.isNotEmpty()) return emptyList()
        val msgs = ChatStore.snapshot().filter {
            it !== user && it.inCtx && (it.role == "user" || it.role == "assistant" || it.role == "event")
        }
        val idx = msgs.indexOfLast { it.role == "user" && it.images.isNotEmpty() }
        if (idx < 0) return emptyList()
        if (msgs.drop(idx + 1).count { it.role == "user" } >= turns) return emptyList()
        val m = msgs[idx]
        val out = ArrayList<Content>()
        out += Content.Text("(Напоминание: это ${if (m.kind == "video") "кадры видео" else "изображение"} " +
            "из предыдущего сообщения, вопрос может относиться к нему.)")
        for (img in m.images) {
            val f = ChatStore.imageFile(img)
            if (f.exists()) out += Content.ImageBytes(f.readBytes())
        }
        return out
    }

    /** История -> сообщения для модели. Роли чередуются user/model, хвост всегда model. */
    private fun buildInitial(hist: List<ChatMsg>): List<Message> {
        val out = ArrayList<Message>()
        val up = ArrayList<Content>()
        val mt = StringBuilder()
        var mode = 0   // 0 пока нет, 1 набираем user, 2 набираем model

        fun closeUser() {
            if (up.isNotEmpty()) out += Message.user(Contents.of(*up.toTypedArray()))
            up.clear()
        }
        fun closeModel() {
            if (mt.isNotBlank()) out += Message.model(mt.toString().trim())
            mt.setLength(0)
        }

        for (m in hist) {
            if (m.role == "assistant") {
                if (mode == 1) { closeUser(); mode = 2 }
                if (mode == 2) {
                    if (mt.isNotEmpty()) mt.append("\n")
                    mt.append(m.text)
                }
            } else {
                if (mode == 2) { closeModel(); mode = 1 }
                if (mode == 0) mode = 1
                if (m.kind == "video") up += Content.Text("Кадры видео по порядку:")
                for (img in m.images) {
                    val f = ChatStore.imageFile(img)
                    if (f.exists()) up += Content.ImageBytes(f.readBytes())
                }
                val prefix = when {
                    m.role == "event" -> "[Событие] "
                    m.kind == "voice" -> "[Голос] "
                    else -> ""
                }
                val t = prefix + m.text
                if (t.isNotBlank()) up += Content.Text(t)
            }
        }
        if (mode == 2) closeModel()   // незавершённую реплику пользователя в конце пропускаем
        return out
    }
}
