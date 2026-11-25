package com.goku.ai

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
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
import com.goku.ai.automation.AntiTheftIntruderGuard
import com.goku.ai.automation.CameraLiveEyeManager
import com.goku.ai.automation.GokuDeviceAdminReceiver
import com.goku.ai.automation.SelfCodingPluginEngine
import com.goku.ai.automation.SensorReflexManager
import com.goku.ai.body.GokuAccessibilityService
import com.goku.ai.body.MistakeBlockerBus
import com.goku.ai.body.TeachModeRecorder
import com.goku.ai.brain.HybridBrainAndSwarm
import com.goku.ai.brain.OfflineModelDownloadManager
import com.goku.ai.data.GokuVaultRepository
import com.goku.ai.data.LearnedSkillEntity
import com.goku.ai.services.FloatingOrbOverlayService
import com.goku.ai.services.GokuHeadlessCommandBus
import com.goku.ai.services.GokuKeepAliveService
import com.goku.ai.services.GokuNotificationListenerService
import com.goku.ai.services.PostBootMicUnlockBus
import com.goku.ai.ui.GokuCyberTheme
import com.goku.ai.ui.GokuDashboardCallbacks
import com.goku.ai.ui.GokuDashboardScreen
import com.goku.ai.ui.GokuDashboardUiState
import com.goku.ai.ui.GokuSettingsSnapshot
import com.goku.ai.ui.PermissionWizardStatus
import com.goku.ai.voice.GokuEarAndVoiceEngine
import com.goku.ai.voice.GokuKillSwitch
import com.goku.ai.voice.SherpaOnnxKwsBridge
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verifies that EVERY critical runtime permission required by Goku V10.1
 * (Voice Ear, Camera Live-Eye, Smart Call Screener + Caller ID, Offline SMS SOS, GPS, Notifications)
 * is actually granted before the Setup Wizard marks Runtime Permissions green.
 */
fun hasAllCriticalRuntimePermissions(context: Context): Boolean {
    val required = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return required.all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Checks whether Goku's Anti-Theft Intruder Guard Device Admin is currently active.
 */
fun isDeviceAdminActive(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
    val adminComponent = ComponentName(context.applicationContext, GokuDeviceAdminReceiver::class.java)
    return runCatching { dpm.isAdminActive(adminComponent) }.getOrDefault(false)
}

/**
 * Launches Android's system Device Admin activation dialog for Goku's 3-Wrong-PIN Intruder Guard.
 */
fun requestDeviceAdminActivation(activity: Activity) {
    val adminComponent = ComponentName(activity.applicationContext, GokuDeviceAdminReceiver::class.java)
    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
        putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Enable Goku V10.1 Anti-Theft Intruder Guard so 3 failed lock-screen PIN/pattern attempts trigger a silent front-camera trap + GPS SOS alert."
        )
    }
    runCatching { activity.startActivity(intent) }
}

/**
 * Pre-creates `filesDir/models/kws/` with a README & `keywords.txt` template for optional
 * custom Sherpa-ONNX `.onnx` wake-word models while keeping the built-in 24x7 Acoustic VAD +
 * On-Device SpeechRecognizer engine immediately active out-of-the-box.
 */
fun prepareKwsDirectory(context: Context): File {
    val kwsDir = File(context.applicationContext.filesDir, "models/kws")
    runCatching {
        if (!kwsDir.exists()) kwsDir.mkdirs()

        val readme = File(kwsDir, "README.txt")
        if (!readme.exists()) {
            readme.writeText(
                """
                Goku V10.1 (10.1-godmode) — 24x7 Offline Wake-Word Model Directory
                ===================================================================
                Default active backend: Built-in 24x7 Acoustic VAD + On-Device SpeechRecognizer.
                Optional Sherpa-ONNX upgrade:
                1. Place sherpa-onnx-android.aar in app/libs/ (for custom builds)
                2. Copy encoder*.onnx, decoder*.onnx, joiner*.onnx, and tokens.txt here
                3. Goku automatically detects and activates Sherpa-ONNX KeywordSpotter on launch.
                """.trimIndent() + "\n"
            )
        }

        val keywords = File(kwsDir, "keywords.txt")
        if (!keywords.exists()) {
            keywords.writeText(
                """
                ▁HEY ▁GO KU @hey goku
                ▁GO KU ▁STOP @goku stop
                ▁GO KU ▁RU KO @goku ruko
                """.trimIndent() + "\n"
            )
        }

        // Probe optional Sherpa-ONNX bridge safely without blocking or crashing if absent
        SherpaOnnxKwsBridge(context.applicationContext).initialize()
    }
    return kwsDir
}

class GokuViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context = application.applicationContext
    private val runtime = GokuHeadlessCommandBus.ensureInitialized(ctx)
    private val vault: GokuVaultRepository = runtime.vault
    private val adbEngine: LocalAdbEngine = runtime.adbEngine
    private val voiceEngine: GokuEarAndVoiceEngine = runtime.voiceEngine
    private val sensorManager = SensorReflexManager.getInstance(ctx)
    private val intruderGuard = AntiTheftIntruderGuard.getInstance(ctx)
    private val pluginEngine: SelfCodingPluginEngine = runtime.pluginEngine
    private val brain: HybridBrainAndSwarm = runtime.brain
    private val modelDownloadManager: OfflineModelDownloadManager = OfflineModelDownloadManager.getInstance(ctx)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val settingsIoDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val hasUserEditedSettings = AtomicBoolean(false)
    private var refreshPermissionsJob: Job? = null

    @Volatile
    var pendingVoiceStartAfterPermission: Boolean = false

    private val _uiState = MutableStateFlow(GokuDashboardUiState())
    val uiState: StateFlow<GokuDashboardUiState> = _uiState.asStateFlow()

    init {
        // Ensure process-wide headless command bus owns the Voice Ear callback so voice/orb commands
        // keep executing seamlessly even after MainActivity / GokuViewModel is closed.
        GokuHeadlessCommandBus.ensureVoiceCallbackWired(ctx)

        viewModelScope.launch(settingsIoDispatcher) {
            prepareKwsDirectory(ctx)
            if (!hasUserEditedSettings.get()) {
                val snapshot = loadSettingsSnapshot()
                _uiState.update { state ->
                    if (!hasUserEditedSettings.get()) state.copy(settings = snapshot) else state
                }
            }
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
            modelDownloadManager.state.collect { dl -> _uiState.update { it.copy(modelDownloadState = dl) } }
        }
        viewModelScope.launch {
            voiceEngine.state.collect { vs -> _uiState.update { it.copy(voiceState = vs) } }
        }
        viewModelScope.launch {
            adbEngine.status.collect { st -> _uiState.update { it.copy(adbStatus = st) } }
        }
        viewModelScope.launch {
            adbEngine.virtualDisplayRunner.isActive.collect { active ->
                _uiState.update { it.copy(isVirtualDisplayActive = active) }
            }
        }
        viewModelScope.launch {
            adbEngine.virtualDisplayRunner.virtualDisplayId.collect { displayId ->
                _uiState.update { it.copy(activeVirtualDisplayId = displayId) }
            }
        }
        viewModelScope.launch {
            intruderGuard.state.collect { guardState ->
                _uiState.update {
                    it.copy(
                        permissionStatus = it.permissionStatus.copy(
                            deviceAdminEnabled = guardState.isDeviceAdminActive
                        )
                    )
                }
            }
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
            @OptIn(FlowPreview::class)
            combine(
                GokuAccessibilityService.isConnected,
                GokuNotificationListenerService.isConnected,
                GokuKeepAliveService.isRunning,
                FloatingOrbOverlayService.isOverlayActive
            ) { a11y, notif, keepAlive, orb -> listOf(a11y, notif, keepAlive, orb) }
                .debounce(120L)
                .collect { refreshPermissionStatus() }
        }
    }

    fun refreshPermissionStatus() {
        refreshPermissionsJob?.cancel()
        refreshPermissionsJob = viewModelScope.launch(Dispatchers.IO) {
            brain.isNetworkAvailable()
            intruderGuard.refreshAdminStatus()
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val batteryExempt = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
            val overlayOk = Settings.canDrawOverlays(ctx)
            val allRuntimeOk = hasAllCriticalRuntimePermissions(ctx)
            val enabledListeners = runCatching {
                Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            }.getOrDefault("")
            val notifConnected = GokuNotificationListenerService.isConnected.value ||
                enabledListeners.contains(ctx.packageName)
            val deviceAdminOk = isDeviceAdminActive(ctx)
            val topHabit = runCatching {
                vault.predictHabitsForCurrentHour().firstOrNull()?.first
            }.getOrNull()

            _uiState.update {
                it.copy(
                    permissionStatus = PermissionWizardStatus(
                        runtimePermissionsGranted = allRuntimeOk,
                        accessibilityEnabled = GokuAccessibilityService.isConnected.value,
                        overlayEnabled = overlayOk,
                        notificationListenerEnabled = notifConnected,
                        batteryOptimizationIgnored = batteryExempt,
                        keepAliveRunning = GokuKeepAliveService.isRunning.value,
                        floatingOrbRunning = FloatingOrbOverlayService.isOverlayActive.value,
                        deviceAdminEnabled = deviceAdminOk
                    ),
                    isVirtualDisplayActive = adbEngine.virtualDisplayRunner.isActive.value,
                    activeVirtualDisplayId = adbEngine.virtualDisplayRunner.virtualDisplayId.value,
                    suggestedHabit = topHabit
                )
            }
        }
    }

    fun sendCommand(text: String, speakReply: Boolean = true) {
        GokuHeadlessCommandBus.dispatchVoiceCommand(ctx, text, speakReply = speakReply)
    }

    fun toggleListening() {
        val vs = _uiState.value.voiceState
        if (vs.isListening || vs.isAcousticEarArmed || vs.isSpeechRecognizerActive) {
            voiceEngine.stopListening()
        } else {
            GokuHeadlessCommandBus.startVoiceListeningIfPermitted(ctx, openDirectWindow = true)
        }
    }

    fun startListeningIfPermitted() {
        val vs = _uiState.value.voiceState
        if (!vs.isListening && !vs.isAcousticEarArmed && !vs.isSpeechRecognizerActive) {
            GokuHeadlessCommandBus.startVoiceListeningIfPermitted(ctx, openDirectWindow = true)
        }
    }

    fun unlockPostBootMicAndListen() {
        PostBootMicUnlockBus.setMicNeedsUnlock(false)
        GokuHeadlessCommandBus.ensureVoiceCallbackWired(ctx)
        voiceEngine.unlockPostBootMicAndListen()
    }

    fun onPermissionsDeniedFeedback(detail: String) {
        viewModelScope.launch(Dispatchers.IO) {
            vault.logAction("SYSTEM", "Permissions Denied", detail, 75, "WARN")
        }
    }

    fun startOfflineModelDownload(url: String, modelName: String) {
        modelDownloadManager.startDownload(url, modelName)
    }

    fun pauseOfflineModelDownload() {
        modelDownloadManager.pauseDownload()
    }

    fun resumeOfflineModelDownload() {
        modelDownloadManager.resumeDownload()
    }

    fun deleteOfflineModel() {
        modelDownloadManager.deleteModel()
    }

    fun importLocalOfflineModel(uri: Uri) {
        modelDownloadManager.importLocalModel(uri)
    }

    fun toggleVirtualDisplay(enable: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enable) {
                val id = adbEngine.virtualDisplayRunner.ensureVirtualDisplay()
                vault.logAction(
                    "SYSTEM",
                    "VirtualDisplay ON",
                    if (id != null) "GokuVirtualScreen active (Display #$id)" else "Failed to allocate VirtualDisplay",
                    if (id != null) 98 else 50,
                    if (id != null) "OK" else "WARN"
                )
            } else {
                adbEngine.virtualDisplayRunner.releaseVirtualDisplay()
                vault.logAction("SYSTEM", "VirtualDisplay OFF", "GokuVirtualScreen released", 100, "OK")
            }
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
            val cleanName = skillName.trim()
            val cleanTrigger = trigger.trim()
            if (cleanName.isBlank() || cleanTrigger.isBlank()) {
                voiceEngine.speak("Skill name aur voice trigger dono likhein pehle.")
                return
            }
            TeachModeRecorder.startRecording(cleanName, cleanTrigger)
            voiceEngine.speak("Teach mode shuru ho gaya. Jo steps sikhana hai un par tap karein.")
        } else {
            val (name, trig, stepsJson) = TeachModeRecorder.stopAndExport()
            val cleanName = name.trim()
            val cleanTrig = trig.trim()
            val hasSteps = runCatching { JSONArray(stepsJson).length() > 0 }.getOrDefault(false)
            if (cleanName.isBlank() || cleanTrig.isBlank() || !hasSteps) {
                viewModelScope.launch {
                    vault.logAction(
                        "SYSTEM",
                        "Teach Mode Discarded",
                        "Skipped saving skill (missing name/trigger or 0 steps recorded)",
                        85,
                        "WARN"
                    )
                }
                voiceEngine.speak("Koi steps record nahi hue ya naam khaali tha, isliye skill save nahi kiya.")
                return
            }
            viewModelScope.launch {
                vault.upsertSkill(
                    LearnedSkillEntity(
                        name = cleanName,
                        triggerPhrase = cleanTrig,
                        stepsJson = stepsJson
                    )
                )
                vault.logAction("SYSTEM", "Teach Mode Saved", "Skill '$cleanName' trigger '$cleanTrig'", 98)
                voiceEngine.speak("Skill $cleanName save ho gaya boss!")
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
        val trimmed = extractedText.trim()
        if (trimmed.startsWith("[VISION-READY]")) {
            sendCommand(trimmed, speakReply = true)
        } else {
            sendCommand("Camera Live-Eye ne yeh text dekha hai, isey Hinglish mein samjhao: ${trimmed.take(400)}", speakReply = true)
        }
    }

    fun updateSettings(updated: GokuSettingsSnapshot) {
        hasUserEditedSettings.set(true)
        _uiState.update { it.copy(settings = updated) }
        viewModelScope.launch(settingsIoDispatcher) {
            vault.settings.batchUpdate(
                geminiKey = updated.geminiKey,
                groqKey = updated.groqKey,
                deepSeekKey = updated.deepSeekKey,
                openRouterKey = updated.openRouterKey,
                nvidiaNimKey = updated.nvidiaNimKey,
                sosContacts = updated.sosContacts,
                githubRepo = updated.githubRepo,
                githubToken = updated.githubToken,
                telegramBot = updated.telegramBot,
                telegramChatId = updated.telegramChatId,
                smartCallPolicy = updated.smartCallPolicy,
                smartCallEnabled = updated.smartCallEnabled,
                dryRunMode = updated.dryRunMode,
                autoAdSkip = updated.autoAdSkip,
                otpAutopilot = updated.otpAutopilot,
                mistakeBlocker = updated.mistakeBlocker,
                workFocusMode = updated.workFocusMode,
                localModelPath = updated.localModelPath
            )
        }
    }

    private fun loadSettingsSnapshot(): GokuSettingsSnapshot {
        val s = vault.settings
        return GokuSettingsSnapshot(
            geminiKey = s.geminiApiKey,
            groqKey = s.groqApiKey,
            deepSeekKey = s.deepSeekApiKey,
            openRouterKey = s.openRouterApiKey,
            nvidiaNimKey = s.nvidiaNimApiKey,
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
    ) { permissionsMap ->
        viewModel.refreshPermissionStatus()
        val micGranted = permissionsMap[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val anyGranted = permissionsMap.values.any { it }
        val deniedList = permissionsMap.filterValues { !it }.keys

        if (micGranted || anyGranted) {
            GokuKeepAliveService.startSafe(this, isFromForeground = true)
        }
        val shouldStartVoice = viewModel.pendingVoiceStartAfterPermission
        viewModel.pendingVoiceStartAfterPermission = false
        if (micGranted && shouldStartVoice) {
            viewModel.startListeningIfPermitted()
        }
        if (deniedList.isNotEmpty()) {
            val shortNames = deniedList.map { it.substringAfterLast('.') }.take(4).joinToString(", ")
            val suffix = if (deniedList.size > 4) " (+${deniedList.size - 4} more)" else ""
            viewModel.onPermissionsDeniedFeedback("Denied: $shortNames$suffix")
            Toast.makeText(
                this,
                "Permissions denied ($shortNames$suffix). Features requiring them remain inactive.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState?.getBoolean(KEY_PENDING_VOICE_START, false) == true) {
            viewModel.pendingVoiceStartAfterPermission = true
        }

        CameraLiveEyeManager.attachContext(applicationContext)
        GokuKeepAliveService.createNotificationChannels(this)

        val handledMicUnlock = handleUnlockMicAndMinimizeIntent(intent)
        if (!handledMicUnlock) {
            GokuKeepAliveService.startSafe(this, isFromForeground = true)
        }

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
                                viewModel.pendingVoiceStartAfterPermission = true
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
                        onRequestDeviceAdmin = { requestDeviceAdminActivation(this) },
                        onToggleVirtualDisplay = { viewModel.toggleVirtualDisplay(it) },
                        onToggleFloatingOrb = { viewModel.toggleFloatingOrb(it) },
                        onUpdateSettings = { viewModel.updateSettings(it) },
                        onRunLivePlugin = { json, dry -> viewModel.runLivePlugin(json, dry) },
                        onAddMemory = { k, v, c -> viewModel.addMemory(k, v, c) },
                        onDeleteMemory = { viewModel.deleteMemory(it) },
                        onDeleteSkill = { viewModel.deleteSkill(it) },
                        onExportSkills = { viewModel.exportSkills() },
                        onDismissExportedJson = { viewModel.dismissExportedJson() },
                        onImportSkillsJson = { viewModel.importSkillsJson(it) },
                        onLiveEyeOcrText = { viewModel.handleLiveEyeOcr(it) },
                        onStartModelDownload = { url, name -> viewModel.startOfflineModelDownload(url, name) },
                        onPauseModelDownload = { viewModel.pauseOfflineModelDownload() },
                        onResumeModelDownload = { viewModel.resumeOfflineModelDownload() },
                        onDeleteOfflineModel = { viewModel.deleteOfflineModel() },
                        onImportLocalModelUri = { uri -> viewModel.importLocalOfflineModel(uri) }
                    )
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUnlockMicAndMinimizeIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PENDING_VOICE_START, viewModel.pendingVoiceStartAfterPermission)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionStatus()
    }

    private fun handleUnlockMicAndMinimizeIntent(launchIntent: Intent?): Boolean {
        if (launchIntent?.getBooleanExtra(EXTRA_UNLOCK_MIC_AND_MINIMIZE, false) != true) {
            return false
        }
        // Consume extra immediately so configuration changes do not re-minimize the activity
        launchIntent.removeExtra(EXTRA_UNLOCK_MIC_AND_MINIMIZE)

        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            viewModel.pendingVoiceStartAfterPermission = true
            requestAllRuntimePermissions()
            return false
        }

        PostBootMicUnlockBus.setMicNeedsUnlock(false)
        // Promote KeepAlive service to MICROPHONE FGS while MainActivity holds foreground eligibility
        val unlockServiceIntent = Intent(this, GokuKeepAliveService::class.java).apply {
            action = GokuKeepAliveService.ACTION_UNLOCK_MIC
            putExtra(GokuKeepAliveService.EXTRA_START_VOICE_IN_SERVICE, false)
        }
        runCatching { ContextCompat.startForegroundService(this, unlockServiceIntent) }
            .onFailure { GokuKeepAliveService.startSafe(this, isFromForeground = true) }

        viewModel.unlockPostBootMicAndListen()
        moveTaskToBack(true)
        return true
    }

    private fun requestAllRuntimePermissions() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        runtimePermissionsLauncher.launch(perms)
    }

    companion object {
        const val EXTRA_UNLOCK_MIC_AND_MINIMIZE = "com.goku.ai.extra.UNLOCK_MIC_AND_MINIMIZE"
        private const val KEY_PENDING_VOICE_START = "goku_pending_voice_start_after_perm"
    }
}
