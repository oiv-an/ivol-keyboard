package com.android.inputmethod.latin.ivol

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * IVOL: last 10 copied texts. As the active IME we may read the clipboard, so we listen for
 * changes and keep the history on the phone (SharedPreferences, private to the app).
 */
object ClipboardHistory {
    private const val TAG = "IvolClipboard"
    private const val PREFS = "ivol_clipboard"
    private const val KEY = "items"
    const val MAX_ITEMS = 10
    private const val MAX_LEN = 5000

    private var appCtx: Context? = null
    private var cm: ClipboardManager? = null
    private val items = ArrayList<String>()
    @JvmStatic var ime: com.android.inputmethod.latin.LatinIME? = null
        private set

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        captureCurrent()
        checkFresh()
    }

    // IVOL: "fresh" clip (Yandex-like): copied a moment ago and not pasted yet -> the clipboard
    // button lights up and one tap pastes it right away.
    private const val FRESH_MS = 5 * 60 * 1000L
    private const val KEY_CONSUMED = "consumed"
    private const val KEY_CONSUMED_TS = "consumed_ts"
    @JvmStatic var freshText: String? = null
        private set
    private var freshAt = 0L
    private var lastSeenText: String? = null
    private var lastSeenAt = 0L
    @JvmStatic var onFreshChanged: Runnable? = null

    /** Paste [text] into the current field. */
    @JvmStatic fun paste(text: String) {
        ime?.ivolVoiceEdit(0, text)
        if (text == freshText) consumeFresh()
    }

    /** Re-read the clipboard and decide whether the button should light up. */
    @JvmStatic fun checkFresh() {
        val now = System.currentTimeMillis()
        var text: String? = null
        var ts = 0L
        try {
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                text = clip.getItemAt(0)?.coerceToText(appCtx)?.toString()?.take(MAX_LEN)
                if (android.os.Build.VERSION.SDK_INT >= 26) ts = clip.description?.timestamp ?: 0L
            }
        } catch (e: Throwable) {
            Log.w(TAG, "clipboard read failed", e)
        }
        if (text.isNullOrBlank()) {
            setFresh(null, 0L)
            return
        }
        if (ts <= 0L) {
            // No timestamp before Android 8: the moment we first saw this text.
            if (text != lastSeenText) { lastSeenText = text; lastSeenAt = now }
            ts = lastSeenAt
        }
        // Already pasted this very copy (same text + same copy time) -> not fresh any more.
        val p = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val consumed = p?.getString(KEY_CONSUMED, null) == text && p?.getLong(KEY_CONSUMED_TS, -1L) == ts
        val fresh = now - ts in 0..FRESH_MS && !consumed
        setFresh(if (fresh) text else null, ts)
    }

    /** True when a fresh clip exists (and is still fresh). */
    @JvmStatic fun hasFresh(): Boolean {
        if (freshText != null && System.currentTimeMillis() - freshAt > FRESH_MS) setFresh(null, 0L)
        return freshText != null
    }

    /** Tap on the lit clipboard button: paste the fresh clip. Returns false if nothing fresh. */
    @JvmStatic fun pasteFresh(): Boolean {
        if (!hasFresh()) return false
        val text = freshText ?: return false
        paste(text)
        return true
    }

    private fun consumeFresh() {
        val text = freshText ?: return
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY_CONSUMED, text)?.putLong(KEY_CONSUMED_TS, freshAt)?.apply()
        setFresh(null, 0L)
    }

    private fun setFresh(text: String?, at: Long) {
        val changed = text != freshText
        freshText = text
        freshAt = at
        if (changed) onFreshChanged?.run()
    }

    @JvmStatic fun init(service: com.android.inputmethod.latin.LatinIME) {
        ime = service
        if (appCtx != null) return
        val ctx: Context = service
        appCtx = ctx.applicationContext
        load()
        cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.addPrimaryClipChangedListener(listener)
    }

    @JvmStatic fun shutdown() {
        cm?.removePrimaryClipChangedListener(listener)
        cm = null
        appCtx = null
    }

    /** Newest first. */
    @JvmStatic fun list(): List<String> {
        captureCurrent()
        return ArrayList(items)
    }

    @JvmStatic fun remove(text: String) {
        if (items.remove(text)) save()
    }

    @JvmStatic fun clear() {
        items.clear()
        save()
    }

    private fun captureCurrent() {
        val text = try {
            cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                ?.coerceToText(appCtx)?.toString()
        } catch (e: Throwable) {
            Log.w(TAG, "clipboard read failed", e); null
        }
        add(text)
    }

    private fun add(raw: String?) {
        val text = raw?.take(MAX_LEN) ?: return
        if (text.isBlank()) return
        if (items.firstOrNull() == text) return
        items.remove(text)
        items.add(0, text)
        while (items.size > MAX_ITEMS) items.removeAt(items.size - 1)
        save()
    }

    private fun load() {
        items.clear()
        val json = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until minOf(arr.length(), MAX_ITEMS)) items.add(arr.getString(i))
        } catch (e: Throwable) {
            Log.w(TAG, "bad clipboard history", e)
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, arr.toString())?.apply()
    }
}
