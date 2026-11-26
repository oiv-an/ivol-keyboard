package com.android.inputmethod.latin.ivol

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.android.inputmethod.latin.LatinIME
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * IVOL: on-device translation of the text in the field (Google ML Kit).
 *
 * Translates the selection, or the whole field if nothing is selected.
 * - Only Russian + English switched on: one tap, direction is automatic (RU <-> EN).
 * - More languages switched on (IVOL settings → «Языки»): the top bar shows the languages,
 *   the tap on one of them translates into it. The source language is detected automatically.
 * Translation dictionaries (~30 MB per language) are downloaded by ML Kit on first use.
 * Tap the translate button again right after (text unchanged) -> the original text is restored.
 */
object IvolTranslator {
    private const val TAG = "IvolTranslator"
    private const val MAX_CHARS = 5000

    private val main = Handler(Looper.getMainLooper())
    private val translators = HashMap<String, Translator>()
    @Volatile private var busy = false

    // last translation, for "tap again = undo"
    private var lastOriginal: String? = null
    private var lastResult: String? = null
    private var lastWasSelection = false

    private class Source(val text: String, val selection: Boolean, val before: Int, val after: Int)

    // ---------------- languages ----------------

    /** ML Kit language code for a keyboard locale ("pl_PL" -> "pl"), or null if not translatable. */
    @JvmStatic fun mlKitCode(locale: String): String? {
        val lang = when (val l = locale.substringBefore('_').lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            else -> l
        }
        return TranslateLanguage.fromLanguageTag(lang)
    }

    /** Translation targets = enabled keyboard languages that ML Kit can translate. */
    @JvmStatic fun targets(ctx: Context): List<String> =
        IvolLanguages.enabled(ctx).mapNotNull { mlKitCode(it) }.distinct()

    /** Short label for the top bar: "RU", "EN", "PL", "UA"... */
    @JvmStatic fun label(code: String): String = when (code) {
        "uk" -> "UA"
        else -> code.uppercase(Locale.ROOT)
    }

    private fun nameIn(code: String): String {
        val ru = Locale("ru")
        val n = Locale(code).getDisplayLanguage(ru)
        // "польский" -> "польский"; used as "Перевожу на польский…"
        return n.lowercase(ru)
    }

    // ---------------- buttons ----------------

    /**
     * Translate button in the top bar.
     * @return true when the bar has to show the language choice (more than two languages).
     */
    @JvmStatic fun onButton(ime: LatinIME): Boolean {
        if (busy) { toast(ime, "Перевожу…"); return false }
        val src = readSource(ime) ?: return false
        if (src.text.isBlank()) {
            toast(ime, "Напишите текст или выделите его — переведу")
            return false
        }
        // Tap again right after a translation: restore the original.
        val prevOriginal = lastOriginal
        if (prevOriginal != null && src.text == lastResult && src.selection == lastWasSelection) {
            replace(ime, src.selection, src.before, src.after, prevOriginal)
            lastOriginal = null; lastResult = null
            return false
        }
        val targets = targets(ime)
        if (targets.size > 2) return true
        // Two languages (default RU + EN): automatic direction.
        val a = targets.getOrNull(0) ?: TranslateLanguage.RUSSIAN
        val b = targets.getOrNull(1) ?: if (a == TranslateLanguage.ENGLISH) TranslateLanguage.RUSSIAN
            else TranslateLanguage.ENGLISH
        if (setOf(a, b) == setOf(TranslateLanguage.RUSSIAN, TranslateLanguage.ENGLISH)) {
            // Old proven heuristic, no language detection needed.
            val toEnglish = isMostlyCyrillic(src.text)
            start(ime, src,
                if (toEnglish) TranslateLanguage.RUSSIAN else TranslateLanguage.ENGLISH,
                if (toEnglish) TranslateLanguage.ENGLISH else TranslateLanguage.RUSSIAN)
            return false
        }
        detect(src.text, listOf(a, b)) { from ->
            start(ime, src, from, if (from == a) b else a)
        }
        return false
    }

    /** A language in the top bar was tapped. */
    @JvmStatic fun translateTo(ime: LatinIME, target: String) {
        if (busy) { toast(ime, "Перевожу…"); return }
        val src = readSource(ime) ?: return
        if (src.text.isBlank()) {
            toast(ime, "Напишите текст или выделите его — переведу")
            return
        }
        detect(src.text, targets(ime)) { from ->
            if (from == target) {
                toast(ime, "Текст уже на этом языке")
            } else {
                start(ime, src, from, target)
            }
        }
    }

    @JvmStatic fun release() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    // ---------------- internals ----------------

    private fun readSource(ime: LatinIME): Source? {
        try { ime.ivolCommitTypedForExternalInput() } catch (e: Throwable) { Log.w(TAG, "commitTyped", e) }
        val ic = ime.currentInputConnection ?: return null
        val selected = ic.getSelectedText(0)?.toString()
        val useSelection = !selected.isNullOrEmpty()
        val before = if (useSelection) "" else ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString() ?: ""
        val after = if (useSelection) "" else ic.getTextAfterCursor(MAX_CHARS, 0)?.toString() ?: ""
        return Source(if (useSelection) selected!! else before + after, useSelection,
            before.length, after.length)
    }

    /**
     * Detects the language of [text] (ML Kit language-id, on device), preferring the user's
     * languages ([prefer]). Falls back to the Cyrillic/Latin heuristic.
     */
    private fun detect(text: String, prefer: List<String>, done: (String) -> Unit) {
        val fallback = if (isMostlyCyrillic(text)) {
            if (prefer.contains("uk") && text.any { it in "іїєґІЇЄҐ" }) "uk" else TranslateLanguage.RUSSIAN
        } else TranslateLanguage.ENGLISH
        try {
            LanguageIdentification.getClient().identifyPossibleLanguages(text.take(1000))
                .addOnSuccessListener { list ->
                    val codes = list.sortedByDescending { it.confidence }
                        .map { it.languageTag.substringBefore('-') }
                    val pick = codes.firstOrNull { it in prefer }
                        ?: codes.firstOrNull { it != "und" && TranslateLanguage.fromLanguageTag(it) != null }
                        ?: fallback
                    Log.i(TAG, "detected $pick from $codes")
                    done(pick)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "language id failed", e)
                    done(fallback)
                }
        } catch (e: Throwable) {
            Log.w(TAG, "language id unavailable", e)
            done(fallback)
        }
    }

    private fun translator(from: String, to: String): Translator =
        translators.getOrPut("$from>$to") {
            Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(from).setTargetLanguage(to).build())
        }

    private fun start(ime: LatinIME, src: Source, from: String, to: String) {
        if (busy) return
        busy = true
        val tr = translator(from, to)
        Log.i(TAG, "translate ${src.text.length} chars $from -> $to, selection=${src.selection}")
        val mm = RemoteModelManager.getInstance()
        val needed = listOf(from, to).filter { it != TranslateLanguage.ENGLISH }
            .map { TranslateRemoteModel.Builder(it).build() }
        Tasks.whenAllComplete(needed.map { mm.isModelDownloaded(it) })
            .addOnCompleteListener { t ->
                val missing = t.result?.any { (it as Task<*>).let { r -> !r.isSuccessful || r.result != true } } ?: true
                toast(ime, if (missing) "Скачиваю словарь перевода (~30 МБ), это один раз…"
                    else "Перевожу на ${nameIn(to)}…")
                run(ime, src, tr)
            }
    }

    private fun run(ime: LatinIME, src: Source, tr: Translator) {
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .continueWithTask { t ->
                if (!t.isSuccessful) throw t.exception ?: IllegalStateException("model download failed")
                tr.translate(src.text)
            }
            .addOnSuccessListener { result ->
                busy = false
                Log.i(TAG, "done: ${result.length} chars")
                // Field must be unchanged since we read it.
                val ic2 = ime.currentInputConnection ?: return@addOnSuccessListener
                val nowSource = if (src.selection) ic2.getSelectedText(0)?.toString()
                    else (ic2.getTextBeforeCursor(MAX_CHARS, 0)?.toString() ?: "") +
                        (ic2.getTextAfterCursor(MAX_CHARS, 0)?.toString() ?: "")
                if (nowSource != src.text) { toast(ime, "Текст изменился — перевод отменён"); return@addOnSuccessListener }
                val b = if (src.selection) 0 else (ic2.getTextBeforeCursor(MAX_CHARS, 0)?.length ?: 0)
                val a = if (src.selection) 0 else (ic2.getTextAfterCursor(MAX_CHARS, 0)?.length ?: 0)
                replace(ime, src.selection, b, a, result)
                lastOriginal = src.text
                lastResult = result
                lastWasSelection = src.selection
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
                Character.UnicodeBlock.BASIC_LATIN, Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                Character.UnicodeBlock.LATIN_EXTENDED_A -> if (c.isLetter()) lat++
                else -> {}
            }
        }
        return cyr >= lat
    }

    private fun toast(ime: LatinIME, text: String) {
        main.post { Toast.makeText(ime.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }
}
