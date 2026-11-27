package com.android.inputmethod.latin.ivol

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.android.inputmethod.latin.settings.Settings
import com.android.inputmethod.latin.settings.SettingsActivity

/** IVOL: one simple settings screen (look, vibration, AI model connection, wand prompts). */
class IvolSettingsActivity : Activity() {

    companion object {
        const val EXTRA_SCROLL_TO_AI = "scroll_to_ai"
        const val EXTRA_INSTALL_UPDATE = "install_update"
    }

    private var scrollView: ScrollView? = null
    private var aiHeader: View? = null
    private var updateStatusView: TextView? = null
    private var updatesHeader: View? = null
    private var updatesOpen: (() -> Unit)? = null
    private var aiOpen: (() -> Unit)? = null
    private var langsBox: LinearLayout? = null
    private var voiceBox: LinearLayout? = null

    private val bg = Color.parseColor("#161C20")
    private val card = Color.parseColor("#242B30")
    private val cText = Color.parseColor("#DCE3E9")
    private val sub = Color.parseColor("#8E979D")
    private val accent = Color.parseColor("#C1E8FF")

    private val seekBars = ArrayList<Pair<SeekBar, () -> Unit>>()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        IvolPrefs.init(this)
        setContentView(build())
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SCROLL_TO_AI, false) == true) scrollToAi()
        // Came from the update icon: show the update block and start installing right away.
        if (intent?.getBooleanExtra(EXTRA_INSTALL_UPDATE, false) == true) {
            scrollToUpdates()
            IvolUpdater.checkNow(this)
        }
    }

    private fun scrollToUpdates() {
        updatesOpen?.invoke()
        val sv = scrollView ?: return
        sv.post { updatesHeader?.let { sv.smoothScrollTo(0, it.top) } }
    }

    override fun onResume() {
        super.onResume()
        IvolUpdater.listener = { s -> runOnUiThread { updateStatusView?.text = s } }
        IvolLanguages.listener = Runnable { runOnUiThread { refreshLanguages() } }
        GigaAmVoice.settingsListener = Runnable { refreshVoice() }
        refreshLanguages()
        refreshVoice()
        updateStatusView?.text = updateStatusText()
        // Back from "allow installing apps": continue the update the user started.
        if (IvolUpdater.pendingInstall && (android.os.Build.VERSION.SDK_INT < 26 ||
                packageManager.canRequestPackageInstalls())) {
            IvolUpdater.checkNow(this)
        }
    }

    override fun onPause() {
        super.onPause()
        IvolUpdater.listener = null
        IvolLanguages.listener = null
        GigaAmVoice.settingsListener = null
    }

    private fun scrollToAi() {
        aiOpen?.invoke()
        val sv = scrollView ?: return
        sv.post { aiHeader?.let { sv.smoothScrollTo(0, it.top) } }
    }

    // ---------------- Accordion: tap a header to open / close its block ----------------

    private class Section(val header: View, val body: LinearLayout, val open: () -> Unit)

    private fun section(
        root: LinearLayout, title: String, open: Boolean = false, onOpen: (() -> Unit)? = null,
    ): Section {
        val arrow = TextView(this).apply { setTextColor(accent); textSize = 16f }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@IvolSettingsActivity).apply {
                text = title
                setTextColor(cText)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrow)
        }
        cardOf(header)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(6), dp(10))
        }
        fun set(o: Boolean) {
            body.visibility = if (o) View.VISIBLE else View.GONE
            arrow.text = if (o) "▲" else "▼"
            if (o) onOpen?.invoke()
        }
        header.setOnClickListener { set(body.visibility != View.VISIBLE) }
        set(open)
        root.addView(header)
        root.addView(body)
        return Section(header, body) { if (body.visibility != View.VISIBLE) set(true) }
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            setText("Настройки клавиатуры")
            setTextColor(cText)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(16))
        })

        // A newer version is known (or we came from the update icon): updates block goes first, open.
        val updatesFirst = IvolUpdater.availableVersion(this) != null ||
            intent?.getBooleanExtra(EXTRA_INSTALL_UPDATE, false) == true
        if (updatesFirst) buildUpdates(root, true)

        // Preview field: keeps our keyboard open, it rebuilds live while sliders move.
        val preview = EditText(this).apply {
            hint = "Попробуй напечатать здесь"
            setHintTextColor(sub)
            setTextColor(cText)
            textSize = 16f
            isSingleLine = true
        }
        val look = section(root, "Внешний вид и вибрация") {
            // Opening the block shows the keyboard so the sliders can be seen in action.
            preview.post {
                preview.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                    ?.showSoftInput(preview, 0)
            }
        }.body
        look.addView(cardOf(preview))

        val p = IvolPrefs.prefs(this)
        look.addView(slider("Высота клавиатуры", IvolPrefs.KEY_HEIGHT, 80, 130,
            p.getInt(IvolPrefs.KEY_HEIGHT, IvolPrefs.HEIGHT_DEFAULT), IvolPrefs.HEIGHT_DEFAULT) { "$it%" })
        look.addView(slider("Ширина клавиш", IvolPrefs.KEY_WIDTH, 0, 100,
            p.getInt(IvolPrefs.KEY_WIDTH, IvolPrefs.WIDTH_DEFAULT), IvolPrefs.WIDTH_DEFAULT) { "$it" })
        look.addView(slider("Скругление углов", IvolPrefs.KEY_CORNER, 0, 16,
            p.getInt(IvolPrefs.KEY_CORNER, IvolPrefs.CORNER_DEFAULT), IvolPrefs.CORNER_DEFAULT) { "$it dp" })

        // Vibration: AOSP preference, read by SettingsValues.
        val dprefs = PreferenceManager.getDefaultSharedPreferences(this)
        look.addView(cardOf(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@IvolSettingsActivity).apply {
                setText("Вибрация при нажатии")
                setTextColor(cText)
                textSize = 16f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(this@IvolSettingsActivity).apply {
                isChecked = Settings.readVibrationEnabled(dprefs, resources)
                setOnCheckedChangeListener { _, on ->
                    dprefs.edit().putBoolean(IvolPrefs.AOSP_VIBRATE, on).apply()
                }
            })
        }))
        look.addView(slider("Сила вибрации", IvolPrefs.KEY_VIBRO, 1, 100,
            p.getInt(IvolPrefs.KEY_VIBRO, IvolPrefs.VIBRO_DEFAULT), IvolPrefs.VIBRO_DEFAULT,
            onStop = {
                // Let the user feel the new strength right away.
                IvolPrefs.vibrate(getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)
            }) { "$it%" })

        look.addView(TextView(this).apply {
            setText("Клавиатура внизу меняется сразу, пока двигаешь ползунки.")
            setTextColor(sub)
            textSize = 13f
            setPadding(dp(4), dp(8), dp(4), dp(16))
        })

        look.addView(button("Сбросить", false) {
            p.edit()
                .putInt(IvolPrefs.KEY_HEIGHT, IvolPrefs.HEIGHT_DEFAULT)
                .putInt(IvolPrefs.KEY_WIDTH, IvolPrefs.WIDTH_DEFAULT)
                .putInt(IvolPrefs.KEY_CORNER, IvolPrefs.CORNER_DEFAULT)
                .putInt(IvolPrefs.KEY_VIBRO, IvolPrefs.VIBRO_DEFAULT)
                .apply()
            seekBars.forEach { it.second() }
        })
        buildVoice(root)
        buildLanguages(root)
        buildAi(root)
        if (!updatesFirst) buildUpdates(root, false)

        root.addView(button("Расширенные настройки", false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(button("Готово", true) { finish() })

        return ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(root)
            scrollView = this
        }
    }

    // ---------------- AI: model connection + wand prompts ----------------

    private fun buildAi(root: LinearLayout) {
        val ap = IvolAiPrefs.prefs(this)

        val conn = section(root, "Подключение модели")
        aiHeader = conn.header
        aiOpen = conn.open
        conn.body.addView(note("Свой сервер в формате OpenAI. Им пользуется волшебная палочка " +
            "на панели клавиатуры. Ключ хранится только на телефоне."))

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label("Адрес сервера"))
        box.addView(field(ap.getString(IvolAiPrefs.KEY_BASE_URL, "")!!, IvolAiPrefs.URL_HINT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) {
            ap.edit().putString(IvolAiPrefs.KEY_BASE_URL, it.trim()).apply()
        })
        box.addView(label("Ключ API"))
        box.addView(field(ap.getString(IvolAiPrefs.KEY_API_KEY, "")!!, "sk-…",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            ap.edit().putString(IvolAiPrefs.KEY_API_KEY, it.trim()).apply()
        })

        box.addView(label("Модель"))
        val modelField = field(ap.getString(IvolAiPrefs.KEY_MODEL, "")!!, "выбери из списка или впиши",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) {
            ap.edit().putString(IvolAiPrefs.KEY_MODEL, it.trim()).apply()
        }
        box.addView(modelField)
        box.addView(smallButton("Выбрать модель с сервера") { pickModel(modelField) })

        box.addView(label("Уровень размышлений"))
        val reasoningView = TextView(this).apply {
            setTextColor(accent)
            textSize = 16f
            setPadding(0, dp(6), 0, dp(6))
            text = IvolAiPrefs.reasoningLabel(this@IvolSettingsActivity) + "  ▾"
        }
        reasoningView.setOnClickListener {
            val cur = IvolAiPrefs.REASONING_VALUES.indexOf(IvolAiPrefs.reasoning(this)).coerceAtLeast(0)
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Уровень размышлений")
                .setSingleChoiceItems(IvolAiPrefs.REASONING_LABELS, cur) { d, which ->
                    ap.edit().putString(IvolAiPrefs.KEY_REASONING, IvolAiPrefs.REASONING_VALUES[which]).apply()
                    reasoningView.text = IvolAiPrefs.REASONING_LABELS[which] + "  ▾"
                    d.dismiss()
                }
                .show()
        }
        box.addView(reasoningView)
        box.addView(note("Если модель не поддерживает размышления, оставь «не отправлять».", 0))
        conn.body.addView(cardOf(box))

        val wand = section(root, "Кнопки волшебной палочки").body
        wand.addView(note("У каждой кнопки свой промт: что модель делает с текстом. " +
            "Меняй под себя. Пустое поле — промт по умолчанию."))
        for (action in IvolAiPrefs.ACTIONS) {
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            b.addView(TextView(this).apply {
                text = action.label
                setTextColor(cText)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            })
            val promptField = field(IvolAiPrefs.prompt(this, action), action.defaultPrompt,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, multiLine = true) {
                ap.edit().putString(action.promptKey, it).apply()
            }
            b.addView(promptField)
            b.addView(smallButton("Вернуть промт по умолчанию") {
                ap.edit().remove(action.promptKey).apply()
                promptField.setText(action.defaultPrompt)
            })
            wand.addView(cardOf(b))
        }
    }

    // ---------------- Voice input model ----------------

    private fun buildVoice(root: LinearLayout) {
        val body = section(root, "Голосовой ввод").body
        body.addView(note("Модель распознаёт речь прямо на телефоне. Выбери, какой пользоваться; " +
            "ненужную можно удалить, чтобы не занимала место."))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voiceBox = box
        body.addView(cardOf(box))
        refreshVoice()
    }

    private var lastVoiceKey = ""

    private fun refreshVoice() {
        val box = voiceBox ?: return
        val downloading = GigaAmVoice.downloadingId
        val selected = GigaAmVoice.selected(this).id
        // Progress ticks come very often: during a download rebuild only when the visible state changes.
        val key = "$downloading:${GigaAmVoice.downloadProgress}:$selected"
        if (downloading != null && box.childCount > 0 && key == lastVoiceKey) return
        lastVoiceKey = key
        box.removeAllViews()
        for (e in GigaAmVoice.ENGINES) {
            val installed = GigaAmVoice.isInstalled(this, e)
            val used = GigaAmVoice.usedBytes(this, e)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val radio = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                isChecked = e.id == selected
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                setOnClickListener { GigaAmVoice.select(this@IvolSettingsActivity, e.id) }
            }
            row.addView(radio)
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = e.title
                setTextColor(cText)
                textSize = 16f
            })
            texts.addView(TextView(this).apply {
                text = e.subtitle + " · " + when {
                    downloading == e.id -> "скачивается ${GigaAmVoice.downloadProgress}%"
                    installed -> "скачана, ${GigaAmVoice.mb(used)}"
                    used > 0 -> "скачана частично (${GigaAmVoice.mb(used)} из ${GigaAmVoice.mb(e.size)})"
                    else -> "не скачана, ${GigaAmVoice.mb(e.size)}"
                }
                setTextColor(sub)
                textSize = 12f
            })
            texts.setOnClickListener { radio.performClick() }
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            when {
                downloading == e.id -> {}
                installed || used > 0 -> row.addView(smallButton("Удалить") { confirmDeleteVoice(e) }.apply {
                    setPadding(dp(12), dp(8), 0, dp(8))
                })
                else -> row.addView(smallButton("Скачать") {
                    GigaAmVoice.download(this@IvolSettingsActivity, e.id)
                }.apply { setPadding(dp(12), dp(8), 0, dp(8)) })
            }
            box.addView(row)
        }
    }

    private fun confirmDeleteVoice(e: GigaAmVoice.Engine) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Удалить ${e.title}?")
            .setMessage("Освободится ${GigaAmVoice.mb(GigaAmVoice.usedBytes(this, e))}. " +
                "Если снова выбрать эту модель, она скачается заново при нажатии на микрофон.")
            .setPositiveButton("Удалить") { _, _ -> GigaAmVoice.delete(this, e.id) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------- Keyboard languages ----------------

    private fun buildLanguages(root: LinearLayout) {
        val body = section(root, "Языки").body
        body.addView(note("Глобус на клавиатуре переключает только включённые языки. " +
            "Словарь подсказок для нового языка скачивается с GitHub при включении. " +
            "Эти же языки появляются при переводе."))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        langsBox = box
        body.addView(cardOf(box))
        refreshLanguages()
    }

    private fun refreshLanguages() {
        val box = langsBox ?: return
        box.removeAllViews()
        val enabled = IvolLanguages.enabled(this)
        for (loc in enabled) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = IvolLanguages.displayName(loc)
                setTextColor(cText)
                textSize = 16f
            })
            texts.addView(TextView(this).apply {
                text = IvolLanguages.status(this@IvolSettingsActivity, loc)
                setTextColor(sub)
                textSize = 12f
            })
            if (IvolLanguages.needsDownload(this, loc)) {
                texts.setOnClickListener { IvolLanguages.download(this, loc) }
            }
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Switch(this).apply {
                isChecked = true
                isEnabled = enabled.size > 1 // at least one language stays on
                setOnCheckedChangeListener { _, on ->
                    if (!on) IvolLanguages.setEnabled(this@IvolSettingsActivity, loc, false)
                }
            })
            box.addView(row)
        }
        box.addView(smallButton("+ Добавить язык") { pickLanguage() })
    }

    private fun pickLanguage() {
        val enabled = IvolLanguages.enabled(this)
        val ru = java.text.Collator.getInstance(java.util.Locale("ru"))
        val locs = IvolLanguages.locales(this).filter { it !in enabled }
            .sortedWith(compareBy(ru) { IvolLanguages.displayName(it) })
        if (locs.isEmpty()) return
        val labels = locs.map { loc ->
            val d = IvolLanguages.dictFor(loc)
            IvolLanguages.displayName(loc) + when {
                IvolLanguages.hasBuiltInDict(loc) -> ""
                d != null -> "  · " + IvolLanguages.mb(d.size)
                else -> "  · без подсказок"
            }
        }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Добавить язык")
            .setItems(labels) { d, which ->
                IvolLanguages.setEnabled(this, locs[which], true)
                d.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------- Updates from GitHub Releases ----------------

    private fun updateStatusText(): String {
        if (IvolUpdater.lastStatus.isNotEmpty()) return IvolUpdater.lastStatus
        val available = IvolUpdater.availableVersion(this)
        if (available != null) return "Есть версия $available — нажми «Проверить и обновить»"
        return "Установлена версия " + IvolUpdater.currentVersion()
    }

    private fun buildUpdates(root: LinearLayout, open: Boolean) {
        val s = section(root, "Обновления", open)
        updatesHeader = s.header
        updatesOpen = s.open
        val body = s.body
        body.addView(note("Раз в день, пока ты печатаешь, клавиатура смотрит релизы на GitHub " +
            "(${IvolUpdater.REPO}). Если вышла новая версия, справа на панели появится значок " +
            "обновления. Обновление ставится только по нажатию, настройки и словарь сохраняются."))

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@IvolSettingsActivity).apply {
                setText("Проверять автоматически")
                setTextColor(cText)
                textSize = 16f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(this@IvolSettingsActivity).apply {
                isChecked = IvolUpdater.isAutoEnabled(this@IvolSettingsActivity)
                setOnCheckedChangeListener { _, on ->
                    IvolUpdater.setAutoEnabled(this@IvolSettingsActivity, on)
                }
            })
        })
        val status = TextView(this).apply {
            setTextColor(sub)
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
            text = updateStatusText()
        }
        updateStatusView = status
        box.addView(status)
        box.addView(smallButton("Проверить и обновить") { IvolUpdater.checkNow(this) })
        body.addView(cardOf(box))
    }

    private fun pickModel(target: EditText) {
        Toast.makeText(this, "Загружаю список моделей…", Toast.LENGTH_SHORT).show()
        Thread {
            val res = try { Result.success(IvolAi.listModels(this)) } catch (e: Throwable) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val models = res.getOrNull()
                if (models == null) {
                    Toast.makeText(this, "Не удалось получить модели: " +
                        (res.exceptionOrNull()?.message ?: "ошибка"), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (models.isEmpty()) {
                    Toast.makeText(this, "Сервер вернул пустой список — впиши модель вручную",
                        Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val arr = models.toTypedArray()
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Модель")
                    .setSingleChoiceItems(arr, arr.indexOf(target.text.toString().trim())) { d, which ->
                        target.setText(arr[which]) // the watcher saves it
                        d.dismiss()
                    }
                    .show()
            }
        }.start()
    }

    private fun note(t: String, bottom: Int = 10): View = TextView(this).apply {
        text = t
        setTextColor(sub)
        textSize = 13f
        setPadding(dp(4), dp(2), dp(4), dp(bottom))
    }

    private fun label(t: String): View = TextView(this).apply {
        text = t
        setTextColor(sub)
        textSize = 13f
        setPadding(0, dp(10), 0, 0)
    }

    private fun field(
        value: String, hintText: String, type: Int, multiLine: Boolean = false,
        onChange: (String) -> Unit,
    ): EditText = EditText(this).apply {
        setText(value)
        hint = hintText
        setHintTextColor(sub)
        setTextColor(cText)
        textSize = 15f
        inputType = type
        if (multiLine) {
            isSingleLine = false
            minLines = 3
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
        } else {
            isSingleLine = true
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
    }

    private fun smallButton(t: String, onClick: () -> Unit): View = TextView(this).apply {
        text = t
        setTextColor(accent)
        textSize = 14f
        setPadding(0, dp(8), 0, dp(4))
        setOnClickListener { onClick() }
    }

    private fun slider(
        title: String, key: String, min: Int, max: Int, value: Int, def: Int,
        onStop: (() -> Unit)? = null,
        fmt: (Int) -> String,
    ): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply {
            setText(title); setTextColor(cText); textSize = 16f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = TextView(this).apply { setTextColor(accent); textSize = 16f }
        head.addView(valueView)
        box.addView(head)
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = value.coerceIn(min, max) - min
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        valueView.text = fmt(sb.progress + min)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, prog: Int, fromUser: Boolean) {
                valueView.text = fmt(prog + min)
                if (fromUser) IvolPrefs.prefs(this@IvolSettingsActivity).edit()
                    .putInt(key, prog + min).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { onStop?.invoke() }
        })
        seekBars.add(sb to { sb.progress = def - min })
        box.addView(sb)
        return cardOf(box)
    }

    private fun cardOf(v: View): View {
        v.setPadding(dp(16), dp(14), dp(16), dp(14))
        v.background = GradientDrawable().apply { setColor(card); cornerRadius = dp(12).toFloat() }
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        return v
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): View =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(if (primary) Color.parseColor("#161C20") else cText)
            background = GradientDrawable().apply {
                setColor(if (primary) accent else card); cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { bottomMargin = dp(10) }
            setOnClickListener { onClick() }
        }
}
