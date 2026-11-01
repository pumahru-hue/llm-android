package com.example.agent

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/** Записывает причину падения и показывает её в чате при следующем запуске. */
object CrashLog {

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(app.filesDir, "crash.txt").writeText("Поток: ${t.name}\n" + Log.getStackTraceString(e))
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun report(ctx: Context) {
        val f = File(ctx.filesDir, "crash.txt")
        if (f.exists()) {
            val text = runCatching { f.readText() }.getOrDefault("")
            f.delete()
            if (text.isNotBlank()) {
                Chat.note("💥 Приложение упало:\n" + text.lines().take(18).joinToString("\n").take(1800))
            }
            return
        }
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return
            val sp = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
            if (last.timestamp <= sp.getLong("exit_ts", 0L)) return
            sp.edit().putLong("exit_ts", last.timestamp).apply()

            val bad = setOf(
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            )
            if (last.reason !in bad) return

            val sb = StringBuilder("💥 Процесс завершён: ${reasonName(last.reason)}")
            last.description?.let { sb.append("\n").append(it) }
            if (last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                val trace = runCatching { last.traceInputStream?.bufferedReader()?.readText() }
                    .getOrNull().orEmpty()
                val keep = trace.lines().filter {
                    it.startsWith("signal") || it.contains("Abort message") || it.trim().startsWith("#")
                }.take(14)
                if (keep.isNotEmpty()) sb.append("\n").append(keep.joinToString("\n"))
            }
            Chat.note(sb.toString().take(1800))
        } catch (_: Throwable) {
        }
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "ошибка (исключение)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "сбой в нативном коде (модель)"
        ApplicationExitInfo.REASON_ANR -> "приложение не отвечало"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "нехватка памяти"
        ApplicationExitInfo.REASON_SIGNALED -> "убито сигналом (возможно, нехватка памяти)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "сбой запуска"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "чрезмерное потребление ресурсов"
        else -> "код $r"
    }
}
