package com.example.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Минимальный клиент MCP по Streamable HTTP (редакции 2025-03-26 … 2025-11-25):
 * initialize -> notifications/initialized -> tools/list, tools/call.
 */
class McpClient(val name: String, private val url: String, private val headers: Map<String, String>) {

    class ToolInfo(
        val name: String,
        val description: String,
        val schema: JSONObject,
        val readOnly: Boolean?,
    )

    private var session: String? = null
    private var version = PROTOCOL
    private var inited = false
    private val ids = AtomicInteger(1)

    @Synchronized
    fun ensureInit() {
        if (inited) return
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "AgentPhone").put("version", "0.3"))
        val res = rpc("initialize", params, notification = false, allowReinit = false)
        res?.optString("protocolVersion")?.takeIf { it.isNotBlank() }?.let { version = it }
        inited = true
        rpc("notifications/initialized", null, notification = true, allowReinit = false)
    }

    @Synchronized
    fun listTools(): List<ToolInfo> {
        ensureInit()
        val out = ArrayList<ToolInfo>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = JSONObject()
            if (cursor != null) params.put("cursor", cursor)
            val res = rpc("tools/list", params, notification = false, allowReinit = true)
                ?: throw IOException("пустой ответ tools/list")
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
    fun callTool(tool: String, args: JSONObject): String {
        ensureInit()
        val params = JSONObject().put("name", tool).put("arguments", args)
        val res = rpc("tools/call", params, notification = false, allowReinit = true)
            ?: throw IOException("пустой ответ tools/call")
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

    private fun reset() {
        session = null
        inited = false
        version = PROTOCOL
    }

    private fun rpc(method: String, params: JSONObject?, notification: Boolean, allowReinit: Boolean): JSONObject? {
        val id = if (notification) null else ids.getAndIncrement()
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (params != null) body.put("params", params)
        if (id != null) body.put("id", id)

        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = TIMEOUT
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json, text/event-stream")
            if (method != "initialize") c.setRequestProperty("MCP-Protocol-Version", version)
            session?.let { c.setRequestProperty("Mcp-Session-Id", it) }
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            if (method == "initialize") c.getHeaderField("Mcp-Session-Id")?.let { session = it }

            if (code == 404 && session != null && allowReinit) {
                // сессия истекла: начинаем заново и повторяем запрос один раз
                reset()
                ensureInit()
                return rpc(method, params, notification, allowReinit = false)
            }
            if (code == 202 || (notification && code in 200..299)) return null
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.readText()?.take(300).orEmpty()
                val hint = if (code == 401 || code == 403)
                    " (нужна авторизация: добавьте заголовок Authorization в headers)" else ""
                throw IOException("HTTP $code$hint $err".trim())
            }

            val ctype = c.contentType.orEmpty()
            val text = if (ctype.contains("text/event-stream")) readSse(c.inputStream, id)
            else c.inputStream.bufferedReader().readText()

            val msg = findResponse(text, id) ?: throw IOException("нет ответа на $method")
            msg.optJSONObject("error")?.let {
                throw IOException("MCP: ${it.optString("message", it.toString())}")
            }
            return msg.optJSONObject("result")
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

    companion object {
        const val PROTOCOL = "2025-06-18"
        const val TIMEOUT = 25_000
    }
}
