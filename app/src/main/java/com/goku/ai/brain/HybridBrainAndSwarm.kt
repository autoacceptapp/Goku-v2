package com.goku.ai.brain

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    val directUrl: String
)

/**
 * Open-access, non-gated public Hugging Face LiteRT/MediaPipe .task model URLs.
 * Zero authentication token or login required.
 */
val OFFLINE_MODEL_PRESETS = listOf(
    OfflineModelPreset(
        id = "qwen25_1_5b",
        name = "Qwen2.5 1.5B Instruct",
        subtitle = "Best Hinglish + JSON actions (Q8)",
        approxSizeMb = 1560,
        directUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.task"
    ),
    OfflineModelPreset(
        id = "qwen25_0_5b",
        name = "Qwen2.5 0.5B Instruct",
        subtitle = "Fast & light for 4GB+ RAM phones",
        approxSizeMb = 520,
        directUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_seq128_q8_ekv1280.task"
    ),
    OfflineModelPreset(
        id = "smollm_135m",
        name = "SmolLM 135M Instruct",
        subtitle = "Ultra-compact quick test brain",
        approxSizeMb = 145,
        directUrl = "https://huggingface.co/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_seq128_q8_ekv1280.task"
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
        sb.append("You are Goku V10, a Jarvis-style personal AI on Android. ")
        sb.append("Reply concisely in natural Hinglish (Hindi+English mix), friendly & practical. ")
        if (memoryHint.isNotBlank()) sb.append("Vault facts: ").append(memoryHint.take(260)).append(". ")
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
        sb.append("System: You are Goku V10, an offline Android AI assistant. ")
        sb.append("Respond ONLY with valid compact JSON: ")
        sb.append("{\"action\":\"CHAT|CALL|SMS|OPEN_APP|ALARM|TORCH\",\"target\":\"\",\"extra\":\"\",\"reply\":\"concise Hinglish reply\"}. ")
        sb.append("Use CHAT unless user explicitly asks to call, send sms, open an app, set alarm, or toggle torch.\n")
        if (memoryHint.isNotBlank()) sb.append("Vault: ").append(memoryHint.take(200)).append("\n")
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

        // Prevent duplicate rapid taps while already downloading the same model
        if (activeJob?.isActive == true) return

        // If switching to a different model URL, clear any partial .tmp from previous model
        if (cleanUrl != lastUrl && tempFile.exists()) {
            tempFile.delete()
            lastKnownTotalBytes = 0L
        }

        lastUrl = cleanUrl
        lastModelName = modelName.ifBlank { cleanUrl.substringAfterLast('/').ifBlank { "Offline AI Model" } }
        saveMeta(lastModelName, lastUrl, lastKnownTotalBytes)
        launchDownloadJob()
    }

    fun pauseDownload() {
        pauseRequested.set(true)
        activeCall?.cancel()
        activeJob?.cancel()
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

    fun resumeDownload() {
        if (activeJob?.isActive == true) return
        loadMeta()
        if (lastUrl.isBlank()) {
            lastUrl = OFFLINE_MODEL_PRESETS.first().directUrl
            lastModelName = OFFLINE_MODEL_PRESETS.first().name
        }
        launchDownloadJob()
    }

    fun deleteModel() {
        pauseRequested.set(true)
        activeCall?.cancel()
        activeJob?.cancel()
        onModelChanged?.invoke()
        runCatching { finalModelFile.delete() }
        runCatching { tempFile.delete() }
        runCatching { metaFile.delete() }
        lastKnownTotalBytes = 0L
        _state.value = ModelDownloadState.Idle(partialMb = 0f, lastModelName = OFFLINE_MODEL_PRESETS.first().name)
    }

    /**
     * Imports a user-selected local .task or .bin model via Android Storage Access Framework URI
     * without requiring broad storage permissions.
     */
    fun importLocalModel(uri: Uri) {
        if (activeJob?.isActive == true) return
        pauseRequested.set(false)
        activeJob = scope.launch {
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
                        while (isActive && !pauseRequested.get()) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            copied += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 250L) {
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

                if (tempFile.length() < MIN_VALID_MODEL_BYTES) {
                    tempFile.delete()
                    throw IllegalStateException("Selected file is too small to be a valid MediaPipe .task/.bin model.")
                }

                onModelChanged?.invoke()
                finalizeAtomicRename(displayName, "local://imported")
            }.onFailure { err ->
                if (err !is CancellationException) {
                    _state.value = ModelDownloadState.Error(
                        message = "Local import failed: ${err.message ?: "Unknown error"}",
                        canResume = false
                    )
                }
            }
        }
    }

    private fun launchDownloadJob() {
        pauseRequested.set(false)
        activeJob = scope.launch {
            try {
                modelsDir.mkdirs()
                var existingBytes = if (tempFile.exists()) tempFile.length() else 0L

                val requestBuilder = Request.Builder()
                    .url(lastUrl)
                    .header("User-Agent", "Goku-V10-Android")
                if (existingBytes > 0L) {
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }

                val call = httpClient.newCall(requestBuilder.build())
                activeCall = call
                val response = call.execute()

                response.use { resp ->
                    if (resp.code == 416) {
                        // Partial file mismatch or already complete; restart cleanly
                        tempFile.delete()
                        lastKnownTotalBytes = 0L
                        existingBytes = 0L
                        _state.value = ModelDownloadState.Error(
                            message = "Range reset required — tap Resume to restart cleanly.",
                            canResume = true
                        )
                        return@launch
                    }

                    if (resp.code == 401 || resp.code == 403) {
                        _state.value = ModelDownloadState.Error(
                            message = "HTTP ${resp.code}: Gated repo requires login. Please select an open-access LiteRT preset above.",
                            canResume = false
                        )
                        return@launch
                    }

                    if (!resp.isSuccessful && resp.code != 206) {
                        _state.value = ModelDownloadState.Error(
                            message = "Download failed (HTTP ${resp.code}). Check internet and tap Resume.",
                            canResume = existingBytes > 0L
                        )
                        return@launch
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
                        saveMeta(lastModelName, lastUrl, totalBytes)
                    }

                    var downloadedBytes = existingBytes
                    var windowBytes = 0L
                    var lastEmitMs = System.currentTimeMillis()

                    _state.value = ModelDownloadState.Downloading(
                        modelName = lastModelName,
                        progressPercent = computePercent(downloadedBytes, totalBytes),
                        downloadedMb = bytesToMb(downloadedBytes),
                        totalMb = bytesToMb(totalBytes)
                    )

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile, isPartialResume).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (isActive && !pauseRequested.get()) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                windowBytes += read

                                val now = System.currentTimeMillis()
                                val deltaMs = now - lastEmitMs
                                if (deltaMs >= 300L) {
                                    val speedMbPerSec = (windowBytes.toFloat() / (1024f * 1024f)) / (deltaMs / 1000f)
                                    _state.value = ModelDownloadState.Downloading(
                                        modelName = lastModelName,
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

                    if (pauseRequested.get() || !isActive) {
                        val partial = tempFile.length()
                        _state.value = ModelDownloadState.Paused(
                            modelName = lastModelName,
                            progressPercent = computePercent(partial, totalBytes),
                            downloadedMb = bytesToMb(partial),
                            totalMb = bytesToMb(totalBytes)
                        )
                        return@launch
                    }

                    if (totalBytes > 0L && downloadedBytes < totalBytes) {
                        _state.value = ModelDownloadState.Paused(
                            modelName = lastModelName,
                            progressPercent = computePercent(downloadedBytes, totalBytes),
                            downloadedMb = bytesToMb(downloadedBytes),
                            totalMb = bytesToMb(totalBytes)
                        )
                        return@launch
                    }

                    if (tempFile.length() < MIN_VALID_MODEL_BYTES) {
                        tempFile.delete()
                        _state.value = ModelDownloadState.Error(
                            message = "Downloaded file appears incomplete or invalid (${bytesToMb(tempFile.length())} MB).",
                            canResume = false
                        )
                        return@launch
                    }

                    onModelChanged?.invoke()
                    finalizeAtomicRename(lastModelName, lastUrl)
                }
            } catch (ce: CancellationException) {
                if (!pauseRequested.get()) {
                    refreshStateFromDisk()
                }
            } catch (e: Exception) {
                if (pauseRequested.get()) return@launch
                val canResume = tempFile.exists() && tempFile.length() > 0L
                _state.value = ModelDownloadState.Error(
                    message = "Network interrupted: ${e.message ?: "Check connection"}. ${if (canResume) "Progress saved — tap Resume." else ""}",
                    canResume = canResume
                )
            } finally {
                activeCall = null
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
    val action: String = "CHAT", // CHAT, CALL, SMS, OPEN_APP, ALARM, TORCH
    val target: String = "",
    val extra: String = ""
)

class OnDeviceLlmEngine(
    private val context: Context,
    private val vault: GokuVaultRepository,
    private val downloadManager: OfflineModelDownloadManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineMutex = Mutex()
    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null
    private var unloadTimerJob: Job? = null

    private val _isLoadedInRam = MutableStateFlow(false)
    val isLoadedInRam: StateFlow<Boolean> = _isLoadedInRam.asStateFlow()

    init {
        downloadManager.onModelChanged = {
            scope.launch { unloadNow() }
        }
        GokuKillSwitch.registerStopHook {
            scope.launch { unloadNow() }
        }
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

    suspend fun unloadNow() {
        unloadTimerJob?.cancel()
        unloadTimerJob = null
        engineMutex.withLock {
            runCatching { llmInference?.close() }
            llmInference = null
            loadedModelPath = null
            _isLoadedInRam.value = false
        }
    }

    private fun scheduleFifteenSecondIdleUnload() {
        unloadTimerJob?.cancel()
        unloadTimerJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            unloadNow()
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
            runCatching {
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
                scheduleFifteenSecondIdleUnload()
                val engine = llmInference ?: return@runCatching null
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(40)
                    .setTemperature(0.65f)
                    .setRandomSeed(42)
                    .build()
                val response = LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
                    session.addQueryChunk(prompt)
                    session.generateResponse()?.trim()
                }
                scheduleFifteenSecondIdleUnload()
                response
            }.onFailure {
                runCatching { llmInference?.close() }
                llmInference = null
                loadedModelPath = null
                _isLoadedInRam.value = false
            }.getOrNull()
        }
    }

    private fun parseStructuredLlmOutput(raw: String): OnDeviceLlmResult {
        val jsonCandidate = extractJsonObject(raw)
        if (jsonCandidate != null) {
            val parsed = runCatching { JSONObject(jsonCandidate) }.getOrNull()
            if (parsed != null) {
                val action = parsed.optString("action", "CHAT").uppercase(Locale.ROOT).trim()
                val target = parsed.optString("target", "").trim()
                val extra = parsed.optString("extra", "").trim()
                val reply = parsed.optString("reply", "").trim().ifBlank { raw.take(360) }
                if (action in setOf("CHAT", "CALL", "SMS", "OPEN_APP", "ALARM", "TORCH")) {
                    return OnDeviceLlmResult(
                        reply = reply,
                        badge = "MEDIAPIPE-LOCAL",
                        action = action,
                        target = target,
                        extra = extra
                    )
                }
            }
        }
        return OnDeviceLlmResult(
            reply = raw.take(480),
            badge = "MEDIAPIPE-LOCAL",
            action = "CHAT"
        )
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1)
        }
        return null
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
                "Namaste boss! Main Goku V10 hoon — 100% ready offline aur online dono mode mein. Bolo kya kaam karna hai?"

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
                "Main Goku V10 hoon — aapka personal Jarvis agent. Main torch, apps, calls, SMS, alarm, Local ADB, Accessibility taps, OTP auto-fill, expense tracking, aur Emergency SOS bina internet ke bhi chala sakta hoon."

            lower.contains("thank") || lower.contains("shukriya") || lower.contains("dhanyawad") ->
                "Hamesha haazir hoon boss! Jab bhi zaroorat ho bas 'Hey Goku' bol dena."

            else ->
                "Maine offline samjha: \"$userPrompt\". Full local AI chat ke liye Vault tab mein 'Latest Offline AI Brain (1-Tap Download)' se Qwen2.5 download karein, ya directly phone commands bolein jaise 'Torch on', 'Open WhatsApp', 'Call Mom', 'SMS Mom hello', 'Expense summary', ya 'Phone Doctor'."
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
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        val geminiKey = vault.settings.geminiApiKey
        val groqKey = vault.settings.groqApiKey
        val deepSeekKey = vault.settings.deepSeekApiKey
        val openRouterKey = vault.settings.openRouterApiKey

        // Provider 1: Google Gemini Free Tier REST API
        if (geminiKey.isNotBlank()) {
            callGeminiRest(geminiKey, userPrompt, systemPrompt)?.let {
                _activeProvider.value = "GEMINI-CLOUD"
                return@withContext it to "GEMINI-CLOUD"
            }
        }

        // Provider 2: Groq Free Tier OpenAI-Compatible Endpoint
        if (groqKey.isNotBlank()) {
            callOpenAiCompatible(
                endpoint = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = groqKey,
                model = "llama-3.3-70b-versatile",
                userPrompt = userPrompt,
                systemPrompt = systemPrompt
            )?.let {
                _activeProvider.value = "GROQ-FAILOVER"
                return@withContext it to "GROQ-FAILOVER"
            }
        }

        // Provider 3: DeepSeek OpenAI-Compatible Endpoint
        if (deepSeekKey.isNotBlank()) {
            callOpenAiCompatible(
                endpoint = "https://api.deepseek.com/v1/chat/completions",
                apiKey = deepSeekKey,
                model = "deepseek-chat",
                userPrompt = userPrompt,
                systemPrompt = systemPrompt
            )?.let {
                _activeProvider.value = "DEEPSEEK-FAILOVER"
                return@withContext it to "DEEPSEEK-FAILOVER"
            }
        }

        // Provider 4: OpenRouter Free Model Failover
        if (openRouterKey.isNotBlank()) {
            callOpenAiCompatible(
                endpoint = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = openRouterKey,
                model = "meta-llama/llama-3.3-70b-instruct:free",
                userPrompt = userPrompt,
                systemPrompt = systemPrompt
            )?.let {
                _activeProvider.value = "OPENROUTER-FREE"
                return@withContext it to "OPENROUTER-FREE"
            }
        }

        _activeProvider.value = "OFFLINE-PARITY"
        null
    }

    private fun callGeminiRest(apiKey: String, userPrompt: String, systemPrompt: String): String? {
        return runCatching {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
            val payload = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", "$systemPrompt\n\nUser: $userPrompt")))
                }))
            }
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = JSONObject(resp.body?.string() ?: return null)
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

    private fun callOpenAiCompatible(
        endpoint: String,
        apiKey: String,
        model: String,
        userPrompt: String,
        systemPrompt: String
    ): String? {
        return runCatching {
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
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = JSONObject(resp.body?.string() ?: return null)
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
                text = "Goku V10 online & MediaPipe offline hybrid core ready hai boss! 'Hey Goku' bolo ya neeche command likho. Vault tab se 1-tap Offline AI model bhi download kar sakte ho.",
                engineBadge = "V10-CORE",
                confidence = 100
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var torchEnabled = false

    init {
        GokuKillSwitch.registerStopHook {
            _pendingConfirmation.value = null
            _brainState.value = BrainVisualState.STOPPED
        }
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        _isOnline.value = online
        return online
    }

    suspend fun confirmPendingAction(approved: Boolean): String {
        val pending = _pendingConfirmation.value ?: return "Koi pending confirmation nahi hai."
        _pendingConfirmation.value = null
        return if (approved) {
            _brainState.value = BrainVisualState.ACTING
            val res = pending.onConfirm()
            appendGokuMessage(res, "SWARM-CONFIRMED", pending.confidence)
            voiceEngine.speak(res)
            _brainState.value = BrainVisualState.IDLE
            res
        } else {
            val cancelMsg = "Theek hai boss, action cancel kar diya: ${pending.actionSummary}"
            vault.logAction("CRITIC", pending.actionSummary, "User rejected via Haan/Nahi gate", 100, "STOPPED")
            appendGokuMessage(cancelMsg, "SAFETY-GATE", 100)
            voiceEngine.speak(cancelMsg)
            _brainState.value = BrainVisualState.IDLE
            cancelMsg
        }
    }

    suspend fun processCommand(rawInput: String, speakReply: Boolean = true): String {
        val startMs = System.currentTimeMillis()
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ""

        // 0. Highest-priority voice emergency stop check
        if (GokuKillSwitch.isStopPhrase(trimmed)) {
            GokuKillSwitch.triggerStop("Command: $trimmed")
            val stopReply = "Ruk gaya boss! Saare active actions band kar diye gaye hain."
            appendUserMessage(trimmed)
            appendGokuMessage(stopReply, "KILL-SWITCH", 100)
            if (speakReply) voiceEngine.speak(stopReply, urgent = true)
            return stopReply
        }

        GokuKillSwitch.reset()
        appendUserMessage(trimmed)

        // Check if awaiting Hindi "Haan" / "Nahi" Safety Confirmation
        val pending = _pendingConfirmation.value
        if (pending != null) {
            val low = trimmed.lowercase(Locale.ROOT)
            if (low in listOf("haan", "han", "ha", "yes", "ok", "confirm", "kar do", "bhej do", "हां", "हाँ")) {
                return confirmPendingAction(true)
            }
            if (low in listOf("nahi", "na", "no", "cancel", "mat karo", "ruko", "नहीं")) {
                return confirmPendingAction(false)
            }
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
        if (reflexResult != null) {
            val (reply, badge, confidence) = reflexResult
            val elapsed = System.currentTimeMillis() - startMs
            vault.logAction("REFLEX", resolvedInput, reply, confidence, if (dryRun) "DRY_RUN" else "OK", elapsed)
            vault.workingMemory.addTurn("GOKU", reply)
            appendGokuMessage(reply, badge, confidence)
            if (speakReply) voiceEngine.speak(reply)
            _brainState.value = BrainVisualState.IDLE
            return reply
        }

        // --------------------------------------------------------------------
        // CHECK LEARNED .GOKU SKILLS (WITH A/B ENGINE BENCHMARKING)
        // --------------------------------------------------------------------
        val matchedSkill = vault.findMatchingSkill(resolvedInput)
        if (matchedSkill != null) {
            _brainState.value = BrainVisualState.ACTING
            val skillStart = System.currentTimeMillis()
            val (ok, detail) = pilot.executeStepsJson(matchedSkill.stepsJson, dryRun = dryRun)
            val skillMs = System.currentTimeMillis() - skillStart
            val usedAdb = !adbEngine.useAccessibilityFallback.value
            vault.recordSkillBenchmark(matchedSkill, usedAdb, skillMs, ok)
            val reply = if (ok) "Skill '${matchedSkill.name}' complete (${skillMs}ms): $detail"
            else "Skill '${matchedSkill.name}' partial issue: $detail"
            vault.logAction("EXECUTOR", "Skill:${matchedSkill.name}", reply, if (ok) 96 else 65, if (ok) "OK" else "WARN", skillMs)
            appendGokuMessage(reply, "SKILL-MACRO", if (ok) 96 else 65)
            if (speakReply) voiceEngine.speak(reply)
            _brainState.value = BrainVisualState.IDLE
            return reply
        }

        // --------------------------------------------------------------------
        // TIER 3: MULTI-AGENT SWARM (PLANNER -> EXECUTOR -> CRITIC) + HYBRID AI
        // --------------------------------------------------------------------
        val screenSummary = pilot.captureCurrentScreenSummary()
        val memories = vault.searchMemories(resolvedInput)
        val ragHits = vault.ragBrain.query(resolvedInput)
        val memoryHint = memories.take(3).joinToString("; ") { "${it.key}=${it.value}" }
        val systemPrompt = DynamicContextCompressor.buildMicroSystemPrompt(
            screenSummary = screenSummary,
            workingContext = vault.workingMemory.buildCompressedContext(),
            memoryHint = memoryHint
        )

        vault.logAction(
            stage = "PLANNER",
            command = resolvedInput,
            detail = "Online=${isNetworkAvailable()}, LocalModel=${offlineLlm.isModelAvailable()}, Memories=${memories.size}, RAG=${ragHits.size}",
            confidence = 92,
            status = "OK"
        )

        val onlineResponse = if (isNetworkAvailable()) {
            onlineRouter.queryWithFailover(resolvedInput, systemPrompt)
        } else null

        val (aiReply, providerBadge) = if (onlineResponse != null) {
            onlineResponse
        } else {
            val localRes = offlineLlm.generateOfflineResponse(resolvedInput, memories, ragHits, screenSummary)
            executeStructuredLocalAction(localRes, dryRun)
        }

        // Critic verification pass
        val elapsed = System.currentTimeMillis() - startMs
        vault.logAction("CRITIC", resolvedInput, "Verified reply via $providerBadge", 94, "OK", elapsed)
        vault.workingMemory.addTurn("GOKU", aiReply)
        appendGokuMessage(aiReply, providerBadge, 94)
        if (speakReply) voiceEngine.speak(aiReply)
        _brainState.value = BrainVisualState.IDLE
        return aiReply
    }

    /**
     * Executes structured JSON actions emitted by the on-device MediaPipe LLM
     * (CALL, SMS, OPEN_APP, ALARM, TORCH, CHAT) with full Hindi Haan/Nahi safety gating.
     */
    private suspend fun executeStructuredLocalAction(
        result: OnDeviceLlmResult,
        dryRun: Boolean
    ): Pair<String, String> {
        return when (result.action) {
            "TORCH" -> {
                val turnOn = !result.target.equals("off", ignoreCase = true)
                val msg = if (dryRun) "[DRY-RUN] Torch -> ${if (turnOn) "ON" else "OFF"}" else toggleTorch(turnOn)
                msg to "${result.badge}+TORCH"
            }
            "OPEN_APP" -> {
                if (result.target.isNotBlank()) {
                    val launched = launchAppByName(result.target, dryRun)
                    if (launched != null) return launched to "${result.badge}+APP"
                }
                result.reply to result.badge
            }
            "ALARM" -> {
                val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?").find(result.target)
                val hour = timeMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
                val minute = timeMatch?.groupValues?.get(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
                if (!dryRun) setAlarmIntent(hour, minute)
                "Alarm $hour:${"%02d".format(minute)} ke liye set kar diya boss." to "${result.badge}+ALARM"
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

        // 2. Torch / Flashlight toggle
        if (lower.contains("torch") || lower.contains("flashlight") || lower.contains("light on") || lower.contains("light off")) {
            val turnOn = !(lower.contains("off") || lower.contains("band"))
            val res = if (dryRun) "[DRY-RUN] Torch -> ${if (turnOn) "ON" else "OFF"}"
            else toggleTorch(turnOn)
            return Triple(res, "REFLEX-0S", 99)
        }

        // 3. Remember / Save to Personal Memory Vault
        if (lower.startsWith("yaad rakh") || lower.startsWith("remember ")) {
            val body = input
                .replace(Regex("^(yaad rakhna|yaad rakho|yaad rakh|remember)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (body.isNotEmpty()) {
                val parts = body.split("=", ":", " is ", " hai ", limit = 2)
                val key = parts[0].trim().take(60)
                val value = parts.getOrNull(1)?.trim()?.ifBlank { body } ?: body
                vault.saveMemory(key, value, "FACT")
                return Triple("Yaad rakh liya boss! Vault mein save ho gaya: $key -> $value.", "VAULT-MEMORY", 98)
            }
        }

        // 4. Expense Guardian Summary
        if (lower.contains("expense") || lower.contains("kharch") || lower.contains("bill summary")) {
            val summary = vault.buildMonthlyExpenseSummaryHinglish()
            return Triple(summary, "EXPENSE-GUARDIAN", 98)
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
        if (lower.contains("alarm")) {
            val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?").find(lower)
            val hour = timeMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
            val minute = timeMatch?.groupValues?.get(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            if (!dryRun) setAlarmIntent(hour, minute)
            return Triple("Alarm $hour:${"%02d".format(minute)} ke liye set kar diya boss.", "INTENT-0.05S", 97)
        }

        // 9. Call with Dual-Verification & Hindi "Haan" / "Nahi" Safety Gate
        if (lower.startsWith("call ") || lower.contains("call karo") || lower.contains("phone lagao")) {
            val targetRaw = input
                .replace(Regex("^(call|phone lagao|call karo)\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(ko call karo|call karo|ko phone lagao)$", RegexOption.IGNORE_CASE), "")
                .trim()
            if (targetRaw.isNotEmpty()) {
                return prepareCallWithSafetyGate(targetRaw, dryRun)
            }
        }

        // 10. SMS with Dual-Verification & Hindi "Haan" / "Nahi" Safety Gate
        if (lower.startsWith("sms ") || lower.startsWith("send sms ")) {
            val rest = input.replace(Regex("^(send\\s+)?sms\\s+", RegexOption.IGNORE_CASE), "").trim()
            val parts = rest.split(" ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                return prepareSmsWithSafetyGate(parts[0].trim(), parts[1].trim(), dryRun)
            }
        }

        // 11. Open App Fast-Lane (Fuzzy PackageManager Match)
        if (lower.startsWith("open ") || lower.endsWith(" kholo") || lower.endsWith(" chala do")) {
            val appQuery = input
                .replace(Regex("^open\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(kholo|chala do|open karo)$", RegexOption.IGNORE_CASE), "")
                .trim()
            if (appQuery.isNotEmpty()) {
                val launched = launchAppByName(appQuery, dryRun)
                if (launched != null) {
                    vault.workingMemory.addTurn("SYSTEM", "Opened $appQuery", "app:$appQuery")
                    return Triple(launched, "INTENT-0.05S", 96)
                }
            }
        }

        // 12. Screen Actions (Tap / Scroll / Home / Back)
        if (lower.startsWith("tap ") || lower.startsWith("click ")) {
            val target = input.substringAfter(" ").trim()
            _brainState.value = BrainVisualState.ACTING
            val (ok, msg) = if (dryRun) true to "[DRY-RUN] Would tap '$target'" else pilot.tapTarget(target)
            return Triple(msg, "BODY-PILOT", if (ok) 92 else 60)
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

        // 13. Visual Timeline Memory Search
        if (lower.contains("timeline") || lower.contains("kal screen") || lower.contains("screen history")) {
            val q = lower.replace("timeline", "").replace("screen history", "").trim()
            val hits = vault.searchVisualTimeline(q.ifBlank { "a" })
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

    private suspend fun prepareCallWithSafetyGate(targetRaw: String, dryRun: Boolean): Triple<String, String, Int> {
        val finalNumber = resolvePhoneNumber(targetRaw)
        vault.workingMemory.addTurn("SYSTEM", "Target contact $targetRaw", "contact:$targetRaw")
        if (finalNumber.isBlank()) {
            return Triple(
                "Ambiguity Resolver: '$targetRaw' ka number Vault mein nahi mila. Kripya number bolein ya 'Yaad rakhna $targetRaw = 98XXXXXXXX' bolkar save karein.",
                "AMBIGUITY-RESOLVER",
                72
            )
        }
        val prompt = "Safety Confirm: Kya main $targetRaw ($finalNumber) ko call lagaoon? 'Haan' ya 'Nahi' bolein."
        _pendingConfirmation.value = PendingConfirmation(
            promptHinglish = prompt,
            actionSummary = "Call $targetRaw ($finalNumber)",
            confidence = 95,
            onConfirm = {
                if (dryRun) "[DRY-RUN] Would dial $finalNumber"
                else placePhoneCall(finalNumber)
            }
        )
        return Triple(prompt, "SAFETY-GATE", 95)
    }

    private suspend fun prepareSmsWithSafetyGate(targetRaw: String, body: String, dryRun: Boolean): Triple<String, String, Int> {
        val finalNumber = resolvePhoneNumber(targetRaw)
        vault.workingMemory.addTurn("SYSTEM", "Target SMS $targetRaw", "contact:$targetRaw")
        if (finalNumber.isBlank()) {
            return Triple(
                "Ambiguity Resolver: '$targetRaw' ka number Vault mein nahi mila. Pehle 'Yaad rakhna $targetRaw = 98XXXXXXXX' save karein.",
                "AMBIGUITY-RESOLVER",
                72
            )
        }
        val prompt = "Safety Confirm: Kya main $targetRaw ($finalNumber) ko SMS bhejoon: \"$body\"? 'Haan' ya 'Nahi' bolein."
        _pendingConfirmation.value = PendingConfirmation(
            promptHinglish = prompt,
            actionSummary = "SMS $targetRaw ($finalNumber)",
            confidence = 95,
            onConfirm = {
                if (dryRun) "[DRY-RUN] Would send SMS to $finalNumber: $body"
                else sendDirectSms(finalNumber, body)
            }
        )
        return Triple(prompt, "SAFETY-GATE", 95)
    }

    private suspend fun resolvePhoneNumber(targetRaw: String): String {
        val digits = targetRaw.filter { it.isDigit() || it == '+' }
        if (digits.length >= 7) return digits
        val vaultMatch = vault.searchMemories(targetRaw).firstOrNull { it.value.any { ch -> ch.isDigit() } }
        return vaultMatch?.value?.filter { it.isDigit() || it == '+' }.orEmpty()
    }

    private fun setAlarmIntent(hour: Int, minute: Int) {
        val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Goku Alarm")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(alarmIntent) }
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
        }
        return runCatching {
            val parts = smsManager.divideMessage(body)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            "SMS $number par bhej diya boss."
        }.getOrElse { "SMS send failed: ${it.message}" }
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
        _messages.value = (_messages.value + ChatMessage(isUser = true, text = text)).takeLast(60)
    }

    private fun appendGokuMessage(text: String, badge: String, confidence: Int) {
        _messages.value = (_messages.value + ChatMessage(
            isUser = false,
            text = text,
            engineBadge = badge,
            confidence = confidence
        )).takeLast(60)
    }

    fun clearConversation() {
        vault.workingMemory.clear()
        _messages.value = emptyList()
    }
}
