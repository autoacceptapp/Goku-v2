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
import com.goku.ai.automation.SensorReflexManager
import com.goku.ai.body.OtpBus
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
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

// ============================================================================
// 1. ANDROID 14/15 KEEP-ALIVE FOREGROUND SERVICE + WATCHDOG + BOOT RECEIVER
// ============================================================================

class GokuKeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
        SensorReflexManager.getInstance(this).start()
        scheduleWatchdog(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildKeepAliveNotification(this, "Goku V10 Core Active • Say 'Hey Goku' or 'Goku Stop'")
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (hasMic) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        } else {
            0
        }

        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID_KEEP_ALIVE, notification, fgsType)
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

        fun buildKeepAliveNotification(context: Context, subtitle: String): Notification {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pi = PendingIntent.getActivity(
                context,
                0,
                launchIntent ?: Intent(),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_KEEP_ALIVE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Goku V10 God-Mode")
                .setContentText(subtitle)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
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
                        @Suppress("DEPRECATION")
                        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown"
                        val svc = Intent(context, SmartCallScreenerService::class.java).apply {
                            putExtra(SmartCallScreenerService.EXTRA_INCOMING_NUMBER, number)
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

        // Draggable Glowing Goku Core Orb Button
        val orbBtn = TextView(this).apply {
            text = "G"
            textSize = 20f
            setTextColor(Color.parseColor("#060A13"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#00F0FF"))
                setStroke(5, Color.parseColor("#8B5CF6"))
            }
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

        container.addView(orbBtn)
        container.addView(stopBtn)

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
                        // Tap orb -> trigger immediate Goku listening window
                        GokuEarAndVoiceEngine.getInstance(applicationContext)
                            .startListening(continuous = true, openDirectWindow = true)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GokuKeepAliveService.createNotificationChannels(this)
        val notif = GokuKeepAliveService.buildKeepAliveNotification(this, "Smart Call 30s Auto-Answer Ready")
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID_CALL, notif, fgsType)
        }

        val incomingNumber = intent?.getStringExtra(EXTRA_INCOMING_NUMBER).orEmpty()
        if (incomingNumber.isNotBlank()) {
            startThirtySecondCountdown(incomingNumber)
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startThirtySecondCountdown(callerNumber: String) {
        screeningJob?.cancel()
        screeningJob = scope.launch {
            val vault = GokuVaultRepository.getInstance(applicationContext)
            vault.logAction("SYSTEM", "SmartCall", "Incoming call from $callerNumber — 30s wait started", 95)

            // Wait 30 seconds for user to answer first
            for (sec in 30 downTo 1) {
                if (GokuKillSwitch.isStopped.value) return@launch
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val state = runCatching {
                    @Suppress("DEPRECATION")
                    tm?.callState ?: TelephonyManager.CALL_STATE_IDLE
                }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
                if (state != TelephonyManager.CALL_STATE_RINGING) {
                    return@launch // User answered or caller hung up
                }
                delay(1000L)
            }

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
                delay(1200L)

                // Half-Duplex Speaker Mode for Hindi AI screening
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                runCatching {
                    am?.mode = AudioManager.MODE_IN_CALL
                    @Suppress("DEPRECATION")
                    am?.isSpeakerphoneOn = true
                }

                val policy = vault.settings.smartCallPolicy
                val greeting = "Namaste, main Goku AI assistant bol raha hoon. Owner abhi busy hain. Aap apna kaam batayein — $policy"
                GokuEarAndVoiceEngine.getInstance(applicationContext).speak(greeting, urgent = true)
                vault.logAction("EXECUTOR", "SmartCall Auto-Answered", "Screened $callerNumber with policy", 92)
            } else {
                sendAutoSmsFallback(callerNumber, vault)
            }
        }
    }

    private fun sendAutoSmsFallback(callerNumber: String, vault: GokuVaultRepository) {
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasSms || callerNumber.length < 6) return
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        runCatching {
            smsManager.sendTextMessage(
                callerNumber,
                null,
                "Namaste, main abhi busy hoon (Goku Auto-Assistant). Kripya urgent kaam SMS/WhatsApp kar dein.",
                null,
                null
            )
            scope.launch {
                vault.logAction("EXECUTOR", "SmartCall SMS Fallback", "Auto-SMS sent to $callerNumber", 94)
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
