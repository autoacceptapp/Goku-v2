package com.goku.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.goku.ai.adb.AdbStatus
import com.goku.ai.automation.CameraLiveEyeOcr
import com.goku.ai.automation.SensorReflexState
import com.goku.ai.body.RecordedStep
import com.goku.ai.brain.BrainVisualState
import com.goku.ai.brain.ChatMessage
import com.goku.ai.brain.ModelDownloadState
import com.goku.ai.brain.OFFLINE_MODEL_PRESETS
import com.goku.ai.brain.OfflineModelDownloadManager
import com.goku.ai.brain.PendingConfirmation
import com.goku.ai.data.ActionLogEntity
import com.goku.ai.data.ExpenseRecordEntity
import com.goku.ai.data.LearnedSkillEntity
import com.goku.ai.data.MemoryNodeEntity
import com.goku.ai.data.ScreenTimelineEntity
import com.goku.ai.voice.VoiceEngineState
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
// 1. JARVIS CYBERPUNK DARK DESIGN SYSTEM & CUSTOM GOOGLE FONTS
// ============================================================================

private val GokuObsidianBg = Color(0xFF060A13)
private val GokuSurfaceDark = Color(0xFF0D1424)
private val GokuSurfaceElevated = Color(0xFF141E33)
private val GokuOutline = Color(0xFF223354)
private val GokuCyan = Color(0xFF00F0FF)
private val GokuViolet = Color(0xFF8B5CF6)
private val GokuAmber = Color(0xFFFFB800)
private val GokuCrimson = Color(0xFFFF2E54)
private val GokuEmerald = Color(0xFF10B981)
private val GokuTextPrimary = Color(0xFFF1F5F9)
private val GokuTextMuted = Color(0xFF94A3B8)

val GokuDisplayFont = FontFamily(Font(R.font.space_grotesk, FontWeight.Bold))
val GokuBodyFont = FontFamily(Font(R.font.inter, FontWeight.Normal))
val GokuMonoFont = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

private val GokuCyberColorScheme = darkColorScheme(
    primary = GokuCyan,
    onPrimary = GokuObsidianBg,
    primaryContainer = Color(0xFF093244),
    onPrimaryContainer = GokuCyan,
    secondary = GokuViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF231842),
    onSecondaryContainer = Color(0xFFD8CCFF),
    tertiary = GokuAmber,
    onTertiary = GokuObsidianBg,
    background = GokuObsidianBg,
    onBackground = GokuTextPrimary,
    surface = GokuSurfaceDark,
    onSurface = GokuTextPrimary,
    surfaceVariant = GokuSurfaceElevated,
    onSurfaceVariant = GokuTextMuted,
    outline = GokuOutline,
    error = GokuCrimson,
    onError = Color.White
)

private val GokuTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = GokuDisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.2.sp
    ),
    titleMedium = TextStyle(
        fontFamily = GokuDisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = GokuDisplayFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = GokuBodyFont,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = GokuBodyFont,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelMedium = TextStyle(
        fontFamily = GokuMonoFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = GokuMonoFont,
        fontSize = 10.sp,
        lineHeight = 13.sp
    )
)

@Composable
fun GokuCyberTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GokuCyberColorScheme,
        typography = GokuTypography,
        content = content
    )
}

// ============================================================================
// 2. DASHBOARD UI STATE & CALLBACK CONTRACTS
// ============================================================================

data class PermissionWizardStatus(
    val runtimePermissionsGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val keepAliveRunning: Boolean = false,
    val floatingOrbRunning: Boolean = false
)

data class GokuSettingsSnapshot(
    val geminiKey: String = "",
    val groqKey: String = "",
    val deepSeekKey: String = "",
    val openRouterKey: String = "",
    val sosContacts: String = "",
    val githubRepo: String = "",
    val githubToken: String = "",
    val telegramBot: String = "",
    val telegramChatId: String = "",
    val smartCallPolicy: String = "",
    val localModelPath: String = "",
    val dryRunMode: Boolean = false,
    val autoAdSkip: Boolean = true,
    val otpAutopilot: Boolean = true,
    val mistakeBlocker: Boolean = true,
    val workFocusMode: Boolean = false,
    val smartCallEnabled: Boolean = false
)

data class GokuDashboardUiState(
    val brainState: BrainVisualState = BrainVisualState.IDLE,
    val voiceState: VoiceEngineState = VoiceEngineState(),
    val adbStatus: AdbStatus = AdbStatus(),
    val sensorState: SensorReflexState = SensorReflexState(),
    val permissionStatus: PermissionWizardStatus = PermissionWizardStatus(),
    val settings: GokuSettingsSnapshot = GokuSettingsSnapshot(),
    val isOnline: Boolean = false,
    val activeProvider: String = "OFFLINE-READY",
    val isLocalModelLoaded: Boolean = false,
    val isKillSwitchStopped: Boolean = false,
    val lastStopSource: String = "",
    val mistakeWarning: String? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val isTeachRecording: Boolean = false,
    val teachSteps: List<RecordedStep> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val actionLogs: List<ActionLogEntity> = emptyList(),
    val memories: List<MemoryNodeEntity> = emptyList(),
    val timeline: List<ScreenTimelineEntity> = emptyList(),
    val skills: List<LearnedSkillEntity> = emptyList(),
    val expenses: List<ExpenseRecordEntity> = emptyList(),
    val exportedSkillsJson: String? = null,
    val pluginOutput: String = ""
)

data class GokuDashboardCallbacks(
    val onSendCommand: (String) -> Unit,
    val onToggleListening: () -> Unit,
    val onEmergencyStop: () -> Unit,
    val onConfirmAction: (Boolean) -> Unit,
    val onDismissMistakeWarning: () -> Unit,
    val onTriggerSos: () -> Unit,
    val onRunDreamMode: () -> Unit,
    val onRunPhoneDoctor: () -> Unit,
    val onStartAdbPairing: () -> Unit,
    val onScanAdbMdns: () -> Unit,
    val onConnectAdbLoopback: () -> Unit,
    val onPairAdbDirect: (Int, String) -> Unit,
    val onToggleTeachMode: (Boolean, String, String) -> Unit,
    val onRequestRuntimePermissions: () -> Unit,
    val onOpenAccessibilitySettings: () -> Unit,
    val onOpenOverlaySettings: () -> Unit,
    val onOpenNotificationListenerSettings: () -> Unit,
    val onRequestBatteryExemption: () -> Unit,
    val onToggleFloatingOrb: (Boolean) -> Unit,
    val onUpdateSettings: (GokuSettingsSnapshot) -> Unit,
    val onRunLivePlugin: (String, Boolean) -> Unit,
    val onAddMemory: (String, String, String) -> Unit,
    val onDeleteMemory: (Long) -> Unit,
    val onDeleteSkill: (Long) -> Unit,
    val onExportSkills: () -> Unit,
    val onDismissExportedJson: () -> Unit,
    val onImportSkillsJson: (String) -> Unit,
    val onLiveEyeOcrText: (String) -> Unit
)

// ============================================================================
// 3. MAIN DASHBOARD SCAFFOLD
// ============================================================================

@Composable
fun GokuDashboardScreen(
    uiState: GokuDashboardUiState,
    callbacks: GokuDashboardCallbacks
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showLiveEyeDialog by rememberSaveable { mutableStateOf(false) }
    var showAdbPairDialog by rememberSaveable { mutableStateOf(false) }

    val tabs = listOf(
        Triple(stringResource(R.string.tab_core), Icons.Filled.Hub, "tab_core"),
        Triple(stringResource(R.string.tab_setup), Icons.Filled.Security, "tab_setup"),
        Triple(stringResource(R.string.tab_studio), Icons.Filled.BugReport, "tab_studio"),
        Triple(stringResource(R.string.tab_vault), Icons.Filled.Memory, "tab_vault")
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(
                containerColor = GokuSurfaceDark,
                contentColor = GokuCyan,
                windowInsets = WindowInsets.navigationBars
            ) {
                tabs.forEachIndexed { idx, (label, icon, tag) ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        modifier = Modifier.testTag(tag),
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GokuObsidianBg,
                            selectedTextColor = GokuCyan,
                            indicatorColor = GokuCyan,
                            unselectedIconColor = GokuTextMuted,
                            unselectedTextColor = GokuTextMuted
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF081022), GokuObsidianBg, Color(0xFF050810))
                    )
                )
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> CoreCommandTab(
                    uiState = uiState,
                    callbacks = callbacks,
                    onOpenAdbDialog = { showAdbPairDialog = true },
                    onOpenLiveEye = { showLiveEyeDialog = true }
                )
                1 -> SetupWizardTab(
                    uiState = uiState,
                    callbacks = callbacks,
                    onOpenAdbDialog = { showAdbPairDialog = true }
                )
                2 -> GokuStudioDebuggerTab(uiState = uiState, callbacks = callbacks)
                3 -> VaultAndSettingsTab(uiState = uiState, callbacks = callbacks)
            }
        }
    }

    if (showAdbPairDialog) {
        LocalAdbPairDialog(
            adbStatus = uiState.adbStatus,
            onDismiss = { showAdbPairDialog = false },
            onScanMdns = callbacks.onScanAdbMdns,
            onOpenDevOptions = callbacks.onStartAdbPairing,
            onConnect5555 = callbacks.onConnectAdbLoopback,
            onPair = { port, code ->
                callbacks.onPairAdbDirect(port, code)
                showAdbPairDialog = false
            }
        )
    }

    if (showLiveEyeDialog) {
        CameraLiveEyeDialog(
            hasCameraPermission = uiState.permissionStatus.runtimePermissionsGranted,
            onRequestPermissions = callbacks.onRequestRuntimePermissions,
            onDismiss = { showLiveEyeDialog = false },
            onTextExtracted = { text ->
                showLiveEyeDialog = false
                callbacks.onLiveEyeOcrText(text)
            }
        )
    }

    uiState.exportedSkillsJson?.let { exported ->
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = callbacks.onDismissExportedJson,
            containerColor = GokuSurfaceDark,
            title = { Text("Exported .goku Skill Pack", color = GokuCyan) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        exported.take(2500),
                        style = MaterialTheme.typography.labelMedium,
                        color = GokuTextPrimary
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("goku-skills", exported))
                    Toast.makeText(ctx, "Copied .goku JSON to clipboard", Toast.LENGTH_SHORT).show()
                    callbacks.onDismissExportedJson()
                }) { Text("Copy JSON") }
            },
            dismissButton = {
                TextButton(onClick = callbacks.onDismissExportedJson) { Text("Close") }
            }
        )
    }
}

// ============================================================================
// 4. TAB 0: CORE COMMAND DECK (PULSING GOKU ORB + VOICE/TEXT + QUICK ACTIONS)
// ============================================================================

@Composable
private fun CoreCommandTab(
    uiState: GokuDashboardUiState,
    callbacks: GokuDashboardCallbacks,
    onOpenAdbDialog: () -> Unit,
    onOpenLiveEye: () -> Unit
) {
    var commandText by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 36.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
    ) {
        // Top Header & Telemetry Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "GOKU V10",
                    style = MaterialTheme.typography.headlineSmall,
                    color = GokuCyan
                )
                Text(
                    text = "${uiState.activeProvider} • ${if (uiState.adbStatus.isConnected) "ADB 5555" else "A11Y FALLBACK"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = GokuTextMuted
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TelemetryPill(
                    text = if (uiState.isLocalModelLoaded) "LLM RAM" else "0MB IDLE",
                    color = if (uiState.isLocalModelLoaded) GokuViolet else GokuTextMuted
                )
                TelemetryPill(
                    text = if (uiState.isOnline) "ONLINE" else "OFFLINE",
                    color = if (uiState.isOnline) GokuEmerald else GokuAmber
                )
                TelemetryPill(
                    text = "${uiState.sensorState.batteryPercent}%",
                    color = if (uiState.sensorState.batteryPercent <= 20) GokuCrimson else GokuCyan
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Live Screen Mistake Blocker Warning Banner
        AnimatedVisibility(visible = !uiState.mistakeWarning.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A0C18)),
                border = BorderStroke(1.dp, GokuCrimson),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = "Warning", tint = GokuCrimson)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = uiState.mistakeWarning.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = callbacks.onDismissMistakeWarning) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Color.White)
                    }
                }
            }
        }

        // Hindi "Haan" / "Nahi" Safety Confirmation Gate Banner
        AnimatedVisibility(visible = uiState.pendingConfirmation != null) {
            uiState.pendingConfirmation?.let { pending ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF281D06)),
                    border = BorderStroke(1.5.dp, GokuAmber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "SAFETY GATE (${pending.confidence}% confidence)",
                            style = MaterialTheme.typography.labelMedium,
                            color = GokuAmber
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = pending.promptHinglish,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { callbacks.onConfirmAction(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = GokuEmerald),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("confirm_haan_button")
                            ) { Text("HAAN (Confirm)", color = GokuObsidianBg, fontWeight = FontWeight.Bold) }
                            Button(
                                onClick = { callbacks.onConfirmAction(false) },
                                colors = ButtonDefaults.buttonColors(containerColor = GokuCrimson),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("confirm_nahi_button")
                            ) { Text("NAHI (Cancel)", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }

        // Animated Pulsing Goku Core Orb + Emergency STOP Control Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(GokuSurfaceDark)
                .border(1.dp, GokuOutline, RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GokuCoreOrb(
                visualState = when {
                    uiState.isKillSwitchStopped -> BrainVisualState.STOPPED
                    uiState.voiceState.isListening -> BrainVisualState.LISTENING
                    else -> uiState.brainState
                },
                onClick = callbacks.onToggleListening,
                modifier = Modifier
                    .size(92.dp)
                    .testTag("goku_core_orb")
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                val stateLabel = when {
                    uiState.isKillSwitchStopped -> "STOPPED (${uiState.lastStopSource.ifBlank { "Manual" }})"
                    uiState.voiceState.isListening -> "LISTENING • ${uiState.voiceState.tier.name}"
                    uiState.brainState == BrainVisualState.THINKING -> "SWARM THINKING..."
                    uiState.brainState == BrainVisualState.ACTING -> "EXECUTING BODY ACTION..."
                    else -> "READY • Tap Orb or say 'Hey Goku'"
                }
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (uiState.isKillSwitchStopped) GokuCrimson else GokuTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val subInfo = uiState.voiceState.partialTranscript.ifBlank {
                    buildString {
                        append(if (uiState.voiceState.isWhisperMode) "Earphone Whisper ON • " else "Speaker Mode • ")
                        append(if (uiState.voiceState.isNightSoftMode) "Night Soft" else "Normal Vol")
                    }
                }
                Text(
                    text = subInfo,
                    style = MaterialTheme.typography.labelMedium,
                    color = GokuTextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Dedicated 1-Tap Red STOP Emergency Button
            Button(
                onClick = callbacks.onEmergencyStop,
                colors = ButtonDefaults.buttonColors(containerColor = GokuCrimson),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .heightIn(min = 52.dp)
                    .testTag("emergency_stop_button")
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.StopCircle, contentDescription = "Emergency Stop", tint = Color.White)
                    Text("STOP", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Quick Action Chips Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickChip(
                label = "Local ADB Pair",
                icon = Icons.Filled.Terminal,
                accent = GokuCyan,
                tag = "chip_adb_pair",
                onClick = onOpenAdbDialog
            )
            QuickChip(
                label = if (uiState.isTeachRecording) "Stop Teach (${uiState.teachSteps.size})" else "Teach Mode",
                icon = Icons.Filled.RadioButtonChecked,
                accent = if (uiState.isTeachRecording) GokuCrimson else GokuViolet,
                tag = "chip_teach_mode",
                onClick = {
                    callbacks.onToggleTeachMode(!uiState.isTeachRecording, "Custom Macro", "run my macro")
                }
            )
            QuickChip(
                label = "Dream Mode",
                icon = Icons.Filled.NightsStay,
                accent = GokuViolet,
                tag = "chip_dream_mode",
                onClick = callbacks.onRunDreamMode
            )
            QuickChip(
                label = "Emergency SOS",
                icon = Icons.Filled.HealthAndSafety,
                accent = GokuCrimson,
                tag = "chip_emergency_sos",
                onClick = callbacks.onTriggerSos
            )
            QuickChip(
                label = "Live-Eye OCR",
                icon = Icons.Filled.CameraAlt,
                accent = GokuAmber,
                tag = "chip_live_eye",
                onClick = onOpenLiveEye
            )
            QuickChip(
                label = "Phone Doctor",
                icon = Icons.Filled.AutoAwesome,
                accent = GokuEmerald,
                tag = "chip_phone_doctor",
                onClick = callbacks.onRunPhoneDoctor
            )
        }

        Spacer(Modifier.height(8.dp))

        // Conversation Feed
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(uiState.messages, key = { it.id }) { msg ->
                ChatBubbleCard(message = msg)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Live Voice & Text Command Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("command_input_field"),
                placeholder = {
                    Text(
                        stringResource(R.string.cmd_input_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GokuCyan,
                    unfocusedBorderColor = GokuOutline,
                    focusedContainerColor = GokuSurfaceDark,
                    unfocusedContainerColor = GokuSurfaceDark,
                    focusedTextColor = GokuTextPrimary,
                    unfocusedTextColor = GokuTextPrimary
                )
            )
            IconButton(
                onClick = callbacks.onToggleListening,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (uiState.voiceState.isListening) GokuCrimson else GokuSurfaceElevated)
                    .testTag("mic_toggle_button")
            ) {
                Icon(
                    imageVector = if (uiState.voiceState.isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = "Toggle Voice Mic",
                    tint = if (uiState.voiceState.isListening) Color.White else GokuCyan
                )
            }
            IconButton(
                onClick = {
                    if (commandText.isNotBlank()) {
                        callbacks.onSendCommand(commandText)
                        commandText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(GokuCyan)
                    .testTag("send_command_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send Command",
                    tint = GokuObsidianBg
                )
            }
        }
    }
}

@Composable
private fun GokuCoreOrb(
    visualState: BrainVisualState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val targetColor by animateColorAsState(
        targetValue = when (visualState) {
            BrainVisualState.LISTENING -> GokuCyan
            BrainVisualState.THINKING -> GokuViolet
            BrainVisualState.ACTING -> GokuAmber
            BrainVisualState.STOPPED -> GokuCrimson
            BrainVisualState.IDLE -> Color(0xFF0EA5E9)
        },
        animationSpec = tween(350),
        label = "orbColor"
    )

    val infinite = rememberInfiniteTransition(label = "orbPulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.84f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Outer glowing plasma halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(targetColor.copy(alpha = 0.42f), Color.Transparent),
                    center = center,
                    radius = radius * pulse
                ),
                radius = radius * pulse,
                center = center
            )

            // Rotating outer cyber ring
            rotate(rotation, pivot = center) {
                drawArc(
                    color = targetColor,
                    startAngle = 0f,
                    sweepAngle = 210f,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Counter-rotating inner neural arc
            rotate(-rotation * 1.4f, pivot = center) {
                drawArc(
                    color = GokuViolet.copy(alpha = 0.85f),
                    startAngle = 90f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(radius * 0.22f, radius * 0.22f),
                    size = androidx.compose.ui.geometry.Size(radius * 1.56f, radius * 1.56f),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Solid core
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, targetColor, targetColor.copy(alpha = 0.2f)),
                    center = center,
                    radius = radius * 0.56f
                ),
                radius = radius * 0.52f * (0.94f + (pulse - 0.84f) * 0.35f),
                center = center
            )
        }
    }
}

@Composable
private fun ChatBubbleCard(message: ChatMessage) {
    val alignment = if (message.isUser) Alignment.End else Alignment.Start
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = if (message.isUser) Color(0xFF0E2942) else GokuSurfaceDark,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            ),
            border = BorderStroke(
                1.dp,
                if (message.isUser) GokuCyan.copy(alpha = 0.4f) else GokuOutline
            ),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!message.isUser) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = message.engineBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = GokuCyan
                        )
                        Text(
                            text = "${message.confidence}% CONF",
                            style = MaterialTheme.typography.labelSmall,
                            color = GokuEmerald
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GokuTextPrimary
                )
            }
        }
    }
}

// ============================================================================
// 5. TAB 1: 1-TAP PERMISSION, KEEP-ALIVE & LOCAL ADB SETUP WIZARD
// ============================================================================

@Composable
private fun SetupWizardTab(
    uiState: GokuDashboardUiState,
    callbacks: GokuDashboardCallbacks,
    onOpenAdbDialog: () -> Unit
) {
    val perm = uiState.permissionStatus
    val settings = uiState.settings

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 36.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text("1-Tap Permission & Keep-Alive Wizard", style = MaterialTheme.typography.headlineSmall, color = GokuCyan)
            Text(
                "Grant these once so Android 14/15 never kills Goku and the Phone Body works seamlessly.",
                style = MaterialTheme.typography.bodySmall,
                color = GokuTextMuted
            )
        }

        item {
            SetupRowCard(
                title = "Core Hardware Permissions",
                subtitle = "Mic, Phone, SMS, Camera, GPS & Notifications",
                isGranted = perm.runtimePermissionsGranted,
                actionLabel = if (perm.runtimePermissionsGranted) "Granted" else "Grant All",
                testTag = "wizard_runtime_perms",
                onClick = callbacks.onRequestRuntimePermissions
            )
        }
        item {
            SetupRowCard(
                title = "Goku Accessibility Phone Body",
                subtitle = "Event-driven UI vision, Ad Skipper, OTP Fill & Auto-Fallback",
                isGranted = perm.accessibilityEnabled,
                actionLabel = if (perm.accessibilityEnabled) "Active" else "Enable",
                testTag = "wizard_accessibility",
                onClick = callbacks.onOpenAccessibilitySettings
            )
        }
        item {
            SetupRowCard(
                title = "Floating Jarvis Orb & Red STOP Overlay",
                subtitle = "SYSTEM_ALERT_WINDOW for 1-tap STOP & ADB 6-digit pairing box",
                isGranted = perm.overlayEnabled,
                actionLabel = if (perm.overlayEnabled) "Allowed" else "Allow",
                testTag = "wizard_overlay",
                onClick = callbacks.onOpenOverlaySettings
            )
        }
        item {
            SetupRowCard(
                title = "Notification & Expense Listener",
                subtitle = "Auto-extracts SMS OTPs & tracks bills/expenses locally",
                isGranted = perm.notificationListenerEnabled,
                actionLabel = if (perm.notificationListenerEnabled) "Connected" else "Enable",
                testTag = "wizard_notification_listener",
                onClick = callbacks.onOpenNotificationListenerSettings
            )
        }
        item {
            SetupRowCard(
                title = "Battery Optimization Exemption",
                subtitle = "Prevents Doze from killing Goku Keep-Alive Watchdog",
                isGranted = perm.batteryOptimizationIgnored,
                actionLabel = if (perm.batteryOptimizationIgnored) "Exempt" else "Exempt Now",
                testTag = "wizard_battery",
                onClick = callbacks.onRequestBatteryExemption
            )
        }

        // Floating Overlay Service Toggle Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
                border = BorderStroke(1.dp, GokuOutline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Floating Jarvis Orb + Red STOP", style = MaterialTheme.typography.titleSmall, color = GokuTextPrimary)
                        Text(
                            if (perm.floatingOrbRunning) "Overlay active over all apps" else "Start floating control orb",
                            style = MaterialTheme.typography.bodySmall,
                            color = GokuTextMuted
                        )
                    }
                    Switch(
                        checked = perm.floatingOrbRunning,
                        onCheckedChange = callbacks.onToggleFloatingOrb,
                        modifier = Modifier.testTag("switch_floating_orb"),
                        colors = SwitchDefaults.colors(checkedThumbColor = GokuObsidianBg, checkedTrackColor = GokuCyan)
                    )
                }
            }
        }

        // Local ADB Wireless Debugging Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
                border = BorderStroke(1.dp, if (uiState.adbStatus.isConnected) GokuEmerald else GokuCyan.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Direct Local ADB (127.0.0.1:5555)", style = MaterialTheme.typography.titleMedium, color = GokuCyan)
                    Spacer(Modifier.height(4.dp))
                    Text(uiState.adbStatus.statusMessage, style = MaterialTheme.typography.bodySmall, color = GokuTextPrimary)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenAdbDialog,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("setup_adb_pair_btn")
                        ) { Text("6-Digit Pair") }
                        OutlinedButton(
                            onClick = callbacks.onConnectAdbLoopback,
                            modifier = Modifier.weight(1f)
                        ) { Text("Connect 5555") }
                    }
                }
            }
        }

        // Autonomous Guardrails & Feature Toggles
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
                border = BorderStroke(1.dp, GokuOutline)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Autonomous Phone Body Controls", style = MaterialTheme.typography.titleMedium, color = GokuViolet)
                    SettingToggleRow("Auto-Ad & Popup Skipper (0.2s)", settings.autoAdSkip) {
                        callbacks.onUpdateSettings(settings.copy(autoAdSkip = it))
                    }
                    SettingToggleRow("SMS OTP Autopilot Auto-Fill", settings.otpAutopilot) {
                        callbacks.onUpdateSettings(settings.copy(otpAutopilot = it))
                    }
                    SettingToggleRow("Live Screen Mistake Blocker", settings.mistakeBlocker) {
                        callbacks.onUpdateSettings(settings.copy(mistakeBlocker = it))
                    }
                    SettingToggleRow("Work Focus Shield (Mute Alerts)", settings.workFocusMode) {
                        callbacks.onUpdateSettings(settings.copy(workFocusMode = it))
                    }
                    SettingToggleRow("Smart Call 30s Auto-Answer & Negotiator", settings.smartCallEnabled) {
                        callbacks.onUpdateSettings(settings.copy(smartCallEnabled = it))
                    }
                    SettingToggleRow("Goku Studio Dry-Run Mode", settings.dryRunMode) {
                        callbacks.onUpdateSettings(settings.copy(dryRunMode = it))
                    }
                }
            }
        }
    }
}

// ============================================================================
// 6. TAB 2: GOKU STUDIO LIVE DEBUGGER, LIVE PLUGIN RUNNER & EXPENSE GUARDIAN
// ============================================================================

@Composable
private fun GokuStudioDebuggerTab(
    uiState: GokuDashboardUiState,
    callbacks: GokuDashboardCallbacks
) {
    var pluginJson by rememberSaveable {
        mutableStateOf(
            """{"steps":[{"action":"speak","target":"Goku live plugin 0.1 second mein chal gaya!"},{"action":"save_memory","key":"plugin_check","target":"Verified V10"}]}"""
        )
    }
    var teachSkillName by rememberSaveable { mutableStateOf("Morning Routine") }
    var teachTrigger by rememberSaveable { mutableStateOf("morning routine") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 36.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text("Goku Studio & Live Debugger", style = MaterialTheme.typography.headlineSmall, color = GokuCyan)
            Text(
                "Real-time Swarm telemetry, Teach-by-Example recorder, Live JSON Plugins & Expense Guardian.",
                style = MaterialTheme.typography.bodySmall,
                color = GokuTextMuted
            )
        }

        // Teach-by-Example Macro Recorder Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
                border = BorderStroke(1.dp, if (uiState.isTeachRecording) GokuCrimson else GokuViolet)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Teach-by-Example Skill Recorder", style = MaterialTheme.typography.titleMedium, color = GokuViolet)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = teachSkillName,
                            onValueChange = { teachSkillName = it },
                            label = { Text("Skill Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = teachTrigger,
                            onValueChange = { teachTrigger = it },
                            label = { Text("Voice Trigger") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            callbacks.onToggleTeachMode(!uiState.isTeachRecording, teachSkillName, teachTrigger)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isTeachRecording) GokuCrimson else GokuViolet
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("studio_teach_toggle")
                    ) {
                        Text(
                            if (uiState.isTeachRecording) "Stop & Save Skill (${uiState.teachSteps.size} steps captured)"
                            else "Start Recording Screen Taps",
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Instant In-App Live Plugin Runner (0.1s JSON Workflow)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
                border = BorderStroke(1.dp, GokuOutline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Code, contentDescription = null, tint = GokuCyan)
                        Spacer(Modifier.width(6.dp))
                        Text("Live Self-Coding Plugin Runner", style = MaterialTheme.typography.titleMedium, color = GokuCyan)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pluginJson,
                        onValueChange = { pluginJson = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(104.dp),
                        textStyle = MaterialTheme.typography.labelMedium.copy(color = GokuTextPrimary)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { callbacks.onRunLivePlugin(pluginJson, false) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("run_live_plugin_btn")
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Run Live")
                        }
                        OutlinedButton(
                            onClick = { callbacks.onRunLivePlugin(pluginJson, true) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Dry-Run") }
                    }
                    if (uiState.pluginOutput.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            uiState.pluginOutput,
                            style = MaterialTheme.typography.labelMedium,
                            color = GokuEmerald
                        )
                    }
                }
            }
        }

        // Autonomous Financial & Bill Guardian Summary
        item {
            val totalSpent = uiState.expenses.sumOf { it.amountInr }
            Card(
                colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
                border = BorderStroke(1.dp, GokuAmber.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Financial & Bill Guardian", style = MaterialTheme.typography.titleMedium, color = GokuAmber)
                        Text("₹${"%.0f".format(totalSpent)}", style = MaterialTheme.typography.titleMedium, color = GokuEmerald)
                    }
                    Spacer(Modifier.height(4.dp))
                    if (uiState.expenses.isEmpty()) {
                        Text(
                            "No debits logged yet. Enable Notification Listener to auto-track bank/bill alerts locally.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GokuTextMuted
                        )
                    } else {
                        uiState.expenses.take(4).forEach { exp ->
                            Text(
                                "• ${exp.category}: ₹${"%.0f".format(exp.amountInr)} @ ${exp.merchant}",
                                style = MaterialTheme.typography.bodySmall,
                                color = GokuTextPrimary
                            )
                        }
                    }
                }
            }
        }

        // Live Swarm Action Logs
        item {
            Text(
                "Live Swarm Trace (${uiState.actionLogs.size})",
                style = MaterialTheme.typography.titleMedium,
                color = GokuTextPrimary
            )
        }

        if (uiState.actionLogs.isEmpty()) {
            item {
                Text("No actions logged yet. Run any command to see Planner -> Executor -> Critic trace.", style = MaterialTheme.typography.bodySmall, color = GokuTextMuted)
            }
        } else {
            items(uiState.actionLogs.take(40), key = { it.id }) { log ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
                    border = BorderStroke(1.dp, GokuOutline)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "[${log.agentStage}] ${log.status} • ${log.confidence}% • ${log.latencyMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = when (log.status) {
                                    "ERROR", "STOPPED" -> GokuCrimson
                                    "WARN" -> GokuAmber
                                    else -> GokuCyan
                                }
                            )
                            Text(
                                formatTime(log.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = GokuTextMuted
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(log.command, style = MaterialTheme.typography.titleSmall, color = GokuTextPrimary)
                        Text(log.detail, style = MaterialTheme.typography.bodySmall, color = GokuTextMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ============================================================================
// 7. TAB 3: MEMORY VAULT, .GOKU SKILLS & ENCRYPTED API/SOS SETTINGS
// ============================================================================

@Composable
private fun VaultAndSettingsTab(
    uiState: GokuDashboardUiState,
    callbacks: GokuDashboardCallbacks
) {
    var memKey by rememberSaveable { mutableStateOf("") }
    var memVal by rememberSaveable { mutableStateOf("") }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var importJsonText by rememberSaveable { mutableStateOf("") }

    var geminiKey by remember(uiState.settings.geminiKey) { mutableStateOf(uiState.settings.geminiKey) }
    var groqKey by remember(uiState.settings.groqKey) { mutableStateOf(uiState.settings.groqKey) }
    var deepSeekKey by remember(uiState.settings.deepSeekKey) { mutableStateOf(uiState.settings.deepSeekKey) }
    var openRouterKey by remember(uiState.settings.openRouterKey) { mutableStateOf(uiState.settings.openRouterKey) }
    var sosContacts by remember(uiState.settings.sosContacts) { mutableStateOf(uiState.settings.sosContacts) }
    var smartCallPolicy by remember(uiState.settings.smartCallPolicy) { mutableStateOf(uiState.settings.smartCallPolicy) }
    var githubRepo by remember(uiState.settings.githubRepo) { mutableStateOf(uiState.settings.githubRepo) }
    var githubToken by remember(uiState.settings.githubToken) { mutableStateOf(uiState.settings.githubToken) }
    var localModelPath by remember(uiState.settings.localModelPath) { mutableStateOf(uiState.settings.localModelPath) }
    val ctx = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 36.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            Text("Memory Vault & V10 Settings", style = MaterialTheme.typography.headlineSmall, color = GokuCyan)
        }

        // Latest Offline AI Brain (1-Tap Download + HTTP Range Resume + 15s RAM Auto-Unload)
        item {
            LatestOfflineAiBrainCard(isLoadedInRam = uiState.isLocalModelLoaded)
        }

        // Personal Knowledge Graph Quick Add
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
                border = BorderStroke(1.dp, GokuOutline)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Personal Memory Vault (${uiState.memories.size} nodes)", style = MaterialTheme.typography.titleMedium, color = GokuCyan)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = memKey,
                            onValueChange = { memKey = it },
                            label = { Text("Key (e.g. Mom)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = memVal,
                            onValueChange = { memVal = it },
                            label = { Text("Value (Phone/Fact)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = {
                            if (memKey.isNotBlank() && memVal.isNotBlank()) {
                                callbacks.onAddMemory(memKey.trim(), memVal.trim(), "FACT")
                                memKey = ""
                                memVal = ""
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_memory_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Save to Vault")
                    }
                    uiState.memories.take(10).forEach { node ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${node.key} = ${node.value}", style = MaterialTheme.typography.bodySmall, color = GokuTextPrimary)
                                Text(node.category, style = MaterialTheme.typography.labelSmall, color = GokuTextMuted)
                            }
                            IconButton(onClick = { callbacks.onDeleteMemory(node.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete Memory", tint = GokuCrimson)
                            }
                        }
                    }
                }
            }
        }

        // Learned Skills (.goku Export/Import + A/B Benchmarks)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
                border = BorderStroke(1.dp, GokuViolet.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Learned .goku Skills (${uiState.skills.size})", style = MaterialTheme.typography.titleMedium, color = GokuViolet)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = callbacks.onExportSkills,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_goku_skills_btn")
                        ) {
                            Icon(Icons.Filled.Upload, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Export .goku")
                        }
                        OutlinedButton(
                            onClick = { showImportDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_goku_skills_btn")
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Import .goku")
                        }
                    }
                    if (uiState.skills.isEmpty()) {
                        Text("No learned skills yet. Use Teach Mode in Studio tab or Import a .goku pack.", style = MaterialTheme.typography.bodySmall, color = GokuTextMuted)
                    } else {
                        uiState.skills.forEach { sk ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${sk.name} ('${sk.triggerPhrase}')", style = MaterialTheme.typography.titleSmall, color = GokuTextPrimary)
                                    Text(
                                        "Best: ${sk.preferredEngine} | ADB ${sk.adbAvgMs}ms (${(sk.adbSuccessRate * 100).toInt()}%) vs A11Y ${sk.a11yAvgMs}ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GokuCyan
                                    )
                                }
                                IconButton(onClick = { callbacks.onDeleteSkill(sk.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete Skill", tint = GokuCrimson)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Compressed Visual Timeline Preview
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
                border = BorderStroke(1.dp, GokuOutline)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Compressed Visual Memory (${uiState.timeline.size} frames)", style = MaterialTheme.typography.titleMedium, color = GokuAmber)
                    if (uiState.timeline.isEmpty()) {
                        Text("No screen frames indexed yet. Enable Accessibility Service to build zero-bloat visual memory.", style = MaterialTheme.typography.bodySmall, color = GokuTextMuted)
                    } else {
                        uiState.timeline.take(5).forEach { snap ->
                            Text(
                                "• ${snap.screenTitle} (${snap.compressedText.size}B): ${snap.summary.take(90)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = GokuTextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Encrypted API Keys, Family SOS & Cloud Archive Settings
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
                border = BorderStroke(1.dp, GokuCyan.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Settings, contentDescription = null, tint = GokuCyan)
                        Spacer(Modifier.width(6.dp))
                        Text("Encrypted Keys, SOS & Smart Call", style = MaterialTheme.typography.titleMedium, color = GokuCyan)
                    }
                    Text(
                        "Stored locally with AES-256 EncryptedSharedPreferences. Offline brain works even with blank keys.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GokuTextMuted
                    )
                    OutlinedTextField(
                        value = sosContacts,
                        onValueChange = { sosContacts = it },
                        label = { Text("Family SOS Numbers (comma separated)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = smartCallPolicy,
                        onValueChange = { smartCallPolicy = it },
                        label = { Text("Smart Call 30s Negotiation Policy") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    HorizontalDivider(color = GokuOutline)
                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = { geminiKey = it },
                        label = { Text("Gemini Free API Key (Primary Cloud)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = groqKey,
                        onValueChange = { groqKey = it },
                        label = { Text("Groq Free API Key (Failover #1)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = deepSeekKey,
                        onValueChange = { deepSeekKey = it },
                        label = { Text("DeepSeek API Key (Failover #2)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = openRouterKey,
                        onValueChange = { openRouterKey = it },
                        label = { Text("OpenRouter Free Key (Failover #3)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = localModelPath,
                        onValueChange = { localModelPath = it },
                        label = { Text("Offline 4-Bit GGUF Model Path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = githubRepo,
                        onValueChange = { githubRepo = it },
                        label = { Text("GitHub Archive Repo (user/repo)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = githubToken,
                        onValueChange = { githubToken = it },
                        label = { Text("GitHub PAT (Optional Dream Backup)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            callbacks.onUpdateSettings(
                                uiState.settings.copy(
                                    geminiKey = geminiKey,
                                    groqKey = groqKey,
                                    deepSeekKey = deepSeekKey,
                                    openRouterKey = openRouterKey,
                                    sosContacts = sosContacts,
                                    smartCallPolicy = smartCallPolicy,
                                    githubRepo = githubRepo,
                                    githubToken = githubToken,
                                    localModelPath = localModelPath
                                )
                            )
                            Toast.makeText(ctx, "Encrypted Vault settings saved!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("save_vault_settings_btn")
                    ) { Text("Save Encrypted Settings") }
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = GokuSurfaceDark,
            title = { Text("Import .goku Skill JSON", color = GokuCyan) },
            text = {
                OutlinedTextField(
                    value = importJsonText,
                    onValueChange = { importJsonText = it },
                    label = { Text("Paste .goku JSON") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (importJsonText.isNotBlank()) {
                        callbacks.onImportSkillsJson(importJsonText)
                        importJsonText = ""
                    }
                    showImportDialog = false
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ============================================================================
// 8. LATEST OFFLINE AI BRAIN (1-TAP DOWNLOAD + RANGE RESUME + RAM MONITOR)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LatestOfflineAiBrainCard(isLoadedInRam: Boolean) {
    val ctx = LocalContext.current
    val downloadManager = remember(ctx) { OfflineModelDownloadManager.getInstance(ctx) }
    val downloadState by downloadManager.state.collectAsStateWithLifecycle()

    var selectedPresetIndex by rememberSaveable { mutableIntStateOf(0) }
    var useCustomUrl by rememberSaveable { mutableStateOf(false) }
    var customUrl by rememberSaveable { mutableStateOf("") }

    val localModelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            downloadManager.importLocalModel(uri)
        }
    }

    val selectedPreset = OFFLINE_MODEL_PRESETS.getOrElse(selectedPresetIndex) { OFFLINE_MODEL_PRESETS.first() }

    Card(
        colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
        border = BorderStroke(
            width = 1.2.dp,
            color = when (downloadState) {
                is ModelDownloadState.Ready -> GokuEmerald.copy(alpha = 0.75f)
                is ModelDownloadState.Downloading -> GokuCyan
                is ModelDownloadState.Error -> GokuCrimson.copy(alpha = 0.75f)
                else -> GokuViolet.copy(alpha = 0.6f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("offline_ai_brain_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row + Live RAM Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudDownload,
                        contentDescription = "Offline AI Brain",
                        tint = GokuCyan
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Latest Offline AI Brain (1-Tap Download)",
                        style = MaterialTheme.typography.titleMedium,
                        color = GokuCyan
                    )
                }
                Spacer(Modifier.width(6.dp))
                TelemetryPill(
                    text = if (isLoadedInRam) "Loaded" else "Sleeping - 0MB RAM",
                    color = if (isLoadedInRam) GokuEmerald else GokuAmber
                )
            }

            Text(
                text = "MediaPipe / LiteRT GenAI on-device LLM with HTTP Range resume & strict 15s idle auto-unload. Open-access models — no login or token needed.",
                style = MaterialTheme.typography.bodySmall,
                color = GokuTextMuted
            )

            // Open-Access Model Preset Chips (hidden while actively downloading)
            if (downloadState !is ModelDownloadState.Downloading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OFFLINE_MODEL_PRESETS.forEachIndexed { idx, preset ->
                        val active = !useCustomUrl && selectedPresetIndex == idx
                        FilterChip(
                            selected = active,
                            onClick = {
                                useCustomUrl = false
                                selectedPresetIndex = idx
                            },
                            modifier = Modifier.testTag("preset_${preset.id}"),
                            label = {
                                Text(
                                    "${preset.name} (~${preset.approxSizeMb}MB)",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GokuCyan.copy(alpha = 0.16f),
                                selectedLabelColor = GokuCyan
                            )
                        )
                    }
                    FilterChip(
                        selected = useCustomUrl,
                        onClick = { useCustomUrl = !useCustomUrl },
                        modifier = Modifier.testTag("preset_custom_url"),
                        label = { Text("Custom URL", style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = GokuViolet.copy(alpha = 0.2f),
                            selectedLabelColor = GokuViolet
                        )
                    )
                }

                if (!useCustomUrl) {
                    Text(
                        text = "${selectedPreset.name}: ${selectedPreset.subtitle}",
                        style = MaterialTheme.typography.labelMedium,
                        color = GokuTextPrimary
                    )
                } else {
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("Direct HTTPS .task / .bin URL") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_model_url_input")
                    )
                }
            }

            // Dynamic Download / Ready / Error Body
            when (val st = downloadState) {
                is ModelDownloadState.Idle -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (useCustomUrl) {
                                    downloadManager.startDownload(customUrl, "Custom Offline LLM")
                                } else {
                                    downloadManager.startDownload(selectedPreset.directUrl, selectedPreset.name)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GokuCyan),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("offline_model_download_btn")
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, tint = GokuObsidianBg)
                            Spacer(Modifier.width(6.dp))
                            Text("1-Tap Download", color = GokuObsidianBg, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { localModelPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("offline_model_import_btn")
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = "Import Local .task")
                            Spacer(Modifier.width(4.dp))
                            Text("Import")
                        }
                    }
                }

                is ModelDownloadState.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Downloading ${st.modelName}...",
                                style = MaterialTheme.typography.titleSmall,
                                color = GokuCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${st.progressPercent}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = GokuCyan
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (st.progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .testTag("offline_model_progress_bar"),
                            color = GokuCyan,
                            trackColor = GokuSurfaceElevated
                        )
                        val totalText = if (st.totalMb > 0f) "${"%.1f".format(st.totalMb)} MB" else "..."
                        val speedText = if (st.speedMbps > 0.01f) " • ${"%.2f".format(st.speedMbps)} MB/s" else ""
                        Text(
                            text = "${"%.1f".format(st.downloadedMb)} MB / $totalText$speedText",
                            style = MaterialTheme.typography.labelMedium,
                            color = GokuTextMuted
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { downloadManager.pauseDownload() },
                                colors = ButtonDefaults.buttonColors(containerColor = GokuAmber),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("offline_model_pause_btn")
                            ) {
                                Icon(Icons.Filled.Pause, contentDescription = null, tint = GokuObsidianBg)
                                Spacer(Modifier.width(6.dp))
                                Text("Pause", color = GokuObsidianBg, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { downloadManager.deleteModel() },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Cancel", tint = GokuCrimson)
                            }
                        }
                    }
                }

                is ModelDownloadState.Paused -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Paused: ${st.modelName} (${st.progressPercent}% • ${"%.1f".format(st.downloadedMb)} / ${"%.1f".format(st.totalMb)} MB)",
                            style = MaterialTheme.typography.bodySmall,
                            color = GokuAmber
                        )
                        LinearProgressIndicator(
                            progress = { (st.progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            color = GokuAmber,
                            trackColor = GokuSurfaceElevated
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { downloadManager.resumeDownload() },
                                colors = ButtonDefaults.buttonColors(containerColor = GokuCyan),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("offline_model_resume_btn")
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = GokuObsidianBg)
                                Spacer(Modifier.width(6.dp))
                                Text("Resume (HTTP Range)", color = GokuObsidianBg, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { downloadManager.deleteModel() },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("offline_model_delete_btn")
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete Partial", tint = GokuCrimson)
                            }
                        }
                    }
                }

                is ModelDownloadState.Ready -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            color = GokuEmerald.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, GokuEmerald.copy(alpha = 0.55f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GokuEmerald)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${st.modelName} Ready",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = GokuTextPrimary
                                    )
                                    Text(
                                        text = "${"%.1f".format(st.sizeMb)} MB on disk • Auto-unloads from RAM after 15s idle",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = GokuEmerald
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { localModelPicker.launch(arrayOf("*/*")) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Replace File")
                            }
                            Button(
                                onClick = {
                                    downloadManager.deleteModel()
                                    Toast.makeText(ctx, "Offline AI model deleted & RAM freed", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GokuCrimson),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("offline_model_delete_btn")
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(4.dp))
                                Text("Delete Model", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                is ModelDownloadState.Error -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = st.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = GokuCrimson
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (st.canResume) {
                                        downloadManager.resumeDownload()
                                    } else if (useCustomUrl) {
                                        downloadManager.startDownload(customUrl, "Custom Offline LLM")
                                    } else {
                                        downloadManager.startDownload(selectedPreset.directUrl, selectedPreset.name)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GokuCyan),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("offline_model_retry_btn")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, tint = GokuObsidianBg)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (st.canResume) "Resume Download" else "Retry Download",
                                    color = GokuObsidianBg,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            OutlinedButton(
                                onClick = { downloadManager.deleteModel() },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Reset")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 9. DIALOGS & SHARED MICRO-COMPONENTS
// ============================================================================

@Composable
private fun LocalAdbPairDialog(
    adbStatus: AdbStatus,
    onDismiss: () -> Unit,
    onScanMdns: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onConnect5555: () -> Unit,
    onPair: (Int, String) -> Unit
) {
    var portStr by remember(adbStatus.discoveredPairingPort) {
        mutableStateOf(adbStatus.discoveredPairingPort?.toString() ?: "")
    }
    var codeStr by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GokuSurfaceDark,
        title = { Text("Direct Local ADB Pairing", color = GokuCyan) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(adbStatus.statusMessage, style = MaterialTheme.typography.bodySmall, color = GokuTextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onScanMdns, modifier = Modifier.weight(1f)) { Text("mDNS Scan") }
                    OutlinedButton(onClick = onOpenDevOptions, modifier = Modifier.weight(1f)) { Text("Dev Options") }
                }
                OutlinedTextField(
                    value = portStr,
                    onValueChange = { portStr = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Pairing Port (e.g. 37123)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = codeStr,
                    onValueChange = { codeStr = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text("6-Digit Wireless Pairing Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onConnect5555) {
                    Text("Already enabled tcpip 5555? Connect 127.0.0.1:5555")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onPair(portStr.toIntOrNull() ?: 0, codeStr)
            }) { Text("Pair & Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun CameraLiveEyeDialog(
    hasCameraPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onDismiss: () -> Unit,
    onTextExtracted: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var statusText by remember { mutableStateOf("Point camera at a medicine strip, bike part, or document...") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GokuSurfaceDark,
        title = { Text("Goku Camera Live-Eye (ML Kit OCR)", color = GokuAmber) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hasCameraPermission) {
                    Text("Camera permission is required for Live-Eye Vision.", color = GokuTextPrimary)
                    Button(onClick = onRequestPermissions) { Text("Grant Camera Permission") }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(14.dp))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    runCatching {
                                        val provider = cameraProviderFuture.get()
                                        val preview = Preview.Builder().build().also {
                                            it.surfaceProvider = previewView.surfaceProvider
                                        }
                                        val capture = ImageCapture.Builder().build()
                                        imageCapture = capture
                                        provider.unbindAll()
                                        provider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            capture
                                        )
                                    }.onFailure {
                                        statusText = "Camera preview unavailable: ${it.message}"
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Text(statusText, style = MaterialTheme.typography.bodySmall, color = GokuTextMuted)
                }
            }
        },
        confirmButton = {
            if (hasCameraPermission) {
                Button(onClick = {
                    val cap = imageCapture ?: return@Button
                    statusText = "Scanning with on-device ML Kit..."
                    cap.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            @androidx.camera.core.ExperimentalGetImage
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val mediaImage = image.image
                                if (mediaImage != null) {
                                    val inputImage = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
                                    CameraLiveEyeOcr.recognize(
                                        image = inputImage,
                                        onResult = { txt ->
                                            image.close()
                                            if (txt.isBlank()) {
                                                statusText = "No readable text found; try closer lighting."
                                            } else {
                                                onTextExtracted(txt)
                                            }
                                        },
                                        onError = { err ->
                                            image.close()
                                            statusText = "OCR error: ${err.message}"
                                        }
                                    )
                                } else {
                                    image.close()
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                statusText = "Capture failed: ${exception.message}"
                            }
                        }
                    )
                }) { Text("Scan & Explain") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun TelemetryPill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickChip(
    label: String,
    icon: ImageVector,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = true,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Icon(icon, contentDescription = label, modifier = Modifier.size(16.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = accent.copy(alpha = 0.14f),
            selectedLabelColor = accent,
            selectedLeadingIconColor = accent
        ),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    )
}

@Composable
private fun SetupRowCard(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    actionLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GokuSurfaceDark),
        border = BorderStroke(1.dp, if (isGranted) GokuEmerald.copy(alpha = 0.6f) else GokuOutline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (isGranted) GokuEmerald else GokuAmber,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = GokuTextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = GokuTextMuted)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) GokuSurfaceElevated else GokuCyan
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .testTag(testTag)
            ) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isGranted) GokuEmerald else GokuObsidianBg
                )
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = GokuTextPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = GokuObsidianBg, checkedTrackColor = GokuCyan)
        )
    }
}

private fun formatTime(ts: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
}
