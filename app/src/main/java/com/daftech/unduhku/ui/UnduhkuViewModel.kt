package com.daftech.unduhku.ui

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.daftech.unduhku.clip.ClipExporter
import com.daftech.unduhku.clip.ClipPlanner
import com.daftech.unduhku.clip.ThumbnailLoader
import com.daftech.unduhku.download.DownloadEngine
import com.daftech.unduhku.download.DownloadService
import com.daftech.unduhku.download.LinkResolver
import com.daftech.unduhku.download.LinkUtils
import com.daftech.unduhku.download.MultiChunkDownloader
import com.daftech.unduhku.history.DownloadHistoryEntity
import com.daftech.unduhku.history.ExportedClipEntity
import com.daftech.unduhku.history.HistoryRepository
import com.daftech.unduhku.model.ArchiveFile
import com.daftech.unduhku.model.VirtualClip
import com.daftech.unduhku.prompt.ApiKeyStore
import com.daftech.unduhku.prompt.ClipAnalysis
import com.daftech.unduhku.prompt.PromptEngine
import com.daftech.unduhku.prompt.PromptRenderer
import com.daftech.unduhku.prompt.PromptResult
import com.daftech.unduhku.prompt.PromptTarget
import com.daftech.unduhku.prompt.VideoPromptGenerator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel UnduhKu — SATU ViewModel untuk 3 tab (Unduh / Clip / Riwayat).
 *
 * ATURAN (pelajaran SuaraKu + ClipKu, JANGAN dilanggar):
 *  - SEMUA state dideklarasikan SEBELUM blok init. Init hanya
 *    me-launch collect Flow (Main) — tidak ada refresh() yang
 *    menyentuh state sebelum dideklarasi.
 *  - JANGAN tulis Compose state dari thread IO: hasil kerja IO
 *    selalu kembali ke Main dulu (viewModelScope = Main; kerja IO
 *    dibungkus withContext(Dispatchers.IO) dan state ditulis setelahnya).
 *  - JANGAN namai fungsi set<Property> kalau ada var dengan nama sama
 *    (clash setter JVM). Semua fungsi memakai updateX / requestX /
 *    startX di sini, bukan pola setter.
 */
class UnduhkuViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "UnduhKu"
    }

    private val repository = HistoryRepository(application)

    // ---------- Tab Unduh ----------
    var link by mutableStateOf("")
        private set
    var isResolving by mutableStateOf(false)
        private set
    var resolveError by mutableStateOf<String?>(null)
        private set
    var directResult by mutableStateOf<LinkResolver.Result.Direct?>(null)
        private set
    var archiveResult by mutableStateOf<LinkResolver.Result.Archive?>(null)
        private set
    var selectedArchiveFile by mutableStateOf<ArchiveFile?>(null)
        private set
    var downloadState by mutableStateOf(DownloadEngine.State())
        private set
    var statusMessage by mutableStateOf("")
        private set

    // ---------- Tab Clip ----------
    var clipSource by mutableStateOf<DownloadHistoryEntity?>(null)
        private set
    var clipDurationMs by mutableStateOf(0L)
        private set
    var clipSegmentMs by mutableStateOf(10_000L)
        private set
    var virtualClips by mutableStateOf<List<VirtualClip>>(emptyList())
        private set
    var clipThumbnails by mutableStateOf<Map<Int, Bitmap>>(emptyMap())
        private set
    var isBuildingClips by mutableStateOf(false)
        private set
    var isExporting by mutableStateOf(false)
        private set
    var exportProgress by mutableStateOf("")
        private set
    /** Alasan gagal export TERAKHIR (persisten, tidak ditimpa ringkasan). */
    var lastExportError by mutableStateOf<String?>(null)
        private set
    var previewClip by mutableStateOf<VirtualClip?>(null)
        private set
    var previewError by mutableStateOf<String?>(null)
        private set

    // ---------- Riwayat ----------
    var historyDownloads by mutableStateOf<List<DownloadHistoryEntity>>(emptyList())
        private set
    var historyExports by mutableStateOf<List<ExportedClipEntity>>(emptyList())
        private set

    // ---------- Video ke Prompt (v1.1.0) ----------
    var promptVisible by mutableStateOf(false)
        private set
    var promptClip by mutableStateOf<VirtualClip?>(null)
        private set
    var promptClipName by mutableStateOf("")
        private set
    var promptEngine by mutableStateOf(PromptEngine.GEMINI)
        private set
    var promptTarget by mutableStateOf(PromptTarget.OMNI_10S)
        private set
    var promptLoading by mutableStateOf(false)
        private set
    var promptProgress by mutableStateOf("")
        private set
    var promptResult by mutableStateOf<PromptResult?>(null)
        private set
    var promptError by mutableStateOf<String?>(null)
        private set
    var promptGeminiKeySet by mutableStateOf(false)
        private set
    var promptGroqKeySet by mutableStateOf(false)
        private set

    // ---------- Private (bukan state) ----------
    private var player: ExoPlayer? = null
    private var exportJob: Job? = null
    private val thumbnailRequested = mutableSetOf<Int>()
    /** Penyimpanan kunci API (EncryptedSharedPreferences). */
    val keyStore = ApiKeyStore(application)
    private val promptGenerator = VideoPromptGenerator(application, keyStore)
    private var promptJob: Job? = null
    private var lastAnalysis: ClipAnalysis? = null
    private var promptClipFile: File? = null

    /** ExoPlayer untuk preview clip — dibaca ClipScreen via AndroidView. */
    fun peekPlayer(): ExoPlayer? = player

    init {
        DownloadEngine.init(application)
        viewModelScope.launch {
            DownloadEngine.state.collect { downloadState = it }
        }
        viewModelScope.launch {
            repository.observeAllDownloads().collect { historyDownloads = it }
        }
        viewModelScope.launch {
            repository.observeAllExports().collect { historyExports = it }
        }
    }

    override fun onCleared() {
        try {
            player?.release()
        } catch (e: Exception) { /* abaikan */
        }
        player = null
        super.onCleared()
    }

    // ================= Tab Unduh =================

    fun updateLink(text: String) {
        link = text
    }

    fun updateStatus(text: String) {
        statusMessage = text
    }

    fun requestResolveLink() {
        val raw = link.trim()
        if (raw.isEmpty()) {
            resolveError = "Tempel link dulu."
            return
        }
        if (isResolving) return
        viewModelScope.launch {
            isResolving = true
            resolveError = null
            directResult = null
            archiveResult = null
            selectedArchiveFile = null
            when (val result = LinkResolver.resolve(raw)) {
                is LinkResolver.Result.Direct -> directResult = result
                is LinkResolver.Result.Archive -> archiveResult = result
                is LinkResolver.Result.Error -> resolveError = result.message
            }
            isResolving = false
        }
    }

    fun updateSelectedArchiveFile(file: ArchiveFile?) {
        selectedArchiveFile = file
    }

    /** Mulai download lewat foreground service. False = sudah ada download aktif. */
    fun requestStartDownload() {
        if (DownloadEngine.state.value.isActive) {
            statusMessage = "Masih ada download aktif — jeda / batalkan dulu."
            return
        }
        val request = buildDownloadRequest() ?: run {
            statusMessage = "Periksa link dulu, lalu pilih file."
            return
        }
        val app = getApplication<Application>()
        ContextCompat.startForegroundService(
            app,
            DownloadService.startIntent(
                app,
                url = request.url,
                fileName = request.fileName,
                totalBytes = request.totalBytes,
                expectedMd5 = request.expectedMd5,
                supportsRanges = request.supportsRanges
            )
        )
        statusMessage = "Download dimulai: ${request.fileName}"
    }

    private fun buildDownloadRequest(): MultiChunkDownloader.Request? {
        val direct = directResult
        if (direct != null) {
            return MultiChunkDownloader.Request(
                url = direct.url,
                fileName = direct.fileName,
                totalBytes = direct.sizeBytes,
                expectedMd5 = null,
                supportsRanges = direct.supportsRanges
            )
        }
        val archive = archiveResult
        val chosen = selectedArchiveFile
        if (archive != null && chosen != null) {
            return MultiChunkDownloader.Request(
                url = LinkUtils.archiveDownloadUrl(archive.id, chosen.name),
                fileName = LinkUtils.sanitizeFileName(
                    chosen.name.substringAfterLast('/')
                ),
                totalBytes = chosen.sizeBytes,
                expectedMd5 = chosen.md5,
                // archive.org terverifikasi dukung Accept-Ranges: bytes.
                supportsRanges = true
            )
        }
        return null
    }

    fun requestPauseDownload() {
        DownloadEngine.pauseDownload()
    }

    fun requestResumeDownload() {
        DownloadEngine.resumeDownload()
    }

    fun requestCancelDownload() {
        DownloadEngine.cancelDownload()
    }

    fun requestDismissDownloadCard() {
        DownloadEngine.resetToIdle()
    }

    // ================= Tab Clip =================

    /** Pilih video hasil download sebagai sumber clip virtual. */
    fun requestLoadClipSource(download: DownloadHistoryEntity) {
        requestStopPreview()
        clipSource = download
        virtualClips = emptyList()
        clipThumbnails = emptyMap()
        thumbnailRequested.clear()
        clipDurationMs = 0L
        exportProgress = ""
        viewModelScope.launch {
            val uri = storageUriToUri(download.storageUri)
            if (uri == null) {
                statusMessage = "File sumber tidak ditemukan (sudah dihapus?)."
                return@launch
            }
            val duration = ThumbnailLoader.videoDurationMs(getApplication(), uri)
            if (duration == null || duration <= 0L) {
                statusMessage = "Durasi video tidak terbaca — file mungkin bukan video."
                return@launch
            }
            clipDurationMs = duration
            requestBuildVirtualClips()
        }
    }

    /** Lepas video sumber (kembali ke daftar pilih video). */
    fun requestClearClipSource() {
        requestStopPreview()
        clipSource = null
        virtualClips = emptyList()
        clipThumbnails = emptyMap()
        thumbnailRequested.clear()
        clipDurationMs = 0L
        exportProgress = ""
    }

    fun updateSegmentDuration(segmentMs: Long) {
        if (segmentMs == clipSegmentMs) return
        clipSegmentMs = segmentMs
        if (clipSource != null && clipDurationMs > 0L) requestBuildVirtualClips()
    }

    fun requestBuildVirtualClips() {
        if (clipDurationMs <= 0L) return
        isBuildingClips = true
        virtualClips = ClipPlanner.buildVirtualClips(clipDurationMs, clipSegmentMs)
        clipThumbnails = emptyMap()
        thumbnailRequested.clear()
        exportProgress = "${virtualClips.size} clip virtual siap — centang lalu Export."
        isBuildingClips = false
    }

    fun updateClipSelection(index: Int, selected: Boolean) {
        virtualClips = virtualClips.map {
            if (it.index == index) it.copy(isSelected = selected) else it
        }
    }

    fun requestToggleSelectAll() {
        val allSelected = virtualClips.isNotEmpty() &&
            virtualClips.all { it.isSelected }
        virtualClips = virtualClips.map { it.copy(isSelected = !allSelected) }
    }

    fun requestThumbnail(index: Int) {
        val clip = virtualClips.getOrNull(index) ?: return
        val source = clipSource ?: return
        if (clipThumbnails.containsKey(index)) return
        if (!thumbnailRequested.add(index)) return
        viewModelScope.launch {
            val uri = storageUriToUri(source.storageUri) ?: return@launch
            val bitmap = ThumbnailLoader.thumbnail(
                getApplication(), uri, clip.startMs,
                "thumb-${source.id}-$index"
            )
            if (bitmap != null) {
                clipThumbnails = clipThumbnails + (index to bitmap)
            }
        }
    }

    /** Preview satu clip virtual di player atas layar Clip. */
    fun requestPreviewClip(clip: VirtualClip) {
        val source = clipSource ?: return
        val uri = storageUriToUri(source.storageUri) ?: run {
            previewError = "File sumber tidak ditemukan."
            return
        }
        previewError = null
        previewClip = clip
        val exo = ensurePlayer()
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clip.startMs)
            .setEndPositionMs(clip.endMs)
            .build()
        val item = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipping)
            .build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.play()
    }

    fun requestStopPreview() {
        try {
            player?.stop()
        } catch (e: Exception) { /* abaikan */
        }
        previewClip = null
        previewError = null
    }

    /** Export HANYA clip yang dicentang — satu per satu, berurutan. */
    fun requestExportSelected() {
        val source = clipSource ?: return
        val selected = virtualClips.filter { it.isSelected && it.exportedPath == null }
        if (selected.isEmpty()) {
            exportProgress = "Tidak ada clip baru yang dicentang."
            return
        }
        if (isExporting) return
        val inputUri = storageUriToUri(source.storageUri) ?: run {
            exportProgress = "File sumber tidak ditemukan."
            return
        }
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            isExporting = true
            lastExportError = null
            var okCount = 0
            var failCount = 0
            var firstPublishedPath: String? = null
            for (clip in selected) {
                virtualClips = virtualClips.map {
                    if (it.index == clip.index) it.copy(isExporting = true) else it
                }
                exportProgress = "Mengekspor clip ${clip.index + 1}/${virtualClips.size}…"
                val fileName = ClipPlanner.clipFileName(source.fileName, clip)
                val tempFile = File(
                    getApplication<Application>().cacheDir,
                    "unduhku-cut-$fileName"
                )
                if (tempFile.exists()) tempFile.delete()
                val (ok, reason) = ClipExporter.exportClip(
                    getApplication(), inputUri, clip.startMs, clip.endMs, tempFile
                )
                if (!ok || !tempFile.exists() || tempFile.length() <= 0L) {
                    failCount++
                    lastExportError = "Clip ${clip.index + 1} gagal: " +
                        (reason ?: "tanpa alasan")
                    virtualClips = virtualClips.map {
                        if (it.index == clip.index) it.copy(isExporting = false) else it
                    }
                    exportProgress = "Clip ${clip.index + 1} gagal (${reason ?: "tanpa alasan"}) — lanjut ke berikutnya."
                    continue
                }
                val publishedPath = publishExport(tempFile, fileName)
                if (publishedPath == null) {
                    failCount++
                    lastExportError = "Clip ${clip.index + 1} gagal: " +
                        "tidak bisa menyimpan ke Download/UnduhKu/clips"
                    virtualClips = virtualClips.map {
                        if (it.index == clip.index) it.copy(isExporting = false) else it
                    }
                    continue
                }
                repository.insertExport(
                    ExportedClipEntity(
                        downloadId = source.id,
                        clipIndex = clip.index,
                        startMs = clip.startMs,
                        endMs = clip.endMs,
                        outputPath = publishedPath
                    )
                )
                if (firstPublishedPath == null) firstPublishedPath = publishedPath
                okCount++
                virtualClips = virtualClips.map {
                    if (it.index == clip.index) {
                        it.copy(isExporting = false, exportedPath = publishedPath)
                    } else {
                        it
                    }
                }
            }
            isExporting = false
            // Lokasi yang ditampilkan HANYA yang benar-benar dikembalikan
            // publish pertama yang sukses — kalau tidak ada yang sukses,
            // jangan klaim lokasi apa pun (pelajaran fix3: klaim hardcode
            // "Download/UnduhKu/clips/" ternyata tidak sesuai kenyataan).
            exportProgress = if (okCount > 0 && firstPublishedPath != null) {
                "Selesai: $okCount berhasil, $failCount gagal. " +
                    "Hasil: $firstPublishedPath"
            } else {
                "Selesai: $okCount berhasil, $failCount gagal."
            }
        }
    }

    /**
     * Publish file clip ke Download/UnduhKu/clips (fix4).
     *
     * PELAJARAN fix3: insert ke `MediaStore.Video` dengan RELATIVE_PATH
     * "Download/..." itu BUKAN lokasi valid buat koleksi Video
     * (RELATIVE_PATH hanya hint di bawah top-level directory yang
     * relevan dengan tipenya — buat video itu Movies/DCIM). MediaStore
     * mengabaikan hint itu, file mendarat di Movies/ atau fallback,
     * sementara kode mengembalikan string hardcode yang tidak pernah
     * diverifikasi — UI mengklaim sukses di lokasi yang salah.
     *
     * Jalur utama sekarang: `MediaStore.Downloads` — koleksi yang
     * memang menerima path Download/. Sukses HANYA setelah byte
     * benar-benar tertulis, dan path yang dikembalikan adalah hasil
     * VERIFIKASI query URI (RELATIVE_PATH + DISPLAY_NAME, atau DATA
     * kalau tersedia) — bukan hardcode.
     */
    private suspend fun publishExport(tempFile: File, fileName: String): String? =
        withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/UnduhKu/clips")
                    }
                }
                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    val out = resolver.openOutputStream(uri)
                    if (out != null) {
                        var bytesWritten = 0L
                        out.use { o -> bytesWritten = tempFile.inputStream().copyTo(o) }
                        if (bytesWritten > 0L) {
                            tempFile.delete()
                            val verified = verifyDownloadPath(resolver, uri, fileName)
                            if (verified != null) {
                                Log.i(TAG, "publishExport OK: $verified " +
                                    "(${bytesWritten} byte)")
                                return@withContext verified
                            }
                            Log.w(TAG, "publishExport: lokasi tidak " +
                                "terverifikasi, klaim Download/UnduhKu/clips/$fileName")
                            return@withContext "Download/UnduhKu/clips/$fileName"
                        }
                    }
                    try {
                        resolver.delete(uri, null, null)
                    } catch (e: Exception) { /* abaikan */
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "publishExport: insert/tulis " +
                    "MediaStore.Downloads gagal", e)
            }
            // Fallback: folder file eksternal app (selalu bisa ditulis).
            try {
                val dir = File(
                    app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "UnduhKu/clips"
                ).apply { mkdirs() }
                val target = File(dir, fileName)
                tempFile.copyTo(target, overwrite = true)
                tempFile.delete()
                Log.i(TAG, "publishExport fallback OK: ${target.absolutePath}")
                target.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "publishExport: fallback gagal", e)
                null
            }
        }

    /**
     * Tanyakan ke MediaStore di mana sebenarnya file hasil insert
     * tadi mendarat. Kembalikan path yang bisa ditampilkan ke user
     * ("Download/UnduhKu/clips/<nama>") atau DATA absolut kalau ada;
     * null kalau query gagal (pemanggil memakai klaim terakhir).
     */
    private fun verifyDownloadPath(
        resolver: android.content.ContentResolver,
        uri: Uri,
        fileName: String
    ): String? {
        return try {
            val projection = arrayOf(
                MediaStore.Downloads.RELATIVE_PATH,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA
            )
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val data = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                if (!data.isNullOrBlank()) return@use data
                val relIdx = cursor.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH)
                val nameIdx = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                val rel = if (relIdx >= 0) cursor.getString(relIdx) else null
                val name = if (nameIdx >= 0) {
                    cursor.getString(nameIdx)
                } else {
                    null
                } ?: fileName
                if (rel.isNullOrBlank()) return@use null
                rel.trimEnd('/') + "/" + name
            }
        } catch (e: Exception) {
            Log.w(TAG, "verifyDownloadPath: query gagal", e)
            null
        }
    }

    // ================= Video ke Prompt (v1.1.0) =================

    /** Buka layar Prompt untuk clip yang SUDAH diekspor (file fisik ada). */
    fun requestOpenPrompt(clip: VirtualClip) {
        val file = clip.exportedPath?.let { exportedPathToFile(it) }
        if (file == null) {
            statusMessage = "File clip hasil export tidak ditemukan."
            return
        }
        promptJob?.cancel()
        promptClip = clip
        promptClipFile = file
        promptClipName = file.name
        lastAnalysis = null
        promptResult = null
        promptError = null
        promptProgress = ""
        promptGeminiKeySet = keyStore.hasGeminiKey()
        promptGroqKeySet = keyStore.hasGroqKey()
        // Kalau engine aktif kuncinya kosong tapi engine lain ada
        // kuncinya, pindah otomatis ke yang bisa jalan.
        if (promptEngine == PromptEngine.GEMINI && !promptGeminiKeySet &&
            promptGroqKeySet
        ) {
            promptEngine = PromptEngine.GROQ
        }
        promptVisible = true
    }

    fun requestClosePrompt() {
        promptJob?.cancel()
        promptVisible = false
        promptLoading = false
    }

    fun updatePromptEngine(engine: PromptEngine) {
        if (engine == promptEngine) return
        promptEngine = engine
        promptError = null
    }

    /**
     * Ganti target Omni/Veo — render ulang dari analisis TERAKHIR
     * tanpa panggil API lagi (hemat kuota).
     */
    fun updatePromptTarget(target: PromptTarget) {
        if (target == promptTarget) return
        promptTarget = target
        val analysis = lastAnalysis
        if (analysis != null) {
            promptResult = PromptRenderer.render(analysis, target)
        }
    }

    /** Generate prompt — engine dicek kuncinya dulu (jangan error mentah). */
    fun requestGeneratePrompt() {
        val file = promptClipFile ?: run {
            promptError = "File clip tidak ditemukan."
            return
        }
        val clip = promptClip ?: return
        if (promptLoading) return
        val hasKey = if (promptEngine == PromptEngine.GEMINI) {
            keyStore.hasGeminiKey()
        } else {
            keyStore.hasGroqKey()
        }
        if (!hasKey) {
            promptError = if (promptEngine == PromptEngine.GEMINI) {
                "Kunci Gemini belum diisi — buka tab Pengaturan."
            } else {
                "Kunci Groq belum diisi — buka tab Pengaturan."
            }
            return
        }
        promptJob?.cancel()
        promptJob = viewModelScope.launch {
            promptLoading = true
            promptError = null
            promptResult = null
            promptProgress = "Menyiapkan…"
            // ATURAN state: callback progress dipanggil generator dari
            // thread IO — tulis state lewat viewModelScope (Main),
            // JANGAN langsung dari callback.
            val outcome = promptGenerator.generate(
                clipFile = file,
                clipDurationMs = clip.durationMs,
                engine = promptEngine,
                target = promptTarget,
                onProgress = { msg ->
                    viewModelScope.launch { promptProgress = msg }
                }
            )
            promptLoading = false
            when (outcome) {
                is VideoPromptGenerator.Outcome.Ok -> {
                    lastAnalysis = outcome.analysis
                    promptResult = outcome.result
                    promptProgress = "Selesai."
                }
                is VideoPromptGenerator.Outcome.Fail -> {
                    promptError = outcome.message
                    promptProgress = ""
                }
            }
        }
    }

    /**
     * Path hasil export (fix4) → File. Bentuknya dua macam:
     *  - absolut (fallback app-private): "/storage/.../xxx.mp4"
     *  - relatif MediaStore: "Download/UnduhKu/clips/xxx.mp4"
     *    (= /storage/emulated/0/Download/UnduhKu/clips/xxx.mp4).
     */
    private fun exportedPathToFile(path: String): File? {
        return try {
            when {
                path.startsWith("/") -> File(path).takeIf { it.exists() }
                path.startsWith("Download/") -> File(
                    Environment.getExternalStorageDirectory(), path
                ).takeIf { it.exists() }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ================= Riwayat =================

    fun requestDeleteDownload(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteDownload(id)
        }
    }

    fun requestClearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteAll()
        }
    }

    /** Buka file hasil download dengan app lain (player / file manager). */
    fun requestOpenDownload(download: DownloadHistoryEntity) {
        val uri = storageUriToUri(download.storageUri) ?: run {
            statusMessage = "File tidak ditemukan."
            return
        }
        val mime = when (download.fileName.substringAfterLast('.', "").lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            statusMessage = "Tidak ada app yang bisa membuka file ini."
        }
    }

    // ================= Util =================

    /**
     * Uri yang bisa dipakai retriever / Transformer / ExoPlayer:
     * content:// langsung, atau FileProvider untuk path file fallback.
     */
    private fun storageUriToUri(storage: String?): Uri? {
        if (storage.isNullOrBlank()) return null
        return try {
            if (storage.startsWith("content://")) {
                Uri.parse(storage)
            } else if (storage.startsWith("file:")) {
                val file = File(storage.removePrefix("file:"))
                if (!file.exists()) return null
                FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<Application>().packageName}.fileprovider",
                    file
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        val existing = player
        if (existing != null) return existing
        return ExoPlayer.Builder(getApplication<Application>()).build().also { exo ->
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    previewError = "Preview gagal: ${error.message ?: "error player"}"
                }
            })
            player = exo
        }
    }
}
