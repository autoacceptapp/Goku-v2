package com.goku.ai.voice

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

private val Context.gokuVoicePackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "goku_voice_pack_prefs"
)

/**
 * Parsed PCM WAV header metadata used to validate user-imported `reference_voice.wav` samples.
 */
data class WavHeaderInfo(
    val sampleRateHz: Int,
    val numChannels: Int,
    val bitsPerSample: Int,
    val dataChunkBytes: Int
) {
    val durationSec: Float
        get() {
            val bytesPerSec = sampleRateHz * numChannels * (bitsPerSample / 8)
            return if (bytesPerSec > 0) dataChunkBytes.toFloat() / bytesPerSec.toFloat() else 0f
        }
}

/**
 * Persisted metadata describing the currently active custom voice pack (imported `reference_voice.wav`
 * and/or local/downloaded `.onnx` voice synthesis bundle).
 */
data class VoicePackMetadata(
    val packId: String,
    val displayName: String,
    val category: VoiceBundleCategory,
    val installDirPath: String,
    val referenceWavPath: String? = null,
    val referenceSampleRateHz: Int = 0,
    val onnxModelPath: String? = null,
    val configOrTokensPath: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun hasPlayableAssets(): Boolean {
        val wavOk = !referenceWavPath.isNullOrBlank() && File(referenceWavPath).let { it.exists() && it.length() > 44L }
        val onnxOk = !onnxModelPath.isNullOrBlank() && File(onnxModelPath).let { it.exists() && it.length() > 0L }
        return wavOk || onnxOk
    }
}

/**
 * Manages user-imported custom voice packs stored permanently in `context.filesDir/voice_pack/`
 * as well as downloaded ONNX voice bundles activated via `OnnxModelDownloader`.
 *
 * - Zero copyrighted audio or `.onnx` weights are bundled in the APK.
 * - Uses zero-permission Android Storage Access Framework (`ContentResolver.openInputStream`)
 *   with strict RIFF/WAVE validation (16kHz or 24kHz mono 16-bit PCM) and atomic `.part` writes.
 * - Persists active voice pack metadata in Jetpack DataStore with SharedPreferences backup fallback.
 */
class VoicePackManager(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore = appContext.gokuVoicePackDataStore
    private val fallbackPrefs = appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
    val voicePackDir: File = File(appContext.filesDir, VOICE_PACK_DIR_NAME).apply { mkdirs() }

    private val _activeVoicePack = MutableStateFlow<ActiveVoicePackDescriptor?>(null)
    val activeVoicePack: StateFlow<ActiveVoicePackDescriptor?> = _activeVoicePack.asStateFlow()

    private val _activeMetadata = MutableStateFlow<VoicePackMetadata?>(null)
    val activeMetadata: StateFlow<VoicePackMetadata?> = _activeMetadata.asStateFlow()

    private object Keys {
        val PACK_ID = stringPreferencesKey("vp_pack_id")
        val DISPLAY_NAME = stringPreferencesKey("vp_display_name")
        val CATEGORY = stringPreferencesKey("vp_category")
        val INSTALL_DIR = stringPreferencesKey("vp_install_dir")
        val REF_WAV_PATH = stringPreferencesKey("vp_ref_wav_path")
        val REF_SAMPLE_RATE = intPreferencesKey("vp_ref_sample_rate")
        val ONNX_PATHS_JSON = stringPreferencesKey("vp_onnx_paths_json")
        val CONFIG_PATHS_JSON = stringPreferencesKey("vp_config_paths_json")
        val WAV_PATHS_JSON = stringPreferencesKey("vp_wav_paths_json")
        val UPDATED_AT = longPreferencesKey("vp_updated_at")
    }

    init {
        restoreFromFallbackPrefsSync()
        scope.launch {
            restoreFromDataStore()
        }
    }

    /**
     * Imports a user-selected `.wav` reference audio file (16kHz or 24kHz mono 16-bit PCM)
     * into `context.filesDir/voice_pack/reference_voice.wav` using SAF `ContentResolver`
     * with atomic `.part` write and full WAV header validation.
     */
    suspend fun importReferenceVoiceWav(
        uri: Uri,
        customDisplayName: String? = null
    ): Result<ActiveVoicePackDescriptor> = withContext(Dispatchers.IO) {
        runCatching {
            if (!voicePackDir.exists()) voicePackDir.mkdirs()
            val partFile = File(voicePackDir, "$REFERENCE_WAV_FILENAME.part")
            val finalFile = File(voicePackDir, REFERENCE_WAV_FILENAME)

            copyUriAtomicallyToPart(uri, partFile)
            val wavInfo = validateReferenceWavFile(partFile).getOrElse { err ->
                runCatching { partFile.delete() }
                throw err
            }

            check(atomicMoveFile(partFile, finalFile)) {
                "Failed to finalize $REFERENCE_WAV_FILENAME in app storage."
            }

            val sourceLabel = customDisplayName?.trim().takeIf { !it.isNullOrBlank() }
                ?: resolveDisplayName(uri)?.substringBeforeLast('.')?.replace('_', ' ')
                    ?.replaceFirstChar { it.uppercaseChar() }
                ?: "Custom Reference Voice"

            val descriptor = buildLocalVoicePackDescriptor(
                displayName = "$sourceLabel (${wavInfo.sampleRateHz / 1000}kHz Mono)",
                referenceSampleRateHz = wavInfo.sampleRateHz
            )
            setActiveVoicePack(descriptor, referenceSampleRateHz = wavInfo.sampleRateHz)
            descriptor
        }
    }

    /**
     * Imports a user-selected local `.onnx` voice model (or companion `.onnx.json` / `tokens.txt` /
     * `tokenizer.model`) into `context.filesDir/voice_pack/` via SAF with atomic `.part` writes.
     */
    suspend fun importOnnxVoiceFile(
        uri: Uri,
        customDisplayName: String? = null
    ): Result<ActiveVoicePackDescriptor> = withContext(Dispatchers.IO) {
        runCatching {
            if (!voicePackDir.exists()) voicePackDir.mkdirs()
            val resolvedName = resolveDisplayName(uri) ?: "custom_voice.onnx"
            val safeFileName = VoiceModelCatalog.sanitizeFileName(resolvedName, "custom_voice.onnx")
            val lower = safeFileName.lowercase(Locale.ROOT)
            require(
                lower.endsWith(".onnx") || lower.endsWith(".json") ||
                    lower.endsWith(".txt") || lower.endsWith(".model") || lower.endsWith(".wav")
            ) {
                "Unsupported voice pack file '$safeFileName'. Pick .wav (16kHz/24kHz mono), .onnx, .onnx.json, tokens.txt, or tokenizer.model."
            }

            if (lower.endsWith(".wav")) {
                return@runCatching importReferenceVoiceWav(uri, customDisplayName).getOrThrow()
            }

            val partFile = File(voicePackDir, "$safeFileName.part")
            val finalFile = File(voicePackDir, safeFileName)
            val copiedBytes = copyUriAtomicallyToPart(uri, partFile)
            require(copiedBytes > 0L) { "Selected file '$safeFileName' is empty." }
            check(atomicMoveFile(partFile, finalFile)) {
                "Failed to store $safeFileName inside voice_pack/."
            }

            val label = customDisplayName?.trim().takeIf { !it.isNullOrBlank() }
                ?: safeFileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
                    .replaceFirstChar { it.uppercaseChar() }

            val existingRate = _activeMetadata.value?.referenceSampleRateHz ?: 0
            val descriptor = buildLocalVoicePackDescriptor(
                displayName = "$label (Local ONNX Pack)",
                referenceSampleRateHz = existingRate
            )
            setActiveVoicePack(descriptor, referenceSampleRateHz = existingRate)
            descriptor
        }
    }

    /**
     * Activates a voice pack descriptor (from local `filesDir/voice_pack/` import OR from
     * `OnnxModelDownloader` in `filesDir/voice_models/<id>/`), persists metadata to DataStore
     * and SharedPreferences, and synchronizes `GokuCustomVoiceEngine` + `GokuEarAndVoiceEngine`.
     */
    fun setActiveVoicePack(
        descriptor: ActiveVoicePackDescriptor,
        referenceSampleRateHz: Int = 0
    ) {
        val refWav = descriptor.wavSamplePaths.firstOrNull { File(it).exists() }
            ?: File(voicePackDir, REFERENCE_WAV_FILENAME).takeIf { it.exists() && it.length() > 44L }?.absolutePath
        val resolvedRate = if (referenceSampleRateHz > 0) {
            referenceSampleRateHz
        } else {
            refWav?.let { validateReferenceWavFile(File(it)).getOrNull()?.sampleRateHz } ?: 0
        }
        val metadata = VoicePackMetadata(
            packId = descriptor.modelId,
            displayName = descriptor.displayName,
            category = descriptor.category,
            installDirPath = descriptor.installDirPath,
            referenceWavPath = refWav,
            referenceSampleRateHz = resolvedRate,
            onnxModelPath = descriptor.onnxFilePaths.firstOrNull(),
            configOrTokensPath = descriptor.tokenizerOrConfigPaths.firstOrNull()
        )

        _activeVoicePack.value = descriptor
        _activeMetadata.value = metadata

        persistMetadataFallbackPrefs(descriptor, metadata)
        scope.launch { persistMetadataDataStore(descriptor, metadata) }

        runCatching {
            GokuCustomVoiceEngine.getInstance(appContext).onVoicePackUpdated(descriptor, metadata)
            GokuEarAndVoiceEngine.getInstance(appContext).setActiveVoicePack(descriptor)
        }
    }

    /**
     * Clears the active voice pack selection (reverting seamlessly to configured Hinglish TTS).
     */
    fun clearActiveVoicePack() {
        _activeVoicePack.value = null
        _activeMetadata.value = null
        runCatching { fallbackPrefs.edit().clear().apply() }
        scope.launch {
            runCatching { dataStore.edit { it.clear() } }
        }
        runCatching {
            GokuCustomVoiceEngine.getInstance(appContext).onVoicePackUpdated(null, null)
            GokuEarAndVoiceEngine.getInstance(appContext).setActiveVoicePack(null)
        }
    }

    /**
     * Deletes all locally imported files inside `context.filesDir/voice_pack/` and clears active state if local.
     */
    fun deleteLocalVoicePackFiles(): Boolean {
        val ok = runCatching {
            if (voicePackDir.exists()) {
                voicePackDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            true
        }.getOrDefault(false)
        if (_activeVoicePack.value?.modelId == LOCAL_VOICE_PACK_ID) {
            clearActiveVoicePack()
        }
        return ok
    }

    fun hasActiveVoicePack(): Boolean = _activeVoicePack.value != null && _activeMetadata.value?.hasPlayableAssets() == true

    fun getReferenceVoiceWavFile(): File? {
        val metaPath = _activeMetadata.value?.referenceWavPath
        if (!metaPath.isNullOrBlank()) {
            val f = File(metaPath)
            if (f.exists() && f.isFile && f.length() > 44L) return f
        }
        val localRef = File(voicePackDir, REFERENCE_WAV_FILENAME)
        return localRef.takeIf { it.exists() && it.isFile && it.length() > 44L }
    }

    private fun buildLocalVoicePackDescriptor(
        displayName: String,
        referenceSampleRateHz: Int
    ): ActiveVoicePackDescriptor {
        val files = voicePackDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()
        val onnxPaths = files.filter { it.name.endsWith(".onnx", ignoreCase = true) }.map { it.absolutePath }
        val configPaths = files.filter {
            it.name.endsWith(".json", ignoreCase = true) ||
                it.name.endsWith(".txt", ignoreCase = true) ||
                it.name.endsWith(".model", ignoreCase = true)
        }.map { it.absolutePath }
        val wavPaths = files.filter { it.name.endsWith(".wav", ignoreCase = true) }.map { it.absolutePath }
        val category = if (onnxPaths.isNotEmpty()) VoiceBundleCategory.TTS_VOICE_PACK else VoiceBundleCategory.CUSTOM_VOICE_SAMPLE
        return ActiveVoicePackDescriptor(
            modelId = LOCAL_VOICE_PACK_ID,
            displayName = displayName,
            category = category,
            installDirPath = voicePackDir.absolutePath,
            onnxFilePaths = onnxPaths,
            tokenizerOrConfigPaths = configPaths,
            wavSamplePaths = wavPaths
        )
    }

    private fun copyUriAtomicallyToPart(uri: Uri, partFile: File): Long {
        var totalBytes = 0L
        val inputStream = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open selected audio/model file.")
        inputStream.use { input ->
            FileOutputStream(partFile, false).use { output ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    output.write(buf, 0, read)
                    totalBytes += read
                }
                output.flush()
            }
        }
        return totalBytes
    }

    private fun atomicMoveFile(partFile: File, finalFile: File): Boolean {
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
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun persistMetadataFallbackPrefs(
        descriptor: ActiveVoicePackDescriptor,
        metadata: VoicePackMetadata
    ) {
        runCatching {
            fallbackPrefs.edit()
                .putString("pack_id", descriptor.modelId)
                .putString("display_name", descriptor.displayName)
                .putString("category", descriptor.category.name)
                .putString("install_dir", descriptor.installDirPath)
                .putString("ref_wav_path", metadata.referenceWavPath.orEmpty())
                .putInt("ref_sample_rate", metadata.referenceSampleRateHz)
                .putString("onnx_paths_json", serializeList(descriptor.onnxFilePaths))
                .putString("config_paths_json", serializeList(descriptor.tokenizerOrConfigPaths))
                .putString("wav_paths_json", serializeList(descriptor.wavSamplePaths))
                .putLong("updated_at", metadata.updatedAtMs)
                .apply()
        }
    }

    private suspend fun persistMetadataDataStore(
        descriptor: ActiveVoicePackDescriptor,
        metadata: VoicePackMetadata
    ) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[Keys.PACK_ID] = descriptor.modelId
                prefs[Keys.DISPLAY_NAME] = descriptor.displayName
                prefs[Keys.CATEGORY] = descriptor.category.name
                prefs[Keys.INSTALL_DIR] = descriptor.installDirPath
                prefs[Keys.REF_WAV_PATH] = metadata.referenceWavPath.orEmpty()
                prefs[Keys.REF_SAMPLE_RATE] = metadata.referenceSampleRateHz
                prefs[Keys.ONNX_PATHS_JSON] = serializeList(descriptor.onnxFilePaths)
                prefs[Keys.CONFIG_PATHS_JSON] = serializeList(descriptor.tokenizerOrConfigPaths)
                prefs[Keys.WAV_PATHS_JSON] = serializeList(descriptor.wavSamplePaths)
                prefs[Keys.UPDATED_AT] = metadata.updatedAtMs
            }
        }.onFailure { err ->
            Log.w(TAG, "DataStore write warning for voice pack metadata", err)
        }
    }

    private fun restoreFromFallbackPrefsSync() {
        runCatching {
            val packId = fallbackPrefs.getString("pack_id", null).orEmpty()
            if (packId.isBlank()) return
            val descriptor = buildDescriptorFromStoredFields(
                packId = packId,
                displayName = fallbackPrefs.getString("display_name", "Custom Voice Pack").orEmpty(),
                categoryRaw = fallbackPrefs.getString("category", VoiceBundleCategory.TTS_VOICE_PACK.name).orEmpty(),
                installDir = fallbackPrefs.getString("install_dir", voicePackDir.absolutePath).orEmpty(),
                onnxJson = fallbackPrefs.getString("onnx_paths_json", "[]").orEmpty(),
                configJson = fallbackPrefs.getString("config_paths_json", "[]").orEmpty(),
                wavJson = fallbackPrefs.getString("wav_paths_json", "[]").orEmpty()
            ) ?: return
            val refPath = fallbackPrefs.getString("ref_wav_path", null)?.takeIf { it.isNotBlank() && File(it).exists() }
            val refRate = fallbackPrefs.getInt("ref_sample_rate", 0)
            val meta = VoicePackMetadata(
                packId = descriptor.modelId,
                displayName = descriptor.displayName,
                category = descriptor.category,
                installDirPath = descriptor.installDirPath,
                referenceWavPath = refPath,
                referenceSampleRateHz = refRate,
                onnxModelPath = descriptor.onnxFilePaths.firstOrNull(),
                configOrTokensPath = descriptor.tokenizerOrConfigPaths.firstOrNull(),
                updatedAtMs = fallbackPrefs.getLong("updated_at", System.currentTimeMillis())
            )
            if (meta.hasPlayableAssets()) {
                _activeVoicePack.value = descriptor
                _activeMetadata.value = meta
            }
        }
    }

    private suspend fun restoreFromDataStore() {
        runCatching {
            val prefs = dataStore.data
                .catch { emit(emptyPreferences()) }
                .first()
            val packId = prefs[Keys.PACK_ID].orEmpty()
            if (packId.isBlank()) return
            val descriptor = buildDescriptorFromStoredFields(
                packId = packId,
                displayName = prefs[Keys.DISPLAY_NAME].orEmpty(),
                categoryRaw = prefs[Keys.CATEGORY].orEmpty(),
                installDir = prefs[Keys.INSTALL_DIR].orEmpty(),
                onnxJson = prefs[Keys.ONNX_PATHS_JSON].orEmpty(),
                configJson = prefs[Keys.CONFIG_PATHS_JSON].orEmpty(),
                wavJson = prefs[Keys.WAV_PATHS_JSON].orEmpty()
            ) ?: return
            val refPath = prefs[Keys.REF_WAV_PATH]?.takeIf { it.isNotBlank() && File(it).exists() }
            val refRate = prefs[Keys.REF_SAMPLE_RATE] ?: 0
            val meta = VoicePackMetadata(
                packId = descriptor.modelId,
                displayName = descriptor.displayName,
                category = descriptor.category,
                installDirPath = descriptor.installDirPath,
                referenceWavPath = refPath,
                referenceSampleRateHz = refRate,
                onnxModelPath = descriptor.onnxFilePaths.firstOrNull(),
                configOrTokensPath = descriptor.tokenizerOrConfigPaths.firstOrNull(),
                updatedAtMs = prefs[Keys.UPDATED_AT] ?: System.currentTimeMillis()
            )
            if (meta.hasPlayableAssets()) {
                _activeVoicePack.value = descriptor
                _activeMetadata.value = meta
                runCatching {
                    GokuCustomVoiceEngine.getInstance(appContext).onVoicePackUpdated(descriptor, meta)
                    GokuEarAndVoiceEngine.getInstance(appContext).setActiveVoicePack(descriptor)
                }
            }
        }.onFailure { err ->
            Log.w(TAG, "Failed restoring voice pack from DataStore", err)
        }
    }

    private fun buildDescriptorFromStoredFields(
        packId: String,
        displayName: String,
        categoryRaw: String,
        installDir: String,
        onnxJson: String,
        configJson: String,
        wavJson: String
    ): ActiveVoicePackDescriptor? {
        val onnxPaths = parseList(onnxJson).filter { File(it).exists() }
        val configPaths = parseList(configJson).filter { File(it).exists() }
        val wavPaths = parseList(wavJson).filter { File(it).exists() }
        if (onnxPaths.isEmpty() && wavPaths.isEmpty()) return null
        val category = VoiceBundleCategory.entries.firstOrNull { it.name == categoryRaw }
            ?: VoiceBundleCategory.TTS_VOICE_PACK
        return ActiveVoicePackDescriptor(
            modelId = packId,
            displayName = displayName.ifBlank { "Custom Voice Pack" },
            category = category,
            installDirPath = installDir.ifBlank { voicePackDir.absolutePath },
            onnxFilePaths = onnxPaths,
            tokenizerOrConfigPaths = configPaths,
            wavSamplePaths = wavPaths
        )
    }

    companion object {
        private const val TAG = "VoicePackManager"
        const val VOICE_PACK_DIR_NAME = "voice_pack"
        const val REFERENCE_WAV_FILENAME = "reference_voice.wav"
        const val LOCAL_VOICE_PACK_ID = "local_custom_voice_pack"
        private const val FALLBACK_PREFS_NAME = "goku_voice_pack_fallback_prefs"

        @Volatile
        private var INSTANCE: VoicePackManager? = null

        fun getInstance(context: Context): VoicePackManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VoicePackManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Validates that a `.wav` file is a real RIFF/WAVE uncompressed PCM file in 16kHz or 24kHz mono (16-bit).
         * Walks arbitrary sub-chunks safely (handles JUNK/LIST/INFO metadata chunks before `fmt ` and `data`).
         */
        fun validateReferenceWavFile(file: File): Result<WavHeaderInfo> {
            return runCatching {
                require(file.exists() && file.isFile && file.length() >= 44L) {
                    "Invalid WAV file: file is too small (<44 bytes)."
                }
                val headerBytes = ByteArray(minOf(file.length(), 4096L).toInt())
                FileInputStream(file).use { fis ->
                    var offset = 0
                    while (offset < headerBytes.size) {
                        val read = fis.read(headerBytes, offset, headerBytes.size - offset)
                        if (read <= 0) break
                        offset += read
                    }
                }
                require(headerBytes.size >= 44) { "Incomplete WAV header." }

                val riff = String(headerBytes, 0, 4, Charsets.US_ASCII)
                val wave = String(headerBytes, 8, 4, Charsets.US_ASCII)
                require(riff == "RIFF" && wave == "WAVE") {
                    "Unsupported audio format. Please select an uncompressed .wav file (RIFF/WAVE)."
                }

                val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                var pos = 12
                var audioFormat = -1
                var channels = -1
                var sampleRate = -1
                var bitsPerSample = -1
                var dataChunkBytes = 0

                while (pos + 8 <= headerBytes.size) {
                    val chunkId = String(headerBytes, pos, 4, Charsets.US_ASCII)
                    val chunkSize = bb.getInt(pos + 4)
                    val chunkDataStart = pos + 8
                    if (chunkId == "fmt " && chunkDataStart + 16 <= headerBytes.size) {
                        audioFormat = bb.getShort(chunkDataStart).toInt() and 0xFFFF
                        channels = bb.getShort(chunkDataStart + 2).toInt() and 0xFFFF
                        sampleRate = bb.getInt(chunkDataStart + 4)
                        bitsPerSample = bb.getShort(chunkDataStart + 14).toInt() and 0xFFFF
                    } else if (chunkId == "data") {
                        dataChunkBytes = if (chunkSize > 0) chunkSize else (file.length() - chunkDataStart).toInt().coerceAtLeast(0)
                        break
                    }
                    if (chunkSize < 0) break
                    val paddedSize = chunkSize + (chunkSize and 1)
                    pos = chunkDataStart + paddedSize
                }

                require(audioFormat == 1) {
                    "WAV must be uncompressed PCM (audioFormat=1), found format=$audioFormat."
                }
                require(channels == 1) {
                    "Reference voice WAV must be MONO (1 channel), found $channels channels."
                }
                require(sampleRate == 16000 || sampleRate == 24000) {
                    "Reference voice WAV must be 16kHz or 24kHz mono, found ${sampleRate}Hz."
                }
                require(bitsPerSample == 16) {
                    "Reference voice WAV must be 16-bit PCM, found ${bitsPerSample}-bit."
                }
                require(dataChunkBytes >= 1600) {
                    "Reference voice WAV audio clip is too short. Provide at least 0.1s of clear speech."
                }

                WavHeaderInfo(
                    sampleRateHz = sampleRate,
                    numChannels = channels,
                    bitsPerSample = bitsPerSample,
                    dataChunkBytes = dataChunkBytes
                )
            }
        }

        private fun serializeList(items: List<String>): String {
            val arr = JSONArray()
            items.forEach { if (it.isNotBlank()) arr.put(it) }
            return arr.toString()
        }

        private fun parseList(rawJson: String): List<String> {
            if (rawJson.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(rawJson)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "").trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
