package com.android.inputmethod.latin.ivol

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.android.inputmethod.latin.LatinIME
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * IVOL: on-device RU<->EN translation of the text in the field (Google ML Kit).
 *
 * Tap the translate button: translates the selection, or the whole field if nothing is selected.
 * Direction is automatic: mostly Cyrillic -> English, otherwise -> Russian.
 * Tap again right after (text unchanged) -> the original text is restored.
 */
object IvolTranslator {
    private const val TAG = "IvolTranslator"
    private const val MAX_CHARS = 5000

    private val main = Handler(Looper.getMainLooper())
    private var ruEn: Translator? = null
    private var enRu: Translator? = null
    @Volatile private var busy = false

    // last translation, for "tap again = undo"
    private var lastOriginal: String? = null
    private var lastResult: String? = null
    private var lastWasSelection = false

    private fun translator(toEnglish: Boolean): Translator {
        if (toEnglish) {
            ruEn?.let { return it }
            return Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.RUSSIAN)
                .setTargetLanguage(TranslateLanguage.ENGLISH).build()).also { ruEn = it }
        }
        enRu?.let { return it }
        return Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.RUSSIAN).build()).also { enRu = it }
    }

    @JvmStatic fun release() {
        ruEn?.close(); ruEn = null
        enRu?.close(); enRu = null
    }

    @JvmStatic fun onButton(ime: LatinIME) {
        if (busy) { toast(ime, "Перевожу…"); return }
        try { ime.ivolCommitTypedForExternalInput() } catch (e: Throwable) { Log.w(TAG, "commitTyped", e) }
        val ic = ime.currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()
        val useSelection = !selected.isNullOrEmpty()
        val before = if (useSelection) "" else ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString() ?: ""
        val after = if (useSelection) "" else ic.getTextAfterCursor(MAX_CHARS, 0)?.toString() ?: ""
        val source = if (useSelection) selected!! else before + after
        if (source.isBlank()) { toast(ime, "Напишите текст или выделите его — переведу RU ↔ EN"); return }

        // Tap again right after a translation: restore the original.
        val prevOriginal = lastOriginal
        if (prevOriginal != null && source == lastResult && useSelection == lastWasSelection) {
            replace(ime, useSelection, before.length, after.length, prevOriginal)
            lastOriginal = null; lastResult = null
            return
        }

        val toEnglish = isMostlyCyrillic(source)
        val tr = translator(toEnglish)
        busy = true
        Log.i(TAG, "translate ${source.length} chars, toEnglish=$toEnglish, selection=$useSelection")
        toast(ime, if (toEnglish) "Перевожу на английский…" else "Перевожу на русский…")
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .continueWithTask { t ->
                if (!t.isSuccessful) throw t.exception ?: IllegalStateException("model download failed")
                tr.translate(source)
            }
            .addOnSuccessListener { result ->
                busy = false
                Log.i(TAG, "done: ${result.length} chars")
                // Field must be unchanged since we read it.
                val ic2 = ime.currentInputConnection ?: return@addOnSuccessListener
                val nowSource = if (useSelection) ic2.getSelectedText(0)?.toString()
                    else (ic2.getTextBeforeCursor(MAX_CHARS, 0)?.toString() ?: "") +
                        (ic2.getTextAfterCursor(MAX_CHARS, 0)?.toString() ?: "")
                if (nowSource != source) { toast(ime, "Текст изменился — перевод отменён"); return@addOnSuccessListener }
                val b = if (useSelection) 0 else (ic2.getTextBeforeCursor(MAX_CHARS, 0)?.length ?: 0)
                val a = if (useSelection) 0 else (ic2.getTextAfterCursor(MAX_CHARS, 0)?.length ?: 0)
                replace(ime, useSelection, b, a, result)
                lastOriginal = source
                lastResult = result
                lastWasSelection = useSelection
            }
            .addOnFailureListener { e ->
                busy = false
                Log.e(TAG, "translate failed", e)
                toast(ime, "Не удалось перевести: нужен интернет для первой загрузки словаря (~30 МБ)")
            }
    }

    private fun replace(ime: LatinIME, selection: Boolean, before: Int, after: Int, text: String) {
        val ic = ime.currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            if (!selection) ic.deleteSurroundingText(before, after)
            ic.commitText(text, 1) // replaces the selection when there is one
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun isMostlyCyrillic(s: String): Boolean {
        var cyr = 0
        var lat = 0
        for (c in s) {
            when (Character.UnicodeBlock.of(c)) {
                Character.UnicodeBlock.CYRILLIC -> cyr++
                Character.UnicodeBlock.BASIC_LATIN -> if (c.isLetter()) lat++
                else -> {}
            }
        }
        return cyr >= lat
    }

    private fun toast(ime: LatinIME, text: String) {
        main.post { Toast.makeText(ime.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }
}
