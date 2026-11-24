package com.goku.ai.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.CallLog
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.R
import com.goku.ai.adb.AdbPairingOverlayBus
import com.goku.ai.adb.LocalAdbEngine
import com.goku.ai.automation.EmergencySosManager
import com.goku.ai.automation.SelfCodingPluginEngine
import com.goku.ai.automation.SensorReflexManager
import com.goku.ai.body.OtpBus
import com.goku.ai.body.UniversalAppPilot
import com.goku.ai.brain.HybridBrainAndSwarm
import com.goku.ai.data.GokuVaultRepository
import com.goku.ai.voice.GokuEarAndVoiceEngine
import com.goku.ai.voice.GokuKillSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

// ============================================================================
// 0. APPLICATION-SCOPED HEADLESS COMMAND BUS (SURVIVES ACTIVITY CLOSE & REBOOT)
// ============================================================================

data class GokuRuntimeComponents(
    val vault: GokuVaultRepository,
    val adbEngine: LocalAdbEngine,
    val voiceEngine: GokuEarAndVoiceEngine,
    val pilot: UniversalAppPilot,
    val sosManager: EmergencySosManager,
    val pluginEngine: SelfCodingPluginEngine,
    val brain: HybridBrainAndSwarm
)

/**
 * Process-wide singleton pipeline shared by GokuKeepAliveService, FloatingOrbOverlayService,
 * GokuEarAndVoiceEngine, and GokuViewModel so voice & orb commands execute, speak replies,
 * and log into Room Vault even when MainActivity is swiped away or after device reboot.
 */
object GokuHeadlessCommandBus {
    private val busScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var components: GokuRuntimeComponents? = null

    fun ensureInitialized(context: Context): GokuRuntimeComponents {
        components?.let { return it }
        return synchronized(this) {
            components ?: run {
                val appCtx = context.applicationContext
                val vault = GokuVaultRepository.getInstance(appCtx)
                val adbEngine = LocalAdbEngine.getInstance(appCtx)
                val voiceEngine = GokuEarAndVoiceEngine.getInstance(appCtx)
                val pilot = UniversalAppPilot(appCtx, adbEngine)
                val sosManager = EmergencySosManager(appCtx, vault)
                val pluginEngine = SelfCodingPluginEngine(appCtx, vault, adbEngine, pilot, voiceEngine)
                val brain = HybridBrainAndSwarm(
                    context = appCtx,
                    vault = vault,
                    adbEngine = adbEngine,
                    voiceEngine = voiceEngine,
                    pilot = pilot,
                    sosManager = sosManager,
                    pluginEngine = pluginEngine
                )
                GokuRuntimeComponents(
                    vault = vault,
                    adbEngine = adbEngine,
                    voiceEngine = voiceEngine,
                    pilot = pilot,
                    sosManager = sosManager,
                    pluginEngine = pluginEngine,
                    brain = brain
                ).also { components = it }
            }
        }
    }

    fun ensureVoiceCallbackWired(context: Context): GokuRuntimeComponents {
        val appCtx = context.applicationContext
        val runtime = ensureInitialized(appCtx)
        runtime.voiceEngine.setCommandCallback { spokenText ->
            dispatchVoiceCommand(appCtx, spokenText, speakReply = true)
        }
        return runtime
    }

    fun dispatchVoiceCommand(
        context: Context,
        spokenText: String,
        speakReply: Boolean = true
    ): Job {
        val appCtx = context.applicationContext
        val runtime = ensureVoiceCallbackWired(appCtx)
        val job = busScope.launch {
            runCatching {
                runtime.brain.processCommand(spokenText, speakReply = speakReply)
            }.onFailure { err ->
                runCatching {
                    runtime.vault.logAction(
                        stage = "SYSTEM",
                        command = "HeadlessCommand",
                        detail = "Headless dispatch error: ${err.message ?: err.javaClass.simpleName}",
                        confidence = 40,
                        status = "ERROR"
                    )
                }
            }
        }
        GokuKillSwitch.registerJob(job)
        return job
    }

    suspend fun generateCallNegotiationReply(
        context: Context,
        callerNumber: String,
        policy: String,
        history: String,
        callerSaid: String
    ): Pair<String, Boolean> {
        val runtime = ensureInitialized(context.applicationContext)
        return runtime.brain.generateCallNegotiationReply(
            callerNumber = callerNumber,
            policy = policy,
            conversationHistory = history,
            callerSaid = callerSaid
        )
    }

    fun startVoiceListeningIfPermitted(
        context: Context,
        openDirectWindow: Boolean = false
    ): Boolean {
        val appCtx = context.applicationContext
        val hasMic = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) return false
        val runtime = ensureVoiceCallbackWired(appCtx)
        return runCatching {
            runtime.voiceEngine.startListening(
                continuous = true,
                openDirectWindow = openDirectWindow
            )
            true
        }.getOrDefault(false)
    }
}

/**
 * Coordinates Android 14/15 post-boot background microphone silence detection between
 * GokuEarAndVoiceEngine, GokuKeepAliveService notification, and FloatingOrbOverlayService badge.
 */
object PostBootMicUnlockBus {
    private val _micNeedsUnlock = MutableStateFlow(false)
    val micNeedsUnlock: StateFlow<Boolean> = _micNeedsUnlock.asStateFlow()

    fun setMicNeedsUnlock(needed: Boolean) {
        _micNeedsUnlock.value = needed
    }
}

// ============================================================================
// 1. ANDROID 14/15 KEEP-ALIVE FOREGROUND SERVICE + WATCHDOG + BOOT RECEIVER
// ============================================================================

class GokuKeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
        GokuHeadlessCommandBus.ensureVoiceCallbackWired(applicationContext)
        SensorReflexManager.getInstance(this).start()
        scheduleWatchdog(this)
        observePostBootMicSilence()
    }

    private fun observePostBootMicSilence() {
        scope.launch {
            GokuEarAndVoiceEngine.getInstance(applicationContext).state
                .map { it.isPostBootMicSilenced }
                .distinctUntilChanged()
                .collectLatest { silenced ->
                    PostBootMicUnlockBus.setMicNeedsUnlock(silenced)
                    val nm = getSystemService(NotificationManager::class.java)
                    if (silenced) {
                        val unlockNotif = buildKeepAliveNotification(
                            context = applicationContext,
                            subtitle = "Tap Goku Orb Once to Unlock 24x7 Mic (Android 14/15 Post-Boot Guard)",
                            showUnlockAction = true
                        )
                        runCatching { nm?.notify(NOTIF_ID_KEEP_ALIVE, unlockNotif) }
                        if (Settings.canDrawOverlays(applicationContext) &&
                            !FloatingOrbOverlayService.isOverlayActive.value
                        ) {
                            runCatching {
                                ContextCompat.startForegroundService(
                                    applicationContext,
                                    Intent(applicationContext, FloatingOrbOverlayService::class.java)
                                )
                            }
                        }
                    } else {
                        val normalNotif = buildKeepAliveNotification(
                            context = applicationContext,
                            subtitle = "Goku V10.1 Core Active • Say 'Hey Goku' or 'Goku Stop'",
                            showUnlockAction = false
                        )
                        runCatching { nm?.notify(NOTIF_ID_KEEP_ALIVE, normalNotif) }
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isUnlockTap = intent?.action == ACTION_UNLOCK_MIC
        val notification = buildKeepAliveNotification(
            context = this,
            subtitle = if (PostBootMicUnlockBus.micNeedsUnlock.value && !isUnlockTap) {
                "Tap Goku Orb Once to Unlock 24x7 Mic (Android 14/15 Post-Boot Guard)"
            } else {
                "Goku V10.1 Core Active • Say 'Hey Goku' or 'Goku Stop'"
            },
            showUnlockAction = PostBootMicUnlockBus.micNeedsUnlock.value && !isUnlockTap
        )
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val primaryType = if (hasMic) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            val started = runCatching {
                ServiceCompat.startForeground(this, NOTIF_ID_KEEP_ALIVE, notification, primaryType)
                true
            }.getOrDefault(false)
            if (!started) {
                // Fallback on Android 14+ background boot if MICROPHONE FGS is temporarily restricted
                runCatching {
                    ServiceCompat.startForeground(
                        this,
                        NOTIF_ID_KEEP_ALIVE,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            }
        } else {
            runCatching {
                ServiceCompat.startForeground(this, NOTIF_ID_KEEP_ALIVE, notification, 0)
            }
        }

        // Always wire headless voice routing & handle 1-tap post-boot mic unlock cleanly
        GokuHeadlessCommandBus.ensureVoiceCallbackWired(applicationContext)
        if (isUnlockTap && hasMic) {
            PostBootMicUnlockBus.setMicNeedsUnlock(false)
            GokuEarAndVoiceEngine.getInstance(applicationContext).unlockPostBootMicAndListen()
            scope.launch {
                runCatching {
                    GokuVaultRepository.getInstance(applicationContext).logAction(
                        stage = "VOICE",
                        command = "Post-Boot Mic Unlocked",
                        detail = "24x7 microphone stream re-bound cleanly via 1-tap user interaction",
                        confidence = 100
                    )
                }
            }
        } else if (hasMic && !GokuKillSwitch.isStopped.value) {
            GokuHeadlessCommandBus.startVoiceListeningIfPermitted(
                context = applicationContext,
                openDirectWindow = false
            )
        }

        _isRunning.value = true
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Auto-restart via AlarmManager watchdog if swiped from recents
        val restartIntent = Intent(applicationContext, GokuKeepAliveService::class.java)
        val pi = PendingIntent.getService(
            applicationContext,
            1001,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        am?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 2500L, pi)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        _isRunning.value = false
        SensorReflexManager.getInstance(this).stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_KEEP_ALIVE = "goku_keep_alive_channel"
        const val CHANNEL_ALERTS = "goku_alerts_channel"
        const val NOTIF_ID_KEEP_ALIVE = 1010
        const val ACTION_UNLOCK_MIC = "com.goku.ai.action.UNLOCK_POST_BOOT_MIC"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                val coreChannel = NotificationChannel(
                    CHANNEL_KEEP_ALIVE,
                    context.getString(R.string.keep_alive_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.keep_alive_channel_desc)
                    setShowBadge(false)
                }
                val alertChannel = NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.alert_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.alert_channel_desc)
                }
                nm.createNotificationChannel(coreChannel)
                nm.createNotificationChannel(alertChannel)
            }
        }

        fun buildKeepAliveNotification(
            context: Context,
            subtitle: String,
            showUnlockAction: Boolean = false
        ): Notification {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pi = PendingIntent.getActivity(
                context,
                0,
                launchIntent ?: Intent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_KEEP_ALIVE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Goku V10.1 God-Mode")
                .setContentText(subtitle)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (showUnlockAction) {
                val unlockIntent = Intent(context, GokuKeepAliveService::class.java).apply {
                    action = ACTION_UNLOCK_MIC
                }
                val unlockPi = PendingIntent.getService(
                    context,
                    1019,
                    unlockIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    R.drawable.ic_launcher_foreground,
                    "Unlock 24x7 Mic",
                    unlockPi
                )
            }
            return builder.build()
        }

        fun startSafe(context: Context) {
            val intent = Intent(context, GokuKeepAliveService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun scheduleWatchdog(context: Context) {
            val req = PeriodicWorkRequestBuilder<KeepAliveWatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "goku_keep_alive_watchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}

class KeepAliveWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!GokuKeepAliveService.isRunning.value) {
            GokuKeepAliveService.startSafe(applicationContext)
        }
        return Result.success()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                GokuKeepAliveService.startSafe(context)
                GokuKeepAliveService.scheduleWatchdog(context)
            }
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                    val vault = GokuVaultRepository.getInstance(context)
                    if (vault.settings.smartCallEnabled && !GokuKillSwitch.isStopped.value) {
                        val hasCallLog = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_CALL_LOG
                        ) == PackageManager.PERMISSION_GRANTED
                        @Suppress("DEPRECATION")
                        val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.trim().orEmpty()
                        // Android 9+ fires TWO RINGING broadcasts when READ_CALL_LOG is granted:
                        // #1 has null EXTRA_INCOMING_NUMBER, #2 carries the real caller phone number.
                        if (hasCallLog && rawNumber.isBlank()) {
                            return
                        }
                        val svc = Intent(context, SmartCallScreenerService::class.java).apply {
                            putExtra(
                                SmartCallScreenerService.EXTRA_INCOMING_NUMBER,
                                rawNumber.ifBlank { "Unknown" }
                            )
                        }
                        runCatching { ContextCompat.startForegroundService(context, svc) }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 2. FLOATING JARVIS ORB + 1-TAP RED STOP + ADB 6-DIGIT PAIRING OVERLAY
// ============================================================================

class FloatingOrbOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var orbRootView: View? = null
    private var adbPairingView: View? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        GokuKeepAliveService.createNotificationChannels(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = GokuKeepAliveService.buildKeepAliveNotification(this, "Floating Goku Orb & STOP Overlay Active")
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID_ORB, notif, fgsType)
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        GokuHeadlessCommandBus.ensureVoiceCallbackWired(applicationContext)
        showOrbOverlay()
        observeAdbPairingOverlayRequests()
        _isOverlayActive.value = true
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrbOverlay() {
        if (orbRootView != null) return
        val wm = windowManager ?: return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(12, 12, 12, 12)
        }

        // Android 14/15 Post-Boot Mic Silence 1-Tap Unlock Badge
        val unlockBadge = TextView(this).apply {
            text = "TAP ORB: UNLOCK 24x7 MIC"
            textSize = 9.5f
            setTextColor(Color.parseColor("#060A13"))
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f
                setColor(Color.parseColor("#FFB020"))
                setStroke(2, Color.WHITE)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 8
            layoutParams = lp
            visibility = if (PostBootMicUnlockBus.micNeedsUnlock.value) View.VISIBLE else View.GONE
            setOnClickListener {
                performPostBootMicUnlockTap()
            }
        }

        // Draggable Glowing Goku Core Orb Button
        val orbBackground = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#00F0FF"))
            setStroke(5, Color.parseColor("#8B5CF6"))
        }
        val orbBtn = TextView(this).apply {
            text = "G"
            textSize = 20f
            setTextColor(Color.parseColor("#060A13"))
            gravity = Gravity.CENTER
            background = orbBackground
            layoutParams = LinearLayout.LayoutParams(132, 132)
        }

        // Dedicated 1-Tap Red STOP Emergency Kill-Switch Button
        val stopBtn = TextView(this).apply {
            text = "STOP"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#FF2E54"))
                setStroke(2, Color.WHITE)
            }
            val lp = LinearLayout.LayoutParams(132, 58)
            lp.topMargin = 10
            layoutParams = lp
            setOnClickListener {
                GokuKillSwitch.triggerStop("Floating Overlay STOP Button")
                GokuEarAndVoiceEngine.getInstance(applicationContext).speak("Ruk gaya boss.", urgent = true)
            }
        }

        container.addView(unlockBadge)
        container.addView(orbBtn)
        container.addView(stopBtn)

        // Reactively show/hide the post-boot mic unlock badge when Android 14/15 silences background mic
        serviceScope.launch {
            PostBootMicUnlockBus.micNeedsUnlock.collectLatest { needsUnlock ->
                unlockBadge.visibility = if (needsUnlock) View.VISIBLE else View.GONE
                orbBackground.setStroke(
                    5,
                    if (needsUnlock) Color.parseColor("#FFB020") else Color.parseColor("#8B5CF6")
                )
            }
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 28
            y = 320
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        orbBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { wm.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (PostBootMicUnlockBus.micNeedsUnlock.value) {
                            performPostBootMicUnlockTap()
                        } else {
                            // Tap orb -> ensure headless command bus is wired & open immediate listening window
                            val started = GokuHeadlessCommandBus.startVoiceListeningIfPermitted(
                                context = applicationContext,
                                openDirectWindow = true
                            )
                            if (!started) {
                                GokuEarAndVoiceEngine.getInstance(applicationContext)
                                    .speak("Mic permission allow karein Setup tab mein boss.", urgent = true)
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(container, params)
            orbRootView = container
        }
    }

    private fun performPostBootMicUnlockTap() {
        PostBootMicUnlockBus.setMicNeedsUnlock(false)
        // Re-promote KeepAlive FGS with MICROPHONE type from explicit user tap
        val unlockIntent = Intent(applicationContext, GokuKeepAliveService::class.java).apply {
            action = GokuKeepAliveService.ACTION_UNLOCK_MIC
        }
        runCatching { ContextCompat.startForegroundService(applicationContext, unlockIntent) }
        GokuEarAndVoiceEngine.getInstance(applicationContext).unlockPostBootMicAndListen()
    }

    private fun observeAdbPairingOverlayRequests() {
        serviceScope.launch {
            AdbPairingOverlayBus.showPairingOverlay.collectLatest { show ->
                if (show) showAdbPairingDialogOverlay() else removeAdbPairingOverlay()
            }
        }
    }

    private fun showAdbPairingDialogOverlay() {
        if (adbPairingView != null) return
        val wm = windowManager ?: return

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(Color.parseColor("#EE0B1325"))
                setStroke(3, Color.parseColor("#00F0FF"))
            }
        }

        val title = TextView(this).apply {
            text = "Goku Local ADB Pairing"
            textSize = 15f
            setTextColor(Color.parseColor("#00F0FF"))
        }

        val portInput = EditText(this).apply {
            hint = "Pairing Port (e.g. 37123)"
            setText(AdbPairingOverlayBus.detectedPairingPort.value)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val codeInput = EditText(this).apply {
            hint = "6-Digit Wi-Fi Pairing Code"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val statusLabel = TextView(this).apply {
            text = "Enter Wireless Debugging port & 6-digit code"
            textSize = 11f
            setTextColor(Color.LTGRAY)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }

        val pairBtn = Button(this).apply {
            text = "PAIR"
            setOnClickListener {
                val p = portInput.text.toString().trim().toIntOrNull() ?: 0
                val c = codeInput.text.toString().trim()
                statusLabel.text = "Pairing..."
                serviceScope.launch {
                    val res = LocalAdbEngine.getInstance(applicationContext).pairAndEnableTcpIp(p, c)
                    statusLabel.text = res.output
                }
            }
        }

        val closeBtn = Button(this).apply {
            text = "CLOSE"
            setOnClickListener { AdbPairingOverlayBus.dismissOverlay() }
        }

        btnRow.addView(pairBtn)
        btnRow.addView(closeBtn)

        panel.addView(title)
        panel.addView(portInput)
        panel.addView(codeInput)
        panel.addView(statusLabel)
        panel.addView(btnRow)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 140
        }

        runCatching {
            wm.addView(panel, params)
            adbPairingView = panel
        }
    }

    private fun removeAdbPairingOverlay() {
        adbPairingView?.let { v ->
            runCatching { windowManager?.removeView(v) }
        }
        adbPairingView = null
    }

    override fun onDestroy() {
        _isOverlayActive.value = false
        orbRootView?.let { runCatching { windowManager?.removeView(it) } }
        orbRootView = null
        removeAdbPairingOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID_ORB = 1011
        private val _isOverlayActive = MutableStateFlow(false)
        val isOverlayActive: StateFlow<Boolean> = _isOverlayActive.asStateFlow()
    }
}

// ============================================================================
// 3. NOTIFICATION LISTENER (OTP EXTRACTOR + EXPENSE GUARDIAN + WHATSAPP BRAIN)
// ============================================================================

class GokuNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val otpRegex = Regex("(?:otp|code|verification|pin)[^\\d]{0,16}(\\d{4,8})", RegexOption.IGNORE_CASE)
    private val fallbackOtpRegex = Regex("\\b(\\d{6})\\b")
    private val expenseAmountRegex = Regex("(?:rs\\.?|inr|₹)\\s*([\\d,]+(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE)

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
    }

    override fun onListenerDisconnected() {
        _isConnected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val combined = "$title $text".trim()
        if (combined.isBlank()) return

        val lower = combined.lowercase(Locale.ROOT)
        val vault = GokuVaultRepository.getInstance(applicationContext)

        // 1. Extract OTP into OtpBus for zero-keyboard Autopilot auto-fill
        if (lower.contains("otp") || lower.contains("verification") || lower.contains("passcode")) {
            val otp = otpRegex.find(combined)?.groupValues?.get(1)
                ?: fallbackOtpRegex.find(combined)?.groupValues?.get(1)
            if (!otp.isNullOrBlank()) {
                OtpBus.publishOtp(otp)
            }
        }

        // 2. Autonomous Financial & Bill Guardian (Local Expense Parser)
        val isDebit = lower.contains("debited") || lower.contains("spent") ||
            lower.contains("paid") || lower.contains("bill due") || lower.contains("auto-debit")
        if (isDebit) {
            val amtStr = expenseAmountRegex.find(combined)?.groupValues?.get(1)?.replace(",", "")
            val amount = amtStr?.toDoubleOrNull()
            if (amount != null && amount > 0.0) {
                val category = classifyExpenseCategory(lower)
                val merchant = title.ifBlank { pkg.substringAfterLast('.') }
                val recurring = lower.contains("auto-debit") || lower.contains("emi") || lower.contains("subscription")
                scope.launch {
                    vault.recordExpense(
                        merchant = merchant,
                        amountInr = amount,
                        category = category,
                        rawSource = combined.take(220),
                        isRecurring = recurring
                    )
                }
            }
        }

        // 3. WhatsApp / SMS Context Feed + Earphone Whisper Readout
        if (pkg.contains("whatsapp") || pkg.contains("messaging") || pkg.contains("mms")) {
            vault.workingMemory.addTurn("NOTIF", "$title: ${text.take(120)}", "contact:$title")
            val voice = GokuEarAndVoiceEngine.getInstance(applicationContext)
            if (voice.isEarphoneConnected() && !vault.settings.workFocusMode && !GokuKillSwitch.isStopped.value) {
                voice.speak("Naya message $title se: ${text.take(90)}", flushQueue = false)
            }
        }
    }

    private fun classifyExpenseCategory(lower: String): String {
        return when {
            lower.contains("petrol") || lower.contains("fuel") || lower.contains("hpcl") || lower.contains("iocl") -> "PETROL"
            lower.contains("electricity") || lower.contains("bijli") || lower.contains("broadband") || lower.contains("recharge") -> "BILL"
            lower.contains("netflix") || lower.contains("hotstar") || lower.contains("prime") || lower.contains("spotify") -> "OTT"
            lower.contains("blinkit") || lower.contains("zepto") || lower.contains("bigbasket") || lower.contains("grocery") -> "GROCERY"
            lower.contains("emi") || lower.contains("loan") -> "EMI"
            else -> "OTHER"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }
}

// ============================================================================
// 4. SMART CALL SCREENER (30s AUTO-ANSWER + HALF-DUPLEX HINDI NEGOTIATOR)
// ============================================================================

class SmartCallScreenerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var screeningJob: Job? = null
    @Volatile
    private var activeCallerNumber: String = "Unknown"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GokuKeepAliveService.createNotificationChannels(this)
        val notif = GokuKeepAliveService.buildKeepAliveNotification(this, "Smart Call 30s Auto-Answer Ready")
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID_CALL, notif, fgsType)
        }

        val incomingNumber = intent?.getStringExtra(EXTRA_INCOMING_NUMBER)?.trim().orEmpty()
        val resolved = resolveCallerNumber(incomingNumber)
        if (screeningJob?.isActive == true) {
            // If Android fired a second RINGING broadcast with the real number, upgrade activeCallerNumber without resetting timer
            if (isValidDialableNumber(resolved)) {
                activeCallerNumber = resolved
            }
        } else {
            activeCallerNumber = resolved
            startThirtySecondCountdown()
        }
        return START_STICKY
    }

    private fun isValidDialableNumber(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        return digits.length >= 6 && !number.equals("Unknown", ignoreCase = true)
    }

    @SuppressLint("MissingPermission")
    private fun resolveCallerNumber(intentNumber: String): String {
        val cleaned = intentNumber.trim()
        if (isValidDialableNumber(cleaned)) return cleaned

        val hasCallLog = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCallLog) return cleaned.ifBlank { "Unknown" }

        return runCatching {
            val cutoffMs = System.currentTimeMillis() - 90_000L
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(cutoffMs.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                while (cursor.moveToNext()) {
                    val callType = if (typeIdx >= 0) cursor.getInt(typeIdx) else -1
                    if (callType == CallLog.Calls.INCOMING_TYPE ||
                        callType == CallLog.Calls.MISSED_TYPE ||
                        callType == CallLog.Calls.REJECTED_TYPE
                    ) {
                        val num = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                        if (isValidDialableNumber(num)) return@use num
                    }
                }
                null
            }
        }.getOrNull() ?: cleaned.ifBlank { "Unknown" }
    }

    @SuppressLint("MissingPermission")
    private fun currentCallState(): Int {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return runCatching {
            @Suppress("DEPRECATION")
            tm?.callState ?: TelephonyManager.CALL_STATE_IDLE
        }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
    }

    private fun isCallStillOffHook(): Boolean {
        return currentCallState() == TelephonyManager.CALL_STATE_OFFHOOK
    }

    @SuppressLint("MissingPermission")
    private fun startThirtySecondCountdown() {
        screeningJob?.cancel()
        screeningJob = scope.launch {
            val vault = GokuVaultRepository.getInstance(applicationContext)
            val initialNumber = resolveCallerNumber(activeCallerNumber)
            activeCallerNumber = initialNumber
            vault.logAction("SYSTEM", "SmartCall", "Incoming call from $initialNumber — 30s wait started", 95)

            // Wait 30 seconds for user to answer first
            for (sec in 30 downTo 1) {
                if (GokuKillSwitch.isStopped.value) return@launch
                if (currentCallState() != TelephonyManager.CALL_STATE_RINGING) {
                    return@launch // User answered or caller hung up
                }
                delay(1000L)
            }

            // Re-resolve caller number from CallLog if initial broadcast carried "Unknown"
            val callerNumber = resolveCallerNumber(activeCallerNumber)
            activeCallerNumber = callerNumber

            // Still ringing after 30s -> Auto-answer if permission granted
            val canAnswer = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED

            if (canAnswer && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val telecom = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                runCatching {
                    @Suppress("DEPRECATION")
                    telecom?.acceptRingingCall()
                }
                delay(1400L)

                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val prevAudioMode = am?.mode ?: AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION")
                val prevSpeakerOn = am?.isSpeakerphoneOn ?: false

                val voiceEngine = GokuEarAndVoiceEngine.getInstance(applicationContext)
                val policy = vault.settings.smartCallPolicy
                val transcriptLog = StringBuilder()
                var callerTurns = 0

                try {
                    // Half-Duplex Speaker Mode for 2-Way Hindi/Hinglish AI Negotiation
                    runCatching {
                        am?.mode = AudioManager.MODE_IN_COMMUNICATION
                        @Suppress("DEPRECATION")
                        am?.isSpeakerphoneOn = true
                    }

                    val greeting = "Namaste, main Goku AI assistant bol raha hoon. Owner abhi busy hain. Aap apna naam aur kaam batayein."
                    transcriptLog.append("Goku: $greeting\n")
                    voiceEngine.speakAndAwaitCompletion(greeting, urgent = true, timeoutMs = 12_000L)

                    // 2-Way Half-Duplex Negotiation Loop (max 3 caller turns)
                    for (turn in 1..3) {
                        if (GokuKillSwitch.isStopped.value || !isCallStillOffHook()) break
                        delay(350L)
                        val callerSaid = voiceEngine.listenSingleUtterance(timeoutMs = 5_500L).trim()
                        if (callerSaid.isBlank()) {
                            if (turn == 1 && isCallStillOffHook() && !GokuKillSwitch.isStopped.value) {
                                val retryPrompt = "Hello? Aapki aawaz nahi aayi. Kripya apna naam aur message bol dein."
                                transcriptLog.append("Goku: $retryPrompt\n")
                                voiceEngine.speakAndAwaitCompletion(retryPrompt, urgent = true, timeoutMs = 10_000L)
                                continue
                            } else {
                                break
                            }
                        }

                        callerTurns++
                        transcriptLog.append("Caller: $callerSaid\n")

                        val (aiReply, shouldEndCall) = GokuHeadlessCommandBus.generateCallNegotiationReply(
                            context = applicationContext,
                            callerNumber = callerNumber,
                            policy = policy,
                            history = transcriptLog.toString(),
                            callerSaid = callerSaid
                        )
                        transcriptLog.append("Goku: $aiReply\n")
                        if (!isCallStillOffHook() || GokuKillSwitch.isStopped.value) break
                        voiceEngine.speakAndAwaitCompletion(aiReply, urgent = true, timeoutMs = 12_000L)
                        if (shouldEndCall) break
                    }
                } finally {
                    // Restore AudioManager cleanly & hang up politely
                    runCatching {
                        @Suppress("DEPRECATION")
                        am?.isSpeakerphoneOn = prevSpeakerOn
                        am?.mode = prevAudioMode
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isCallStillOffHook()) {
                        runCatching {
                            @Suppress("DEPRECATION")
                            telecom?.endCall()
                        }
                    }

                    val finalNumber = resolveCallerNumber(callerNumber)
                    val summary = "Caller: $finalNumber | Turns: $callerTurns | Policy: $policy\n${transcriptLog.toString().trim()}"
                    vault.logAction(
                        stage = "EXECUTOR",
                        command = "SmartCall 2-Way Negotiation",
                        detail = summary.take(480),
                        confidence = 95
                    )
                    vault.saveMemory("last_screened_call", summary.take(600), category = "CALL")

                    if (callerTurns == 0) {
                        sendAutoSmsFallback(finalNumber, vault)
                    }

                    // Resume continuous wake-word listening after call concludes
                    if (!GokuKillSwitch.isStopped.value) {
                        GokuHeadlessCommandBus.startVoiceListeningIfPermitted(
                            context = applicationContext,
                            openDirectWindow = false
                        )
                    }
                }
            } else {
                val finalNumber = resolveCallerNumber(callerNumber)
                sendAutoSmsFallback(finalNumber, vault)
            }
        }
    }

    private fun sendAutoSmsFallback(callerNumber: String, vault: GokuVaultRepository) {
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val resolvedNumber = resolveCallerNumber(callerNumber)
        if (!hasSms || !isValidDialableNumber(resolvedNumber)) {
            scope.launch {
                vault.logAction(
                    stage = "EXECUTOR",
                    command = "SmartCall SMS Fallback",
                    detail = "SMS skipped (hasSms=$hasSms, number=$resolvedNumber)",
                    confidence = 60,
                    status = "WARN"
                )
            }
            return
        }
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        runCatching {
            smsManager.sendTextMessage(
                resolvedNumber,
                null,
                "Namaste, main abhi busy hoon (Goku Auto-Assistant). Kripya urgent kaam SMS/WhatsApp kar dein.",
                null,
                null
            )
            scope.launch {
                vault.logAction("EXECUTOR", "SmartCall SMS Fallback", "Auto-SMS sent to $resolvedNumber", 94)
            }
        }
    }

    override fun onDestroy() {
        screeningJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_INCOMING_NUMBER = "extra_incoming_number"
        private const val NOTIF_ID_CALL = 1012
    }
}
