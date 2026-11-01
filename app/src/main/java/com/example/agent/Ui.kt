package com.example.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow

/** Уровень сигнала микрофона и последняя услышанная фраза (для индикаторов на экране). */
object Level {
    val level = MutableStateFlow(0f)
    val heard = MutableStateFlow("")

    fun rms(b: ByteArray, n: Int): Float {
        var sum = 0.0
        var cnt = 0
        var i = 0
        while (i + 1 < n) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            cnt++
            i += 2
        }
        if (cnt == 0) return 0f
        return (Math.sqrt(sum / cnt) / 32768.0 * 6.0).toFloat().coerceIn(0f, 1f)
    }
}

object Ui {
    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float = 28f) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }
}

/** Вместо кнопки в режиме «по имени»: показывает уровень сигнала, по нажатию включает/выключает слушание. */
class LevelView(ctx: Context) : View(ctx) {
    var active = false
        set(v) { field = v; invalidate() }
    var label = ""
        set(v) { field = v; invalidate() }

    private val levels = FloatArray(32)
    private var pos = 0
    private val density = ctx.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9CCC65.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
    }

    fun push(v: Float) {
        levels[pos] = v
        pos = (pos + 1) % levels.size
        if (active) invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bg.color = if (active) 0xFF1B5E20.toInt() else 0xFF424242.toInt()
        c.drawRoundRect(0f, 0f, w, h, 14f * density, 14f * density, bg)
        if (!active) {
            c.drawText("👂 нажмите, чтобы слушать по имени", w / 2, h / 2 + txt.textSize / 3, txt)
            return
        }
        val n = levels.size
        val bw = w / n
        for (i in 0 until n) {
            val v = levels[(pos + i) % n].coerceIn(0f, 1f)
            val bh = h * 0.12f + h * 0.55f * v
            val l = i * bw
            c.drawRoundRect(l + bw * 0.2f, (h - bh) / 2 - h * 0.08f, l + bw * 0.8f, (h + bh) / 2 - h * 0.08f,
                4f * density, 4f * density, bar)
        }
        if (label.isNotEmpty()) c.drawText(label, w / 2, h - 6f * density, txt)
    }
}
