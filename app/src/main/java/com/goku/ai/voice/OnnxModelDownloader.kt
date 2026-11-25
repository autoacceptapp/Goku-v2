package com.goku.ai.voice

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class ModelDownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    READY,
    ERROR
}

data class ModelDownloadProgress(
    val modelId: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val percentage: Int = 0,
    val speedKBps: Float = 0f,
    val status: ModelDownloadStatus = ModelDownloadStatus.NOT_DOWNLOADED,
    val currentFileName: String = "",
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val errorMessage: String? = null,
    val installedDirPath: String? = null
)

/**
 * WorkManager CoroutineWorker wrapper allowing resilient background ONNX voice pack downloads
 * backed by the exact same resumable HTTP Range + atomic `.part` engine.
 */
class OnnxVoiceDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID)?.trim().orEmpty()
        if (modelId.isBlank()) return Result.failure()
        val downloader = OnnxModelDownloader.getInstance(applicationContext)
        val ok = downloader.downloadBundleBlocking(modelId)
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_MODEL_ID = "onnx_voice_model_id"
    }
}

/**
 * Production-grade, 1-Tap In-App ONNX Model & Voice Pack Downloader.
 *
 * Guarantees:
 * - Permanent storage in `context.filesDir/voice_models/<model_id>/` (never `cacheDir`).
 * - Atomic `.part` writes with HTTP Range (`Range: bytes=$existingBytes-`) pause/resume support.
 * - Handles HTTP 206 Partial Content (append), HTTP 200 OK (clean restart), and HTTP 416 recovery.
 * - Multi-file bundle verification (`.onnx` + `.onnx.json` / `tokens.txt` / `tokenizer.model`).
 * - Real-time `StateFlow<Map<String, ModelDownloadProgress>>` progress, speedKBps & status updates.
 * - Zero-permission SAF local `.onnx` / `.wav` import + 1-tap Activate & Delete controls.
 */
class OnnxModelDownloader(
    context: Context,
    private val httpClient: OkHttpClient = buildDefaultHttpClient()
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val voiceModelsRootDir = File(appContext.filesDir, VOICE_MODELS_DIR_NAME).apply { mkdirs() }

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val pausedFlags = ConcurrentHashMap<String, Boolean>()
    private val cancelledFlags = ConcurrentHashMap<String, Boolean>()

    private val _bundles = MutableStateFlow<List<VoiceModelBundle>>(emptyList())
    val bundles: StateFlow<List<VoiceModelBundle>> = _bundles.asStateFlow()

    private val _progressMap = MutableStateFlow<Map<String, ModelDownloadProgress>>(emptyMap())
    val progressMap: StateFlow<Map<String, ModelDownloadProgress>> = _progressMap.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    init {
        reloadCatalogAndDiskStates()
    }

    /**
     * Scans `filesDir/voice_models/<model_id>/` on startup and after edits so READY / PAUSED /
     * NOT_DOWNLOADED states accurately survive app restarts and phone reboots.
     */
    fun reloadCatalogAndDiskStates() {
        runCatching {
            val customBundles = loadCustomBundlesFromPrefs()
            val merged = (VoiceModelCatalog.PRECONFIGURED_BUNDLES + customBundles)
                .distinctBy { it.id }
            _bundles.value = merged

            val updatedMap = LinkedHashMap<String, ModelDownloadProgress>(merged.size)
            for (bundle in merged) {
                if (activeJobs[bundle.id]?.isActive == true) {
                    _progressMap.value[bundle.id]?.let { updatedMap[bundle.id] = it }
                } else {
                    updatedMap[bundle.id] = inspectBundleOnDisk(bundle)
                }
            }
            _progressMap.value = updatedMap

            val savedActive = prefs.getString(KEY_ACTIVE_MODEL_ID, null)
            if (savedActive != null && updatedMap[savedActive]?.status == ModelDownloadStatus.READY) {
                _activeModelId.value = savedActive
                syncActiveVoicePackToEngine(savedActive)
            } else {
                if (savedActive != null) {
                    prefs.edit().remove(KEY_ACTIVE_MODEL_ID).apply()
                }
                _activeModelId.value = null
            }
        }.onFailure { err ->
            Log.e(TAG, "Failed to reload ONNX voice catalog state", err)
        }
    }

    fun getModelDirectory(modelId: String): File {
        val safeId = VoiceModelCatalog.sanitizeId(modelId)
        val dir = File(voiceModelsRootDir, safeId)
        val rootCanonical = runCatching { voiceModelsRootDir.canonicalPath }.getOrDefault(voiceModelsRootDir.absolutePath)
        val dirCanonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        require(dirCanonical.startsWith(rootCanonical)) { "Invalid model directory path" }
        return dir
    }

    fun getTotalInstalledBytes(): Long {
        return runCatching {
            voiceModelsRootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    /**
     * Starts or resumes a multi-file ONNX bundle download in the background coroutine pool.
     * Duplicate rapid taps while already downloading are safely ignored.
     */
    fun startOrResumeDownload(modelId: String) {
        val bundle = _bundles.value.firstOrNull { it.id == modelId } ?: return
        if (activeJobs[modelId]?.isActive == true) return

        pausedFlags[modelId] = false
        cancelledFlags[modelId] = false

        val job = scope.launch {
            try {
                downloadBundleBlocking(modelId)
            } finally {
                activeJobs.remove(modelId)
                activeCalls.remove(modelId)
            }
        }
        activeJobs[modelId] = job
    }

    /**
     * Optional WorkManager enqueue helper for OS-managed background resume on constrained networks.
     */
    fun enqueueWorkManagerDownload(modelId: String) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<OnnxVoiceDownloadWorker>()
                .setInputData(Data.Builder().putString(OnnxVoiceDownloadWorker.KEY_MODEL_ID, modelId).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("onnx_voice_$modelId")
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                "onnx_voice_download_$modelId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }.onFailure {
            // Fallback directly to in-process coroutine download if WorkManager isn't initialized (e.g. unit tests)
            startOrResumeDownload(modelId)
        }
    }

    /**
     * Executes the full multi-file resumable HTTP Range download synchronously inside a suspend call.
     * Used by both `startOrResumeDownload` and `OnnxVoiceDownloadWorker` (and directly testable).
     */
    suspend fun downloadBundleBlocking(modelId: String): Boolean = withContext(Dispatchers.IO) {
        val bundle = _bundles.value.firstOrNull { it.id == modelId } ?: return@withContext false
        if (bundle.files.isEmpty()) {
            emitProgress(
                ModelDownloadProgress(
                    modelId = modelId,
                    status = ModelDownloadStatus.ERROR,
                    errorMessage = "Bundle has no downloadable files."
                )
            )
            return@withContext false
        }

        val modelDir = try {
            getModelDirectory(bundle.id).apply { mkdirs() }
        } catch (t: Throwable) {
            emitProgress(
                ModelDownloadProgress(
                    modelId = modelId,
                    status = ModelDownloadStatus.ERROR,
                    errorMessage = "Storage error: ${t.message}"
                )
            )
            return@withContext false
        }

        pausedFlags[modelId] = false
        cancelledFlags[modelId] = false

        // Estimate combined total bytes across bundle files (refined as each Content-Length arrives)
        val fileExpectedTotals = LongArray(bundle.files.size) { idx ->
            val f = safeChildFile(modelDir, bundle.files[idx].fileName)
            when {
                f.exists() && f.length() >= bundle.files[idx].expectedMinBytes -> f.length()
                else -> bundle.files[idx].expectedMinBytes
            }
        }
        val estimatedBundleTotal = maxOf(bundle.totalSizeBytes, fileExpectedTotals.sum()).coerceAtLeast(1L)

        var completedFilesCount = 0
        var completedBytesBeforeCurrent = 0L

        for ((index, spec) in bundle.files.withIndex()) {
            if (!isActive || pausedFlags[modelId] == true || cancelledFlags[modelId] == true) {
                break
            }

            val finalFile = safeChildFile(modelDir, spec.fileName)
            val partFile = safeChildFile(modelDir, "${spec.fileName}.part")

            // Skip already-verified files on multi-file bundle resume
            if (finalFile.exists() && finalFile.length() >= spec.expectedMinBytes && !partFile.exists()) {
                completedFilesCount++
                completedBytesBeforeCurrent += finalFile.length()
                fileExpectedTotals[index] = finalFile.length()
                continue
            }

            val existingPartBytes = if (partFile.exists()) partFile.length() else 0L
            val currentTotal = maxOf(estimatedBundleTotal, completedBytesBeforeCurrent + existingPartBytes)
            emitProgress(
                ModelDownloadProgress(
                    modelId = modelId,
                    downloadedBytes = completedBytesBeforeCurrent + existingPartBytes,
                    totalBytes = currentTotal,
                    percentage = computePct(completedBytesBeforeCurrent + existingPartBytes, currentTotal),
                    speedKBps = 0f,
                    status = ModelDownloadStatus.DOWNLOADING,
                    currentFileName = spec.fileName,
                    completedFiles = completedFilesCount,
                    totalFiles = bundle.files.size,
                    installedDirPath = modelDir.absolutePath
                )
            )

            val fileOk = downloadSingleFileWithResume(
                bundle = bundle,
                spec = spec,
                fileIndex = index,
                finalFile = finalFile,
                partFile = partFile,
                completedFilesCount = completedFilesCount,
                completedBytesBeforeCurrent = completedBytesBeforeCurrent,
                estimatedBundleTotal = estimatedBundleTotal,
                retryOn416 = true
            )

            if (!fileOk) {
                if (cancelledFlags[modelId] == true) {
                    cleanupPartFiles(modelDir)
                    emitProgress(inspectBundleOnDisk(bundle))
                    return@withContext false
                }
                if (pausedFlags[modelId] == true) {
                    val pausedState = inspectBundleOnDisk(bundle).copy(status = ModelDownloadStatus.PAUSED)
                    emitProgress(pausedState)
                    return@withContext false
                }
                return@withContext false
            }

            completedFilesCount++
            completedBytesBeforeCurrent += finalFile.length()
        }

        if (cancelledFlags[modelId] == true) {
            cleanupPartFiles(modelDir)
            emitProgress(inspectBundleOnDisk(bundle))
            return@withContext false
        }
        if (pausedFlags[modelId] == true) {
            emitProgress(inspectBundleOnDisk(bundle).copy(status = ModelDownloadStatus.PAUSED))
            return@withContext false
        }

        // Final bundle integrity verification
        emitProgress(
            ModelDownloadProgress(
                modelId = modelId,
                downloadedBytes = completedBytesBeforeCurrent,
                totalBytes = completedBytesBeforeCurrent.coerceAtLeast(1L),
                percentage = 100,
                speedKBps = 0f,
                status = ModelDownloadStatus.VERIFYING,
                currentFileName = "Verifying bundle...",
                completedFiles = completedFilesCount,
                totalFiles = bundle.files.size,
                installedDirPath = modelDir.absolutePath
            )
        )

        val allVerified = bundle.files.all { spec ->
            val f = safeChildFile(modelDir, spec.fileName)
            f.exists() && f.isFile && f.length() >= spec.expectedMinBytes
        }

        return@withContext if (allVerified) {
            val actualSize = bundle.files.sumOf { safeChildFile(modelDir, it.fileName).length() }
            emitProgress(
                ModelDownloadProgress(
                    modelId = modelId,
                    downloadedBytes = actualSize,
                    totalBytes = actualSize.coerceAtLeast(1L),
                    percentage = 100,
                    speedKBps = 0f,
                    status = ModelDownloadStatus.READY,
                    currentFileName = "",
                    completedFiles = bundle.files.size,
                    totalFiles = bundle.files.size,
                    installedDirPath = modelDir.absolutePath
                )
            )
            true
        } else {
            emitProgress(
                ModelDownloadProgress(
                    modelId = modelId,
                    downloadedBytes = completedBytesBeforeCurrent,
                    totalBytes = estimatedBundleTotal,
                    percentage = computePct(completedBytesBeforeCurrent, estimatedBundleTotal),
                    status = ModelDownloadStatus.ERROR,
                    errorMessage = "Bundle verification failed (missing or incomplete file). Retry download."
                )
            )
            false
        }
    }

    private fun downloadSingleFileWithResume(
        bundle: VoiceModelBundle,
        spec: ModelFileSpec,
        fileIndex: Int,
        finalFile: File,
        partFile: File,
        completedFilesCount: Int,
        completedBytesBeforeCurrent: Long,
        estimatedBundleTotal: Long,
        retryOn416: Boolean
    ): Boolean {
        val modelId = bundle.id
        var existingBytes = if (partFile.exists()) partFile.length() else 0L

        return try {
            val requestBuilder = Request.Builder()
                .url(spec.downloadUrl)
                .header("User-Agent", "GokuAI-Android/10.1")
                .header("Accept-Encoding", "identity")
            if (existingBytes > 0L) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            val call = httpClient.newCall(requestBuilder.build())
            activeCalls[modelId] = call

            call.execute().use { response ->
                val code = response.code

                // Handle HTTP 416 Range Not Satisfiable gracefully
                if (code == 416) {
                    val remoteTotal = parseTotalFromContentRange(response.header("Content-Range"))
                    if (remoteTotal > 0L && existingBytes == remoteTotal && existingBytes >= spec.expectedMinBytes) {
                        return verifyAndPromotePartFile(
                            bundle = bundle,
                            spec = spec,
                            partFile = partFile,
                            finalFile = finalFile,
                            expectedServerBytes = remoteTotal,
                            completedFilesCount = completedFilesCount,
                            completedBytesBeforeCurrent = completedBytesBeforeCurrent,
                            estimatedBundleTotal = estimatedBundleTotal
                        )
                    }
                    // Stale/corrupt .part file: delete and retry cleanly from byte 0 once
                    runCatching { partFile.delete() }
                    if (retryOn416) {
                        return downloadSingleFileWithResume(
                            bundle = bundle,
                            spec = spec,
                            fileIndex = fileIndex,
                            finalFile = finalFile,
                            partFile = partFile,
                            completedFilesCount = completedFilesCount,
                            completedBytesBeforeCurrent = completedBytesBeforeCurrent,
                            estimatedBundleTotal = estimatedBundleTotal,
                            retryOn416 = false
                        )
                    }
                    emitError(modelId, "Server returned HTTP 416 Range Not Satisfiable for ${spec.fileName}.")
                    return false
                }

                if (code != 200 && code != 206) {
                    val hint = when (code) {
                        401, 403 -> "HTTP $code: Repo gated/private hai. Open-access Hugging Face resolve/main/ URL use karein."
                        404 -> "HTTP 404: File URL nahi mila (${spec.fileName})."
                        429 -> "HTTP 429: Server rate-limited. Thodi der baad Resume dabayein."
                        else -> "HTTP $code download error for ${spec.fileName}."
                    }
                    emitError(modelId, hint)
                    return false
                }

                val body = response.body ?: run {
                    emitError(modelId, "Empty response body for ${spec.fileName}.")
                    return false
                }

                val isPartialAppend = (code == 206 && existingBytes > 0L)
                if (!isPartialAppend) {
                    // HTTP 200 OK: server ignored Range or fresh download — restart cleanly from byte 0
                    existingBytes = 0L
                }

                val contentLength = body.contentLength()
                val expectedFileTotal = when {
                    isPartialAppend -> {
                        val rangeTotal = parseTotalFromContentRange(response.header("Content-Range"))
                        when {
                            rangeTotal > 0L -> rangeTotal
                            contentLength > 0L -> existingBytes + contentLength
                            else -> -1L
                        }
                    }
                    contentLength > 0L -> contentLength
                    else -> -1L
                }

                val remainingMinBytes = bundle.files.drop(fileIndex + 1).sumOf { it.expectedMinBytes }
                val effectiveBundleTotal = if (expectedFileTotal > 0L) {
                    maxOf(
                        estimatedBundleTotal,
                        completedBytesBeforeCurrent + expectedFileTotal + remainingMinBytes
                    )
                } else {
                    estimatedBundleTotal
                }

                var fileWritten = existingBytes
                var windowBytes = 0L
                var windowStartNs = System.nanoTime()
                var lastEmitMs = 0L
                var currentSpeedKBps = 0f

                body.byteStream().use { input ->
                    FileOutputStream(partFile, isPartialAppend).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            if (pausedFlags[modelId] == true || cancelledFlags[modelId] == true) {
                                output.flush()
                                return false
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            fileWritten += read
                            windowBytes += read

                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastEmitMs >= 200L) {
                                val elapsedSec = ((System.nanoTime() - windowStartNs) / 1_000_000_000.0)
                                    .coerceAtLeast(0.05)
                                currentSpeedKBps = ((windowBytes / 1024.0) / elapsedSec).toFloat()
                                windowBytes = 0L
                                windowStartNs = System.nanoTime()
                                lastEmitMs = nowMs

                                val totalDownloaded = completedBytesBeforeCurrent + fileWritten
                                val denom = maxOf(effectiveBundleTotal, totalDownloaded).coerceAtLeast(1L)
                                emitProgress(
                                    ModelDownloadProgress(
                                        modelId = modelId,
                                        downloadedBytes = totalDownloaded,
                                        totalBytes = denom,
                                        percentage = computePct(totalDownloaded, denom).coerceAtMost(99),
                                        speedKBps = currentSpeedKBps,
                                        status = ModelDownloadStatus.DOWNLOADING,
                                        currentFileName = spec.fileName,
                                        completedFiles = completedFilesCount,
                                        totalFiles = bundle.files.size,
                                        installedDirPath = finalFile.parentFile?.absolutePath
                                    )
                                )
                            }
                        }
                        output.flush()
                    }
                }

                if (pausedFlags[modelId] == true || cancelledFlags[modelId] == true) {
                    return false
                }

                verifyAndPromotePartFile(
                    bundle = bundle,
                    spec = spec,
                    partFile = partFile,
                    finalFile = finalFile,
                    expectedServerBytes = expectedFileTotal,
                    completedFilesCount = completedFilesCount,
                    completedBytesBeforeCurrent = completedBytesBeforeCurrent,
                    estimatedBundleTotal = effectiveBundleTotal
                )
            }
        } catch (ce: CancellationException) {
            if (pausedFlags[modelId] == true || cancelledFlags[modelId] == true) {
                false
            } else {
                throw ce
            }
        } catch (ioe: IOException) {
            if (pausedFlags[modelId] == true || cancelledFlags[modelId] == true) {
                false
            } else {
                val partialDisk = inspectBundleOnDisk(bundle)
                emitProgress(
                    partialDisk.copy(
                        status = ModelDownloadStatus.ERROR,
                        speedKBps = 0f,
                        currentFileName = spec.fileName,
                        errorMessage = "Network dropped (${ioe.message ?: "IO error"}). Tap Resume — saved progress is safe."
                    )
                )
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected download failure for $modelId/${spec.fileName}", t)
            emitError(modelId, "Download failed: ${t.message ?: t.javaClass.simpleName}")
            false
        } finally {
            activeCalls.remove(modelId)
        }
    }

    private fun verifyAndPromotePartFile(
        bundle: VoiceModelBundle,
        spec: ModelFileSpec,
        partFile: File,
        finalFile: File,
        expectedServerBytes: Long,
        completedFilesCount: Int,
        completedBytesBeforeCurrent: Long,
        estimatedBundleTotal: Long
    ): Boolean {
        val modelId = bundle.id
        val actualLength = partFile.length()
        emitProgress(
            ModelDownloadProgress(
                modelId = modelId,
                downloadedBytes = completedBytesBeforeCurrent + actualLength,
                totalBytes = maxOf(estimatedBundleTotal, completedBytesBeforeCurrent + actualLength).coerceAtLeast(1L),
                percentage = computePct(
                    completedBytesBeforeCurrent + actualLength,
                    maxOf(estimatedBundleTotal, completedBytesBeforeCurrent + actualLength)
                ),
                speedKBps = 0f,
                status = ModelDownloadStatus.VERIFYING,
                currentFileName = "Verifying ${spec.fileName}...",
                completedFiles = completedFilesCount,
                totalFiles = bundle.files.size,
                installedDirPath = finalFile.parentFile?.absolutePath
            )
        )

        if (actualLength <= 0L || actualLength < spec.expectedMinBytes) {
            runCatching { partFile.delete() }
            emitError(
                modelId,
                "Verification failed for ${spec.fileName}: received ${VoiceModelCatalog.formatBytesHuman(actualLength)}, expected at least ${VoiceModelCatalog.formatBytesHuman(spec.expectedMinBytes)}."
            )
            return false
        }

        if (expectedServerBytes > 0L && actualLength != expectedServerBytes) {
            emitProgress(
                inspectBundleOnDisk(bundle).copy(
                    status = ModelDownloadStatus.ERROR,
                    errorMessage = "Incomplete stream for ${spec.fileName} ($actualLength / $expectedServerBytes bytes). Tap Resume."
                )
            )
            return false
        }

        val promoted = atomicRenameFile(partFile, finalFile)
        if (!promoted) {
            emitError(modelId, "Failed to finalize ${spec.fileName} on disk.")
            return false
        }
        return true
    }

    fun pauseDownload(modelId: String) {
        pausedFlags[modelId] = true
        runCatching { activeCalls.remove(modelId)?.cancel() }
        runCatching { activeJobs.remove(modelId)?.cancel() }
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork("onnx_voice_download_$modelId") }

        val bundle = _bundles.value.firstOrNull { it.id == modelId } ?: return
        val onDisk = inspectBundleOnDisk(bundle)
        if (onDisk.status != ModelDownloadStatus.READY) {
            emitProgress(
                onDisk.copy(
                    status = if (onDisk.downloadedBytes > 0L) ModelDownloadStatus.PAUSED else ModelDownloadStatus.NOT_DOWNLOADED,
                    speedKBps = 0f,
                    errorMessage = null
                )
            )
        }
    }

    fun cancelAndClean(modelId: String) {
        cancelledFlags[modelId] = true
        pausedFlags[modelId] = false
        runCatching { activeCalls.remove(modelId)?.cancel() }
        runCatching { activeJobs.remove(modelId)?.cancel() }
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork("onnx_voice_download_$modelId") }

        val bundle = _bundles.value.firstOrNull { it.id == modelId } ?: return
        runCatching {
            val dir = getModelDirectory(modelId)
            cleanupPartFiles(dir)
            // If no final files exist, remove empty folder cleanly
            if (dir.exists() && dir.listFiles().isNullOrEmpty()) {
                dir.delete()
            }
        }
        emitProgress(inspectBundleOnDisk(bundle))
    }

    /**
     * 1-Tap Storage Cleanup: cancels any active download, recursively removes `filesDir/voice_models/<model_id>/`,
     * deactivates the pack if active, and resets state to `NOT_DOWNLOADED`.
     */
    fun deleteInstalledModel(modelId: String): Boolean {
        cancelledFlags[modelId] = true
        pausedFlags[modelId] = false
        runCatching { activeCalls.remove(modelId)?.cancel() }
        runCatching { activeJobs.remove(modelId)?.cancel() }
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork("onnx_voice_download_$modelId") }

        val deleted = runCatching {
            val dir = getModelDirectory(modelId)
            if (dir.exists()) dir.deleteRecursively() else true
        }.getOrDefault(false)

        if (_activeModelId.value == modelId) {
            _activeModelId.value = null
            prefs.edit().remove(KEY_ACTIVE_MODEL_ID).apply()
            VoicePackManager.getInstance(appContext).clearActiveVoicePack()
        }

        val bundle = _bundles.value.firstOrNull { it.id == modelId }
        if (bundle != null) {
            emitProgress(
                ModelDownloadProgress(
                    modelId = modelId,
                    downloadedBytes = 0L,
                    totalBytes = bundle.totalSizeBytes,
                    percentage = 0,
                    speedKBps = 0f,
                    status = ModelDownloadStatus.NOT_DOWNLOADED,
                    completedFiles = 0,
                    totalFiles = bundle.files.size
                )
            )
        }
        return deleted
    }

    /**
     * Activates a verified READY voice pack or 24x7 KWS bundle and connects it cleanly to
     * `VoicePackManager` / `GokuCustomVoiceEngine` / `GokuEarAndVoiceEngine`.
     */
    fun activateModel(modelId: String): Boolean {
        val bundle = _bundles.value.firstOrNull { it.id == modelId } ?: return false
        val onDisk = inspectBundleOnDisk(bundle)
        if (onDisk.status != ModelDownloadStatus.READY) {
            emitProgress(
                onDisk.copy(
                    errorMessage = "Model download complete nahi hua. Pehle download finish karein."
                )
            )
            return false
        }

        return runCatching {
            val dir = getModelDirectory(bundle.id)
            prefs.edit().putString(KEY_ACTIVE_MODEL_ID, bundle.id).apply()
            _activeModelId.value = bundle.id
            syncActiveVoicePackToEngine(bundle.id)

            // If this is a Sherpa-ONNX 24x7 KWS bundle, mirror verified files into filesDir/models/kws/
            // so LockScreenWakeWordEngine / SherpaOnnxKwsBridge picks them up immediately.
            if (bundle.category == VoiceBundleCategory.WAKE_WORD_KWS) {
                val kwsDir = File(appContext.filesDir, "models/kws").apply { mkdirs() }
                bundle.files.forEach { spec ->
                    val src = safeChildFile(dir, spec.fileName)
                    val dst = safeChildFile(kwsDir, spec.fileName)
                    if (src.exists()) {
                        src.copyTo(dst, overwrite = true)
                    }
                }
                GokuEarAndVoiceEngine.getInstance(appContext).refreshWakeWordDiagnostics()
            }
            true
        }.getOrElse { err ->
            Log.e(TAG, "Failed to activate voice model $modelId", err)
            false
        }
    }

    /**
     * Registers a custom Hugging Face `resolve/main/` URL bundle into the catalog and starts downloading.
     */
    fun addCustomUrlBundle(
        primaryUrl: String,
        displayName: String = "",
        companionUrl: String? = null,
        autoStartDownload: Boolean = true
    ): Result<VoiceModelBundle> {
        val created = VoiceModelCatalog.createCustomUrlBundle(
            primaryUrl = primaryUrl,
            displayName = displayName,
            companionUrl = companionUrl
        )
        val bundle = created.getOrElse { return Result.failure(it) }

        val updatedCustom = (loadCustomBundlesFromPrefs().filterNot { it.id == bundle.id } + bundle)
        saveCustomBundlesToPrefs(updatedCustom)

        _bundles.update { list -> (list.filterNot { it.id == bundle.id } + bundle) }
        emitProgress(inspectBundleOnDisk(bundle))

        if (autoStartDownload) {
            startOrResumeDownload(bundle.id)
        }
        return Result.success(bundle)
    }

    /**
     * Zero-permission Storage Access Framework (SAF) local `.onnx` / `.wav` / `tokens.txt` importer.
     * Copies stream atomically via `.part` into permanent `filesDir/voice_models/<id>/`.
     */
    fun importLocalFile(uri: Uri, onComplete: (Result<VoiceModelBundle>) -> Unit = {}) {
        scope.launch {
            val result = runCatching {
                val resolvedName = resolveDisplayName(uri) ?: "imported_voice_${System.currentTimeMillis()}.onnx"
                val safeFileName = VoiceModelCatalog.sanitizeFileName(resolvedName, "imported_voice.onnx")
                val lower = safeFileName.lowercase(Locale.ROOT)
                require(
                    lower.endsWith(".onnx") || lower.endsWith(".wav") ||
                        lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".model")
                ) {
                    "Unsupported file '$safeFileName'. Please pick a .onnx, .wav, tokens.txt, or tokenizer.model file."
                }

                val bundleTitle = safeFileName.substringBeforeLast('.')
                    .replace('_', ' ')
                    .replace('-', ' ')
                    .replaceFirstChar { it.uppercaseChar() }
                val modelId = VoiceModelCatalog.sanitizeId("local_${safeFileName.substringBeforeLast('.')}_${System.currentTimeMillis() % 100000}")
                val targetDir = getModelDirectory(modelId).apply { mkdirs() }
                val partFile = safeChildFile(targetDir, "$safeFileName.part")
                val finalFile = safeChildFile(targetDir, safeFileName)

                var copiedBytes = 0L
                val inputStream = appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("Unable to read selected file.")
                inputStream.use { input ->
                    FileOutputStream(partFile, false).use { output ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            copiedBytes += n
                        }
                        output.flush()
                    }
                }
                require(copiedBytes > 0L) { "Selected file is empty (0 bytes)." }
                check(atomicRenameFile(partFile, finalFile)) { "Failed to finalize imported file on disk." }

                val bundle = VoiceModelBundle(
                    id = modelId,
                    displayName = "$bundleTitle (Local Import)",
                    description = "Imported local file ($safeFileName · ${VoiceModelCatalog.formatBytesHuman(copiedBytes)})",
                    category = if (lower.endsWith(".wav")) VoiceBundleCategory.CUSTOM_VOICE_SAMPLE else VoiceBundleCategory.TTS_VOICE_PACK,
                    totalSizeBytes = copiedBytes,
                    files = listOf(
                        ModelFileSpec(
                            fileName = safeFileName,
                            downloadUrl = "local://$safeFileName",
                            expectedMinBytes = 1L
                        )
                    ),
                    isCustom = true
                )

                val updatedCustom = (loadCustomBundlesFromPrefs().filterNot { it.id == bundle.id } + bundle)
                saveCustomBundlesToPrefs(updatedCustom)
                _bundles.update { list -> (list.filterNot { it.id == bundle.id } + bundle) }
                emitProgress(
                    ModelDownloadProgress(
                        modelId = bundle.id,
                        downloadedBytes = copiedBytes,
                        totalBytes = copiedBytes,
                        percentage = 100,
                        speedKBps = 0f,
                        status = ModelDownloadStatus.READY,
                        completedFiles = 1,
                        totalFiles = 1,
                        installedDirPath = targetDir.absolutePath
                    )
                )
                bundle
            }
            onComplete(result)
        }
    }

    private fun syncActiveVoicePackToEngine(modelId: String) {
        val bundle = _bundles.value.firstOrNull { it.id == modelId } ?: return
        val dir = runCatching { getModelDirectory(bundle.id) }.getOrNull() ?: return
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()
        val descriptor = ActiveVoicePackDescriptor(
            modelId = bundle.id,
            displayName = bundle.displayName,
            category = bundle.category,
            installDirPath = dir.absolutePath,
            onnxFilePaths = files.filter { it.name.endsWith(".onnx", ignoreCase = true) }.map { it.absolutePath },
            tokenizerOrConfigPaths = files.filter {
                it.name.endsWith(".json", ignoreCase = true) ||
                    it.name.endsWith(".txt", ignoreCase = true) ||
                    it.name.endsWith(".model", ignoreCase = true)
            }.map { it.absolutePath },
            wavSamplePaths = files.filter { it.name.endsWith(".wav", ignoreCase = true) }.map { it.absolutePath }
        )
        VoicePackManager.getInstance(appContext).setActiveVoicePack(descriptor)
    }

    private fun inspectBundleOnDisk(bundle: VoiceModelBundle): ModelDownloadProgress {
        val dir = runCatching { getModelDirectory(bundle.id) }.getOrNull()
        if (dir == null || !dir.exists()) {
            return ModelDownloadProgress(
                modelId = bundle.id,
                downloadedBytes = 0L,
                totalBytes = bundle.totalSizeBytes,
                percentage = 0,
                status = ModelDownloadStatus.NOT_DOWNLOADED,
                completedFiles = 0,
                totalFiles = bundle.files.size
            )
        }

        var completedFiles = 0
        var totalDiskBytes = 0L
        var hasAnyPart = false
        var activePartName = ""

        for (spec in bundle.files) {
            val finalFile = safeChildFile(dir, spec.fileName)
            val partFile = safeChildFile(dir, "${spec.fileName}.part")
            if (finalFile.exists() && finalFile.isFile && finalFile.length() >= spec.expectedMinBytes && !partFile.exists()) {
                completedFiles++
                totalDiskBytes += finalFile.length()
            } else if (partFile.exists() && partFile.isFile && partFile.length() > 0L) {
                hasAnyPart = true
                activePartName = spec.fileName
                totalDiskBytes += partFile.length()
            }
        }

        val allReady = bundle.files.isNotEmpty() && completedFiles == bundle.files.size
        val denom = if (allReady) totalDiskBytes.coerceAtLeast(1L) else maxOf(bundle.totalSizeBytes, totalDiskBytes).coerceAtLeast(1L)
        val status = when {
            allReady -> ModelDownloadStatus.READY
            hasAnyPart || completedFiles > 0 -> ModelDownloadStatus.PAUSED
            else -> ModelDownloadStatus.NOT_DOWNLOADED
        }

        return ModelDownloadProgress(
            modelId = bundle.id,
            downloadedBytes = totalDiskBytes,
            totalBytes = denom,
            percentage = if (allReady) 100 else computePct(totalDiskBytes, denom),
            speedKBps = 0f,
            status = status,
            currentFileName = activePartName,
            completedFiles = completedFiles,
            totalFiles = bundle.files.size,
            installedDirPath = if (allReady || hasAnyPart || completedFiles > 0) dir.absolutePath else null
        )
    }

    private fun emitProgress(progress: ModelDownloadProgress) {
        _progressMap.update { current ->
            val next = LinkedHashMap(current)
            next[progress.modelId] = progress
            next
        }
    }

    private fun emitError(modelId: String, message: String) {
        val current = _progressMap.value[modelId]
        emitProgress(
            (current ?: ModelDownloadProgress(modelId = modelId)).copy(
                status = ModelDownloadStatus.ERROR,
                speedKBps = 0f,
                errorMessage = message
            )
        )
    }

    private fun safeChildFile(dir: File, rawFileName: String): File {
        val safeName = VoiceModelCatalog.sanitizeFileName(rawFileName, "asset.bin")
        val target = File(dir, safeName)
        val dirCanonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        val targetCanonical = runCatching { target.canonicalPath }.getOrDefault(target.absolutePath)
        require(targetCanonical.startsWith(dirCanonical)) { "Path traversal blocked for $rawFileName" }
        return target
    }

    private fun cleanupPartFiles(dir: File) {
        runCatching {
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }?.forEach { it.delete() }
        }
    }

    private fun atomicRenameFile(partFile: File, finalFile: File): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Files.move(
                        partFile.toPath(),
                        finalFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                    return@runCatching true
                } catch (_: Throwable) {
                    Files.move(
                        partFile.toPath(),
                        finalFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    return@runCatching true
                }
            }
            if (finalFile.exists()) finalFile.delete()
            partFile.renameTo(finalFile)
        }.getOrDefault(false)
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun loadCustomBundlesFromPrefs(): List<VoiceModelBundle> {
        val raw = prefs.getString(KEY_CUSTOM_BUNDLES_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val filesArr = obj.optJSONArray("files") ?: continue
                    val files = buildList(filesArr.length()) {
                        for (j in 0 until filesArr.length()) {
                            val f = filesArr.optJSONObject(j) ?: continue
                            add(
                                ModelFileSpec(
                                    fileName = f.optString("fileName"),
                                    downloadUrl = f.optString("downloadUrl"),
                                    expectedMinBytes = f.optLong("expectedMinBytes", 1L)
                                )
                            )
                        }
                    }
                    if (files.isEmpty()) continue
                    val catName = obj.optString("category", VoiceBundleCategory.TTS_VOICE_PACK.name)
                    val category = VoiceBundleCategory.entries.firstOrNull { it.name == catName }
                        ?: VoiceBundleCategory.TTS_VOICE_PACK
                    add(
                        VoiceModelBundle(
                            id = obj.optString("id"),
                            displayName = obj.optString("displayName"),
                            description = obj.optString("description"),
                            category = category,
                            totalSizeBytes = obj.optLong("totalSizeBytes", 1024L),
                            files = files,
                            isCustom = true
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveCustomBundlesToPrefs(bundles: List<VoiceModelBundle>) {
        runCatching {
            val arr = JSONArray()
            bundles.forEach { b ->
                val filesArr = JSONArray()
                b.files.forEach { f ->
                    filesArr.put(
                        JSONObject().apply {
                            put("fileName", f.fileName)
                            put("downloadUrl", f.downloadUrl)
                            put("expectedMinBytes", f.expectedMinBytes)
                        }
                    )
                }
                arr.put(
                    JSONObject().apply {
                        put("id", b.id)
                        put("displayName", b.displayName)
                        put("description", b.description)
                        put("category", b.category.name)
                        put("totalSizeBytes", b.totalSizeBytes)
                        put("files", filesArr)
                    }
                )
            }
            prefs.edit().putString(KEY_CUSTOM_BUNDLES_JSON, arr.toString()).apply()
        }
    }

    companion object {
        private const val TAG = "OnnxModelDownloader"
        private const val PREFS_NAME = "goku_onnx_voice_downloader_prefs"
        private const val VOICE_MODELS_DIR_NAME = "voice_models"
        private const val KEY_ACTIVE_MODEL_ID = "active_voice_model_id"
        private const val KEY_CUSTOM_BUNDLES_JSON = "custom_voice_bundles_json"

        @Volatile
        private var INSTANCE: OnnxModelDownloader? = null

        fun getInstance(context: Context): OnnxModelDownloader {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OnnxModelDownloader(context.applicationContext).also { INSTANCE = it }
            }
        }

        internal fun buildDefaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

        internal fun parseTotalFromContentRange(header: String?): Long {
            if (header.isNullOrBlank()) return -1L
            // Examples: "bytes 100-499/1000" or "bytes */1000"
            val slash = header.lastIndexOf('/')
            if (slash < 0 || slash == header.lastIndex) return -1L
            val totalPart = header.substring(slash + 1).trim()
            if (totalPart == "*") return -1L
            return totalPart.toLongOrNull() ?: -1L
        }

        internal fun computePct(downloaded: Long, total: Long): Int {
            if (total <= 0L) return 0
            return ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
        }
    }
}
