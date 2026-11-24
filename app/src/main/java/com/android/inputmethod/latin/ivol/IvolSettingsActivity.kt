package com.android.inputmethod.latin.ivol

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.preference.PreferenceManager
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
import com.android.inputmethod.latin.settings.Settings
import com.android.inputmethod.latin.settings.SettingsActivity

/** IVOL: one simple settings screen (height, key width, corner rounding, vibration). */
class IvolSettingsActivity : Activity() {

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

        // Preview field: keeps our keyboard open, it rebuilds live while sliders move.
        val preview = EditText(this).apply {
            hint = "Попробуй напечатать здесь"
            setHintTextColor(sub)
            setTextColor(cText)
            textSize = 16f
            isSingleLine = true
        }
        root.addView(cardOf(preview))
        preview.requestFocus()

        val p = IvolPrefs.prefs(this)
        root.addView(slider("Высота клавиатуры", IvolPrefs.KEY_HEIGHT, 80, 130,
            p.getInt(IvolPrefs.KEY_HEIGHT, IvolPrefs.HEIGHT_DEFAULT), IvolPrefs.HEIGHT_DEFAULT) { "$it%" })
        root.addView(slider("Ширина клавиш", IvolPrefs.KEY_WIDTH, 0, 100,
            p.getInt(IvolPrefs.KEY_WIDTH, IvolPrefs.WIDTH_DEFAULT), IvolPrefs.WIDTH_DEFAULT) { "$it" })
        root.addView(slider("Скругление углов", IvolPrefs.KEY_CORNER, 0, 16,
            p.getInt(IvolPrefs.KEY_CORNER, IvolPrefs.CORNER_DEFAULT), IvolPrefs.CORNER_DEFAULT) { "$it dp" })

        // Vibration: AOSP preference, read by SettingsValues.
        val dprefs = PreferenceManager.getDefaultSharedPreferences(this)
        root.addView(cardOf(LinearLayout(this).apply {
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
        root.addView(slider("Сила вибрации", IvolPrefs.KEY_VIBRO, 1, 100,
            p.getInt(IvolPrefs.KEY_VIBRO, IvolPrefs.VIBRO_DEFAULT), IvolPrefs.VIBRO_DEFAULT,
            onStop = {
                // Let the user feel the new strength right away.
                IvolPrefs.vibrate(getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)
            }) { "$it%" })

        root.addView(TextView(this).apply {
            setText("Клавиатура внизу меняется сразу, пока двигаешь ползунки.")
            setTextColor(sub)
            textSize = 13f
            setPadding(dp(4), dp(8), dp(4), dp(16))
        })

        root.addView(button("Сбросить", false) {
            p.edit()
                .putInt(IvolPrefs.KEY_HEIGHT, IvolPrefs.HEIGHT_DEFAULT)
                .putInt(IvolPrefs.KEY_WIDTH, IvolPrefs.WIDTH_DEFAULT)
                .putInt(IvolPrefs.KEY_CORNER, IvolPrefs.CORNER_DEFAULT)
                .putInt(IvolPrefs.KEY_VIBRO, IvolPrefs.VIBRO_DEFAULT)
                .apply()
            seekBars.forEach { it.second() }
        })
        root.addView(button("Расширенные настройки", false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(button("Готово", true) { finish() })

        return ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(root)
        }
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
