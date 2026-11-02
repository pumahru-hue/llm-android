package com.example.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.example.agent.Tools.P
import com.google.ai.edge.litertlm.OpenApiTool
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId

/** Инструмент для модели: описание — JSON-схема, вызов — JSON-строка на входе и на выходе. */
class AgentTool(
    val name: String,
    val description: String,
    val params: JSONObject,
    val source: String,
    private val showResult: Boolean = false,
    private val handler: (JSONObject) -> String,
) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String =
        JSONObject().put("name", name).put("description", description).put("parameters", params).toString()

    override fun execute(paramsJsonString: String): String {
        val args = try { JSONObject(paramsJsonString.ifBlank { "{}" }) } catch (_: Throwable) { JSONObject() }
        note("🔧 $name ${args.toString().take(140)}")
        val out = try { handler(args) } catch (t: Throwable) { Tools.err(t.message ?: t.toString()) }
        if (showResult) note("↳ ${out.take(200)}")
        return out
    }

    private fun note(t: String) {
        runCatching { ChatStore.add(ChatMsg(ChatStore.newId(), "tool", t, inCtx = false)) }
    }
}

object Tools {
    fun ok(vararg kv: Pair<String, Any?>): String {
        val o = JSONObject().put("status", "ok")
        kv.forEach { o.put(it.first, it.second) }
        return o.toString()
    }

    fun err(msg: String): String = JSONObject().put("status", "error").put("message", msg).toString()

    class P(val name: String, val type: String, val desc: String, val required: Boolean = true)

    fun schema(vararg ps: P): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        for (p in ps) {
            props.put(p.name, JSONObject().put("type", p.type).put("description", p.desc))
            if (p.required) req.put(p.name)
        }
        return JSONObject().put("type", "object").put("properties", props).put("required", req)
    }
}

/** Встроенные инструменты телефона (те же, что были в PhoneTools, но через OpenApiTool). */
object ToolRegistry {

    fun builtin(ctx: Context): List<AgentTool> {
        fun launch(i: Intent): String = try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Tools.ok()
        } catch (e: Exception) {
            Tools.err(e.message ?: "не удалось")
        }
        fun s(vararg ps: P): JSONObject = Tools.schema(*ps)

        return listOf(
            AgentTool("get_datetime",
                "Get the current local date, time and weekday. Tell the user the 'text' field as is.",
                s(), "phone") {
                Tools.ok("text" to nowRu())
            },

            AgentTool("battery_status",
                "Get the phone battery level in percent and whether it is charging.",
                s(), "phone") {
                val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val charging = st == BatteryManager.BATTERY_STATUS_CHARGING ||
                    st == BatteryManager.BATTERY_STATUS_FULL
                Tools.ok("percent" to (level * 100 / scale), "charging" to charging)
            },

            AgentTool("set_alarm", "Set an alarm clock at the given time (24-hour format).",
                s(P("hour", "integer", "Hour 0-23"), P("minute", "integer", "Minute 0-59"),
                    P("label", "string", "Short label for the alarm", false)), "phone") { a ->
                launch(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", ""))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            },

            AgentTool("set_timer", "Start a countdown timer.",
                s(P("seconds", "integer", "Duration in seconds"),
                    P("label", "string", "Short label for the timer", false)), "phone") { a ->
                launch(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", ""))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            },

            AgentTool("flashlight", "Turn the phone flashlight on or off.",
                s(P("on", "boolean", "true to turn on, false to turn off")), "phone") { a ->
                val on = a.optBoolean("on")
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (id == null) Tools.err("нет фонарика") else {
                    cm.setTorchMode(id, on)
                    Tools.ok("on" to on)
                }
            },

            AgentTool("set_volume", "Set the media volume as a percentage from 0 to 100.",
                s(P("percent", "integer", "Volume percent 0-100")), "phone") { a ->
                val p = a.optInt("percent").coerceIn(0, 100)
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, p * max / 100, 0)
                Tools.ok("percent" to p)
            },

            AgentTool("open_app", "Open an installed app by its name, e.g. YouTube, Camera, Telegram, Settings.",
                s(P("name", "string", "App name as shown on the launcher")), "phone") { a ->
                val name = a.optString("name")
                val pm = ctx.packageManager
                val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val match = pm.queryIntentActivities(main, 0).firstOrNull {
                    it.loadLabel(pm).toString().contains(name, ignoreCase = true)
                }
                val i = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                if (i == null) Tools.err("приложение не найдено: $name") else launch(i)
            },

            AgentTool("open_url", "Open a web address in the browser.",
                s(P("url", "string", "Full URL or domain")), "phone") { a ->
                val url = a.optString("url")
                launch(Intent(Intent.ACTION_VIEW, Uri.parse(if (url.startsWith("http")) url else "https://$url")))
            },

            AgentTool("search_web", "Search the web in the browser.",
                s(P("query", "string", "Search query")), "phone") { a ->
                val q = URLEncoder.encode(a.optString("query"), "UTF-8")
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q")))
            },

            AgentTool("dial_number", "Open the phone dialer with a number filled in. The user presses call themselves.",
                s(P("number", "string", "Phone number")), "phone") { a ->
                launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", a.optString("number"), null)))
            },

            AgentTool("compose_sms", "Open an SMS draft with text prepared. The user presses send themselves.",
                s(P("number", "string", "Phone number"), P("text", "string", "Message text")), "phone") { a ->
                launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${a.optString("number")}"))
                    .putExtra("sms_body", a.optString("text")))
            },

            AgentTool("create_calendar_event", "Open a new calendar event form. The user saves it themselves.",
                s(P("title", "string", "Event title"),
                    P("start", "string", "Start local time in ISO format, e.g. 2026-10-25T18:30"),
                    P("duration_minutes", "integer", "Duration in minutes", false)), "phone") { a ->
                val begin = LocalDateTime.parse(a.optString("start"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val dur = a.optInt("duration_minutes", 60).coerceAtLeast(1)
                launch(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, a.optString("title"))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + dur * 60_000L))
            },
        )
    }
}
