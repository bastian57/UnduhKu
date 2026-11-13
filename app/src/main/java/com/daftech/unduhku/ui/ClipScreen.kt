package com.daftech.unduhku.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.daftech.unduhku.clip.ClipPlanner
import com.daftech.unduhku.download.LinkUtils

/**
 * Tab 2 — Clip Virtual.
 * Pilih video hasil download -> baca durasi -> daftar clip VIRTUAL
 * tiap N detik (thumbnail + checkbox + preview ExoPlayer) -> Export
 * HANYA yang dicentang (Media3 Transformer, edit-list trim).
 * TIDAK ADA file clip fisik sebelum Export ditekan.
 */
@Composable
fun ClipScreen(viewModel: UnduhkuViewModel) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Clip Virtual", fontSize = 20.sp, color = Color.White)
            Text(
                "Video dipotong VIRTUAL tiap 10/15/30/60 detik. " +
                    "Tidak ada file yang dibuat di awal — centang clip yang " +
                    "bagus, tekan Export, baru file itu yang dibuat.",
                fontSize = 13.sp, color = Color(0xFF9CA3AF)
            )
        }
        if (viewModel.clipSource == null) {
            item {
                Text(
                    "Pilih video hasil download:",
                    fontSize = 14.sp, color = Color.White
                )
            }
            val videos = viewModel.historyDownloads.filter {
                it.status == "COMPLETED" && it.fileName.lowercase().let { name ->
                    name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm")
                }
            }
            if (videos.isEmpty()) {
                item {
                    Text(
                        "Belum ada video yang terdownload — unduh dulu di tab Unduh.",
                        fontSize = 13.sp, color = Color(0xFF9CA3AF)
                    )
                }
            } else {
                items(videos) { download ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                        onClick = { viewModel.requestLoadClipSource(download) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(download.fileName, fontSize = 14.sp, color = Color.White)
                            Text(
                                LinkUtils.formatBytes(download.totalBytes),
                                fontSize = 12.sp, color = Color(0xFF9CA3AF)
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            viewModel.clipSource?.fileName ?: "",
                            fontSize = 15.sp, color = Color.White
                        )
                        Text(
                            "Durasi: ${LinkUtils.formatDurationMs(viewModel.clipDurationMs)} • " +
                                "${viewModel.virtualClips.size} clip virtual",
                            fontSize = 13.sp, color = Color(0xFF9CA3AF)
                        )
                        Text("Durasi tiap clip:", fontSize = 13.sp, color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (optionMs in ClipPlanner.SEGMENT_OPTIONS_MS) {
                                val label = "${optionMs / 1000} dtk"
                                if (viewModel.clipSegmentMs == optionMs) {
                                    Button(onClick = {
                                        viewModel.updateSegmentDuration(optionMs)
                                    }) { Text(label) }
                                } else {
                                    OutlinedButton(onClick = {
                                        viewModel.updateSegmentDuration(optionMs)
                                    }) { Text(label) }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.requestBuildVirtualClips() },
                                enabled = viewModel.clipDurationMs > 0L
                            ) { Text("Buat Daftar Clip") }
                            OutlinedButton(onClick = {
                                viewModel.requestClearClipSource()
                            }) { Text("Ganti Video") }
                        }
                        if (viewModel.isBuildingClips) CircularProgressIndicator()
                    }
                }
            }
            val preview = viewModel.previewClip
            if (preview != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = viewModel.peekPlayer()
                                        useController = true
                                    }
                                },
                                update = { view -> view.player = viewModel.peekPlayer() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                            Text(
                                "Preview clip ${preview.index + 1}: " +
                                    "${LinkUtils.formatDurationMs(preview.startMs)}–" +
                                    LinkUtils.formatDurationMs(preview.endMs)",
                                fontSize = 13.sp, color = Color(0xFF9CA3AF)
                            )
                            if (viewModel.previewError != null) {
                                Text(
                                    viewModel.previewError ?: "",
                                    fontSize = 13.sp, color = Color(0xFFF87171)
                                )
                            }
                            TextButton(onClick = { viewModel.requestStopPreview() }) {
                                Text("Tutup Preview")
                            }
                        }
                    }
                }
            }
            if (viewModel.virtualClips.isNotEmpty()) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { viewModel.requestToggleSelectAll() }) {
                            Text("Pilih Semua")
                        }
                        val selectedCount = viewModel.virtualClips.count { it.isSelected }
                        Button(
                            onClick = { viewModel.requestExportSelected() },
                            enabled = selectedCount > 0 && !viewModel.isExporting
                        ) { Text("Export yang Dipilih ($selectedCount)") }
                    }
                }
                item {
                    if (viewModel.isExporting) CircularProgressIndicator()
                    if (viewModel.exportProgress.isNotBlank()) {
                        Text(
                            viewModel.exportProgress,
                            fontSize = 13.sp, color = Color(0xFF9CA3AF)
                        )
                    }
                }
            }
            items(viewModel.virtualClips, key = { it.index }) { clip ->
                LaunchedEffect(clip.index) { viewModel.requestThumbnail(clip.index) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Checkbox(
                            checked = clip.isSelected,
                            onCheckedChange = {
                                viewModel.updateClipSelection(clip.index, it)
                            }
                        )
                        val bitmap = viewModel.clipThumbnails[clip.index]
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Thumbnail clip ${clip.index + 1}",
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(45.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(45.dp)
                                    .background(Color(0xFF1F2937))
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Clip ${clip.index + 1} • " +
                                    "${LinkUtils.formatDurationMs(clip.startMs)}–" +
                                    LinkUtils.formatDurationMs(clip.endMs)",
                                fontSize = 14.sp, color = Color.White
                            )
                            Text(
                                when {
                                    clip.exportedPath != null ->
                                        "Terekspor: ${clip.exportedPath}"
                                    clip.isExporting -> "Mengekspor…"
                                    else -> "Virtual — belum jadi file"
                                },
                                fontSize = 12.sp, color = Color(0xFF9CA3AF)
                            )
                        }
                        TextButton(onClick = { viewModel.requestPreviewClip(clip) }) {
                            Text("Preview")
                        }
                    }
                }
            }
        }
    }
}
