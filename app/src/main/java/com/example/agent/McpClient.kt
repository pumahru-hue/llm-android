package com.example.agent

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Клиент MCP. Сам определяет, на каком варианте протокола говорит сервер:
 *  1) MODERN   — редакция 2026-07-28: без initialize и сессий, метаданные в каждом запросе;
 *  2) STATEFUL — редакции 2025-03-26 … 2025-11-25: initialize, заголовок Mcp-Session-Id;
 *  3) SSE      — устаревший HTTP+SSE (2024-11-05): GET-поток «endpoint» + POST-сообщения.
 */
class McpClient(
    val name: String,
    private val url: String,
    private val headers: Map<String, String>,
    private val appCtx: Context,
) {

    class ToolInfo(
        val name: String,
        val description: String,
        val schema: JSONObject,
        val readOnly: Boolean?,
        val risk: String? = null,
        val defaultOn: Boolean? = null,
        val media: String? = null,
    )

    private enum class Mode { NONE, MODERN, STATEFUL, SSE, AGENT }
    private class Resp(val code: Int, val text: String, val session: String?)

    private var mode = Mode.NONE
    private var version = "2025-06-18"
    private var session: String? = null
    private var link: SseLink? = null
    private val ids = AtomicInteger(1)
    private val diag = StringBuilder()

    /** Какой вариант выбран (показывается в настройках). */
    val modeName: String
        get() = when (mode) {
            Mode.MODERN -> "протокол $MODERN"
            Mode.STATEFUL -> "протокол $version"
            Mode.SSE -> "протокол 2024-11-05 (HTTP+SSE)"
            Mode.AGENT -> "Android-агент (REST)"
            Mode.NONE -> "?"
        }

    // ---------- публичное ----------

    @Synchronized
    fun listTools(): List<ToolInfo> {
        ensure()
        if (mode == Mode.AGENT) return agentTools()
        val out = ArrayList<ToolInfo>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = JSONObject()
            if (cursor != null) params.put("cursor", cursor)
            val res = call("tools/list", params)
            val arr = res.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val ann = t.optJSONObject("annotations")
                val ro = if (ann != null && ann.has("readOnlyHint")) ann.optBoolean("readOnlyHint") else null
                out += ToolInfo(
                    t.getString("name"),
                    t.optString("description", t.optString("title", "")),
                    t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"),
                    ro,
                )
            }
            cursor = res.optString("nextCursor", "").ifBlank { null }
            pages++
        } while (cursor != null && pages < 10)
        return out
    }

    @Synchronized
    fun callTool(tool: String, args: JSONObject, schema: JSONObject?): String {
        ensure()
        if (mode == Mode.AGENT) return agentCall(tool, args)
        val params = JSONObject().put("name", tool).put("arguments", args)
        val res = call("tools/call", params, tool, schema, args)
        val sb = StringBuilder()
        val content = res.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                when (c.optString("type")) {
                    "text" -> sb.append(c.optString("text")).append('\n')
                    "image" -> sb.append("[изображение]\n")
                    "resource" -> sb.append(c.optJSONObject("resource")?.optString("text", "[ресурс]") ?: "[ресурс]").append('\n')
                    else -> sb.append("[").append(c.optString("type")).append("]\n")
                }
            }
        }
        if (sb.isBlank()) res.optJSONObject("structuredContent")?.let { sb.append(it.toString()) }
        val text = sb.toString().trim()
        return if (res.optBoolean("isError")) "Ошибка инструмента: $text" else text
    }

    @Synchronized
    fun close() {
        link?.close()
        link = null
        mode = Mode.NONE
        session = null
    }

    // ---------- выбор варианта протокола ----------

    private fun ensure() {
        if (mode != Mode.NONE) return
        diag.setLength(0)
        detect()
    }

    private fun authError(code: Int) =
        IOException("HTTP $code: нужна авторизация (добавьте заголовок Authorization в headers)")

    private fun info() = JSONObject().put("name", "AgentPhone").put("version", "0.3")

    private fun detect() {
        // 0) Android-агент (REST, «инструмент = маршрут»), он же отвечает на /ping
        if (agentProbe()) { mode = Mode.AGENT; return }

        // 1) современный вариант без сессий
        val id = ids.getAndIncrement()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "tools/list")
            .put("params", withMeta(JSONObject()))
        val r = http(body, mapOf("MCP-Protocol-Version" to MODERN, "Mcp-Method" to "tools/list"), id)
        diag.append("современный: HTTP ${r.code} ${r.text.take(120).replace('\n', ' ')}; ")
        if (r.code == 401 || r.code == 403) throw authError(r.code)
        val msg = findResponse(r.text, id)
        if (r.code in 200..299 && msg != null && msg.has("result")) { mode = Mode.MODERN; return }

        val err = msg?.optJSONObject("error")
        val supported = err?.optJSONObject("data")?.optJSONArray("supported")
        if (r.code == 400 && err != null && (supported != null || err.optInt("code") == -32020)) {
            val list = (0 until (supported?.length() ?: 0)).map { supported!!.optString(it) }
            val old = list.firstOrNull { it in LEGACY }
            if (old != null && tryStateful(old)) { mode = Mode.STATEFUL; return }
            throw IOException("сервер поддерживает версии протокола: ${list.joinToString().ifEmpty { "неизвестно" }}; " +
                "клиент знает $MODERN и ${LEGACY.joinToString()}. ${err.optString("message")}")
        }

        // 2) версии с initialize и сессией
        if (tryStateful("2025-06-18")) { mode = Mode.STATEFUL; return }

        // 3) устаревший HTTP+SSE
        trySse()
        mode = Mode.SSE
    }

    private fun tryStateful(first: String): Boolean {
        var v = first
        for (attempt in 0..1) {
            val id = ids.getAndIncrement()
            val params = JSONObject().put("protocolVersion", v)
                .put("capabilities", JSONObject()).put("clientInfo", info())
            val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "initialize").put("params", params)
            val r = http(body, emptyMap(), id)
            diag.append("initialize($v): HTTP ${r.code} ${r.text.take(120).replace('\n', ' ')}; ")
            if (r.code == 401 || r.code == 403) throw authError(r.code)
            val msg = findResponse(r.text, id)
            val res = msg?.optJSONObject("result")
            if (r.code in 200..299 && res != null) {
                version = res.optString("protocolVersion", v).ifBlank { v }
                session = r.session
                http(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"),
                    statefulHeaders(), null)
                return true
            }
            val err = msg?.optJSONObject("error")
            val supported = err?.optJSONObject("data")?.optJSONArray("supported")
            if (attempt == 0 && supported != null) {
                val pick = (0 until supported.length()).map { supported.optString(it) }.firstOrNull { it in LEGACY }
                if (pick != null && pick != v) { v = pick; continue }
            }
            return false
        }
        return false
    }

    private fun trySse() {
        val l = SseLink()
        l.open()
        try {
            l.ready.get(15, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            l.close()
            val why = e.cause?.message ?: e.message
            throw IOException("не удалось определить протокол MCP. $diag GET: $why")
        }
        val res = l.request("initialize", JSONObject().put("protocolVersion", "2024-11-05")
            .put("capabilities", JSONObject()).put("clientInfo", info()), false)
        if (res == null) { l.close(); throw IOException("HTTP+SSE: нет ответа на initialize") }
        l.request("notifications/initialized", null, true)
        link = l
    }

    // ---------- вызовы ----------

    private fun call(
        method: String,
        params: JSONObject,
        toolName: String? = null,
        schema: JSONObject? = null,
        args: JSONObject? = null,
    ): JSONObject = when (mode) {
        Mode.MODERN -> modernCall(method, params, toolName, schema, args)
        Mode.STATEFUL -> statefulCall(method, params, true)
        Mode.SSE -> link?.request(method, params, false) ?: throw IOException("поток SSE не открыт")
        Mode.AGENT -> throw IOException("режим агента: вызов идёт напрямую")
        Mode.NONE -> throw IOException("соединение не установлено")
    }

    private fun modernCall(method: String, params: JSONObject, toolName: String?, schema: JSONObject?, args: JSONObject?): JSONObject {
        val id = ids.getAndIncrement()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
            .put("params", withMeta(params))
        val h = LinkedHashMap<String, String>()
        h["MCP-Protocol-Version"] = MODERN
        h["Mcp-Method"] = method
        if (toolName != null) h["Mcp-Name"] = hv(toolName)
        if (schema != null && args != null) h.putAll(paramHeaders(schema, args))
        return result(http(body, h, id), id)
    }

    private fun statefulHeaders(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["MCP-Protocol-Version"] = version
        session?.let { h["Mcp-Session-Id"] = it }
        return h
    }

    private fun statefulCall(method: String, params: JSONObject, retry: Boolean): JSONObject {
        val id = ids.getAndIncrement()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val r = http(body, statefulHeaders(), id)
        if (r.code == 404 && session != null && retry) {      // сессия истекла
            session = null
            if (!tryStateful(version)) throw IOException("сессия истекла, переподключиться не удалось")
            return statefulCall(method, params, false)
        }
        return result(r, id)
    }

    private fun result(r: Resp, id: Int): JSONObject {
        if (r.code == 401 || r.code == 403) throw authError(r.code)
        val msg = findResponse(r.text, id)
        if (msg != null) {
            msg.optJSONObject("error")?.let { throw IOException("MCP: ${it.optString("message", it.toString())}") }
            val res = msg.optJSONObject("result") ?: JSONObject()
            if (res.has("inputRequests")) throw IOException("инструмент запросил ввод от клиента (MRTR), это не поддерживается")
            return res
        }
        throw IOException("HTTP ${r.code} ${r.text.take(200)}".trim())
    }

    // ---------- вспомогательное ----------

    private fun withMeta(p: JSONObject): JSONObject {
        val meta = p.optJSONObject("_meta") ?: JSONObject()
        meta.put("io.modelcontextprotocol/protocolVersion", MODERN)
        meta.put("io.modelcontextprotocol/clientInfo", info())
        meta.put("io.modelcontextprotocol/clientCapabilities", JSONObject())
        p.put("_meta", meta)
        return p
    }

    /** Значение заголовка: обычный ASCII как есть, иначе base64 по правилам спецификации. */
    private fun hv(v: String): String {
        val plain = v.all { it.code in 0x20..0x7E } && v == v.trim() &&
            !(v.startsWith("=?base64?") && v.endsWith("?="))
        return if (plain) v else "=?base64?" + Base64.encodeToString(v.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="
    }

    /** Параметры инструмента, помеченные x-mcp-header, дублируются в заголовки Mcp-Param-*. */
    private fun paramHeaders(schema: JSONObject, args: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(s: JSONObject, a: JSONObject?) {
            val props = s.optJSONObject("properties") ?: return
            for (k in props.keys()) {
                val p = props.optJSONObject(k) ?: continue
                val v = a?.opt(k)
                val h = p.optString("x-mcp-header", "")
                if (h.isNotEmpty() && v != null && v != JSONObject.NULL) {
                    when (v) {
                        is String -> out["Mcp-Param-$h"] = hv(v)
                        is Int, is Long -> out["Mcp-Param-$h"] = v.toString()
                        is Boolean -> out["Mcp-Param-$h"] = v.toString()
                    }
                }
                if (p.optString("type") == "object") walk(p, v as? JSONObject)
            }
        }
        walk(schema, args)
        return out
    }

    private fun http(body: JSONObject, extra: Map<String, String>, id: Int?, target: String = url): Resp {
        val c = URL(target).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = TIMEOUT
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json, text/event-stream")
            extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            val sess = c.getHeaderField("Mcp-Session-Id")
            val stream: InputStream? = if (code in 200..299) c.inputStream else c.errorStream
            val ctype = c.contentType.orEmpty()
            val text = when {
                stream == null -> ""
                ctype.contains("text/event-stream") -> readSse(stream, id)
                else -> stream.bufferedReader().readText()
            }
            return Resp(code, text, sess)
        } finally {
            c.disconnect()
        }
    }

    private fun readSse(stream: InputStream, id: Int?): String {
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            val data = StringBuilder()
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) {
                    if (data.isNotEmpty()) {
                        val s = data.toString()
                        data.setLength(0)
                        if (findResponse(s, id) != null) return s
                    }
                } else if (line.startsWith("data:")) {
                    data.append(line.removePrefix("data:").trim())
                }
            }
            if (data.isNotEmpty()) return data.toString()
        }
        return ""
    }

    /** Ищет ответ с нужным id в одном объекте или в пакете (массиве). */
    private fun findResponse(text: String, id: Int?): JSONObject? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return try {
            if (t.startsWith("[")) {
                val arr = JSONArray(t)
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { id == null || it.optInt("id", -1) == id }
            } else {
                val o = JSONObject(t)
                if ((o.has("result") || o.has("error")) && (id == null || o.optInt("id", -1) == id)) o else null
            }
        } catch (_: Throwable) {
            null
        }
    }


    // ---------- Android-агент (REST: «инструмент = маршрут») ----------

    private fun agentProbe(): Boolean {
        if (!url.startsWith("http://")) return false
        return try {
            val (code, text) = rawHttp("GET", "/ping", null)
            diag.append("агент /ping: HTTP $code ${text.take(80).replace('\n', ' ')}; ")
            code == 200 && JSONObject(text).optBoolean("ok", false)
        } catch (e: Throwable) {
            diag.append("агент /ping: ${e.javaClass.simpleName}; ")
            false
        }
    }

    private fun agentTools(): List<ToolInfo> {
        // токен проверяем защищённым запросом: /ping открыт для всех
        val (code, _) = rawHttp("GET", "/status", null)
        if (code == 401 || code == 403) throw IOException("HTTP $code: неверный токен агента (X-Auth-Token в headers)")
        return AgentCatalog.all(appCtx).values.map {
            ToolInfo(it.name, it.description, it.schema, it.risk == "read", it.risk, it.defaultOn, it.media)
        }
    }

    private fun agentCall(tool: String, args: JSONObject): String {
        val e = AgentCatalog.all(appCtx)[tool] ?: throw IOException("неизвестный инструмент агента: $tool")
        if (tool == "android_ui_dump") {            // экономим контекст модели
            if (!args.has("only_clickable")) args.put("only_clickable", true)
            if (!args.has("limit")) args.put("limit", 40)
        }
        val body = if (args.length() > 0) args.toString() else null
        val (code, text) = rawHttp(e.method, e.path, body)
        if (code >= 400) return "Ошибка агента (HTTP $code): ${text.take(500)}"
        return if (tool == "android_ui_dump") compactDump(text) else text
    }

    /** Дамп экрана -> короткий список «текст + координаты», чтобы он влез в окно маленькой модели. */
    private fun compactDump(text: String): String {
        return try {
            val root = JSONObject(text)
            var arr: JSONArray? = null
            for (k in root.keys()) {
                val v = root.opt(k)
                if (v is JSONArray) { arr = v; break }
            }
            if (arr == null) return text
            val sb = StringBuilder("Элементы экрана (текст и координаты центра cx,cy):\n")
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = listOf("text", "desc", "description", "content_desc", "contentDescription",
                    "hint", "label", "id").map { o.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: ""
                val hasXY = o.has("cx") && o.has("cy")
                if (label.isBlank() && !hasXY) continue
                n++
                sb.append(n).append(". ")
                if (label.isNotBlank()) sb.append('«').append(label.take(60)).append("» ")
                if (hasXY) sb.append('(').append(o.optInt("cx")).append(',').append(o.optInt("cy")).append(')')
                if (o.optBoolean("clickable", false)) sb.append(" [нажимаемый]")
                if (o.optBoolean("editable", false)) sb.append(" [поле ввода]")
                sb.append('\n')
                if (sb.length > 2800) { sb.append("…\n"); break }
            }
            if (n == 0) text else sb.toString()
        } catch (_: Throwable) {
            text
        }
    }

    /** Свой HTTP/1.1 поверх сокета: агенту нужно тело и у GET-запросов, а HttpURLConnection так не умеет. */
    private fun rawHttp(method: String, pathIn: String, body: String?): Pair<Int, String> {
        val u = URL(url)
        val port = if (u.port > 0) u.port else 80
        val path = u.path.trimEnd('/') + pathIn
        return Socket().use { s ->
            s.connect(InetSocketAddress(u.host, port), 8000)
            s.soTimeout = 30_000
            val b = body?.toByteArray(Charsets.UTF_8)
            val req = StringBuilder()
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            req.append("Host: ").append(u.host).append(':').append(port).append("\r\n")
            req.append("Connection: close\r\nAccept: application/json\r\n")
            if (b != null) req.append("Content-Type: application/json\r\nContent-Length: ").append(b.size).append("\r\n")
            headers.forEach { (k, v) -> req.append(k).append(": ").append(v).append("\r\n") }
            req.append("\r\n")
            val out = s.getOutputStream()
            out.write(req.toString().toByteArray(Charsets.UTF_8))
            if (b != null) out.write(b)
            out.flush()
            readHttp(BufferedInputStream(s.getInputStream()))
        }
    }

    private fun readHttp(inp: BufferedInputStream): Pair<Int, String> {
        fun line(): String? {
            val bo = ByteArrayOutputStream()
            while (true) {
                val c = inp.read()
                if (c < 0) return if (bo.size() == 0) null else bo.toString("UTF-8")
                if (c == '\n'.code) break
                bo.write(c)
            }
            return bo.toString("UTF-8").trimEnd('\r')
        }
        val status = line() ?: throw IOException("пустой ответ агента")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("плохой ответ агента: $status")
        val h = HashMap<String, String>()
        while (true) {
            val l = line() ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val bytes = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        val chunked = h["transfer-encoding"].orEmpty().lowercase().contains("chunked")
        val cl = h["content-length"]?.toIntOrNull()
        if (chunked) {
            while (true) {
                val sz = (line() ?: break).substringBefore(';').trim().toIntOrNull(16) ?: break
                if (sz == 0) break
                var left = sz
                while (left > 0) {
                    val n = inp.read(buf, 0, minOf(buf.size, left))
                    if (n < 0) break
                    bytes.write(buf, 0, n)
                    left -= n
                }
                line()
            }
        } else if (cl != null) {
            var left = cl
            while (left > 0) {
                val n = inp.read(buf, 0, minOf(buf.size, left))
                if (n < 0) break
                bytes.write(buf, 0, n)
                left -= n
            }
        } else {
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                bytes.write(buf, 0, n)
            }
        }
        return code to bytes.toString("UTF-8")
    }

    // ---------- устаревший HTTP+SSE ----------

    private inner class SseLink {
        private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
        val ready = CompletableFuture<String>()
        @Volatile private var alive = true
        @Volatile private var conn: HttpURLConnection? = null

        fun open() {
            val t = Thread {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    conn = c
                    c.connectTimeout = 15_000
                    c.readTimeout = 0
                    c.setRequestProperty("Accept", "text/event-stream")
                    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                    val code = c.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                        var event = "message"
                        val data = StringBuilder()
                        while (alive) {
                            val line = r.readLine() ?: break
                            when {
                                line.isEmpty() -> {
                                    if (data.isNotEmpty()) dispatch(event, data.toString())
                                    event = "message"
                                    data.setLength(0)
                                }
                                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> {
                                    if (data.isNotEmpty()) data.append('\n')
                                    data.append(line.removePrefix("data:").trim())
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    if (!ready.isDone) ready.completeExceptionally(e)
                } finally {
                    alive = false
                    if (!ready.isDone) ready.completeExceptionally(IOException("поток закрыт без endpoint"))
                    pending.values.forEach { it.completeExceptionally(IOException("поток SSE закрыт")) }
                }
            }
            t.isDaemon = true
            t.start()
        }

        private fun dispatch(event: String, data: String) {
            if (event == "endpoint") {
                if (!ready.isDone) ready.complete(URL(URL(url), data.trim()).toString())
            } else {
                val o = try { JSONObject(data) } catch (_: Throwable) { return }
                if (o.has("id") && (o.has("result") || o.has("error"))) pending.remove(o.optInt("id"))?.complete(o)
            }
        }

        fun request(method: String, params: JSONObject?, notification: Boolean): JSONObject? {
            val ep = ready.getNow(null) ?: throw IOException("нет endpoint")
            if (!alive) throw IOException("поток SSE закрыт")
            val id = if (notification) null else ids.getAndIncrement()
            val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
            if (params != null) body.put("params", params)
            if (id != null) body.put("id", id)
            val fut = if (id != null) CompletableFuture<JSONObject>().also { pending[id] = it } else null

            val r = http(body, emptyMap(), null, ep)
            if (r.code !in 200..299) {
                if (id != null) pending.remove(id)
                throw IOException("HTTP ${r.code} ${r.text.take(200)}".trim())
            }
            if (fut == null) return null
            val msg = try {
                fut.get(TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                pending.remove(id)
                throw IOException("нет ответа по SSE: ${e.cause?.message ?: e.message}")
            }
            msg.optJSONObject("error")?.let { throw IOException("MCP: ${it.optString("message", it.toString())}") }
            return msg.optJSONObject("result") ?: JSONObject()
        }

        fun close() {
            alive = false
            runCatching { conn?.disconnect() }
        }
    }

    companion object {
        const val MODERN = "2026-07-28"
        val LEGACY = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        const val TIMEOUT = 25_000
    }
}
