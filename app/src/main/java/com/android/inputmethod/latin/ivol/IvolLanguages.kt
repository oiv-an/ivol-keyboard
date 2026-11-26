package com.android.inputmethod.latin.ivol

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.android.inputmethod.dictionarypack.DictionaryPackConstants
import com.android.inputmethod.latin.BinaryDictionaryGetter
import com.android.inputmethod.latin.utils.DictionaryInfoUtils
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/**
 * IVOL: keyboard languages.
 *
 * All AOSP languages are declared in res/xml/method.xml (layouts are tiny XML, already in the APK),
 * but only the languages switched on here are used by the globe key. Default: Russian + English.
 * Russian and English dictionaries are built in; dictionaries of other languages are downloaded
 * from the GitHub release [DICT_TAG] of [IvolUpdater.REPO] when the language is switched on, and
 * verified by size + SHA-256. Files go to the AOSP word list cache (files/dicts/<locale>/), which
 * BinaryDictionaryGetter already reads.
 */
object IvolLanguages {
    private const val TAG = "IvolLanguages"
    private const val PREFS = "ivol_langs"
    private const val KEY_ENABLED = "enabled"
    @JvmField val DEFAULT_ENABLED: Set<String> = linkedSetOf("ru", "en_US")

    const val DICT_TAG = "dicts-v1"
    private const val DICT_URL = "https://github.com/${IvolUpdater.REPO}/releases/download/$DICT_TAG/"

    class Dict(val file: String, val size: Long, val sha256: String) {
        /** Locale of the dictionary itself: main_pt_br -> pt_BR. */
        val dictLocale: String
            get() {
                val p = file.removePrefix("main_").split('_')
                return p[0] + p.drop(1).joinToString("") { "_" + it.uppercase(Locale.ROOT) }
            }
    }

    /** Subtype locale -> downloadable dictionary (AOSP word lists, mirrored to GitHub). */
    private val DICTS: Map<String, Dict> = mapOf(
        "ar" to Dict("main_ar", 948090L, "61c7351bceceb44f01de6ef9ad7e4e26afa3e135da5930531fb6d4dc2f5b7480"),
        "az_AZ" to Dict("main_az", 339140L, "91025ccdf1c92beaab95a8307080cddb0950f0780595eace21e8bfc5da379df7"),
        "be_BY" to Dict("main_be", 16746960L, "0d02046c84e14c1a9fd541c9be869c4e558e40ae706c20b7667c2df36fc72b80"),
        "bg" to Dict("main_bg", 3392445L, "4a0eb372dd8bfe11b6a126e0e2801f035156ec9822d5f736368930df467fd4b6"),
        "bn_BD" to Dict("main_bn", 1527167L, "dd555eee57aceec08200cc293ffafd29a13bb531156e3a36e5a16ff6895d13a6"),
        "bn_IN" to Dict("main_bn", 1527167L, "dd555eee57aceec08200cc293ffafd29a13bb531156e3a36e5a16ff6895d13a6"),
        "ca" to Dict("main_ca", 418913L, "30657ba9c7604c560963e6107b170ae1fb1fa985a24c9688289d51d277e1af06"),
        "cs" to Dict("main_cs", 1017987L, "d2317360a5af36b9db484892cbb2975d53cd40c01b9e8a2a1f23251be80cfa86"),
        "da" to Dict("main_da", 1304047L, "7eca55127764b36d7823b7ff86b4e2a0dabbf76bfc0d5659df657c6ea0f24f13"),
        "de" to Dict("main_de", 10741643L, "14e94e229ba51fce0f40cab8a7dcfec4085c183712898f8a14d2016478ff6d1b"),
        "de_CH" to Dict("main_de_ch", 1613300L, "f621788f28eda8531d63e8ef32dc833075e76b5688d348ffa4b12dfaa80d849e"),
        "el" to Dict("main_el", 8548109L, "bc93c0b59c4059442c9b451f213aec5d83e8c389736cf325de387bdd4770e1ae"),
        "eo" to Dict("main_eo", 190378L, "b1e4ead01631d578bcae538d40cdb7a17fb4ee2721814e363dfeaf404765ae69"),
        "es" to Dict("main_es", 1377071L, "44a048bdb352b7b3f3d07a86ad1fb1692052b5a7838a57bc39caaae6181fcbad"),
        "es_419" to Dict("main_es", 1377071L, "44a048bdb352b7b3f3d07a86ad1fb1692052b5a7838a57bc39caaae6181fcbad"),
        "es_US" to Dict("main_es", 1377071L, "44a048bdb352b7b3f3d07a86ad1fb1692052b5a7838a57bc39caaae6181fcbad"),
        "eu_ES" to Dict("main_eu", 644407L, "1e147d575efda14bf17659f255395780a766fde14aca316b8a06065d04fcbdd9"),
        "fi" to Dict("main_fi", 1591913L, "87bc832f9b0f191907f1d08a12527a2c0e8854e23db807fa28af1936a4c0a87a"),
        "fr" to Dict("main_fr", 1328933L, "6cc7bc856de7928606ec974a17887f80848c7692560824ab05153a48cb193c94"),
        "fr_CA" to Dict("main_fr", 1328933L, "6cc7bc856de7928606ec974a17887f80848c7692560824ab05153a48cb193c94"),
        "fr_CH" to Dict("main_fr", 1328933L, "6cc7bc856de7928606ec974a17887f80848c7692560824ab05153a48cb193c94"),
        "gl_ES" to Dict("main_gl", 354419L, "91da6b79b1fd124b85fa64095ed90b407bcb037349939ea33183fea591f927f2"),
        "hi" to Dict("main_hi", 526586L, "57d1e2e7d0b08beec4d540952d0e538698c5d82f4e6a2cdba938800bfdc4a80d"),
        "hi_ZZ" to Dict("main_hi_zz", 1339914L, "928601773e693660e831aacd21d14e803289cfcc70ae309c7bafa2c3572014fc"),
        "hr" to Dict("main_hr", 1166744L, "3c1bb98883ac5bc96a96e206972b5fc8c4a04ad8992d26c4d365ae0cdcc8f686"),
        "hu" to Dict("main_hu", 426868L, "9937ec9a8929536ba6dea19f92e984b8a4aaea82d385dcbe239f9586bca415d8"),
        "hy_AM" to Dict("main_hy", 2076204L, "316e9a7e610b381f271926f3bd38d7bffd640befda99129f5f1092fa6b19a04a"),
        "it" to Dict("main_it", 1143338L, "fa9841c4a10fad7a422a61c84fb1c8bd7618b5b9d2debddaa84e1e95f841c1e8"),
        "it_CH" to Dict("main_it", 1143338L, "fa9841c4a10fad7a422a61c84fb1c8bd7618b5b9d2debddaa84e1e95f841c1e8"),
        "iw" to Dict("main_iw", 779557L, "7cf81b85f8602d970d1b4cd4ca8136e4a65313784fdf9011f17fdfc2e0b8f0c5"),
        "ka_GE" to Dict("main_ka", 1103957L, "d45c1dc02a76e527bef7d330f8d1f15a4c4dffef8f58215f4bd5ea91e5f28ce6"),
        "km_KH" to Dict("main_km", 780555L, "08687a0119f7e8e9fb1ab30d8fb757c22cab9606dc38a5392192581bacc3dc53"),
        "kn_IN" to Dict("main_kn", 607756L, "f58d0bdadeba0f08330c1233448f47b18cb459eddd9f24bfcf56cf638d8c9013"),
        "lt" to Dict("main_lt", 1320376L, "47155863f9fe3c188c9c5d4f850d02a4b499e9828fecc4bfb8305f65fedfcef2"),
        "lv" to Dict("main_lv", 1347250L, "d9ecea24f9b769462834c8760661678f62f33267bec0cc9b069e8fc2ae986e32"),
        "mk" to Dict("main_mk", 2607486L, "2c889377c660ec79a5131172baff8d87f568667665a81685bde555ae4ae0ea4c"),
        "ml_IN" to Dict("main_ml", 713914L, "eb86c6ab70cb1486c759a159b4ca39cd19bf9ec099ea92afb24a76effa71fd11"),
        "mr_IN" to Dict("main_mr", 547598L, "acf2164c98d715501cdcd34a8021791af3f08f6845bd245b740ba7a3e452d36b"),
        "nb" to Dict("main_nb", 1214008L, "6e4c39ba5e471af8be2d8ea4a62d2cf0afded233481a3f7c6486889696014ffe"),
        "nl" to Dict("main_nl", 1407361L, "537078e3ba369c87063031f03051d80a0ee121a5459f8192811ca254fc959d19"),
        "nl_BE" to Dict("main_nl", 1407361L, "537078e3ba369c87063031f03051d80a0ee121a5459f8192811ca254fc959d19"),
        "pl" to Dict("main_pl", 1193157L, "5b2d08c14128bcfb9d62db3cd5df8f0af8f7c2e653d9913f72f9f87d64648864"),
        "pt_BR" to Dict("main_pt_br", 1064251L, "2af42e311220b7440c4b62fbdbddc439626621ec8c5873f4146b045337a58b84"),
        "pt_PT" to Dict("main_pt_pt", 1418266L, "c9e5607bd63143930b41ca91919bce23e8abca8172ad24a0fdede029398c95d7"),
        "ro" to Dict("main_ro", 6366453L, "882c7276bb909afcebc3dddf9086ea0d8c37519ee136971cc448b1b80c51539c"),
        "sk" to Dict("main_sk", 6694001L, "a955a81ecf7cc1267484cdecc53e5f9092c080d36a16d7d335434bf59d976627"),
        "sl" to Dict("main_sl", 336572L, "d779d40bee274be2a5596e6df7882ac4685327c360810000ab5b900c6a79fe32"),
        "sr" to Dict("main_sr", 1774606L, "dace1f700bb5335ed5c9e627a32dd02642c37f6f7fa524867786ca0496b20500"),
        "sr_ZZ" to Dict("main_sr_zz", 1071472L, "61ce4cd1cd1961ab2ca2796964e4b1468eb48cb7568973c16f850348ebaedcc2"),
        "sv" to Dict("main_sv", 1385043L, "fd4ff64e40d0c7cd34914f473090b21c266e0ab9631b04a96e799fbe4879a5e4"),
        "ta_IN" to Dict("main_ta", 664358L, "e5d70df2a2b1cde9ffce60d7cad28316864c01dd9123606c4ec79e5ae9a5123e"),
        "ta_LK" to Dict("main_ta", 664358L, "e5d70df2a2b1cde9ffce60d7cad28316864c01dd9123606c4ec79e5ae9a5123e"),
        "ta_SG" to Dict("main_ta", 664358L, "e5d70df2a2b1cde9ffce60d7cad28316864c01dd9123606c4ec79e5ae9a5123e"),
        "te_IN" to Dict("main_te", 607634L, "59e66fa3ac88783a1ad3e7db4b200c6206dfcebd459f4da30fffd895173c220c"),
        "th" to Dict("main_th", 1127911L, "a94696448b8258b9d087de43a67d19eb46b83f83e199afcf10bdea92a18a0e31"),
        "tr" to Dict("main_tr", 14176026L, "772d24cb4841d9f9c4d3196e4d35dd18ce5b05485ce4ab996e06165e8495d11c"),
        "uk" to Dict("main_uk", 10694610L, "50d931a2a73acfbd5e2d69d14927981f985aefeda1067a37cf40d9d4d5b330e0"),
    )

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** locale -> download progress 0..100 while downloading. */
    private val progress = HashMap<String, Int>()
    /** locale -> last download error (Russian). */
    private val errors = HashMap<String, String>()

    /** Settings screen subscribes here; called on the main thread. */
    @JvmStatic var listener: Runnable? = null

    @Volatile private var cached: Set<String>? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- enabled languages ----------------

    /** Enabled subtype locales, in the order they were switched on. Cheap (in memory). */
    @JvmStatic fun enabled(ctx: Context): Set<String> {
        cached?.let { return it }
        val raw = prefs(ctx).getString(KEY_ENABLED, null)
        val set = if (raw.isNullOrEmpty()) DEFAULT_ENABLED
            else LinkedHashSet(raw.split(',').filter { it.isNotBlank() })
        cached = set
        return set
    }

    @JvmStatic fun isEnabled(ctx: Context, locale: String): Boolean = enabled(ctx).contains(locale)

    @JvmStatic fun setEnabled(ctx: Context, locale: String, on: Boolean) {
        val set = LinkedHashSet(enabled(ctx))
        if (on) set.add(locale) else set.remove(locale)
        if (set.isEmpty()) return // at least one language must stay
        cached = set
        prefs(ctx).edit().putString(KEY_ENABLED, set.joinToString(",")).apply()
        if (on) {
            if (needsDownload(ctx, locale)) download(ctx, locale)
        } else {
            deleteDict(ctx, locale)
        }
        notifyChanged()
    }

    /** All languages of the keyboard (declared in method.xml), in declaration order. */
    @JvmStatic fun allSubtypes(ctx: Context): List<InputMethodSubtype> {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val imi = imm.inputMethodList.firstOrNull { it.packageName == ctx.packageName }
            ?: return emptyList()
        return (0 until imi.subtypeCount).map { imi.getSubtypeAt(it) }
    }

    @JvmStatic fun locales(ctx: Context): List<String> = allSubtypes(ctx).map { it.locale }.distinct()

    /** Russian name of the language, e.g. "Польский", "Испанский (США)". */
    @JvmStatic fun displayName(locale: String): String {
        when (locale) {
            "hi_ZZ" -> return "Хинглиш"
            "sr_ZZ" -> return "Сербский (латиница)"
            "es_419" -> return "Испанский (Латинская Америка)"
        }
        val p = locale.split('_')
        val l = if (p.size > 1) Locale(p[0], p[1]) else Locale(p[0])
        val ru = Locale("ru")
        val name = if (locale == "en_US" || p.size == 1) l.getDisplayLanguage(ru) else l.getDisplayName(ru)
        return name.replaceFirstChar { it.titlecase(ru) }
    }

    // ---------------- dictionaries ----------------

    @JvmStatic fun dictFor(locale: String): Dict? = DICTS[locale]

    /** Russian and English dictionaries are inside the APK. */
    @JvmStatic fun hasBuiltInDict(locale: String): Boolean = locale == "ru" || locale.startsWith("en")

    private fun dictTarget(ctx: Context, d: Dict): File {
        val id = BinaryDictionaryGetter.MAIN_DICTIONARY_CATEGORY +
            BinaryDictionaryGetter.ID_CATEGORY_SEPARATOR + d.dictLocale.lowercase(Locale.ROOT)
        return File(DictionaryInfoUtils.getCacheFileName(id, d.dictLocale, ctx.applicationContext))
    }

    @JvmStatic fun isDictDownloaded(ctx: Context, locale: String): Boolean {
        val d = DICTS[locale] ?: return false
        val f = dictTarget(ctx, d)
        return f.isFile && f.length() == d.size
    }

    @JvmStatic fun needsDownload(ctx: Context, locale: String): Boolean =
        DICTS.containsKey(locale) && !isDictDownloaded(ctx, locale)

    /** Human-readable dictionary status for the settings screen. */
    @JvmStatic fun status(ctx: Context, locale: String): String {
        if (hasBuiltInDict(locale)) return "словарь встроен"
        val d = DICTS[locale] ?: return "без словаря подсказок, только раскладка"
        progress[locale]?.let { return "скачиваю словарь: $it%" }
        errors[locale]?.let { return "$it — нажми, чтобы повторить" }
        if (isDictDownloaded(ctx, locale)) return "словарь скачан (${mb(d.size)})"
        return "словарь ${mb(d.size)} — нажми, чтобы скачать"
    }

    @JvmStatic fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f МБ", bytes / 1048576.0)

    /** Starts (or retries) the dictionary download for an enabled language. */
    @JvmStatic fun download(ctx: Context, locale: String) {
        val d = DICTS[locale] ?: return
        if (progress.containsKey(locale)) return
        val app = ctx.applicationContext
        progress[locale] = 0
        errors.remove(locale)
        notifyChanged()
        exec.execute {
            var err: String? = null
            try {
                fetch(app, locale, d)
            } catch (e: Throwable) {
                Log.w(TAG, "download $locale failed", e)
                err = e.message ?: "ошибка загрузки"
            }
            main.post {
                progress.remove(locale)
                if (err != null) errors[locale] = err else errors.remove(locale)
                if (err == null) reloadKeyboardDictionaries(app)
                notifyChanged()
            }
        }
    }

    private fun fetch(app: Context, locale: String, d: Dict) {
        val target = dictTarget(app, d)
        // Partial file lives outside files/dicts/, otherwise the keyboard could try to open it.
        val tmpDir = File(DictionaryInfoUtils.getWordListTempDirectory(app)).apply { mkdirs() }
        val part = File(tmpDir, "ivol_" + d.file + ".part")
        part.delete()
        val c = URL(DICT_URL + d.file + ".dict").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "ivol-keyboard/${IvolUpdater.currentVersion()}")
            val code = c.responseCode
            if (code != 200) throw IllegalStateException("GitHub ответил $code")
            if (c.url.protocol != "https") throw IllegalStateException("загрузка не по https")
            var done = 0L
            var lastPct = -1
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        val pct = (done * 100 / d.size).toInt().coerceIn(0, 100)
                        if (pct >= lastPct + 5) {
                            lastPct = pct
                            main.post { if (progress.containsKey(locale)) { progress[locale] = pct; notifyChanged() } }
                        }
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (done != d.size || sha != d.sha256) {
                part.delete()
                throw IllegalStateException("словарь скачался с ошибкой")
            }
        } finally {
            c.disconnect()
        }
        target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("не удалось сохранить словарь")
        Log.i(TAG, "dictionary for $locale saved: ${target.absolutePath}")
    }

    private fun deleteDict(ctx: Context, locale: String) {
        val d = DICTS[locale] ?: return
        // Another enabled language may share this file (es / es_US / es_419...).
        if (enabled(ctx).any { it != locale && DICTS[it]?.file == d.file }) return
        if (dictTarget(ctx, d).delete()) reloadKeyboardDictionaries(ctx.applicationContext)
        errors.remove(locale)
    }

    /** Tells the running keyboard (LatinIME) to reopen its main dictionary. */
    private fun reloadKeyboardDictionaries(app: Context) {
        try {
            app.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
                .setPackage(app.packageName))
        } catch (e: Throwable) {
            Log.w(TAG, "reload broadcast failed", e)
        }
    }

    private fun notifyChanged() {
        listener?.run()
    }
}
