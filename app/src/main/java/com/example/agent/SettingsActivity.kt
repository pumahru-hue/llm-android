package com.example.agent

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
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
            "model" -> "Модель"
            "prompt" -> "Системный промпт"
            "context" -> "Контекст чата"
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
        nav("Модель", "model")
        nav("Системный промпт", "prompt")
        nav("Контекст чата", "context")
        button(batteryLabel()) { AgentService.requestIgnoreBattery(this) }
        hint("Готовится в следующих этапах:")
        soon("MCP-серверы (JSON)")
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
        val st = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        col.addView(st)
        lifecycleScope.launch { DownloadState.text.collect { st.text = it.ifBlank { "…" } } }
        val f = AgentRuntime.host(this).modelFile
        hint("Файл модели: ${f.name} (${if (f.exists()) f.length() / 1048576 else 0} МБ)")
        button("Скачать модель (2,6 ГБ, лучше по Wi-Fi)") {
            AgentService.start(this, AgentService.ACTION_DOWNLOAD)
        }
    }

    private fun pagePrompt() {
        hint("Системная инструкция модели. Изменения сохраняются сразу, диалог пересоберётся.")
        edit("Промпт", Settings.PROMPT, Settings.DEFAULT_PROMPT, multiline = true)
        button("Сбросить к умолчанию") {
            Settings.put(this, Settings.PROMPT, Settings.DEFAULT_PROMPT)
            recreate()
        }
    }

    private fun pageContext() {
        hint("Чат склеенный: модель помнит всё, что в контексте (текст, картинки, кадры видео, расшифровки голоса). " +
            "Контекст чистится только кнопкой «очистить чат» или при превышении лимита.")
        edit("Лимит контекста, токенов (оценка)", Settings.CTX_BUDGET, "3500", number = true)
        hint("Реальный размер окна зависит от сборки модели и неизвестен. Если появляются ошибки «Task failed», " +
            "уменьшите лимит. Приложение при ошибке само сожмёт контекст сильнее и повторит запрос.")
        hint("Что убирать при превышении:")
        radio(Settings.CTX_STRATEGY, listOf(
            "start" to "Начало чата (самое старое)",
            "middle" to "Середину (начало и свежее остаются)"), "start")
        edit("Для «середины»: сколько первых сообщений оставлять", Settings.CTX_KEEP_FIRST, "2", number = true)
        edit("Оценка токенов на одну картинку или кадр", Settings.TOK_IMAGE, "280", number = true)
        hint("Сейчас в контексте ≈ ${Tokens.usage(this)} токенов. Убранные сообщения остаются в чате " +
            "бледными, но модель их больше не видит.")
    }

    // ---------- элементы ----------

    private fun batteryLabel(): String {
        val pm = getSystemService(android.os.PowerManager::class.java)
        return if (pm.isIgnoringBatteryOptimizations(packageName)) "Экономия батареи: отключена ✓"
        else "Отключить экономию батареи (рекомендуется)"
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

    private fun radio(key: String, options: List<Pair<String, String>>, def: String) {
        val cur = Settings.str(this, key, def)
        val g = RadioGroup(this)
        options.forEachIndexed { i, (value, label) ->
            g.addView(RadioButton(this).apply {
                id = 1000 + i
                text = label
                isChecked = value == cur
            })
        }
        g.setOnCheckedChangeListener { _, id -> Settings.put(this, key, options[id - 1000].first) }
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
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val t = s?.toString() ?: ""
                    if (number) t.toIntOrNull()?.let { Settings.put(ctx, key, it) } else Settings.put(ctx, key, t)
                }
            })
        })
    }
}
