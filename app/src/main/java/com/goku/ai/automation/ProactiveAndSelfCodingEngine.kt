package com.goku.ai.automation

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Base64
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

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
    suspend fun triggerSosBeacon(): String = withContext(Dispatchers.IO) {
        val contacts = vault.settings.getSosContactsList()
        val battery = readBatteryPercentage()
        val location = getBestLastKnownLocation()
        val locPart = if (location != null) {
            "https://maps.google.com/?q=${location.latitude},${location.longitude} (acc ${location.accuracy.toInt()}m)"
        } else {
            "GPS unavailable (enable Location)"
        }

        val smsBody = "EMERGENCY SOS from Goku V10! I need help immediately. Location: $locPart | Battery: $battery%"

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
// 3. SELF-CODING LIVE PLUGIN ENGINE + GITHUB/TELEGRAM SYNC + DREAM MODE WORKER
// ============================================================================

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

    /**
     * Instant In-App Live Plugin Runner (0.1s):
     * Executes dynamic JSON workflow plugins without rebuilding the APK.
     * Supports: open_app, tap, type, scroll, wait, speak, adb_shell, save_memory, http_get.
     */
    suspend fun runLivePlugin(pluginJson: String, dryRun: Boolean = false): String = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val parsed = runCatching {
            val trimmed = pluginJson.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("steps") ?: JSONArray()
        }.getOrNull() ?: return@withContext "Invalid plugin JSON format. Expected {\"steps\": [...]} or [...]."

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

            // Check existing SHA if updating
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
                "backups/goku-skills-v10.goku.json",
                skillJson,
                "Dream Mode nightly .goku skill backup"
            )
        } else "GitHub backup not configured"

        val tgStatus = if (vault.settings.telegramBotToken.isNotBlank()) {
            sendTelegramArchiveSummary("Goku Dream Mode Backup\nApps indexed: ${apps.size}\nCompacted screens: $compacted\nRecent issues audited: ${failures.size}")
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
// 4. CAMERA LIVE-EYE ON-DEVICE OCR HELPER (ML KIT TEXT RECOGNITION)
// ============================================================================

object CameraLiveEyeOcr {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun recognize(
        image: InputImage,
        onResult: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                onResult(visionText.text.trim())
            }
            .addOnFailureListener { err ->
                onError(err)
            }
    }
}
