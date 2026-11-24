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

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { captureCurrent() }

    /** Paste [text] into the current field. */
    @JvmStatic fun paste(text: String) {
        ime?.ivolVoiceEdit(0, text)
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
