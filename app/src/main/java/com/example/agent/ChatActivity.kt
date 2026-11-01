package com.example.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChatActivity : AppCompatActivity() {

    private val recorder = Recorder()
    private lateinit var speaker: Speaker
    private lateinit var rv: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var subtitle: TextView
    private lateinit var input: EditText
    private lateinit var actionHolder: FrameLayout
    private lateinit var attachBar: LinearLayout
    private lateinit var attachThumb: ImageView
    private lateinit var attachLabel: TextView
    private var levelView: LevelView? = null

    private var recording = false
    private var listening = false
    private var busyNow = false
    private var modelStatus = ""
    private var pendingUri: Uri? = null
    private var pending: Prepared? = null

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        AgentService.start(this)
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pendingUri?.let { stageMedia(it) }
    }
    private val takeVideo = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        if (ok) pendingUri?.let { stageMedia(it) }
    }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) stageMedia(uri)
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun iconBtn(res: Int, desc: String, bg: Int? = null, onClick: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        if (bg != null) background = Ui.rounded(bg, dp(14).toFloat()) else setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { onClick(it) }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        supportActionBar?.hide()
        ChatStore.init(this)
        CrashLog.report(this)
        speaker = Speaker(this)

        // верхняя панель
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF263238.toInt())
            setPadding(dp(16), dp(8), dp(8), dp(8))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply { text = "Agent"; textSize = 20f; setTextColor(Color.WHITE) })
        subtitle = TextView(this).apply { textSize = 12f; setTextColor(0xFFB0BEC5.toInt()); maxLines = 3 }
        titles.addView(subtitle)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(
            iconBtn(android.R.drawable.ic_menu_preferences, "Настройки") {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        // список сообщений
        adapter = ChatAdapter()
        adapter.items = ChatStore.snapshot()
        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity)
            adapter = this@ChatActivity.adapter
            clipToPadding = false
        }

        // полоса вложения (появляется после фото/видео/галереи)
        attachThumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        attachLabel = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE) }
        val attachClose = iconBtn(android.R.drawable.ic_menu_close_clear_cancel, "Убрать вложение") { clearPending() }
        attachBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF37474F.toInt())
            setPadding(dp(12), dp(6), dp(8), dp(6))
            visibility = View.GONE
        }
        attachBar.addView(attachThumb, LinearLayout.LayoutParams(dp(56), dp(56)))
        attachBar.addView(attachLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(10), 0, dp(10), 0) })
        attachBar.addView(attachClose, LinearLayout.LayoutParams(dp(40), dp(40)))

        // поле ввода: вложение, текст, отправить
        input = EditText(this).apply { hint = "Сообщение"; maxLines = 4 }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        val attach = iconBtn(R.drawable.ic_add, "Вложение", 0xFF424242.toInt()) { showAttachMenu(it) }
        val send = iconBtn(R.drawable.ic_send, "Отправить", 0xFF1565C0.toInt()) { sendText() }
        inputRow.addView(attach, LinearLayout.LayoutParams(dp(44), dp(44)))
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(6), 0, dp(6), 0) })
        inputRow.addView(send, LinearLayout.LayoutParams(dp(44), dp(44)))

        // нижний ряд: главная кнопка (или уровень сигнала) и «очистить чат»
        actionHolder = FrameLayout(this)
        val clear = iconBtn(R.drawable.ic_delete, "Очистить чат", 0xFF6D4C41.toInt()) { confirmClear() }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        actionRow.addView(actionHolder, LinearLayout.LayoutParams(0, dp(56), 3f).apply { setMargins(0, 0, dp(6), 0) })
        actionRow.addView(clear, LinearLayout.LayoutParams(0, dp(56), 1f))

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(attachBar)
            addView(inputRow)
            addView(actionRow)
        })

        if (adapter.items.isNotEmpty()) rv.scrollToPosition(adapter.items.size - 1)
        Chat.usage.value = Tokens.usage(this)

        lifecycleScope.launch { ChatStore.events.collect { onStoreEvent(it) } }
        lifecycleScope.launch { DownloadState.text.collect { modelStatus = it; renderSubtitle() } }
        lifecycleScope.launch { Chat.busy.collect { busyNow = it; renderSubtitle() } }
        lifecycleScope.launch { Chat.usage.collect { renderSubtitle() } }
        lifecycleScope.launch { Level.heard.collect { renderSubtitle() } }
        lifecycleScope.launch {
            ListenState.on.collect { on ->
                listening = on
                levelView?.active = on
                renderSubtitle()
            }
        }
        lifecycleScope.launch { Level.level.collect { levelView?.push(it) } }

        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(need.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        refreshAction()
        renderSubtitle()
    }

    // ---------- отрисовка ----------

    private fun renderSubtitle() {
        if (!::subtitle.isInitialized) return
        val sb = StringBuilder()
        sb.append(modelStatus.ifBlank { "Модель: запуск…" })
        if (busyNow) sb.append(" · думаю…")
        sb.append("\nконтекст ≈ ${Chat.usage.value} / ${Settings.ctxBudget(this)} ток.")
        val heard = Level.heard.value
        if (listening && heard.isNotBlank()) sb.append(" · слышу: ${heard.take(40)}")
        subtitle.text = sb.toString()
    }

    private fun onStoreEvent(e: ChatStore.Event) {
        val snap = ChatStore.snapshot()
        val nearBottom = !rv.canScrollVertically(1)
        val old = adapter.items.size
        adapter.items = snap
        when {
            e.kind == 0 && snap.size == old + 1 -> adapter.notifyItemInserted(snap.size - 1)
            e.kind == 1 && snap.size == old && e.index < snap.size -> adapter.notifyItemChanged(e.index)
            else -> adapter.notifyDataSetChanged()
        }
        if (snap.isNotEmpty() && (e.kind == 0 || nearBottom)) rv.scrollToPosition(snap.size - 1)
    }

    /** Главная кнопка: по кнопке — микрофон/стоп, по имени — индикатор уровня сигнала. */
    private fun refreshAction() {
        actionHolder.removeAllViews()
        if (Settings.actMode(this) == "name") {
            val lv = LevelView(this).apply {
                active = ListenState.on.value
                label = "Слушаю по имени: ${Settings.wakeName(this@ChatActivity)}"
                setOnClickListener { toggleListen() }
            }
            levelView = lv
            actionHolder.addView(lv, FrameLayout.LayoutParams(-1, -1))
        } else {
            levelView = null
            if (ListenState.on.value) ListenService.stop(this)
            val b = iconBtn(
                if (recording) R.drawable.ic_stop else R.drawable.ic_mic,
                if (recording) "Стоп" else "Говорить",
                if (recording) 0xFFC62828.toInt() else 0xFF2E7D32.toInt()
            ) { onMicClick() }
            actionHolder.addView(b, FrameLayout.LayoutParams(-1, -1))
        }
    }

    // ---------- вложение ----------

    private fun renderAttach() {
        val p = pending
        if (p == null) {
            attachBar.visibility = View.GONE
            input.hint = "Сообщение"
            return
        }
        val first = p.jpegs.first()
        attachThumb.setImageBitmap(BitmapFactory.decodeByteArray(first, 0, first.size))
        attachLabel.text = if (p.kind == "video")
            "🎞 Видео: ${p.jpegs.size} кадров. Напишите или скажите вопрос"
        else "📷 Фото. Напишите или скажите вопрос"
        attachBar.visibility = View.VISIBLE
        input.hint = "Ваш вопрос к вложению"
    }

    private fun clearPending() {
        pending = null
        renderAttach()
    }

    private fun stageMedia(uri: Uri) {
        lifecycleScope.launch {
            try {
                pending = withContext(Dispatchers.Default) { MediaUtil.prepare(this@ChatActivity, uri) }
                renderAttach()
                input.requestFocus()
            } catch (t: Throwable) {
                Chat.note("⚠ Медиа: ${t.message}")
            }
        }
    }

    /** Забирает вложение и сохраняет его кадры в чат. */
    private suspend fun takePending(): Pair<Prepared, List<String>>? {
        val p = pending ?: return null
        pending = null
        renderAttach()
        val names = withContext(Dispatchers.IO) { p.jpegs.map { ChatStore.saveImage(it) } }
        return p to names
    }

    // ---------- отправка ----------

    private fun ready(): Boolean {
        if (AgentRuntime.host(this).isReady) return true
        Toast.makeText(this, "Модель ещё не загружена. Настройки → Модель", Toast.LENGTH_LONG).show()
        return false
    }

    private fun runAsk(user: ChatMsg, live: List<Content>, voice: Boolean, extra: Int = 0) {
        val speak = when (Settings.ttsMode(this)) { "always" -> true; "never" -> false; else -> voice }
        speaker.stop()
        lifecycleScope.launch {
            Chat.ask(this@ChatActivity, user, live, extra)
                .catch { }
                .collect { if (speak) speaker.feed(it) }
            if (speak) speaker.flush()
        }
    }

    private fun sendText() {
        val t = input.text.toString().trim()
        if ((t.isEmpty() && pending == null) || !ready()) return
        input.setText("")
        lifecycleScope.launch {
            val att = takePending()
            if (att == null) {
                runAsk(ChatMsg(ChatStore.newId(), "user", t), listOf(Content.Text(t)), voice = false)
            } else {
                val (prep, names) = att
                val q = t.ifEmpty { DEFAULT_Q }
                val user = ChatMsg(ChatStore.newId(), "user", q, kind = prep.kind, images = names)
                runAsk(user, MediaUtil.parts(prep) + Content.Text(q), voice = false)
            }
        }
    }

    private fun onMicClick() {
        if (!ready()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нужно разрешение на микрофон", Toast.LENGTH_SHORT).show(); return
        }
        if (!recording) {
            speaker.stop()
            recorder.start(lifecycleScope)
            recording = true
            refreshAction()
        } else {
            recording = false
            refreshAction()
            lifecycleScope.launch {
                val pcm = recorder.stop()
                if (pcm.size < 8000) return@launch    // меньше 0,25 с — случайное нажатие
                val text = Stt.transcribe(this@ChatActivity, pcm) { Chat.note(it) }
                val att = takePending()
                val user = ChatMsg(
                    ChatStore.newId(), "user", text.ifBlank { "(голосовое сообщение)" },
                    kind = "voice", images = att?.second ?: emptyList()
                )
                val media = att?.let { MediaUtil.parts(it.first) } ?: emptyList()
                runAsk(user, Chat.voiceParts(pcm, media = media), voice = true, extra = pcm.size / 32000 * 25)
            }
        }
    }

    private fun toggleListen() {
        if (ListenState.on.value) { ListenService.stop(this); return }
        if (Settings.wakeName(this).isBlank()) {
            Toast.makeText(this, "Впишите имя: Настройки → Активация", Toast.LENGTH_LONG).show(); return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нужно разрешение на микрофон", Toast.LENGTH_SHORT).show(); return
        }
        if (!ready()) return
        ListenService.start(this)
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Очистить чат?")
            .setMessage("Вся переписка, картинки и контекст модели будут удалены.")
            .setPositiveButton("Очистить") { _, _ ->
                speaker.stop()
                clearPending()
                ChatStore.clear()
                lifecycleScope.launch { AgentRuntime.host(this@ChatActivity).dropConversation() }
                Chat.usage.value = Tokens.usage(this)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- камера и галерея ----------

    private fun showAttachMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "📷 Фото")
            menu.add(0, 2, 1, "🎞 Видео")
            menu.add(0, 3, 2, "🖼 Галерея")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { val u = captureUri("jpg"); pendingUri = u; takePhoto.launch(u) }
                    2 -> { val u = captureUri("mp4"); pendingUri = u; takeVideo.launch(u) }
                    else -> pickMedia.launch(arrayOf("image/*", "video/*"))
                }
                true
            }
            show()
        }
    }

    private fun captureUri(ext: String): Uri {
        val dir = File(cacheDir, "capture").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "cap_${System.currentTimeMillis()}.$ext")
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
    }

    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }

    companion object {
        const val DEFAULT_Q = "Что на этом снимке? Опиши коротко."
    }

    // ---------- адаптер чата ----------

    private class VH(
        val root: LinearLayout,
        val bubble: LinearLayout,
        val text: TextView,
        val imgs: LinearLayout,
    ) : RecyclerView.ViewHolder(root)

    private inner class ChatAdapter : RecyclerView.Adapter<VH>() {
        var items: List<ChatMsg> = emptyList()
        private val cache = object : LruCache<String, Bitmap>(32) {}

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position].role) {
            "user" -> 0
            "assistant" -> 1
            else -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }
            val bubble = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val imgs = LinearLayout(parent.context).apply { orientation = LinearLayout.HORIZONTAL }
            val text = TextView(parent.context).apply {
                textSize = 16f
                maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
            }
            bubble.addView(imgs)
            bubble.addView(text)
            val g = when (type) { 0 -> Gravity.END; 1 -> Gravity.START; else -> Gravity.CENTER_HORIZONTAL }
            root.addView(bubble, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = g })
            return VH(root, bubble, text, imgs)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val m = items[position]
            h.root.setOnLongClickListener {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("chat", m.text))
                Toast.makeText(this@ChatActivity, "Скопировано", Toast.LENGTH_SHORT).show()
                true
            }
            val type = getItemViewType(position)
            val prefix = when {
                m.role == "event" -> "🔔 "
                m.kind == "voice" -> "🎤 "
                m.kind == "video" -> "🎞 "
                else -> ""
            }
            h.text.text = prefix + m.text

            when (type) {
                0 -> {
                    h.bubble.background = Ui.rounded(0xFF1565C0.toInt(), dp(16).toFloat())
                    h.text.setTextColor(Color.WHITE); h.text.textSize = 16f
                }
                1 -> {
                    h.bubble.background = Ui.rounded(0xFF37474F.toInt(), dp(16).toFloat())
                    h.text.setTextColor(Color.WHITE); h.text.textSize = 16f
                }
                else -> {
                    h.bubble.background = null
                    h.text.setTextColor(0xFF90A4AE.toInt()); h.text.textSize = 13f
                }
            }
            h.root.alpha = if (m.inCtx || m.role == "note" || m.role == "tool") 1f else 0.45f

            h.imgs.removeAllViews()
            val shown = m.images.take(3)
            for (name in shown) {
                h.imgs.addView(ImageView(h.root.context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(thumb(name))
                }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { setMargins(0, 0, dp(4), dp(4)) })
            }
            if (m.images.size > 3) {
                h.imgs.addView(TextView(h.root.context).apply {
                    text = "+${m.images.size - 3}"
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(40), dp(96)))
            }
            h.imgs.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun thumb(name: String): Bitmap? {
            cache.get(name)?.let { return it }
            val f = ChatStore.imageFile(name)
            if (!f.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 400) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp != null) cache.put(name, bmp)
            return bmp
        }
    }
}
