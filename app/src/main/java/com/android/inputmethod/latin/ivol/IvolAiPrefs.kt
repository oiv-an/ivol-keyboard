package com.android.inputmethod.latin.ivol

import android.content.Context
import android.content.SharedPreferences
import com.android.inputmethod.latin.BuildConfig

/**
 * IVOL: settings of the AI (magic wand) button — an OpenAI-compatible server added by the user.
 *
 * Nothing is built in: the user types the base URL in the OpenAI format
 * (for example https://api.openai.com/v1), the API key, picks a model from the server's
 * /models list and a reasoning level. Each wand action has its own prompt the user can edit.
 */
object IvolAiPrefs {
    const val NAME = "ivol_ai"

    const val KEY_BASE_URL = "base_url"
    const val KEY_API_KEY = "api_key"
    const val KEY_MODEL = "model"
    const val KEY_REASONING = "reasoning"

    const val URL_HINT = "https://api.openai.com/v1"

    /** reasoning_effort values; "" = do not send the parameter (non-reasoning models). */
    val REASONING_VALUES = arrayOf("", "minimal", "low", "medium", "high")
    val REASONING_LABELS = arrayOf("не отправлять", "минимальный", "низкий", "средний", "высокий")

    /** One magic-wand action: a button in the panel plus its own editable prompt. */
    class Action(
        val id: String,
        val label: String,
        val hint: String,
        val defaultPrompt: String,
    ) {
        val promptKey: String get() = "prompt_$id"
    }

    @JvmField val ACTION_FORMAT = Action(
        "format", "Оформить", "Орфография, пунктуация, регистр",
        "Исправь орфографию, пунктуацию, регистр и пробелы в тексте. " +
            "Не меняй смысл, стиль, порядок слов и язык текста. " +
            "Верни только исправленный текст, без пояснений и без кавычек."
    )

    @JvmField val ACTION_REWRITE = Action(
        "rewrite", "Переписать", "Сделать текст понятнее",
        "Перепиши текст так, чтобы он читался понятнее и аккуратнее. " +
            "Сохрани смысл, язык текста и его тон. " +
            "Верни только новый текст, без пояснений и без кавычек."
    )

    @JvmField val ACTION_TRANSLATE = Action(
        "translate", "Перевести через модель", "Русский → английский, иначе → русский",
        "Переведи текст: если текст на русском — на английский, в остальных случаях — на русский. " +
            "Сохрани форматирование и переносы строк. " +
            "Верни только перевод, без пояснений и без кавычек."
    )

    /** Order of the buttons in the wand panel: the main action is first. */
    @JvmField val ACTIONS = arrayOf(ACTION_FORMAT, ACTION_REWRITE, ACTION_TRANSLATE)

    /**
     * Build defaults from the git-ignored ai-dev.properties (empty in a clean clone).
     * They prefill the settings screen so a developer build works right away.
     */
    @JvmStatic fun prefs(ctx: Context): SharedPreferences {
        val p = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_SEEDED, false)) {
            val e = p.edit().putBoolean(KEY_SEEDED, true)
            if (BuildConfig.IVOL_AI_BASE_URL.isNotEmpty()) {
                e.putString(KEY_BASE_URL, BuildConfig.IVOL_AI_BASE_URL)
                    .putString(KEY_API_KEY, BuildConfig.IVOL_AI_API_KEY)
                    .putString(KEY_MODEL, BuildConfig.IVOL_AI_MODEL)
                    .putString(KEY_REASONING, BuildConfig.IVOL_AI_REASONING)
            }
            e.apply()
        }
        return p
    }

    private const val KEY_SEEDED = "seeded"

    @JvmStatic fun baseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_BASE_URL, "")!!.trim().trimEnd('/')

    @JvmStatic fun apiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_API_KEY, "")!!.trim()

    @JvmStatic fun model(ctx: Context): String =
        prefs(ctx).getString(KEY_MODEL, "")!!.trim()

    @JvmStatic fun reasoning(ctx: Context): String =
        prefs(ctx).getString(KEY_REASONING, "")!!.trim()

    @JvmStatic fun prompt(ctx: Context, action: Action): String {
        val saved = prefs(ctx).getString(action.promptKey, null)
        return if (saved.isNullOrBlank()) action.defaultPrompt else saved
    }

    @JvmStatic fun isConfigured(ctx: Context): Boolean =
        baseUrl(ctx).isNotEmpty() && apiKey(ctx).isNotEmpty() && model(ctx).isNotEmpty()

    @JvmStatic fun reasoningLabel(ctx: Context): String {
        val v = reasoning(ctx)
        val i = REASONING_VALUES.indexOf(v)
        return REASONING_LABELS[if (i < 0) 0 else i]
    }
}
