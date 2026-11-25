package com.goku.ai.brain

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.goku.ai.adb.LocalAdbEngine
import com.goku.ai.automation.EmergencySosManager
import com.goku.ai.automation.SelfCodingPluginEngine
import com.goku.ai.body.GokuAccessibilityService
import com.goku.ai.body.UniversalAppPilot
import com.goku.ai.data.GokuVaultRepository
import com.goku.ai.data.MemoryNodeEntity
import com.goku.ai.data.RagChunk
import com.goku.ai.voice.GokuEarAndVoiceEngine
import com.goku.ai.voice.GokuKillSwitch
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

// ============================================================================
// 1. UI, SWARM & OFFLINE MODEL DOWNLOAD STATE MODELS
// ============================================================================

enum class BrainVisualState {
    IDLE,
    LISTENING,
    THINKING,
    ACTING,
    STOPPED
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val engineBadge: String = "HYBRID",
    val confidence: Int = 100,
    val timestamp: Long = System.currentTimeMillis()
)

data class PendingConfirmation(
    val id: String = UUID.randomUUID().toString(),
    val promptHinglish: String,
    val actionSummary: String,
    val confidence: Int,
    val onConfirm: suspend () -> String
)

data class OfflineModelPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val approxSizeMb: Int,
    val directUrl: String,
    val isVerifiedDefault: Boolean = false
)

/**
 * Open-access, non-gated public Hugging Face LiteRT/MediaPipe .task model URLs.
 * Zero authentication token or login required.
 */
val OFFLINE_MODEL_PRESETS = listOf(
    OfflineModelPreset(
        id = "qwen25_1_5b",
        name = "Qwen2.5 1.5B Instruct (Q8)",
        subtitle = "Verified Default • Open-access LiteRT — Best Hinglish + JSON actions",
        approxSizeMb = 1560,
        directUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.task",
        isVerifiedDefault = true
    ),
    OfflineModelPreset(
        id = "qwen25_0_5b",
        name = "Qwen2.5 0.5B Instruct (Q8)",
        subtitle = "Verified Open-Access LiteRT — Fast & lightweight Qwen brain (4GB+ RAM)",
        approxSizeMb = 540,
        directUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    ),
    OfflineModelPreset(
        id = "deepseek_r1_qwen_1_5b",
        name = "DeepSeek-R1 Qwen 1.5B (Q8)",
        subtitle = "Community LiteRT Mirror — Strong reasoning (use Default or Import if moved)",
        approxSizeMb = 1580,
        directUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/deepseek_q8_ekv1280.task"
    ),
    OfflineModelPreset(
        id = "smollm_135m",
        name = "SmolLM 135M Instruct (Q8)",
        subtitle = "Community LiteRT Mirror — Ultra-compact test brain (use Default or Import if moved)",
        approxSizeMb = 145,
        directUrl = "https://huggingface.co/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task"
    )
)

sealed class ModelDownloadState {
    data class Idle(
        val partialMb: Float = 0f,
        val lastModelName: String = OFFLINE_MODEL_PRESETS.first().name
    ) : ModelDownloadState()

    data class Downloading(
        val modelName: String,
        val progressPercent: Int,
        val downloadedMb: Float,
        val totalMb: Float,
        val speedMbps: Float = 0f
    ) : ModelDownloadState()

    data class Paused(
        val modelName: String,
        val progressPercent: Int,
        val downloadedMb: Float,
        val totalMb: Float
    ) : ModelDownloadState()

    data class Ready(
        val modelName: String,
        val sizeMb: Float,
        val filePath: String
    ) : ModelDownloadState()

    data class Error(
        val message: String,
        val canResume: Boolean = false
    ) : ModelDownloadState()
}

// ============================================================================
// 2. ADAPTIVE MICRO-PROMPTS & DYNAMIC CONTEXT COMPRESSOR (3X FAST BRAIN)
// ============================================================================

object DynamicContextCompressor {
    fun compressScreenTree(rawScreen: String, maxChars: Int = 420): String {
        if (rawScreen.isBlank()) return ""
        val tokens = rawScreen.split("|")
            .map { it.trim() }
            .filter { it.length in 2..60 }
            .distinct()
        return tokens.joinToString(" | ").take(maxChars)
    }

    fun buildMicroSystemPrompt(screenSummary: String, workingContext: String, memoryHint: String): String {
        val sb = StringBuilder()
        sb.append("You are Goku V10.1 (10.1-godmode), a Jarvis-style personal AI agent on Android. ")
        sb.append("ALWAYS respond with ONLY a single valid compact JSON object (no markdown fences, no extra text): ")
        sb.append("{\"action\":\"CHAT|CALL|SMS|OPEN_APP|ALARM|TORCH|VIRTUAL_APP\",\"target\":\"\",\"message\":\"\",\"reply\":\"concise friendly Hinglish reply\"}. ")
        sb.append("Action rules: ")
        sb.append("CALL -> target=contact name or phone number; ")
        sb.append("SMS -> target=contact name or phone number, message=exact SMS text; ")
        sb.append("OPEN_APP -> target=app name; ")
        sb.append("VIRTUAL_APP -> target=app name when user asks to run/open an app invisibly or in background; ")
        sb.append("ALARM -> target=HH:MM (24h); ")
        sb.append("TORCH -> target=on or off; ")
        sb.append("CHAT -> general Q&A, memory recall, or when no device action is requested. ")
        if (memoryHint.isNotBlank()) sb.append("Vault & RAG facts: ").append(memoryHint.take(480)).append(". ")
        if (screenSummary.isNotBlank()) sb.append("Screen: ").append(compressScreenTree(screenSummary, 240)).append(". ")
        if (workingContext.isNotBlank()) sb.append("Recent: ").append(workingContext.take(280))
        return sb.toString()
    }

    fun buildOnDeviceActionPrompt(
        userPrompt: String,
        memoryHint: String,
        workingContext: String,
        screenSummary: String
    ): String {
        val sb = StringBuilder()
        sb.append("System: You are Goku V10.1 (10.1-godmode), an offline Android AI assistant. ")
        sb.append("Respond ONLY with valid compact JSON: ")
        sb.append("{\"action\":\"CHAT|CALL|SMS|OPEN_APP|ALARM|TORCH|VIRTUAL_APP\",\"target\":\"\",\"message\":\"\",\"reply\":\"concise Hinglish reply\"}. ")
        sb.append("Use CHAT unless user explicitly asks to call, send sms, open an app, run app in background (VIRTUAL_APP), set alarm, or toggle torch.\n")
        if (memoryHint.isNotBlank()) sb.append("Vault: ").append(memoryHint.take(260)).append("\n")
        if (screenSummary.isNotBlank()) sb.append("Screen: ").append(compressScreenTree(screenSummary, 160)).append("\n")
        if (workingContext.isNotBlank()) sb.append("Context: ").append(workingContext.take(200)).append("\n")
        sb.append("User: ").append(userPrompt.take(300)).append("\nAssistant JSON:")
        return sb.toString()
    }
}

// ============================================================================
// 3. 1-CLICK OFFLINE AI MODEL DOWNLOAD MANAGER (HTTP RANGE RESUME + IMPORT)
// ============================================================================

class OfflineModelDownloadManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val modelsDir: File = File(context.applicationContext.filesDir, "models")
    private val tempFile: File = File(modelsDir, "goku_offline.tmp")
    private val finalModelFile: File = File(modelsDir, "goku_offline.task")
    private val metaFile: File = File(modelsDir, "goku_offline.meta.json")

    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle())
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    @Volatile
    var onModelChanged: (() -> Unit)? = null

    private val opMutex = Mutex()
    private val opLock = Any()
    private val transferGeneration = AtomicLong(0L)
    private val isTransferActive = AtomicBoolean(false)
    private var activeJob: Job? = null
    @Volatile
    private var activeCall: Call? = null
    private val pauseRequested = AtomicBoolean(false)

    @Volatile
    private var lastUrl: String = OFFLINE_MODEL_PRESETS.first().directUrl
    @Volatile
    private var lastModelName: String = OFFLINE_MODEL_PRESETS.first().name
    @Volatile
    private var lastKnownTotalBytes: Long = 0L

    init {
        refreshStateFromDisk()
    }

    fun getReadyModelFile(): File? {
        return if (finalModelFile.exists() && finalModelFile.length() > MIN_VALID_MODEL_BYTES) {
            finalModelFile
        } else null
    }

    fun refreshStateFromDisk() {
        modelsDir.mkdirs()
        loadMeta()
        if (finalModelFile.exists() && finalModelFile.length() > MIN_VALID_MODEL_BYTES) {
            val sizeMb = bytesToMb(finalModelFile.length())
            _state.value = ModelDownloadState.Ready(
                modelName = lastModelName,
                sizeMb = sizeMb,
                filePath = finalModelFile.absolutePath
            )
            return
        }
        val partialBytes = if (tempFile.exists()) tempFile.length() else 0L
        if (partialBytes > 0L && lastKnownTotalBytes > 0L) {
            val pct = ((partialBytes * 100L) / lastKnownTotalBytes).toInt().coerceIn(0, 99)
            _state.value = ModelDownloadState.Paused(
                modelName = lastModelName,
                progressPercent = pct,
                downloadedMb = bytesToMb(partialBytes),
                totalMb = bytesToMb(lastKnownTotalBytes)
            )
        } else {
            _state.value = ModelDownloadState.Idle(
                partialMb = bytesToMb(partialBytes),
                lastModelName = lastModelName
            )
        }
    }

    fun startDownload(
        url: String = OFFLINE_MODEL_PRESETS.first().directUrl,
        modelName: String = OFFLINE_MODEL_PRESETS.first().name
    ) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://", ignoreCase = true)) {
            _state.value = ModelDownloadState.Error(
                message = "Only direct HTTPS model URLs are allowed (e.g. https://huggingface.co/.../*.task).",
                canResume = false
            )
            return
        }

        synchronized(opLock) {
            // Prevent duplicate rapid taps while already downloading/importing
            if (isTransferActive.get() || (activeJob?.isActive == true && !pauseRequested.get())) return

            val switchedUrl = cleanUrl != lastUrl
            lastUrl = cleanUrl
            lastModelName = modelName.ifBlank { cleanUrl.substringAfterLast('/').ifBlank { "Offline AI Model" } }
            val myGen = transferGeneration.incrementAndGet()
            launchDownloadJobLocked(myGen = myGen, resetPartialForNewUrl = switchedUrl)
        }
    }

    fun pauseDownload() {
        val pauseGen: Long
        val previousJob: Job?
        synchronized(opLock) {
            pauseGen = transferGeneration.incrementAndGet()
            pauseRequested.set(true)
            activeCall?.cancel()
            previousJob = activeJob
            previousJob?.cancel()
            isTransferActive.set(false)
        }
        scope.launch {
            runCatching { previousJob?.join() }
            opMutex.withLock {
                if (transferGeneration.get() != pauseGen) return@withLock
                val partialBytes = if (tempFile.exists()) tempFile.length() else 0L
                val total = lastKnownTotalBytes.coerceAtLeast(partialBytes)
                val pct = if (total > 0L) ((partialBytes * 100L) / total).toInt().coerceIn(0, 99) else 0
                _state.value = ModelDownloadState.Paused(
                    modelName = lastModelName,
                    progressPercent = pct,
                    downloadedMb = bytesToMb(partialBytes),
                    totalMb = bytesToMb(total)
                )
            }
        }
    }

    fun resumeDownload() {
        synchronized(opLock) {
            if (isTransferActive.get() || (activeJob?.isActive == true && !pauseRequested.get())) return
            loadMeta()
            if (lastUrl.isBlank()) {
                lastUrl = OFFLINE_MODEL_PRESETS.first().directUrl
                lastModelName = OFFLINE_MODEL_PRESETS.first().name
            }
            val myGen = transferGeneration.incrementAndGet()
            launchDownloadJobLocked(myGen = myGen, resetPartialForNewUrl = false)
        }
    }

    fun deleteModel() {
        synchronized(opLock) {
            val deleteGen = transferGeneration.incrementAndGet()
            pauseRequested.set(true)
            activeCall?.cancel()
            val previousJob = activeJob
            previousJob?.cancel()
            isTransferActive.set(false)
            onModelChanged?.invoke()
            activeJob = scope.launch {
                runCatching { previousJob?.join() }
                opMutex.withLock {
                    if (transferGeneration.get() != deleteGen) return@withLock
                    runCatching { finalModelFile.delete() }
                    runCatching { tempFile.delete() }
                    runCatching { metaFile.delete() }
                    lastKnownTotalBytes = 0L
                    _state.value = ModelDownloadState.Idle(
                        partialMb = 0f,
                        lastModelName = OFFLINE_MODEL_PRESETS.first().name
                    )
                }
            }
        }
    }

    /**
     * Imports a user-selected local .task or .bin model via Android Storage Access Framework URI
     * without requiring broad storage permissions.
     */
    fun importLocalModel(uri: Uri) {
        synchronized(opLock) {
            if (isTransferActive.get() || (activeJob?.isActive == true && !pauseRequested.get())) return
            val myGen = transferGeneration.incrementAndGet()
            val previousJob = activeJob
            pauseRequested.set(false)
            isTransferActive.set(true)
            activeJob = scope.launch {
                try {
                    previousJob?.cancelAndJoin()
                    opMutex.withLock {
                        if (transferGeneration.get() != myGen) return@withLock
                        runCatching {
                            modelsDir.mkdirs()
                            val resolver = context.contentResolver
                            val displayName = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "Imported Local Model" }
                                ?: "Imported Local Model"

                            _state.value = ModelDownloadState.Downloading(
                                modelName = displayName,
                                progressPercent = 0,
                                downloadedMb = 0f,
                                totalMb = 0f
                            )

                            val totalBytes = runCatching {
                                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                            }.getOrDefault(-1L)

                            resolver.openInputStream(uri)?.use { input ->
                                FileOutputStream(tempFile, false).use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    var copied = 0L
                                    var lastEmit = 0L
                                    while (isActive && !pauseRequested.get() && transferGeneration.get() == myGen) {
                                        val read = input.read(buffer)
                                        if (read == -1) break
                                        out.write(buffer, 0, read)
                                        copied += read
                                        val now = System.currentTimeMillis()
                                        if (now - lastEmit >= 250L && transferGeneration.get() == myGen) {
                                            lastEmit = now
                                            val pct = if (totalBytes > 0L) ((copied * 100L) / totalBytes).toInt().coerceIn(0, 99) else 50
                                            _state.value = ModelDownloadState.Downloading(
                                                modelName = displayName,
                                                progressPercent = pct,
                                                downloadedMb = bytesToMb(copied),
                                                totalMb = if (totalBytes > 0L) bytesToMb(totalBytes) else bytesToMb(copied)
                                            )
                                        }
                                    }
                                    out.flush()
                                }
                            } ?: throw IllegalStateException("Unable to open selected model file.")

                            if (pauseRequested.get() || !isActive || transferGeneration.get() != myGen) {
                                if (transferGeneration.get() == myGen) {
                                    runCatching { tempFile.delete() }
                                    refreshStateFromDisk()
                                }
                                return@withLock
                            }

                            if (tempFile.length() < MIN_VALID_MODEL_BYTES) {
                                tempFile.delete()
                                throw IllegalStateException("Selected file is too small to be a valid MediaPipe .task/.bin model.")
                            }

                            if (transferGeneration.get() != myGen) return@withLock
                            onModelChanged?.invoke()
                            finalizeAtomicRename(displayName, "local://imported")
                        }.onFailure { err ->
                            if (transferGeneration.get() == myGen) {
                                if (err !is CancellationException) {
                                    _state.value = ModelDownloadState.Error(
                                        message = "Local import failed: ${err.message ?: "Unknown error"}",
                                        canResume = false
                                    )
                                } else {
                                    runCatching { tempFile.delete() }
                                }
                            }
                        }
                    }
                } finally {
                    if (transferGeneration.get() == myGen) {
                        isTransferActive.set(false)
                    }
                }
            }
        }
    }

    private fun launchDownloadJobLocked(myGen: Long, resetPartialForNewUrl: Boolean) {
        val previousJob = activeJob
        val targetUrl = lastUrl
        val targetModelName = lastModelName
        pauseRequested.set(false)
        isTransferActive.set(true)
        activeJob = scope.launch {
            var currentCall: Call? = null
            try {
                previousJob?.cancelAndJoin()
                opMutex.withLock {
                    if (transferGeneration.get() != myGen) return@withLock
                    modelsDir.mkdirs()
                    if (resetPartialForNewUrl && tempFile.exists()) {
                        runCatching { tempFile.delete() }
                        lastKnownTotalBytes = 0L
                    }
                    saveMeta(targetModelName, targetUrl, lastKnownTotalBytes)

                    var existingBytes = if (tempFile.exists()) tempFile.length() else 0L

                    val requestBuilder = Request.Builder()
                        .url(targetUrl)
                        .header("User-Agent", "Goku-10.1-godmode-Android")
                    if (existingBytes > 0L) {
                        requestBuilder.header("Range", "bytes=$existingBytes-")
                    }

                    val call = httpClient.newCall(requestBuilder.build())
                    currentCall = call
                    activeCall = call
                    val response = call.execute()

                    response.use { resp ->
                        if (transferGeneration.get() != myGen) return@withLock

                        if (resp.code == 416) {
                            // Verify whether existing tempFile is already complete before deleting
                            val serverTotal = parseTotalFromContentRange(resp.header("Content-Range"))
                            val expectedTotal = serverTotal ?: lastKnownTotalBytes.takeIf { it > 0L }
                            if (expectedTotal != null &&
                                existingBytes == expectedTotal &&
                                existingBytes >= MIN_VALID_MODEL_BYTES
                            ) {
                                onModelChanged?.invoke()
                                finalizeAtomicRename(targetModelName, targetUrl)
                                return@withLock
                            }
                            runCatching { tempFile.delete() }
                            lastKnownTotalBytes = 0L
                            existingBytes = 0L
                            _state.value = ModelDownloadState.Error(
                                message = "Range mismatch detected — partial file cleared. Tap Resume to restart cleanly.",
                                canResume = true
                            )
                            return@withLock
                        }

                        if (resp.code == 401 || resp.code == 403) {
                            _state.value = ModelDownloadState.Error(
                                message = formatHttpErrorMessage(resp.code),
                                canResume = false
                            )
                            return@withLock
                        }

                        if (resp.code == 404) {
                            runCatching { tempFile.delete() }
                            lastKnownTotalBytes = 0L
                            _state.value = ModelDownloadState.Error(
                                message = formatHttpErrorMessage(404),
                                canResume = false
                            )
                            return@withLock
                        }

                        if (!resp.isSuccessful && resp.code != 206) {
                            _state.value = ModelDownloadState.Error(
                                message = "Download failed (HTTP ${resp.code}). Check internet and tap Resume.",
                                canResume = existingBytes > 0L
                            )
                            return@withLock
                        }

                        val body = resp.body ?: throw IllegalStateException("Empty response body from model server.")
                        val isPartialResume = resp.code == 206 && existingBytes > 0L
                        if (!isPartialResume) {
                            existingBytes = 0L
                        }

                        val contentLength = body.contentLength()
                        val totalBytes = when {
                            isPartialResume && contentLength > 0L -> existingBytes + contentLength
                            !isPartialResume && contentLength > 0L -> contentLength
                            else -> parseTotalFromContentRange(resp.header("Content-Range")) ?: lastKnownTotalBytes
                        }
                        if (totalBytes > 0L) {
                            lastKnownTotalBytes = totalBytes
                            saveMeta(targetModelName, targetUrl, totalBytes)
                        }

                        var downloadedBytes = existingBytes
                        var windowBytes = 0L
                        var lastEmitMs = System.currentTimeMillis()

                        _state.value = ModelDownloadState.Downloading(
                            modelName = targetModelName,
                            progressPercent = computePercent(downloadedBytes, totalBytes),
                            downloadedMb = bytesToMb(downloadedBytes),
                            totalMb = bytesToMb(totalBytes)
                        )

                        body.byteStream().use { input ->
                            FileOutputStream(tempFile, isPartialResume).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (isActive && !pauseRequested.get() && transferGeneration.get() == myGen) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    downloadedBytes += read
                                    windowBytes += read

                                    val now = System.currentTimeMillis()
                                    val deltaMs = now - lastEmitMs
                                    if (deltaMs >= 300L && transferGeneration.get() == myGen) {
                                        val speedMbPerSec = (windowBytes.toFloat() / (1024f * 1024f)) / (deltaMs / 1000f)
                                        _state.value = ModelDownloadState.Downloading(
                                            modelName = targetModelName,
                                            progressPercent = computePercent(downloadedBytes, totalBytes),
                                            downloadedMb = bytesToMb(downloadedBytes),
                                            totalMb = bytesToMb(totalBytes),
                                            speedMbps = speedMbPerSec
                                        )
                                        windowBytes = 0L
                                        lastEmitMs = now
                                    }
                                }
                                output.flush()
                            }
                        }

                        if (transferGeneration.get() != myGen) return@withLock

                        if (pauseRequested.get() || !isActive) {
                            val partial = if (tempFile.exists()) tempFile.length() else 0L
                            _state.value = ModelDownloadState.Paused(
                                modelName = targetModelName,
                                progressPercent = computePercent(partial, totalBytes),
                                downloadedMb = bytesToMb(partial),
                                totalMb = bytesToMb(totalBytes)
                            )
                            return@withLock
                        }

                        if (totalBytes > 0L && downloadedBytes < totalBytes) {
                            _state.value = ModelDownloadState.Paused(
                                modelName = targetModelName,
                                progressPercent = computePercent(downloadedBytes, totalBytes),
                                downloadedMb = bytesToMb(downloadedBytes),
                                totalMb = bytesToMb(totalBytes)
                            )
                            return@withLock
                        }

                        val actualBytes = if (tempFile.exists()) tempFile.length() else 0L
                        if (actualBytes < MIN_VALID_MODEL_BYTES) {
                            val actualMb = bytesToMb(actualBytes)
                            runCatching { tempFile.delete() }
                            _state.value = ModelDownloadState.Error(
                                message = "Downloaded file appears incomplete or invalid (${"%.2f".format(Locale.US, actualMb)} MB).",
                                canResume = false
                            )
                            return@withLock
                        }

                        if (transferGeneration.get() != myGen) return@withLock
                        onModelChanged?.invoke()
                        finalizeAtomicRename(targetModelName, targetUrl)
                    }
                }
            } catch (ce: CancellationException) {
                if (!pauseRequested.get() && transferGeneration.get() == myGen) {
                    refreshStateFromDisk()
                }
            } catch (e: Exception) {
                if (pauseRequested.get() || transferGeneration.get() != myGen) return@launch
                val canResume = tempFile.exists() && tempFile.length() > 0L
                _state.value = ModelDownloadState.Error(
                    message = "Network interrupted: ${e.message ?: "Check connection"}. ${if (canResume) "Progress saved — tap Resume." else ""}",
                    canResume = canResume
                )
            } finally {
                if (activeCall === currentCall) {
                    activeCall = null
                }
                if (transferGeneration.get() == myGen) {
                    isTransferActive.set(false)
                }
            }
        }
    }

    private fun finalizeAtomicRename(modelName: String, sourceUrl: String) {
        if (finalModelFile.exists()) {
            finalModelFile.delete()
        }
        val renamed = tempFile.renameTo(finalModelFile)
        if (!renamed) {
            tempFile.copyTo(finalModelFile, overwrite = true)
            tempFile.delete()
        }
        val finalSize = finalModelFile.length()
        saveMeta(modelName, sourceUrl, finalSize)
        _state.value = ModelDownloadState.Ready(
            modelName = modelName,
            sizeMb = bytesToMb(finalSize),
            filePath = finalModelFile.absolutePath
        )
    }

    private fun saveMeta(name: String, url: String, totalBytes: Long) {
        runCatching {
            modelsDir.mkdirs()
            val obj = JSONObject().apply {
                put("name", name)
                put("url", url)
                put("totalBytes", totalBytes)
            }
            metaFile.writeText(obj.toString())
        }
    }

    private fun loadMeta() {
        runCatching {
            if (!metaFile.exists()) return
            val obj = JSONObject(metaFile.readText())
            lastModelName = obj.optString("name", lastModelName)
            lastUrl = obj.optString("url", lastUrl)
            lastKnownTotalBytes = obj.optLong("totalBytes", lastKnownTotalBytes)
        }
    }

    private fun parseTotalFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        return header.substringAfter('/', "").trim().toLongOrNull()
    }

    private fun computePercent(downloaded: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
    }

    private fun bytesToMb(bytes: Long): Float = bytes.toFloat() / (1024f * 1024f)

    companion object {
        private const val MIN_VALID_MODEL_BYTES = 1L * 1024L * 1024L // 1 MB sanity threshold

        fun formatHttpErrorMessage(code: Int): String = when (code) {
            404 -> "HTTP 404: Model file URL server par nahi mila. Kripya verified default '${OFFLINE_MODEL_PRESETS.first().name}' preset chunein ya 'Import' button se local .task/.bin file (SAF) select karein."
            401, 403 -> "HTTP $code: Gated repo requires login. Kripya verified open-access '${OFFLINE_MODEL_PRESETS.first().name}' preset ya local .task/.bin SAF Import use karein."
            else -> "Download failed (HTTP $code). Check internet and tap Resume."
        }

        @Volatile
        private var INSTANCE: OfflineModelDownloadManager? = null

        fun getInstance(context: Context): OfflineModelDownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OfflineModelDownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

// ============================================================================
// 4. REAL ON-DEVICE MEDIAPIPE / LITERT GENAI LLM ENGINE (15s AUTO-UNLOAD)
// ============================================================================

data class OnDeviceLlmResult(
    val reply: String,
    val badge: String,
    val action: String = "CHAT", // CHAT, CALL, SMS, OPEN_APP, ALARM, TORCH, VIRTUAL_APP
    val target: String = "",
    val extra: String = "" // Holds SMS message body or action extra metadata
)

/**
 * Shared structured JSON action parser for both Online Multi-Model Router (Gemini/Groq/DeepSeek/OpenRouter)
 * and Offline MediaPipe GenAI LLM outputs. Safely strips markdown code fences and extracts balanced JSON.
 */
object StructuredActionParser {
    private val SUPPORTED_ACTIONS = setOf(
        "CHAT", "CALL", "SMS", "OPEN_APP", "ALARM", "TORCH", "VIRTUAL_APP"
    )

    fun parse(raw: String, badge: String): OnDeviceLlmResult {
        val cleaned = stripMarkdownCodeFences(raw).trim()
        val jsonCandidate = extractBalancedJsonObject(cleaned)
        if (jsonCandidate != null) {
            val parsed = runCatching { JSONObject(jsonCandidate) }.getOrNull()
            if (parsed != null) {
                val rawAction = parsed.optString("action", "CHAT").uppercase(Locale.ROOT).trim()
                val action = if (rawAction in SUPPORTED_ACTIONS) rawAction else "CHAT"
                val target = parsed.optString("target", "").trim()
                val messageOrExtra = parsed.optString("message", "").trim()
                    .ifBlank { parsed.optString("extra", "").trim() }
                val reply = parsed.optString("reply", "").trim()
                    .ifBlank {
                        if (action == "CHAT") cleaned.take(520)
                        else "Action '$action' process kar raha hoon boss."
                    }
                return OnDeviceLlmResult(
                    reply = reply,
                    badge = badge,
                    action = action,
                    target = target,
                    extra = messageOrExtra
                )
            }
        }
        return OnDeviceLlmResult(
            reply = cleaned.ifBlank { raw.trim() }.take(600),
            badge = badge,
            action = "CHAT"
        )
    }

    private fun stripMarkdownCodeFences(text: String): String {
        val trimmed = text.trim()
        val fenceRegex = Regex("^```(?:json|JSON)?\\s*\\n?([\\s\\S]*?)\\n?```$")
        val match = fenceRegex.find(trimmed)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        return trimmed
            .replace(Regex("```(?:json|JSON)?", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()
    }

    private fun extractBalancedJsonObject(text: String): String? {
        var firstValidFallback: String? = null
        var firstReplyFallback: String? = null
        var idx = 0
        while (idx < text.length) {
            val start = text.indexOf('{', idx)
            if (start < 0) break
            var depth = 0
            var inString = false
            var escaped = false
            var endPos = -1
            for (i in start until text.length) {
                val ch = text[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\' && inString) {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (ch == '{') depth++
                    else if (ch == '}') {
                        depth--
                        if (depth == 0) {
                            endPos = i
                            break
                        }
                    }
                }
            }
            if (endPos > start) {
                val candidate = text.substring(start, endPos + 1)
                val parsed = runCatching { JSONObject(candidate) }.getOrNull()
                if (parsed != null) {
                    if (parsed.has("action")) {
                        return candidate
                    }
                    if (firstReplyFallback == null && parsed.has("reply")) {
                        firstReplyFallback = candidate
                    }
                    if (firstValidFallback == null) {
                        firstValidFallback = candidate
                    }
                }
                idx = endPos + 1
            } else {
                idx = start + 1
            }
        }
        return firstReplyFallback ?: firstValidFallback
    }
}

class OnDeviceLlmEngine(
    private val context: Context,
    private val vault: GokuVaultRepository,
    private val downloadManager: OfflineModelDownloadManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineMutex = Mutex()
    private val timerLock = Any()
    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null
    @Volatile
    private var unloadTimerJob: Job? = null

    private val _isLoadedInRam = MutableStateFlow(false)
    val isLoadedInRam: StateFlow<Boolean> = _isLoadedInRam.asStateFlow()

    private val stopHook: () -> Unit = {
        scope.launch { unloadNow() }
    }
    private val modelChangedHook: () -> Unit = {
        scope.launch { unloadNow() }
    }

    init {
        downloadManager.onModelChanged = modelChangedHook
        GokuKillSwitch.registerStopHook(stopHook)
    }

    fun close() {
        GokuKillSwitch.unregisterStopHook(stopHook)
        if (downloadManager.onModelChanged === modelChangedHook) {
            downloadManager.onModelChanged = null
        }
        cancelIdleUnloadTimer()
        scope.launch { unloadNow() }
        scope.cancel()
    }

    private fun resolveActiveModelFile(): File? {
        // 1. Primary: 1-click downloaded/imported model in filesDir/models/goku_offline.task
        downloadManager.getReadyModelFile()?.let { return it }
        // 2. Secondary: user-configured custom file path in Vault settings
        val customPath = vault.settings.localModelPath.trim()
        if (customPath.isNotBlank()) {
            val f = File(customPath)
            if (f.exists() && f.isFile && f.length() > 1024L * 1024L &&
                (f.name.endsWith(".task", true) || f.name.endsWith(".bin", true))
            ) {
                return f
            }
        }
        return null
    }

    fun isModelAvailable(): Boolean = resolveActiveModelFile() != null

    private fun cancelIdleUnloadTimer() {
        synchronized(timerLock) {
            unloadTimerJob?.cancel()
            unloadTimerJob = null
        }
    }

    suspend fun unloadNow(fromTimer: Boolean = false) {
        if (!fromTimer) {
            cancelIdleUnloadTimer()
        }
        engineMutex.withLock {
            runCatching { llmInference?.close() }
            llmInference = null
            loadedModelPath = null
            _isLoadedInRam.value = false
        }
    }

    private fun scheduleFifteenSecondIdleUnload() {
        synchronized(timerLock) {
            unloadTimerJob?.cancel()
            lateinit var createdJob: Job
            createdJob = scope.launch {
                delay(IDLE_UNLOAD_MS)
                unloadNow(fromTimer = true)
                synchronized(timerLock) {
                    if (unloadTimerJob === createdJob) {
                        unloadTimerJob = null
                    }
                }
            }
            unloadTimerJob = createdJob
        }
    }

    /**
     * Runs real MediaPipe LlmInference on-demand when a downloaded `.task`/`.bin` model exists,
     * with strict 15-second idle RAM auto-unload. Gracefully falls back to built-in offline
     * Hinglish NLU + Vault + RAG engine when no model is downloaded yet or native init fails.
     */
    suspend fun generateOfflineResponse(
        userPrompt: String,
        memories: List<MemoryNodeEntity>,
        ragHits: List<RagChunk>,
        screenSummary: String
    ): OnDeviceLlmResult = withContext(Dispatchers.IO) {
        val modelFile = resolveActiveModelFile()
        if (modelFile != null) {
            val memoryHint = memories.take(3).joinToString("; ") { "${it.key}=${it.value}" }
            val ragHint = ragHits.firstOrNull()?.let { "Doc(${it.docName}): ${it.text.take(150)}" }.orEmpty()
            val combinedHint = listOf(memoryHint, ragHint).filter { it.isNotBlank() }.joinToString(" | ")
            val structuredPrompt = DynamicContextCompressor.buildOnDeviceActionPrompt(
                userPrompt = userPrompt,
                memoryHint = combinedHint,
                workingContext = vault.workingMemory.buildCompressedContext(),
                screenSummary = screenSummary
            )

            val rawLlmOutput = runMediaPipeInference(modelFile, structuredPrompt)
            if (!rawLlmOutput.isNullOrBlank()) {
                return@withContext parseStructuredLlmOutput(rawLlmOutput)
            }
        }

        // Graceful fallback before model download (zero crashes)
        generateBuiltInOfflineFallback(userPrompt, memories, ragHits, screenSummary)
    }

    private suspend fun runMediaPipeInference(modelFile: File, prompt: String): String? {
        return engineMutex.withLock {
            cancelIdleUnloadTimer()
            try {
                if (llmInference == null || loadedModelPath != modelFile.absolutePath) {
                    runCatching { llmInference?.close() }
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.absolutePath)
                        .setMaxTokens(512)
                        .setMaxTopK(40)
                        .build()
                    llmInference = LlmInference.createFromOptions(context.applicationContext, options)
                    loadedModelPath = modelFile.absolutePath
                    _isLoadedInRam.value = true
                }
                val engine = llmInference ?: return@withLock null
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(40)
                    .setTemperature(0.65f)
                    .setRandomSeed(42)
                    .build()
                LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
                    session.addQueryChunk(prompt)
                    session.generateResponse()?.trim()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                runCatching { llmInference?.close() }
                llmInference = null
                loadedModelPath = null
                _isLoadedInRam.value = false
                null
            } finally {
                if (llmInference != null && !GokuKillSwitch.isStopped.value) {
                    scheduleFifteenSecondIdleUnload()
                }
            }
        }
    }

    private fun parseStructuredLlmOutput(raw: String): OnDeviceLlmResult {
        return StructuredActionParser.parse(raw, "MEDIAPIPE-LOCAL")
    }

    private fun generateBuiltInOfflineFallback(
        userPrompt: String,
        memories: List<MemoryNodeEntity>,
        ragHits: List<RagChunk>,
        screenSummary: String
    ): OnDeviceLlmResult {
        val lower = userPrompt.lowercase(Locale.ROOT).trim()

        // 1. Check Vault Knowledge Graph direct answer
        if (memories.isNotEmpty() && (lower.contains("kya hai") || lower.contains("what is") || lower.contains("yaad") || lower.contains("mera") || lower.contains("my "))) {
            val top = memories.first()
            return OnDeviceLlmResult("Vault se mila: ${top.key} = ${top.value}.", "OFFLINE-VAULT-NLU")
        }

        // 2. Check Local RAG hits
        if (ragHits.isNotEmpty()) {
            val hit = ragHits.first()
            return OnDeviceLlmResult("Offline Document Brain (${hit.docName}): ${hit.text.take(240)}", "OFFLINE-RAG-NLU")
        }

        // 3. Natural Hindi/Hinglish Conversational & Command Parity
        val reply = when {
            lower.contains("hello") || lower.contains("hi") || lower.contains("namaste") || lower.contains("kaise ho") ->
                "Namaste boss! Main Goku V10.1 (10.1-godmode) hoon — 100% ready offline aur online dono mode mein. Bolo kya kaam karna hai?"

            lower.contains("time") || lower.contains("samay") || lower.contains("baje") -> {
                val now = SimpleDateFormat("hh:mm a, EEEE dd MMM", Locale.getDefault()).format(Date())
                "Abhi $now baj rahe hain boss."
            }

            lower.contains("screen") && (lower.contains("padh") || lower.contains("read") || lower.contains("kya hai")) -> {
                if (screenSummary.isNotBlank()) {
                    "Current screen par mujhe yeh dikh raha hai: ${screenSummary.take(260)}"
                } else {
                    "Screen read karne ke liye Setup tab mein Goku Accessibility Service ON kar dijiye."
                }
            }

            lower.contains("kaun ho") || lower.contains("who are you") || lower.contains("capabilities") ->
                "Main Goku V10.1 (10.1-godmode) hoon — aapka personal Jarvis agent. Main Qwen2.5/SmolLM2 offline AI, Camera Live-Eye Vision, torch, apps, calls, SMS, alarm, Local ADB Virtual Display, Accessibility taps, Headless WebView, OTP auto-fill, expense tracking, Anti-Theft Intruder Guard, aur Emergency SOS bina internet ke bhi chala sakta hoon."

            lower.contains("thank") || lower.contains("shukriya") || lower.contains("dhanyawad") ->
                "Hamesha haazir hoon boss! Jab bhi zaroorat ho bas 'Hey Goku' bol dena."

            else ->
                "Maine offline samjha: \"$userPrompt\". Full local AI chat ke liye Vault tab mein 'Latest Offline AI Brain (1-Tap Download)' se Qwen2.5 ya SmolLM2 download karein, ya directly phone commands bolein jaise 'Torch on', 'Open WhatsApp', 'Call Mom', 'SMS Mom hello', 'Expense summary', ya 'Phone Doctor'."
        }
        return OnDeviceLlmResult(reply = reply, badge = "OFFLINE-NLU-FALLBACK")
    }

    companion object {
        private const val IDLE_UNLOAD_MS = 15_000L
    }
}

// ============================================================================
// 5. ONLINE MULTI-MODEL ROUTER WITH AUTOMATIC QUOTA FAILOVER
// ============================================================================

class OnlineMultiModelRouter(private val vault: GokuVaultRepository) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .build()

    private val _activeProvider = MutableStateFlow("OFFLINE-READY")
    val activeProvider: StateFlow<String> = _activeProvider.asStateFlow()

    suspend fun queryWithFailover(
        userPrompt: String,
        systemPrompt: String
    ): OnDeviceLlmResult? = withContext(Dispatchers.IO) {
        val geminiKey = vault.settings.geminiApiKey
        val groqKey = vault.settings.groqApiKey
        val deepSeekKey = vault.settings.deepSeekApiKey
        val openRouterKey = vault.settings.openRouterApiKey
        val nvidiaNimKey = vault.settings.nvidiaNimApiKey

        // Provider 1: Google Gemini Free Tier REST API
        if (geminiKey.isNotBlank() && isActive && !GokuKillSwitch.isStopped.value) {
            callGeminiRest(geminiKey, userPrompt, systemPrompt)?.let {
                _activeProvider.value = "GEMINI-CLOUD"
                return@withContext StructuredActionParser.parse(it, "GEMINI-CLOUD")
            }
        }

        // Provider 2: Groq Free Tier OpenAI-Compatible Endpoint
        if (groqKey.isNotBlank() && isActive && !GokuKillSwitch.isStopped.value) {
            callOpenAiCompatible(
                endpoint = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = groqKey,
                model = "llama-3.3-70b-versatile",
                userPrompt = userPrompt,
                systemPrompt = systemPrompt
            )?.let {
                _activeProvider.value = "GROQ-FAILOVER"
                return@withContext StructuredActionParser.parse(it, "GROQ-FAILOVER")
            }
        }

        // Provider 3: DeepSeek OpenAI-Compatible Endpoint
        if (deepSeekKey.isNotBlank() && isActive && !GokuKillSwitch.isStopped.value) {
            callOpenAiCompatible(
                endpoint = "https://api.deepseek.com/v1/chat/completions",
                apiKey = deepSeekKey,
                model = "deepseek-chat",
                userPrompt = userPrompt,
                systemPrompt = systemPrompt
            )?.let {
                _activeProvider.value = "DEEPSEEK-FAILOVER"
                return@withContext StructuredActionParser.parse(it, "DEEPSEEK-FAILOVER")
            }
        }

        // Provider 4: OpenRouter Free Model Failover
        if (openRouterKey.isNotBlank() && isActive && !GokuKillSwitch.isStopped.value) {
            callOpenAiCompatible(
                endpoint = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = openRouterKey,
                model = "meta-llama/llama-3.3-70b-instruct:free",
                userPrompt = userPrompt,
                systemPrompt = systemPrompt
            )?.let {
                _activeProvider.value = "OPENROUTER-FREE"
                return@withContext StructuredActionParser.parse(it, "OPENROUTER-FREE")
            }
        }

        // Provider 5: NVIDIA NIM Free Tier Failover (Blueprint V10.1)
        if (nvidiaNimKey.isNotBlank() && isActive && !GokuKillSwitch.isStopped.value) {
            callOpenAiCompatible(
                endpoint = "https://integrate.api.nvidia.com/v1/chat/completions",
                apiKey = nvidiaNimKey,
                model = "meta/llama-3.3-70b-instruct",
                userPrompt = userPrompt,
                systemPrompt = systemPrompt
            )?.let {
                _activeProvider.value = "NVIDIA-NIM"
                return@withContext StructuredActionParser.parse(it, "NVIDIA-NIM")
            }
        }

        _activeProvider.value = "OFFLINE-PARITY"
        null
    }

    private suspend fun executeCancellable(req: Request): Response? {
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    } else {
                        runCatching { response.close() }
                    }
                }
            })
        }
    }

    private suspend fun callGeminiRest(apiKey: String, userPrompt: String, systemPrompt: String): String? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", "$systemPrompt\n\nUser: $userPrompt")))
            }))
        }
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = executeCancellable(req) ?: return null
        return runCatching {
            resp.use { r ->
                if (!r.isSuccessful) return@runCatching null
                val body = JSONObject(r.body?.string() ?: return@runCatching null)
                body.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private suspend fun callOpenAiCompatible(
        endpoint: String,
        apiKey: String,
        model: String,
        userPrompt: String,
        systemPrompt: String
    ): String? {
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", 320)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = executeCancellable(req) ?: return null
        return runCatching {
            resp.use { r ->
                if (!r.isSuccessful) return@runCatching null
                val body = JSONObject(r.body?.string() ?: return@runCatching null)
                body.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}

// ============================================================================
// 6. SELF-CORRECTING MULTI-AGENT SWARM + 0s FAST REFLEX + SAFETY CONFIRMATION
// ============================================================================

class HybridBrainAndSwarm(
    private val context: Context,
    private val vault: GokuVaultRepository,
    private val adbEngine: LocalAdbEngine,
    private val voiceEngine: GokuEarAndVoiceEngine,
    private val pilot: UniversalAppPilot,
    private val sosManager: EmergencySosManager,
    private val pluginEngine: SelfCodingPluginEngine
) {
    val modelDownloader: OfflineModelDownloadManager = OfflineModelDownloadManager.getInstance(context)
    val offlineLlm: OnDeviceLlmEngine = OnDeviceLlmEngine(context, vault, modelDownloader)
    val onlineRouter = OnlineMultiModelRouter(vault)

    private val _brainState = MutableStateFlow(BrainVisualState.IDLE)
    val brainState: StateFlow<BrainVisualState> = _brainState.asStateFlow()

    private val _messages = MutableStateFlow(
        listOf(
            ChatMessage(
                isUser = false,
                text = "Goku V10.1 (10.1-godmode) online & MediaPipe offline hybrid core ready hai boss! 'Hey Goku' bolo ya neeche command likho. Vault tab se 1-tap Qwen2.5 1.5B / 0.5B Offline AI download kar sakte ho.",
                engineBadge = "V10.1-CORE",
                confidence = 100
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val commandMutex = Mutex()

    @Volatile
    private var torchEnabled = false

    private fun isDeviceLocked(): Boolean = runCatching {
        keyguardManager?.isKeyguardLocked == true
    }.getOrDefault(false)

    private val stopHook: () -> Unit = {
        _pendingConfirmation.value = null
        _brainState.value = BrainVisualState.STOPPED
    }

    init {
        GokuKillSwitch.registerStopHook(stopHook)
    }

    private val preWarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastPreWarmAtMs: Long = 0L

    /**
     * V10.1 Speculative Pre-Warming:
     * Invoked the millisecond "Hey Goku" wake-word triggers, pre-reading the active Accessibility
     * screen tree and refreshing network state in the background before the user finishes speaking.
     */
    fun preWarmForWakeWord() {
        val now = System.currentTimeMillis()
        if (now - lastPreWarmAtMs < 1_500L) return
        lastPreWarmAtMs = now
        preWarmScope.launch {
            runCatching { isNetworkAvailable() }
            val liveScreen = runCatching {
                GokuAccessibilityService.instance?.captureLiveScreenText()
            }.getOrNull()
            if (!liveScreen.isNullOrBlank()) {
                vault.workingMemory.lastScreenSummary =
                    DynamicContextCompressor.compressScreenTree(liveScreen, maxChars = 360)
            }
        }
    }

    fun close() {
        GokuKillSwitch.unregisterStopHook(stopHook)
        preWarmScope.cancel()
        offlineLlm.close()
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        _isOnline.value = online
        return online
    }

    suspend fun confirmPendingAction(approved: Boolean): String {
        GokuKillSwitch.registerJob(coroutineContext.job)
        return commandMutex.withLock {
            if (GokuKillSwitch.isStopped.value) return@withLock "Stopped."
            confirmPendingActionLocked(approved)
        }
    }

    private suspend fun confirmPendingActionLocked(approved: Boolean): String {
        if (approved && isDeviceLocked()) {
            val lockMsg = "Security Lock: Phone locked hai boss. Sensitive action confirm karne ke liye pehle device unlock karein."
            vault.logAction("CRITIC", "PendingConfirmation", "Blocked voice confirmation while keyguard locked", 100, "STOPPED")
            appendGokuMessage(lockMsg, "LOCK-SCREEN-GUARD", 100)
            voiceEngine.speak(lockMsg)
            return lockMsg
        }
        val pending = _pendingConfirmation.getAndUpdate { null }
            ?: return "Koi pending confirmation nahi hai."
        return try {
            if (approved) {
                if (GokuKillSwitch.isStopped.value) return "Stopped."
                _brainState.value = BrainVisualState.ACTING
                val res = pending.onConfirm()
                appendGokuMessage(res, "SWARM-CONFIRMED", pending.confidence)
                voiceEngine.speak(res)
                res
            } else {
                val cancelMsg = "Theek hai boss, action cancel kar diya: ${pending.actionSummary}"
                vault.logAction("CRITIC", pending.actionSummary, "User rejected via Haan/Nahi gate", 100, "STOPPED")
                appendGokuMessage(cancelMsg, "SAFETY-GATE", 100)
                voiceEngine.speak(cancelMsg)
                cancelMsg
            }
        } finally {
            if (!GokuKillSwitch.isStopped.value && _brainState.value != BrainVisualState.STOPPED) {
                _brainState.value = BrainVisualState.IDLE
            }
        }
    }

    suspend fun processCommand(rawInput: String, speakReply: Boolean = true): String {
        val startMs = System.currentTimeMillis()
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ""

        // 0. Highest-priority voice emergency stop check (bypasses commandMutex to abort immediately)
        if (GokuKillSwitch.isStopPhrase(trimmed)) {
            GokuKillSwitch.triggerStop("Command: $trimmed")
            val stopReply = "Ruk gaya boss! Saare active actions band kar diye gaye hain."
            appendUserMessage(trimmed)
            appendGokuMessage(stopReply, "KILL-SWITCH", 100)
            if (speakReply) voiceEngine.speak(stopReply, urgent = true)
            return stopReply
        }

        // Register caller coroutine Job before acquiring commandMutex so "Goku stop" cancels both running and queued commands
        GokuKillSwitch.registerJob(coroutineContext.job)

        return commandMutex.withLock {
            try {
                // 0B. Direct Multimodal Gemini Vision Live-Eye Result passthrough (guard against late post-stop callbacks)
                if (trimmed.startsWith("[VISION-READY]")) {
                    if (GokuKillSwitch.isStopped.value) return@withLock "Stopped."
                    val visionText = trimmed.removePrefix("[VISION-READY]").trim()
                    appendUserMessage("Camera Live-Eye Vision Scan")
                    vault.workingMemory.addTurn("GOKU", visionText)
                    vault.logAction("EXECUTOR", "Camera Live-Eye Vision", visionText, 97, "OK", System.currentTimeMillis() - startMs)
                    appendGokuMessage(visionText, "GEMINI-VISION", 97)
                    if (speakReply) voiceEngine.speak(visionText)
                    return@withLock visionText
                }

                GokuKillSwitch.reset()
                appendUserMessage(trimmed)

                // Check if awaiting Hindi "Haan" / "Nahi" Safety Confirmation
                val pending = _pendingConfirmation.value
                if (pending != null) {
                    val low = trimmed.lowercase(Locale.ROOT)
                    if (low in listOf("haan", "han", "ha", "yes", "ok", "confirm", "kar do", "bhej do", "हां", "हाँ")) {
                        return@withLock confirmPendingActionLocked(true)
                    }
                    if (low in listOf("nahi", "na", "no", "cancel", "mat karo", "ruko", "नहीं")) {
                        return@withLock confirmPendingActionLocked(false)
                    }
                    // Clear stale pending confirmation when a new unrelated command arrives
                    _pendingConfirmation.value = null
                }

                // Resolve pronouns against 15-turn Working Memory
                val resolvedInput = vault.workingMemory.resolvePronouns(trimmed)
                vault.workingMemory.addTurn("USER", resolvedInput)

                _brainState.value = BrainVisualState.THINKING
                val dryRun = vault.settings.dryRunMode

                // --------------------------------------------------------------------
                // TIER 2: 0-SECOND FAST REFLEX ENGINE (INSTANT LOCAL EXECUTION)
                // --------------------------------------------------------------------
                val reflexResult = tryFastReflex(resolvedInput, dryRun)
                if (GokuKillSwitch.isStopped.value) return@withLock "Stopped."
                if (reflexResult != null) {
                    val (reply, badge, confidence) = reflexResult
                    val elapsed = System.currentTimeMillis() - startMs
                    vault.logAction("REFLEX", resolvedInput, reply, confidence, if (dryRun) "DRY_RUN" else "OK", elapsed)
                    vault.workingMemory.addTurn("GOKU", reply)
                    appendGokuMessage(reply, badge, confidence)
                    if (speakReply) voiceEngine.speak(reply)
                    return@withLock reply
                }

                // --------------------------------------------------------------------
                // CHECK LEARNED .GOKU SKILLS (WITH A/B ENGINE BENCHMARKING)
                // --------------------------------------------------------------------
                val matchedSkill = vault.findMatchingSkill(resolvedInput)
                if (matchedSkill != null) {
                    if (GokuKillSwitch.isStopped.value) return@withLock "Stopped."
                    if (isDeviceLocked()) {
                        val lockMsg = "Security Lock: Phone locked hai boss. Skill '${matchedSkill.name}' chalane ke liye pehle device unlock karein."
                        vault.logAction("CRITIC", resolvedInput, "Blocked skill while keyguard locked", 100, "STOPPED")
                        appendGokuMessage(lockMsg, "LOCK-SCREEN-GUARD", 100)
                        if (speakReply) voiceEngine.speak(lockMsg)
                        return@withLock lockMsg
                    }
                    _brainState.value = BrainVisualState.ACTING
                    val skillStart = System.currentTimeMillis()
                    val (ok, detail) = pilot.executeStepsJson(matchedSkill.stepsJson, dryRun = dryRun)
                    if (GokuKillSwitch.isStopped.value) return@withLock "Stopped."
                    val skillMs = System.currentTimeMillis() - skillStart
                    val usedAdb = !adbEngine.useAccessibilityFallback.value
                    vault.recordSkillBenchmark(matchedSkill, usedAdb, skillMs, ok)
                    val reply = if (ok) "Skill '${matchedSkill.name}' complete (${skillMs}ms): $detail"
                    else "Skill '${matchedSkill.name}' partial issue: $detail"
                    vault.logAction("EXECUTOR", "Skill:${matchedSkill.name}", reply, if (ok) 96 else 65, if (ok) "OK" else "WARN", skillMs)
                    appendGokuMessage(reply, "SKILL-MACRO", if (ok) 96 else 65)
                    if (speakReply) voiceEngine.speak(reply)
                    return@withLock reply
                }

                // --------------------------------------------------------------------
                // TIER 3: MULTI-AGENT SWARM (PLANNER -> EXECUTOR -> CRITIC) + HYBRID AI
                // --------------------------------------------------------------------
                val screenSummary = pilot.captureCurrentScreenSummary()
                val memories = vault.searchMemories(resolvedInput)
                val ragHits = vault.ragBrain.queryPersistentFts(resolvedInput, topK = 5)
                val memoryHint = memories.take(4).joinToString("; ") { "${it.key}=${it.value}" }
                val ragHint = ragHits.take(3).joinToString("; ") { "${it.docName}: ${it.text.take(140)}" }
                val combinedVaultHint = listOf(memoryHint, ragHint).filter { it.isNotBlank() }.joinToString(" | ")
                val systemPrompt = DynamicContextCompressor.buildMicroSystemPrompt(
                    screenSummary = screenSummary,
                    workingContext = vault.workingMemory.buildCompressedContext(),
                    memoryHint = combinedVaultHint
                )

                vault.logAction(
                    stage = "PLANNER",
                    command = resolvedInput,
                    detail = "v10.1-godmode | Online=${isNetworkAvailable()}, LocalModel=${offlineLlm.isModelAvailable()}, VirtualDisplay=${adbEngine.virtualDisplayRunner.isActive.value}(#${adbEngine.virtualDisplayRunner.virtualDisplayId.value ?: -1}), Memories=${memories.size}, PersistentRAG=${ragHits.size}",
                    confidence = 92,
                    status = "OK"
                )

                val structuredResult: OnDeviceLlmResult = if (isNetworkAvailable()) {
                    onlineRouter.queryWithFailover(resolvedInput, systemPrompt)
                        ?: offlineLlm.generateOfflineResponse(resolvedInput, memories, ragHits, screenSummary)
                } else {
                    offlineLlm.generateOfflineResponse(resolvedInput, memories, ragHits, screenSummary)
                }

                if (GokuKillSwitch.isStopped.value) return@withLock "Stopped."

                if (isDeviceLocked() && structuredResult.action in SENSITIVE_LOCK_GUARD_ACTIONS) {
                    val lockMsg = "Security Lock: Phone locked hai boss. ${structuredResult.action} ke liye pehle device unlock karein."
                    vault.logAction("CRITIC", resolvedInput, "Blocked ${structuredResult.action} while keyguard locked", 100, "STOPPED")
                    vault.workingMemory.addTurn("GOKU", lockMsg)
                    appendGokuMessage(lockMsg, "LOCK-SCREEN-GUARD", 100)
                    if (speakReply) voiceEngine.speak(lockMsg)
                    return@withLock lockMsg
                }

                val (aiReply, providerBadge) = executeStructuredAction(structuredResult, resolvedInput, dryRun)
                if (GokuKillSwitch.isStopped.value) return@withLock "Stopped."

                // Critic verification pass
                val elapsed = System.currentTimeMillis() - startMs
                vault.logAction("CRITIC", resolvedInput, "Action=${structuredResult.action} verified via $providerBadge", 94, "OK", elapsed)
                vault.workingMemory.addTurn("GOKU", aiReply)
                appendGokuMessage(aiReply, providerBadge, 94)
                if (speakReply) voiceEngine.speak(aiReply)
                aiReply
            } finally {
                if (!GokuKillSwitch.isStopped.value && _brainState.value != BrainVisualState.STOPPED) {
                    _brainState.value = BrainVisualState.IDLE
                }
            }
        }
    }

    /**
     * Bug #1 Helper: Generates a concise Hinglish negotiation reply + hang-up decision
     * for SmartCallScreenerService's Half-Duplex 2-Way Caller Negotiation Loop.
     */
    suspend fun generateCallNegotiationReply(
        callerNumber: String,
        policy: String,
        conversationHistory: String,
        callerSaid: String
    ): Pair<String, Boolean> {
        val lower = callerSaid.lowercase(Locale.ROOT)
        val systemPrompt = buildString {
            append("You are Goku, an AI assistant answering a phone call on behalf of the phone owner in polite, natural Hinglish (1-2 short sentences max). ")
            append("Owner policy: ").append(policy.take(220)).append(". ")
            append("Respond ONLY with compact JSON: {\"action\":\"CHAT\",\"target\":\"CONTINUE|END_CALL\",\"message\":\"\",\"reply\":\"short Hinglish spoken response\"}. ")
            append("Use target=END_CALL if caller is spam/loan/telemarketer, said goodbye/okay/thanks, or the delivery/message instruction is complete.")
        }
        val userTurn = "Caller ($callerNumber) history: ${conversationHistory.take(360)}\nCaller just said: ${callerSaid.take(220)}"

        if (isNetworkAvailable()) {
            val cloud = onlineRouter.queryWithFailover(userTurn, systemPrompt)
            if (cloud != null && cloud.reply.isNotBlank()) {
                val endNow = cloud.target.equals("END_CALL", ignoreCase = true) ||
                    lower.contains("bye") || lower.contains("thank") || lower.contains("theek hai")
                return cloud.reply to endNow
            }
        }

        // Offline policy-aware Hinglish fallback negotiator
        return when {
            lower.contains("delivery") || lower.contains("order") || lower.contains("parcel") ||
                lower.contains("amazon") || lower.contains("flipkart") || lower.contains("swiggy") || lower.contains("zomato") ->
                "Ji shukriya, owner abhi busy hain. Kripya parcel gate ya security par rakh dijiye, main unhe inform kar deta hoon. Dhanyawad!" to true

            lower.contains("loan") || lower.contains("credit card") || lower.contains("insurance") ||
                lower.contains("bank") || lower.contains("offer") || lower.contains("emi") ->
                "Maaf kijiye, humein abhi kisi loan, card ya offer ki zaroorat nahi hai. Kripya dobara call na karein. Namaste." to true

            lower.contains("bye") || lower.contains("theek hai") || lower.contains("okay") || lower.contains("shukriya") || lower.contains("thanks") ->
                "Theek hai ji, maine aapka message note kar liya hai aur owner ko bata doonga. Namaste!" to true

            else ->
                "Maine aapki baat note kar li hai: '${callerSaid.take(80)}'. Kya koi aur urgent message dena hai owner ke liye?" to false
        }
    }

    /**
     * Unified structured JSON action executor for BOTH Online LLMs (Gemini/Groq/DeepSeek/OpenRouter)
     * and Offline MediaPipe LLM (CALL, SMS, OPEN_APP, VIRTUAL_APP, ALARM, TORCH, CHAT),
     * preserving the Hindi "Haan" / "Nahi" Safety Confirmation Gate for CALL & SMS.
     */
    private suspend fun executeStructuredAction(
        result: OnDeviceLlmResult,
        originalCommand: String,
        dryRun: Boolean
    ): Pair<String, String> {
        return when (result.action) {
            "TORCH" -> {
                _brainState.value = BrainVisualState.ACTING
                val turnOn = !(result.target.equals("off", ignoreCase = true) || result.target.equals("band", ignoreCase = true))
                val msg = if (dryRun) "[DRY-RUN] Torch -> ${if (turnOn) "ON" else "OFF"}" else toggleTorch(turnOn)
                msg to "${result.badge}+TORCH"
            }
            "VIRTUAL_APP" -> {
                if (result.target.isNotBlank()) {
                    _brainState.value = BrainVisualState.ACTING
                    val launched = launchAppInVirtualDisplay(result.target, dryRun)
                        ?: launchAppByName(result.target, dryRun)
                    if (launched != null) {
                        vault.workingMemory.addTurn("SYSTEM", "Background app ${result.target}", "app:${result.target}")
                        val suffix = maybeCompileAppShortcut(originalCommand, result.target, dryRun)
                        return (launched + suffix) to "${result.badge}+VAPP"
                    }
                }
                result.reply to result.badge
            }
            "OPEN_APP" -> {
                if (result.target.isNotBlank()) {
                    _brainState.value = BrainVisualState.ACTING
                    val launched = if (adbEngine.virtualDisplayRunner.isActive.value && !adbEngine.useAccessibilityFallback.value) {
                        launchAppInVirtualDisplay(result.target, dryRun) ?: launchAppByName(result.target, dryRun)
                    } else {
                        launchAppByName(result.target, dryRun)
                    }
                    if (launched != null) {
                        vault.workingMemory.addTurn("SYSTEM", "Opened ${result.target}", "app:${result.target}")
                        val suffix = maybeCompileAppShortcut(originalCommand, result.target, dryRun)
                        return (launched + suffix) to "${result.badge}+APP"
                    }
                }
                result.reply to result.badge
            }
            "ALARM" -> {
                _brainState.value = BrainVisualState.ACTING
                val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?").find(result.target)
                val hour = timeMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
                val minute = timeMatch?.groupValues?.get(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
                val replyMsg = if (dryRun) {
                    "[DRY-RUN] Would set alarm for $hour:${"%02d".format(Locale.US, minute)}"
                } else {
                    setAlarmIntent(hour, minute).second
                }
                replyMsg to "${result.badge}+ALARM"
            }
            "CALL" -> {
                if (result.target.isNotBlank()) {
                    return prepareCallWithSafetyGate(result.target, dryRun).first to "${result.badge}+GATE"
                }
                result.reply to result.badge
            }
            "SMS" -> {
                if (result.target.isNotBlank() && result.extra.isNotBlank()) {
                    return prepareSmsWithSafetyGate(result.target, result.extra, dryRun).first to "${result.badge}+GATE"
                }
                result.reply to result.badge
            }
            else -> result.reply to result.badge
        }
    }

    private suspend fun tryFastReflex(
        input: String,
        dryRun: Boolean
    ): Triple<String, String, Int>? {
        val lower = input.lowercase(Locale.ROOT)

        // 1. Emergency SOS Beacon
        if (lower.contains("emergency sos") || lower.contains("bachao") || lower == "sos") {
            _brainState.value = BrainVisualState.ACTING
            val res = if (dryRun) "[DRY-RUN] Would send offline GPS + battery SOS SMS to saved family contacts."
            else sosManager.triggerSosBeacon()
            return Triple(res, "REFLEX-SOS", 100)
        }

        // 1B. VirtualDisplay Invisible Background Screen Control (V10.1)
        if (lower.contains("virtual screen band") ||
            lower.contains("virtual display band") ||
            lower.contains("virtual display off") ||
            lower.contains("virtual screen off") ||
            lower.contains("invisible mode off") ||
            lower.contains("background mode off")
        ) {
            if (!dryRun) adbEngine.virtualDisplayRunner.releaseVirtualDisplay()
            return Triple(
                "GokuVirtualScreen band kar diya boss. Ab sab actions normal foreground screen par chalenge.",
                "VIRTUAL-DISPLAY",
                99
            )
        }

        if (lower.contains("virtual display on") ||
            lower.contains("virtual screen on") ||
            lower == "invisible mode" ||
            lower.contains("invisible mode on") ||
            lower.contains("background mode on")
        ) {
            if (dryRun) {
                return Triple("[DRY-RUN] Would allocate GokuVirtualScreen (1080x2400 @ 320dpi).", "VIRTUAL-DISPLAY", 98)
            }
            val displayId = adbEngine.virtualDisplayRunner.ensureVirtualDisplay()
            val msg = if (displayId != null) {
                val adbHint = if (adbEngine.useAccessibilityFallback.value) {
                    " (Local ADB pair kar lo for invisible --display input; tab tak Accessibility fallback ready hai.)"
                } else {
                    " Local ADB --display $displayId routing active!"
                }
                "VirtualDisplay ON ho gaya boss (GokuVirtualScreen Display #$displayId).$adbHint"
            } else {
                "VirtualDisplay allocate nahi ho paya; standard ADB/Accessibility mode active rahega."
            }
            return Triple(msg, "VIRTUAL-DISPLAY", if (displayId != null) 98 else 72)
        }

        if (lower.contains("background mein chalao") ||
            lower.contains("background me chalao") ||
            lower.contains("background mein kholo") ||
            lower.contains("in background")
        ) {
            val appQuery = input
                .replace(Regex("(background\\s+mein?\\s*(chalao|kholo)?|in\\s+background|run|open)", RegexOption.IGNORE_CASE), "")
                .trim()
            if (appQuery.isNotEmpty()) {
                if (isDeviceLocked()) {
                    return Triple(
                        "Security Lock: Phone locked hai boss. '$appQuery' background mein chalane ke liye pehle device unlock karein.",
                        "LOCK-SCREEN-GUARD",
                        100
                    )
                }
                _brainState.value = BrainVisualState.ACTING
                val bgResult = launchAppInVirtualDisplay(appQuery, dryRun)
                    ?: launchAppByName(appQuery, dryRun)
                if (bgResult != null) {
                    vault.workingMemory.addTurn("SYSTEM", "Background app $appQuery", "app:$appQuery")
                    return Triple(bgResult, "VIRTUAL-DISPLAY", 95)
                }
            } else {
                val id = if (dryRun) 99 else adbEngine.virtualDisplayRunner.ensureVirtualDisplay()
                return Triple(
                    if (id != null) "Invisible Background Mode ON (Display #$id). Bolo kaunsa app background mein chalaoon?"
                    else "Background display ready nahi ho paya; normal screen mode active hai.",
                    "VIRTUAL-DISPLAY",
                    92
                )
            }
        }

        // 2. Torch / Flashlight toggle
        if (lower.contains("torch") || lower.contains("flashlight") || lower.contains("light on") || lower.contains("light off")) {
            val turnOn = !(lower.contains("off") || lower.contains("band"))
            val res = if (dryRun) "[DRY-RUN] Torch -> ${if (turnOn) "ON" else "OFF"}"
            else toggleTorch(turnOn)
            return Triple(res, "REFLEX-0S", 99)
        }

        // 2B. 0-Sec Offline Volume Control (Blueprint Phase 4)
        if (lower.contains("volume") || lower.contains("aawaz") || lower == "mute" || lower.contains("mute karo")) {
            val res = if (dryRun) "[DRY-RUN] Would adjust device volume ($input)"
            else adjustDeviceVolume(lower)
            return Triple(res, "REFLEX-0S", 98)
        }

        // 2C. Deep Focus Shield ("Goku, kaam mode ON/OFF") (Blueprint Phase 3)
        if (lower.contains("kaam mode") || lower.contains("work mode") || lower.contains("focus mode") || lower.contains("focus shield")) {
            val enable = !(lower.contains("off") || lower.contains("band") || lower.contains("disable"))
            val res = if (dryRun) {
                "[DRY-RUN] Work Focus Shield -> ${if (enable) "ON" else "OFF"}"
            } else {
                applyWorkFocusShield(enable)
            }
            return Triple(res, "FOCUS-SHIELD", 99)
        }

        // 2D. Smart App Freezer via Direct Local ADB (Blueprint Phase 2 & 4)
        if (lower.startsWith("freeze ") || lower.startsWith("unfreeze ") ||
            lower.endsWith(" freeze karo") || lower.endsWith(" unfreeze karo")
        ) {
            val isUnfreeze = lower.contains("unfreeze")
            val appQuery = input
                .replace(Regex("^(?:un)?freeze\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(?:un)?freeze\\s+karo$", RegexOption.IGNORE_CASE), "")
                .trim()
            if (appQuery.isNotEmpty()) {
                if (isDeviceLocked()) {
                    return Triple(
                        "Security Lock: Phone locked hai boss. App freeze/unfreeze ke liye pehle device unlock karein.",
                        "LOCK-SCREEN-GUARD",
                        100
                    )
                }
                val pkg = resolveInstalledPackage(appQuery)
                if (pkg == null) {
                    return Triple("'$appQuery' naam ka koi installed app nahi mila.", "APP-FREEZER", 70)
                }
                _brainState.value = BrainVisualState.ACTING
                val resMsg = if (dryRun) {
                    "[DRY-RUN] Would ${if (isUnfreeze) "unfreeze" else "freeze"} $appQuery ($pkg) via Local ADB."
                } else {
                    val adbRes = if (isUnfreeze) adbEngine.unfreezeApp(pkg) else adbEngine.freezeApp(pkg)
                    if (adbRes.success) {
                        "$appQuery ($pkg) ${if (isUnfreeze) "unfreeze" else "freeze"} kar diya boss!"
                    } else {
                        "$appQuery ${if (isUnfreeze) "unfreeze" else "freeze"} ke liye Local ADB connected hona zaroori hai (${adbRes.output.take(100)})."
                    }
                }
                return Triple(resMsg, "APP-FREEZER", 95)
            }
        }

        // 3. Remember / Save to Personal Memory Vault
        if (lower.startsWith("yaad rakh") || lower.startsWith("remember ")) {
            val body = input
                .replace(Regex("^(yaad rakhna|yaad rakho|yaad rakh|remember)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (body.isNotEmpty()) {
                if (isDeviceLocked()) {
                    return Triple(
                        "Security Lock: Phone locked hai boss. Memory Vault mein save karne ke liye pehle device unlock karein.",
                        "LOCK-SCREEN-GUARD",
                        100
                    )
                }
                val parts = body.split("=", ":", " is ", " hai ", limit = 2)
                val key = parts[0].trim().take(60)
                val value = parts.getOrNull(1)?.trim()?.ifBlank { body } ?: body
                vault.saveMemory(key, value, "FACT")
                return Triple("Yaad rakh liya boss! Vault mein save ho gaya: $key -> $value.", "VAULT-MEMORY", 98)
            }
        }

        // 4. Expense Guardian & 24h Recurring Bills Summary
        if (lower.contains("recurring bill") || lower.contains("auto debit") || lower.contains("auto-debit") || lower.contains("subscription")) {
            if (isDeviceLocked()) {
                return Triple(
                    "Security Lock: Phone locked hai boss. Bill alerts dekhne ke liye pehle device unlock karein.",
                    "LOCK-SCREEN-GUARD",
                    100
                )
            }
            return Triple(vault.buildRecurringBillsReminderHinglish(), "BILL-GUARDIAN", 98)
        }
        if (lower.contains("expense") || lower.contains("kharch") || lower.contains("bill summary")) {
            if (isDeviceLocked()) {
                return Triple(
                    "Security Lock: Phone locked hai boss. Expense summary dekhne ke liye pehle device unlock karein.",
                    "LOCK-SCREEN-GUARD",
                    100
                )
            }
            val summary = vault.buildMonthlyExpenseSummaryHinglish()
            return Triple(summary, "EXPENSE-GUARDIAN", 98)
        }

        // 4B. Habit Predictor (Blueprint Phase 6)
        if (lower.contains("habit") || lower.contains("mera routine") || lower.contains("aaj kya karoon") || lower.contains("kya suggest")) {
            val habits = vault.predictHabitsForCurrentHour()
            val msg = if (habits.isEmpty()) {
                "Abhi is samay ke liye koi repeated habit pattern nahi mila. Jaise-jaise aap roz commands use karenge, main is time ke tasks khud suggest karoonga."
            } else {
                val formatted = habits.joinToString(", ") { "'${it.first}' (${it.second}x)" }
                "Habit Predictor: Is samay aap aksar yeh karte hain — $formatted. Bolo shuru kar doon?"
            }
            return Triple(msg, "HABIT-PREDICTOR", 94)
        }

        // 4C. Live Screen Co-Pilot (Blueprint Phase 5)
        if (lower.contains("read screen") || lower.contains("screen padho") ||
            lower.contains("screen samjhao") || lower.contains("explain screen")
        ) {
            if (isDeviceLocked()) {
                return Triple(
                    "Security Lock: Phone locked hai boss. Screen read karne ke liye pehle device unlock karein.",
                    "LOCK-SCREEN-GUARD",
                    100
                )
            }
            val liveScreen = GokuAccessibilityService.instance?.captureLiveScreenText().orEmpty()
                .ifBlank { vault.workingMemory.lastScreenSummary }
            if (liveScreen.isBlank()) {
                return Triple(
                    "Live Screen Co-Pilot: Screen text nahi mila. Setup tab se Goku Accessibility Phone Body enable karein.",
                    "SCREEN-COPILOT",
                    65
                )
            }
            val compact = DynamicContextCompressor.compressScreenTree(liveScreen, 360)
            return Triple("Live Screen Co-Pilot: Screen par yeh dikh raha hai — $compact", "SCREEN-COPILOT", 95)
        }

        // 4D. Zero-Space Encrypted Cloud Archive On-Demand (Blueprint Phase 7)
        if (lower.contains("cloud archive") || lower.contains("backup now") || lower.contains("skills backup")) {
            _brainState.value = BrainVisualState.ACTING
            if (dryRun) {
                return Triple("[DRY-RUN] Would export .goku skills & push encrypted archive to GitHub/Telegram.", "CLOUD-ARCHIVE", 96)
            }
            val skillsJson = vault.exportAllSkillsToGokuJson()
            val ghRes = if (vault.settings.githubToken.isNotBlank()) {
                pluginEngine.pushToGithubArchive("backups/goku-skills-v10.1.goku.json", skillsJson, "On-demand Goku V10.1 encrypted skill backup")
            } else "GitHub not configured"
            val tgRes = if (vault.settings.telegramBotToken.isNotBlank()) {
                pluginEngine.sendTelegramArchiveSummary("Goku V10.1 On-Demand Encrypted Backup\nSkills exported (${skillsJson.length} chars)")
            } else "Telegram not configured"
            return Triple("Cloud Archive Complete: $ghRes | $tgRes", "CLOUD-ARCHIVE", 96)
        }

        // 5. Phone Doctor & Cache Cleaner
        if (lower.contains("phone doctor") || lower.contains("clean cache") || lower.contains("boost battery") || lower.contains("storage clean")) {
            _brainState.value = BrainVisualState.ACTING
            val res = if (dryRun) "[DRY-RUN] Would run ADB pm trim-caches & Aggressive Doze."
            else adbEngine.runPhoneDoctor()
            return Triple(res, "PHONE-DOCTOR", 97)
        }

        // 6. Dream Mode Manual Trigger
        if (lower.contains("dream mode") || lower.contains("nightly audit")) {
            _brainState.value = BrainVisualState.ACTING
            val res = if (dryRun) "[DRY-RUN] Would run Dream Mode self-critique & timeline compaction."
            else pluginEngine.runDreamModeAudit()
            return Triple(res, "DREAM-MODE", 97)
        }

        // 7. Wi-Fi / Bluetooth / Settings Fast-Lane Intents
        if (lower.contains("wifi") || lower.contains("wi-fi")) {
            if (!dryRun) openSettingsAction(Settings.ACTION_WIFI_SETTINGS)
            return Triple("Wi-Fi settings khol diya boss.", "INTENT-0.05S", 98)
        }
        if (lower.contains("bluetooth")) {
            if (!dryRun) openSettingsAction(Settings.ACTION_BLUETOOTH_SETTINGS)
            return Triple("Bluetooth settings khol diya boss.", "INTENT-0.05S", 98)
        }

        // 8. Alarm Fast-Lane
        if (Regex("\\balarm\\b").containsMatchIn(lower) &&
            !lower.contains("cancel") &&
            !lower.contains("delete") &&
            !lower.contains("band")
        ) {
            val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?|subah|shaam|raat)?", RegexOption.IGNORE_CASE).find(lower)
            var hour = (timeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 7).coerceIn(0, 23)
            val minute = (timeMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0).coerceIn(0, 59)
            val period = timeMatch?.groupValues?.getOrNull(3).orEmpty().lowercase(Locale.ROOT)
            if ((period.startsWith("p") || period == "shaam" || period == "raat") && hour in 1..11) {
                hour += 12
            } else if ((period.startsWith("a") || period == "subah") && hour == 12) {
                hour = 0
            }
            val alarmMsg = if (dryRun) {
                "[DRY-RUN] Would set alarm for $hour:${"%02d".format(Locale.US, minute)}"
            } else {
                setAlarmIntent(hour, minute).second
            }
            return Triple(alarmMsg, "INTENT-0.05S", 97)
        }

        // 9. Call with Dual-Verification & Hindi "Haan" / "Nahi" Safety Gate
        if (lower.startsWith("call ") ||
            Regex("\\bcall\\s+karo\\b|\\bphone\\s+lagao\\b").containsMatchIn(lower)
        ) {
            val targetRaw = input
                .replace(Regex("^(call|phone\\s+lagao|call\\s+karo)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(ko\\s+call\\s+karo|call\\s+karo|ko\\s+phone\\s+lagao)$", RegexOption.IGNORE_CASE), "")
                .trim()
            if (targetRaw.isNotEmpty()) {
                return prepareCallWithSafetyGate(targetRaw, dryRun)
            }
        }

        // 10. SMS with Dual-Verification & Hindi "Haan" / "Nahi" Safety Gate (supports multi-word contact names)
        if (lower.startsWith("sms ") || lower.startsWith("send sms ")) {
            val rest = input
                .replace(Regex("^(?:send\\s+)?sms\\s+(?:to\\s+)?", RegexOption.IGNORE_CASE), "")
                .trim()
            val parsedSms = parseSmsTargetAndBody(rest)
            if (parsedSms != null) {
                return prepareSmsWithSafetyGate(parsedSms.first, parsedSms.second, dryRun)
            }
        }

        // 10B. Headless Background WebView Read & Auto-Fill Commands
        if (lower.startsWith("read webpage ") || lower.startsWith("web read ") || lower.startsWith("read url ")) {
            val url = input.replace(
                Regex("^(read\\s+webpage|web\\s+read|read\\s+url)\\s+", RegexOption.IGNORE_CASE),
                ""
            ).trim()
            if (url.isNotBlank()) {
                _brainState.value = BrainVisualState.ACTING
                val (ok, text) = if (dryRun) true to "[DRY-RUN] Would read webpage $url in headless WebView."
                else pilot.runHeadlessWebRead(url)
                return Triple(text.take(480), "HEADLESS-WEB", if (ok) 95 else 55)
            }
        }
        if (lower.startsWith("autofill ") || lower.startsWith("fill form ")) {
            val url = input.replace(Regex("^(autofill|fill\\s+form)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (url.isNotBlank()) {
                if (isDeviceLocked()) {
                    return Triple(
                        "Security Lock: Phone locked hai boss. Form auto-fill karne ke liye pehle device unlock karein.",
                        "LOCK-SCREEN-GUARD",
                        100
                    )
                }
                _brainState.value = BrainVisualState.ACTING
                val (ok, msg) = if (dryRun) true to "[DRY-RUN] Would auto-fill $url using Vault profile."
                else pilot.runHeadlessWebAutoFill(url, vault)
                return Triple(msg, "HEADLESS-WEB", if (ok) 95 else 60)
            }
        }

        // 10C. Multi-Step Chained Command Executor + Auto 3x Shortcut Compiler
        if (lower.contains(" and ") || lower.contains(" aur ") || lower.contains(" then ")) {
            val chainResult = tryExecuteMultiStepChain(input, dryRun)
            if (chainResult != null) return chainResult
        }

        // 11. Open App Fast-Lane (Fuzzy PackageManager Match + Auto 3x Shortcut Compiler)
        if (lower.startsWith("open ") || lower.endsWith(" kholo") || lower.endsWith(" chala do")) {
            val appQuery = input
                .replace(Regex("^open\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(kholo|chala do|open karo)$", RegexOption.IGNORE_CASE), "")
                .trim()
            if (appQuery.isNotEmpty()) {
                if (isDeviceLocked()) {
                    return Triple(
                        "Security Lock: Phone locked hai boss. '$appQuery' kholne ke liye pehle device unlock karein.",
                        "LOCK-SCREEN-GUARD",
                        100
                    )
                }
                val launched = launchAppByName(appQuery, dryRun)
                if (launched != null) {
                    vault.workingMemory.addTurn("SYSTEM", "Opened $appQuery", "app:$appQuery")
                    val suffix = maybeCompileAppShortcut(input, appQuery, dryRun)
                    return Triple(launched + suffix, "INTENT-0.05S", 96)
                }
            }
        }

        // 12. Screen Actions (Tap / Scroll / Home / Back)
        if (lower.startsWith("tap ") || lower.startsWith("click ")) {
            val target = input.substringAfter(" ").trim()
            if (isDeviceLocked()) {
                return Triple(
                    "Security Lock: Phone locked hai boss. Screen par '$target' tap karne ke liye pehle device unlock karein.",
                    "LOCK-SCREEN-GUARD",
                    100
                )
            }
            _brainState.value = BrainVisualState.ACTING
            val (ok, msg) = if (dryRun) true to "[DRY-RUN] Would tap '$target'" else pilot.tapTarget(target)
            val suffix = if (ok && !dryRun) {
                val steps = JSONArray().put(JSONObject().put("action", "tap").put("target", target)).toString()
                val compiled = vault.recordCommandPatternAndAutoCompile(input, steps, "Tap $target")
                if (compiled != null) " ⚡ Auto-Shortcut Compiled! ('${compiled.name}')" else ""
            } else ""
            return Triple(msg + suffix, "BODY-PILOT", if (ok) 92 else 60)
        }
        if (lower == "scroll down" || lower == "neeche scroll karo") {
            if (!dryRun) pilot.scrollDown()
            return Triple("Neeche scroll kar diya.", "BODY-PILOT", 95)
        }
        if (lower == "go home" || lower == "home screen") {
            if (!dryRun) pilot.navigateHome()
            return Triple("Home screen par aa gaye.", "BODY-PILOT", 98)
        }
        if (lower == "go back" || lower == "peeche jao") {
            if (!dryRun) pilot.navigateBack()
            return Triple("Back button press kar diya.", "BODY-PILOT", 98)
        }

        // 13. Visual Timeline Memory Search (strip Hinglish filler/FTS stopwords)
        if (lower.contains("timeline") || lower.contains("kal screen") || lower.contains("screen history")) {
            if (isDeviceLocked()) {
                return Triple(
                    "Security Lock: Phone locked hai boss. Visual Timeline history dekhne ke liye pehle device unlock karein.",
                    "LOCK-SCREEN-GUARD",
                    100
                )
            }
            val cleanedTokens = lower
                .replace(Regex("\\b(timeline|screen\\s+history|kal\\s+screen|screen|history|kal|aaj|mein|me|par|pe|kya|dekha|tha|thi|batao|dikhao|mera|meri|mere|goku|hey)\\b"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
            val hits = vault.searchVisualTimeline(cleanedTokens.ifBlank { "a" })
            val msg = if (hits.isEmpty()) {
                "Visual Memory Vault mein koi matching screen snapshot nahi mila."
            } else {
                val top = hits.first()
                "Visual Memory mila (${top.first.screenTitle}): ${top.second.take(220)}"
            }
            return Triple(msg, "VISUAL-VAULT", 90)
        }

        return null
    }

    /**
     * Parses SMS recipient (including multi-word contacts like "Rahul Sharma" or phone numbers)
     * and message body from fast-reflex commands.
     */
    private suspend fun parseSmsTargetAndBody(rest: String): Pair<String, String>? {
        if (rest.isBlank()) return null

        // 1. Explicit delimiter ("Rahul Sharma: hello", "Rahul Sharma - hello", "Rahul Sharma saying hello", "Rahul Sharma ko hello")
        val delimMatch = Regex("^(.+?)\\s*(?::|-\\s+|\\bsaying\\b|\\bmessage\\b|\\bthat\\b|\\bko\\b)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(rest)
        if (delimMatch != null) {
            val target = delimMatch.groupValues[1].trim()
            val body = delimMatch.groupValues[2].trim()
                .replace(Regex("\\s*(?:bhejo|bhej\\s+do|send\\s+karo)$", RegexOption.IGNORE_CASE), "")
                .trim()
            if (target.isNotEmpty() && body.isNotEmpty()) {
                return target to body
            }
        }

        // 2. Leading phone number ("+91 98765 43210 I am reaching soon")
        val phonePrefixMatch = Regex("^(\\+?\\d[\\d\\s-]{6,16})\\s+(.+)$").find(rest)
        if (phonePrefixMatch != null) {
            val phoneCandidate = phonePrefixMatch.groupValues[1].trim()
            val body = phonePrefixMatch.groupValues[2].trim()
            if (extractFirstPhoneNumber(phoneCandidate) != null && body.isNotEmpty()) {
                return phoneCandidate to body
            }
        }

        // 3. Multi-word Vault contact prefix resolution (try 3-word, 2-word, then 1-word prefixes)
        val words = rest.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 2) return null
        val maxPrefix = minOf(3, words.size - 1)
        for (len in maxPrefix downTo 2) {
            val candidate = words.take(len).joinToString(" ")
            val exactNode = vault.searchMemories(candidate).firstOrNull {
                it.key.equals(candidate, ignoreCase = true) && extractFirstPhoneNumber(it.value) != null
            }
            if (exactNode != null) {
                val body = words.drop(len).joinToString(" ").trim()
                if (body.isNotEmpty()) return candidate to body
            }
        }

        // 4. Default single-word recipient + remaining message body
        val target = words[0].trim()
        val body = words.drop(1).joinToString(" ").trim()
        return if (target.isNotEmpty() && body.isNotEmpty()) target to body else null
    }

    /**
     * Parses chained multi-step commands (e.g. "open YouTube and tap search and scroll down"),
     * executes them sequentially via UniversalAppPilot, and automatically compiles a zero-latency
     * LearnedSkillEntity macro when repeated 3 times.
     */
    private suspend fun tryExecuteMultiStepChain(
        rawCommand: String,
        dryRun: Boolean
    ): Triple<String, String, Int>? {
        val segments = rawCommand
            .split(Regex("\\s+(?:and|aur|then)\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.size < 2) return null

        val stepsArr = JSONArray()
        for (seg in segments) {
            val low = seg.lowercase(Locale.ROOT)
            when {
                low.startsWith("open ") || low.endsWith(" kholo") -> {
                    val appName = seg.replace(Regex("^open\\s+", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("\\s*kholo$", RegexOption.IGNORE_CASE), "").trim()
                    val pkg = resolveInstalledPackage(appName) ?: return null
                    stepsArr.put(JSONObject().put("action", "open_app").put("target", pkg))
                    stepsArr.put(JSONObject().put("action", "wait").put("target", "850"))
                }
                low.startsWith("tap ") || low.startsWith("click ") -> {
                    val target = seg.substringAfter(" ").trim()
                    if (target.isEmpty()) return null
                    stepsArr.put(JSONObject().put("action", "tap").put("target", target))
                }
                low.startsWith("type ") -> {
                    val text = seg.substringAfter(" ").trim()
                    if (text.isEmpty()) return null
                    stepsArr.put(JSONObject().put("action", "type").put("target", text))
                }
                low.contains("scroll") -> stepsArr.put(JSONObject().put("action", "scroll").put("target", ""))
                low == "go home" || low == "home" -> stepsArr.put(JSONObject().put("action", "home").put("target", ""))
                low == "go back" || low == "back" -> stepsArr.put(JSONObject().put("action", "back").put("target", ""))
                else -> return null
            }
        }
        if (stepsArr.length() < 2) return null
        if (isDeviceLocked()) {
            return Triple(
                "Security Lock: Phone locked hai boss. Multi-step automation chalane ke liye pehle device unlock karein.",
                "LOCK-SCREEN-GUARD",
                100
            )
        }

        _brainState.value = BrainVisualState.ACTING
        val stepsJson = stepsArr.toString()
        val (ok, detail) = pilot.executeStepsJson(stepsJson, dryRun = dryRun)
        val compiledSuffix = if (ok && !dryRun) {
            val compiled = vault.recordCommandPatternAndAutoCompile(
                rawCommand = rawCommand,
                stepsJson = stepsJson,
                skillDisplayName = rawCommand.take(40)
            )
            if (compiled != null) " ⚡ Auto-Shortcut Compiled! ('${compiled.name}')" else ""
        } else ""
        return Triple(detail + compiledSuffix, "CHAIN-MACRO", if (ok) 96 else 65)
    }

    private suspend fun maybeCompileAppShortcut(
        rawCommand: String,
        appQuery: String,
        dryRun: Boolean
    ): String {
        if (dryRun) return ""
        val pkg = resolveInstalledPackage(appQuery) ?: return ""
        val stepsJson = JSONArray().put(JSONObject().put("action", "open_app").put("target", pkg)).toString()
        val compiled = vault.recordCommandPatternAndAutoCompile(rawCommand, stepsJson, "Open $appQuery")
        return if (compiled != null) " ⚡ Auto-Shortcut Compiled! ('${compiled.name}')" else ""
    }

    private suspend fun resolveInstalledPackage(query: String): String? {
        val apps = adbEngine.discoverInstalledApps()
        val low = query.lowercase(Locale.ROOT).trim()
        val match = apps.firstOrNull { it.first.lowercase(Locale.ROOT) == low }
            ?: apps.firstOrNull { it.first.lowercase(Locale.ROOT).contains(low) || it.second.lowercase(Locale.ROOT).contains(low) }
        return match?.second
    }

    private suspend fun prepareCallWithSafetyGate(targetRaw: String, dryRun: Boolean): Triple<String, String, Int> {
        if (isDeviceLocked()) {
            return Triple(
                "Security Lock: Phone locked hai boss. Call lagane ke liye pehle device unlock karein.",
                "LOCK-SCREEN-GUARD",
                100
            )
        }
        val candidates = resolvePhoneCandidates(targetRaw)
        vault.workingMemory.addTurn("SYSTEM", "Target contact $targetRaw", "contact:$targetRaw")
        if (candidates.isEmpty()) {
            return Triple(
                "Ambiguity Resolver: '$targetRaw' ka number Vault mein nahi mila. Kripya number bolein ya 'Yaad rakhna $targetRaw = 98XXXXXXXX' bolkar save karein.",
                "AMBIGUITY-RESOLVER",
                72
            )
        }
        if (candidates.size > 1) {
            val options = candidates.take(3).joinToString(" ya ") { "${it.first} (${it.second})" }
            return Triple(
                "Ambiguity Resolver: '$targetRaw' naam ke ${candidates.size} contacts mile hain — $options. Kise call lagaoon?",
                "AMBIGUITY-RESOLVER",
                80
            )
        }
        val (resolvedLabel, finalNumber) = candidates.first()
        val prompt = "Safety Confirm: Kya main $resolvedLabel ($finalNumber) ko call lagaoon? 'Haan' ya 'Nahi' bolein."
        _pendingConfirmation.value = PendingConfirmation(
            promptHinglish = prompt,
            actionSummary = "Call $resolvedLabel ($finalNumber)",
            confidence = 95,
            onConfirm = {
                if (dryRun) "[DRY-RUN] Would dial $finalNumber"
                else placePhoneCall(finalNumber)
            }
        )
        return Triple(prompt, "SAFETY-GATE", 95)
    }

    private suspend fun prepareSmsWithSafetyGate(targetRaw: String, body: String, dryRun: Boolean): Triple<String, String, Int> {
        if (isDeviceLocked()) {
            return Triple(
                "Security Lock: Phone locked hai boss. SMS bhejne ke liye pehle device unlock karein.",
                "LOCK-SCREEN-GUARD",
                100
            )
        }
        val candidates = resolvePhoneCandidates(targetRaw)
        vault.workingMemory.addTurn("SYSTEM", "Target SMS $targetRaw", "contact:$targetRaw")
        if (candidates.isEmpty()) {
            return Triple(
                "Ambiguity Resolver: '$targetRaw' ka number Vault mein nahi mila. Pehle 'Yaad rakhna $targetRaw = 98XXXXXXXX' save karein.",
                "AMBIGUITY-RESOLVER",
                72
            )
        }
        if (candidates.size > 1) {
            val options = candidates.take(3).joinToString(" ya ") { "${it.first} (${it.second})" }
            return Triple(
                "Ambiguity Resolver: '$targetRaw' naam ke ${candidates.size} contacts mile hain — $options. Kise SMS bhejoon?",
                "AMBIGUITY-RESOLVER",
                80
            )
        }
        val (resolvedLabel, finalNumber) = candidates.first()
        val prompt = "Safety Confirm: Kya main $resolvedLabel ($finalNumber) ko SMS bhejoon: \"$body\"? 'Haan' ya 'Nahi' bolein."
        _pendingConfirmation.value = PendingConfirmation(
            promptHinglish = prompt,
            actionSummary = "SMS $resolvedLabel ($finalNumber)",
            confidence = 95,
            onConfirm = {
                if (dryRun) "[DRY-RUN] Would send SMS to $finalNumber: $body"
                else sendDirectSms(finalNumber, body)
            }
        )
        return Triple(prompt, "SAFETY-GATE", 95)
    }

    private suspend fun resolvePhoneCandidates(targetRaw: String): List<Pair<String, String>> {
        extractFirstPhoneNumber(targetRaw)?.let { return listOf(targetRaw to it) }
        val matches = vault.searchMemories(targetRaw)
            .mapNotNull { node ->
                val num = extractFirstPhoneNumber(node.value) ?: return@mapNotNull null
                node.key to num
            }
            .distinctBy { it.second }
        if (matches.size <= 1) return matches
        val exact = matches.firstOrNull { it.first.equals(targetRaw.trim(), ignoreCase = true) }
        return if (exact != null) listOf(exact) else matches
    }

    private fun adjustDeviceVolume(lower: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "AudioManager unavailable hai."
        return runCatching {
            val stream = AudioManager.STREAM_MUSIC
            val maxVol = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            when {
                lower.contains("mute") || lower.contains("silent") || lower.contains("band") -> {
                    am.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
                    "Media volume mute kar diya boss."
                }
                lower.contains("full") || lower.contains("max") || lower.contains("100") -> {
                    am.setStreamVolume(stream, maxVol, AudioManager.FLAG_SHOW_UI)
                    "Volume full ($maxVol/$maxVol) kar diya boss."
                }
                lower.contains("down") || lower.contains("kam") || lower.contains("low") || lower.contains("ghata") -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "Volume kam kar diya boss."
                }
                else -> {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "Volume badha diya boss."
                }
            }
        }.getOrElse { "Volume adjust nahi ho paya: ${it.message}" }
    }

    private fun applyWorkFocusShield(enable: Boolean): String {
        vault.settings.workFocusMode = enable
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val dndNote = if (nm != null && nm.isNotificationPolicyAccessGranted) {
            runCatching {
                nm.setInterruptionFilter(
                    if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    else NotificationManager.INTERRUPTION_FILTER_ALL
                )
                if (enable) " + DND Priority ON" else " + DND OFF"
            }.getOrDefault("")
        } else {
            ""
        }
        return if (enable) {
            "Work Focus Shield ON kar diya boss$dndNote — distraction alerts aur whisper readouts paused hain."
        } else {
            "Work Focus Shield OFF kar diya boss$dndNote — normal notifications active hain."
        }
    }

    private fun extractFirstPhoneNumber(raw: String): String? {
        val pattern = Regex(
            "(?:\\+\\d{1,3}(?:[\\s-]\\d{2,5}){1,4}|\\+?\\d{7,15}|\\d{5}[\\s-]\\d{5}|\\d{3,5}-\\d{5,8}|\\d{3,4}[\\s-]\\d{3,4}[\\s-]\\d{3,4})"
        )
        val match = pattern.find(raw)?.value ?: return null
        val normalized = buildString {
            match.forEach { ch ->
                if (ch.isDigit() || (ch == '+' && isEmpty())) append(ch)
            }
        }
        val digits = normalized.count { it.isDigit() }
        return normalized.takeIf { digits in 7..15 }
    }

    private suspend fun setAlarmIntent(hour: Int, minute: Int): Pair<Boolean, String> {
        val formattedTime = "$hour:${"%02d".format(Locale.US, minute)}"
        val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Goku Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val canResolve = runCatching {
            alarmIntent.resolveActivity(context.packageManager) != null
        }.getOrDefault(false)

        if (canResolve) {
            val launched = try {
                context.startActivity(alarmIntent)
                true
            } catch (_: SecurityException) {
                false
            } catch (_: Throwable) {
                false
            }
            if (launched) {
                return true to "Alarm $formattedTime ke liye set kar diya boss."
            }
        }

        // Android 14-16 BAL restriction, missing SET_ALARM, or no Clock app: persist an honest in-app Vault reminder fallback
        val reminderKey = "alarm_reminder_$formattedTime"
        vault.saveMemory(
            key = reminderKey,
            value = "Alarm reminder requested for $formattedTime (system Clock launch restricted in background)",
            category = "ALARM_REMINDER"
        )
        vault.logAction(
            stage = "EXECUTOR",
            command = "Alarm Fallback ($formattedTime)",
            detail = "System Clock launch blocked (BAL/permission/unavailable); saved fallback reminder in Vault.",
            confidence = 85,
            status = "WARN"
        )
        return false to "System Clock background mein open nahi ho paya (Android 14-16 BAL/permission restriction). Maine $formattedTime ka fallback alarm reminder Vault mein save kar diya hai — Goku app foreground mein kholkar alarm set kar sakte ho."
    }

    private fun toggleTorch(turnOn: Boolean): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "Camera service available nahi hai."
        return runCatching {
            val camId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "Is device mein flash torch available nahi hai."
            cm.setTorchMode(camId, turnOn)
            torchEnabled = turnOn
            if (turnOn) "Torch ON kar diya boss." else "Torch OFF kar diya boss."
        }.getOrElse { "Torch control error: ${it.message}" }
    }

    private fun openSettingsAction(action: String) {
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(intent) }
    }

    private fun placePhoneCall(number: String): String {
        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            if (hasCallPerm) "$number par call laga diya boss." else "Dialer khol diya $number ke liye."
        }.getOrElse { "Call start nahi ho paya: ${it.message}" }
    }

    private fun sendDirectSms(number: String, body: String): String {
        val hasSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasSms) {
            return "SEND_SMS permission nahi mili hai. Setup tab se SMS permission allow karein."
        }
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return "SmsManager is device par available nahi hai."
        return runCatching {
            val parts = smsManager.divideMessage(body)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            "SMS $number par bhej diya boss."
        }.getOrElse { "SMS send failed: ${it.message}" }
    }

    private suspend fun launchAppInVirtualDisplay(query: String, dryRun: Boolean): String? {
        val apps = adbEngine.discoverInstalledApps()
        val low = query.lowercase(Locale.ROOT)
        val match = apps.firstOrNull { it.first.lowercase(Locale.ROOT) == low }
            ?: apps.firstOrNull { it.first.lowercase(Locale.ROOT).contains(low) || it.second.lowercase(Locale.ROOT).contains(low) }
            ?: return null

        if (dryRun) {
            return "[DRY-RUN] Would launch ${match.first} (${match.second}) on GokuVirtualScreen (--display)."
        }
        val displayId = adbEngine.virtualDisplayRunner.ensureVirtualDisplay()
        if (displayId != null) {
            val res = adbEngine.virtualDisplayRunner.launchAppInBackground(match.second)
            if (res.success && res.viaAdb) {
                return "${match.first} ko invisibly VirtualDisplay #$displayId par chala diya boss — aapki current screen disturb nahi hogi!"
            }
        }
        // Clean fallback to standard foreground launch when Local ADB is not paired
        return launchAppByName(query, dryRun = false)?.let {
            if (displayId != null) {
                "$it (Local ADB pair karne par yeh VirtualDisplay #$displayId par invisibly chalega.)"
            } else {
                "$it (Local ADB pair karne par yeh VirtualDisplay par invisibly chalega.)"
            }
        }
    }

    private suspend fun launchAppByName(query: String, dryRun: Boolean): String? {
        val apps = adbEngine.discoverInstalledApps()
        val low = query.lowercase(Locale.ROOT)
        val match = apps.firstOrNull { it.first.lowercase(Locale.ROOT) == low }
            ?: apps.firstOrNull { it.first.lowercase(Locale.ROOT).contains(low) || it.second.lowercase(Locale.ROOT).contains(low) }
            ?: return null

        if (dryRun) return "[DRY-RUN] Would launch ${match.first} (${match.second})"
        val intent = context.packageManager.getLaunchIntentForPackage(match.second)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: return null
        return runCatching {
            context.startActivity(intent)
            "${match.first} open kar diya boss."
        }.getOrNull()
    }

    private fun appendUserMessage(text: String) {
        _messages.update { (it + ChatMessage(isUser = true, text = text)).takeLast(60) }
    }

    private fun appendGokuMessage(text: String, badge: String, confidence: Int) {
        _messages.update {
            (it + ChatMessage(
                isUser = false,
                text = text,
                engineBadge = badge,
                confidence = confidence
            )).takeLast(60)
        }
    }

    fun clearConversation() {
        vault.workingMemory.clear()
        _messages.value = emptyList()
    }

    companion object {
        private val SENSITIVE_LOCK_GUARD_ACTIONS = setOf("CALL", "SMS", "OPEN_APP", "VIRTUAL_APP")
    }
}
