package com.goku.ai.persona

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.io.IOException

private val Context.gokuPersonaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "goku_persona_preferences"
)

enum class PersonaPresetId(val displayName: String, val subtitle: String) {
    CLASSIC_ENERGETIC(
        displayName = "Classic Energetic Goku",
        subtitle = "Upbeat, loyal, playful Saiyan energy in natural Hinglish"
    ),
    CALM_CARING(
        displayName = "Calm Caring Mode",
        subtitle = "Warm, supportive, empathetic tone with gentle cadence"
    ),
    ULTRA_FAST_SERIOUS(
        displayName = "Ultra-Fast Serious Assistant",
        subtitle = "Crisp, focused, zero-fluff tactical execution"
    ),
    CUSTOM(
        displayName = "100% Custom",
        subtitle = "Every slider, catchphrase, pitch, rate, and rule tuned by you"
    );

    companion object {
        fun fromStored(raw: String?): PersonaPresetId {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: CLASSIC_ENERGETIC
        }
    }
}

/**
 * Full customizable personality & prosody profile persisted in Jetpack DataStore.
 * Zero artificial limits on custom catchphrases or custom system rules.
 */
data class GokuPersonaConfig(
    val presetId: PersonaPresetId = PersonaPresetId.CLASSIC_ENERGETIC,
    val energyLevel: Float = 0.85f,
    val humorPlayfulness: Float = 0.72f,
    val caringEmpathy: Float = 0.80f,
    val seriousFocus: Float = 0.35f,
    val userNickname: String = "Boss",
    val customCatchphrases: List<String> = DEFAULT_CATCHPHRASES,
    val customVoicePitch: Float = 1.08f,
    val customSpeechRate: Float = 1.06f,
    val customSystemRules: String = ""
) {
    fun sanitized(): GokuPersonaConfig = copy(
        energyLevel = energyLevel.coerceIn(0f, 1f),
        humorPlayfulness = humorPlayfulness.coerceIn(0f, 1f),
        caringEmpathy = caringEmpathy.coerceIn(0f, 1f),
        seriousFocus = seriousFocus.coerceIn(0f, 1f),
        userNickname = userNickname.trim().ifBlank { "Boss" },
        customCatchphrases = customCatchphrases.map { it.trim() }.filter { it.isNotEmpty() },
        customVoicePitch = customVoicePitch.coerceIn(MIN_VOICE_PITCH, MAX_VOICE_PITCH),
        customSpeechRate = customSpeechRate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE),
        customSystemRules = customSystemRules.trim()
    )

    companion object {
        const val MIN_VOICE_PITCH = 0.70f
        const val MAX_VOICE_PITCH = 1.40f
        const val MIN_SPEECH_RATE = 0.70f
        const val MAX_SPEECH_RATE = 1.50f

        val DEFAULT_CATCHPHRASES = listOf(
            "Chalo power up karte hain!",
            "Super Saiyan focus ON!",
            "Main hoon na, sab handle ho jayega!"
        )
    }
}

/**
 * Resilient Jetpack DataStore repository for Goku's Bi-Directional Emotion & Persona Studio.
 * Wraps all reads and writes in try-catch so storage hiccups never crash the app.
 */
class PersonaPreferencesRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dataStore = appContext.gokuPersonaDataStore

    private object Keys {
        val PRESET_ID = stringPreferencesKey("persona_preset_id")
        val ENERGY_LEVEL = floatPreferencesKey("persona_energy_level")
        val HUMOR_PLAYFULNESS = floatPreferencesKey("persona_humor_playfulness")
        val CARING_EMPATHY = floatPreferencesKey("persona_caring_empathy")
        val SERIOUS_FOCUS = floatPreferencesKey("persona_serious_focus")
        val USER_NICKNAME = stringPreferencesKey("persona_user_nickname")
        val CUSTOM_CATCHPHRASES_JSON = stringPreferencesKey("persona_custom_catchphrases_json")
        val CUSTOM_VOICE_PITCH = floatPreferencesKey("persona_custom_voice_pitch")
        val CUSTOM_SPEECH_RATE = floatPreferencesKey("persona_custom_speech_rate")
        val CUSTOM_SYSTEM_RULES = stringPreferencesKey("persona_custom_system_rules")
    }

    val configFlow: Flow<GokuPersonaConfig> = dataStore.data
        .catch { err ->
            if (err is IOException) {
                Log.w(TAG, "DataStore IO error while reading persona preferences; using defaults", err)
                emit(emptyPreferences())
            } else {
                Log.w(TAG, "Unexpected error reading persona preferences; using defaults", err)
                emit(emptyPreferences())
            }
        }
        .map { prefs -> mapPreferencesToConfig(prefs) }

    suspend fun getCurrentConfig(): GokuPersonaConfig {
        return try {
            configFlow.first()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read current persona config; returning default", t)
            GokuPersonaConfig()
        }
    }

    suspend fun saveConfig(config: GokuPersonaConfig): Boolean {
        val clean = config.sanitized()
        return try {
            dataStore.edit { prefs ->
                prefs[Keys.PRESET_ID] = clean.presetId.name
                prefs[Keys.ENERGY_LEVEL] = clean.energyLevel
                prefs[Keys.HUMOR_PLAYFULNESS] = clean.humorPlayfulness
                prefs[Keys.CARING_EMPATHY] = clean.caringEmpathy
                prefs[Keys.SERIOUS_FOCUS] = clean.seriousFocus
                prefs[Keys.USER_NICKNAME] = clean.userNickname
                prefs[Keys.CUSTOM_CATCHPHRASES_JSON] = serializeCatchphrases(clean.customCatchphrases)
                prefs[Keys.CUSTOM_VOICE_PITCH] = clean.customVoicePitch
                prefs[Keys.CUSTOM_SPEECH_RATE] = clean.customSpeechRate
                prefs[Keys.CUSTOM_SYSTEM_RULES] = clean.customSystemRules
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist GokuPersonaConfig to DataStore", t)
            false
        }
    }

    suspend fun applyPreset(presetId: PersonaPresetId): GokuPersonaConfig {
        val current = getCurrentConfig()
        val updated = presetConfig(presetId, current)
        saveConfig(updated)
        return updated
    }

    suspend fun addCatchphrase(phrase: String): Boolean {
        val cleanPhrase = phrase.trim()
        if (cleanPhrase.isEmpty()) return false
        val current = getCurrentConfig()
        if (current.customCatchphrases.any { it.equals(cleanPhrase, ignoreCase = true) }) {
            return true
        }
        return saveConfig(
            current.copy(
                presetId = PersonaPresetId.CUSTOM,
                customCatchphrases = current.customCatchphrases + cleanPhrase
            )
        )
    }

    suspend fun removeCatchphrase(index: Int): Boolean {
        val current = getCurrentConfig()
        if (index !in current.customCatchphrases.indices) return false
        val updatedList = current.customCatchphrases.toMutableList().apply { removeAt(index) }
        return saveConfig(
            current.copy(
                presetId = PersonaPresetId.CUSTOM,
                customCatchphrases = updatedList
            )
        )
    }

    private fun mapPreferencesToConfig(prefs: Preferences): GokuPersonaConfig {
        val defaults = GokuPersonaConfig()
        val catchphrases = prefs[Keys.CUSTOM_CATCHPHRASES_JSON]?.let { parseCatchphrases(it) }
            ?: defaults.customCatchphrases
        return GokuPersonaConfig(
            presetId = PersonaPresetId.fromStored(prefs[Keys.PRESET_ID]),
            energyLevel = prefs[Keys.ENERGY_LEVEL] ?: defaults.energyLevel,
            humorPlayfulness = prefs[Keys.HUMOR_PLAYFULNESS] ?: defaults.humorPlayfulness,
            caringEmpathy = prefs[Keys.CARING_EMPATHY] ?: defaults.caringEmpathy,
            seriousFocus = prefs[Keys.SERIOUS_FOCUS] ?: defaults.seriousFocus,
            userNickname = prefs[Keys.USER_NICKNAME]?.ifBlank { defaults.userNickname } ?: defaults.userNickname,
            customCatchphrases = catchphrases,
            customVoicePitch = prefs[Keys.CUSTOM_VOICE_PITCH] ?: defaults.customVoicePitch,
            customSpeechRate = prefs[Keys.CUSTOM_SPEECH_RATE] ?: defaults.customSpeechRate,
            customSystemRules = prefs[Keys.CUSTOM_SYSTEM_RULES] ?: defaults.customSystemRules
        ).sanitized()
    }

    companion object {
        private const val TAG = "PersonaPreferences"

        @Volatile
        private var INSTANCE: PersonaPreferencesRepository? = null

        fun getInstance(context: Context): PersonaPreferencesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PersonaPreferencesRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        fun presetConfig(
            presetId: PersonaPresetId,
            current: GokuPersonaConfig = GokuPersonaConfig()
        ): GokuPersonaConfig = when (presetId) {
            PersonaPresetId.CLASSIC_ENERGETIC -> current.copy(
                presetId = PersonaPresetId.CLASSIC_ENERGETIC,
                energyLevel = 0.90f,
                humorPlayfulness = 0.78f,
                caringEmpathy = 0.80f,
                seriousFocus = 0.30f,
                customVoicePitch = 1.12f,
                customSpeechRate = 1.08f
            )
            PersonaPresetId.CALM_CARING -> current.copy(
                presetId = PersonaPresetId.CALM_CARING,
                energyLevel = 0.42f,
                humorPlayfulness = 0.35f,
                caringEmpathy = 0.96f,
                seriousFocus = 0.48f,
                customVoicePitch = 0.98f,
                customSpeechRate = 0.92f
            )
            PersonaPresetId.ULTRA_FAST_SERIOUS -> current.copy(
                presetId = PersonaPresetId.ULTRA_FAST_SERIOUS,
                energyLevel = 0.58f,
                humorPlayfulness = 0.10f,
                caringEmpathy = 0.45f,
                seriousFocus = 0.96f,
                customVoicePitch = 1.00f,
                customSpeechRate = 1.16f
            )
            PersonaPresetId.CUSTOM -> current.copy(
                presetId = PersonaPresetId.CUSTOM
            )
        }.sanitized()

        internal fun serializeCatchphrases(list: List<String>): String {
            val arr = JSONArray()
            list.forEach { phrase ->
                val clean = phrase.trim()
                if (clean.isNotEmpty()) arr.put(clean)
            }
            return arr.toString()
        }

        internal fun parseCatchphrases(rawJson: String): List<String> {
            return try {
                val arr = JSONArray(rawJson)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optString(i, "").trim()
                        if (item.isNotEmpty()) add(item)
                    }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}
