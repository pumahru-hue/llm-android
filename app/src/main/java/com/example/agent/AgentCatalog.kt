package com.example.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Каталог инструментов Android-агента (из assets/android_agent_tools.json, выгружен из tools.go моста). */
object AgentCatalog {
    class Entry(
        val name: String,
        val description: String,
        val method: String,
        val path: String,
        val schema: JSONObject,
        val risk: String,          // read | act | danger
        val defaultOn: Boolean,
        val media: String?,        // image | audio
    )

    @Volatile private var cache: Map<String, Entry>? = null

    fun all(ctx: Context): Map<String, Entry> =
        cache ?: synchronized(this) { cache ?: load(ctx).also { cache = it } }

    private fun load(ctx: Context): Map<String, Entry> {
        val text = ctx.applicationContext.assets.open("android_agent_tools.json")
            .bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        val out = LinkedHashMap<String, Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out[o.getString("name")] = Entry(
                o.getString("name"), o.optString("description"), o.getString("method"), o.getString("path"),
                o.optJSONObject("schema") ?: JSONObject().put("type", "object"),
                o.optString("risk", "danger"), o.optBoolean("defaultOn"),
                o.optString("media").ifBlank { null },
            )
        }
        return out
    }
}
