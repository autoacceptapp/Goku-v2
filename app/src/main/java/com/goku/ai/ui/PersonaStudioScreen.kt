package com.goku.ai.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goku.ai.persona.EmotionEngineState
import com.goku.ai.persona.GokuFeeling
import com.goku.ai.persona.GokuPersonaConfig
import com.goku.ai.persona.PersonaPreferencesRepository
import com.goku.ai.persona.PersonaPresetId
import com.goku.ai.persona.UserMood
import java.util.Locale
import kotlin.math.roundToInt

private val StudioSurfaceDark = Color(0xFF0D1424)
private val StudioSurfaceElevated = Color(0xFF141E33)
private val StudioOutline = Color(0xFF223354)
private val StudioCyan = Color(0xFF00F0FF)
private val StudioViolet = Color(0xFF8B5CF6)
private val StudioAmber = Color(0xFFFFB800)
private val StudioCrimson = Color(0xFFFF2E54)
private val StudioEmerald = Color(0xFF10B981)
private val StudioTextPrimary = Color(0xFFF1F5F9)
private val StudioTextMuted = Color(0xFF94A3B8)

/**
 * 100% Customizable Bi-Directional Emotion & Personality Studio (Jarvis Dark Material 3).
 * Zero artificial limits on custom catchphrases or custom system rules.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonaStudioScreen(
    emotionState: EmotionEngineState,
    onSaveConfig: (GokuPersonaConfig) -> Unit,
    onApplyPreset: (PersonaPresetId) -> Unit,
    onTestVoiceAndFeeling: (GokuPersonaConfig) -> Unit
) {
    val ctx = LocalContext.current
    val persisted = emotionState.personaConfig

    var presetId by rememberSaveable(persisted.presetId) { mutableStateOf(persisted.presetId) }
    var energy by rememberSaveable(persisted.energyLevel) { mutableFloatStateOf(persisted.energyLevel) }
    var humor by rememberSaveable(persisted.humorPlayfulness) { mutableFloatStateOf(persisted.humorPlayfulness) }
    var caring by rememberSaveable(persisted.caringEmpathy) { mutableFloatStateOf(persisted.caringEmpathy) }
    var serious by rememberSaveable(persisted.seriousFocus) { mutableFloatStateOf(persisted.seriousFocus) }
    var pitch by rememberSaveable(persisted.customVoicePitch) { mutableFloatStateOf(persisted.customVoicePitch) }
    var rate by rememberSaveable(persisted.customSpeechRate) { mutableFloatStateOf(persisted.customSpeechRate) }
    var nickname by rememberSaveable(persisted.userNickname) { mutableStateOf(persisted.userNickname) }
    var customRules by rememberSaveable(persisted.customSystemRules) { mutableStateOf(persisted.customSystemRules) }
    var newCatchphrase by rememberSaveable { mutableStateOf("") }

    val catchphrases = remember { mutableStateListOf<String>() }
    LaunchedEffect(persisted.customCatchphrases) {
        if (catchphrases.toList() != persisted.customCatchphrases) {
            catchphrases.clear()
            catchphrases.addAll(persisted.customCatchphrases)
        }
    }

    fun buildDraftConfig(overridePreset: PersonaPresetId = presetId): GokuPersonaConfig {
        return GokuPersonaConfig(
            presetId = overridePreset,
            energyLevel = energy,
            humorPlayfulness = humor,
            caringEmpathy = caring,
            seriousFocus = serious,
            userNickname = nickname,
            customCatchphrases = catchphrases.toList(),
            customVoicePitch = pitch,
            customSpeechRate = rate,
            customSystemRules = customRules
        ).sanitized()
    }

    val feelingAccent = when (emotionState.lastGokuFeeling) {
        GokuFeeling.EXCITED -> StudioCyan
        GokuFeeling.CARING -> StudioEmerald
        GokuFeeling.SERIOUS -> StudioAmber
        GokuFeeling.PLAYFUL -> StudioViolet
    }

    val moodAccent = when (emotionState.lastUserMood) {
        UserMood.ANGRY -> StudioCrimson
        UserMood.URGENT -> StudioAmber
        UserMood.TIRED_SAD -> StudioViolet
        UserMood.EXCITED -> StudioEmerald
        UserMood.NEUTRAL -> StudioCyan
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 12.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        // 1. Header & Live Bi-Directional Emotion Telemetry Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurfaceDark),
                border = BorderStroke(1.dp, feelingAccent.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("persona_telemetry_card")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    feelingAccent.copy(alpha = 0.14f),
                                    StudioSurfaceDark,
                                    StudioViolet.copy(alpha = 0.10f)
                                )
                            )
                        )
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(feelingAccent.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.Psychology,
                                        contentDescription = "Persona Studio",
                                        tint = feelingAccent
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Persona & Emotion Studio",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = StudioTextPrimary
                                    )
                                    Text(
                                        text = "Bi-Directional Offline Mood + Live Prosody",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = StudioTextMuted
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EmotionStatusChip(
                                title = "USER MOOD",
                                value = emotionState.lastUserMood.label,
                                accent = moodAccent,
                                modifier = Modifier.weight(1f)
                            )
                            EmotionStatusChip(
                                title = "GOKU FEELING",
                                value = emotionState.lastGokuFeeling.label,
                                accent = feelingAccent,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live TTS • Rate ${"%.2f".format(Locale.US, emotionState.activeProsody.speechRate)}x • Pitch ${"%.2f".format(Locale.US, emotionState.activeProsody.pitch)}x • Pause ${emotionState.activeProsody.pauseCadenceMs}ms",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioCyan
                            )
                        }

                        Button(
                            onClick = {
                                val draft = buildDraftConfig()
                                onTestVoiceAndFeeling(draft)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = feelingAccent,
                                contentColor = Color(0xFF060A13)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("persona_test_voice_btn")
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Test Voice & Feeling")
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Test Voice & Feeling",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 2. Preset Chips Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurfaceDark),
                border = BorderStroke(1.dp, StudioOutline),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = StudioCyan)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Personality Presets",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioCyan
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PersonaPresetId.entries.forEach { itemPreset ->
                            val selected = presetId == itemPreset
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    presetId = itemPreset
                                    if (itemPreset != PersonaPresetId.CUSTOM) {
                                        val next = PersonaPreferencesRepository.presetConfig(itemPreset, buildDraftConfig())
                                        energy = next.energyLevel
                                        humor = next.humorPlayfulness
                                        caring = next.caringEmpathy
                                        serious = next.seriousFocus
                                        pitch = next.customVoicePitch
                                        rate = next.customSpeechRate
                                        onSaveConfig(next)
                                    } else {
                                        onSaveConfig(buildDraftConfig(PersonaPresetId.CUSTOM))
                                    }
                                },
                                label = { Text(itemPreset.displayName) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StudioCyan.copy(alpha = 0.22f),
                                    selectedLabelColor = StudioCyan,
                                    selectedLeadingIconColor = StudioCyan,
                                    labelColor = StudioTextPrimary
                                ),
                                modifier = Modifier.testTag("persona_preset_${itemPreset.name.lowercase(Locale.ROOT)}")
                            )
                        }
                    }
                    Text(
                        text = presetId.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextMuted
                    )
                }
            }
        }

        // 3. 4 Personality Dimension Sliders
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurfaceDark),
                border = BorderStroke(1.dp, StudioOutline),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Tune, contentDescription = null, tint = StudioViolet)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Personality Sliders",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioViolet
                        )
                    }

                    PersonaTraitSlider(
                        label = "Energy Level",
                        value = energy,
                        valueText = "${(energy * 100).roundToInt()}%",
                        accent = StudioCyan,
                        valueRange = 0f..1f,
                        tag = "persona_slider_energy",
                        onValueChange = {
                            energy = it
                            presetId = PersonaPresetId.CUSTOM
                        }
                    )
                    PersonaTraitSlider(
                        label = "Humor & Playfulness",
                        value = humor,
                        valueText = "${(humor * 100).roundToInt()}%",
                        accent = StudioViolet,
                        valueRange = 0f..1f,
                        tag = "persona_slider_humor",
                        onValueChange = {
                            humor = it
                            presetId = PersonaPresetId.CUSTOM
                        }
                    )
                    PersonaTraitSlider(
                        label = "Caring & Empathy",
                        value = caring,
                        valueText = "${(caring * 100).roundToInt()}%",
                        accent = StudioEmerald,
                        valueRange = 0f..1f,
                        tag = "persona_slider_caring",
                        onValueChange = {
                            caring = it
                            presetId = PersonaPresetId.CUSTOM
                        }
                    )
                    PersonaTraitSlider(
                        label = "Serious & Focus Mode",
                        value = serious,
                        valueText = "${(serious * 100).roundToInt()}%",
                        accent = StudioAmber,
                        valueRange = 0f..1f,
                        tag = "persona_slider_serious",
                        onValueChange = {
                            serious = it
                            presetId = PersonaPresetId.CUSTOM
                        }
                    )
                }
            }
        }

        // 4. Voice Prosody Sliders (Pitch 0.7f-1.4f, Rate 0.7f-1.5f)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurfaceDark),
                border = BorderStroke(1.dp, StudioOutline),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = StudioEmerald)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Voice Prosody & Cadence",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioEmerald
                        )
                    }
                    Text(
                        text = "Base voice profile automatically bends with your detected mood (Tired/Sad slows & softens, Urgent speeds up).",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextMuted
                    )

                    PersonaTraitSlider(
                        label = "Custom Voice Pitch",
                        value = pitch,
                        valueText = "${"%.2f".format(Locale.US, pitch)}x",
                        accent = StudioCyan,
                        valueRange = GokuPersonaConfig.MIN_VOICE_PITCH..GokuPersonaConfig.MAX_VOICE_PITCH,
                        tag = "persona_slider_pitch",
                        onValueChange = {
                            pitch = it
                            presetId = PersonaPresetId.CUSTOM
                        }
                    )
                    PersonaTraitSlider(
                        label = "Custom Speech Rate",
                        value = rate,
                        valueText = "${"%.2f".format(Locale.US, rate)}x",
                        accent = StudioEmerald,
                        valueRange = GokuPersonaConfig.MIN_SPEECH_RATE..GokuPersonaConfig.MAX_SPEECH_RATE,
                        tag = "persona_slider_rate",
                        onValueChange = {
                            rate = it
                            presetId = PersonaPresetId.CUSTOM
                        }
                    )
                }
            }
        }

        // 5. Nickname, Unlimited Catchphrases & Unlimited System Prompt Rules
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurfaceDark),
                border = BorderStroke(1.dp, StudioCyan.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Mood, contentDescription = null, tint = StudioAmber)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Identity, Catchphrases & Rules",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioAmber
                        )
                    }

                    OutlinedTextField(
                        value = nickname,
                        onValueChange = {
                            nickname = it
                            presetId = PersonaPresetId.CUSTOM
                        },
                        label = { Text("Preferred User Nickname (e.g. Boss, Bhai, Captain)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("persona_nickname_input")
                    )

                    HorizontalDivider(color = StudioOutline)

                    Text(
                        text = "Custom Catchphrases (${catchphrases.size} — Unlimited)",
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioTextPrimary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newCatchphrase,
                            onValueChange = { newCatchphrase = it },
                            label = { Text("Add catchphrase...") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("persona_catchphrase_input")
                        )
                        OutlinedButton(
                            onClick = {
                                val clean = newCatchphrase.trim()
                                if (clean.isNotEmpty()) {
                                    if (catchphrases.none { it.equals(clean, ignoreCase = true) }) {
                                        catchphrases.add(clean)
                                    }
                                    newCatchphrase = ""
                                    presetId = PersonaPresetId.CUSTOM
                                    onSaveConfig(buildDraftConfig(PersonaPresetId.CUSTOM))
                                }
                            },
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("persona_add_catchphrase_btn")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add Catchphrase", tint = StudioCyan)
                            Spacer(Modifier.width(4.dp))
                            Text("Add", color = StudioCyan)
                        }
                    }

                    AnimatedVisibility(visible = catchphrases.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.Favorite, contentDescription = null, tint = StudioTextMuted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "No catchphrases yet — add your favorite Hinglish lines above!",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextMuted
                            )
                        }
                    }

                    catchphrases.forEachIndexed { index, phrase ->
                        Surface(
                            color = StudioSurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, StudioOutline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "“$phrase”",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextPrimary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = {
                                        if (index in catchphrases.indices) {
                                            catchphrases.removeAt(index)
                                            presetId = PersonaPresetId.CUSTOM
                                            onSaveConfig(buildDraftConfig(PersonaPresetId.CUSTOM))
                                        }
                                    },
                                    modifier = Modifier.testTag("persona_remove_catchphrase_$index")
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove Catchphrase",
                                        tint = StudioCrimson
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = StudioOutline)

                    OutlinedTextField(
                        value = customRules,
                        onValueChange = {
                            customRules = it
                            presetId = PersonaPresetId.CUSTOM
                        },
                        label = { Text("Custom System Prompt Rules (Unlimited)") },
                        placeholder = {
                            Text("e.g. Always encourage my morning workouts, speak in friendly Mumbai Hinglish, never use robotic disclaimers.")
                        },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("persona_rules_input")
                    )

                    Button(
                        onClick = {
                            val draft = buildDraftConfig()
                            onSaveConfig(draft)
                            Toast.makeText(ctx, "Persona & Emotion settings saved!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioCyan,
                            contentColor = Color(0xFF060A13)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("persona_save_btn")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "Save Persona")
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Save Custom Persona",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmotionStatusChip(
    title: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = StudioSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PersonaTraitSlider(
    label: String,
    value: Float,
    valueText: String,
    accent: Color,
    valueRange: ClosedFloatingPointRange<Float>,
    tag: String,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = StudioTextPrimary)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = accent)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = StudioOutline
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
        )
    }
}
