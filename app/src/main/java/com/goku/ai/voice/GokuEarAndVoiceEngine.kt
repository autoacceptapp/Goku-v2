package com.goku.ai.voice

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

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

    private val activeJobs = CopyOnWriteArrayList<Job>()
    private val stopHooks = CopyOnWriteArrayList<() -> Unit>()

    fun registerJob(job: Job) {
        activeJobs.removeAll { !it.isActive }
        activeJobs.add(job)
    }

    fun registerStopHook(hook: () -> Unit) {
        stopHooks.add(hook)
    }

    fun triggerStop(source: String) {
        _isStopped.value = true
        _lastStopSource.value = source
        activeJobs.forEach { runCatching { it.cancel() } }
        activeJobs.clear()
        stopHooks.forEach { runCatching { it.invoke() } }
    }

    fun reset() {
        _isStopped.value = false
    }

    fun isStopPhrase(text: String): Boolean {
        val norm = text.lowercase(Locale.ROOT).trim()
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
    val isSpeaking: Boolean = false,
    val isWhisperMode: Boolean = false,
    val isNightSoftMode: Boolean = false,
    val partialTranscript: String = "",
    val lastError: String? = null
)

/**
 * 3-Tier Low-Power Ear + Hindi/Hinglish Natural Prosody Voice Engine.
 * - Instant 0.1s "Goku stop" / "Goku ruko" kill-switch detection on partial & final transcripts.
 * - "Hey Goku" wake-word detection + 6-second Continuous Talk window + Barge-In.
 * - Private Earphone Whisper Mode + Night/DND soft volume adaptation.
 */
class GokuEarAndVoiceEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private val _state = MutableStateFlow(VoiceEngineState())
    val state: StateFlow<VoiceEngineState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var continuousMode = false
    private var continuousWindowUntilMs = 0L
    private var onCommandRecognized: ((String) -> Unit)? = null
    private var restartRunnable: Runnable? = null

    init {
        initTts()
        GokuKillSwitch.registerStopHook {
            stopSpeaking()
            _state.value = _state.value.copy(
                tier = EarTier.STOPPED,
                isSpeaking = false
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
                            _state.value = _state.value.copy(
                                isSpeaking = true,
                                tier = EarTier.SPEAKING
                            )
                        }

                        override fun onDone(utteranceId: String?) {
                            _state.value = _state.value.copy(isSpeaking = false)
                            if (continuousMode && !GokuKillSwitch.isStopped.value) {
                                // Open 6-second Continuous Talk window right after Goku finishes speaking
                                continuousWindowUntilMs = System.currentTimeMillis() + 6000L
                                _state.value = _state.value.copy(tier = EarTier.TIER3_CONTINUOUS_TALK)
                                scheduleRestartListening(220L)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _state.value = _state.value.copy(isSpeaking = false)
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
        _state.value = _state.value.copy(
            isWhisperMode = whisper,
            isNightSoftMode = nightSoft
        )

        if (!ttsReady) return

        // Natural Hinglish prosody adaptation
        val speechRate = when {
            urgent -> 1.12f
            whisper -> 0.96f
            nightSoft -> 0.92f
            else -> 1.03f
        }
        val pitch = if (whisper || nightSoft) 0.95f else 1.02f
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(pitch)

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
            tts?.speak(text, queueMode, params, utteranceId)
        }
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
        _state.value = _state.value.copy(isSpeaking = false)
    }

    fun startListening(continuous: Boolean = true, openDirectWindow: Boolean = false) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = _state.value.copy(
                lastError = "RECORD_AUDIO permission required for Goku Ear"
            )
            return
        }

        GokuKillSwitch.reset()
        continuousMode = continuous
        if (openDirectWindow) {
            continuousWindowUntilMs = System.currentTimeMillis() + 8000L
        }

        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _state.value = _state.value.copy(
                    lastError = "Speech recognition service unavailable on device"
                )
                return@post
            }

            // Barge-in: immediately interrupt TTS when user taps to speak
            if (openDirectWindow) stopSpeaking()

            ensureRecognizer()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }

            runCatching {
                speechRecognizer?.startListening(intent)
                val tier = if (System.currentTimeMillis() < continuousWindowUntilMs) {
                    EarTier.TIER3_CONTINUOUS_TALK
                } else {
                    EarTier.TIER1_WAKE_SLEEP
                }
                _state.value = _state.value.copy(
                    tier = tier,
                    isListening = true,
                    isWhisperMode = isEarphoneConnected(),
                    isNightSoftMode = isNightOrDnd(),
                    lastError = null
                )
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isListening = false,
                    lastError = err.message
                )
            }
        }
    }

    fun stopListening() {
        continuousMode = false
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.post {
            runCatching { speechRecognizer?.stopListening() }
            runCatching { speechRecognizer?.cancel() }
            _state.value = _state.value.copy(
                isListening = false,
                tier = EarTier.IDLE_OFF,
                partialTranscript = ""
            )
        }
    }

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}

                override fun onBeginningOfSpeech() {
                    // Barge-In: If Goku is speaking and user interrupts, stop TTS immediately
                    if (_state.value.isSpeaking) {
                        stopSpeaking()
                    }
                    _state.value = _state.value.copy(tier = EarTier.TIER2_PREWARM_REFLEX)
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    _state.value = _state.value.copy(isListening = false)
                    if (continuousMode && !GokuKillSwitch.isStopped.value) {
                        val backoff = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900L else 450L
                        scheduleRestartListening(backoff)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isBlank()) return
                    _state.value = _state.value.copy(partialTranscript = partial)

                    // Instant 0.1s kill-switch interrupt on partial voice recognition
                    if (GokuKillSwitch.isStopPhrase(partial)) {
                        GokuKillSwitch.triggerStop("Voice: $partial")
                        stopSpeaking()
                        speak("Ruk gaya, sab band kar diya.", urgent = true)
                        return
                    }
                }

                override fun onResults(results: Bundle?) {
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                    _state.value = _state.value.copy(
                        isListening = false,
                        partialTranscript = ""
                    )

                    if (spoken.isNotBlank()) {
                        handleTranscript(spoken)
                    } else if (continuousMode && !GokuKillSwitch.isStopped.value) {
                        scheduleRestartListening(400L)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun handleTranscript(transcript: String) {
        // 1. Highest priority: Instant Emergency Stop
        if (GokuKillSwitch.isStopPhrase(transcript)) {
            GokuKillSwitch.triggerStop("Voice: $transcript")
            stopSpeaking()
            speak("Ruk gaya boss, saare active tasks stop kar diye.", urgent = true)
            return
        }

        val lower = transcript.lowercase(Locale.ROOT)
        val inContinuousWindow = System.currentTimeMillis() <= continuousWindowUntilMs
        val hasWakeWord = lower.contains("hey goku") ||
            lower.contains("ok goku") ||
            lower.startsWith("goku") ||
            lower.contains("हे गोकू") ||
            lower.startsWith("गोकू")

        if (hasWakeWord || inContinuousWindow) {
            val cleaned = transcript
                .replace(Regex("^(hey|ok|hi|hello)?\\s*goku[,:\\s]*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^(हे\\s*)?गोकू[,:\\s]*"), "")
                .trim()

            // Refresh 6-second continuous conversation window
            continuousWindowUntilMs = System.currentTimeMillis() + 6000L

            if (cleaned.isEmpty()) {
                speak("Haan boss, bolo?")
            } else {
                onCommandRecognized?.invoke(cleaned)
            }
        } else if (continuousMode) {
            scheduleRestartListening(350L)
        }
    }

    private fun scheduleRestartListening(delayMs: Long) {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (continuousMode && !_state.value.isSpeaking) {
                startListening(continuous = true, openDirectWindow = false)
            }
        }
        restartRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    fun release() {
        stopListening()
        mainHandler.post {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
        runCatching { tts?.shutdown() }
        tts = null
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
