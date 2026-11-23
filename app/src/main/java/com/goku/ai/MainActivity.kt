package com.goku.ai

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.goku.ai.adb.AdbPairingOverlayBus
import com.goku.ai.adb.LocalAdbEngine
import com.goku.ai.automation.EmergencySosManager
import com.goku.ai.automation.SelfCodingPluginEngine
import com.goku.ai.automation.SensorReflexManager
import com.goku.ai.body.GokuAccessibilityService
import com.goku.ai.body.MistakeBlockerBus
import com.goku.ai.body.TeachModeRecorder
import com.goku.ai.body.UniversalAppPilot
import com.goku.ai.brain.HybridBrainAndSwarm
import com.goku.ai.data.GokuVaultRepository
import com.goku.ai.data.LearnedSkillEntity
import com.goku.ai.services.FloatingOrbOverlayService
import com.goku.ai.services.GokuKeepAliveService
import com.goku.ai.services.GokuNotificationListenerService
import com.goku.ai.ui.GokuCyberTheme
import com.goku.ai.ui.GokuDashboardCallbacks
import com.goku.ai.ui.GokuDashboardScreen
import com.goku.ai.ui.GokuDashboardUiState
import com.goku.ai.ui.GokuSettingsSnapshot
import com.goku.ai.ui.PermissionWizardStatus
import com.goku.ai.voice.GokuEarAndVoiceEngine
import com.goku.ai.voice.GokuKillSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GokuViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context = application.applicationContext
    private val vault = GokuVaultRepository.getInstance(ctx)
    private val adbEngine = LocalAdbEngine.getInstance(ctx)
    private val voiceEngine = GokuEarAndVoiceEngine.getInstance(ctx)
    private val sensorManager = SensorReflexManager.getInstance(ctx)
    private val pilot = UniversalAppPilot(ctx, adbEngine)
    private val sosManager = EmergencySosManager(ctx, vault)
    private val pluginEngine = SelfCodingPluginEngine(ctx, vault, adbEngine, pilot, voiceEngine)
    private val brain = HybridBrainAndSwarm(ctx, vault, adbEngine, voiceEngine, pilot, sosManager, pluginEngine)

    private val _uiState = MutableStateFlow(GokuDashboardUiState(settings = loadSettingsSnapshot()))
    val uiState: StateFlow<GokuDashboardUiState> = _uiState.asStateFlow()

    init {
        // Wire Goku Voice Ear recognized commands directly to HybridBrainAndSwarm
        voiceEngine.setCommandCallback { spoken ->
            sendCommand(spoken, speakReply = true)
        }

        pluginEngine.scheduleNightlyDreamMode()
        sensorManager.start()
        refreshPermissionStatus()
        observeAllStreams()
    }

    private fun observeAllStreams() {
        viewModelScope.launch {
            brain.brainState.collect { st -> _uiState.update { it.copy(brainState = st) } }
        }
        viewModelScope.launch {
            brain.messages.collect { msgs -> _uiState.update { it.copy(messages = msgs) } }
        }
        viewModelScope.launch {
            brain.pendingConfirmation.collect { p -> _uiState.update { it.copy(pendingConfirmation = p) } }
        }
        viewModelScope.launch {
            brain.isOnline.collect { online -> _uiState.update { it.copy(isOnline = online) } }
        }
        viewModelScope.launch {
            brain.onlineRouter.activeProvider.collect { prov -> _uiState.update { it.copy(activeProvider = prov) } }
        }
        viewModelScope.launch {
            brain.offlineLlm.isLoadedInRam.collect { loaded -> _uiState.update { it.copy(isLocalModelLoaded = loaded) } }
        }
        viewModelScope.launch {
            voiceEngine.state.collect { vs -> _uiState.update { it.copy(voiceState = vs) } }
        }
        viewModelScope.launch {
            adbEngine.status.collect { st -> _uiState.update { it.copy(adbStatus = st) } }
        }
        viewModelScope.launch {
            sensorManager.state.collect { ss -> _uiState.update { it.copy(sensorState = ss) } }
        }
        viewModelScope.launch {
            GokuKillSwitch.isStopped.collect { stopped -> _uiState.update { it.copy(isKillSwitchStopped = stopped) } }
        }
        viewModelScope.launch {
            GokuKillSwitch.lastStopSource.collect { src -> _uiState.update { it.copy(lastStopSource = src) } }
        }
        viewModelScope.launch {
            MistakeBlockerBus.activeWarning.collect { warn -> _uiState.update { it.copy(mistakeWarning = warn) } }
        }
        viewModelScope.launch {
            TeachModeRecorder.isRecording.collect { rec -> _uiState.update { it.copy(isTeachRecording = rec) } }
        }
        viewModelScope.launch {
            TeachModeRecorder.recordedSteps.collect { steps -> _uiState.update { it.copy(teachSteps = steps) } }
        }
        viewModelScope.launch {
            vault.actionLogs.collect { logs -> _uiState.update { it.copy(actionLogs = logs) } }
        }
        viewModelScope.launch {
            vault.memories.collect { mems -> _uiState.update { it.copy(memories = mems) } }
        }
        viewModelScope.launch {
            vault.screenTimeline.collect { tl -> _uiState.update { it.copy(timeline = tl) } }
        }
        viewModelScope.launch {
            vault.skills.collect { sk -> _uiState.update { it.copy(skills = sk) } }
        }
        viewModelScope.launch {
            vault.expenses.collect { ex -> _uiState.update { it.copy(expenses = ex) } }
        }
        viewModelScope.launch {
            GokuAccessibilityService.isConnected.collect { refreshPermissionStatus() }
        }
        viewModelScope.launch {
            GokuNotificationListenerService.isConnected.collect { refreshPermissionStatus() }
        }
        viewModelScope.launch {
            GokuKeepAliveService.isRunning.collect { refreshPermissionStatus() }
        }
        viewModelScope.launch {
            FloatingOrbOverlayService.isOverlayActive.collect { refreshPermissionStatus() }
        }
    }

    fun refreshPermissionStatus() {
        brain.isNetworkAvailable()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryExempt = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
        val overlayOk = Settings.canDrawOverlays(ctx)
        val hasMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasCam = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val enabledListeners = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        val notifConnected = GokuNotificationListenerService.isConnected.value ||
            enabledListeners.contains(ctx.packageName)

        _uiState.update {
            it.copy(
                permissionStatus = PermissionWizardStatus(
                    runtimePermissionsGranted = hasMic && hasCam,
                    accessibilityEnabled = GokuAccessibilityService.isConnected.value,
                    overlayEnabled = overlayOk,
                    notificationListenerEnabled = notifConnected,
                    batteryOptimizationIgnored = batteryExempt,
                    keepAliveRunning = GokuKeepAliveService.isRunning.value,
                    floatingOrbRunning = FloatingOrbOverlayService.isOverlayActive.value
                )
            )
        }
    }

    fun sendCommand(text: String, speakReply: Boolean = true) {
        val job = viewModelScope.launch {
            brain.processCommand(text, speakReply = speakReply)
        }
        GokuKillSwitch.registerJob(job)
    }

    fun toggleListening() {
        if (_uiState.value.voiceState.isListening) {
            voiceEngine.stopListening()
        } else {
            voiceEngine.startListening(continuous = true, openDirectWindow = true)
        }
    }

    fun triggerEmergencyStop() {
        GokuKillSwitch.triggerStop("Dashboard STOP Button")
        voiceEngine.speak("Ruk gaya boss, sab stop kar diya.", urgent = true)
    }

    fun confirmPending(approved: Boolean) {
        viewModelScope.launch { brain.confirmPendingAction(approved) }
    }

    fun dismissMistakeWarning() = MistakeBlockerBus.dismiss()

    fun triggerSos() = sendCommand("Emergency SOS", speakReply = true)
    fun runDreamMode() = sendCommand("Dream mode", speakReply = true)
    fun runPhoneDoctor() = sendCommand("Phone doctor", speakReply = true)

    fun startAdbPairingOverlay() {
        adbEngine.startMdnsDiscovery()
        if (Settings.canDrawOverlays(ctx)) {
            toggleFloatingOrb(true)
            AdbPairingOverlayBus.requestOverlay()
        }
        adbEngine.openWirelessDebuggingSettings()
    }

    fun scanAdbMdns() = adbEngine.startMdnsDiscovery()

    fun connectAdbLoopback() {
        viewModelScope.launch {
            val ok = adbEngine.connectLoopback(5555)
            vault.logAction("SYSTEM", "ADB Connect 5555", if (ok) "Connected" else "Fallback to Accessibility", 95)
        }
    }

    fun pairAdbDirect(port: Int, code: String) {
        viewModelScope.launch {
            val res = adbEngine.pairAndEnableTcpIp(port, code)
            vault.logAction("SYSTEM", "ADB Pair $port", res.output, if (res.success) 98 else 70, if (res.success) "OK" else "WARN", res.latencyMs)
        }
    }

    fun toggleTeachMode(start: Boolean, skillName: String, trigger: String) {
        if (start) {
            TeachModeRecorder.startRecording(skillName, trigger)
            voiceEngine.speak("Teach mode shuru ho gaya. Jo steps sikhana hai un par tap karein.")
        } else {
            val (name, trig, stepsJson) = TeachModeRecorder.stopAndExport()
            viewModelScope.launch {
                vault.upsertSkill(
                    LearnedSkillEntity(
                        name = name,
                        triggerPhrase = trig,
                        stepsJson = stepsJson
                    )
                )
                vault.logAction("SYSTEM", "Teach Mode Saved", "Skill '$name' trigger '$trig'", 98)
                voiceEngine.speak("Skill $name save ho gaya boss!")
            }
        }
    }

    fun toggleFloatingOrb(enable: Boolean) {
        val intent = Intent(ctx, FloatingOrbOverlayService::class.java)
        if (enable) {
            if (Settings.canDrawOverlays(ctx)) {
                runCatching { ContextCompat.startForegroundService(ctx, intent) }
            }
        } else {
            runCatching { ctx.stopService(intent) }
        }
        refreshPermissionStatus()
    }

    fun runLivePlugin(json: String, dryRun: Boolean) {
        viewModelScope.launch {
            val out = pluginEngine.runLivePlugin(json, dryRun)
            _uiState.update { it.copy(pluginOutput = out) }
        }
    }

    fun addMemory(key: String, value: String, category: String) {
        viewModelScope.launch { vault.saveMemory(key, value, category) }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch { vault.deleteMemory(id) }
    }

    fun deleteSkill(id: Long) {
        viewModelScope.launch { vault.deleteSkill(id) }
    }

    fun exportSkills() {
        viewModelScope.launch {
            val json = vault.exportAllSkillsToGokuJson()
            _uiState.update { it.copy(exportedSkillsJson = json) }
        }
    }

    fun dismissExportedJson() {
        _uiState.update { it.copy(exportedSkillsJson = null) }
    }

    fun importSkillsJson(json: String) {
        viewModelScope.launch {
            val count = runCatching { vault.importSkillsFromGokuJson(json) }.getOrDefault(0)
            vault.logAction("SYSTEM", "Import .goku", "Imported $count skills", 98)
        }
    }

    fun handleLiveEyeOcr(extractedText: String) {
        sendCommand("Camera Live-Eye ne yeh text dekha hai, isey Hinglish mein samjhao: ${extractedText.take(400)}", speakReply = true)
    }

    fun updateSettings(updated: GokuSettingsSnapshot) {
        val s = vault.settings
        s.geminiApiKey = updated.geminiKey
        s.groqApiKey = updated.groqKey
        s.deepSeekApiKey = updated.deepSeekKey
        s.openRouterApiKey = updated.openRouterKey
        s.sosContactsCsv = updated.sosContacts
        s.githubRepo = updated.githubRepo
        s.githubToken = updated.githubToken
        s.telegramBotToken = updated.telegramBot
        s.telegramChatId = updated.telegramChatId
        s.smartCallPolicy = updated.smartCallPolicy
        s.localModelPath = updated.localModelPath
        s.dryRunMode = updated.dryRunMode
        s.autoAdSkipEnabled = updated.autoAdSkip
        s.otpAutopilotEnabled = updated.otpAutopilot
        s.mistakeBlockerEnabled = updated.mistakeBlocker
        s.workFocusMode = updated.workFocusMode
        s.smartCallEnabled = updated.smartCallEnabled
        _uiState.update { it.copy(settings = updated) }
    }

    private fun loadSettingsSnapshot(): GokuSettingsSnapshot {
        val s = vault.settings
        return GokuSettingsSnapshot(
            geminiKey = s.geminiApiKey,
            groqKey = s.groqApiKey,
            deepSeekKey = s.deepSeekApiKey,
            openRouterKey = s.openRouterApiKey,
            sosContacts = s.sosContactsCsv,
            githubRepo = s.githubRepo,
            githubToken = s.githubToken,
            telegramBot = s.telegramBotToken,
            telegramChatId = s.telegramChatId,
            smartCallPolicy = s.smartCallPolicy,
            localModelPath = s.localModelPath,
            dryRunMode = s.dryRunMode,
            autoAdSkip = s.autoAdSkipEnabled,
            otpAutopilot = s.otpAutopilotEnabled,
            mistakeBlocker = s.mistakeBlockerEnabled,
            workFocusMode = s.workFocusMode,
            smartCallEnabled = s.smartCallEnabled
        )
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: GokuViewModel by viewModels()

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissionStatus()
        GokuKeepAliveService.startSafe(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        GokuKeepAliveService.createNotificationChannels(this)
        GokuKeepAliveService.startSafe(this)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            GokuCyberTheme {
                GokuDashboardScreen(
                    uiState = uiState,
                    callbacks = GokuDashboardCallbacks(
                        onSendCommand = { viewModel.sendCommand(it) },
                        onToggleListening = {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                requestAllRuntimePermissions()
                            } else {
                                viewModel.toggleListening()
                            }
                        },
                        onEmergencyStop = { viewModel.triggerEmergencyStop() },
                        onConfirmAction = { viewModel.confirmPending(it) },
                        onDismissMistakeWarning = { viewModel.dismissMistakeWarning() },
                        onTriggerSos = { viewModel.triggerSos() },
                        onRunDreamMode = { viewModel.runDreamMode() },
                        onRunPhoneDoctor = { viewModel.runPhoneDoctor() },
                        onStartAdbPairing = { viewModel.startAdbPairingOverlay() },
                        onScanAdbMdns = { viewModel.scanAdbMdns() },
                        onConnectAdbLoopback = { viewModel.connectAdbLoopback() },
                        onPairAdbDirect = { port, code -> viewModel.pairAdbDirect(port, code) },
                        onToggleTeachMode = { start, name, trig -> viewModel.toggleTeachMode(start, name, trig) },
                        onRequestRuntimePermissions = { requestAllRuntimePermissions() },
                        onOpenAccessibilitySettings = {
                            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        },
                        onOpenOverlaySettings = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            runCatching { startActivity(intent) }
                        },
                        onOpenNotificationListenerSettings = {
                            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                        },
                        onRequestBatteryExemption = {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName")
                            )
                            runCatching { startActivity(intent) }
                        },
                        onToggleFloatingOrb = { viewModel.toggleFloatingOrb(it) },
                        onUpdateSettings = { viewModel.updateSettings(it) },
                        onRunLivePlugin = { json, dry -> viewModel.runLivePlugin(json, dry) },
                        onAddMemory = { k, v, c -> viewModel.addMemory(k, v, c) },
                        onDeleteMemory = { viewModel.deleteMemory(it) },
                        onDeleteSkill = { viewModel.deleteSkill(it) },
                        onExportSkills = { viewModel.exportSkills() },
                        onDismissExportedJson = { viewModel.dismissExportedJson() },
                        onImportSkillsJson = { viewModel.importSkillsJson(it) },
                        onLiveEyeOcrText = { viewModel.handleLiveEyeOcr(it) }
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionStatus()
    }

    private fun requestAllRuntimePermissions() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        runtimePermissionsLauncher.launch(perms)
    }
}
