package com.daftech.unduhku.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftech.unduhku.download.LinkUtils
import com.daftech.unduhku.history.DownloadHistoryEntity

/**
 * Tab 3 — Riwayat download + clip hasil export.
 * "Hapus" hanya menghapus BARIS riwayat — file di Downloads
 * milik user dan TIDAK ikut dihapus.
 */
@Composable
fun HistoryScreen(
    viewModel: UnduhkuViewModel,
    onMakeClip: (DownloadHistoryEntity) -> Unit
) {
    // Breadcrumb (fix4): tap tab Riwayat pernah menutup app tanpa
    // pesan. Baris ini memastikan dari logcat ketahuan apakah crash
    // terjadi SEBELUM atau SESUDAH HistoryScreen mulai dirender.
    LaunchedEffect(Unit) {
        Log.i(
            "UnduhKu",
            "HistoryScreen: ${viewModel.historyDownloads.size} download, " +
                "${viewModel.historyExports.size} export"
        )
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Riwayat", fontSize = 20.sp, color = Color.White)
                TextButton(onClick = { viewModel.requestClearHistory() }) {
                    Text("Hapus Semua")
                }
            }
        }
        item {
            Text("Download", fontSize = 16.sp, color = Color.White)
        }
        if (viewModel.historyDownloads.isEmpty()) {
            item {
                Text(
                    "Belum ada download.",
                    fontSize = 13.sp, color = Color(0xFF9CA3AF)
                )
            }
        }
        items(viewModel.historyDownloads, key = { it.id }) { download ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(download.fileName, fontSize = 15.sp, color = Color.White)
                    Text(
                        "Status: ${download.status} • " +
                            "${LinkUtils.formatBytes(download.downloadedBytes)} / " +
                            LinkUtils.formatBytes(download.totalBytes),
                        fontSize = 13.sp, color = Color(0xFF9CA3AF)
                    )
                    Text(
                        download.displayPath,
                        fontSize = 12.sp, color = Color(0xFF9CA3AF)
                    )
                    if (download.actualMd5 != null && download.expectedMd5 != null) {
                        Text(
                            if (download.actualMd5.equals(download.expectedMd5, ignoreCase = true)) {
                                "MD5 cocok — file utuh."
                            } else {
                                "MD5 TIDAK cocok."
                            },
                            fontSize = 12.sp,
                            color = if (download.actualMd5.equals(download.expectedMd5, ignoreCase = true)) {
                                Color(0xFF4ADE80)
                            } else {
                                Color(0xFFF87171)
                            }
                        )
                    }
                    if (download.errorMessage != null) {
                        Text(
                            download.errorMessage,
                            fontSize = 12.sp, color = Color(0xFFF87171)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.requestOpenDownload(download) }) {
                            Text("Buka")
                        }
                        val isVideo = download.fileName.lowercase().let {
                            download.status == "COMPLETED" &&
                                (it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".webm"))
                        }
                        if (isVideo) {
                            OutlinedButton(onClick = { onMakeClip(download) }) {
                                Text("Buat Clip")
                            }
                        }
                        OutlinedButton(onClick = {
                            viewModel.requestDeleteDownload(download.id)
                        }) { Text("Hapus") }
                    }
                }
            }
        }
        item {
            Text("Clip Hasil Export", fontSize = 16.sp, color = Color.White)
        }
        if (viewModel.historyExports.isEmpty()) {
            item {
                Text(
                    "Belum ada clip yang diekspor.",
                    fontSize = 13.sp, color = Color(0xFF9CA3AF)
                )
            }
        }
        items(viewModel.historyExports, key = { it.id }) { clip ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Clip ${clip.clipIndex + 1} • " +
                            "${LinkUtils.formatDurationMs(clip.startMs)}–" +
                            LinkUtils.formatDurationMs(clip.endMs),
                        fontSize = 14.sp, color = Color.White
                    )
                    Text(
                        clip.outputPath,
                        fontSize = 12.sp, color = Color(0xFF9CA3AF)
                    )
                }
            }
        }
    }
}
