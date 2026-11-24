package com.android.inputmethod.latin.ivol

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/**
 * IVOL: own keyboard look settings (one simple screen): height, key width (gaps), corner rounding.
 * Vibration uses AOSP's own "vibrate_on" preference.
 */
object IvolPrefs {
    const val NAME = "ivol_settings"
    const val KEY_HEIGHT = "height_percent"
    const val KEY_WIDTH = "key_width"
    const val KEY_CORNER = "corner_dp"
    const val KEY_VIBRO = "vibro_strength"
    const val VIBRO_DEFAULT = 30   // 1..100
    const val AOSP_VIBRATE = "vibrate_on"

    const val HEIGHT_DEFAULT = 100 // % of default keyboard height, 80..130
    const val WIDTH_DEFAULT = 50   // 0 = narrow keys (2x gaps) .. 100 = no gaps
    const val CORNER_DEFAULT = 4   // dp, 0..16

    @JvmStatic var heightPercent = HEIGHT_DEFAULT
        private set
    @JvmStatic var widthValue = WIDTH_DEFAULT
        private set
    @JvmStatic var cornerDp = CORNER_DEFAULT
        private set
    @JvmStatic var vibroStrength = VIBRO_DEFAULT
        private set

    /** Vibration length for the current strength: ~4 ms (barely) .. ~30 ms (strong). */
    @JvmStatic fun vibroMs(): Long = (3 + vibroStrength * 27 / 100).toLong().coerceAtLeast(3)

    /** Amplitude 1..255 (used only if the motor supports amplitude control). */
    @JvmStatic fun vibroAmplitude(): Int = (vibroStrength * 255 / 100).coerceIn(1, 255)

    /** One key-press vibration with the user's strength. */
    @JvmStatic fun vibrate(v: android.os.Vibrator?) {
        if (v == null) return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val amp = if (v.hasAmplitudeControl()) vibroAmplitude()
                else android.os.VibrationEffect.DEFAULT_AMPLITUDE
            v.vibrate(android.os.VibrationEffect.createOneShot(vibroMs(), amp))
        } else {
            @Suppress("DEPRECATION") v.vibrate(vibroMs())
        }
    }

    /** Set when the settings changed and the keyboard view must be rebuilt. */
    @JvmStatic @Volatile var dirty = false

    /** Called (main thread) after a change, so a visible keyboard can rebuild live. */
    @JvmStatic var onChange: Runnable? = null

    private var listening: SharedPreferences? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        read(p)
        if (key == KEY_VIBRO) return@OnSharedPreferenceChangeListener // no rebuild needed
        dirty = true
        onChange?.run()
    }

    @JvmStatic fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    @JvmStatic fun init(ctx: Context) {
        val p = prefs(ctx)
        read(p)
        if (listening == null) {
            listening = p
            p.registerOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun read(p: SharedPreferences) {
        heightPercent = p.getInt(KEY_HEIGHT, HEIGHT_DEFAULT).coerceIn(80, 130)
        widthValue = p.getInt(KEY_WIDTH, WIDTH_DEFAULT).coerceIn(0, 100)
        cornerDp = p.getInt(KEY_CORNER, CORNER_DEFAULT).coerceIn(0, 16)
        vibroStrength = p.getInt(KEY_VIBRO, VIBRO_DEFAULT).coerceIn(1, 100)
    }

    @JvmStatic fun heightScale(): Float = heightPercent / 100f

    /** Multiplier for the horizontal gap between keys: 2.0 (narrow keys) .. 0.0 (no gaps). */
    @JvmStatic fun gapScale(): Float = (100 - widthValue) / 50f

    /** Applies the corner radius to our shape-based key backgrounds (state lists / layer lists). */
    @JvmStatic fun applyCorners(ctx: Context, d: Drawable?): Drawable? {
        if (d == null) return null
        val px = cornerDp * ctx.resources.displayMetrics.density
        val m = d.mutate()
        walk(m, px)
        return m
    }

    private fun walk(d: Drawable?, px: Float) {
        when (d) {
            null -> {}
            is GradientDrawable -> d.cornerRadius = px
            is LayerDrawable -> for (i in 0 until d.numberOfLayers) walk(d.getDrawable(i), px)
            is DrawableContainer -> {
                val state = d.constantState as? DrawableContainer.DrawableContainerState ?: return
                state.children?.forEach { walk(it, px) }
            }
        }
    }
}
