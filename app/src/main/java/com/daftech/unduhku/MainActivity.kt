package com.daftech.unduhku

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daftech.unduhku.download.DownloadEngine
import com.daftech.unduhku.ui.ClipScreen
import com.daftech.unduhku.ui.DownloadScreen
import com.daftech.unduhku.ui.HistoryScreen
import com.daftech.unduhku.ui.PromptScreen
import com.daftech.unduhku.ui.SettingsScreen
import com.daftech.unduhku.ui.UnduhkuTheme
import com.daftech.unduhku.ui.UnduhkuViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Penangkap crash (fix4): tap tab Riwayat pernah menutup app
        // tanpa pesan — penyebabnya belum ketemu dari baca kode, jadi
        // crash berikutnya WAJIB terekam: stack trace lengkap ditulis
        // ke crash.log + Logcat, lalu diteruskan ke handler default.
        installCrashHandler()
        // Engine butuh context aplikasi untuk Room + downloader.
        DownloadEngine.init(applicationContext)
        setContent {
            UnduhkuTheme {
                // Surface menetapkan LocalContentColor ke onBackground
                // (putih) di atas tema gelap — tanpa ini Text Compose
                // default ke hitam dan tidak terbaca (pelajaran LoopLive).
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Satu ViewModel dipakai bersama semua tab supaya
                    // hasil resolve/download tidak hilang saat pindah tab.
                    val viewModel: UnduhkuViewModel = viewModel()
                    var selectedTab by remember { mutableStateOf(0) }
                    val tabs = listOf("Unduh", "Clip", "Riwayat", "Pengaturan")
                    // Layar Prompt (v1.1.0) menumpuk DI ATAS tab — bukan
                    // tab ke-5; ditutup lewat tombol "Tutup" di layarnya.
                    if (viewModel.promptVisible) {
                        PromptScreen(
                            viewModel = viewModel,
                            onOpenSettings = {
                                viewModel.requestClosePrompt()
                                selectedTab = 3
                            }
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            TabRow(selectedTabIndex = selectedTab) {
                                tabs.forEachIndexed { index, title ->
                                    Tab(
                                        selected = selectedTab == index,
                                        onClick = { selectedTab = index },
                                        text = { Text(title) }
                                    )
                                }
                            }
                            when (selectedTab) {
                                0 -> DownloadScreen(
                                    viewModel = viewModel,
                                    onNavigateToClip = { selectedTab = 1 }
                                )
                                1 -> ClipScreen(viewModel = viewModel)
                                2 -> HistoryScreen(
                                    viewModel = viewModel,
                                    onMakeClip = { download ->
                                        viewModel.requestLoadClipSource(download)
                                        selectedTab = 1
                                    }
                                )
                                else -> SettingsScreen(keyStore = viewModel.keyStore)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Penangkap crash global (fix4). Menulis stack trace lengkap +
     * timestamp + versi app ke `crash.log` di getExternalFilesDir(null)
     * (append, bukan overwrite) dan ke Logcat, lalu meneruskan ke
     * handler default sebelumnya supaya dialog sistem tetap muncul.
     * Handler TIDAK PERNAH melempar — semua dibungkus try/catch.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.US
                ).format(Date())
                val trace = Log.getStackTraceString(throwable)
                val logFile = File(getExternalFilesDir(null), "crash.log")
                logFile.appendText(
                    "=== CRASH $stamp (UnduhKu 1.3.0/vc4, " +
                        "thread ${thread.name}) ===\n$trace\n"
                )
                Log.e("UnduhKu", "CRASH", throwable)
            } catch (e: Exception) {
                // Jangan pernah throw dari dalam handler.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
