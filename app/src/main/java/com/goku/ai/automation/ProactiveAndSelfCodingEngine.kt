package com.goku.ai.automation

import android.Manifest
import android.app.NotificationManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.Image
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.telephony.SmsManager
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goku.ai.adb.LocalAdbEngine
import com.goku.ai.body.UniversalAppPilot
import com.goku.ai.data.GokuVaultRepository
import com.goku.ai.voice.GokuEarAndVoiceEngine
import com.goku.ai.voice.GokuKillSwitch
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.ScriptableObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

// ============================================================================
// 1. HARDWARE SENSOR REFLEXES + POCKET DEEP HIBERNATION + BATTERY THRESHOLDS
// ============================================================================

data class SensorReflexState(
    val isFaceDown: Boolean = false,
    val isInPocket: Boolean = false,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val adaptivePowerMode: String = "NORMAL" // NORMAL, ECO_20, SURVIVAL_10, DREAM_CHARGING
)

class SensorReflexManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private val _state = MutableStateFlow(SensorReflexState())
    val state: StateFlow<SensorReflexState> = _state.asStateFlow()

    private var registered = false
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = (level * 100) / scale
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val mode = when {
                pct <= 10 && !charging -> "SURVIVAL_10"
                pct <= 20 && !charging -> "ECO_20"
                charging && (hour >= 22 || hour < 6) -> "DREAM_CHARGING"
                else -> "NORMAL"
            }

            _state.value = _state.value.copy(
                batteryPercent = pct,
                isCharging = charging,
                adaptivePowerMode = mode
            )
        }
    }

    fun start() {
        if (!registered) {
            val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val prox = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            accel?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            prox?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            registered = true
        }
        if (!batteryReceiverRegistered) {
            runCatching {
                context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                batteryReceiverRegistered = true
            }
        }
    }

    fun stop() {
        if (registered) {
            runCatching { sensorManager?.unregisterListener(this) }
            registered = false
        }
        if (batteryReceiverRegistered) {
            runCatching { context.unregisterReceiver(batteryReceiver) }
            batteryReceiverRegistered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val z = event.values.getOrNull(2) ?: 0f
                val faceDownNow = z < -8.6f
                if (faceDownNow != _state.value.isFaceDown) {
                    _state.value = _state.value.copy(isFaceDown = faceDownNow)
                    applySilentReflexIfPermitted(faceDownNow || _state.value.isInPocket)
                }
            }
            Sensor.TYPE_PROXIMITY -> {
                val dist = event.values.getOrNull(0) ?: 5f
                val maxRange = event.sensor.maximumRange.coerceAtLeast(1f)
                val inPocketNow = dist < minOf(2.5f, maxRange)
                if (inPocketNow != _state.value.isInPocket) {
                    _state.value = _state.value.copy(isInPocket = inPocketNow)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun applySilentReflexIfPermitted(enableQuiet: Boolean) {
        val nm = notificationManager ?: return
        if (nm.isNotificationPolicyAccessGranted) {
            runCatching {
                if (enableQuiet) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                } else {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            }
        } else if (enableQuiet) {
            runCatching {
                audioManager?.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: SensorReflexManager? = null

        fun getInstance(context: Context): SensorReflexManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SensorReflexManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

// ============================================================================
// 2. EMERGENCY SOS & OFFLINE GPS LOCATION BEACON
// ============================================================================

class EmergencySosManager(
    private val context: Context,
    private val vault: GokuVaultRepository
) {
    suspend fun triggerSosBeacon(customPrefix: String? = null): String = withContext(Dispatchers.IO) {
        val contacts = vault.settings.getSosContactsList()
        val battery = readBatteryPercentage()
        val location = getBestLastKnownLocation()
        val locPart = if (location != null) {
            "https://maps.google.com/?q=${location.latitude},${location.longitude} (acc ${location.accuracy.toInt()}m)"
        } else {
            "GPS unavailable (enable Location)"
        }

        val header = customPrefix ?: "EMERGENCY SOS from Goku V10.1! I need help immediately."
        val smsBody = "$header Location: $locPart | Battery: $battery%"

        if (contacts.isEmpty()) {
            vault.logAction("SYSTEM", "Emergency SOS", "No SOS contacts configured. $smsBody", 100, "WARN")
            return@withContext "Emergency SOS alert taiyaar hai ($locPart, Battery $battery%), par Vault > Settings mein koi Family SOS number saved nahi hai. Pehle contact add karein!"
        }

        val hasSmsPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasSmsPerm) {
            vault.logAction("SYSTEM", "Emergency SOS", "SEND_SMS permission missing", 100, "ERROR")
            return@withContext "SEND_SMS permission nahi mili hai. Setup tab se SMS permission allow karein taaki offline SOS bhej sakoon."
        }

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        var sentCount = 0
        contacts.forEach { number ->
            runCatching {
                val parts = smsManager.divideMessage(smsBody)
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                sentCount++
            }
        }

        val msg = "Emergency SOS beacon $sentCount/${contacts.size} family contacts ko offline SMS se bhej diya gaya. Battery: $battery%, Location: $locPart"
        vault.logAction("SYSTEM", "Emergency SOS", msg, 100, "OK")
        msg
    }

    suspend fun triggerIntruderAlert(failedAttempts: Int, photoPath: String?): String {
        val photoNote = if (!photoPath.isNullOrBlank()) {
            "Front-camera snapshot saved: ${File(photoPath).name}."
        } else {
            "Camera capture skipped/unavailable."
        }
        return triggerSosBeacon(
            customPrefix = "ANTI-THEFT ALERT (Goku V10.1): $failedAttempts failed lock-screen unlock attempts! $photoNote"
        )
    }

    fun getBestLastKnownLocationSummary(): String {
        val loc = getBestLastKnownLocation() ?: return "GPS unavailable"
        return "${loc.latitude},${loc.longitude} (acc ${loc.accuracy.toInt()}m)"
    }

    private fun readBatteryPercentage(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 100
    }

    @Suppress("MissingPermission")
    private fun getBestLastKnownLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (p in providers) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || loc.accuracy < best.accuracy) {
                best = loc
            }
        }
        return best
    }
}

// ============================================================================
// 3. V10.1 ANTI-THEFT INTRUDER GUARD + DEVICE ADMIN RECEIVER (HEADLESS CAMERAX)
// ============================================================================

data class IntruderGuardState(
    val failedAttempts: Int = 0,
    val lastIntruderPhotoPath: String? = null,
    val lastAlertTimestamp: Long = 0L,
    val lastAlertSummary: String = "Armed (activate Device Admin to watch failed unlocks)",
    val isDeviceAdminActive: Boolean = false
)

/**
 * Lightweight LifecycleOwner for silent headless CameraX `ImageCapture` without any Preview UI.
 */
private class HeadlessCameraLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun moveToResumed() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun moveToDestroyed() {
        runCatching {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}

/**
 * Anti-Theft Intruder Guard:
 * Tracks consecutive failed lock-screen PIN/pattern attempts via `GokuDeviceAdminReceiver`.
 * On the 3rd failed attempt:
 * 1) Silently captures a front-camera photo using CameraX `ProcessCameraProvider` + `ImageCapture`
 *    bound to a headless `LifecycleOwner` into `filesDir/intruder/`.
 * 2) Logs GPS coordinates & photo metadata into `GokuVaultRepository`.
 * 3) Triggers `EmergencySosManager` offline SMS alert to saved family contacts.
 */
class AntiTheftIntruderGuard(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("goku_intruder_guard", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val failedCount = AtomicInteger(prefs.getInt(KEY_FAILED_COUNT, 0))
    @Volatile
    private var lastAlertTimeMs: Long = prefs.getLong(KEY_LAST_ALERT_MS, 0L)

    private val _state = MutableStateFlow(
        IntruderGuardState(
            failedAttempts = failedCount.get(),
            lastIntruderPhotoPath = prefs.getString(KEY_LAST_PHOTO, null),
            lastAlertTimestamp = lastAlertTimeMs,
            isDeviceAdminActive = isDeviceAdminActive()
        )
    )
    val state: StateFlow<IntruderGuardState> = _state.asStateFlow()

    fun isDeviceAdminActive(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val admin = ComponentName(context, GokuDeviceAdminReceiver::class.java)
        return runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)
    }

    fun buildActivateAdminIntent(): Intent {
        val admin = ComponentName(context, GokuDeviceAdminReceiver::class.java)
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enable Goku V10.1 Anti-Theft Intruder Guard to detect 3 failed lock-screen unlock attempts."
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun refreshAdminStatus() {
        _state.value = _state.value.copy(isDeviceAdminActive = isDeviceAdminActive())
    }

    fun onPasswordSucceeded() {
        failedCount.set(0)
        prefs.edit().putInt(KEY_FAILED_COUNT, 0).apply()
        _state.value = _state.value.copy(
            failedAttempts = 0,
            isDeviceAdminActive = isDeviceAdminActive()
        )
    }

    fun onPasswordFailed(onComplete: (() -> Unit)? = null) {
        val attempts = failedCount.incrementAndGet()
        prefs.edit().putInt(KEY_FAILED_COUNT, attempts).apply()
        _state.value = _state.value.copy(
            failedAttempts = attempts,
            isDeviceAdminActive = isDeviceAdminActive()
        )

        val now = System.currentTimeMillis()
        if (attempts >= INTRUDER_THRESHOLD && (now - lastAlertTimeMs) >= ALERT_COOLDOWN_MS) {
            lastAlertTimeMs = now
            prefs.edit().putLong(KEY_LAST_ALERT_MS, now).apply()
            scope.launch {
                try {
                    executeIntruderResponse(attempts)
                } finally {
                    onComplete?.invoke()
                }
            }
        } else {
            onComplete?.invoke()
        }
    }

    private suspend fun executeIntruderResponse(attempts: Int) {
        val vault = GokuVaultRepository.getInstance(context)
        val sos = EmergencySosManager(context, vault)

        // 1. Silent front-camera snapshot via CameraX (no Preview UI)
        val photoFile = captureSilentFrontCameraPhoto()
        val photoPath = photoFile?.absolutePath
        val gpsSummary = sos.getBestLastKnownLocationSummary()

        if (photoPath != null) {
            prefs.edit().putString(KEY_LAST_PHOTO, photoPath).apply()
        }

        val logDetail = "Failed attempts=$attempts | GPS=$gpsSummary | Photo=${photoPath ?: "unavailable"}"
        vault.logAction("SYSTEM", "Anti-Theft Intruder Guard", logDetail, 100, "WARN")
        vault.saveMemory("last_intruder_event", logDetail, category = "SECURITY")

        // 2. Trigger offline Emergency SOS SMS to saved family contacts
        val smsReport = sos.triggerIntruderAlert(attempts, photoPath)

        _state.value = _state.value.copy(
            failedAttempts = attempts,
            lastIntruderPhotoPath = photoPath ?: _state.value.lastIntruderPhotoPath,
            lastAlertTimestamp = System.currentTimeMillis(),
            lastAlertSummary = "$logDetail | $smsReport",
            isDeviceAdminActive = isDeviceAdminActive()
        )
    }

    /**
     * Captures a silent front-camera JPEG using CameraX `ProcessCameraProvider` + `ImageCapture`
     * bound to a temporary `HeadlessCameraLifecycleOwner` without any UI surface/preview.
     */
    suspend fun captureSilentFrontCameraPhoto(): File? {
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasCamera) return null

        val intruderDir = File(context.filesDir, "intruder").apply { mkdirs() }
        val outFile = File(intruderDir, "intruder_${System.currentTimeMillis()}.jpg")

        return withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                val providerFuture = ProcessCameraProvider.getInstance(context)
                val mainExecutor = ContextCompat.getMainExecutor(context)

                providerFuture.addListener({
                    var cameraProvider: ProcessCameraProvider? = null
                    val lifecycleOwner = HeadlessCameraLifecycleOwner()
                    try {
                        cameraProvider = providerFuture.get()
                        lifecycleOwner.moveToResumed()

                        val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }

                        val imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, selector, imageCapture)

                        val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()
                        imageCapture.takePicture(
                            outputOptions,
                            cameraExecutor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    mainHandler.post {
                                        runCatching { cameraProvider.unbindAll() }
                                        lifecycleOwner.moveToDestroyed()
                                    }
                                    if (cont.isActive) cont.resume(outFile)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    mainHandler.post {
                                        runCatching { cameraProvider.unbindAll() }
                                        lifecycleOwner.moveToDestroyed()
                                    }
                                    runCatching { outFile.delete() }
                                    if (cont.isActive) cont.resume(null)
                                }
                            }
                        )
                    } catch (_: Throwable) {
                        mainHandler.post {
                            runCatching { cameraProvider?.unbindAll() }
                            lifecycleOwner.moveToDestroyed()
                        }
                        runCatching { outFile.delete() }
                        if (cont.isActive) cont.resume(null)
                    }
                }, mainExecutor)
            }
        }
    }

    companion object {
        private const val INTRUDER_THRESHOLD = 3
        private const val ALERT_COOLDOWN_MS = 60_000L
        private const val KEY_FAILED_COUNT = "failed_attempts"
        private const val KEY_LAST_ALERT_MS = "last_alert_ms"
        private const val KEY_LAST_PHOTO = "last_photo_path"

        @Volatile
        private var INSTANCE: AntiTheftIntruderGuard? = null

        fun getInstance(context: Context): AntiTheftIntruderGuard {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AntiTheftIntruderGuard(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

/**
 * Android DeviceAdminReceiver wired to `res/xml/device_admin_policies.xml` (`<watch-login />`).
 * Detects failed lock-screen PIN/pattern attempts and delegates immediately to `AntiTheftIntruderGuard`.
 */
class GokuDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        AntiTheftIntruderGuard.getInstance(context).refreshAdminStatus()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        AntiTheftIntruderGuard.getInstance(context).refreshAdminStatus()
    }

    @Deprecated("Deprecated in Android API 26")
    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            dispatchPasswordFailed(context)
        }
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)
        dispatchPasswordFailed(context)
    }

    @Deprecated("Deprecated in Android API 26")
    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            AntiTheftIntruderGuard.getInstance(context).onPasswordSucceeded()
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordSucceeded(context, intent, user)
        AntiTheftIntruderGuard.getInstance(context).onPasswordSucceeded()
    }

    private fun dispatchPasswordFailed(context: Context) {
        val pendingResult = goAsync()
        AntiTheftIntruderGuard.getInstance(context).onPasswordFailed {
            runCatching { pendingResult.finish() }
        }
    }
}

// ============================================================================
// 4. SANDBOXED MOZILLA RHINO JS BRIDGE + SELF-CODING LIVE PLUGIN ENGINE
// ============================================================================

/**
 * Safe JavaScript bridge exposed inside sandboxed `.goku` JS plugins as global `goku`.
 * All methods respect `dryRun` and `GokuKillSwitch`.
 */
class GokuJsBridge(
    private val vault: GokuVaultRepository,
    private val adbEngine: LocalAdbEngine,
    private val pilot: UniversalAppPilot,
    private val voiceEngine: GokuEarAndVoiceEngine,
    private val dryRun: Boolean,
    private val outputLog: StringBuilder
) {
    fun tap(target: String): Boolean {
        checkNotStopped()
        if (dryRun) {
            outputLog.append("[DRY-JS] goku.tap($target)\n")
            return true
        }
        val (ok, msg) = runBlocking(Dispatchers.IO) { pilot.tapTarget(target) }
        outputLog.append("JS tap($target): $msg\n")
        return ok
    }

    fun tapXY(x: Int, y: Int): Boolean {
        checkNotStopped()
        if (dryRun) {
            outputLog.append("[DRY-JS] goku.tapXY($x, $y)\n")
            return true
        }
        val res = runBlocking(Dispatchers.IO) { adbEngine.dispatchAdbTap(x, y) }
        outputLog.append("JS tapXY($x,$y): ${res.output}\n")
        return res.success
    }

    fun adb(command: String): String {
        checkNotStopped()
        if (dryRun) {
            outputLog.append("[DRY-JS] goku.adb($command)\n")
            return "[DRY] $command"
        }
        val res = runBlocking(Dispatchers.IO) { adbEngine.execShellCommand(command) }
        outputLog.append("JS adb($command): ${res.output.take(120)}\n")
        return res.output
    }

    fun speak(text: String) {
        checkNotStopped()
        if (dryRun) {
            outputLog.append("[DRY-JS] goku.speak($text)\n")
            return
        }
        voiceEngine.speak(text)
        outputLog.append("JS spoke: $text\n")
    }

    fun saveMemory(key: String, value: String) {
        checkNotStopped()
        if (dryRun) {
            outputLog.append("[DRY-JS] goku.saveMemory($key, $value)\n")
            return
        }
        runBlocking(Dispatchers.IO) { vault.saveMemory(key, value, "PLUGIN_JS") }
        outputLog.append("JS saved memory [$key]\n")
    }

    fun openApp(appName: String): Boolean {
        checkNotStopped()
        if (dryRun) {
            outputLog.append("[DRY-JS] goku.openApp($appName)\n")
            return true
        }
        val stepArr = JSONArray().put(JSONObject().put("action", "open_app").put("target", appName)).toString()
        val (ok, msg) = runBlocking(Dispatchers.IO) { pilot.executeStepsJson(stepArr, dryRun = false) }
        outputLog.append("JS openApp($appName): $msg\n")
        return ok
    }

    fun sleep(ms: Long) {
        checkNotStopped()
        val clamped = ms.coerceIn(0L, 3000L)
        if (!dryRun && clamped > 0L) {
            Thread.sleep(clamped)
        }
    }

    fun log(message: String) {
        outputLog.append("JS log: ").append(message.take(240)).append("\n")
    }

    private fun checkNotStopped() {
        if (GokuKillSwitch.isStopped.value) {
            throw SecurityException("Stopped by GokuKillSwitch")
        }
    }
}

private class GuardedRhinoContext(factory: ContextFactory) : RhinoContext(factory) {
    var startTimeMs: Long = System.currentTimeMillis()
    var totalInstructions: Long = 0L
}

private class GokuSandboxedRhinoContextFactory : ContextFactory() {
    override fun makeContext(): RhinoContext {
        return GuardedRhinoContext(this).apply {
            // Interpreted mode (-1) is mandatory on Android ART/Dalvik (no JVM bytecode emit)
            optimizationLevel = -1
            languageVersion = RhinoContext.VERSION_ES6
            instructionObserverThreshold = 5_000
        }
    }

    override fun observeInstructionCount(cx: RhinoContext, instructionCount: Int) {
        val guarded = cx as? GuardedRhinoContext ?: return
        guarded.totalInstructions += instructionCount
        if (GokuKillSwitch.isStopped.value) {
            throw SecurityException("JS plugin aborted by GokuKillSwitch.")
        }
        if (guarded.totalInstructions > MAX_JS_INSTRUCTIONS) {
            throw SecurityException("JS plugin halted: exceeded instruction limit ($MAX_JS_INSTRUCTIONS).")
        }
        if (System.currentTimeMillis() - guarded.startTimeMs > MAX_JS_WALL_MS) {
            throw SecurityException("JS plugin halted: exceeded ${MAX_JS_WALL_MS}ms timeout.")
        }
    }

    companion object {
        private const val MAX_JS_INSTRUCTIONS = 150_000L
        private const val MAX_JS_WALL_MS = 6_000L
    }
}

class SelfCodingPluginEngine(
    private val context: Context,
    private val vault: GokuVaultRepository,
    private val adbEngine: LocalAdbEngine,
    private val pilot: UniversalAppPilot,
    private val voiceEngine: GokuEarAndVoiceEngine
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val rhinoFactory = GokuSandboxedRhinoContextFactory()

    /**
     * Instant In-App Live Plugin Runner (0.1s):
     * Executes BOTH:
     * 1) Dynamic JSON action-step workflows (`{"steps":[...]}` or `[...]`)
     * 2) Sandboxed JavaScript `.goku` plugins via Mozilla Rhino (`{"script":"..."}` or raw JS)
     *    with instruction-count loop guard, 6s wall-clock cap, ClassShutter sandbox, and
     *    Android bridge (`goku.tap()`, `goku.adb()`, `goku.speak()`, `goku.saveMemory()`).
     */
    suspend fun runLivePlugin(pluginSource: String, dryRun: Boolean = false): String = withContext(Dispatchers.IO) {
        val trimmed = pluginSource.trim()
        if (trimmed.isEmpty()) {
            return@withContext "Plugin source is empty."
        }

        // Check for JSON-wrapped JavaScript plugin: {"script": "goku.speak('Hi');"}
        if (trimmed.startsWith("{")) {
            val obj = runCatching { JSONObject(trimmed) }.getOrNull()
            val embeddedJs = obj?.optString("script", "")?.trim().orEmpty()
            if (embeddedJs.isNotEmpty()) {
                return@withContext runSandboxedJavaScript(embeddedJs, dryRun)
            }
        }

        // Check for raw JavaScript plugin (non-JSON script)
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return@withContext runSandboxedJavaScript(trimmed, dryRun)
        }

        val start = System.currentTimeMillis()
        val parsed = runCatching {
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("steps") ?: JSONArray()
        }.getOrNull() ?: return@withContext "Invalid plugin format. Provide JSON {\"steps\":[...]}, {\"script\":\"...\"}, or raw JS."

        val report = StringBuilder()
        for (i in 0 until parsed.length()) {
            if (GokuKillSwitch.isStopped.value) {
                return@withContext "Plugin execution stopped by GokuKillSwitch."
            }
            val step = parsed.optJSONObject(i) ?: continue
            val action = step.optString("action").lowercase()
            val target = step.optString("target")
            if (dryRun) {
                report.append("[DRY] #${i + 1} $action($target)\n")
                continue
            }
            when (action) {
                "speak" -> {
                    voiceEngine.speak(target)
                    report.append("#${i + 1} Spoke: $target\n")
                }
                "save_memory" -> {
                    val key = step.optString("key", "plugin_note")
                    vault.saveMemory(key, target, "PLUGIN")
                    report.append("#${i + 1} Saved memory [$key]\n")
                }
                "http_get" -> {
                    if (target.startsWith("https://")) {
                        val respText = runCatching {
                            val req = Request.Builder().url(target).get().build()
                            http.newCall(req).execute().use { r ->
                                "HTTP ${r.code}: ${(r.body?.string() ?: "").take(160)}"
                            }
                        }.getOrElse { "HTTP error: ${it.message}" }
                        report.append("#${i + 1} $respText\n")
                    } else {
                        report.append("#${i + 1} Blocked non-HTTPS URL\n")
                    }
                }
                else -> {
                    val singleArr = JSONArray().put(step).toString()
                    val (_, msg) = pilot.executeStepsJson(singleArr, dryRun = false)
                    report.append("#${i + 1} $msg\n")
                }
            }
        }
        val elapsed = System.currentTimeMillis() - start
        val out = report.toString().trim().ifEmpty { "Plugin finished (${elapsed}ms)." }
        vault.logAction("EXECUTOR", "Live Plugin", out, 96, if (dryRun) "DRY_RUN" else "OK", elapsed)
        out
    }

    /**
     * Executes untrusted `.goku` JavaScript inside a strict Mozilla Rhino sandbox:
     * - `initSafeStandardObjects()` removes Java package/reflection globals
     * - `ClassShutter` permits ONLY `GokuJsBridge` and `java.lang.String`
     * - `observeInstructionCount` aborts infinite loops (150k instructions / 6s cap / KillSwitch)
     */
    suspend fun runSandboxedJavaScript(jsCode: String, dryRun: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val logBuilder = StringBuilder()
            val bridge = GokuJsBridge(vault, adbEngine, pilot, voiceEngine, dryRun, logBuilder)

            val cx = rhinoFactory.enterContext() as GuardedRhinoContext
            val outcome = try {
                cx.startTimeMs = start
                cx.totalInstructions = 0L
                cx.setClassShutter(ClassShutter { className ->
                    className == GokuJsBridge::class.java.name ||
                        className == "java.lang.String"
                })

                val scope = cx.initSafeStandardObjects()
                val jsBridge = RhinoContext.javaToJS(bridge, scope)
                ScriptableObject.putProperty(scope, "goku", jsBridge)

                val evalResult = cx.evaluateString(scope, jsCode, "goku_plugin.js", 1, null)
                val resultText = if (evalResult != null && evalResult != RhinoContext.getUndefinedValue()) {
                    RhinoContext.toString(evalResult)
                } else ""

                val combined = buildString {
                    if (logBuilder.isNotEmpty()) append(logBuilder.toString().trim())
                    if (resultText.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("Return: ").append(resultText.take(300))
                    }
                }.ifBlank { "JS plugin finished cleanly (${System.currentTimeMillis() - start}ms)." }
                true to combined
            } catch (err: Throwable) {
                false to "JS Sandbox Error: ${err.message ?: err.javaClass.simpleName}"
            } finally {
                RhinoContext.exit()
            }

            val elapsed = System.currentTimeMillis() - start
            vault.logAction(
                stage = "EXECUTOR",
                command = "Live JS Plugin",
                detail = outcome.second,
                confidence = if (outcome.first) 96 else 40,
                status = if (!outcome.first) "ERROR" else if (dryRun) "DRY_RUN" else "OK",
                latencyMs = elapsed
            )
            outcome.second
        }

    /**
     * Pushes a skill pack / self-coding commit to user's private GitHub repo via GitHub REST API.
     */
    suspend fun pushToGithubArchive(filePathInRepo: String, content: String, commitMsg: String): String =
        withContext(Dispatchers.IO) {
            val token = vault.settings.githubToken
            val repo = vault.settings.githubRepo // e.g. "username/goku-archive"
            if (token.isBlank() || repo.isBlank() || !repo.contains("/")) {
                return@withContext "GitHub archive skipped: Configure GitHub Repo (user/repo) & PAT in Vault Settings."
            }
            val url = "https://api.github.com/repos/$repo/contents/$filePathInRepo"
            val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val existingSha = runCatching {
                val getReq = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .get()
                    .build()
                http.newCall(getReq).execute().use { resp ->
                    if (resp.isSuccessful) JSONObject(resp.body?.string() ?: "{}").optString("sha", "") else ""
                }
            }.getOrDefault("")

            val bodyJson = JSONObject().apply {
                put("message", commitMsg)
                put("content", encoded)
                if (existingSha.isNotBlank()) put("sha", existingSha)
            }

            runCatching {
                val putReq = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .put(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(putReq).execute().use { resp ->
                    if (resp.isSuccessful) "GitHub sync OK ($repo/$filePathInRepo)"
                    else "GitHub sync HTTP ${resp.code}"
                }
            }.getOrElse { "GitHub sync error: ${it.message}" }
        }

    suspend fun sendTelegramArchiveSummary(messageText: String): String = withContext(Dispatchers.IO) {
        val bot = vault.settings.telegramBotToken
        val chatId = vault.settings.telegramChatId
        if (bot.isBlank() || chatId.isBlank()) {
            return@withContext "Telegram archive skipped: Configure Bot Token & Chat ID in Vault Settings."
        }
        val url = "https://api.telegram.org/bot$bot/sendMessage"
        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("text", messageText.take(3900))
        }
        runCatching {
            val req = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) "Telegram encrypted archive sent." else "Telegram HTTP ${resp.code}"
            }
        }.getOrElse { "Telegram sync failed: ${it.message}" }
    }

    /**
     * Nightly Dream Mode Audit:
     * 1) Compacts >7-day visual timeline entries & purges >30-day summarized entries
     * 2) Runs Phone Doctor (cache trim + Aggressive Doze via ADB if connected)
     * 3) Self-critiques recent failures from ActionLog
     * 4) Discovers installed app intents & syncs .goku skills archive if configured
     */
    suspend fun runDreamModeAudit(): String = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val compacted = vault.compactOldTimelineEntries()
        vault.pruneOldLogs()
        val doctorMsg = adbEngine.runPhoneDoctor()
        val failures = vault.getRecentFailures()
        val apps = adbEngine.discoverInstalledApps()
        val skillJson = vault.exportAllSkillsToGokuJson()

        val ghStatus = if (vault.settings.githubToken.isNotBlank()) {
            pushToGithubArchive(
                "backups/goku-skills-v10.1.goku.json",
                skillJson,
                "Dream Mode nightly .goku skill backup (10.1-godmode)"
            )
        } else "GitHub backup not configured"

        val tgStatus = if (vault.settings.telegramBotToken.isNotBlank()) {
            sendTelegramArchiveSummary("Goku V10.1 Dream Mode Backup\nApps indexed: ${apps.size}\nCompacted screens: $compacted\nRecent issues audited: ${failures.size}")
        } else "Telegram backup not configured"

        val summary = "Dream Mode Complete (${System.currentTimeMillis() - start}ms): " +
            "$compacted screens compacted, ${apps.size} apps indexed, ${failures.size} past failures audited. " +
            "$doctorMsg | $ghStatus | $tgStatus"
        vault.logAction("CRITIC", "Nightly Dream Mode", summary, 98, "OK", System.currentTimeMillis() - start)
        summary
    }

    fun scheduleNightlyDreamMode() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val req = PeriodicWorkRequestBuilder<DreamModeWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "goku_nightly_dream_mode",
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }
}

class DreamModeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val vault = GokuVaultRepository.getInstance(applicationContext)
        val adb = LocalAdbEngine.getInstance(applicationContext)
        val pilot = UniversalAppPilot(applicationContext, adb)
        val voice = GokuEarAndVoiceEngine.getInstance(applicationContext)
        val engine = SelfCodingPluginEngine(applicationContext, vault, adb, pilot, voice)
        return runCatching {
            engine.runDreamModeAudit()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

// ============================================================================
// 5. MULTIMODAL CAMERA LIVE-EYE (GEMINI VISION + SEAMLESS OFFLINE ML KIT OCR)
// ============================================================================

/**
 * V10.1 Blueprint Multimodal Camera Live-Eye Manager:
 * - Scales captured camera frames to <=768px JPEG Base64
 * - Sends `inline_data` (`image/jpeg`) to Gemini Vision (`gemini-2.0-flash:generateContent`)
 *   when online & Gemini API key is configured, identifying real-world objects, bike/scooter parts,
 *   medicine strips, appliances, or documents in concise Hinglish
 * - Seamlessly falls back to on-device ML Kit OCR when offline or when no Gemini key is saved
 */
object CameraLiveEyeManager {

    private const val DEFAULT_VISION_PROMPT =
        "You are Goku V10.1 Camera Live-Eye. Identify whatever object, bike/scooter part, medicine strip, appliance, receipt, or text is visible in this photo and explain it clearly in concise Hinglish (max 3-4 sentences). If a medicine strip is shown, mention its general use and add a short reminder to consult a doctor. If a part or device is shown, explain what it is and how to check or use it."

    private val visionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val visionHttp = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(24, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var attachedContext: Context? = null

    fun attachContext(context: Context) {
        attachedContext = context.applicationContext
    }

    internal fun getAttachedContext(): Context? = attachedContext

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun canUseGeminiVision(context: Context): Boolean {
        val key = GokuVaultRepository.getInstance(context.applicationContext).settings.geminiApiKey.trim()
        return key.isNotBlank() && isOnline(context.applicationContext)
    }

    fun scaleAndEncodeJpegBase64(bitmap: Bitmap, maxDim: Int = 768, quality: Int = 82): String {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val maxSide = maxOf(w, h)
        val scaled = if (maxSide > maxDim) {
            val ratio = maxDim.toFloat() / maxSide.toFloat()
            val nw = (w * ratio).toInt().coerceAtLeast(1)
            val nh = (h * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        } else {
            bitmap
        }
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(45, 95), baos)
        if (scaled !== bitmap) {
            runCatching { scaled.recycle() }
        }
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Synchronously snapshots a scaled 768px JPEG Base64 string from a CameraX/ML Kit `InputImage`
     * while the underlying `ImageProxy` is still open, without mutating plane buffer positions.
     */
    fun extractJpegBase64FromInputImage(image: InputImage): String? {
        return runCatching {
            val bmp = image.bitmapInternal?.let { rotateBitmapIfNeeded(it, image.rotationDegrees, recycleSource = false) }
                ?: image.mediaImage?.let { mediaImg -> decodeMediaImageToBitmap(mediaImg, image.rotationDegrees) }
                ?: return@runCatching null
            val b64 = scaleAndEncodeJpegBase64(bmp, maxDim = 768, quality = 82)
            if (bmp !== image.bitmapInternal) {
                runCatching { bmp.recycle() }
            }
            b64
        }.getOrNull()
    }

    private fun decodeMediaImageToBitmap(mediaImage: Image, rotationDegrees: Int): Bitmap? {
        val decoded: Bitmap? = when (mediaImage.format) {
            ImageFormat.JPEG -> {
                val buf = mediaImage.planes.firstOrNull()?.buffer?.duplicate() ?: return null
                buf.rewind()
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                decodeDownsampledJpeg(bytes, maxDim = 1024)
            }
            ImageFormat.YUV_420_888 -> {
                val nv21 = yuv420ToNv21(mediaImage) ?: return null
                val yuv = YuvImage(nv21, ImageFormat.NV21, mediaImage.width, mediaImage.height, null)
                val out = ByteArrayOutputStream()
                yuv.compressToJpeg(Rect(0, 0, mediaImage.width, mediaImage.height), 85, out)
                decodeDownsampledJpeg(out.toByteArray(), maxDim = 1024)
            }
            else -> null
        }
        return decoded?.let { rotateBitmapIfNeeded(it, rotationDegrees, recycleSource = true) }
    }

    private fun decodeDownsampledJpeg(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while ((bounds.outWidth / sample) > maxDim * 2 || (bounds.outHeight / sample) > maxDim * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, rotationDegrees: Int, recycleSource: Boolean): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (recycleSource && rotated !== bitmap) {
            runCatching { bitmap.recycle() }
        }
        return rotated
    }

    private fun yuv420ToNv21(image: Image): ByteArray? {
        if (image.planes.size < 3) return null
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = ySize / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer.duplicate().apply { rewind() }
        val uBuf = uPlane.buffer.duplicate().apply { rewind() }
        val vBuf = vPlane.buffer.duplicate().apply { rewind() }

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var outPos = 0
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            if (yPixelStride == 1 && rowStart + width <= yBuf.limit()) {
                yBuf.position(rowStart)
                yBuf.get(nv21, outPos, width)
                outPos += width
            } else {
                for (col in 0 until width) {
                    val idx = rowStart + col * yPixelStride
                    nv21[outPos++] = if (idx < yBuf.limit()) yBuf.get(idx) else 0
                }
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIdx = row * vRowStride + col * vPixelStride
                val uIdx = row * uRowStride + col * uPixelStride
                if (outPos + 1 < nv21.size) {
                    nv21[outPos++] = if (vIdx < vBuf.limit()) vBuf.get(vIdx) else 128.toByte()
                    nv21[outPos++] = if (uIdx < uBuf.limit()) uBuf.get(uIdx) else 128.toByte()
                }
            }
        }
        return nv21
    }

    suspend fun analyzeFrameWithGeminiVision(
        context: Context,
        jpegBase64: String,
        ocrHint: String = "",
        userPrompt: String = DEFAULT_VISION_PROMPT
    ): String? = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val vault = GokuVaultRepository.getInstance(appCtx)
        val apiKey = vault.settings.geminiApiKey.trim()
        if (apiKey.isBlank() || !isOnline(appCtx)) return@withContext null

        val promptText = buildString {
            append(userPrompt)
            if (ocrHint.isNotBlank()) {
                append("\nDetected on-image OCR text hint: ")
                append(ocrHint.take(280))
            }
        }

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("text", promptText))
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject()
                                        .put("mime_type", "image/jpeg")
                                        .put("data", jpegBase64)
                                )
                            )
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.3)
                    .put("maxOutputTokens", 320)
            )
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            visionHttp.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || raw.isBlank()) return@use null
                val root = JSONObject(raw)
                val reply = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                    .orEmpty()
                reply.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    suspend fun analyzeBitmap(
        context: Context,
        bitmap: Bitmap,
        prompt: String = DEFAULT_VISION_PROMPT
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        attachContext(appCtx)
        val ocrText = suspendCancellableCoroutine { cont ->
            val input = InputImage.fromBitmap(bitmap, 0)
            CameraLiveEyeOcr.recognizeOfflineOcrOnly(
                image = input,
                onResult = { txt -> if (cont.isActive) cont.resume(txt) },
                onError = { if (cont.isActive) cont.resume("") }
            )
        }
        val b64 = scaleAndEncodeJpegBase64(bitmap)
        val visionReply = analyzeFrameWithGeminiVision(appCtx, b64, ocrHint = ocrText, userPrompt = prompt)
        if (!visionReply.isNullOrBlank()) {
            "[VISION-READY] $visionReply"
        } else if (ocrText.isNotBlank()) {
            ocrText
        } else {
            "[VISION-READY] Offline mode mein image par koi clear text nahi mila. Object identification ke liye Internet + Gemini API key Vault Settings mein add karein."
        }
    }

    internal fun dispatchVisionOrFallback(
        jpegBase64: String?,
        ocrText: String,
        onResult: (String) -> Unit
    ) {
        val ctx = attachedContext
        if (ctx != null && !jpegBase64.isNullOrBlank() && canUseGeminiVision(ctx)) {
            visionScope.launch {
                val visionReply = analyzeFrameWithGeminiVision(
                    context = ctx,
                    jpegBase64 = jpegBase64,
                    ocrHint = ocrText
                )
                val finalOutput = if (!visionReply.isNullOrBlank()) {
                    "[VISION-READY] $visionReply"
                } else {
                    ocrText
                }
                mainHandler.post { onResult(finalOutput) }
            }
        } else {
            onResult(ocrText)
        }
    }
}

object CameraLiveEyeOcr {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    internal fun recognizeOfflineOcrOnly(
        image: InputImage,
        onResult: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        recognizer.process(image)
            .addOnSuccessListener { visionText -> onResult(visionText.text.trim()) }
            .addOnFailureListener { err -> onError(err) }
    }

    fun recognize(
        image: InputImage,
        onResult: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        // Snapshot scaled 768px JPEG Base64 before ImageProxy is closed by caller's callback
        val jpegBase64 = CameraLiveEyeManager.extractJpegBase64FromInputImage(image)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val ocrText = visionText.text.trim()
                CameraLiveEyeManager.dispatchVisionOrFallback(
                    jpegBase64 = jpegBase64,
                    ocrText = ocrText,
                    onResult = onResult
                )
            }
            .addOnFailureListener { err ->
                val ctx = CameraLiveEyeManager.getAttachedContext()
                if (ctx != null && !jpegBase64.isNullOrBlank() && CameraLiveEyeManager.canUseGeminiVision(ctx)) {
                    CameraLiveEyeManager.dispatchVisionOrFallback(
                        jpegBase64 = jpegBase64,
                        ocrText = "",
                        onResult = onResult
                    )
                } else {
                    onError(err)
                }
            }
    }
}
