package com.android.inputmethod.latin.ivol

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.android.inputmethod.latin.LatinIME
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * IVOL: on-device speech recognition via sherpa-onnx (NeMo transducer). Two selectable models:
 * Sber GigaAM v3 (Russian, ~230 MB) and Fluid Speech Pro (25 European languages, ~800 MB).
 * Each model is downloaded on demand and can be deleted from the settings screen.
 *
 * Push-to-talk on the mic zone of the space bar: recording runs only while the finger is down.
 * While holding, the speech is re-recognized about twice a second and the text in the field is
 * updated live; on release the final recognition replaces it.
 */
object GigaAmVoice {
    enum class State { IDLE, DOWNLOADING, RECORDING, TRANSCRIBING }

    private const val TAG = "GigaAmVoice"
    const val MODEL_NAME = "sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16"

    class ModelFile(val name: String, val size: Long, val sha256: String?)

    class Engine(
        val id: String,
        val title: String,
        val subtitle: String,
        /** Path under filesDir. */
        val dirPath: String,
        val baseUrl: String,
        val files: List<ModelFile>,
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
    ) {
        val size: Long get() = files.sumOf { it.size }
    }

    const val ENGINE_GIGAAM = "gigaam"
    const val ENGINE_FLUID = "fluid"
    private const val PREF_ENGINE = "voice_engine"

    @JvmField val ENGINES = listOf(
        Engine(
            ENGINE_GIGAAM, "GigaAM (Сбер)", "Только русский, быстрая",
            "gigaam/$MODEL_NAME",
            "https://huggingface.co/csukuangfj/$MODEL_NAME/resolve/a6039be7cee829a9044a69ac0ebaf1c191217c97/",
            listOf(
                ModelFile("encoder.int8.onnx", 224_570_820, "369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf"),
                ModelFile("decoder.onnx", 4_600_132, "38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4"),
                ModelFile("joiner.onnx", 2_712_896, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
                ModelFile("tokens.txt", 13_354, null),
            ),
            "encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt",
        ),
        Engine(
            ENGINE_FLUID, "Fluid Speech Pro", "25 языков, точнее, но тяжелее",
            "fluid/fluid-speech-pro-onnx-int8-fa588e9",
            "https://huggingface.co/FluidVoice-Labs/fluid-speech-pro-onnx/resolve/fa588e9f87f1b9ad0ca2e2d8f7eaf84dc3803581/",
            listOf(
                ModelFile("vocab.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
                ModelFile("joiner.int8.onnx", 2_754, "c0e995b659ed5e15fc0c38408ee56cd9a03e6d3329f304c32b9d5017c8cdd381"),
                ModelFile("joiner.int8.onnx.data", 6_564_352, "7473427ab99417fae8cf7025ba11507cb5dcc223194bae2f79064dde0936d0af"),
                ModelFile("decoder.int8.onnx", 4_797, "0bd3e225124a8a90373e500f62516270fd85fdf42237aa569489b232d21cac08"),
                ModelFile("decoder.int8.onnx.data", 47_251_456, "632c3cbf59d3e52d2f7f4dda3bf8e28c045f7a3bb3dbef0f44529ba63926efaa"),
                ModelFile("encoder-model.int8.onnx", 955_592, "07ad77459bd3bb165c2c53e978ed1dfd64c6e129b18af2ec5ee38343be1d4451"),
                ModelFile("encoder-model.int8.onnx.data", 745_971_712, "d2f8bfbb1fa399f207cde85aa3fabee0d67f6730cb31cbf31832c10de69f9c3c"),
            ),
            "encoder-model.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "vocab.txt",
        ),
    )

    fun engine(id: String?): Engine = ENGINES.firstOrNull { it.id == id } ?: ENGINES[0]
    // Own prefs file: writing to IvolPrefs would trigger a keyboard rebuild.
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("ivol_voice", Context.MODE_PRIVATE)
    fun selected(ctx: Context): Engine = engine(prefs(ctx).getString(PREF_ENGINE, ENGINE_GIGAAM))

    /** Settings screen refresh callback (state / progress / install changes), main thread. */
    @JvmStatic var settingsListener: Runnable? = null
    /** Engine being downloaded right now, null if none. */
    @Volatile var downloadingId: String? = null
        private set

    private const val SAMPLE_RATE = 16000
    private const val MIN_AUDIO_SAMPLES = SAMPLE_RATE * 3 / 10 // shorter = accidental tap
    private const val MAX_RECORD_MS = 5 * 60 * 1000L
    private const val SEGMENT_SAMPLES = 18 * SAMPLE_RATE // finalize a piece once the open tail is this long
    private const val PARTIAL_INTERVAL_MS = 450L
    private const val IDLE_RELEASE_MS = 10 * 60 * 1000L

    @JvmStatic @Volatile var state = State.IDLE
        private set
    /** Mic level 0..1 for the recording animation. */
    @JvmStatic @Volatile var level = 0f
        private set
    @Volatile var downloadProgress = 0
        private set

    /** Redraw callback (space bar), always invoked on the main thread. */
    @JvmStatic var onChanged: Runnable? = null

    private var ime: LatinIME? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "GigaAmWorker") }
    private var recognizer: OfflineRecognizer? = null // only touched on worker
    private var loadedId: String? = null // worker-only: engine of [recognizer]
    @Volatile private var activeId: String = ENGINE_GIGAAM // engine of the current session

    // audio, written by the recorder thread
    private val lock = Any()
    private var samples = FloatArray(SAMPLE_RATE * 30)
    private var sampleCount = 0
    @Volatile private var recording = false
    private var recordThread: Thread? = null

    // session, increments on every start so late results of an old session are dropped
    @Volatile private var session = 0
    // worker-only: finalized pieces of the current session
    private var doneSamples = 0
    private var doneText = ""

    // main-thread only: text this session has put into the field
    private var emitted = ""
    private var addLeadingSpace = false
    private var lowercaseStart = false

    private val releaseRunnable = Runnable {
        worker.execute {
            releaseRecognizer()
            Log.i(TAG, "model released after idle")
        }
    }

    private fun releaseRecognizer() {
        recognizer?.release()
        recognizer = null
        loadedId = null
    }

    @JvmStatic fun attach(service: LatinIME) { ime = service }

    @JvmStatic fun detach() {
        release()
        ime = null
    }

    fun modelDir(ctx: Context, e: Engine) = File(ctx.filesDir, e.dirPath)
    fun isInstalled(ctx: Context, e: Engine) = File(modelDir(ctx, e), ".installed").exists()
    fun isInstalled(ctx: Context) = isInstalled(ctx, selected(ctx))

    /** Choose the model used by the mic key. The other model's files stay until deleted. */
    fun select(ctx: Context, id: String) {
        prefs(ctx).edit().putString(PREF_ENGINE, id).apply()
        main.removeCallbacks(releaseRunnable)
        worker.execute { if (loadedId != id) releaseRecognizer() }
        notifyChanged()
    }

    /** Download a model from the settings screen. */
    fun download(ctx: Context, id: String) {
        when (state) {
            State.DOWNLOADING -> { toast(ctx, "Уже скачивается ${engine(downloadingId).title}: $downloadProgress%"); return }
            State.RECORDING, State.TRANSCRIBING -> { toast(ctx, "Сейчас идёт распознавание, попробуй позже"); return }
            State.IDLE -> {}
        }
        startDownload(ctx, engine(id))
    }

    /** Delete a model's files to free space. Runs on the worker, so it never races with recognition. */
    fun delete(ctx: Context, id: String) {
        if (downloadingId == id) { toast(ctx, "Модель сейчас скачивается"); return }
        val e = engine(id)
        val appCtx = ctx.applicationContext
        worker.execute {
            if (loadedId == id) releaseRecognizer()
            val dir = modelDir(appCtx, e)
            dir.deleteRecursively()
            File(dir.path + ".part").deleteRecursively()
            Log.i(TAG, "model ${e.id} deleted")
            notifyChanged()
        }
    }

    /** Bytes the model occupies on disk now (complete or partial download). */
    fun usedBytes(ctx: Context, e: Engine): Long {
        val dir = modelDir(ctx, e)
        val files = dir.listFiles().orEmpty().toList() + File(dir.path + ".part").listFiles().orEmpty().toList()
        return files.sumOf { f: File -> f.length() }
    }

    /** Text shown on the space bar instead of the language name, null = normal label. */
    @JvmStatic fun spaceLabel(): String? = when (state) {
        State.RECORDING -> "Говорите…"
        State.TRANSCRIBING -> "Распознаю…"
        State.DOWNLOADING -> engine(downloadingId).title.substringBefore(" (") + " $downloadProgress%"
        State.IDLE -> null
    }

    /** Finger down on the mic zone: start recording. */
    @JvmStatic fun onPress(ctx: Context) {
        Log.i(TAG, "onPress state=$state")
        when (state) {
            State.RECORDING -> { onRelease(ctx); return } // a lost finger-up must not leave the mic on
            State.TRANSCRIBING -> return
            State.DOWNLOADING -> { toast(ctx, "Модель ${engine(downloadingId).title} загружается: $downloadProgress%"); return }
            State.IDLE -> {}
        }
        val eng = selected(ctx)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "no RECORD_AUDIO, asking")
            ctx.startActivity(Intent(ctx, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!isInstalled(ctx, eng)) {
            startDownload(ctx, eng)
            return
        }
        activeId = eng.id
        main.removeCallbacks(releaseRunnable)
        prepareInsertion()
        if (!startRecording(ctx)) return
        val s = session
        val appCtx = ctx.applicationContext
        worker.execute {
            doneSamples = 0
            doneText = ""
            try { ensureLoaded(appCtx) } catch (e: Throwable) { Log.e(TAG, "model load failed", e) }
            partialLoop(s)
        }
    }

    /** Finger up (or cancelled): stop recording and finish recognition. */
    @JvmStatic fun onRelease(ctx: Context) {
        Log.i(TAG, "onRelease state=$state")
        if (state != State.RECORDING) return
        val audio = stopRecorder()
        val s = session
        if (audio.size < MIN_AUDIO_SAMPLES && emitted.isEmpty()) { setState(State.IDLE); return }
        setState(State.TRANSCRIBING)
        val appCtx = ctx.applicationContext
        worker.execute {
            val t0 = SystemClock.elapsedRealtime()
            val text = try {
                val r = ensureLoaded(appCtx) ?: throw IllegalStateException("model not installed")
                finalizeSegments(r, audio, audio.size) // remaining long tail, if any
                join(doneText, decode(r, audio, doneSamples, audio.size))
            } catch (e: Throwable) {
                Log.e(TAG, "transcription failed", e); null
            }
            Log.i(TAG, "final [$activeId]: ${audio.size / SAMPLE_RATE}s audio, ${SystemClock.elapsedRealtime() - t0} ms, ${text?.length} chars")
            main.post {
                if (s != session) return@post
                setState(State.IDLE)
                if (text == null) toast(appCtx, "Ошибка распознавания ${engine(activeId).title}") else showText(text, true)
                scheduleRelease()
            }
        }
    }

    /** Keyboard hidden: drop the recording. */
    @JvmStatic fun cancel() {
        if (state != State.RECORDING) return
        stopRecorder()
        session++
        emitted = ""
        setState(State.IDLE)
    }

    @JvmStatic fun release() {
        cancel()
        main.removeCallbacks(releaseRunnable)
        worker.execute { releaseRecognizer() }
    }

    // ---------------------------------------------------------------- live recognition (worker thread)

    private fun partialLoop(s: Int) {
        try {
            while (recording && s == session) {
                val t0 = SystemClock.elapsedRealtime()
                val r = recognizer ?: break
                val (audio, count) = synchronized(lock) { samples to sampleCount }
                if (count - doneSamples >= SAMPLE_RATE / 2) {
                    finalizeSegments(r, audio, count)
                    val partial = join(doneText, decode(r, audio, doneSamples, count))
                    if (recording && s == session) main.post { if (s == session && state == State.RECORDING) showText(partial, false) }
                }
                val wait = PARTIAL_INTERVAL_MS - (SystemClock.elapsedRealtime() - t0)
                if (wait > 0) Thread.sleep(wait)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "live recognition failed", e)
        }
    }

    /** While the open tail is longer than SEGMENT_SAMPLES, cut it at a quiet spot and recognize the head for good. */
    private fun finalizeSegments(r: OfflineRecognizer, audio: FloatArray, count: Int) {
        while (count - doneSamples > SEGMENT_SAMPLES) {
            val cut = quietestPoint(audio, doneSamples + SEGMENT_SAMPLES - 5 * SAMPLE_RATE, doneSamples + SEGMENT_SAMPLES)
            doneText = join(doneText, decode(r, audio, doneSamples, cut))
            doneSamples = cut
        }
    }

    private fun quietestPoint(audio: FloatArray, from: Int, to: Int): Int {
        val win = SAMPLE_RATE / 10
        var best = to
        var bestEnergy = Double.MAX_VALUE
        var p = max(0, from)
        while (p + win <= to) {
            var e = 0.0
            for (i in p until p + win) e += audio[i] * audio[i]
            if (e < bestEnergy) { bestEnergy = e; best = p + win / 2 }
            p += win / 2
        }
        return best
    }

    private fun decode(r: OfflineRecognizer, audio: FloatArray, from: Int, to: Int): String {
        if (to - from < SAMPLE_RATE / 5) return ""
        val stream = r.createStream()
        try {
            stream.acceptWaveform(audio.copyOfRange(from, to), SAMPLE_RATE)
            r.decode(stream)
            val text = r.getResult(stream).text
            return if (loadedId == ENGINE_FLUID) fixUnk(text) else text.trim()
        } finally {
            stream.release()
        }
    }

    /**
     * Fluid's vocabulary has no «Ё» and no quotes: the model emits `<unk>` there. Simplified port of the
     * server's unk_fixer: inside a word → «ё», after a Russian lowercase letter at a word end → «ё»,
     * otherwise paired quotes « »; an unpaired opener before a lowercase Russian letter → «ё», else dropped.
     */
    internal fun fixUnk(raw: String): String {
        val t = raw.replace(Regex("<\\|[^>]*\\|>"), " ")
        if (!t.contains("<unk>")) return t.replace(Regex("\\s+"), " ").trim()
        fun cyrLower(c: Char?) = c != null && c in 'а'..'я' || c == 'ё'
        val parts = t.split("<unk>")
        val out = StringBuilder(parts[0])
        var openAt = -1
        for (i in 1 until parts.size) {
            var p = parts[i]
            val prev = out.lastOrNull()
            val next = p.firstOrNull()
            val prevL = prev != null && prev.isLetter()
            when {
                prevL && next != null && next.isLetter() && next.isLowerCase() -> out.append('ё')
                openAt >= 0 -> {
                    while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
                    out.append('»')
                    openAt = -1
                }
                cyrLower(prev) && (next == null || !next.isLetter()) -> out.append('ё')
                else -> {
                    if (prev != null && !prev.isWhitespace() && prev != '(') out.append(' ')
                    openAt = out.length
                    out.append('«')
                    p = p.trimStart()
                }
            }
            out.append(p)
        }
        if (openAt >= 0) {
            val nextCh = out.getOrNull(openAt + 1)
            if (cyrLower(nextCh)) {
                val before = out.substring(0, openAt).trimEnd().lastOrNull()
                out.setCharAt(openAt, if (before == null || before in ".!?") 'Ё' else 'ё')
            } else {
                out.deleteCharAt(openAt)
            }
        }
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun join(a: String, b: String) = "$a $b".replace(Regex("\\s+"), " ").trim()

    // ---------------------------------------------------------------- text in the field (main thread)

    /** Commit what the user was typing and decide spacing/case from the text before the cursor. */
    private fun prepareInsertion() {
        emitted = ""
        val service = ime ?: return
        try { service.ivolCommitTypedForExternalInput() } catch (e: Throwable) { Log.w(TAG, "commitTyped failed", e) }
        val ic = service.currentInputConnection ?: return
        ic.finishComposingText()
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        val prev = before.lastOrNull()
        val prevNonSpace = before.trimEnd().lastOrNull()
        addLeadingSpace = prev != null && !prev.isWhitespace()
        // continuing a sentence: "привет, как дела" instead of "привет, Как дела"
        lowercaseStart = prevNonSpace != null && (prevNonSpace.isLetterOrDigit() || prevNonSpace == ',')
    }

    /** Replace what this session has emitted with [raw]; [final] adds the trailing space and closes the session text. */
    private fun showText(raw: String, final: Boolean) {
        val service = ime ?: return
        val ic = service.currentInputConnection ?: return
        var target = raw.trim()
        if (target.isNotEmpty()) {
            if (lowercaseStart && target.length > 1 && target[0].isUpperCase() && !target[1].isUpperCase())
                target = target[0].lowercaseChar() + target.substring(1)
            if (addLeadingSpace) target = " $target"
            if (final) target += " "
        }
        val current = emitted
        var common = 0
        val n = min(current.length, target.length)
        while (common < n && current[common] == target[common]) common++
        val del = current.length - common
        val ins = target.substring(common)
        val before = ic.getTextBeforeCursor(current.length, 0)?.toString()
        Log.i(TAG, "showText final=$final emitted='$current' fieldTail='$before' target='$target' del=$del ins='$ins'")
        // If the field no longer ends with what we emitted (app changed it), do not delete anything.
        // If the field no longer ends with what we emitted (app/IME changed it), re-insert the full text.
        if (current.isEmpty() || before == current) service.ivolVoiceEdit(del, ins)
        else service.ivolVoiceEdit(0, target)
        emitted = if (final) "" else target
    }

    // ---------------------------------------------------------------- recording

    @SuppressLint("MissingPermission")
    private fun startRecording(ctx: Context): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SAMPLE_RATE))
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed", e); null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            toast(ctx, "Микрофон недоступен")
            return false
        }
        synchronized(lock) { samples = FloatArray(SAMPLE_RATE * 30); sampleCount = 0 }
        session++
        level = 0f
        recording = true
        setState(State.RECORDING)
        val appCtx = ctx.applicationContext
        recordThread = Thread({
            val buf = ShortArray(1600) // 100 ms
            val start = SystemClock.elapsedRealtime()
            var lastNotify = 0L
            try {
                rec.startRecording()
                Log.i(TAG, "recording started")
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) { Log.e(TAG, "AudioRecord.read error $n"); break }
                    if (n == 0) continue
                    var sum = 0.0
                    synchronized(lock) {
                        // grow into a new array: the worker may be reading the old one
                        if (sampleCount + n > samples.size) samples = samples.copyOf(max(samples.size * 2, sampleCount + n))
                        for (i in 0 until n) {
                            val f = buf[i] / 32768f
                            samples[sampleCount + i] = f
                            sum += f * f
                        }
                        sampleCount += n
                    }
                    level = min(1f, sqrt(sum / n).toFloat() * 8f)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotify > 60) { lastNotify = now; notifyChanged() }
                    if (now - start > MAX_RECORD_MS) { main.post { onRelease(appCtx) }; break }
                }
            } catch (e: Exception) {
                Log.e(TAG, "recording failed", e)
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }, "GigaAmRecorder").apply { start() }
        return true
    }

    private fun stopRecorder(): FloatArray {
        recording = false
        try { recordThread?.join(500) } catch (_: InterruptedException) {}
        recordThread = null
        level = 0f
        return synchronized(lock) { samples.copyOf(sampleCount) }
    }

    // ---------------------------------------------------------------- model

    private fun scheduleRelease() {
        main.removeCallbacks(releaseRunnable)
        main.postDelayed(releaseRunnable, IDLE_RELEASE_MS)
    }

    private fun ensureLoaded(ctx: Context): OfflineRecognizer? {
        val e = engine(activeId)
        if (loadedId == e.id) recognizer?.let { return it }
        releaseRecognizer()
        if (!isInstalled(ctx, e)) return null
        val dir = modelDir(ctx, e).absolutePath
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$dir/${e.encoder}",
                    decoder = "$dir/${e.decoder}",
                    joiner = "$dir/${e.joiner}",
                ),
                tokens = "$dir/${e.tokens}",
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                modelType = "nemo_transducer",
            ),
        )
        val t0 = SystemClock.elapsedRealtime()
        return OfflineRecognizer(config = config).also {
            recognizer = it
            loadedId = e.id
            Log.i(TAG, "model ${e.id} loaded in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    // ---------------------------------------------------------------- model download

    fun mb(bytes: Long) = "${(bytes + 500_000) / 1_000_000} МБ"

    private fun startDownload(ctx: Context, e: Engine) {
        val need = e.size - usedBytes(ctx, e) + 50_000_000
        if (ctx.filesDir.usableSpace < need) {
            toast(ctx, "Мало места для ${e.title}: нужно ещё ${mb(need)}")
            return
        }
        downloadingId = e.id
        downloadProgress = 0
        setState(State.DOWNLOADING)
        toast(ctx, "Скачиваю модель ${e.title} (~${mb(e.size)}), один раз")
        val appCtx = ctx.applicationContext
        Thread({
            val ok = try { download(appCtx, e); true } catch (t: Throwable) { Log.e(TAG, "download failed", t); false }
            main.post {
                downloadingId = null
                setState(State.IDLE)
                toast(appCtx, if (ok) "${e.title} готова: зажмите микрофон на пробеле и говорите" else "Не удалось скачать модель ${e.title}")
            }
        }, "GigaAmDownload").start()
    }

    private fun download(ctx: Context, e: Engine) {
        val dir = modelDir(ctx, e)
        val tmp = File(dir.path + ".part").apply { mkdirs() }
        val total = e.size
        var done = 0L
        for (f in e.files) {
            val out = File(tmp, f.name)
            if (out.length() == f.size && (f.sha256 == null || sha256(out) == f.sha256)) { done += f.size; continue }
            var existing = if (out.length() in 1 until f.size) out.length() else { out.delete(); 0L }
            val conn = URL(e.baseUrl + f.name).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            try {
                val code = conn.responseCode
                if (code == 200) existing = 0 else if (code != 206) throw IOException("HTTP $code for ${f.name}")
                conn.inputStream.use { input ->
                    FileOutputStream(out, existing > 0).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var written = existing
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            written += n
                            val p = ((done + written) * 100 / total).toInt()
                            if (p != downloadProgress) { downloadProgress = p; notifyChanged() }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (out.length() != f.size) throw IOException("size mismatch for ${f.name}: ${out.length()}")
            if (f.sha256 != null && sha256(out) != f.sha256) { out.delete(); throw IOException("sha256 mismatch for ${f.name}") }
            done += f.size
        }
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IOException("rename failed")
        File(dir, ".installed").writeText("ok")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- helpers

    private fun setState(s: State) {
        state = s
        notifyChanged()
    }

    private fun notifyChanged() {
        main.post { onChanged?.run(); settingsListener?.run() }
    }

    private fun toast(ctx: Context, text: String) {
        val appCtx = ctx.applicationContext
        main.post { Toast.makeText(appCtx, text, Toast.LENGTH_LONG).show() }
    }
}
