package com.android.inputmethod.latin.ivol

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * IVOL: clipboard panel shown over the keys (Yandex-like): header + last 10 copied texts.
 * Tap = paste and close, long tap = delete item.
 */
class ClipboardPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context)
    private val density = resources.displayMetrics.density

    init {
        setBackgroundColor(BG)
        isClickable = true // swallow touches so keys below are not pressed
        visibility = GONE
        scroll.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.isVerticalScrollBarEnabled = false
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        current = this
    }

    override fun onDetachedFromWindow() {
        if (current === this) current = null
        super.onDetachedFromWindow()
    }

    fun toggle() = if (visibility == VISIBLE) hide() else show()

    fun show() {
        rebuild()
        scroll.scrollTo(0, 0)
        visibility = VISIBLE
    }

    fun hide() {
        visibility = GONE
    }

    private fun dp(v: Float) = (v * density).toInt()

    private fun rebuild() {
        list.removeAllViews()
        val items = ClipboardHistory.list()

        // header: title ... Очистить  ✕
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(4f), dp(4f), dp(4f))
        }
        header.addView(TextView(context).apply {
            text = "Буфер обмена"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (items.isNotEmpty()) {
            header.addView(headerButton("Очистить") { ClipboardHistory.clear(); rebuild() })
        }
        header.addView(headerButton("✕") { hide() })
        list.addView(header)

        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Здесь появятся последние 10 скопированных фрагментов"
                setTextColor(HINT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(dp(16f), dp(32f), dp(16f), dp(32f))
            })
            return
        }
        for (text in items) {
            val item = TextView(context).apply {
                this.text = text.replace('\n', ' ')
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                background = itemBackground()
                setOnClickListener {
                    ClipboardHistory.paste(text)
                    hide()
                }
                setOnLongClickListener {
                    ClipboardHistory.remove(text)
                    rebuild()
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(8f), dp(3f), dp(8f), dp(3f))
            list.addView(item, lp)
        }
        list.addView(TextView(context).apply {
            text = "Нажмите — вставить, удерживайте — удалить"
            setTextColor(HINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, dp(10f))
        })
    }

    private fun headerButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        setOnClickListener { onClick() }
    }

    private fun itemBackground(): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 8f * density
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(KEY_PRESSED))
            addState(intArrayOf(), shape(KEY))
        }
    }

    companion object {
        private val BG = Color.parseColor("#161C20")
        private val KEY = Color.parseColor("#242B30")
        private val KEY_PRESSED = Color.parseColor("#3A4247")
        private val TEXT = Color.parseColor("#DCE3E9")
        private val HINT = Color.parseColor("#8A949A")

        @JvmStatic var current: ClipboardPanelView? = null
            private set

        @JvmStatic fun toggleCurrent() { current?.toggle() }
        @JvmStatic fun hideCurrent() { current?.hide() }
    }
}
