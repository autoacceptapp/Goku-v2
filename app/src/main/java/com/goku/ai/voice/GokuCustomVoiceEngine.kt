package com.goku.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.goku.ai.persona.VoiceProsodyProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Raw synthesized 16-bit mono PCM audio chunk ready for real-time `AudioTrack` streaming.
 */
data class SynthesizedAudioChunk(
    val chunkIndex: Int,
    val sourceText: String,
    val pcm16: ShortArray,
    val sampleRateHz: Int
)

/**
 * Telemetry snapshot for UI / Studio diagnostics alongside the primary `VoiceEngineState` enum.
 */
data class CustomVoiceTelemetry(
    val state: GokuCustomVoiceEngine.VoiceEngineState = GokuCustomVoiceEngine.VoiceEngineState.IDLE,
    val activePackName: String = "",
    val backendLabel: String = "Configured Hinglish TTS (Standby)",
    val isNativeOnnxRuntimeReady: Boolean = false,
    val activeChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val lastError: String? = null
)

/**
 * Pluggable offline custom voice synthesizer contract (ONNX / Sherpa-ONNX VITS / Piper / Zero-Shot).
 */
interface CustomVoiceSynthesizerBackend {
    val backendName: String
    fun isReady(): Boolean
    fun initialize(descriptor: ActiveVoicePackDescriptor, metadata: VoicePackMetadata?): Boolean
    fun synthesizeChunk(text: String, speechRate: Float, pitch: Float): SynthesizedAudioChunk?
    fun release()
}

/**
 * Pluggable PCM audio sink contract (defaults to real Android `AudioTrack` low-latency streaming;
 * injectable in JVM/Robolectric tests for deterministic `<0.1s` cancel/flush verification).
 */
interface CustomVoiceAudioSink {
    fun writeChunk(chunk: SynthesizedAudioChunk, volumeGain: Float, isCancelled: () -> Boolean): Boolean
    fun stopAndFlushImmediately()
}

/**
 * Real-time Android `AudioTrack` streaming sink (`USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH`,
 * `MODE_STREAM`, `ENCODING_PCM_16BIT`, `CHANNEL_OUT_MONO`) with lock-protected `<0.1s` flush/stop.
 */
internal class AndroidAudioTrackSink : CustomVoiceAudioSink {

    private val trackLock = Any()
    private var currentTrack: AudioTrack? = null
    private var currentSampleRate: Int = 0

    override fun writeChunk(
        chunk: SynthesizedAudioChunk,
        volumeGain: Float,
        isCancelled: () -> Boolean
    ): Boolean {
        if (chunk.pcm16.isEmpty() || isCancelled()) return false
        val track = ensureTrack(chunk.sampleRateHz, volumeGain) ?: return false
        return try {
            val frameStep = (chunk.sampleRateHz / 20).coerceIn(512, 2048) // ~50ms slice for <0.1s abort responsiveness
            var offset = 0
            while (offset < chunk.pcm16.size) {
                if (isCancelled()) return false
                val count = minOf(frameStep, chunk.pcm16.size - offset)
                val written = track.write(chunk.pcm16, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written < 0) return false
                offset += count
            }
            !isCancelled()
        } catch (_: Throwable) {
            false
        }
    }

    override fun stopAndFlushImmediately() {
        synchronized(trackLock) {
            val track = currentTrack
            currentTrack = null
            currentSampleRate = 0
            if (track != null) {
                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                    }
                }
                runCatching { track.flush() }
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }
    }

    private fun ensureTrack(sampleRateHz: Int, volumeGain: Float): AudioTrack? {
        synchronized(trackLock) {
            val existing = currentTrack
            if (existing != null && currentSampleRate == sampleRateHz && existing.state == AudioTrack.STATE_INITIALIZED) {
                runCatching { existing.setVolume(volumeGain.coerceIn(0.05f, 1.0f)) }
                if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    runCatching { existing.play() }
                }
                return existing
            }
            if (existing != null) {
                runCatching { existing.pause() }
                runCatching { existing.flush() }
                runCatching { existing.stop() }
                runCatching { existing.release() }
                currentTrack = null
            }

            return runCatching {
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRateHz,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(sampleRateHz / 4)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRateHz)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    runCatching { track.release() }
                    return@runCatching null
                }
                track.setVolume(volumeGain.coerceIn(0.05f, 1.0f))
                track.play()
                currentTrack = track
                currentSampleRate = sampleRateHz
                track
            }.getOrNull()
        }
    }
}

/**
 * Reflection-safe bridge to Sherpa-ONNX OfflineTts (`com.k2fsa.sherpa.onnx.OfflineTts`) for local
 * Piper / VITS ONNX voice packs and optional `reference_voice.wav` voice packs.
 * Fails gracefully with zero crashes when the optional native AAR is absent, triggering Hinglish TTS fallback.
 */
internal class SherpaOnnxOfflineTtsBridge : CustomVoiceSynthesizerBackend {

    private val lock = Any()
    private var ttsInstance: Any? = null
    private var generateMethod: java.lang.reflect.Method? = null
    private var releaseMethod: java.lang.reflect.Method? = null
    private var loadedModelKey: String? = null

    @Volatile
    private var ready: Boolean = false

    override val backendName: String
        get() = if (ready) "Sherpa-ONNX Offline Neural Voice" else "Sherpa-ONNX OfflineTts (Unavailable)"

    override fun isReady(): Boolean = ready

    override fun initialize(descriptor: ActiveVoicePackDescriptor, metadata: VoicePackMetadata?): Boolean {
        synchronized(lock) {
            val onnxPath = descriptor.onnxFilePaths.firstOrNull { File(it).exists() } ?: run {
                releaseLocked()
                return false
            }
            val key = "${descriptor.modelId}:$onnxPath:${metadata?.referenceWavPath.orEmpty()}"
            if (ready && loadedModelKey == key && ttsInstance != null) return true

            releaseLocked()

            val dir = File(descriptor.installDirPath)
            val tokensFile = descriptor.tokenizerOrConfigPaths
                .firstOrNull { it.endsWith("tokens.txt", ignoreCase = true) && File(it).exists() }
                ?: File(dir, "tokens.txt").takeIf { it.exists() }?.absolutePath
                ?: ""
            val dataDir = File(dir, "espeak-ng-data").takeIf { it.exists() && it.isDirectory }?.absolutePath ?: ""
            val lexiconFile = File(dir, "lexicon.txt").takeIf { it.exists() }?.absolutePath ?: ""

            return runCatching {
                val offlineTtsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
                val ttsConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")
                val modelConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig")
                val vitsConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig")

                val vitsConfig = vitsConfigClass.getDeclaredConstructor().newInstance().apply {
                    setFieldOrProp(this, "model", onnxPath)
                    setFieldOrProp(this, "lexicon", lexiconFile)
                    setFieldOrProp(this, "tokens", tokensFile)
                    setFieldOrProp(this, "dataDir", dataDir)
                    setFieldOrProp(this, "noiseScale", 0.667f)
                    setFieldOrProp(this, "noiseScaleW", 0.8f)
                    setFieldOrProp(this, "lengthScale", 1.0f)
                }
                val modelConfig = modelConfigClass.getDeclaredConstructor().newInstance().apply {
                    setFieldOrProp(this, "vits", vitsConfig)
                    setFieldOrProp(this, "numThreads", 2)
                    setFieldOrProp(this, "provider", "cpu")
                }
                val ttsConfig = ttsConfigClass.getDeclaredConstructor().newInstance().apply {
                    setFieldOrProp(this, "model", modelConfig)
                    setFieldOrProp(this, "maxNumSentences", 1)
                }

                val ctor = offlineTtsClass.constructors.firstOrNull { it.parameterTypes.size == 2 }
                    ?: offlineTtsClass.constructors.firstOrNull { it.parameterTypes.size == 1 }
                    ?: error("Unsupported Sherpa-ONNX OfflineTts constructor")

                val instance = if (ctor.parameterTypes.size == 2) {
                    ctor.newInstance(null, ttsConfig)
                } else {
                    ctor.newInstance(ttsConfig)
                }

                val genMethod = offlineTtsClass.methods.firstOrNull {
                    it.name == "generate" && it.parameterTypes.size == 3
                } ?: offlineTtsClass.methods.firstOrNull {
                    it.name == "generate" && it.parameterTypes.size == 1
                } ?: error("Missing OfflineTts.generate method")

                ttsInstance = instance
                generateMethod = genMethod
                releaseMethod = offlineTtsClass.methods.firstOrNull {
                    it.name == "release" && it.parameterTypes.isEmpty()
                }
                loadedModelKey = key
                ready = true
                true
            }.getOrElse {
                releaseLocked()
                false
            }
        }
    }

    override fun synthesizeChunk(text: String, speechRate: Float, pitch: Float): SynthesizedAudioChunk? {
        synchronized(lock) {
            if (!ready) return null
            val instance = ttsInstance ?: return null
            val gen = generateMethod ?: return null
            return runCatching {
                val safeRate = speechRate.coerceIn(0.65f, 1.65f)
                val audioObj = if (gen.parameterTypes.size == 3) {
                    gen.invoke(instance, text, 0, safeRate)
                } else {
                    gen.invoke(instance, text)
                } ?: return@runCatching null

                val samples = readSamplesFloatArray(audioObj) ?: return@runCatching null
                val sampleRate = readSampleRate(audioObj).takeIf { it > 0 } ?: 22050
                if (samples.isEmpty()) return@runCatching null

                val pcm16 = ShortArray(samples.size) { i ->
                    (samples[i].coerceIn(-1.0f, 1.0f) * 32767.0f).roundToInt().toShort()
                }
                SynthesizedAudioChunk(
                    chunkIndex = 0,
                    sourceText = text,
                    pcm16 = pcm16,
                    sampleRateHz = sampleRate
                )
            }.getOrNull()
        }
    }

    override fun release() {
        synchronized(lock) {
            releaseLocked()
        }
    }

    private fun releaseLocked() {
        runCatching { ttsInstance?.let { releaseMethod?.invoke(it) } }
        ttsInstance = null
        generateMethod = null
        releaseMethod = null
        loadedModelKey = null
        ready = false
    }

    private fun readSamplesFloatArray(audioObj: Any): FloatArray? {
        return runCatching {
            val getter = audioObj.javaClass.methods.firstOrNull { it.name == "getSamples" }
            val value = if (getter != null) {
                getter.invoke(audioObj)
            } else {
                val field = audioObj.javaClass.getDeclaredField("samples")
                field.isAccessible = true
                field.get(audioObj)
            }
            value as? FloatArray
        }.getOrNull()
    }

    private fun readSampleRate(audioObj: Any): Int {
        return runCatching {
            val getter = audioObj.javaClass.methods.firstOrNull { it.name == "getSampleRate" }
            val value = if (getter != null) {
                getter.invoke(audioObj)
            } else {
                val field = audioObj.javaClass.getDeclaredField("sampleRate")
                field.isAccessible = true
                field.get(audioObj)
            }
            (value as? Number)?.toInt() ?: 22050
        }.getOrDefault(22050)
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
 * Modular, pluggable Custom Voice Synthesis & Real-Time `AudioTrack` Streaming Engine for Goku V10.1.
 *
 * Key guarantees:
 * 1. Splits dynamic LLM responses into natural sentence/phrase chunks and runs synthesis (producer)
 *    concurrently with `AudioTrack` streaming playback (consumer) via Kotlin Coroutines `Channel`.
 * 2. Routes synthesis through the active ONNX / Sherpa-ONNX voice pack (or `reference_voice.wav` profile);
 *    gracefully falls back to configured Hinglish TTS (`FALLBACK_TTS`) with zero unhandled exceptions
 *    whenever no native ONNX runtime/pack is ready.
 * 3. `stopImmediately()` cancels active synthesis/playback jobs, flushes & stops `AudioTrack`, and
 *    halts fallback TTS in `<0.1s` for `"Goku stop"` / `"Goku ruko"` and user barge-in.
 * 4. Exposes `VoiceEngineState` (`IDLE`, `SYNTHESIZING`, `SPEAKING`, `FALLBACK_TTS`, `ERROR`) via `StateFlow`.
 */
class GokuCustomVoiceEngine(
    context: Context,
    private val synthesizerBackend: CustomVoiceSynthesizerBackend = SherpaOnnxOfflineTtsBridge(),
    private val audioSink: CustomVoiceAudioSink = AndroidAudioTrackSink()
) {

    enum class VoiceEngineState {
        IDLE,
        SYNTHESIZING,
        SPEAKING,
        FALLBACK_TTS,
        ERROR
    }

    private val appContext = context.applicationContext
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionLock = Any()
    private val generationCounter = AtomicLong(0L)
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var activePipelineJob: Job? = null
    @Volatile
    private var activeChannel: Channel<SynthesizedAudioChunk>? = null
    @Volatile
    private var activeDescriptor: ActiveVoicePackDescriptor? = null
    @Volatile
    private var activeMetadata: VoicePackMetadata? = null

    @Volatile
    private var fallbackTtsSpeaker: ((text: String, urgent: Boolean, flushQueue: Boolean, prosody: VoiceProsodyProfile?) -> Unit)? = null
    @Volatile
    private var fallbackTtsStopper: (() -> Unit)? = null
    @Volatile
    private var onPlaybackStateListener: ((isSpeaking: Boolean, finishedNaturally: Boolean) -> Unit)? = null

    private val _engineState = MutableStateFlow(VoiceEngineState.IDLE)
    val engineState: StateFlow<VoiceEngineState> = _engineState.asStateFlow()

    private val _telemetry = MutableStateFlow(CustomVoiceTelemetry())
    val telemetry: StateFlow<CustomVoiceTelemetry> = _telemetry.asStateFlow()

    private val killSwitchHook: () -> Unit = {
        stopImmediately()
    }

    init {
        GokuKillSwitch.registerStopHook(killSwitchHook)
    }

    /**
     * Connects Goku's configured Hinglish Android TTS as the seamless fallback handler and
     * wires playback lifecycle notifications back to `GokuEarAndVoiceEngine` (avoiding duplicate audio loops).
     */
    fun attachFallbackTtsBridge(
        speaker: (text: String, urgent: Boolean, flushQueue: Boolean, prosody: VoiceProsodyProfile?) -> Unit,
        stopper: () -> Unit,
        onPlaybackChanged: (isSpeaking: Boolean, finishedNaturally: Boolean) -> Unit
    ) {
        fallbackTtsSpeaker = speaker
        fallbackTtsStopper = stopper
        onPlaybackStateListener = onPlaybackChanged
    }

    /**
     * Called automatically by `VoicePackManager` whenever a user imports `reference_voice.wav` / `.onnx`
     * or activates/deletes an ONNX voice bundle from `OnnxModelDownloader`.
     */
    fun onVoicePackUpdated(descriptor: ActiveVoicePackDescriptor?, metadata: VoicePackMetadata?) {
        activeDescriptor = descriptor
        activeMetadata = metadata
        if (descriptor == null) {
            synthesizerBackend.release()
            _telemetry.update {
                it.copy(
                    activePackName = "",
                    backendLabel = "Configured Hinglish TTS (Default)",
                    isNativeOnnxRuntimeReady = false,
                    lastError = null
                )
            }
            return
        }

        engineScope.launch(Dispatchers.Default) {
            val nativeReady = runCatching {
                synthesizerBackend.initialize(descriptor, metadata)
            }.getOrDefault(false)

            _telemetry.update {
                it.copy(
                    activePackName = descriptor.displayName,
                    backendLabel = if (nativeReady) {
                        synthesizerBackend.backendName
                    } else {
                        "Voice Pack Active (Hinglish TTS Fallback)"
                    },
                    isNativeOnnxRuntimeReady = nativeReady,
                    lastError = null
                )
            }
        }
    }

    fun hasCustomPackConfigured(): Boolean {
        val desc = activeDescriptor ?: return false
        return desc.onnxFilePaths.isNotEmpty() || desc.wavSamplePaths.isNotEmpty()
    }

    /**
     * Speaks a dynamic LLM response asynchronously using concurrent chunked synthesis + `AudioTrack`
     * streaming when a native ONNX/Sherpa-ONNX voice pack is active, or falls back gracefully to
     * configured Hinglish TTS without throwing unhandled exceptions.
     */
    fun speak(
        text: String,
        urgent: Boolean = false,
        flushQueue: Boolean = true,
        prosodyOverride: VoiceProsodyProfile? = null,
        volumeGain: Float = 0.92f
    ) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return
        if (GokuKillSwitch.isStopped.value && !urgent) return

        val descriptor = activeDescriptor
        val metadata = activeMetadata

        // If no custom voice pack is active, delegate straight to configured Hinglish TTS cleanly
        if (descriptor == null) {
            dispatchFallbackTts(cleanText, urgent, flushQueue, prosodyOverride)
            return
        }

        if (flushQueue) {
            stopImmediately(invokeFallbackStopper = true)
        }

        val myGen = generationCounter.incrementAndGet()
        stopRequested.set(false)

        val chunks = splitIntoSpeechChunks(cleanText)
        if (chunks.isEmpty()) return

        val job = engineScope.launch {
            runConcurrentSynthesisPipeline(
                generation = myGen,
                fullText = cleanText,
                chunks = chunks,
                descriptor = descriptor,
                metadata = metadata,
                urgent = urgent,
                flushQueue = flushQueue,
                prosody = prosodyOverride,
                volumeGain = volumeGain
            )
        }
        synchronized(sessionLock) {
            activePipelineJob = job
        }
        GokuKillSwitch.registerJob(job)
    }

    /**
     * Suspends until the current custom synthesis & playback pipeline finishes (or aborts).
     * Useful for deterministic testing and sequential callers.
     */
    suspend fun awaitCurrentPlayback() {
        val job = synchronized(sessionLock) { activePipelineJob }
        job?.join()
    }

    /**
     * Instant `<0.1s` cancellation for `"Goku stop"` / `"Goku ruko"` and user barge-in.
     * Cancels active synthesis & playback coroutines, drains the `Channel`, flushes & stops
     * `AudioTrack`, and stops fallback TTS immediately.
     */
    fun stopImmediately(invokeFallbackStopper: Boolean = true) {
        generationCounter.incrementAndGet()
        stopRequested.set(true)

        val jobToCancel: Job?
        val channelToClose: Channel<SynthesizedAudioChunk>?
        synchronized(sessionLock) {
            jobToCancel = activePipelineJob
            activePipelineJob = null
            channelToClose = activeChannel
            activeChannel = null
        }

        runCatching { channelToClose?.cancel() }
        runCatching { jobToCancel?.cancel() }
        runCatching { audioSink.stopAndFlushImmediately() }
        if (invokeFallbackStopper) {
            runCatching { fallbackTtsStopper?.invoke() }
        }

        updateState(VoiceEngineState.IDLE)
        runCatching { onPlaybackStateListener?.invoke(false, false) }
    }

    fun release() {
        GokuKillSwitch.unregisterStopHook(killSwitchHook)
        stopImmediately(invokeFallbackStopper = false)
        runCatching { synthesizerBackend.release() }
        engineScope.cancel()
        synchronized(Companion) {
            if (INSTANCE === this) {
                INSTANCE = null
            }
        }
    }

    private suspend fun runConcurrentSynthesisPipeline(
        generation: Long,
        fullText: String,
        chunks: List<String>,
        descriptor: ActiveVoicePackDescriptor,
        metadata: VoicePackMetadata?,
        urgent: Boolean,
        flushQueue: Boolean,
        prosody: VoiceProsodyProfile?,
        volumeGain: Float
    ) {
        val isStale: () -> Boolean = {
            stopRequested.get() || generationCounter.get() != generation || (GokuKillSwitch.isStopped.value && !urgent)
        }

        if (isStale()) return

        updateState(
            newState = VoiceEngineState.SYNTHESIZING,
            activeChunk = 0,
            totalChunks = chunks.size,
            error = null
        )

        // Ensure native ONNX / Sherpa-ONNX runtime is initialized for the active voice pack
        val runtimeReady = withContext(Dispatchers.Default) {
            if (synthesizerBackend.isReady()) true
            else runCatching { synthesizerBackend.initialize(descriptor, metadata) }.getOrDefault(false)
        }

        if (isStale()) return

        if (!runtimeReady) {
            // Graceful fallback to configured Hinglish TTS (zero crash, respects emotion prosody + whisper mode)
            dispatchFallbackTts(fullText, urgent, flushQueue, prosody)
            return
        }

        val speechRate = prosody?.speechRate ?: if (urgent) 1.12f else 1.04f
        val pitch = prosody?.pitch ?: 1.02f

        val chunkChannel = Channel<SynthesizedAudioChunk>(capacity = 3)
        synchronized(sessionLock) {
            activeChannel = chunkChannel
        }

        val anyChunkSynthesized = AtomicBoolean(false)
        val synthesisFailedBeforeFirstAudio = AtomicBoolean(false)

        try {
            // Producer coroutine: synthesizes sentence/phrase chunks on CPU pool concurrently
            val producerJob = engineScope.launch(Dispatchers.Default) {
                try {
                    for ((idx, sentence) in chunks.withIndex()) {
                        if (!isActive || isStale()) break
                        val synthesized = runCatching {
                            synthesizerBackend.synthesizeChunk(sentence, speechRate, pitch)
                        }.getOrNull()

                        if (!isActive || isStale()) break

                        if (synthesized == null || synthesized.pcm16.isEmpty()) {
                            if (!anyChunkSynthesized.get()) {
                                synthesisFailedBeforeFirstAudio.set(true)
                                break
                            }
                            continue
                        }

                        anyChunkSynthesized.set(true)
                        chunkChannel.send(synthesized.copy(chunkIndex = idx))
                    }
                } catch (_: CancellationException) {
                    // Normal instant stop / barge-in cancellation
                } catch (t: Throwable) {
                    Log.w(TAG, "Custom voice producer error", t)
                    if (!anyChunkSynthesized.get()) {
                        synthesisFailedBeforeFirstAudio.set(true)
                    }
                } finally {
                    chunkChannel.close()
                }
            }

            // Consumer coroutine: streams PCM chunks immediately to AudioTrack as soon as chunk #0 arrives
            val consumerJob = engineScope.launch(Dispatchers.IO) {
                var notifiedSpeaking = false
                try {
                    for (audioChunk in chunkChannel) {
                        if (!isActive || isStale()) break
                        if (!notifiedSpeaking) {
                            notifiedSpeaking = true
                            updateState(
                                newState = VoiceEngineState.SPEAKING,
                                activeChunk = audioChunk.chunkIndex + 1,
                                totalChunks = chunks.size
                            )
                            runCatching { onPlaybackStateListener?.invoke(true, false) }
                        } else {
                            updateState(
                                newState = VoiceEngineState.SPEAKING,
                                activeChunk = audioChunk.chunkIndex + 1,
                                totalChunks = chunks.size
                            )
                        }

                        val wroteOk = audioSink.writeChunk(
                            chunk = audioChunk,
                            volumeGain = volumeGain,
                            isCancelled = isStale
                        )
                        if (!wroteOk || isStale()) break
                    }
                } catch (_: CancellationException) {
                    // Instant stop / barge-in
                } catch (t: Throwable) {
                    Log.e(TAG, "AudioTrack consumer error", t)
                    updateState(VoiceEngineState.ERROR, error = t.message ?: "AudioTrack playback error")
                }
            }

            joinAll(producerJob, consumerJob)

            if (isStale()) {
                audioSink.stopAndFlushImmediately()
                return
            }

            if (synthesisFailedBeforeFirstAudio.get() || !anyChunkSynthesized.get()) {
                dispatchFallbackTts(fullText, urgent, flushQueue, prosody)
                return
            }

            updateState(VoiceEngineState.IDLE, activeChunk = chunks.size, totalChunks = chunks.size)
            runCatching { onPlaybackStateListener?.invoke(false, true) }
        } catch (_: CancellationException) {
            audioSink.stopAndFlushImmediately()
        } catch (t: Throwable) {
            Log.e(TAG, "Custom voice pipeline failure; falling back to Hinglish TTS", t)
            updateState(VoiceEngineState.ERROR, error = t.message)
            if (!isStale()) {
                dispatchFallbackTts(fullText, urgent, flushQueue, prosody)
            }
        } finally {
            synchronized(sessionLock) {
                if (activeChannel === chunkChannel) {
                    activeChannel = null
                }
            }
            runCatching { chunkChannel.close() }
        }
    }

    private fun dispatchFallbackTts(
        text: String,
        urgent: Boolean,
        flushQueue: Boolean,
        prosody: VoiceProsodyProfile?
    ) {
        updateState(VoiceEngineState.FALLBACK_TTS, error = null)
        val speaker = fallbackTtsSpeaker
        if (speaker != null) {
            runCatching { speaker.invoke(text, urgent, flushQueue, prosody) }
                .onFailure { err ->
                    updateState(VoiceEngineState.ERROR, error = err.message ?: "Fallback TTS failed")
                }
        } else {
            updateState(VoiceEngineState.IDLE)
        }
    }

    private fun updateState(
        newState: VoiceEngineState,
        activeChunk: Int = _telemetry.value.activeChunkIndex,
        totalChunks: Int = _telemetry.value.totalChunks,
        error: String? = _telemetry.value.lastError
    ) {
        _engineState.value = newState
        _telemetry.update {
            it.copy(
                state = newState,
                activeChunkIndex = activeChunk,
                totalChunks = totalChunks,
                lastError = error
            )
        }
    }

    companion object {
        private const val TAG = "GokuCustomVoiceEngine"

        @Volatile
        private var INSTANCE: GokuCustomVoiceEngine? = null

        fun getInstance(context: Context): GokuCustomVoiceEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GokuCustomVoiceEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Splits dynamic LLM responses into natural low-latency sentence & phrase chunks
         * (supporting English punctuation + Devanagari danda `।` + long-clause comma splits).
         */
        fun splitIntoSpeechChunks(text: String, maxChunkChars: Int = 110): List<String> {
            val normalized = text.replace(Regex("\\s+"), " ").trim()
            if (normalized.isEmpty()) return emptyList()

            val rawSentences = normalized
                .split(Regex("(?<=[.!?।\\n])\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val result = mutableListOf<String>()
            for (sentence in rawSentences) {
                if (sentence.length <= maxChunkChars) {
                    result.add(sentence)
                } else {
                    // Split long sentences on comma/semicolon/dash boundaries for fast first-audio latency
                    val subParts = sentence.split(Regex("(?<=[,;—–:])\\s+"))
                    val buf = StringBuilder()
                    for (part in subParts) {
                        if (buf.isNotEmpty() && buf.length + part.length + 1 > maxChunkChars) {
                            result.add(buf.toString().trim())
                            buf.clear()
                        }
                        if (buf.isNotEmpty()) buf.append(' ')
                        buf.append(part.trim())
                    }
                    if (buf.isNotEmpty()) result.add(buf.toString().trim())
                }
            }
            return result
        }
    }
}
