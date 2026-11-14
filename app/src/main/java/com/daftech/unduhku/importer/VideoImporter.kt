package com.daftech.unduhku.importer

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.IOException

/**
 * Pemilih video dari perangkat (v1.2.0).
 *
 * Alur: user memilih satu file video lewat pemilih file sistem
 * (OpenDocument, MIME video) -> file DISALIN ke folder import di
 * penyimpanan bersama -> didaftarkan sebagai sumber video biasa
 * (satu baris riwayat status COMPLETED) supaya alur yang sudah ada
 * (daftar clip virtual, export, pembuatan prompt) langsung jalan.
 *
 * Disimpan di penyimpanan bersama lewat MediaStore.Downloads
 * (RELATIVE_PATH Download/UnduhKu/import) — tulis file langsung ke
 * /storage/emulated/0/Download TIDAK diizinkan di targetSdk 34
 * (scoped storage), jadi JANGAN ganti jalur ini dengan File mentah.
 *
 * Penanda sumber: kolom `url` di riwayat diisi [SOURCE_MARKER]
 * (kolom itu tidak pernah ditampilkan di UI, hanya dipakai mesin
 * download) — TANPA ubah skema Room (tidak ada migrasi).
 */
object VideoImporter {

    private const val TAG = "UnduhKu"

    /** Folder tujuan di penyimpanan bersama. */
    const val IMPORT_FOLDER = "Download/UnduhKu/import"

    /** Nilai kolom `url` untuk baris riwayat dari pemilih file. */
    const val SOURCE_MARKER = "local-import"

    /** Ekstensi video yang bisa jadi sumber clip (sama dengan filter layar Clip). */
    val SUPPORTED_EXTENSIONS = setOf("mp4", "mkv", "webm")

    /** Batas aman: sisakan 10 MB di luar ukuran file saat cek ruang kosong. */
    const val FREE_SPACE_MARGIN_BYTES = 10L * 1024L * 1024L

    /** Info file yang dipilih user dari pemilih file sistem. */
    data class PickedInfo(val displayName: String?, val sizeBytes: Long)

    /** Durasi + resolusi hasil baca MediaMetadataRetriever. */
    data class VideoInfo(val durationMs: Long?, val width: Int?, val height: Int?)

    /** Nama file aman untuk penyimpanan: buang karakter yang dilarang. */
    fun sanitizeFileName(raw: String): String {
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return clean.ifEmpty { "video" }.take(120)
    }

    /**
     * Nama unik: kalau [base] sudah dipakai (menurut [exists]),
     * tambah " (2)", " (3)", ... sebelum ekstensi. Tanpa ekstensi
     * pun tetap jalan ("video" -> "video (2)").
     */
    fun uniqueFileName(base: String, exists: (String) -> Boolean): String {
        if (!exists(base)) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var n = 2
        while (n <= 999) {
            val candidate = "$stem ($n)$ext"
            if (!exists(candidate)) return candidate
            n++
        }
        return "$stem-${System.currentTimeMillis()}$ext"
    }

    /** Cek apakah [name] sudah ada sebagai DISPLAY_NAME di koleksi Downloads. */
    fun displayNameExists(resolver: ContentResolver, name: String): Boolean {
        return try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(name),
                null
            )?.use { cursor -> cursor.count > 0 } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "displayNameExists: query gagal", e)
            false
        }
    }

    /** Ruang kosong (byte) di penyimpanan bersama. */
    fun freeBytes(): Long {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            stat.availableBytes
        } catch (e: Exception) {
            Log.w(TAG, "freeBytes: StatFs gagal", e)
            -1L
        }
    }

    /** Baca nama + ukuran file yang dipilih dari pemilih file sistem. */
    fun readPickedInfo(resolver: ContentResolver, uri: Uri): PickedInfo {
        return try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use PickedInfo(null, -1L)
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else -1L
                PickedInfo(name, size)
            } ?: PickedInfo(null, -1L)
        } catch (e: Exception) {
            Log.w(TAG, "readPickedInfo gagal", e)
            PickedInfo(null, -1L)
        }
    }

    sealed interface CopyResult {
        /** Salinan selesai: Uri MediaStore tujuan + byte yang tersalin. */
        data class Ok(val contentUri: Uri, val bytesCopied: Long) : CopyResult

        /** Gagal (baris MediaStore setengah jadi sudah dihapus). */
        data class Fail(val message: String) : CopyResult
    }

    /**
     * Salin [sourceUri] ke Download/UnduhKu/import/[displayName]
     * secara streaming (buffer 256 KB, tidak pernah load file utuh
     * ke RAM). [onProgress] dipanggil dari thread IO — pemanggil
     * WAJIB hop ke Main sebelum menulis state Compose.
     * Batal (job di-cancel) atau gagal -> baris MediaStore tujuan
     * dihapus, tidak ada file setengah jadi yang tertinggal.
     */
    suspend fun copyVideo(
        resolver: ContentResolver,
        sourceUri: Uri,
        displayName: String,
        sizeBytes: Long,
        onProgress: (copied: Long, total: Long) -> Unit
    ): CopyResult = withContext(Dispatchers.IO) {
        val mime = when (displayName.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> "video/mp4"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, IMPORT_FOLDER)
            }
        }
        val destUri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e(TAG, "copyVideo: insert MediaStore gagal", e)
            null
        } ?: return@withContext CopyResult.Fail(
            "Tidak bisa membuat file tujuan di Download/UnduhKu/import."
        )
        try {
            val input = resolver.openInputStream(sourceUri)
                ?: return@withContext CopyResult.Fail(
                    "Tidak bisa membaca file yang dipilih."
                )
            val output = resolver.openOutputStream(destUri)
                ?: return@withContext CopyResult.Fail(
                    "Tidak bisa menulis file tujuan."
                )
            var copied = 0L
            val buffer = ByteArray(256 * 1024)
            input.use { inputStream ->
                output.use { outputStream ->
                    while (true) {
                        if (!currentCoroutineContext().isActive) {
                            throw kotlinx.coroutines.CancellationException(
                                "Penyalinan dibatalkan."
                            )
                        }
                        val read = inputStream.read(buffer)
                        if (read <= 0) break
                        outputStream.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, sizeBytes)
                    }
                    outputStream.flush()
                }
            }
            if (copied <= 0L) {
                return@withContext CopyResult.Fail("File yang dipilih kosong.")
            }
            Log.i(TAG, "copyVideo OK: $displayName ($copied byte)")
            CopyResult.Ok(destUri, copied)
        } catch (e: kotlinx.coroutines.CancellationException) {
            try {
                resolver.delete(destUri, null, null)
            } catch (ignored: Exception) { /* abaikan */
            }
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "copyVideo: salin gagal", e)
            try {
                resolver.delete(destUri, null, null)
            } catch (ignored: Exception) { /* abaikan */
            }
            CopyResult.Fail("Gagal menyalin file: ${e.message ?: "error IO"}")
        } catch (e: Exception) {
            Log.e(TAG, "copyVideo: gagal", e)
            try {
                resolver.delete(destUri, null, null)
            } catch (ignored: Exception) { /* abaikan */
            }
            CopyResult.Fail("Gagal menyalin file: ${e.message ?: "error"}")
        }
    }

    /** Baca durasi + resolusi video dari Uri (null per field kalau gagal). */
    suspend fun readVideoInfo(context: Context, uri: Uri): VideoInfo =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()
                val width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull()
                val height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull()
                VideoInfo(duration, width, height)
            } catch (e: Exception) {
                Log.w(TAG, "readVideoInfo gagal", e)
                VideoInfo(null, null, null)
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) { /* abaikan */
                }
            }
        }
}
