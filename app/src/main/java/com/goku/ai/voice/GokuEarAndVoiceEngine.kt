package com.goku.ai.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Global Emergency Kill Switch.
 * STRICT POLICY: Triggered ONLY via "Goku stop" / "Goku ruko" voice command
 * or the Floating Overlay / Dashboard STOP button. (No Volume-Down trigger.)
 */
object GokuKillSwitch {
    private val _isStopped = MutableStateFlow(false)
    val isStopped: StateFlow<Boolean> = _isStopped.asStateFlow()

    private val _lastStopSource = MutableStateFlow("")
    val lastStopSource: StateFlow<String> = _lastStopSource.asStateFlow()

    private val stateLock = Any()
    private val activeJobs = ArrayList<Job>()
    private val stopHooks = CopyOnWriteArrayList<() -> Unit>()

    fun registerJob(job: Job) {
        synchronized(stateLock) {
            activeJobs.removeAll { !it.isActive }
            if (job.isActive && !activeJobs.contains(job)) {
                activeJobs.add(job)
                job.invokeOnCompletion {
                    synchronized(stateLock) {
                        activeJobs.remove(job)
                    }
                }
            }
        }
    }

    fun registerStopHook(hook: () -> Unit) {
        stopHooks.addIfAbsent(hook)
    }

    fun unregisterStopHook(hook: () -> Unit) {
        stopHooks.remove(hook)
    }

    fun triggerStop(source: String) {
        val jobsToCancel: List<Job>
        synchronized(stateLock) {
            _isStopped.value = true
            _lastStopSource.value = source
            jobsToCancel = activeJobs.toList()
            activeJobs.clear()
        }
        jobsToCancel.forEach { runCatching { it.cancel() } }
        stopHooks.forEach { runCatching { it.invoke() } }
    }

    fun reset() {
        synchronized(stateLock) {
            _isStopped.value = false
        }
    }

    fun isStopPhrase(text: String): Boolean {
        val norm = text.lowercase(Locale.ROOT)
            .replace(Regex("[,\\.!?।:;\"'()]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return norm.contains("goku stop") ||
            norm.contains("goku ruko") ||
            norm.contains("गोकू स्टॉप") ||
            norm.contains("गोकू रुको") ||
            norm.contains("goku ruk") ||
            norm == "ruko goku" ||
            norm == "stop goku"
    }
}

enum class EarTier {
    IDLE_OFF,
    TIER1_WAKE_SLEEP,
    TIER2_PREWARM_REFLEX,
    TIER3_CONTINUOUS_TALK,
    SPEAKING,
    STOPPED
}

data class VoiceEngineState(
    val tier: EarTier = EarTier.IDLE_OFF,
    val isListening: Boolean = false,
    val isAcousticEarArmed: Boolean = false,
    val isSpeechRecognizerActive: Boolean = false,
    val isSpeaking: Boolean = false,
    val isWhisperMode: Boolean = false,
    val isNightSoftMode: Boolean = false,
    val partialTranscript: String = "",
    val lastError: String? = null,
    val isLockScreenMonitorActive: Boolean = false,
    val isKeyguardLocked: Boolean = false,
    val kwsEngineBackend: String = "Offline Acoustic VAD + SpeechRecognizer Hybrid",
    val isNativeKwsActive: Boolean = false,
    val isSherpaAarPresent: Boolean = false,
    val hasKwsOnnxModels: Boolean = false,
    val isPostBootMicSilenced: Boolean = false
)

typealias GokuVoiceState = VoiceEngineState

/**
 * Reflection-safe bridge to Sherpa-ONNX KeywordSpotter (com.k2fsa.sherpa.onnx.KeywordSpotter).
 * Automatically loads offline KWS ONNX models from `filesDir/models/kws/` (or extracts from
 * `assets/kws/` if bundled) whenever the Sherpa-ONNX AAR + KWS model files are present.
 * Falls back cleanly with zero crashes when the optional native AAR or `.onnx` files are absent.
 */
internal class SherpaOnnxKwsBridge(private val context: Context) {

    private var spotterInstance: Any? = null
    private var streamInstance: Any? = null
    private var acceptWaveformMethod: java.lang.reflect.Method? = null
    private var isReadyMethod: java.lang.reflect.Method? = null
    private var decodeMethod: java.lang.reflect.Method? = null
    private var getResultMethod: java.lang.reflect.Method? = null
    private var resetMethod: java.lang.reflect.Method? = null
    private var releaseSpotterMethod: java.lang.reflect.Method? = null
    private var releaseStreamMethod: java.lang.reflect.Method? = null
    private var keywordFieldOrGetter: ((Any) -> String)? = null

    private val bridgeLock = Any()

    @Volatile
    var isAvailable: Boolean = false
        private set

    @Volatile
    var isAarPresent: Boolean = detectSherpaAarPresent()
        private set

    @Volatile
    var hasOnnxModels: Boolean = false
        private set

    @Volatile
    var statusLabel: String = "Offline Acoustic VAD + SpeechRecognizer Hybrid"
        private set

    fun detectSherpaAarPresent(): Boolean = runCatching {
        Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
        true
    }.getOrDefault(false)

    fun refreshDiagnostics(): Triple<Boolean, Boolean, String> = synchronized(bridgeLock) {
        refreshDiagnosticsLocked()
    }

    private fun refreshDiagnosticsLocked(): Triple<Boolean, Boolean, String> {
        isAarPresent = detectSherpaAarPresent()
        val modelDir = prepareKwsModelDirectory()
        val files = modelDir?.listFiles().orEmpty()
        val hasEncoder = files.any { it.isFile && it.name.contains("encoder", ignoreCase = true) && it.name.endsWith(".onnx") }
        val hasDecoder = files.any { it.isFile && it.name.contains("decoder", ignoreCase = true) && it.name.endsWith(".onnx") }
        val hasJoiner = files.any { it.isFile && it.name.contains("joiner", ignoreCase = true) && it.name.endsWith(".onnx") }
        val hasTokens = modelDir != null && File(modelDir, "tokens.txt").exists()
        hasOnnxModels = hasEncoder && hasDecoder && hasJoiner && hasTokens
        statusLabel = when {
            isAvailable -> "Sherpa-ONNX Native 24x7 KWS Active"
            !isAarPresent && !hasOnnxModels ->
                "Acoustic VAD + SpeechRecognizer Hybrid (sherpa-onnx-android.aar & files/models/kws/*.onnx absent)"
            !isAarPresent ->
                "Acoustic VAD + SpeechRecognizer Hybrid (app/libs/sherpa-onnx-android.aar absent)"
            !hasOnnxModels ->
                "Acoustic VAD + SpeechRecognizer Hybrid (files/models/kws/*.onnx absent)"
            else ->
                "Acoustic VAD + SpeechRecognizer Hybrid"
        }
        return Triple(isAarPresent, hasOnnxModels, statusLabel)
    }

    fun initialize(): Boolean = synchronized(bridgeLock) {
        if (isAvailable) return@synchronized true
        isAarPresent = detectSherpaAarPresent()
        val modelDir = prepareKwsModelDirectory()
        if (modelDir == null) {
            refreshDiagnosticsLocked()
            return@synchronized false
        }

        val encoderFile = modelDir.listFiles()?.firstOrNull {
            it.isFile && it.name.contains("encoder", ignoreCase = true) && it.name.endsWith(".onnx")
        }
        val decoderFile = modelDir.listFiles()?.firstOrNull {
            it.isFile && it.name.contains("decoder", ignoreCase = true) && it.name.endsWith(".onnx")
        }
        val joinerFile = modelDir.listFiles()?.firstOrNull {
            it.isFile && it.name.contains("joiner", ignoreCase = true) && it.name.endsWith(".onnx")
        }
        val tokensFile = File(modelDir, "tokens.txt").takeIf { it.exists() }
        hasOnnxModels = encoderFile != null && decoderFile != null && joinerFile != null && tokensFile != null
        if (encoderFile == null || decoderFile == null || joinerFile == null || tokensFile == null || !isAarPresent) {
            refreshDiagnosticsLocked()
            return@synchronized false
        }

        val keywordsFile = ensureKeywordsFile(modelDir)

        return runCatching {
            val kwsClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
            val kwsConfigClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotterConfig")
            val featConfigClass = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
            val modelConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig")
            val transducerClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig")
            val streamClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineStream")

            val featConfig = featConfigClass.getDeclaredConstructor().newInstance().apply {
                setFieldOrProp(this, "sampleRate", 16000)
                setFieldOrProp(this, "featureDim", 80)
            }
            val transducerConfig = transducerClass.getDeclaredConstructor().newInstance().apply {
                setFieldOrProp(this, "encoder", encoderFile.absolutePath)
                setFieldOrProp(this, "decoder", decoderFile.absolutePath)
                setFieldOrProp(this, "joiner", joinerFile.absolutePath)
            }
            val modelConfig = modelConfigClass.getDeclaredConstructor().newInstance().apply {
                setFieldOrProp(this, "transducer", transducerConfig)
                setFieldOrProp(this, "tokens", tokensFile.absolutePath)
                setFieldOrProp(this, "numThreads", 2)
                setFieldOrProp(this, "provider", "cpu")
            }
            val kwsConfig = kwsConfigClass.getDeclaredConstructor().newInstance().apply {
                setFieldOrProp(this, "featConfig", featConfig)
                setFieldOrProp(this, "modelConfig", modelConfig)
                setFieldOrProp(this, "keywordsFile", keywordsFile.absolutePath)
                setFieldOrProp(this, "keywordsScore", 1.5f)
                setFieldOrProp(this, "keywordsThreshold", 0.25f)
            }

            val ctor = kwsClass.constructors.firstOrNull { it.parameterTypes.size == 2 }
                ?: kwsClass.constructors.firstOrNull { it.parameterTypes.size == 1 }
                ?: error("Unsupported Sherpa-ONNX KeywordSpotter constructor")

            val spotter = if (ctor.parameterTypes.size == 2) {
                ctor.newInstance(null, kwsConfig)
            } else {
                ctor.newInstance(kwsConfig)
            }

            val createStreamMethod = kwsClass.methods.firstOrNull {
                it.name == "createStream" && it.parameterTypes.isEmpty()
            } ?: kwsClass.methods.firstOrNull {
                it.name == "createStream" && it.parameterTypes.size == 1
            } ?: error("Missing createStream on KeywordSpotter")

            val stream = if (createStreamMethod.parameterTypes.isEmpty()) {
                createStreamMethod.invoke(spotter)
            } else {
                createStreamMethod.invoke(spotter, "")
            }

            acceptWaveformMethod = streamClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
            isReadyMethod = kwsClass.getMethod("isReady", streamClass)
            decodeMethod = kwsClass.getMethod("decode", streamClass)
            getResultMethod = kwsClass.getMethod("getResult", streamClass)
            resetMethod = kwsClass.methods.firstOrNull { it.name == "reset" && it.parameterTypes.size == 1 }
            releaseSpotterMethod = kwsClass.methods.firstOrNull { it.name == "release" && it.parameterTypes.isEmpty() }
            releaseStreamMethod = streamClass.methods.firstOrNull { it.name == "release" && it.parameterTypes.isEmpty() }

            keywordFieldOrGetter = { resultObj ->
                runCatching {
                    val getter = resultObj.javaClass.methods.firstOrNull { it.name == "getKeyword" }
                    if (getter != null) {
                        getter.invoke(resultObj)?.toString().orEmpty()
                    } else {
                        val field = resultObj.javaClass.getDeclaredField("keyword")
                        field.isAccessible = true
                        field.get(resultObj)?.toString().orEmpty()
                    }
                }.getOrDefault("")
            }

            spotterInstance = spotter
            streamInstance = stream
            isAvailable = true
            statusLabel = "Sherpa-ONNX Native 24x7 KWS Active"
            true
        }.getOrElse {
            isAvailable = false
            refreshDiagnosticsLocked()
            false
        }
    }

    /**
     * Feeds 16kHz normalized float PCM samples into Sherpa-ONNX KeywordSpotter.
     * Returns detected keyword ("hey goku", "goku stop", "goku ruko") or null.
     */
    fun processPcmFrame(samples: FloatArray, sampleRate: Int = 16000): String? = synchronized(bridgeLock) {
        if (!isAvailable) return@synchronized null
        val spotter = spotterInstance ?: return@synchronized null
        val stream = streamInstance ?: return@synchronized null

        return@synchronized runCatching {
            acceptWaveformMethod?.invoke(stream, samples, sampleRate)
            while ((isReadyMethod?.invoke(spotter, stream) as? Boolean) == true) {
                decodeMethod?.invoke(spotter, stream)
            }
            val resultObj = getResultMethod?.invoke(spotter, stream) ?: return@runCatching null
            val detected = keywordFieldOrGetter?.invoke(resultObj)?.trim().orEmpty()
            if (detected.isNotEmpty()) {
                resetMethod?.invoke(spotter, stream)
                detected
            } else {
                null
            }
        }.getOrNull()
    }

    fun release() = synchronized(bridgeLock) {
        runCatching { streamInstance?.let { releaseStreamMethod?.invoke(it) } }
        runCatching { spotterInstance?.let { releaseSpotterMethod?.invoke(it) } }
        streamInstance = null
        spotterInstance = null
        isAvailable = false
    }

    private fun prepareKwsModelDirectory(): File? {
        val dir = File(context.filesDir, "models/kws")
        if (!dir.exists()) dir.mkdirs()

        // If model files are already in filesDir/models/kws, use them directly
        val hasOnnx = dir.listFiles()?.any { it.isFile && it.name.endsWith(".onnx") } == true
        if (!hasOnnx) {
            // Auto-extract bundled assets/kws/* if shipped inside APK assets
            runCatching {
                val assetFiles = context.assets.list("kws").orEmpty()
                for (name in assetFiles) {
                    val outFile = File(dir, name)
                    if (!outFile.exists()) {
                        context.assets.open("kws/$name").use { input ->
                            FileOutputStream(outFile).use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
        }
        return dir
    }

    private fun ensureKeywordsFile(dir: File): File {
        val kwFile = File(dir, "keywords.txt")
        if (!kwFile.exists()) {
            runCatching {
                kwFile.writeText(
                    """
                    ▁HEY ▁GO KU @hey goku
                    ▁GO KU ▁STOP @goku stop
                    ▁GO KU ▁RU KO @goku ruko
                    """.trimIndent() + "\n"
                )
            }
        }
        return kwFile
    }

    private fun setFieldOrProp(target: Any, name: String, value: Any) {
        val setterName = "set" + name.replaceFirstChar { it.uppercaseChar() }
        val setter = target.javaClass.methods.firstOrNull {
            it.name == setterName && it.parameterTypes.size == 1
        }
        if (setter != null) {
            setter.invoke(target, value)
            return
        }
        val field = runCatching { target.javaClass.getDeclaredField(name) }.getOrNull()
        if (field != null) {
            field.isAccessible = true
            field.set(target, value)
        }
    }
}

/**
 * REAL 24x7 Lock-Screen Wake Word & 0.1s Emergency Kill-Switch Engine.
 *
 * Runs a dedicated `Dispatchers.IO` coroutine loop using `AudioRecord`
 * (`VOICE_RECOGNITION`, `16000 Hz`, `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`) that
 * keeps listening even while `KeyguardManager.isKeyguardLocked == true` AND while
 * `TextToSpeech` is actively speaking.
 *
 * - Primary backend: Sherpa-ONNX `KeywordSpotter` (if KWS `.onnx` models & native library exist).
 * - Automatic zero-crash fallback: Built-in offline acoustic VAD + energy/ZCR barge-in detector
 *   paired with Android SpeechRecognizer so `"Hey Goku"` and `"Goku stop"` / `"Goku ruko"`
 *   work immediately out-of-the-box without external files.
 */
class LockScreenWakeWordEngine(
    private val context: Context,
    private val isTtsSpeakingProvider: () -> Boolean,
    private val onWakeWordDetected: (isKeyguardLocked: Boolean, detectedPhrase: String) -> Unit,
    private val onEmergencyStopDetected: (source: String) -> Unit,
    private val onBargeInSpeechDetected: (isKeyguardLocked: Boolean) -> Unit,
    private val onMonitorStateChanged: (active: Boolean, locked: Boolean, backendLabel: String) -> Unit,
    private val onPostBootMicSilenceChanged: (silenced: Boolean) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val sherpaBridge = SherpaOnnxKwsBridge(context)

    private val running = AtomicBoolean(false)
    private val pausedForExternalRecognizer = AtomicBoolean(false)
    private val audioRecordReleased = AtomicBoolean(true)
    private val pauseLock = Any()
    private val audioHardwareLock = Any()
    private var activeAudioRecord = false
    private var activeAudioRecordRef: AudioRecord? = null
    private var pauseAckDeferred: CompletableDeferred<Unit>? = null
    @Volatile
    private var monitorJob: Job? = null
    @Volatile
    private var lastStoppingJob: Job? = null

    fun isRunning(): Boolean = running.get()

    fun isNativeKwsActive(): Boolean = sherpaBridge.isAvailable
    fun isSherpaAarPresent(): Boolean = sherpaBridge.isAarPresent
    fun hasKwsOnnxModels(): Boolean = sherpaBridge.hasOnnxModels

    fun refreshKwsDiagnostics(): Triple<Boolean, Boolean, String> = sherpaBridge.refreshDiagnostics()

    fun isDeviceLocked(): Boolean = runCatching {
        keyguardManager?.isKeyguardLocked == true
    }.getOrDefault(false)

    private fun interruptActiveAudioRecord() {
        synchronized(audioHardwareLock) {
            val rec = activeAudioRecordRef ?: return
            if (!audioRecordReleased.get()) {
                runCatching {
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        rec.stop()
                    }
                }
            }
        }
    }

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onMonitorStateChanged(false, isDeviceLocked(), "Mic permission required")
            return
        }
        if (running.getAndSet(true)) return
        pausedForExternalRecognizer.set(false)

        val oldJob = monitorJob
        val stoppingJob = lastStoppingJob
        monitorJob = scope.launch {
            stoppingJob?.join()
            oldJob?.cancelAndJoin()
            if (lastStoppingJob === stoppingJob) {
                lastStoppingJob = null
            }
            if (!running.get()) return@launch
            sherpaBridge.initialize()
            onMonitorStateChanged(true, isDeviceLocked(), sherpaBridge.statusLabel)
            runAudioCaptureLoop()
        }
    }

    /**
     * Temporarily yields the microphone when SpeechRecognizer is actively capturing a full
     * command utterance (prevents AudioRecord / SpeechRecognizer contention on single-mic OEMs)
     * by immediately interrupting AudioRecord.stop() and suspending until AudioRecord releases.
     */
    suspend fun pauseForRecognizer(timeoutMs: Long = 1200L) {
        val stoppingJob = lastStoppingJob
        val deferred = synchronized(pauseLock) {
            pausedForExternalRecognizer.set(true)
            if (running.get() && activeAudioRecord) {
                pauseAckDeferred ?: CompletableDeferred<Unit>().also { pauseAckDeferred = it }
            } else null
        }
        if (deferred != null) {
            // Proactively unblock blocking AudioRecord.read() so the mic lock is released immediately
            interruptActiveAudioRecord()
        }
        if (deferred != null || (stoppingJob != null && stoppingJob.isActive)) {
            withTimeoutOrNull(timeoutMs) {
                if (stoppingJob != null && stoppingJob.isActive) {
                    stoppingJob.join()
                }
                deferred?.await()
            }
        }
    }

    fun resumeAfterRecognizer() {
        pausedForExternalRecognizer.set(false)
    }

    fun stop() {
        running.set(false)
        pausedForExternalRecognizer.set(false)
        interruptActiveAudioRecord()
        synchronized(pauseLock) {
            pauseAckDeferred?.complete(Unit)
            pauseAckDeferred = null
        }
        val activeJob = monitorJob
        monitorJob = null
        if (activeJob != null) {
            lastStoppingJob = activeJob
            activeJob.cancel()
        }
        onMonitorStateChanged(false, isDeviceLocked(), sherpaBridge.statusLabel)
    }

    suspend fun stopAndJoin() {
        stop()
        lastStoppingJob?.join()
    }

    fun release() {
        stop()
        sherpaBridge.release()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private suspend fun runAudioCaptureLoop() {
        val sampleRate = 16000
        val frameSamples = 1600 // 100ms frame -> 0.1s kill-switch & barge-in resolution
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameSamples * 4)

        val pcmShorts = ShortArray(frameSamples)
        val pcmFloats = FloatArray(frameSamples)

        var noiseFloorDb = -50.0
        var speechBurstFrames = 0
        var bargeInBurstFrames = 0
        var consecutiveZeroFrames = 0
        var micSilencedNotified = false
        var lastTriggerAtMs = 0L

        while (running.get() && scope.isActive) {
            if (pausedForExternalRecognizer.get()) {
                delay(80L)
                continue
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                onMonitorStateChanged(false, isDeviceLocked(), "Mic permission revoked")
                running.set(false)
                return
            }

            var audioRecord: AudioRecord? = null
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { audioRecord.release() }
                    delay(600L)
                    continue
                }

                val shouldYield = synchronized(pauseLock) {
                    if (pausedForExternalRecognizer.get()) {
                        true
                    } else {
                        activeAudioRecord = true
                        false
                    }
                }
                if (shouldYield) {
                    delay(80L)
                    continue
                }
                synchronized(audioHardwareLock) {
                    activeAudioRecordRef = audioRecord
                    audioRecordReleased.set(false)
                }
                audioRecord.startRecording()

                while (running.get() && !pausedForExternalRecognizer.get() && scope.isActive) {
                    val readCount = audioRecord.read(pcmShorts, 0, frameSamples)
                    if (readCount < 0) {
                        delay(100L)
                        break
                    }
                    if (readCount == 0) {
                        delay(40L)
                        continue
                    }

                    val locked = isDeviceLocked()
                    val speakingNow = isTtsSpeakingProvider()

                    // Compute normalized floats, RMS energy (dBFS), Peak amplitude, ZCR, and all-zero check
                    var sumSq = 0.0
                    var peakAbs = 0.0
                    var zeroCrossings = 0
                    var allZero = true
                    var prev = pcmShorts[0]
                    for (i in 0 until readCount) {
                        val s = pcmShorts[i]
                        if (s.toInt() != 0) allZero = false
                        val f = s / 32768.0f
                        pcmFloats[i] = f
                        val af = abs(f.toDouble())
                        if (af > peakAbs) peakAbs = af
                        sumSq += f * f
                        if ((s >= 0 && prev < 0) || (s < 0 && prev >= 0)) {
                            zeroCrossings++
                        }
                        prev = s
                    }

                    // Bug #6: Detect Android 11-15 post-boot background mic privacy silence (all-zero PCM)
                    if (allZero) {
                        consecutiveZeroFrames++
                        if (consecutiveZeroFrames >= 25 && !micSilencedNotified) {
                            micSilencedNotified = true
                            onPostBootMicSilenceChanged(true)
                        }
                        speechBurstFrames = 0
                        bargeInBurstFrames = 0
                        continue
                    } else {
                        consecutiveZeroFrames = 0
                        if (micSilencedNotified) {
                            micSilencedNotified = false
                            onPostBootMicSilenceChanged(false)
                        }
                    }

                    val rms = sqrt(sumSq / readCount).coerceAtLeast(1e-7)
                    val db = 20.0 * log10(rms)
                    val zcr = zeroCrossings.toDouble() / readCount.toDouble()
                    val crestFactor = peakAbs / rms

                    // 1. Primary path: Native Sherpa-ONNX offline KeywordSpotter (100ms frame)
                    if (sherpaBridge.isAvailable) {
                        val slice = if (readCount == frameSamples) pcmFloats else pcmFloats.copyOf(readCount)
                        val detected = sherpaBridge.processPcmFrame(slice, sampleRate)
                        if (!detected.isNullOrBlank()) {
                            val now = System.currentTimeMillis()
                            if (GokuKillSwitch.isStopPhrase(detected)) {
                                // Instant 0.1s emergency kill switch even while TTS is speaking or screen is locked
                                lastTriggerAtMs = now
                                onEmergencyStopDetected("LockScreen KWS: $detected")
                                continue
                            }
                            if (now - lastTriggerAtMs > 1000L) {
                                lastTriggerAtMs = now
                                onWakeWordDetected(locked, detected)
                                continue
                            }
                        }
                    }

                    // 2. Hardened Offline Acoustic VAD + Speech Cadence Gate (when Sherpa-ONNX is absent)
                    // Tighter human speech-band ZCR (0.02..0.24) + crest factor (1.6..11.0) rejects TV/fan drone
                    val looksLikeHumanSpeech = zcr in 0.02..0.24 && crestFactor in 1.6..11.0
                    val snrDb = db - noiseFloorDb

                    if (speakingNow) {
                        // During TTS output, detect deliberate close-range human barge-in
                        if (looksLikeHumanSpeech && db > -21.0 && snrDb > 15.0) {
                            bargeInBurstFrames++
                            if (bargeInBurstFrames >= 3) { // ~300ms sustained speech cadence
                                val now = System.currentTimeMillis()
                                if (now - lastTriggerAtMs > 1600L) {
                                    lastTriggerAtMs = now
                                    bargeInBurstFrames = 0
                                    onBargeInSpeechDetected(locked)
                                }
                            }
                        } else {
                            bargeInBurstFrames = 0
                        }
                    } else {
                        // Update adaptive ambient noise floor smoothly during non-speech / steady ambient frames
                        if (snrDb < 7.0 || !looksLikeHumanSpeech) {
                            noiseFloorDb = (noiseFloorDb * 0.93) + (db * 0.07)
                            noiseFloorDb = noiseFloorDb.coerceIn(-66.0, -26.0)
                            speechBurstFrames = 0
                        } else if (db > -28.0 && snrDb > 13.5) {
                            speechBurstFrames++
                            if (speechBurstFrames >= 3) { // ~300ms clear voiced speech onset
                                val now = System.currentTimeMillis()
                                if (now - lastTriggerAtMs > 2600L) {
                                    lastTriggerAtMs = now
                                    speechBurstFrames = 0
                                    onBargeInSpeechDetected(locked)
                                }
                            }
                        } else {
                            speechBurstFrames = 0
                        }
                    }
                }
            } catch (_: SecurityException) {
                onMonitorStateChanged(false, isDeviceLocked(), "Mic SecurityException")
                running.set(false)
                return
            } catch (_: Throwable) {
                delay(400L)
            } finally {
                synchronized(audioHardwareLock) {
                    audioRecordReleased.set(true)
                    val rec = audioRecord
                    if (activeAudioRecordRef === rec) {
                        activeAudioRecordRef = null
                    }
                    runCatching {
                        if (rec?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            rec.stop()
                        }
                    }
                    runCatching { rec?.release() }
                }
                val waiter = synchronized(pauseLock) {
                    activeAudioRecord = false
                    pauseAckDeferred.also { pauseAckDeferred = null }
                }
                waiter?.complete(Unit)
            }
        }
    }
}

/**
 * 3-Tier Low-Power Ear + 24x7 Lock-Screen Wake Word + Hindi/Hinglish Natural Prosody Voice Engine.
 * - Real 24x7 Lock-Screen AudioRecord KWS/VAD loop (continues when screen is locked).
 * - Instant 0.1s "Goku stop" / "Goku ruko" kill-switch detection even while TTS is speaking.
 * - "Hey Goku" wake-word detection + 6-second Continuous Talk window + Barge-In.
 * - Private Earphone Whisper Mode + Night/DND soft volume adaptation.
 */
class GokuEarAndVoiceEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var captureStartJob: Job? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    private val _state = MutableStateFlow(VoiceEngineState())
    val state: StateFlow<VoiceEngineState> = _state.asStateFlow()

    private val sessionGeneration = AtomicLong(0L)
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentRecognizerIsOnDevice: Boolean? = null
    @Volatile
    private var onDeviceLanguageUnavailable: Boolean = false
    @Volatile
    private var strictWakeGateActive: Boolean = false
    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false
    @Volatile
    private var continuousMode = false
    @Volatile
    private var continuousWindowUntilMs = 0L
    @Volatile
    private var onCommandRecognized: ((String) -> Unit)? = null
    @Volatile
    private var restartRunnable: Runnable? = null
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val activeOneShotRecognizers = CopyOnWriteArrayList<SpeechRecognizer>()

    private val lockScreenWakeWordEngine: LockScreenWakeWordEngine = LockScreenWakeWordEngine(
        context = context.applicationContext,
        isTtsSpeakingProvider = { _state.value.isSpeaking },
        onWakeWordDetected = { isLocked, phrase ->
            mainHandler.post {
                GokuKillSwitch.reset()
                stopSpeaking()
                strictWakeGateActive = false
                continuousWindowUntilMs = System.currentTimeMillis() + 7000L
                _state.update {
                    it.copy(
                        tier = EarTier.TIER2_PREWARM_REFLEX,
                        isKeyguardLocked = isLocked,
                        partialTranscript = phrase
                    )
                }
                startRecognizerCapture(openDirectWindow = true)
            }
        },
        onEmergencyStopDetected = { source ->
            // Instant 0.1s kill switch: halt TTS immediately and cancel all jobs
            stopSpeaking()
            GokuKillSwitch.triggerStop(source)
            mainHandler.post {
                _state.update {
                    it.copy(
                        tier = EarTier.STOPPED,
                        isSpeaking = false,
                        isSpeechRecognizerActive = false,
                        isListening = it.isAcousticEarArmed,
                        partialTranscript = source
                    )
                }
                speak("Ruk gaya, sab band kar diya.", urgent = true)
            }
        },
        onBargeInSpeechDetected = { isLocked ->
            mainHandler.post {
                if (GokuKillSwitch.isStopped.value) return@post
                // Barge-In during TTS: interrupt TTS and open strict wake-word verification window
                if (_state.value.isSpeaking) {
                    stopSpeaking()
                }
                _state.update {
                    it.copy(
                        tier = EarTier.TIER2_PREWARM_REFLEX,
                        isKeyguardLocked = isLocked
                    )
                }
                // Check isSpeechRecognizerActive (NOT isAcousticEarArmed/isListening) so lock-screen
                // VAD immediately launches SpeechRecognizer when Sherpa-ONNX AAR is absent.
                if (!_state.value.isSpeechRecognizerActive) {
                    // Enforce strict wake-word gate on ambient VAD onset so TV/chatter cannot wake Goku
                    startRecognizerCapture(openDirectWindow = false)
                }
            }
        },
        onMonitorStateChanged = { active, locked, backendLabel ->
            _state.update {
                it.copy(
                    isLockScreenMonitorActive = active,
                    isAcousticEarArmed = active,
                    isListening = active || it.isSpeechRecognizerActive,
                    isKeyguardLocked = locked,
                    kwsEngineBackend = backendLabel,
                    isNativeKwsActive = lockScreenWakeWordEngine.isNativeKwsActive(),
                    isSherpaAarPresent = lockScreenWakeWordEngine.isSherpaAarPresent(),
                    hasKwsOnnxModels = lockScreenWakeWordEngine.hasKwsOnnxModels()
                )
            }
        },
        onPostBootMicSilenceChanged = { silenced ->
            _state.update { it.copy(isPostBootMicSilenced = silenced) }
        }
    )

    private val killSwitchStopHook: () -> Unit = {
        val stopGen = sessionGeneration.incrementAndGet()
        continuousMode = false
        strictWakeGateActive = false
        continuousWindowUntilMs = 0L
        captureStartJob?.cancel()
        captureStartJob = null
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
        stopSpeaking()
        mainHandler.post {
            if (sessionGeneration.get() == stopGen) {
                runCatching { speechRecognizer?.cancel() }
                activeOneShotRecognizers.forEach { rec ->
                    runCatching { rec.cancel() }
                    runCatching { rec.destroy() }
                }
                activeOneShotRecognizers.clear()
                _state.update {
                    it.copy(
                        tier = EarTier.STOPPED,
                        isSpeaking = false,
                        isSpeechRecognizerActive = false,
                        isListening = it.isAcousticEarArmed,
                        partialTranscript = ""
                    )
                }
            }
        }
    }

    init {
        initTts()
        refreshWakeWordDiagnostics()
        GokuKillSwitch.registerStopHook(killSwitchStopHook)
    }

    fun refreshWakeWordDiagnostics() {
        val (hasAar, hasModels, label) = lockScreenWakeWordEngine.refreshKwsDiagnostics()
        _state.update {
            it.copy(
                kwsEngineBackend = label,
                isNativeKwsActive = lockScreenWakeWordEngine.isNativeKwsActive(),
                isSherpaAarPresent = hasAar,
                hasKwsOnnxModels = hasModels
            )
        }
    }

    fun setCommandCallback(callback: (String) -> Unit) {
        onCommandRecognized = callback
    }

    private fun initTts() {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val hindi = Locale("hi", "IN")
                    val res = tts?.setLanguage(hindi)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale("en", "IN"))
                    }
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _state.update {
                                it.copy(
                                    isSpeaking = true,
                                    tier = EarTier.SPEAKING
                                )
                            }
                            // Keep 24x7 AudioRecord barge-in / KWS ear active DURING TTS speech
                            if (continuousMode) {
                                lockScreenWakeWordEngine.resumeAfterRecognizer()
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
                            val stopped = GokuKillSwitch.isStopped.value
                            _state.update {
                                it.copy(
                                    isSpeaking = false,
                                    tier = if (stopped) EarTier.STOPPED else it.tier
                                )
                            }
                            if (continuousMode && !stopped) {
                                // Open 6-second Continuous Talk window right after Goku finishes speaking
                                continuousWindowUntilMs = System.currentTimeMillis() + 6000L
                                _state.update { it.copy(tier = EarTier.TIER3_CONTINUOUS_TALK) }
                                scheduleRestartListening(200L)
                            }
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
                            _state.update {
                                it.copy(
                                    isSpeaking = false,
                                    tier = if (GokuKillSwitch.isStopped.value) EarTier.STOPPED else it.tier
                                )
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            utteranceId?.let { pendingUtterances.remove(it)?.complete(Unit) }
                            _state.update {
                                it.copy(
                                    isSpeaking = false,
                                    tier = if (GokuKillSwitch.isStopped.value) EarTier.STOPPED else it.tier
                                )
                            }
                        }
                    })
                    ttsReady = true
                }
            }
        }
    }

    fun isEarphoneConnected(): Boolean {
        val am = audioManager ?: return false
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    fun isNightOrDnd(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isNight = hour >= 22 || hour < 6
        val dndActive = runCatching {
            val filter = notificationManager?.currentInterruptionFilter
            filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)
        return isNight || dndActive
    }

    fun speak(
        text: String,
        urgent: Boolean = false,
        flushQueue: Boolean = true
    ) {
        if (text.isBlank()) return
        if (GokuKillSwitch.isStopped.value && !urgent) return

        val whisper = isEarphoneConnected()
        val nightSoft = isNightOrDnd()
        _state.update {
            it.copy(
                isWhisperMode = whisper,
                isNightSoftMode = nightSoft
            )
        }

        val engine = tts
        if (!ttsReady || engine == null) return

        // Natural Hinglish prosody adaptation
        val speechRate = when {
            urgent -> 1.12f
            whisper -> 0.96f
            nightSoft -> 0.92f
            else -> 1.03f
        }
        val pitch = if (whisper || nightSoft) 0.95f else 1.02f
        engine.setSpeechRate(speechRate)
        engine.setPitch(pitch)

        val params = Bundle().apply {
            val vol = when {
                urgent -> 1.0f
                whisper -> 0.65f
                nightSoft -> 0.45f
                else -> 0.92f
            }
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol)
        }

        val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "goku_${UUID.randomUUID()}"
        runCatching {
            engine.speak(text, queueMode, params, utteranceId)
        }
    }

    /**
     * Speaks text via TTS and suspends cleanly until utterance completes (or timeout elapses).
     * Used by SmartCallScreenerService's Half-Duplex 2-Way Caller Negotiation Loop.
     */
    suspend fun speakAndAwaitCompletion(
        text: String,
        urgent: Boolean = true,
        timeoutMs: Long = 12_000L
    ) {
        val engine = tts
        if (text.isBlank() || !ttsReady || engine == null) return
        val utteranceId = "goku_await_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<Unit>()
        pendingUtterances[utteranceId] = deferred

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, if (urgent) 1.0f else 0.92f)
        }
        engine.setSpeechRate(1.02f)
        engine.setPitch(1.0f)
        val started = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId) == TextToSpeech.SUCCESS
        }.getOrDefault(false)

        if (!started) {
            pendingUtterances.remove(utteranceId)
            return
        }
        try {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }
        } finally {
            pendingUtterances.remove(utteranceId)
        }
    }

    /**
     * Captures a single 5-second speech utterance (e.g. from an incoming phone caller during
     * Half-Duplex Smart Call Screening), temporarily yielding the 24x7 wake-word ear.
     */
    suspend fun listenSingleUtterance(timeoutMs: Long = 5_000L): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return ""

        val resumeContinuous = continuousMode

        return try {
            lockScreenWakeWordEngine.pauseForRecognizer()
            stopSpeaking()
            val result = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String> { cont ->
                    var oneShotRecognizer: SpeechRecognizer? = null
                    mainHandler.post {
                        if (!cont.isActive) return@post
                        val onDeviceOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            !onDeviceLanguageUnavailable &&
                            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
                        val useOnDevice = onDeviceOk && !isNetworkConnected()
                        oneShotRecognizer = runCatching {
                            if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)
                            } else {
                                SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
                            }
                        }.getOrNull()

                        val rec = oneShotRecognizer
                        if (rec == null) {
                            if (cont.isActive) cont.resume("")
                            return@post
                        }
                        activeOneShotRecognizers.add(rec)

                        var lastPartial = ""
                        rec.setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {}
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(rmsdB: Float) {}
                            override fun onBufferReceived(buffer: ByteArray?) {}
                            override fun onEndOfSpeech() {}
                            override fun onEvent(eventType: Int, params: Bundle?) {}

                            override fun onPartialResults(partialResults: Bundle?) {
                                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()?.trim().orEmpty()
                                if (p.isNotBlank()) lastPartial = p
                            }

                            override fun onResults(results: Bundle?) {
                                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()?.trim().orEmpty().ifBlank { lastPartial }
                                activeOneShotRecognizers.remove(rec)
                                runCatching { rec.destroy() }
                                if (cont.isActive) cont.resume(spoken)
                            }

                            override fun onError(error: Int) {
                                if (useOnDevice && (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
                                ) {
                                    onDeviceLanguageUnavailable = true
                                }
                                activeOneShotRecognizers.remove(rec)
                                runCatching { rec.destroy() }
                                if (cont.isActive) cont.resume(lastPartial)
                            }
                        })

                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 2)
                            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
                            if (useOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        }

                        runCatching { rec.startListening(intent) }.onFailure {
                            activeOneShotRecognizers.remove(rec)
                            runCatching { rec.destroy() }
                            if (cont.isActive) cont.resume("")
                        }
                    }

                    cont.invokeOnCancellation {
                        mainHandler.post {
                            oneShotRecognizer?.let { activeOneShotRecognizers.remove(it) }
                            runCatching { oneShotRecognizer?.cancel() }
                            runCatching { oneShotRecognizer?.destroy() }
                        }
                    }
                }
            }.orEmpty()
            result.trim()
        } finally {
            if (resumeContinuous && continuousMode && !GokuKillSwitch.isStopped.value) {
                lockScreenWakeWordEngine.resumeAfterRecognizer()
            }
        }
    }

    /**
     * Bug #6 Fix: Called when the user taps the Floating Orb once after reboot to clear Android 11-15
     * background mic silence restrictions, restart AudioRecord cleanly, and open a direct listening window.
     */
    fun unlockPostBootMicAndListen() {
        _state.update { it.copy(isPostBootMicSilenced = false, lastError = null) }
        lockScreenWakeWordEngine.stop()
        startListening(continuous = true, openDirectWindow = true)
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
        pendingUtterances.values.forEach { it.complete(Unit) }
        pendingUtterances.clear()
        _state.update { it.copy(isSpeaking = false) }
    }

    fun startLockScreenMonitor() {
        lockScreenWakeWordEngine.start()
    }

    fun stopLockScreenMonitor() {
        lockScreenWakeWordEngine.stop()
    }

    fun startListening(continuous: Boolean = true, openDirectWindow: Boolean = false) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _state.update {
                it.copy(lastError = "RECORD_AUDIO permission required for Goku Ear")
            }
            return
        }

        sessionGeneration.incrementAndGet()
        GokuKillSwitch.reset()
        continuousMode = continuous
        if (openDirectWindow) {
            continuousWindowUntilMs = System.currentTimeMillis() + 8000L
        }

        // Always start the 24x7 Lock-Screen AudioRecord Wake-Word & Kill-Switch monitor in continuous mode
        if (continuous) {
            lockScreenWakeWordEngine.start()
        }

        val locked = keyguardManager?.isKeyguardLocked == true
        if (locked && !openDirectWindow) {
            // While locked and idle, rely on low-power 24x7 AudioRecord KWS/VAD loop until wake/speech onset.
            // Keep isAcousticEarArmed=true and isSpeechRecognizerActive=false so acoustic VAD triggers
            // SpeechRecognizer immediately when Sherpa-ONNX AAR is absent.
            lockScreenWakeWordEngine.resumeAfterRecognizer()
            _state.update {
                it.copy(
                    tier = EarTier.TIER1_WAKE_SLEEP,
                    isAcousticEarArmed = continuous,
                    isSpeechRecognizerActive = false,
                    isListening = continuous,
                    isKeyguardLocked = true,
                    isWhisperMode = isEarphoneConnected(),
                    isNightSoftMode = isNightOrDnd(),
                    lastError = null
                )
            }
            return
        }

        startRecognizerCapture(openDirectWindow = openDirectWindow)
    }

    private fun isNetworkConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun startRecognizerCapture(openDirectWindow: Boolean) {
        val targetGen = sessionGeneration.get()
        captureStartJob?.cancel()
        val job = engineScope.launch {
            if (sessionGeneration.get() != targetGen || GokuKillSwitch.isStopped.value) return@launch
            val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !onDeviceLanguageUnavailable &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
            val standardAvailable = SpeechRecognizer.isRecognitionAvailable(context)

            if (!standardAvailable && !onDeviceAvailable) {
                // Keep 24x7 AudioRecord KWS running even if Google SpeechRecognizer service is absent
                lockScreenWakeWordEngine.resumeAfterRecognizer()
                _state.update {
                    it.copy(
                        isSpeechRecognizerActive = false,
                        isListening = it.isAcousticEarArmed,
                        lastError = "System SpeechRecognizer unavailable; 24x7 AudioRecord KWS active"
                    )
                }
                return@launch
            }

            // Barge-in: immediately interrupt TTS when user taps to speak
            if (openDirectWindow) stopSpeaking()

            val locked = keyguardManager?.isKeyguardLocked == true
            val online = isNetworkConnected()
            val preferOnDevice = onDeviceAvailable && (locked || !online)

            // Strict wake-word gate applies whenever capture was triggered passively (not direct tap / active window)
            strictWakeGateActive = !openDirectWindow && System.currentTimeMillis() > continuousWindowUntilMs

            // Yield AudioRecord while SpeechRecognizer holds the mic stream (interrupts & awaits release off-UI)
            lockScreenWakeWordEngine.pauseForRecognizer()
            if (!isActive || sessionGeneration.get() != targetGen || GokuKillSwitch.isStopped.value) {
                if (continuousMode && !GokuKillSwitch.isStopped.value) {
                    lockScreenWakeWordEngine.resumeAfterRecognizer()
                }
                return@launch
            }
            ensureRecognizer(preferOnDevice = preferOnDevice)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                if (preferOnDevice) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            runCatching {
                speechRecognizer?.startListening(intent)
                val tier = if (System.currentTimeMillis() < continuousWindowUntilMs) {
                    EarTier.TIER3_CONTINUOUS_TALK
                } else {
                    EarTier.TIER1_WAKE_SLEEP
                }
                _state.update {
                    it.copy(
                        tier = tier,
                        isSpeechRecognizerActive = true,
                        isListening = true,
                        isKeyguardLocked = locked,
                        isWhisperMode = isEarphoneConnected(),
                        isNightSoftMode = isNightOrDnd(),
                        lastError = null
                    )
                }
            }.onFailure { err ->
                lockScreenWakeWordEngine.resumeAfterRecognizer()
                _state.update {
                    it.copy(
                        isSpeechRecognizerActive = false,
                        isListening = it.isAcousticEarArmed,
                        lastError = err.message
                    )
                }
            }
        }
        captureStartJob = job
        GokuKillSwitch.registerJob(job)
    }

    fun stopListening() {
        val stopGen = sessionGeneration.incrementAndGet()
        continuousMode = false
        strictWakeGateActive = false
        captureStartJob?.cancel()
        captureStartJob = null
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
        lockScreenWakeWordEngine.stop()
        mainHandler.post {
            if (sessionGeneration.get() != stopGen) return@post
            runCatching { speechRecognizer?.stopListening() }
            runCatching { speechRecognizer?.cancel() }
            _state.update {
                it.copy(
                    isAcousticEarArmed = false,
                    isSpeechRecognizerActive = false,
                    isListening = false,
                    tier = EarTier.IDLE_OFF,
                    partialTranscript = ""
                )
            }
        }
    }

    /**
     * Bug #4 Fix: Whenever lock-screen state or connectivity changes between on-device and standard
     * recognition modes, cleanly destroy any stale SpeechRecognizer instance and recreate it in the
     * exact matching mode so lock-screen recognition never freezes.
     */
    private fun ensureRecognizer(preferOnDevice: Boolean = false) {
        if (speechRecognizer != null && currentRecognizerIsOnDevice == preferOnDevice) return

        // Destroy stale recognizer before switching mode
        speechRecognizer?.let { stale ->
            runCatching { stale.stopListening() }
            runCatching { stale.cancel() }
            runCatching { stale.destroy() }
        }
        speechRecognizer = null
        currentRecognizerIsOnDevice = null

        var actualIsOnDevice = false
        speechRecognizer = runCatching {
            if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                actualIsOnDevice = true
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)
            } else {
                actualIsOnDevice = false
                SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            }
        }.getOrElse {
            actualIsOnDevice = false
            SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        }.apply {
            currentRecognizerIsOnDevice = actualIsOnDevice
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}

                override fun onBeginningOfSpeech() {
                    // Barge-In: If Goku is speaking and user interrupts, stop TTS immediately
                    if (_state.value.isSpeaking) {
                        stopSpeaking()
                    }
                    _state.update { it.copy(tier = EarTier.TIER2_PREWARM_REFLEX) }
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    _state.update {
                        it.copy(
                            isSpeechRecognizerActive = false,
                            isListening = it.isAcousticEarArmed
                        )
                    }
                    val isLangUnsupported = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                    if (isLangUnsupported && currentRecognizerIsOnDevice == true) {
                        onDeviceLanguageUnavailable = true
                    }
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        isLangUnsupported
                    ) {
                        val stale = speechRecognizer
                        speechRecognizer = null
                        currentRecognizerIsOnDevice = null
                        runCatching { stale?.destroy() }
                    }
                    // Resume 24x7 AudioRecord lock-screen monitor immediately on recognizer error/timeout
                    if (continuousMode) {
                        lockScreenWakeWordEngine.resumeAfterRecognizer()
                    }
                    if (continuousMode && !GokuKillSwitch.isStopped.value) {
                        val locked = keyguardManager?.isKeyguardLocked == true
                        if (!locked && System.currentTimeMillis() <= continuousWindowUntilMs) {
                            val backoff = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900L else 450L
                            scheduleRestartListening(backoff)
                        } else {
                            _state.update {
                                it.copy(
                                    tier = EarTier.TIER1_WAKE_SLEEP,
                                    isKeyguardLocked = locked
                                )
                            }
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isBlank()) return
                    _state.update { it.copy(partialTranscript = partial) }

                    // Instant 0.1s kill-switch interrupt on partial voice recognition
                    if (GokuKillSwitch.isStopPhrase(partial)) {
                        runCatching { speechRecognizer?.cancel() }
                        stopSpeaking()
                        GokuKillSwitch.triggerStop("Voice: $partial")
                        speak("Ruk gaya, sab band kar diya.", urgent = true)
                        return
                    }

                    // Strict Wake-Word Gate: Drop background TV / ambient chatter immediately
                    // if 4+ words are heard without "hey goku" or "goku stop"/"goku ruko"
                    if (strictWakeGateActive) {
                        val words = partial.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                        if (words.size >= 4 && !containsWakePhrase(partial)) {
                            runCatching { speechRecognizer?.cancel() }
                            _state.update {
                                it.copy(
                                    isSpeechRecognizerActive = false,
                                    isListening = it.isAcousticEarArmed,
                                    partialTranscript = "",
                                    tier = EarTier.TIER1_WAKE_SLEEP
                                )
                            }
                            if (continuousMode) {
                                lockScreenWakeWordEngine.resumeAfterRecognizer()
                            }
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                    _state.update {
                        it.copy(
                            isSpeechRecognizerActive = false,
                            isListening = it.isAcousticEarArmed,
                            partialTranscript = ""
                        )
                    }

                    if (continuousMode) {
                        lockScreenWakeWordEngine.resumeAfterRecognizer()
                    }

                    if (spoken.isNotBlank()) {
                        handleTranscript(spoken)
                    } else if (continuousMode && !GokuKillSwitch.isStopped.value) {
                        val locked = keyguardManager?.isKeyguardLocked == true
                        if (!locked && System.currentTimeMillis() <= continuousWindowUntilMs) {
                            scheduleRestartListening(400L)
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun containsWakePhrase(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
            .replace(Regex("[,\\.!?।:;\"'()]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return lower.contains("hey goku") ||
            lower.contains("ok goku") ||
            lower.contains("hi goku") ||
            lower.startsWith("goku ") ||
            lower == "goku" ||
            lower.contains("हे गोकू") ||
            lower.startsWith("गोकू")
    }

    private fun handleTranscript(transcript: String) {
        // 1. Highest priority: Instant Emergency Stop
        if (GokuKillSwitch.isStopPhrase(transcript)) {
            stopSpeaking()
            GokuKillSwitch.triggerStop("Voice: $transcript")
            speak("Ruk gaya boss, saare active tasks stop kar diye.", urgent = true)
            return
        }

        val inContinuousWindow = System.currentTimeMillis() <= continuousWindowUntilMs
        val hasWakeWord = containsWakePhrase(transcript)

        // Never execute stale mid-sentence transcripts delivered after GokuKillSwitch STOP
        if (GokuKillSwitch.isStopped.value && !hasWakeWord) {
            return
        }

        // Strict Wake-Word Gate: reject ambient TV / conversation unless wake phrase or explicit direct window
        if (hasWakeWord || inContinuousWindow) {
            if (hasWakeWord) GokuKillSwitch.reset()
            strictWakeGateActive = false
            val cleaned = transcript
                .replace(Regex("^(hey|ok|hi|hello)?[,\\s]*goku[,:\\s]*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^(हे\\s*)?गोकू[,:\\s]*"), "")
                .trim()

            // Refresh 6-second continuous conversation window
            continuousWindowUntilMs = System.currentTimeMillis() + 6000L

            if (cleaned.isEmpty()) {
                speak("Haan boss, bolo?")
            } else {
                onCommandRecognized?.invoke(cleaned)
            }
        } else {
            // Silently ignore non-wake ambient chatter and remain in low-power Tier 1 VAD monitor
            _state.update {
                it.copy(
                    tier = EarTier.TIER1_WAKE_SLEEP,
                    partialTranscript = ""
                )
            }
            if (continuousMode) {
                lockScreenWakeWordEngine.resumeAfterRecognizer()
            }
        }
    }

    private fun scheduleRestartListening(delayMs: Long) {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (continuousMode && !_state.value.isSpeaking && !GokuKillSwitch.isStopped.value) {
                startListening(continuous = true, openDirectWindow = false)
            }
        }
        restartRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    fun release() {
        GokuKillSwitch.unregisterStopHook(killSwitchStopHook)
        captureStartJob?.cancel()
        captureStartJob = null
        stopListening()
        engineScope.cancel()
        pendingUtterances.values.forEach { it.complete(Unit) }
        pendingUtterances.clear()
        lockScreenWakeWordEngine.release()
        onCommandRecognized = null
        mainHandler.post {
            activeOneShotRecognizers.forEach { rec ->
                runCatching { rec.cancel() }
                runCatching { rec.destroy() }
            }
            activeOneShotRecognizers.clear()
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            currentRecognizerIsOnDevice = null
        }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        synchronized(Companion) {
            if (INSTANCE === this) {
                INSTANCE = null
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: GokuEarAndVoiceEngine? = null

        fun getInstance(context: Context): GokuEarAndVoiceEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GokuEarAndVoiceEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
