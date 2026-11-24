package com.android.inputmethod.latin.ivol

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.android.inputmethod.latin.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * IVOL: updates from GitHub Releases (repo oiv-an/ivol-keyboard).
 *
 * 1. Check: only when the person really types (first key press), at most once a day, in the
 *    background. Nothing is downloaded at this point.
 * 2. A newer `vX.Y.Z` release with an `ivol-keyboard-*.apk` asset → an update icon appears on the
 *    right of the keyboard top bar.
 * 3. Tap on the icon → settings screen → APK is downloaded into the private cache, size, SHA-256
 *    (GitHub asset digest), package, versionCode and signing certificate are verified → the system
 *    install dialog. Nothing is installed without the user's tap.
 *
 * Only the release package `pro.ivol.keyboard` can install updates; a debug build only shows that a
 * new version exists.
 */
object IvolUpdater {
    private const val TAG = "IvolUpdater"
    const val REPO = "oiv-an/ivol-keyboard"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val RELEASE_PACKAGE = "pro.ivol.keyboard"
    private val APK_NAME = Regex("^ivol-keyboard-[A-Za-z0-9._-]+\\.apk$")

    private const val PREFS = "ivol_update"
    private const val KEY_AUTO = "auto"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_AVAILABLE = "available_version"
    private const val KEY_READY_VERSION = "ready_version"
    private const val KEY_READY_FILE = "ready_file"

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val RETRY_AFTER_ERROR_MS = 2L * 60 * 60 * 1000
    const val ACTION_INSTALL_STATUS = "pro.ivol.keyboard.UPDATE_INSTALL_STATUS"
    private const val EXTRA_VERSION = "version"

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var busy = false
    @Volatile private var loaded = false
    @Volatile private var lastCheckMem = 0L
    @Volatile private var availableMem: String? = null

    /** Set when the install permission screen was opened; settings resumes the install. */
    @Volatile var pendingInstall = false
        private set

    /** Last human-readable status (Russian, shown on the settings screen). */
    @Volatile var lastStatus: String = ""
        private set

    /** Settings screen subscribes here; called on the main thread. */
    var listener: ((String) -> Unit)? = null

    /** The keyboard top bar subscribes here to show/hide the update icon; main thread. */
    @JvmStatic var onAvailableChanged: Runnable? = null

    private data class Release(
        val version: String, val apkUrl: String, val size: Long, val sha256: String?,
    )

    private class UpdateException(msg: String) : Exception(msg)

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        lastCheckMem = p.getLong(KEY_LAST_CHECK, 0L)
        availableMem = p.getString(KEY_AVAILABLE, null)
        loaded = true
    }

    @JvmStatic fun canInstall(ctx: Context): Boolean =
        !BuildConfig.DEBUG && ctx.packageName == RELEASE_PACKAGE

    @JvmStatic fun isAutoEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO, true)

    @JvmStatic fun setAutoEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO, on).apply()
        if (!on) setAvailable(ctx, null)
    }

    @JvmStatic fun currentVersion(): String = BuildConfig.VERSION_NAME

    /** Newer version known from the last check, or null. Cheap: in-memory after the first call. */
    @JvmStatic fun availableVersion(ctx: Context): String? {
        load(ctx)
        val v = availableMem ?: return null
        return if (compareVersions(v, currentVersion()) > 0) v else null
    }

    private fun setAvailable(ctx: Context, v: String?) {
        availableMem = v
        prefs(ctx).edit().apply { if (v == null) remove(KEY_AVAILABLE) else putString(KEY_AVAILABLE, v) }.apply()
        main.post { onAvailableChanged?.run() }
    }

    // ---------------- hook from LatinIME (every key press — must stay cheap) ----------------

    @JvmStatic fun onKeyTyped(ctx: Context) {
        if (busy) return
        val now = System.currentTimeMillis()
        if (loaded && now - lastCheckMem < DAY_MS) return
        load(ctx)
        if (now - lastCheckMem < DAY_MS) return
        if (!isAutoEnabled(ctx)) {
            lastCheckMem = now // do not re-read prefs on every key
            return
        }
        markChecked(ctx, now)
        val app = ctx.applicationContext
        busy = true
        exec.execute {
            try {
                cleanup(app)
                val rel = fetchLatest()
                setAvailable(app, rel.version.takeIf { compareVersions(it, currentVersion()) > 0 })
                Log.i(TAG, "background check: latest ${rel.version}, installed ${currentVersion()}")
            } catch (e: Throwable) {
                // Offline / GitHub blocked: try again in a couple of hours, not on every key.
                Log.w(TAG, "background check failed: ${e.message}")
                markChecked(app, System.currentTimeMillis() - DAY_MS + RETRY_AFTER_ERROR_MS)
            } finally {
                busy = false
            }
        }
    }

    private fun markChecked(ctx: Context, t: Long) {
        lastCheckMem = t
        prefs(ctx).edit().putLong(KEY_LAST_CHECK, t).apply()
    }

    // ---------------- settings screen: check + download + install, only on the user's tap ----------------

    @JvmStatic fun checkNow(ctx: Context) {
        if (busy) {
            status("Уже проверяю…")
            return
        }
        pendingInstall = false
        val app = ctx.applicationContext
        markChecked(app, System.currentTimeMillis())
        busy = true
        exec.execute {
            try {
                flow(app)
            } catch (e: UpdateException) {
                Log.w(TAG, e.message ?: "update error")
                status(e.message ?: "Ошибка обновления")
            } catch (e: Throwable) {
                Log.w(TAG, "update failed", e)
                status("Не удалось обновиться: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    private fun flow(app: Context) {
        cleanup(app)
        status("Проверяю GitHub…")
        val rel = fetchLatest()
        if (compareVersions(rel.version, currentVersion()) <= 0) {
            setAvailable(app, null)
            status("Установлена последняя версия ${currentVersion()}")
            return
        }
        setAvailable(app, rel.version)
        if (!canInstall(app)) {
            status("На GitHub есть версия ${rel.version}. Эта сборка (отладочная) сама не " +
                "обновляется — поставь релизный APK со страницы релизов.")
            return
        }
        val file = download(app, rel)
        verifyArchive(app, file)
        prefs(app).edit()
            .putString(KEY_READY_VERSION, rel.version)
            .putString(KEY_READY_FILE, file.absolutePath)
            .apply()
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            pendingInstall = true
            status("Разреши клавиатуре устанавливать приложения и вернись сюда")
            main.post {
                try {
                    app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Throwable) {
                    Log.w(TAG, "cannot open unknown sources settings", e)
                }
            }
            return
        }
        install(app, rel.version, file)
    }

    private fun fetchLatest(): Release {
        val c = URL(API_LATEST).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "ivol-keyboard/${currentVersion()}")
            val code = c.responseCode
            if (code == 404) throw UpdateException("На GitHub пока нет релизов")
            if (code != 200) throw UpdateException("GitHub ответил $code, попробуй позже")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val tag = j.optString("tag_name").trim()
            if (tag.isEmpty()) throw UpdateException("В релизе GitHub нет номера версии")
            val assets = j.optJSONArray("assets") ?: throw UpdateException("В релизе $tag нет файлов")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (!APK_NAME.matches(a.optString("name"))) continue
                val url = a.optString("browser_download_url")
                if (!url.startsWith(DOWNLOAD_PREFIX)) continue
                val digest = a.optString("digest").removePrefix("sha256:").lowercase()
                return Release(
                    version = tag.removePrefix("v").removePrefix("V"),
                    apkUrl = url,
                    size = a.optLong("size", -1L),
                    sha256 = digest.takeIf { it.length == 64 },
                )
            }
            throw UpdateException("В релизе $tag нет APK")
        } finally {
            c.disconnect()
        }
    }

    private fun updatesDir(app: Context): File = File(app.cacheDir, "updates").apply { mkdirs() }

    private fun download(app: Context, rel: Release): File {
        val dir = updatesDir(app)
        val target = File(dir, "ivol-keyboard-${rel.version}.apk")
        if (target.isFile && (rel.size <= 0 || target.length() == rel.size) &&
            (rel.sha256 == null || sha256(target) == rel.sha256)) {
            return target
        }
        target.delete()
        val part = File(dir, target.name + ".part")
        part.delete()
        status("Скачиваю версию ${rel.version}…")
        val c = URL(rel.apkUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true // GitHub redirects to its https asset storage
            c.setRequestProperty("User-Agent", "ivol-keyboard/${currentVersion()}")
            val code = c.responseCode
            if (code != 200) throw UpdateException("Скачивание: GitHub ответил $code")
            if (c.url.protocol != "https") throw UpdateException("Скачивание не по https — отменено")
            val total = if (rel.size > 0) rel.size else c.contentLengthLong
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct >= lastPct + 5) {
                                lastPct = pct
                                status("Скачиваю версию ${rel.version}: $pct%")
                            }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (rel.size > 0 && part.length() != rel.size) {
            part.delete()
            throw UpdateException("Файл скачался не полностью, попробуй ещё раз")
        }
        if (rel.sha256 != null && sha256(part) != rel.sha256) {
            part.delete()
            throw UpdateException("Контрольная сумма APK не совпала — обновление отменено")
        }
        if (!part.renameTo(target)) throw UpdateException("Не удалось сохранить APK")
        return target
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun certs(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo
            when {
                si == null -> info.signatures
                si.hasMultipleSigners() -> si.apkContentsSigners
                else -> si.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        val md = MessageDigest.getInstance("SHA-256")
        return sigs?.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
            ?.toSet() ?: emptySet()
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(app: Context, file: File) {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        if (archive == null) {
            file.delete()
            throw UpdateException("Скачанный APK повреждён")
        }
        if (archive.packageName != app.packageName) {
            file.delete()
            throw UpdateException("APK от другого приложения (${archive.packageName}) — отменено")
        }
        val installed = pm.getPackageInfo(app.packageName, flags)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            file.delete()
            throw UpdateException("Номер сборки на GitHub не новее установленного")
        }
        val mine = certs(installed)
        val theirs = certs(archive)
        // Android itself refuses an update with another signer; this check only gives a clear error.
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()) {
            file.delete()
            throw UpdateException("APK подписан чужим ключом — обновление отменено")
        }
    }

    private fun install(app: Context, version: String, file: File) {
        status("Открываю установку версии $version…")
        val installer = app.packageManager.packageInstaller
        for (s in installer.mySessions) runCatching { installer.abandonSession(s.sessionId) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        params.setSize(file.length())
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val intent = Intent(app, IvolUpdateReceiver::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .putExtra(EXTRA_VERSION, version)
            var piFlags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) piFlags = piFlags or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(app, id, intent, piFlags).intentSender)
        }
    }

    /** PackageInstaller session results (from [IvolUpdateReceiver]). */
    internal fun onInstallStatus(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val version = intent.getStringExtra(EXTRA_VERSION) ?: ""
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                status("Подтверди установку версии $version")
                try {
                    app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Throwable) {
                    Log.w(TAG, "cannot open installer", e)
                    status("Не удалось открыть установщик: ${e.message}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> status("Версия $version установлена")
            PackageInstaller.STATUS_FAILURE_ABORTED -> status("Установка версии $version отменена")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "код $st"
                Log.w(TAG, "install failed: $st $msg")
                status("Не удалось установить версию $version: $msg")
                if (st == PackageInstaller.STATUS_FAILURE_CONFLICT ||
                    st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ||
                    st == PackageInstaller.STATUS_FAILURE_INVALID) {
                    dropReady(app)
                }
            }
        }
    }

    // ---------------- housekeeping ----------------

    private fun dropReady(app: Context) {
        val p = prefs(app)
        p.getString(KEY_READY_FILE, null)?.let { File(it).delete() }
        p.edit().remove(KEY_READY_VERSION).remove(KEY_READY_FILE).apply()
    }

    /** Removes APKs of versions that are already installed and stale partial downloads. */
    private fun cleanup(app: Context) {
        val p = prefs(app)
        val ready = p.getString(KEY_READY_VERSION, null)
        if (ready != null && compareVersions(ready, currentVersion()) <= 0) dropReady(app)
        val keep = p.getString(KEY_READY_FILE, null)
        updatesDir(app).listFiles()?.forEach { f -> if (f.absolutePath != keep) f.delete() }
        if (availableMem != null && availableVersion(app) == null) setAvailable(app, null)
    }

    private fun status(s: String) {
        lastStatus = s
        main.post { listener?.invoke(s) }
    }

    /** Compares "0.2.10" style versions numerically; suffixes like "-beta" are ignored. */
    @JvmStatic fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.')
            .map { it.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }
}

/** Receives PackageInstaller session results for [IvolUpdater]. */
class IvolUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == IvolUpdater.ACTION_INSTALL_STATUS) IvolUpdater.onInstallStatus(context, intent)
    }
}
