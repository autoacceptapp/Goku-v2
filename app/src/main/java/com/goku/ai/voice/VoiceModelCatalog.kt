package com.goku.ai.voice

import java.net.URI
import java.util.Locale

enum class VoiceBundleCategory(val badgeLabel: String) {
    TTS_VOICE_PACK("ONNX VOICE"),
    WAKE_WORD_KWS("24x7 KWS"),
    CUSTOM_VOICE_SAMPLE("CUSTOM PACK")
}

/**
 * Specification for a single downloadable asset inside an ONNX voice or KWS bundle
 * (e.g., `.onnx`, `.onnx.json`, `tokens.txt`, `tokenizer.model`, or `.wav`).
 */
data class ModelFileSpec(
    val fileName: String,
    val downloadUrl: String,
    val expectedMinBytes: Long
)

/**
 * Multi-file downloadable ONNX Voice Pack / KWS bundle definition.
 * Zero copyrighted audio or model weights are bundled in the APK.
 */
data class VoiceModelBundle(
    val id: String,
    val displayName: String,
    val description: String,
    val category: VoiceBundleCategory = VoiceBundleCategory.TTS_VOICE_PACK,
    val totalSizeBytes: Long,
    val files: List<ModelFileSpec>,
    val isCustom: Boolean = false
) {
    val sizeSummaryLabel: String
        get() = "${VoiceModelCatalog.formatBytesHuman(totalSizeBytes)} · One-time download"
}

/**
 * Active voice pack descriptor exposed to GokuEarAndVoiceEngine / GokuCustomVoiceEngine.
 */
data class ActiveVoicePackDescriptor(
    val modelId: String,
    val displayName: String,
    val category: VoiceBundleCategory,
    val installDirPath: String,
    val onnxFilePaths: List<String>,
    val tokenizerOrConfigPaths: List<String>,
    val wavSamplePaths: List<String>
)

/**
 * Open-access Hugging Face `resolve/main/` ONNX voice & KWS catalog plus custom URL / local import helpers.
 */
object VoiceModelCatalog {

    private const val MB = 1024L * 1024L

    val PRECONFIGURED_BUNDLES: List<VoiceModelBundle> = listOf(
        VoiceModelBundle(
            id = "piper_hi_rohan_medium",
            displayName = "Rohan Hindi / Hinglish Voice (Piper ONNX)",
            description = "Open-access natural Indian Hindi/Hinglish VITS ONNX voice pack with phoneme config.",
            category = VoiceBundleCategory.TTS_VOICE_PACK,
            totalSizeBytes = 63L * MB,
            files = listOf(
                ModelFileSpec(
                    fileName = "hi_IN-rohan-medium.onnx",
                    downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/rohan/medium/hi_IN-rohan-medium.onnx?download=true",
                    expectedMinBytes = 40L * MB
                ),
                ModelFileSpec(
                    fileName = "hi_IN-rohan-medium.onnx.json",
                    downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/rohan/medium/hi_IN-rohan-medium.onnx.json?download=true",
                    expectedMinBytes = 512L
                )
            )
        ),
        VoiceModelBundle(
            id = "piper_hi_pratham_medium",
            displayName = "Pratham Crisp Indian Voice (Piper ONNX)",
            description = "Clear, fast-response open-access Indian voice model optimized for low-latency commands.",
            category = VoiceBundleCategory.TTS_VOICE_PACK,
            totalSizeBytes = 63L * MB,
            files = listOf(
                ModelFileSpec(
                    fileName = "hi_IN-pratham-medium.onnx",
                    downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx?download=true",
                    expectedMinBytes = 40L * MB
                ),
                ModelFileSpec(
                    fileName = "hi_IN-pratham-medium.onnx.json",
                    downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx.json?download=true",
                    expectedMinBytes = 512L
                )
            )
        ),
        VoiceModelBundle(
            id = "piper_en_us_ryan_medium",
            displayName = "Ryan Energetic Hero Voice (Piper ONNX)",
            description = "Expressive open-access English neural voice pack tuned for energetic assistant delivery.",
            category = VoiceBundleCategory.TTS_VOICE_PACK,
            totalSizeBytes = 63L * MB,
            files = listOf(
                ModelFileSpec(
                    fileName = "en_US-ryan-medium.onnx",
                    downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/medium/en_US-ryan-medium.onnx?download=true",
                    expectedMinBytes = 40L * MB
                ),
                ModelFileSpec(
                    fileName = "en_US-ryan-medium.onnx.json",
                    downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/medium/en_US-ryan-medium.onnx.json?download=true",
                    expectedMinBytes = 512L
                )
            )
        ),
        VoiceModelBundle(
            id = "sherpa_kws_zipformer_gigaspeech",
            displayName = "Sherpa-ONNX 24x7 Wake-Word Pack",
            description = "Ultra-light 4-file ONNX Transducer KWS bundle (encoder + decoder + joiner + tokens.txt) for offline 'Hey Goku' / 'Goku stop'.",
            category = VoiceBundleCategory.WAKE_WORD_KWS,
            totalSizeBytes = 5L * MB,
            files = listOf(
                ModelFileSpec(
                    fileName = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/resolve/main/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx?download=true",
                    expectedMinBytes = 1024L * 1024L
                ),
                ModelFileSpec(
                    fileName = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/resolve/main/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx?download=true",
                    expectedMinBytes = 64L * 1024L
                ),
                ModelFileSpec(
                    fileName = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/resolve/main/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx?download=true",
                    expectedMinBytes = 32L * 1024L
                ),
                ModelFileSpec(
                    fileName = "tokens.txt",
                    downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/resolve/main/tokens.txt?download=true",
                    expectedMinBytes = 128L
                )
            )
        )
    )

    fun sanitizeId(raw: String): String {
        val cleaned = raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_\\-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '-')
        return cleaned.ifBlank { "custom_voice_${System.currentTimeMillis()}" }.take(64)
    }

    fun sanitizeFileName(raw: String, fallback: String = "voice_model.onnx"): String {
        val base = raw.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val safe = base.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
            .replace(Regex("\\.{2,}"), ".")
            .trim('.')
        return if (safe.isBlank() || safe == "." || safe == "..") fallback else safe.take(120)
    }

    fun isValidDirectDownloadUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return false
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)
            (scheme == "https" || (scheme == "http" && (host == "127.0.0.1" || host == "localhost"))) &&
                !host.isNullOrBlank()
        }.getOrDefault(false)
    }

    /**
     * Converts a Hugging Face `/blob/main/` web URL automatically into a direct `/resolve/main/` download URL.
     */
    fun normalizeHuggingFaceUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return if (trimmed.contains("huggingface.co/", ignoreCase = true) && trimmed.contains("/blob/")) {
            trimmed.replace("/blob/", "/resolve/")
        } else {
            trimmed
        }
    }

    /**
     * Builds a validated custom VoiceModelBundle from a user-pasted Hugging Face `resolve/main/`
     * (or direct HTTPS) URL, with optional companion `tokens.txt` / `.onnx.json` / `tokenizer.model` URL.
     */
    fun createCustomUrlBundle(
        primaryUrl: String,
        displayName: String = "",
        companionUrl: String? = null,
        estimatedSizeBytes: Long = 20L * MB
    ): Result<VoiceModelBundle> {
        val normalizedPrimary = normalizeHuggingFaceUrl(primaryUrl)
        if (!isValidDirectDownloadUrl(normalizedPrimary)) {
            return Result.failure(
                IllegalArgumentException("Valid HTTPS Hugging Face resolve/main/ URL paste karein (e.g. https://huggingface.co/.../resolve/main/model.onnx).")
            )
        }
        val primaryFileName = sanitizeFileName(normalizedPrimary, "custom_voice.onnx")
        val lowerName = primaryFileName.lowercase(Locale.ROOT)
        if (!lowerName.endsWith(".onnx") && !lowerName.endsWith(".wav") && !lowerName.endsWith(".bin") && !lowerName.endsWith(".model")) {
            return Result.failure(
                IllegalArgumentException("Supported file extensions: .onnx, .wav, .model, ya .bin")
            )
        }

        val specs = mutableListOf(
            ModelFileSpec(
                fileName = primaryFileName,
                downloadUrl = normalizedPrimary,
                expectedMinBytes = 1024L
            )
        )

        val cleanCompanion = companionUrl?.trim().orEmpty()
        if (cleanCompanion.isNotEmpty()) {
            val normalizedComp = normalizeHuggingFaceUrl(cleanCompanion)
            if (!isValidDirectDownloadUrl(normalizedComp)) {
                return Result.failure(
                    IllegalArgumentException("Companion tokenizer/config URL valid HTTPS hona chahiye.")
                )
            }
            val compFileName = sanitizeFileName(normalizedComp, "tokens.txt")
            if (specs.none { it.fileName.equals(compFileName, ignoreCase = true) }) {
                specs.add(
                    ModelFileSpec(
                        fileName = compFileName,
                        downloadUrl = normalizedComp,
                        expectedMinBytes = 16L
                    )
                )
            }
        }

        val finalTitle = displayName.trim().ifBlank {
            primaryFileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
                .replaceFirstChar { it.uppercaseChar() }
        }
        val id = sanitizeId("custom_${finalTitle}_${normalizedPrimary.hashCode().toUInt()}")
        val category = if (lowerName.endsWith(".wav")) {
            VoiceBundleCategory.CUSTOM_VOICE_SAMPLE
        } else {
            VoiceBundleCategory.TTS_VOICE_PACK
        }

        return Result.success(
            VoiceModelBundle(
                id = id,
                displayName = finalTitle,
                description = "Custom user bundle (${specs.joinToString(" + ") { it.fileName }})",
                category = category,
                totalSizeBytes = estimatedSizeBytes.coerceAtLeast(1024L),
                files = specs,
                isCustom = true
            )
        )
    }

    fun formatBytesHuman(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> {
                val v = bytes / mb
                if (v >= 10.0) String.format(Locale.US, "%.0f MB", v)
                else String.format(Locale.US, "%.1f MB", v)
            }
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun formatSpeedHuman(speedKBps: Float): String {
        if (speedKBps <= 0.1f) return "0 KB/s"
        return if (speedKBps >= 1024f) {
            String.format(Locale.US, "%.1f MB/s", speedKBps / 1024f)
        } else {
            String.format(Locale.US, "%.0f KB/s", speedKBps)
        }
    }
}
