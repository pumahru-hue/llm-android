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
            if (text.isNotBlank()) Chat.note("💥 Приложение упало:\n" + text.take(6000))
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

            // Нативный сбой: в трассе строки с именами библиотек, функций, сигналом
            if (last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                val bytes = runCatching { last.traceInputStream?.readBytes() }.getOrNull()
                if (bytes != null) {
                    val keys = listOf("signal", "SIG", "Abort", "abort", "lib", ".so", "litert", "LiteRT",
                        "tflite", "gemma", "Gemma", "fault", "Check failed", "FATAL")
                    val lines = asciiRuns(bytes).filter { r -> keys.any { r.contains(it) } }
                        .distinct().take(30).map { it.take(150) }
                    if (lines.isNotEmpty()) sb.append("\n\nТрасса (строки из дампа):\n").append(lines.joinToString("\n"))
                }
            }
            Chat.note(sb.toString().take(3500))

            // Логи самого приложения перед падением (читаются в отдельном потоке)
            Thread {
                val tail = logcatTail()
                if (tail.isNotBlank()) Chat.note("📜 Логи перед падением:\n$tail")
            }.start()
        } catch (_: Throwable) {
        }
    }

    private fun asciiRuns(b: ByteArray, min: Int = 8): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c in 32..126) sb.append(c.toChar())
            else {
                if (sb.length >= min) out += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.length >= min) out += sb.toString()
        return out
    }

    private fun logcatTail(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "3000"))
        val lines = p.inputStream.bufferedReader().readLines()
        lines.filter { l ->
            l.contains(" E/") || l.contains(" F/") || l.contains("Fatal") ||
                l.contains("litert", true) || l.contains("tflite", true) || l.contains("abort", true)
        }.takeLast(25).joinToString("\n") { it.take(180) }.take(3500)
    } catch (_: Throwable) {
        ""
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
