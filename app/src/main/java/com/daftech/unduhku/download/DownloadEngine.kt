package com.daftech.unduhku.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.daftech.unduhku.history.DownloadHistoryEntity
import com.daftech.unduhku.history.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Satu-satunya pemilik status download (singleton).
 *
 * ViewModel + DownloadService + notifikasi semuanya mengamati
 * [state] — TIDAK ada status download kedua yang bisa drift.
 * Riwayat Room ditulis DI SINI (bukan di ViewModel) supaya download
 * yang jalan di foreground service tetap tercatat walau Activity mati.
 *
 * SATU download aktif dalam satu waktu (scope v1 — tanpa antrean).
 * Jeda: worker parkir di delay(200) sampai resume; proses yang mati
 * saat jeda bisa dilanjutkan lewat startDownload ulang dengan
 * request yang sama (part di cache deterministik dari URL+nama+ukuran).
 */
object DownloadEngine {

    data class State(
        val status: String = "IDLE",
        val url: String = "",
        val fileName: String = "",
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val bytesPerSec: Double = 0.0,
        val errorMessage: String? = null,
        val storageUri: String? = null,
        val displayPath: String = "",
        val downloadId: Long = 0L
    ) {
        val isActive: Boolean get() = status == "DOWNLOADING" || status == "PAUSED"
        val progressFraction: Float
            get() = if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var repository: HistoryRepository? = null
    private var job: Job? = null
    private var downloader: MultiChunkDownloader? = null
    private var currentRequest: MultiChunkDownloader.Request? = null

    // Sampel terakhir untuk hitung kecepatan (disentuh hanya di Main).
    private var lastSampleBytes = 0L
    private var lastSampleMs = 0L
    private var lastHistoryWriteMs = 0L

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            repository = HistoryRepository(appContext!!)
        }
    }

    /**
     * Mulai download. False kalau sudah ada download aktif — pemanggil
     * harus menampilkan pesan, bukan menimpa download yang jalan.
     * [scope] = serviceScope (service) atau viewModelScope (fallback
     * tanpa service — tidak dipakai v1, service selalu dipakai).
     */
    fun startDownload(
        context: Context,
        scope: CoroutineScope,
        request: MultiChunkDownloader.Request
    ): Boolean {
        if (_state.value.isActive) return false
        init(context)
        currentRequest = request
        lastSampleBytes = 0L
        lastSampleMs = System.currentTimeMillis()
        lastHistoryWriteMs = 0L
        postState {
            State(
                status = "DOWNLOADING",
                url = request.url,
                fileName = request.fileName,
                totalBytes = request.totalBytes
            )
        }
        val app = context.applicationContext
        job = scope.launch {
            val repo = repository
            var historyId = 0L
            try {
                historyId = repo?.insertDownload(
                    DownloadHistoryEntity(
                        url = request.url,
                        fileName = request.fileName,
                        totalBytes = request.totalBytes,
                        downloadedBytes = 0L,
                        status = "DOWNLOADING",
                        displayPath = "Download/UnduhKu/${request.fileName}",
                        expectedMd5 = request.expectedMd5
                    )
                ) ?: 0L
                postState { it.copy(downloadId = historyId) }
                val dl = MultiChunkDownloader(app)
                downloader = dl
                val outcome = dl.download(request) { downloadedBytes ->
                    postProgress(downloadedBytes)
                }
                // Tulis hasil di NonCancellable: kalau job dibatalkan
                // tepat saat download selesai, tulis riwayat + status
                // akhir TETAP jalan (bukan hilang karena cancel).
                withContext(NonCancellable) {
                    if (outcome.ok) {
                        repo?.updateDownload(
                            DownloadHistoryEntity(
                                id = historyId,
                                url = request.url,
                                fileName = request.fileName,
                                totalBytes = request.totalBytes,
                                downloadedBytes = request.totalBytes,
                                status = "COMPLETED",
                                storageUri = outcome.storageUri,
                                displayPath = outcome.displayPath ?: "",
                                expectedMd5 = request.expectedMd5,
                                actualMd5 = outcome.actualMd5
                            )
                        )
                        postState {
                            it.copy(
                                status = "COMPLETED",
                                downloadedBytes = request.totalBytes,
                                bytesPerSec = 0.0,
                                storageUri = outcome.storageUri,
                                displayPath = outcome.displayPath ?: ""
                            )
                        }
                    } else {
                        val reason = outcome.reason ?: "gagal tanpa alasan"
                        // download() menelan CancellationException dan
                        // mengembalikannya sebagai Outcome "dibatalkan" —
                        // tampilkan sebagai CANCELLED, bukan FAILED.
                        val finalStatus =
                            if (reason == "dibatalkan") "CANCELLED" else "FAILED"
                        repo?.updateDownload(
                            DownloadHistoryEntity(
                                id = historyId,
                                url = request.url,
                                fileName = request.fileName,
                                totalBytes = request.totalBytes,
                                downloadedBytes = _state.value.downloadedBytes,
                                status = finalStatus,
                                displayPath = "Download/UnduhKu/${request.fileName}",
                                expectedMd5 = request.expectedMd5,
                                errorMessage = if (finalStatus == "FAILED") reason else null
                            )
                        )
                        postState {
                            it.copy(
                                status = finalStatus,
                                bytesPerSec = 0.0,
                                errorMessage = if (finalStatus == "FAILED") reason else null
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    try {
                        withContext(NonCancellable) {
                            repo?.updateDownload(
                                DownloadHistoryEntity(
                                    id = historyId,
                                    url = request.url,
                                    fileName = request.fileName,
                                    totalBytes = request.totalBytes,
                                    downloadedBytes = _state.value.downloadedBytes,
                                    status = "CANCELLED",
                                    displayPath = "Download/UnduhKu/${request.fileName}",
                                    expectedMd5 = request.expectedMd5
                                )
                            )
                        }
                    } catch (e2: Exception) { /* best-effort */
                    }
                    postState { it.copy(status = "CANCELLED", bytesPerSec = 0.0) }
                } else {
                    val reason = (e.message ?: e.javaClass.simpleName).take(100)
                    postState {
                        it.copy(status = "FAILED", bytesPerSec = 0.0, errorMessage = reason)
                    }
                }
            } finally {
                downloader = null
            }
        }
        return true
    }

    fun pauseDownload() {
        if (_state.value.status != "DOWNLOADING") return
        downloader?.pause()
        postState { it.copy(status = "PAUSED", bytesPerSec = 0.0) }
        writeHistoryProgress(force = true)
    }

    fun resumeDownload() {
        if (_state.value.status != "PAUSED") return
        downloader?.resume()
        lastSampleMs = System.currentTimeMillis()
        lastSampleBytes = _state.value.downloadedBytes
        postState { it.copy(status = "DOWNLOADING") }
    }

    fun cancelDownload() {
        if (!_state.value.isActive) return
        downloader?.cancel()
        job?.cancel()
    }

    /** Kembali ke IDLE setelah user menutup kartu selesai/gagal. */
    fun resetToIdle() {
        if (_state.value.isActive) return
        postState { State() }
    }

    private fun postProgress(downloadedBytes: Long) {
        // Dipanggil dari thread IO downloader — pindah ke Main dulu
        // (pelajaran ClipKu v1.4.0: JANGAN tulis Compose state dari IO).
        mainHandler.post {
            val now = System.currentTimeMillis()
            val elapsed = (now - lastSampleMs).coerceAtLeast(1L)
            val delta = (downloadedBytes - lastSampleBytes).coerceAtLeast(0L)
            val speed = delta * 1000.0 / elapsed
            lastSampleBytes = downloadedBytes
            lastSampleMs = now
            _state.value = _state.value.copy(
                downloadedBytes = downloadedBytes,
                bytesPerSec = speed
            )
            writeHistoryProgress(force = false)
        }
    }

    private fun writeHistoryProgress(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHistoryWriteMs < 5_000L) return
        lastHistoryWriteMs = now
        val current = _state.value
        val id = current.downloadId
        if (id == 0L) return
        // Tulis di IO — fire-and-forget, jangan blokir Main.
        val repo = repository ?: return
        val snapshot = current
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val existing = repo.getDownload(id) ?: return@launch
                repo.updateDownload(
                    existing.copy(
                        downloadedBytes = snapshot.downloadedBytes,
                        status = snapshot.status
                    )
                )
            } catch (e: Exception) { /* best-effort */
            }
        }
    }

    private fun postState(transform: (State) -> State) {
        mainHandler.post { _state.value = transform(_state.value) }
    }
}
