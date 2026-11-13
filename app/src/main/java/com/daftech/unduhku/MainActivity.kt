package com.daftech.unduhku

import android.os.Bundle
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
import com.daftech.unduhku.ui.UnduhkuTheme
import com.daftech.unduhku.ui.UnduhkuViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    // Satu ViewModel dipakai bersama 3 tab supaya hasil
                    // resolve/download tidak hilang saat pindah tab.
                    val viewModel: UnduhkuViewModel = viewModel()
                    var selectedTab by remember { mutableStateOf(0) }
                    val tabs = listOf("Unduh", "Clip", "Riwayat")
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
                            else -> HistoryScreen(
                                viewModel = viewModel,
                                onMakeClip = { download ->
                                    viewModel.requestLoadClipSource(download)
                                    selectedTab = 1
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
