package com.android.inputmethod.latin.ivol

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.android.inputmethod.latin.LatinIME
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * IVOL: magic-wand actions through the user's own OpenAI-compatible server
 * (base URL + key + model + reasoning level are set in the settings screen).
 *
 * Every action sends the text of the field (or the selection) with its own editable prompt
 * and replaces the text with the answer. Pressing the same action again right after
 * (text unchanged) restores the original.
 */
object IvolAi {
    private const val TAG = "IvolAi"
    private const val MAX_CHARS = 8000
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 120000

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var busy = false
    @JvmStatic fun isBusy(): Boolean = busy

    // last run, for "press again = undo"
    private var lastOriginal: String? = null
    private var lastResult: String? = null
    private var lastWasSelection = false

    /** Runs a wand action on the current field. [onDone] is called on the main thread. */
    @JvmStatic fun run(ime: LatinIME, action: IvolAiPrefs.Action, onDone: Runnable?) {
        if (busy) { toast(ime, "Модель ещё отвечает…"); onDone?.run(); return }
        if (!IvolAiPrefs.isConfigured(ime)) {
            toast(ime, "Сначала добавь сервер, ключ и модель в настройках")
            onDone?.run()
            return
        }
        try { ime.ivolCommitTypedForExternalInput() } catch (e: Throwable) { Log.w(TAG, "commitTyped", e) }
        val ic = ime.currentInputConnection
        if (ic == null) { onDone?.run(); return }
        val selected = ic.getSelectedText(0)?.toString()
        val useSelection = !selected.isNullOrEmpty()
        val before = if (useSelection) "" else ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString() ?: ""
        val after = if (useSelection) "" else ic.getTextAfterCursor(MAX_CHARS, 0)?.toString() ?: ""
        val source = if (useSelection) selected!! else before + after
        if (source.isBlank()) {
            toast(ime, "Напиши текст или выдели его — модель его обработает")
            onDone?.run()
            return
        }

        // Press again right after a run: restore the original text.
        val prevOriginal = lastOriginal
        if (prevOriginal != null && source == lastResult && useSelection == lastWasSelection) {
            replace(ime, useSelection, before.length, after.length, prevOriginal)
            lastOriginal = null; lastResult = null
            onDone?.run()
            return
        }

        val prompt = IvolAiPrefs.prompt(ime, action)
        val model = IvolAiPrefs.model(ime)
        val reasoning = IvolAiPrefs.reasoning(ime)
        val url = IvolAiPrefs.baseUrl(ime)
        val key = IvolAiPrefs.apiKey(ime)
        busy = true
        toast(ime, action.label + "…")
        Thread {
            var result: String? = null
            var error: String? = null
            try {
                result = chat(url, key, model, reasoning, prompt, source)
            } catch (e: Throwable) {
                Log.e(TAG, "ai failed", e)
                error = e.message ?: e.javaClass.simpleName
            }
            val text = result?.trim()
            main.post {
                busy = false
                onDone?.run()
                if (text.isNullOrEmpty()) {
                    toast(ime, "Модель не ответила: " + (error ?: "пустой ответ"))
                    return@post
                }
                val ic2 = ime.currentInputConnection ?: return@post
                val nowSource = if (useSelection) ic2.getSelectedText(0)?.toString()
                    else (ic2.getTextBeforeCursor(MAX_CHARS, 0)?.toString() ?: "") +
                        (ic2.getTextAfterCursor(MAX_CHARS, 0)?.toString() ?: "")
                if (nowSource != source) { toast(ime, "Текст изменился — замена отменена"); return@post }
                val b = if (useSelection) 0 else (ic2.getTextBeforeCursor(MAX_CHARS, 0)?.length ?: 0)
                val a = if (useSelection) 0 else (ic2.getTextAfterCursor(MAX_CHARS, 0)?.length ?: 0)
                replace(ime, useSelection, b, a, text)
                lastOriginal = source
                lastResult = text
                lastWasSelection = useSelection
            }
        }.start()
    }

    /** POST {base}/chat/completions, returns the assistant message. Blocking. */
    private fun chat(
        base: String, key: String, model: String, reasoning: String,
        systemPrompt: String, userText: String,
    ): String {
        val body = JSONObject()
        body.put("model", model)
        body.put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userText)))
        if (reasoning.isNotEmpty()) body.put("reasoning_effort", reasoning)
        val json = post(base + "/chat/completions", key, body.toString())
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) throw IllegalStateException(errorOf(json))
        val msg = choices.getJSONObject(0).optJSONObject("message")
            ?: throw IllegalStateException("нет message в ответе")
        val content = msg.opt("content")
        if (content is String) return content
        // Some servers answer with a content parts array.
        if (content is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                sb.append(part.optString("text", ""))
            }
            return sb.toString()
        }
        return ""
    }

    /** GET {base}/models — the list for the settings screen. Blocking. */
    @JvmStatic fun listModels(ctx: Context): List<String> {
        val base = IvolAiPrefs.baseUrl(ctx)
        val key = IvolAiPrefs.apiKey(ctx)
        if (base.isEmpty() || key.isEmpty()) throw IllegalStateException("нужны адрес сервера и ключ")
        val json = get("$base/models", key)
        val data = json.optJSONArray("data") ?: throw IllegalStateException(errorOf(json))
        val out = ArrayList<String>(data.length())
        for (i in 0 until data.length()) {
            val id = data.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotEmpty()) out.add(id)
        }
        out.sort()
        return out
    }

    private fun errorOf(json: JSONObject): String {
        val err = json.opt("error")
        if (err is JSONObject) return err.optString("message", err.toString())
        if (err is String) return err
        return json.optString("message", "неожиданный ответ сервера")
    }

    private fun get(url: String, key: String): JSONObject = request(url, key, null)

    private fun post(url: String, key: String, body: String): JSONObject = request(url, key, body)

    private fun request(url: String, key: String, body: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = if (body == null) "GET" else "POST"
            c.connectTimeout = CONNECT_TIMEOUT
            c.readTimeout = READ_TIMEOUT
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            val json = try { JSONObject(text) } catch (e: Throwable) {
                throw IllegalStateException("HTTP $code: " + text.take(200))
            }
            if (code !in 200..299) throw IllegalStateException("HTTP $code: " + errorOf(json))
            return json
        } finally {
            c.disconnect()
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

    private fun toast(ime: LatinIME, text: String) {
        main.post { Toast.makeText(ime.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }
}
