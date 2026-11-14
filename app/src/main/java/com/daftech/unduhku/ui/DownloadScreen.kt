package com.daftech.unduhku.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftech.unduhku.download.LinkUtils

/**
 * Tab 1 — Unduh.
 * Tempel link -> Periksa Link -> pilih file (direct / archive.org)
 * -> Download (foreground service, 8 chunk, resume).
 *
 * Warna teks TextField di-set EKSPLISIT (pelajaran LoopLive:
 * EditText di atas background gelap WAJIB textColor + textColorHint
 * eksplisit, kalau tidak ketikan tidak terlihat).
 */
@Composable
fun DownloadScreen(
    viewModel: UnduhkuViewModel,
    onNavigateToClip: () -> Unit
) {
    val state = viewModel.downloadState
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        disabledTextColor = Color(0xFF9CA3AF),
        focusedPlaceholderColor = Color(0xFF9CA3AF),
        unfocusedPlaceholderColor = Color(0xFF9CA3AF),
        focusedBorderColor = Color(0xFF22D3EE),
        unfocusedBorderColor = Color(0xFF374151),
        cursorColor = Color(0xFF22D3EE)
    )
    // Pemilih file sistem (v1.2.0) — tanpa izin akses semua file;
    // file yang dipilih langsung disalin ViewModel ke folder import.
    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.requestImportVideo(uri)
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Unduh File", fontSize = 20.sp, color = Color.White)
            Text(
                "Tempel link file langsung (.mp4/.mkv/.zip/.apk/.pdf/dll) " +
                    "atau halaman archive.org/details/… — UnduhKu mengunduh " +
                    "dengan 8 koneksi paralel + resume.",
                fontSize = 13.sp, color = Color(0xFF9CA3AF)
            )
        }
        item {
            OutlinedTextField(
                value = viewModel.link,
                onValueChange = { viewModel.updateLink(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://…/video.mp4  atau  https://archive.org/details/…") },
                singleLine = true,
                colors = fieldColors
            )
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.requestResolveLink() },
                    enabled = !viewModel.isResolving
                ) { Text("Periksa Link") }
                if (viewModel.isResolving) CircularProgressIndicator()
            }
        }
        // Pilih video dari HP (v1.2.0) — pemilih file sistem, lalu
        // disalin ke Download/UnduhKu/import/ dan jadi sumber clip.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { pickVideo.launch(arrayOf("video/*")) },
                    enabled = !viewModel.isImporting
                ) { Text("Pilih Video dari HP") }
                Text(
                    "Pilih video dari galeri / file HP — disalin ke " +
                        "Download/UnduhKu/import/, lalu bisa dibuat clip " +
                        "virtual + export + prompt seperti video lainnya.",
                    fontSize = 13.sp, color = Color(0xFF9CA3AF)
                )
            }
        }
        // Kartu status impor (v1.2.0): progress + batal / sukses / error.
        if (viewModel.isImporting || viewModel.importError != null ||
            viewModel.importSuccess != null
        ) {
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
                            "Impor Video dari HP",
                            fontSize = 15.sp, color = Color.White
                        )
                        if (viewModel.isImporting) {
                            LinearProgressIndicator(
                                progress = { viewModel.importProgressFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                viewModel.importProgressText,
                                fontSize = 13.sp, color = Color(0xFF9CA3AF)
                            )
                            OutlinedButton(onClick = {
                                viewModel.requestCancelImport()
                            }) { Text("Batal") }
                        }
                        if (viewModel.importError != null) {
                            Text(
                                viewModel.importError ?: "",
                                fontSize = 13.sp, color = Color(0xFFF87171)
                            )
                            OutlinedButton(onClick = {
                                viewModel.requestDismissImportMessage()
                            }) { Text("Tutup") }
                        }
                        if (viewModel.importSuccess != null) {
                            Text(
                                viewModel.importSuccess ?: "",
                                fontSize = 13.sp, color = Color(0xFF4ADE80)
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(onClick = { onNavigateToClip() }) {
                                    Text("Buka Tab Clip")
                                }
                                OutlinedButton(onClick = {
                                    viewModel.requestDismissImportMessage()
                                }) { Text("Tutup") }
                            }
                        }
                    }
                }
            }
        }
        if (viewModel.resolveError != null) {
            item {
                Text(
                    viewModel.resolveError ?: "",
                    fontSize = 13.sp, color = Color(0xFFF87171)
                )
            }
        }
        val direct = viewModel.directResult
        if (direct != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(direct.fileName, fontSize = 15.sp, color = Color.White)
                        Text(
                            "Ukuran: " +
                                if (direct.sizeBytes > 0L) LinkUtils.formatBytes(direct.sizeBytes)
                                else "tidak diketahui (single-stream)",
                            fontSize = 13.sp, color = Color(0xFF9CA3AF)
                        )
                        Text(
                            "Dukung resume multi-chunk: " +
                                if (direct.supportsRanges) "Ya" else "Tidak",
                            fontSize = 13.sp, color = Color(0xFF9CA3AF)
                        )
                        Button(onClick = { viewModel.requestStartDownload() }) {
                            Text("Download")
                        }
                    }
                }
            }
        }
        val archive = viewModel.archiveResult
        if (archive != null) {
            item {
                Text(
                    "Item archive.org: ${archive.id} — pilih satu file:",
                    fontSize = 14.sp, color = Color.White
                )
            }
            items(archive.files) { file ->
                val selected = viewModel.selectedArchiveFile == file
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) Color(0xFF164E63)
                        else Color(0xFF111827)
                    ),
                    onClick = { viewModel.updateSelectedArchiveFile(file) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { viewModel.updateSelectedArchiveFile(file) }
                        )
                        Column {
                            Text(file.name, fontSize = 14.sp, color = Color.White)
                            Text(
                                LinkUtils.formatBytes(file.sizeBytes) +
                                    if (file.format.isNotBlank()) " • ${file.format}" else "",
                                fontSize = 12.sp, color = Color(0xFF9CA3AF)
                            )
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { viewModel.requestStartDownload() },
                    enabled = viewModel.selectedArchiveFile != null
                ) { Text("Download File Terpilih") }
            }
        }
        if (state.status != "IDLE") {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(state.fileName, fontSize = 15.sp, color = Color.White)
                        if (state.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { state.progressFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${(state.progressFraction * 100).toInt()}% • " +
                                    "${LinkUtils.formatBytes(state.downloadedBytes)} / " +
                                    LinkUtils.formatBytes(state.totalBytes),
                                fontSize = 13.sp, color = Color(0xFF9CA3AF)
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                LinkUtils.formatBytes(state.downloadedBytes),
                                fontSize = 13.sp, color = Color(0xFF9CA3AF)
                            )
                        }
                        if (state.status == "DOWNLOADING") {
                            val etaMs = if (state.bytesPerSec > 0.0 && state.totalBytes > 0L) {
                                ((state.totalBytes - state.downloadedBytes) * 1000.0 /
                                    state.bytesPerSec).toLong()
                            } else {
                                -1L
                            }
                            Text(
                                "Kecepatan: ${LinkUtils.formatSpeed(state.bytesPerSec)}" +
                                    if (etaMs >= 0L) " • Sisa: ${LinkUtils.formatEtaMs(etaMs)}" else "",
                                fontSize = 13.sp, color = Color(0xFF9CA3AF)
                            )
                        }
                        when (state.status) {
                            "DOWNLOADING" -> Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(onClick = { viewModel.requestPauseDownload() }) {
                                    Text("Jeda")
                                }
                                OutlinedButton(onClick = { viewModel.requestCancelDownload() }) {
                                    Text("Batal")
                                }
                            }
                            "PAUSED" -> Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(onClick = { viewModel.requestResumeDownload() }) {
                                    Text("Lanjut")
                                }
                                OutlinedButton(onClick = { viewModel.requestCancelDownload() }) {
                                    Text("Batal")
                                }
                            }
                            "COMPLETED" -> {
                                Text(
                                    "Selesai: ${state.displayPath}",
                                    fontSize = 13.sp, color = Color(0xFF4ADE80)
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val isVideo = state.fileName.lowercase().let {
                                        it.endsWith(".mp4") || it.endsWith(".mkv") ||
                                            it.endsWith(".webm")
                                    }
                                    if (isVideo) {
                                        Button(onClick = {
                                            val done = viewModel.historyDownloads.firstOrNull {
                                                it.id == state.downloadId
                                            }
                                            if (done != null) {
                                                viewModel.requestLoadClipSource(done)
                                                onNavigateToClip()
                                            }
                                        }) { Text("Buat Clip Virtual") }
                                    }
                                    OutlinedButton(onClick = {
                                        viewModel.requestDismissDownloadCard()
                                    }) { Text("Tutup") }
                                }
                            }
                            "FAILED" -> {
                                Text(
                                    "Gagal: ${state.errorMessage ?: "tanpa alasan"}",
                                    fontSize = 13.sp, color = Color(0xFFF87171)
                                )
                                OutlinedButton(onClick = {
                                    viewModel.requestDismissDownloadCard()
                                }) { Text("Tutup") }
                            }
                            "CANCELLED" -> {
                                Text(
                                    "Download dibatalkan.",
                                    fontSize = 13.sp, color = Color(0xFF9CA3AF)
                                )
                                OutlinedButton(onClick = {
                                    viewModel.requestDismissDownloadCard()
                                }) { Text("Tutup") }
                            }
                        }
                    }
                }
            }
        }
        if (viewModel.statusMessage.isNotBlank()) {
            item {
                Text(
                    viewModel.statusMessage,
                    fontSize = 13.sp, color = Color(0xFF9CA3AF)
                )
            }
        }
    }
}
