package com.example.agent

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Один класс на все экраны настроек: страница выбирается extra "page". */
class SettingsActivity : AppCompatActivity() {

    private lateinit var col: LinearLayout
    private var page = "home"

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        page = intent.getStringExtra("page") ?: "home"
        title = when (page) {
            "activation" -> "Активация"
            "voice" -> "Озвучка ответов"
            "model" -> "Модель и инструменты"
            "prompt" -> "Системный промпт"
            "context" -> "Контекст чата"
            "mcp" -> "MCP-серверы"
            else -> "Настройки"
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        when (page) {
            "activation" -> pageActivation()
            "voice" -> pageVoice()
            "model" -> pageModel()
            "prompt" -> pagePrompt()
            "context" -> pageContext()
            "mcp" -> pageMcp()
            else -> pageHome()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onPause() {
        super.onPause()
        if (page == "prompt") AgentRuntime.invalidate(this)   // новый промпт вступает в силу сразу
    }

    // ---------- страницы ----------

    private fun pageHome() {
        nav("Активация (кнопка или имя)", "activation")
        nav("Озвучка ответов", "voice")
        nav("Модель и инструменты", "model")
        nav("Системный промпт", "prompt")
        nav("Контекст чата", "context")
        nav("MCP-серверы (JSON)", "mcp")
        button(batteryLabel()) { AgentService.requestIgnoreBattery(this) }
        hint("Готовится в следующих этапах:")
        soon("События: SMS и звонки")
        soon("Камеры")
        soon("Задачи по расписанию")
    }

    private fun pageActivation() {
        hint("Как будить ассистента.")
        radio(Settings.ACT_MODE, listOf("button" to "По кнопке (микрофон на главном экране)",
            "name" to "По имени (слушаю всегда, на главном экране индикатор уровня)"), "button")
        edit("Имя ассистента (для режима «по имени»)", Settings.WAKE_NAME, "")
        hint("Лучше имя из двух-трёх слогов, редкое в обычной речи. Фразу «слышу: …» видно под заголовком " +
            "главного экрана, по ней легко подобрать имя, которое хорошо распознаётся.")
    }

    private fun pageVoice() {
        hint("Когда ассистент отвечает голосом.")
        radio(Settings.TTS_MODE, listOf(
            "voice" to "Только если я говорил голосом",
            "always" to "Всегда",
            "never" to "Никогда"), "voice")
    }

    private fun pageModel() {
        hint("Версия LiteRT-LM в этой сборке: ${BuildConfig.LITERT_VERSION}")
        col.addView(CheckBox(this).apply {
            text = "Встроенные инструменты телефона (будильник, фонарик, звонки…)"
            isChecked = Settings.toolsOn(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Settings.put(this@SettingsActivity, Settings.TOOLS_ON, if (on) 1 else 0)
                AgentRuntime.invalidate(this@SettingsActivity)
            }
        })
        hint("Способ подключения инструментов. «OpenAPI» — новый путь (рекомендуется), «Kotlin» — прежний. " +
            "Если одно из двух падает, переключитесь на другое.")
        radio(Settings.TOOLS_IMPL, listOf(
            "openapi" to "OpenAPI (JSON-описания)",
            "kotlin" to "Kotlin-функции (прежний)"), "openapi") {
            AgentRuntime.invalidate(this)
        }

        val st = TextView(this).apply { textSize = 15f; setPadding(0, dp(12), 0, dp(8)) }
        col.addView(st)
        lifecycleScope.launch { DownloadState.text.collect { st.text = it.ifBlank { "…" } } }
        val f = AgentRuntime.host(this).modelFile
        hint("Файл модели: ${f.name} (${if (f.exists()) f.length() / 1048576 else 0} МБ)")
        button("Скачать модель (2,6 ГБ, лучше по Wi-Fi)") {
            AgentService.start(this, AgentService.ACTION_DOWNLOAD)
        }
    }

    private fun pagePrompt() {
        hint("Системная инструкция модели. Изменения сохраняются сразу, диалог пересоберётся. " +
            "К ней приложение само добавляет правила про инструменты.")
        edit("Промпт", Settings.PROMPT, Settings.DEFAULT_PROMPT, multiline = true)
        button("Сбросить к умолчанию") {
            Settings.put(this, Settings.PROMPT, Settings.DEFAULT_PROMPT)
            recreate()
        }
    }

    private fun pageContext() {
        hint("Чат склеенный: модель помнит всё, что в контексте (текст, картинки, кадры видео, расшифровки голоса). " +
            "Контекст чистится только кнопкой «очистить чат» или при превышении лимита.")
        edit("Окно модели при загрузке, токенов (применяется после перезагрузки модели)",
            Settings.ENGINE_WINDOW, Settings.recommendedWindow(this).toString(), number = true)
        hint("Память телефона: ${Settings.totalRamMb(this)} МБ, рекомендуемое окно: " +
            "${Settings.recommendedWindow(this)} ток. Сейчас загружено: ${AgentRuntime.window} ток. " +
            "Можно вписать и больше (до 131072): если памяти не хватит, приложение само попробует вдвое " +
            "меньше, но при нехватке памяти система может пару раз закрыть приложение, пока оно подберёт " +
            "окно. Чем больше окно, тем дольше загрузка и долгая первая обработка.")
        button("Перезагрузить модель (применить окно)") {
            AgentRuntime.reload(this)
            Toast.makeText(this, "Перезагружаю, смотрите статус на главном экране", Toast.LENGTH_LONG).show()
        }
        edit("Лимит контекста для сжатия, токенов (оценка)", Settings.CTX_BUDGET, "120000", number = true)
        hint("Реальный размер окна зависит от сборки модели и неизвестен. Если появляются ошибки «Task failed», " +
            "уменьшите лимит. Приложение при ошибке само сожмёт контекст сильнее и повторит запрос.")
        hint("Что убирать при превышении:")
        radio(Settings.CTX_STRATEGY, listOf(
            "start" to "Начало чата (самое старое)",
            "middle" to "Середину (начало и свежее остаются)"), "start")
        edit("Для «середины»: сколько первых сообщений оставлять", Settings.CTX_KEEP_FIRST, "2", number = true)
        edit("Оценка токенов на одну картинку или кадр", Settings.TOK_IMAGE, "280", number = true)
        edit("Повторять последнюю картинку в N следующих репликах (0 = выключено)", Settings.PIN_TURNS, "3", number = true)
        hint("Сейчас в контексте ≈ ${Tokens.usage(this)} токенов. Убранные сообщения остаются в чате " +
            "бледными, но модель их больше не видит.")
    }

    private fun pageMcp() {
        val ctx = this
        hint("Удалённые MCP-серверы по HTTP (Streamable HTTP, редакции 2025-03-26 … 2025-11-25). " +
            "Формат как в Claude Desktop: имя → url и, если нужно, headers. Android-агент (порт 8745, " +
            "токен в X-Auth-Token) определяется автоматически. Серверы, которые запускаются локально " +
            "через npx или uvx, на телефоне не работают. Для адресов http:// разрешён открытый трафик.")

        val et = EditText(this).apply {
            setText(Settings.str(ctx, McpManager.KEY_JSON, McpManager.EXAMPLE))
            typeface = Typeface.MONOSPACE
            textSize = 12f
            minLines = 10
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(watcher { Settings.put(ctx, McpManager.KEY_JSON, it) })
        }
        col.addView(et)

        val status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)); setTextIsSelectable(true) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        button("Проверить и загрузить инструменты") {
            status.text = "Подключаюсь…"
            lifecycleScope.launch {
                val st = McpManager.refresh(ctx)
                status.text = if (st.isEmpty()) "В JSON нет серверов" else
                    st.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.server + ": " + it.message }
                renderMcpTools(box)
                AgentRuntime.invalidate(ctx)
            }
        }
        button("Вставить пример") { et.setText(McpManager.EXAMPLE) }
        val probeOut = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        button("Разведка: что отвечает адрес сервера") {
            probeOut.text = "Опрашиваю…"
            lifecycleScope.launch { probeOut.text = McpManager.probe(ctx) }
        }
        col.addView(status)
        col.addView(probeOut)
        col.addView(box)
        renderMcpTools(box)
    }

    private fun renderMcpTools(box: LinearLayout) {
        box.removeAllViews()
        val recs = McpManager.saved(this)
        if (recs.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "Инструментов пока нет. Нажмите «Проверить и загрузить»."
                alpha = 0.7f
            })
            return
        }
        box.addView(TextView(this).apply {
            text = "Инструменты. 👁 только читают, ✋ действуют сразу (клики, ввод, запуск приложений), " +
                "⚠ перед выполнением пришлют уведомление с кнопками «Да / Нет». " +
                "Включено по умолчанию только нужное для управления экраном."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(4))
            alpha = 0.8f
        })
        val bulk = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bulk.addView(Button(this).apply {
            text = "Включить все"
            isAllCaps = false
            setOnClickListener {
                McpManager.setAll(this@SettingsActivity, recs, true)
                AgentRuntime.invalidate(this@SettingsActivity)
                renderMcpTools(box)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bulk.addView(Button(this).apply {
            text = "По умолчанию"
            isAllCaps = false
            setOnClickListener {
                McpManager.setAll(this@SettingsActivity, recs, null)
                AgentRuntime.invalidate(this@SettingsActivity)
                renderMcpTools(box)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(bulk)
        val footer = TextView(this).apply { textSize = 13f; setPadding(0, dp(8), 0, 0) }
        fun updateFooter() {
            val n = McpManager.tools(this@SettingsActivity).size
            footer.text = "Включено: $n (максимум ${McpManager.MAX_TOOLS}). " +
                "Описания займут ≈ ${McpManager.tokenCost(this@SettingsActivity)} токенов контекста."
        }
        for (r in recs) {
            val tag = when (r.risk) { "read" -> "👁 чтение"; "act" -> "✋ действие"; else -> "⚠ спросит разрешение" }
            box.addView(CheckBox(this).apply {
                text = "${r.server} · ${r.name}  [$tag]\n${r.description.take(100)}"
                textSize = 13f
                isChecked = McpManager.isEnabled(this@SettingsActivity, r)
                setOnCheckedChangeListener { _, on ->
                    McpManager.setEnabled(this@SettingsActivity, r, on)
                    AgentRuntime.invalidate(this@SettingsActivity)
                    updateFooter()
                }
            })
        }
        box.addView(footer)
        updateFooter()
    }

    // ---------- элементы ----------

    private fun batteryLabel(): String {
        val pm = getSystemService(android.os.PowerManager::class.java)
        return if (pm.isIgnoringBatteryOptimizations(packageName)) "Экономия батареи: отключена ✓"
        else "Отключить экономию батареи (рекомендуется)"
    }

    private fun watcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
    }

    private fun hint(t: String) {
        col.addView(TextView(this).apply {
            text = t
            textSize = 13f
            setPadding(0, dp(10), 0, dp(4))
            alpha = 0.75f
        })
    }

    private fun button(label: String, onClick: () -> Unit) {
        col.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        })
    }

    private fun nav(label: String, target: String) = button(label) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", target))
    }

    private fun soon(label: String) {
        col.addView(Button(this).apply {
            text = "$label — скоро"
            isAllCaps = false
            isEnabled = false
        })
    }

    private fun radio(key: String, options: List<Pair<String, String>>, def: String, after: () -> Unit = {}) {
        val cur = Settings.str(this, key, def)
        val g = RadioGroup(this)
        options.forEachIndexed { i, (value, label) ->
            g.addView(RadioButton(this).apply {
                id = 1000 + i
                text = label
                isChecked = value == cur
            })
        }
        g.setOnCheckedChangeListener { _, id ->
            Settings.put(this, key, options[id - 1000].first)
            after()
        }
        col.addView(g)
    }

    private fun edit(label: String, key: String, def: String, multiline: Boolean = false, number: Boolean = false) {
        col.addView(TextView(this).apply { text = label; setPadding(0, dp(12), 0, 0) })
        val ctx = this
        col.addView(EditText(this).apply {
            setText(if (number) Settings.int(ctx, key, def.toInt()).toString() else Settings.str(ctx, key, def))
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 8
                gravity = Gravity.TOP
            }
            addTextChangedListener(watcher { t ->
                if (number) t.toIntOrNull()?.let { Settings.put(ctx, key, it) } else Settings.put(ctx, key, t)
            })
        })
    }
}
