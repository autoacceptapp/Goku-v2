package com.goku.ai.persona

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

enum class UserMood(val label: String) {
    ANGRY("Angry / Frustrated"),
    TIRED_SAD("Tired / Low"),
    URGENT("Rushed / Urgent"),
    EXCITED("Happy / Excited"),
    NEUTRAL("Neutral")
}

enum class GokuFeeling(val tag: String, val label: String) {
    EXCITED("[EXCITED]", "Excited / Hyped"),
    CARING("[CARING]", "Caring / Supportive"),
    SERIOUS("[SERIOUS]", "Serious / Focused"),
    PLAYFUL("[PLAYFUL]", "Playful / Training")
}

/**
 * Optional on-device acoustic prosody telemetry captured from Android SpeechRecognizer / AudioRecord.
 */
data class SpeechProsodyMetrics(
    val speakingWordsPerSec: Float? = null,
    val peakEnergyDb: Double? = null,
    val utteranceDurationMs: Long? = null
)

/**
 * Dynamic TTS voice prosody profile mapped from active GokuFeeling + UserMood + User Custom Sliders.
 */
data class VoiceProsodyProfile(
    val speechRate: Float,
    val pitch: Float,
    val pauseCadenceMs: Long,
    val feeling: GokuFeeling,
    val userMood: UserMood
)

data class MoodAnalysisResult(
    val mood: UserMood,
    val confidence: Int,
    val cues: List<String>,
    val recommendedFeeling: GokuFeeling
)

data class ParsedEmotionReply(
    val cleanText: String,
    val detectedFeeling: GokuFeeling,
    val hadExplicitTag: Boolean
)

data class EmotionEngineState(
    val personaConfig: GokuPersonaConfig = GokuPersonaConfig(),
    val lastUserMood: UserMood = UserMood.NEUTRAL,
    val lastMoodConfidence: Int = 100,
    val lastMoodCues: List<String> = emptyList(),
    val lastGokuFeeling: GokuFeeling = GokuFeeling.EXCITED,
    val activeProsody: VoiceProsodyProfile = VoiceProsodyProfile(
        speechRate = 1.08f,
        pitch = 1.12f,
        pauseCadenceMs = 150L,
        feeling = GokuFeeling.EXCITED,
        userMood = UserMood.NEUTRAL
    )
)

/**
 * Bi-Directional Emotion & Personality Engine for Goku V10.1 (100% Offline-First + Online Compatible).
 *
 * 1. Detects UserMood (ANGRY, TIRED_SAD, URGENT, EXCITED, NEUTRAL) with zero network delay from
 *    Hinglish/English text heuristics + optional live speech prosody (speaking rate & RMS energy).
 * 2. Generates dynamic LLM persona/mood directives with unlimited custom catchphrases & system rules.
 * 3. Strips LLM emotion tags ([EXCITED], [CARING], [SERIOUS], [PLAYFUL]) before UI display / speech
 *    and maps them directly to real-time TTS pitch, speech rate, and pause cadence.
 */
class GokuEmotionPersonaEngine(
    private val context: Context,
    val repository: PersonaPreferencesRepository = PersonaPreferencesRepository.getInstance(context)
) {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var currentConfig: GokuPersonaConfig = GokuPersonaConfig()
        private set

    private val _state = MutableStateFlow(
        EmotionEngineState(
            personaConfig = currentConfig,
            activeProsody = computeProsody(GokuFeeling.EXCITED, UserMood.NEUTRAL, currentConfig)
        )
    )
    val state: StateFlow<EmotionEngineState> = _state.asStateFlow()

    init {
        engineScope.launch {
            repository.configFlow.collect { loaded ->
                currentConfig = loaded
                _state.update { prev ->
                    val feeling = recommendFeeling(prev.lastUserMood, loaded)
                    prev.copy(
                        personaConfig = loaded,
                        lastGokuFeeling = feeling,
                        activeProsody = computeProsody(feeling, prev.lastUserMood, loaded)
                    )
                }
            }
        }
    }

    suspend fun savePersonaConfig(config: GokuPersonaConfig): Boolean {
        val clean = config.sanitized()
        currentConfig = clean
        _state.update { prev ->
            val feeling = recommendFeeling(prev.lastUserMood, clean)
            prev.copy(
                personaConfig = clean,
                lastGokuFeeling = feeling,
                activeProsody = computeProsody(feeling, prev.lastUserMood, clean)
            )
        }
        return repository.saveConfig(clean)
    }

    suspend fun applyPreset(presetId: PersonaPresetId): GokuPersonaConfig {
        val updated = repository.applyPreset(presetId)
        currentConfig = updated
        _state.update { prev ->
            val feeling = recommendFeeling(prev.lastUserMood, updated)
            prev.copy(
                personaConfig = updated,
                lastGokuFeeling = feeling,
                activeProsody = computeProsody(feeling, prev.lastUserMood, updated)
            )
        }
        return updated
    }

    /**
     * Fast, zero-network on-device heuristic mood analyzer combining Hinglish/English lexical cues,
     * punctuation/casing signals, and optional acoustic speech prosody metrics.
     */
    fun analyzeUserMood(
        text: String,
        prosody: SpeechProsodyMetrics? = null,
        config: GokuPersonaConfig = currentConfig
    ): MoodAnalysisResult {
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        val cues = mutableListOf<String>()

        var urgentScore = 0
        var angryScore = 0
        var tiredSadScore = 0
        var excitedScore = 0

        URGENT_KEYWORDS.forEach { kw ->
            if (lower.contains(kw)) {
                urgentScore += 3
                cues.add("urgent:$kw")
            }
        }
        ANGRY_KEYWORDS.forEach { kw ->
            if (lower.contains(kw)) {
                angryScore += 3
                cues.add("angry:$kw")
            }
        }
        TIRED_SAD_KEYWORDS.forEach { kw ->
            if (lower.contains(kw)) {
                tiredSadScore += 3
                cues.add("low:$kw")
            }
        }
        EXCITED_KEYWORDS.forEach { kw ->
            if (lower.contains(kw)) {
                excitedScore += 3
                cues.add("excited:$kw")
            }
        }

        // Uppercase shouting detection (words >= 3 letters in ALL CAPS)
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        val shoutWords = words.count { w ->
            val letters = w.filter { it.isLetter() }
            letters.length >= 3 && letters == letters.uppercase(Locale.ROOT)
        }
        if (shoutWords >= 2) {
            angryScore += 2
            cues.add("caps_emphasis")
        }

        val exclamationCount = trimmed.count { it == '!' }
        if (exclamationCount >= 2) {
            if (angryScore > 0) angryScore += 1
            else if (urgentScore > 0) urgentScore += 1
            else excitedScore += 2
            cues.add("multi_exclamation")
        }

        // Acoustic speech prosody cues (zero network delay)
        if (prosody != null) {
            val wps = prosody.speakingWordsPerSec
            if (wps != null) {
                when {
                    wps >= 3.6f && words.size >= 3 -> {
                        urgentScore += 2
                        cues.add("fast_speech:${"%.1f".format(Locale.US, wps)}wps")
                    }
                    wps in 0.2f..1.35f && words.size >= 3 -> {
                        tiredSadScore += 2
                        cues.add("slow_speech:${"%.1f".format(Locale.US, wps)}wps")
                    }
                }
            }
            val peakDb = prosody.peakEnergyDb
            if (peakDb != null && peakDb >= 8.5) {
                // SpeechRecognizer RMS dB spikes strongly (>8.5 dB) on raised voice
                if (excitedScore > angryScore) {
                    excitedScore += 1
                } else {
                    angryScore += 2
                }
                cues.add("energy_spike:${"%.1f".format(Locale.US, peakDb)}dB")
            }
        }

        val scores = listOf(
            UserMood.URGENT to urgentScore,
            UserMood.ANGRY to angryScore,
            UserMood.TIRED_SAD to tiredSadScore,
            UserMood.EXCITED to excitedScore
        )
        val top = scores.maxByOrNull { it.second }
        val detectedMood = if (top == null || top.second < 2) UserMood.NEUTRAL else top.first
        val confidence = when {
            detectedMood == UserMood.NEUTRAL -> 85
            top != null && top.second >= 6 -> 96
            top != null && top.second >= 4 -> 90
            else -> 80
        }

        val feeling = recommendFeeling(detectedMood, config)
        val activeProsody = computeProsody(feeling, detectedMood, config)
        _state.update {
            it.copy(
                lastUserMood = detectedMood,
                lastMoodConfidence = confidence,
                lastMoodCues = cues.take(5),
                lastGokuFeeling = feeling,
                activeProsody = activeProsody
            )
        }

        return MoodAnalysisResult(
            mood = detectedMood,
            confidence = confidence,
            cues = cues,
            recommendedFeeling = feeling
        )
    }

    /**
     * Builds the dynamic personality + mood injection block appended to both Online & Offline LLM prompts.
     */
    fun buildPersonaPromptDirective(
        moodResult: MoodAnalysisResult,
        config: GokuPersonaConfig = currentConfig
    ): String {
        val clean = config.sanitized()
        val sb = StringBuilder()
        sb.append("Persona: Call user '${clean.userNickname}'. ")
        sb.append("Sliders(Energy=${(clean.energyLevel * 100).roundToInt()}%, ")
        sb.append("Humor=${(clean.humorPlayfulness * 100).roundToInt()}%, ")
        sb.append("Caring=${(clean.caringEmpathy * 100).roundToInt()}%, ")
        sb.append("Focus=${(clean.seriousFocus * 100).roundToInt()}%). ")

        sb.append("Detected User Mood: ${moodResult.mood.name}. ")
        when (moodResult.mood) {
            UserMood.URGENT -> sb.append(
                "URGENT RULE: Reply in ONE ultra-short, direct sentence confirming the action immediately with zero fluff. Prefix reply with [SERIOUS]. "
            )
            UserMood.TIRED_SAD -> sb.append(
                "CARING RULE: Be warm, empathetic, calm, and comforting in natural Hinglish. Prefix reply with [CARING]. "
            )
            UserMood.ANGRY -> sb.append(
                "DE-ESCALATE RULE: Stay calm, patient, non-argumentative, acknowledge frustration briefly, and solve the issue immediately. Prefix reply with [SERIOUS] or [CARING]. "
            )
            UserMood.EXCITED -> sb.append(
                "HYPED RULE: Match their upbeat energy with loyal, enthusiastic Goku spirit in Hinglish. Prefix reply with [EXCITED] or [PLAYFUL]. "
            )
            UserMood.NEUTRAL -> {
                val prefTag = recommendFeeling(UserMood.NEUTRAL, clean).tag
                sb.append("TONE RULE: Natural friendly Hinglish matching sliders. Prefix reply with $prefTag. ")
            }
        }

        sb.append("Allowed emotion tags inside 'reply' prefix ONLY: [EXCITED], [CARING], [SERIOUS], [PLAYFUL]. ")
        if (clean.customCatchphrases.isNotEmpty()) {
            sb.append("User custom catchphrases (use naturally when fitting): ")
            sb.append(clean.customCatchphrases.joinToString(" | "))
            sb.append(". ")
        }
        if (clean.customSystemRules.isNotBlank()) {
            sb.append("Custom user rules (strictly follow): ")
            sb.append(clean.customSystemRules)
            sb.append(". ")
        }
        return sb.toString().trim()
    }

    /**
     * Strips `[EXCITED]`, `[CARING]`, `[SERIOUS]`, `[PLAYFUL]` emotion tags from LLM output so
     * users never see raw brackets in chat bubbles or hear them spoken aloud by TTS.
     */
    fun parseAndStripEmotionTag(
        rawReply: String,
        fallbackMood: UserMood = _state.value.lastUserMood,
        config: GokuPersonaConfig = currentConfig
    ): ParsedEmotionReply {
        val match = EMOTION_TAG_REGEX.find(rawReply)
        val explicitFeeling = when (match?.groupValues?.getOrNull(1)?.uppercase(Locale.ROOT)) {
            "EXCITED" -> GokuFeeling.EXCITED
            "CARING" -> GokuFeeling.CARING
            "SERIOUS" -> GokuFeeling.SERIOUS
            "PLAYFUL" -> GokuFeeling.PLAYFUL
            else -> null
        }
        val stripped = rawReply
            .replace(EMOTION_TAG_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { rawReply.trim() }

        val feeling = explicitFeeling ?: recommendFeeling(fallbackMood, config)
        val prosody = computeProsody(feeling, fallbackMood, config)
        _state.update {
            it.copy(
                lastGokuFeeling = feeling,
                activeProsody = prosody
            )
        }
        return ParsedEmotionReply(
            cleanText = stripped,
            detectedFeeling = feeling,
            hadExplicitTag = explicitFeeling != null
        )
    }

    /**
     * Adapts instant 0-sec offline reflex replies to the user's detected mood and custom nickname
     * without adding any network latency.
     */
    fun adaptReflexReplyForMood(
        rawReply: String,
        mood: UserMood,
        config: GokuPersonaConfig = currentConfig
    ): Pair<String, GokuFeeling> {
        val clean = config.sanitized()
        val nickname = clean.userNickname
        val personalized = if (!nickname.equals("Boss", ignoreCase = true)) {
            rawReply.replace(
                Regex("\\bboss\\b", RegexOption.IGNORE_CASE),
                Regex.escapeReplacement(nickname)
            )
        } else {
            rawReply
        }

        val feeling = recommendFeeling(mood, clean)
        val adapted = when (mood) {
            UserMood.URGENT -> {
                // Ultra-short, direct 1-sentence confirmation
                val firstSentence = personalized
                    .split(Regex("(?<=[.!?।])\\s+"))
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?: personalized.trim()
                firstSentence.take(140)
            }
            UserMood.TIRED_SAD -> {
                if (personalized.contains("aaram", ignoreCase = true) ||
                    personalized.contains("fikar", ignoreCase = true)
                ) {
                    personalized
                } else {
                    "Fikar mat karo $nickname, main hoon na. $personalized"
                }
            }
            UserMood.ANGRY -> {
                "Samajh gaya $nickname, turant fix kar raha hoon. $personalized"
            }
            UserMood.EXCITED -> {
                val catchphrase = clean.customCatchphrases.firstOrNull()
                if (clean.energyLevel >= 0.65f && !catchphrase.isNullOrBlank() && personalized.length < 130) {
                    "$personalized $catchphrase"
                } else {
                    personalized
                }
            }
            UserMood.NEUTRAL -> personalized
        }

        val prosody = computeProsody(feeling, mood, clean)
        _state.update {
            it.copy(
                lastGokuFeeling = feeling,
                activeProsody = prosody
            )
        }
        return adapted to feeling
    }

    /**
     * Computes live TTS speech rate, pitch, and pause cadence from UserMood + GokuFeeling + Custom Sliders.
     *
     * Baseline targets:
     * - TIRED_SAD / CARING -> calm rate ~0.92f, soft pitch ~0.98f
     * - ANGRY -> calm de-escalating rate ~0.95f, steady pitch ~0.96f
     * - URGENT / SERIOUS -> brisk rate ~1.12f, steady pitch ~1.00f
     * - EXCITED / NEUTRAL (Classic Goku) -> energetic rate ~1.08f, pitch ~1.12f
     */
    fun computeProsody(
        feeling: GokuFeeling,
        mood: UserMood,
        config: GokuPersonaConfig = currentConfig
    ): VoiceProsodyProfile {
        val clean = config.sanitized()
        // Compute relative offset from user's custom pitch/rate sliders so customization always takes effect
        val userRateDelta = clean.customSpeechRate - 1.06f
        val userPitchDelta = clean.customVoicePitch - 1.08f

        val (baseRate, basePitch, pauseMs) = when {
            mood == UserMood.TIRED_SAD || feeling == GokuFeeling.CARING -> Triple(0.92f, 0.98f, 280L)
            mood == UserMood.ANGRY -> Triple(0.95f, 0.96f, 220L)
            mood == UserMood.URGENT -> Triple(1.14f, 1.00f, 80L)
            feeling == GokuFeeling.SERIOUS -> Triple(1.06f, 0.99f, 120L)
            feeling == GokuFeeling.PLAYFUL -> Triple(1.08f, 1.14f, 150L)
            else -> Triple(1.08f, 1.12f, 140L) // EXCITED / NEUTRAL signature Goku
        }

        val energyNudge = (clean.energyLevel - 0.5f) * 0.06f
        val finalRate = (baseRate + userRateDelta * 0.75f + energyNudge)
            .coerceIn(0.68f, 1.55f)
        val finalPitch = (basePitch + userPitchDelta * 0.75f + energyNudge * 0.5f)
            .coerceIn(0.68f, 1.45f)

        return VoiceProsodyProfile(
            speechRate = finalRate,
            pitch = finalPitch,
            pauseCadenceMs = pauseMs,
            feeling = feeling,
            userMood = mood
        )
    }

    fun recommendFeeling(
        mood: UserMood,
        config: GokuPersonaConfig = currentConfig
    ): GokuFeeling {
        val clean = config.sanitized()
        return when (mood) {
            UserMood.URGENT -> GokuFeeling.SERIOUS
            UserMood.TIRED_SAD -> GokuFeeling.CARING
            UserMood.ANGRY -> if (clean.caringEmpathy >= clean.seriousFocus) GokuFeeling.CARING else GokuFeeling.SERIOUS
            UserMood.EXCITED -> if (clean.humorPlayfulness >= 0.75f) GokuFeeling.PLAYFUL else GokuFeeling.EXCITED
            UserMood.NEUTRAL -> when {
                clean.seriousFocus >= 0.75f -> GokuFeeling.SERIOUS
                clean.caringEmpathy >= 0.85f && clean.energyLevel < 0.55f -> GokuFeeling.CARING
                clean.humorPlayfulness >= 0.80f && clean.energyLevel >= 0.65f -> GokuFeeling.PLAYFUL
                else -> GokuFeeling.EXCITED
            }
        }
    }

    /**
     * Generates a live Hinglish preview utterance reflecting the exact sliders, nickname,
     * active feeling, and custom catchphrase for Persona Studio's "Test Voice & Feeling" button.
     */
    fun buildVoicePreviewSample(config: GokuPersonaConfig = currentConfig): ParsedEmotionReply {
        val clean = config.sanitized()
        val nickname = clean.userNickname
        val catchphrase = clean.customCatchphrases.firstOrNull().orEmpty()
        val feeling = recommendFeeling(UserMood.NEUTRAL, clean)

        val rawText = when (feeling) {
            GokuFeeling.CARING ->
                "${feeling.tag} Hey $nickname, sab theek hai na? Aaram se bolo, main hamesha tumhare saath hoon. $catchphrase"
            GokuFeeling.SERIOUS ->
                "${feeling.tag} $nickname, ultra-fast focus mode ready hai. Order do, turant execute karunga. $catchphrase"
            GokuFeeling.PLAYFUL ->
                "${feeling.tag} Yo $nickname! Aaj kaunsi training aur missions complete karne hain? $catchphrase"
            GokuFeeling.EXCITED ->
                "${feeling.tag} Yo $nickname! Goku full power mein ready hai! $catchphrase"
        }.trim()

        return parseAndStripEmotionTag(rawText, UserMood.NEUTRAL, clean)
    }

    companion object {
        private val EMOTION_TAG_REGEX = Regex("\\[(EXCITED|CARING|SERIOUS|PLAYFUL)]", RegexOption.IGNORE_CASE)

        private val URGENT_KEYWORDS = listOf(
            "jaldi", "turant", "abhi", "fatafat", "urgent", "asap", "emergency",
            "hurry", "quick", "right now", "fast", "der ho rahi", "late ho raha",
            "immediately", "ek second", "abhi ke abhi"
        )

        private val ANGRY_KEYWORDS = listOf(
            "gussa", "bakwas", "bkwas", "bekar", "pagal", "dimag kharab",
            "kyu nahi", "kyon nahi", "kaam nahi kar raha", "angry", "frustrated",
            "frustrating", "annoyed", "annoying", "stupid", "worst", "hate",
            "irritated", "irritating", "useless", "shut up", "band kar bakwas"
        )

        private val TIRED_SAD_KEYWORDS = listOf(
            "thak", "neend", "sad", "dukhi", "udaas", "akela", "lonely",
            "tired", "exhausted", "stress", "stressed", "tension", "depressed",
            "rona", "bura lag", "low feel", "sar dard", "headache", "mann nahi",
            "break chahiye", "drained", "overwhelmed"
        )

        private val EXCITED_KEYWORDS = listOf(
            "awesome", "amazing", "mast", "zabardast", "badhiya", "jeet", "won",
            "party", "khush", "happy", "yay", "woohoo", "love", "shandar",
            "maza aa gaya", "super", "haha", "let's go", "lets go", "🔥", "🎉", "😂"
        )

        @Volatile
        private var INSTANCE: GokuEmotionPersonaEngine? = null

        fun getInstance(context: Context): GokuEmotionPersonaEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GokuEmotionPersonaEngine(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
