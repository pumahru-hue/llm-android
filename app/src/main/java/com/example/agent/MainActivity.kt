package com.example.agent

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val recorder = Recorder()
    private lateinit var speaker: Speaker
    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var talk: Button
    private var recording = false

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        AgentService.start(this)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        speaker = Speaker(this)

        status = TextView(this).apply { textSize = 14f; setPadding(32, 24, 32, 8) }
        log = TextView(this).apply { textSize = 16f; setPadding(32, 32, 32, 32) }
        talk = Button(this).apply { text = "🎤 Говорить"; setOnClickListener { toggle() } }
        val download = Button(this).apply {
            text = "Скачать модель (2,6 ГБ, лучше по Wi-Fi)"
            setOnClickListener { AgentService.start(this@MainActivity, AgentService.ACTION_DOWNLOAD) }
        }
        val battery = Button(this).apply {
            text = "Отключить экономию батареи"
            setOnClickListener { AgentService.requestIgnoreBattery(this@MainActivity) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status); addView(download); addView(talk); addView(battery)
            addView(ScrollView(context).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })

        lifecycleScope.launch { DownloadState.text.collect { status.text = it } }
        lifecycleScope.launch { ToolLog.events.collect { log.append("$it\n") } }

        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(need.toTypedArray())
    }

    private fun toggle() {
        val host = AgentRuntime.host(this)
        if (!host.isReady) { log.append("Модель ещё не загружена, смотрите статус сверху.\n"); return }

        if (!recording) {
            speaker.stop()
            recorder.start(lifecycleScope)
            recording = true; talk.text = "⏹ Стоп"
        } else {
            recording = false; talk.text = "🎤 Говорить"
            lifecycleScope.launch {
                val pcm = recorder.stop()
                log.append("\n[аудио ${pcm.size / 32000.0} с]\nАгент: ")
                host.askWithAudio(pcm)
                    .catch { log.append("\n[ошибка: ${it.message}]") }
                    .collect { chunk -> log.append(chunk); speaker.feed(chunk) }
                speaker.flush()
            }
        }
    }

    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }
}
