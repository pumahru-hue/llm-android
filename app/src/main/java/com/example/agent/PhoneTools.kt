package com.example.agent

import android.app.Activity
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
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.flow.MutableSharedFlow
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Журнал вызовов инструментов для экрана. */
object ToolLog {
    val events = MutableSharedFlow<String>(extraBufferCapacity = 64)
}

class PhoneTools(private val ctx: Context) : ToolSet {

    private fun log(s: String) { ToolLog.events.tryEmit("\n🔧 $s\n") }

    private fun launch(i: Intent): Map<String, Any> = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        mapOf("status" to "ok")
    } catch (e: Exception) {
        mapOf("status" to "error", "message" to (e.message ?: "не удалось"))
    }

    @Tool(description = "Get the current local date, time and weekday. Tell the user the 'text' field as is.")
    fun getDatetime(): Map<String, Any> {
        log("get_datetime")
        return mapOf("text" to nowRu())
    }

    @Tool(description = "Get the phone battery level in percent and whether it is charging.")
    fun batteryStatus(): Map<String, Any> {
        log("battery_status")
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return mapOf("percent" to (level * 100 / scale), "charging" to charging)
    }

    @Tool(description = "Set an alarm clock at the given time (24-hour format).")
    fun setAlarm(
        @ToolParam(description = "Hour 0-23") hour: Int,
        @ToolParam(description = "Minute 0-59") minute: Int,
        @ToolParam(description = "Short label for the alarm, may be empty") label: String,
    ): Map<String, Any> {
        log("set_alarm($hour:$minute, $label)")
        return launch(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
    }

    @Tool(description = "Start a countdown timer.")
    fun setTimer(
        @ToolParam(description = "Duration in seconds") seconds: Int,
        @ToolParam(description = "Short label for the timer, may be empty") label: String,
    ): Map<String, Any> {
        log("set_timer($seconds c, $label)")
        return launch(Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
    }

    @Tool(description = "Turn the phone flashlight on or off.")
    fun flashlight(@ToolParam(description = "true to turn on, false to turn off") on: Boolean): Map<String, Any> {
        log("flashlight($on)")
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return mapOf("status" to "error", "message" to "нет фонарика")
            cm.setTorchMode(id, on)
            mapOf("status" to "ok", "on" to on)
        } catch (e: Exception) {
            mapOf("status" to "error", "message" to (e.message ?: "ошибка"))
        }
    }

    @Tool(description = "Set the media volume as a percentage from 0 to 100.")
    fun setVolume(@ToolParam(description = "Volume percent 0-100") percent: Int): Map<String, Any> {
        log("set_volume($percent)")
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, percent.coerceIn(0, 100) * max / 100, 0)
        return mapOf("status" to "ok", "percent" to percent.coerceIn(0, 100))
    }

    @Tool(description = "Open an installed app by its name, e.g. YouTube, Camera, Telegram, Settings.")
    fun openApp(@ToolParam(description = "App name as shown on the launcher") name: String): Map<String, Any> {
        log("open_app($name)")
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(main, 0).firstOrNull {
            it.loadLabel(pm).toString().contains(name, ignoreCase = true)
        } ?: return mapOf("status" to "error", "message" to "приложение не найдено: $name")
        val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return mapOf("status" to "error", "message" to "нельзя запустить")
        return launch(i)
    }

    @Tool(description = "Open a web address in the browser.")
    fun openUrl(@ToolParam(description = "Full URL or domain") url: String): Map<String, Any> {
        log("open_url($url)")
        val u = if (url.startsWith("http")) url else "https://$url"
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    }

    @Tool(description = "Search the web in the browser.")
    fun searchWeb(@ToolParam(description = "Search query") query: String): Map<String, Any> {
        log("search_web($query)")
        val q = URLEncoder.encode(query, "UTF-8")
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q")))
    }

    @Tool(description = "Open the phone dialer with a number filled in. The user presses call themselves.")
    fun dialNumber(@ToolParam(description = "Phone number") number: String): Map<String, Any> {
        log("dial_number($number)")
        return launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    }

    @Tool(description = "Open an SMS draft with text prepared. The user presses send themselves.")
    fun composeSms(
        @ToolParam(description = "Phone number") number: String,
        @ToolParam(description = "Message text") text: String,
    ): Map<String, Any> {
        log("compose_sms($number)")
        return launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", text))
    }

    @Tool(description = "Open a new calendar event form. The user saves it themselves.")
    fun createCalendarEvent(
        @ToolParam(description = "Event title") title: String,
        @ToolParam(description = "Start local time in ISO format, e.g. 2026-10-25T18:30") start: String,
        @ToolParam(description = "Duration in minutes") durationMinutes: Int,
    ): Map<String, Any> {
        log("create_event($title, $start)")
        return try {
            val begin = LocalDateTime.parse(start).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            launch(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + durationMinutes * 60_000L))
        } catch (e: Exception) {
            mapOf("status" to "error", "message" to "неверный формат времени")
        }
    }
}
