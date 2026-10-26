package com.example.agent

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val recorder = Recorder()
    private lateinit var speaker: Speaker
    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var talk: Button
    private lateinit var listen: Button
    private lateinit var nameEdit: EditText
    private lateinit var askEdit: EditText
    private lateinit var preview: ImageView
    private var recording = false
    private var pendingUri: Uri? = null

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        AgentService.start(this)
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pendingUri?.let { askAboutMedia(it) }
    }
    private val takeVideo = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        if (ok) pendingUri?.let { askAboutMedia(it) }
    }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askAboutMedia(uri)
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

        askEdit = EditText(this).apply {
            hint = "Вопрос к фото или видео"
            setText(prefs.getString("media_q", "Что на этом снимке? Опиши коротко."))
        }
        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fun b(t: String, f: () -> Unit) = Button(context).apply {
                text = t; setOnClickListener { f() }
            }
            addView(b("📷 Фото") { shootPhoto() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(b("🎞 Видео") { shootVideo() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(b("🖼 Галерея") { pickMedia.launch(arrayOf("image/*", "video/*")) },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val previewHeight = (160 * resources.displayMetrics.density).toInt()

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status); addView(download); addView(nameEdit); addView(listen)
            addView(talk); addView(askEdit); addView(mediaRow)
            addView(preview, LinearLayout.LayoutParams(-1, previewHeight))
            addView(battery)
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

    // ---------- камера и медиа ----------

    private fun captureUri(ext: String): Uri {
        val dir = File(cacheDir, "capture").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "cap_${System.currentTimeMillis()}.$ext")
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
    }

    private fun shootPhoto() {
        val u = captureUri("jpg"); pendingUri = u; takePhoto.launch(u)
    }

    private fun shootVideo() {
        val u = captureUri("mp4"); pendingUri = u; takeVideo.launch(u)
    }

    private fun askAboutMedia(uri: Uri) {
        val host = AgentRuntime.host(this)
        if (!host.isReady) { log.append("\nМодель ещё не загружена, смотрите статус сверху.\n"); return }
        val question = askEdit.text.toString()
        getSharedPreferences("agent", MODE_PRIVATE).edit().putString("media_q", question).apply()
        speaker.stop()

        lifecycleScope.launch {
            val kind = contentResolver.getType(uri) ?: "?"
            log.append("\n[медиа: $kind] $question\nАгент: ")
            try {
                val prep = withContext(Dispatchers.Default) { MediaUtil.prepare(this@MainActivity, uri, question) }
                preview.setImageBitmap(BitmapFactory.decodeByteArray(prep.preview, 0, prep.preview.size))
                host.askMedia(prep.parts)
                    .catch { log.append("\n[ошибка: ${it.message}]") }
                    .collect { chunk -> log.append(chunk); speaker.feed(chunk) }
                speaker.flush()
            } catch (t: Throwable) {
                log.append("\n[ошибка медиа: ${t.message}]")
            }
        }
    }

    // ---------- голос ----------

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
