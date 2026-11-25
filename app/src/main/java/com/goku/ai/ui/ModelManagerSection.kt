package com.goku.ai.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goku.ai.voice.GokuCustomVoiceEngine
import com.goku.ai.voice.ModelDownloadProgress
import com.goku.ai.voice.ModelDownloadStatus
import com.goku.ai.voice.VoiceBundleCategory
import com.goku.ai.voice.VoiceModelBundle
import com.goku.ai.voice.VoiceModelCatalog

private val ModelSurfaceDark = Color(0xFF0D1424)
private val ModelSurfaceElevated = Color(0xFF141E33)
private val ModelOutline = Color(0xFF223354)
private val ModelCyan = Color(0xFF00F0FF)
private val ModelViolet = Color(0xFF8B5CF6)
private val ModelAmber = Color(0xFFFFB800)
private val ModelCrimson = Color(0xFFFF2E54)
private val ModelEmerald = Color(0xFF10B981)
private val ModelTextPrimary = Color(0xFFF1F5F9)
private val ModelTextMuted = Color(0xFF94A3B8)
private val ModelDarkInk = Color(0xFF060A13)

/**
 * Jarvis-style dark Material 3 ONNX Model & Voice Pack Manager Card.
 * Supports 1-Tap Download, Pause/Resume (HTTP Range), Cancel, Activate, 1-Tap Delete,
 * Custom Hugging Face `resolve/main/` URLs, and Zero-Permission Local `.onnx` / `.wav` Import.
 */
@Composable
fun ModelManagerSection(
    bundles: List<VoiceModelBundle>,
    progressMap: Map<String, ModelDownloadProgress>,
    activeModelId: String?,
    activeVoicePackLabel: String = "",
    customVoiceState: GokuCustomVoiceEngine.VoiceEngineState = GokuCustomVoiceEngine.VoiceEngineState.IDLE,
    onDownloadOrResume: (String) -> Unit,
    onPause: (String) -> Unit,
    onCancel: (String) -> Unit,
    onActivate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAddCustomUrl: (primaryUrl: String, displayName: String, companionUrl: String) -> Unit,
    onImportLocalUri: (Uri) -> Unit,
    onImportReferenceWav: (Uri) -> Unit = {}
) {
    val ctx = LocalContext.current
    var showCustomDrawer by rememberSaveable { mutableStateOf(false) }
    var customPrimaryUrl by rememberSaveable { mutableStateOf("") }
    var customCompanionUrl by rememberSaveable { mutableStateOf("") }
    var customDisplayName by rememberSaveable { mutableStateOf("") }

    val localVoiceFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportLocalUri(uri)
        }
    }

    val referenceWavPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportReferenceWav(uri)
        }
    }

    val activeBundle = bundles.firstOrNull { it.id == activeModelId }
    val installedCount = progressMap.values.count { it.status == ModelDownloadStatus.READY }
    val totalInstalledBytes = progressMap.values
        .filter { it.status == ModelDownloadStatus.READY }
        .sumOf { it.downloadedBytes }

    Card(
        colors = CardDefaults.cardColors(containerColor = ModelSurfaceDark),
        border = BorderStroke(1.2.dp, ModelCyan.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onnx_voice_model_manager_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            ModelCyan.copy(alpha = 0.08f),
                            ModelSurfaceDark,
                            ModelSurfaceDark
                        )
                    )
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(ModelCyan.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = "ONNX Voice Packs",
                            tint = ModelCyan
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "ONNX Voice & KWS Pack Manager",
                            style = MaterialTheme.typography.titleMedium,
                            color = ModelCyan
                        )
                        Text(
                            text = "HTTP Range Resume • Atomic .part • Permanent Storage",
                            style = MaterialTheme.typography.labelSmall,
                            color = ModelTextMuted
                        )
                    }
                }
            }

            // Active Voice Pack Status Banner
            Surface(
                color = ModelSurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (activeBundle != null) ModelEmerald.copy(alpha = 0.5f) else ModelOutline
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ACTIVE VOICE PACK • ${customVoiceState.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ModelTextMuted
                        )
                        val resolvedActiveName = activeVoicePackLabel.ifBlank {
                            activeBundle?.displayName ?: "System Hinglish Neural TTS (Default)"
                        }
                        Text(
                            text = resolvedActiveName,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (activeVoicePackLabel.isNotBlank() || activeBundle != null) ModelEmerald else ModelTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$installedCount ready • ${VoiceModelCatalog.formatBytesHuman(totalInstalledBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ModelCyan
                    )
                }
            }

            // Bundle Items
            bundles.forEach { bundle ->
                val progress = progressMap[bundle.id] ?: ModelDownloadProgress(
                    modelId = bundle.id,
                    totalBytes = bundle.totalSizeBytes,
                    totalFiles = bundle.files.size
                )
                VoiceBundleRowCard(
                    bundle = bundle,
                    progress = progress,
                    isActive = activeModelId == bundle.id,
                    onDownloadOrResume = { onDownloadOrResume(bundle.id) },
                    onPause = { onPause(bundle.id) },
                    onCancel = { onCancel(bundle.id) },
                    onActivate = { onActivate(bundle.id) },
                    onDelete = { onDelete(bundle.id) }
                )
            }

            HorizontalDivider(color = ModelOutline)

            // Custom Hugging Face URL & Local .onnx / .wav Import Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showCustomDrawer = !showCustomDrawer },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("voice_custom_url_toggle_btn")
                ) {
                    Icon(
                        imageVector = if (showCustomDrawer) Icons.Filled.ExpandLess else Icons.Filled.AddLink,
                        contentDescription = "Custom Hugging Face URL",
                        tint = ModelViolet
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Custom HF URL",
                        color = ModelViolet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = { localVoiceFilePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("voice_import_local_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = "Import local ONNX or WAV",
                        tint = ModelCyan
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Import .onnx/.wav",
                        color = ModelCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            OutlinedButton(
                onClick = { referenceWavPicker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*", "*/*")) },
                border = BorderStroke(1.dp, ModelEmerald.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("voice_import_reference_wav_btn")
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = "Import Reference Voice WAV",
                    tint = ModelEmerald
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Import reference_voice.wav (16kHz / 24kHz Mono)",
                    color = ModelEmerald,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AnimatedVisibility(visible = showCustomDrawer) {
                Surface(
                    color = ModelSurfaceElevated,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ModelViolet.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Paste Open-Access Hugging Face resolve/main/ URL",
                            style = MaterialTheme.typography.titleSmall,
                            color = ModelViolet
                        )
                        OutlinedTextField(
                            value = customDisplayName,
                            onValueChange = { customDisplayName = it },
                            label = { Text("Pack Name (optional)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("voice_custom_name_input")
                        )
                        OutlinedTextField(
                            value = customPrimaryUrl,
                            onValueChange = { customPrimaryUrl = it },
                            label = { Text("Primary .onnx / .wav URL (https://huggingface.co/.../resolve/main/...)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("voice_custom_primary_url_input")
                        )
                        OutlinedTextField(
                            value = customCompanionUrl,
                            onValueChange = { customCompanionUrl = it },
                            label = { Text("Companion tokens.txt / .onnx.json / tokenizer.model URL (optional)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("voice_custom_companion_url_input")
                        )
                        Button(
                            onClick = {
                                val url = customPrimaryUrl.trim()
                                if (url.isBlank()) {
                                    Toast.makeText(ctx, "Pehle valid Hugging Face URL paste karein.", Toast.LENGTH_SHORT).show()
                                } else {
                                    onAddCustomUrl(url, customDisplayName.trim(), customCompanionUrl.trim())
                                    customPrimaryUrl = ""
                                    customCompanionUrl = ""
                                    customDisplayName = ""
                                    showCustomDrawer = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ModelViolet,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("voice_custom_add_download_btn")
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add Bundle & Start Download", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceBundleRowCard(
    bundle: VoiceModelBundle,
    progress: ModelDownloadProgress,
    isActive: Boolean,
    onDownloadOrResume: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = when {
        isActive -> ModelEmerald
        progress.status == ModelDownloadStatus.READY -> ModelCyan
        progress.status == ModelDownloadStatus.DOWNLOADING -> ModelCyan
        progress.status == ModelDownloadStatus.PAUSED -> ModelAmber
        progress.status == ModelDownloadStatus.VERIFYING -> ModelViolet
        progress.status == ModelDownloadStatus.ERROR -> ModelCrimson
        else -> ModelOutline
    }

    val categoryColor = when (bundle.category) {
        VoiceBundleCategory.TTS_VOICE_PACK -> ModelCyan
        VoiceBundleCategory.WAKE_WORD_KWS -> ModelViolet
        VoiceBundleCategory.CUSTOM_VOICE_SAMPLE -> ModelAmber
    }

    Surface(
        color = ModelSurfaceElevated,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = if (accent == ModelOutline) 0.8f else 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_bundle_card_${bundle.id}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Title + Category Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = bundle.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = ModelTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Surface(
                    color = categoryColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = bundle.category.badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = categoryColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = bundle.description,
                style = MaterialTheme.typography.bodySmall,
                color = ModelTextMuted
            )

            // Exact Size Before Starting & File Manifest Count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (progress.status == ModelDownloadStatus.READY && progress.downloadedBytes > 0L) {
                        "${VoiceModelCatalog.formatBytesHuman(progress.downloadedBytes)} installed • ${bundle.files.size} file(s)"
                    } else {
                        "${bundle.sizeSummaryLabel} • ${bundle.files.size} file(s)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (progress.status == ModelDownloadStatus.READY) ModelEmerald else ModelAmber
                )
                if (isActive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = "Active Voice Pack",
                            tint = ModelEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "ACTIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ModelEmerald,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Live Progress Bar + MB Counter + Speed during DOWNLOADING / PAUSED / VERIFYING
            if (progress.status == ModelDownloadStatus.DOWNLOADING ||
                progress.status == ModelDownloadStatus.PAUSED ||
                progress.status == ModelDownloadStatus.VERIFYING
            ) {
                val fraction = (progress.percentage / 100f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    color = if (progress.status == ModelDownloadStatus.PAUSED) ModelAmber else ModelCyan,
                    trackColor = ModelOutline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${VoiceModelCatalog.formatBytesHuman(progress.downloadedBytes)} / ${VoiceModelCatalog.formatBytesHuman(progress.totalBytes)} (${progress.percentage}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = ModelTextPrimary
                    )
                    Text(
                        text = when (progress.status) {
                            ModelDownloadStatus.DOWNLOADING -> VoiceModelCatalog.formatSpeedHuman(progress.speedKBps)
                            ModelDownloadStatus.PAUSED -> "PAUSED (.part saved)"
                            ModelDownloadStatus.VERIFYING -> "VERIFYING..."
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (progress.status == ModelDownloadStatus.PAUSED) ModelAmber else ModelCyan
                    )
                }
                if (progress.currentFileName.isNotBlank()) {
                    Text(
                        text = "File ${progress.completedFiles + 1}/${progress.totalFiles.coerceAtLeast(1)}: ${progress.currentFileName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ModelTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Error Message Banner
            if (progress.status == ModelDownloadStatus.ERROR && !progress.errorMessage.isNullOrBlank()) {
                Text(
                    text = progress.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = ModelCrimson
                )
            }

            // 1-Tap Action Buttons (min 48.dp touch target)
            when (progress.status) {
                ModelDownloadStatus.NOT_DOWNLOADED -> {
                    Button(
                        onClick = onDownloadOrResume,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ModelCyan,
                            contentColor = ModelDarkInk
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("voice_download_btn_${bundle.id}")
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = "Download ${bundle.displayName}")
                        Spacer(Modifier.width(6.dp))
                        Text("Download (${VoiceModelCatalog.formatBytesHuman(bundle.totalSizeBytes)})", fontWeight = FontWeight.Bold)
                    }
                }

                ModelDownloadStatus.DOWNLOADING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPause,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("voice_pause_btn_${bundle.id}")
                        ) {
                            Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = ModelAmber)
                            Spacer(Modifier.width(4.dp))
                            Text("Pause", color = ModelAmber)
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("voice_cancel_btn_${bundle.id}")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = ModelCrimson)
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel", color = ModelCrimson)
                        }
                    }
                }

                ModelDownloadStatus.PAUSED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onDownloadOrResume,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ModelAmber,
                                contentColor = ModelDarkInk
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("voice_resume_btn_${bundle.id}")
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Resume")
                            Spacer(Modifier.width(4.dp))
                            Text("Resume (${progress.percentage}%)", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("voice_cancel_paused_btn_${bundle.id}")
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Discard partial", tint = ModelCrimson)
                        }
                    }
                }

                ModelDownloadStatus.VERIFYING -> {
                    Text(
                        text = "Verifying checksum & finalizing atomic .part rename...",
                        style = MaterialTheme.typography.labelMedium,
                        color = ModelViolet
                    )
                }

                ModelDownloadStatus.READY -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onActivate,
                            enabled = !isActive,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isActive) ModelEmerald.copy(alpha = 0.25f) else ModelEmerald,
                                contentColor = if (isActive) ModelEmerald else ModelDarkInk,
                                disabledContainerColor = ModelEmerald.copy(alpha = 0.22f),
                                disabledContentColor = ModelEmerald
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("voice_activate_btn_${bundle.id}")
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Activate")
                            Spacer(Modifier.width(6.dp))
                            Text(if (isActive) "Active Voice Pack" else "Activate", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            border = BorderStroke(1.dp, ModelCrimson.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("voice_delete_btn_${bundle.id}")
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete model", tint = ModelCrimson)
                            Spacer(Modifier.width(4.dp))
                            Text("Delete", color = ModelCrimson)
                        }
                    }
                }

                ModelDownloadStatus.ERROR -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onDownloadOrResume,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ModelCyan,
                                contentColor = ModelDarkInk
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("voice_retry_btn_${bundle.id}")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Retry / Resume")
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (progress.downloadedBytes > 0L) "Resume" else "Retry Download",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("voice_clear_err_btn_${bundle.id}")
                        ) {
                            Text("Clear", color = ModelCrimson)
                        }
                    }
                }
            }
        }
    }
}
