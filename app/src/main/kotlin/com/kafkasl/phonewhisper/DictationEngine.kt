package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * App-wide dictation core shared by the floating-button accessibility service and the
 * voice keyboard: microphone capture, local (sherpa-onnx, incl. streaming) or
 * OpenAI-compatible cloud transcription, and optional cleanup.
 *
 * One instance per process, so the local model is loaded into memory only once no matter
 * how many front ends are enabled. All [Listener] callbacks run on the main thread.
 */
object DictationEngine {

    enum class State { IDLE, RECORDING, TRANSCRIBING }
    enum class StartResult { STARTED, BUSY, NO_PERMISSION, MIC_ERROR }
    enum class Notice { NONE, CLEANUP_NO_KEY, CLEANUP_FAILED }

    interface Listener {
        fun onStateChanged(state: State) {}
        /** Smoothed-ready microphone level in [0, 1] while recording. */
        fun onLevel(level: Float) {}
        fun onResult(text: String, notice: Notice)
        fun onError(message: String)
        /** Live mode only: the unfinished current segment changed. */
        fun onLivePartial(text: String) {}
        /** Live mode only: a segment is final; [forced] after [cutSegment]. */
        fun onLiveSegment(text: String, forced: Boolean) {}
    }

    const val SAMPLE_RATE = 16000
    private const val TAG = "DictationEngine"
    private const val MODEL_IDLE_RELEASE_MS = 2 * 60 * 1000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var appContext: Context? = null

    @Volatile var state = State.IDLE
        private set
    private var listener: Listener? = null
    /** Bumped on every start/cancel so callbacks from an abandoned utterance are dropped. */
    @Volatile private var generation = 0
    private var allowCleanup = true

    /**
     * True while the current utterance streams text live (streaming model loaded and the
     * owner asked for it). Live results skip cleanup here: the owner already typed the
     * segments and runs [cleanup] on what is actually in the editor.
     */
    @Volatile var isLive = false
        private set

    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    @Volatile private var streamSession: LocalTranscriber.StreamingSession? = null

    private val modelLock = Any()
    @Volatile private var localTranscriber: LocalTranscriber? = null
    @Volatile private var isModelLoading = false

    private val releaseModelRunnable = Runnable {
        if (state != State.IDLE) { scheduleModelRelease(); return@Runnable }
        thread {
            synchronized(modelLock) {
                localTranscriber?.let {
                    Log.i(TAG, "Releasing local transcriber model due to inactivity")
                    it.release()
                    localTranscriber = null
                }
            }
        }
    }

    /** Remember the application context. Call from every front end's onCreate. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun ctx(): Context = appContext ?: error("DictationEngine.attach() not called")
    private fun prefs(): SharedPreferences = ctx().getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)

    fun isOwner(l: Listener) = listener === l

    // --- Model lifecycle ---

    /** Load the local model in the background (if needed) and arm the idle-release timer. */
    fun preloadModel() {
        thread {
            initLocalModel()
            scheduleModelRelease()
        }
    }

    /** Load the local model if cloud transcription is not selected, and keep it loaded. */
    fun ensureModelLoaded() {
        handler.removeCallbacks(releaseModelRunnable)
        if (!prefs().getBoolean("use_local", true)) return
        if (localTranscriber == null) {
            Log.i(TAG, "Pre-loading model...")
            thread { initLocalModel() }
        }
    }

    fun scheduleModelRelease() {
        handler.removeCallbacks(releaseModelRunnable)
        handler.postDelayed(releaseModelRunnable, MODEL_IDLE_RELEASE_MS)
    }

    /** Reload after the selected model or decoding settings change. Lazy if nothing is loaded. */
    fun reloadModel() {
        if (appContext == null) return
        val wasLoaded = synchronized(modelLock) { localTranscriber != null || isModelLoading }
        if (!wasLoaded) return
        thread {
            synchronized(modelLock) {
                localTranscriber?.let {
                    Log.i(TAG, "Releasing old model for reload")
                    it.release()
                    localTranscriber = null
                }
            }
            initLocalModel()
            scheduleModelRelease()
        }
    }

    private fun initLocalModel() {
        synchronized(modelLock) {
            if (localTranscriber != null) return
            if (isModelLoading) return
            isModelLoading = true
        }
        try {
            val context = ctx()
            val modelName = prefs().getString("model_name", "") ?: ""
            val transcriber = if (modelName.isBlank() || !isLocalModelInstalled(context, modelName)) {
                val models = LocalTranscriber.availableModels(context)
                if (models.isNotEmpty()) {
                    Log.i(TAG, "Auto-detected model: ${models.first()}")
                    LocalTranscriber.create(context, models.first())
                } else null
            } else {
                LocalTranscriber.create(context, modelName)
            }
            synchronized(modelLock) {
                localTranscriber = transcriber
                Log.i(TAG, if (transcriber != null) "Local transcription ready" else "No local model found, will use API")
            }
        } finally {
            synchronized(modelLock) { isModelLoading = false }
        }
    }

    // --- Recording ---

    /**
     * Start capturing for [owner]. With [cleanup] false the transcript skips post-processing
     * (used when re-dictating a single word).
     */
    fun start(owner: Listener, cleanup: Boolean = true, live: Boolean = false): StartResult {
        if (state != State.IDLE) return StartResult.BUSY
        val context = ctx()
        val useLocal = prefs().getBoolean("use_local", true)
        if (useLocal) ensureModelLoaded()

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return StartResult.NO_PERMISSION
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) {
            return StartResult.NO_PERMISSION
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            return StartResult.MIC_ERROR
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return StartResult.MIC_ERROR
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            record.release()
            return StartResult.MIC_ERROR
        }

        val gen = ++generation
        listener = owner
        audioRecord = record
        pcmStream = ByteArrayOutputStream()
        // If a streaming model is already loaded, decode incrementally while recording so
        // the result is ready almost instantly on stop. Otherwise fall back to one-shot.
        val local = localTranscriber
        val streaming = useLocal && local != null && local.isStreaming
        isLive = live && streaming
        allowCleanup = cleanup && !isLive
        streamSession = if (streaming) local!!.newStreamingSession(
            SAMPLE_RATE, if (isLive) liveUpdates(gen) else null
        ) else null

        state = State.RECORDING
        owner.onStateChanged(State.RECORDING)

        thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING && generation == gen) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) {
                    pcmStream?.write(buf, 0, n)
                    val level = audioLevel(buf, n)
                    handler.post { if (generation == gen) listener?.onLevel(level) }
                    streamSession?.accept(PcmConverter.toFloatSamples(buf, n))
                }
            }
        }
        return StartResult.STARTED
    }

    private fun liveUpdates(gen: Int) = object : LocalTranscriber.StreamingSession.UpdateListener {
        override fun onPartial(text: String) {
            handler.post { if (generation == gen) listener?.onLivePartial(text) }
        }

        override fun onSegment(text: String, forced: Boolean) {
            handler.post { if (generation == gen) listener?.onLiveSegment(text, forced) }
        }
    }

    /** Live mode: finalize the segment being spoken now. Answered by onLiveSegment(forced = true). */
    fun cutSegment(): Boolean {
        if (!isLive || state != State.RECORDING) return false
        val session = streamSession ?: return false
        session.cut()
        return true
    }

    fun stopAndTranscribe() {
        if (state != State.RECORDING) return
        val gen = generation
        setState(State.TRANSCRIBING)

        stopAudio()
        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null
        val session = streamSession
        streamSession = null

        if (pcm.isEmpty()) { session?.abandon(); fail(gen, "No audio captured"); return }

        val useLocal = prefs().getBoolean("use_local", true)
        if (useLocal && session != null) {
            finishStreaming(gen, session)
        } else if (useLocal) {
            thread {
                var attempts = 0
                // Wait up to 10 seconds for the local model if it is still loading.
                while (localTranscriber == null && isModelLoading && attempts < 100) {
                    Thread.sleep(100)
                    attempts++
                }
                val local = localTranscriber
                if (local != null) transcribeLocal(gen, pcm, local)
                else fail(gen, "Local model not loaded yet. Please try again.")
            }
        } else {
            transcribeApi(gen, pcm)
        }
    }

    /** Abort whatever [owner] is doing (recording or transcribing); its result is dropped. */
    fun cancel(owner: Listener, onlyWhileRecording: Boolean = false) {
        if (listener !== owner || state == State.IDLE) return
        if (onlyWhileRecording && state != State.RECORDING) return
        Log.i(TAG, "Cancelling dictation in state $state")
        generation++
        stopAudio()
        streamSession?.abandon()
        streamSession = null
        pcmStream = null
        listener = null
        isLive = false
        state = State.IDLE
        owner.onStateChanged(State.IDLE)
    }

    private fun stopAudio() {
        audioRecord?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        audioRecord = null
    }

    private fun setState(s: State) {
        state = s
        listener?.onStateChanged(s)
    }

    // --- Transcription ---

    private fun finishStreaming(gen: Int, session: LocalTranscriber.StreamingSession) {
        thread {
            try {
                val t0 = System.currentTimeMillis()
                val text = session.finish()
                Log.i(TAG, "Streaming transcription drained in ${System.currentTimeMillis() - t0}ms")
                handleTranscript(gen, text)
            } catch (e: Exception) {
                Log.e(TAG, "Streaming transcription failed", e)
                scheduleModelRelease()
                fail(gen, "Local error: ${e.message}")
            }
        }
    }

    private fun transcribeLocal(gen: Int, pcm: ByteArray, transcriber: LocalTranscriber) {
        try {
            val samples = PcmConverter.toFloatSamples(pcm, pcm.size)
            val t0 = System.currentTimeMillis()
            val text = transcriber.transcribe(samples, SAMPLE_RATE)
            Log.i(TAG, "Local transcription: ${System.currentTimeMillis() - t0}ms, ${samples.size / SAMPLE_RATE}s audio")
            handleTranscript(gen, text)
        } catch (e: Exception) {
            Log.e(TAG, "Local transcription failed", e)
            scheduleModelRelease()
            fail(gen, "Local error: ${e.message}")
        }
    }

    private fun transcribeApi(gen: Int, pcm: ByteArray) {
        val prefs = prefs()
        val wav = WavWriter.encode(pcm)
        val apiKey = OpenAiCompatibleApi.apiKey(prefs, OpenAiCompatibleApi.TRANSCRIPTION_API_KEY_PREF)
        val apiBaseUrl = OpenAiCompatibleApi.apiBaseUrl(prefs, OpenAiCompatibleApi.TRANSCRIPTION_API_BASE_URL_PREF)
        val model = OpenAiCompatibleApi.model(
            prefs, OpenAiCompatibleApi.TRANSCRIPTION_MODEL_PREF, OpenAiCompatibleApi.DEFAULT_TRANSCRIPTION_MODEL
        )
        if (apiKey.isBlank()) { fail(gen, "Set transcription API key in Phone Whisper app"); return }

        TranscriberClient.transcribe(wav, apiKey, apiBaseUrl, model) { result ->
            if (!result.text.isNullOrBlank()) handleTranscript(gen, result.text)
            else fail(gen, "Error: ${result.error ?: "empty transcript"}")
        }
    }

    private fun handleTranscript(gen: Int, text: String?) {
        scheduleModelRelease()
        if (text.isNullOrBlank()) { fail(gen, "No speech detected"); return }

        if (!allowCleanup) { succeed(gen, text, Notice.NONE); return }
        cleanup(text) { cleaned, notice -> succeed(gen, cleaned, notice) }
    }

    fun isCleanupEnabled(): Boolean = prefs().getBoolean("use_post_processing", false)

    /**
     * Run the configured cleanup on [text]; the callback gets the cleaned text (or the
     * original plus a notice) on a background thread. No-op when cleanup is disabled.
     */
    fun cleanup(text: String, callback: (String, Notice) -> Unit) {
        val prefs = prefs()
        if (!prefs.getBoolean("use_post_processing", false)) { callback(text, Notice.NONE); return }
        val apiKey = OpenAiCompatibleApi.apiKey(prefs, OpenAiCompatibleApi.CLEANUP_API_KEY_PREF)
        if (apiKey.isBlank()) { callback(text, Notice.CLEANUP_NO_KEY); return }

        val apiBaseUrl = OpenAiCompatibleApi.apiBaseUrl(prefs, OpenAiCompatibleApi.CLEANUP_API_BASE_URL_PREF)
        val model = OpenAiCompatibleApi.model(
            prefs, OpenAiCompatibleApi.CLEANUP_MODEL_PREF, OpenAiCompatibleApi.DEFAULT_CLEANUP_MODEL
        )
        val prompt = prefs.getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
        PostProcessor.process(text, prompt, apiKey, apiBaseUrl, model) { result ->
            if (!result.text.isNullOrBlank()) callback(result.text, Notice.NONE)
            else callback(text, Notice.CLEANUP_FAILED)
        }
    }

    private fun succeed(gen: Int, text: String, notice: Notice) =
        finish(gen) { it.onResult(text, notice) }

    private fun fail(gen: Int, message: String) =
        finish(gen) { it.onError(message) }

    /** Return to IDLE on the main thread and deliver to the owner, unless the utterance was cancelled. */
    private fun finish(gen: Int, deliver: (Listener) -> Unit) {
        handler.post {
            if (gen != generation) return@post
            val l = listener
            listener = null
            isLive = false
            state = State.IDLE
            l?.onStateChanged(State.IDLE)
            l?.let(deliver)
        }
    }

    /** RMS level of a PCM chunk scaled for the recording animation. */
    private fun audioLevel(buf: ByteArray, byteCount: Int): Float {
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < byteCount) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sum / samples) / 32768.0
        return (rms * 8.5).coerceIn(0.02, 1.0).toFloat()
    }
}
