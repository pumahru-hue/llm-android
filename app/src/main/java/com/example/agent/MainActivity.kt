package com.example.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val recorder = Recorder()
    private lateinit var speaker: Speaker
    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var talk: Button
    private lateinit var listen: Button
    private lateinit var nameEdit: EditText
    private var recording = false

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        AgentService.start(this)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        speaker = Speaker(this)
        val prefs = getSharedPreferences("agent", MODE_PRIVATE)

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
        nameEdit = EditText(this).apply {
            hint = "Имя ассистента (например: Джарвис)"
            setText(prefs.getString("wake_name", ""))
            setSingleLine()
        }
        listen = Button(this).apply { setOnClickListener { toggleListen() } }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status); addView(download); addView(nameEdit); addView(listen)
            addView(talk); addView(battery)
            addView(ScrollView(context).apply { addView(log) }, LinearLayout.LayoutParams(-1, 0, 1f))
        })

        lifecycleScope.launch { DownloadState.text.collect { status.text = it } }
        lifecycleScope.launch { ToolLog.events.collect { log.append(it) } }
        lifecycleScope.launch {
            ListenState.on.collect { on ->
                listen.text = if (on) "⏹ Выключить прослушивание" else "👂 Включить прослушивание по имени"
                talk.isEnabled = !on
            }
        }

        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(need.toTypedArray())
    }

    private fun toggleListen() {
        if (ListenState.on.value) { ListenService.stop(this); return }

        val name = nameEdit.text.toString().trim()
        if (name.isEmpty()) { Toast.makeText(this, "Сначала впишите имя", Toast.LENGTH_SHORT).show(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нужно разрешение на микрофон", Toast.LENGTH_SHORT).show(); return
        }
        if (!AgentRuntime.host(this).isReady) {
            Toast.makeText(this, "Модель ещё не загружена", Toast.LENGTH_SHORT).show(); return
        }
        getSharedPreferences("agent", MODE_PRIVATE).edit().putString("wake_name", name).apply()
        ListenService.start(this)
    }

    private fun toggle() {
        val host = AgentRuntime.host(this)
        if (!host.isReady) { log.append("\nМодель ещё не загружена, смотрите статус сверху.\n"); return }

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
