package com.daftech.unduhku.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Download multi-chunk (padanan aria2c -x 8 di Termux).
 *
 * ALUR:
 *  1. Bagi file jadi 8 chunk (LinkUtils.splitRanges — deterministik,
 *     supaya resume lintas restart menghitung batas yang sama).
 *  2. Tiap chunk diunduh paralel dengan header Range ke file part
 *     di cacheDir/unduhku-parts/<dir>/part-N. Part yang sudah
 *     lengkap tidak diunduh ulang (resume per-chunk).
 *  3. Setelah semua chunk lengkap, publish: salin part berurutan
 *     LANGSUNG ke MediaStore Downloads/UnduhKu (tanpa file gabung
 *     di cache — hemat 1x ukuran file), MD5 dihitung sambil menyalin.
 *  4. Fallback kalau MediaStore gagal: gabung ke file di
 *     getExternalFilesDir(Downloads)/UnduhKu (pola ClipKu).
 *
 * KEBUTUHAN RUANG (jujur, tampilkan ke user sebelum download):
 * part (1x ukuran) + file final (1x ukuran) = ~2x ukuran file.
 * Download dibatalkan sebelum mulai kalau ruang kosong < 2x + 100 MB.
 *
 * JEDA / BATAL: flag AtomicBoolean dicek tiap buffer 64 KB. Jeda
 * TIDAK membatalkan coroutine — worker parkir di delay(200) sampai
 * resume. Batal = CancellationException + part dihapus.
 *
 * CATATAN OkHttp: Dispatcher default maxRequestsPerHost=5 — 8 chunk
 * paralel ke host yang sama akan antre jadi 5 kalau tidak dinaikkan.
 * Di sini dinaikkan ke 16 (CHUNK_COUNT + cadangan).
 */
class MultiChunkDownloader(private val context: Context) {

    data class Request(
        val url: String,
        val fileName: String,
        val totalBytes: Long,
        val expectedMd5: String?,
        val supportsRanges: Boolean
    )

    data class Outcome(
        val ok: Boolean,
        val storageUri: String? = null,
        val displayPath: String? = null,
        val actualMd5: String? = null,
        val reason: String? = null
    )

    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val downloaded = AtomicLong(0L)
    private val lastReportMs = AtomicLong(0L)

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
    }

    fun cancel() {
        cancelled.set(true)
    }

    suspend fun download(
        request: Request,
        onProgress: (downloadedBytes: Long) -> Unit
    ): Outcome = withContext(Dispatchers.IO) {
        downloaded.set(0L)
        lastReportMs.set(0L)
        if (request.totalBytes > 0L && !hasFreeSpace(request.totalBytes)) {
            return@withContext Outcome(
                ok = false,
                reason = "Ruang kosong kurang (butuh ~${LinkUtils.formatBytes(request.totalBytes * 2)} " +
                    "untuk part + file final)."
            )
        }
        val chunked = request.supportsRanges && request.totalBytes > 0L
        try {
            runDownload(request, chunked, onProgress)
        } catch (e: RangeIgnoredException) {
            // Server mengaku dukung Range tapi mengembalikan 200 penuh —
            // hapus part, ulangi SEKALI sebagai single-stream.
            deleteParts(request)
            downloaded.set(0L)
            runDownload(request, false, onProgress)
        } catch (e: CancellationException) {
            deleteParts(request)
            Outcome(ok = false, reason = "dibatalkan")
        } catch (e: Exception) {
            // Gagal network / HTTP: part DIBIARKAN supaya bisa resume.
            Outcome(ok = false, reason = shortReason(e) + " — bisa dilanjutkan")
        }
    }

    private suspend fun runDownload(
        request: Request,
        chunked: Boolean,
        onProgress: (Long) -> Unit
    ): Outcome {
        val partsDir = partsDir(request)
        partsDir.mkdirs()
        val ranges = if (chunked) {
            LinkUtils.splitRanges(request.totalBytes, CHUNK_COUNT)
        } else {
            listOf(0L to (if (request.totalBytes > 0L) request.totalBytes - 1L else -1L))
        }
        coroutineScope {
            ranges.mapIndexed { index, range ->
                async(Dispatchers.IO) {
                    downloadChunk(request.url, range, File(partsDir, "part-$index"), chunked, onProgress)
                }
            }.forEach { it.await() }
        }
        if (cancelled.get()) throw CancellationException("dibatalkan")
        // Validasi: tiap part harus sepanjang chunk-nya (single-stream:
        // part-0 boleh berapa pun selama > 0 — total dicek saat publish).
        if (chunked) {
            ranges.forEachIndexed { index, (start, end) ->
                val part = File(partsDir, "part-$index")
                if (!part.exists() || part.length() != end - start + 1L) {
                    throw IOException("chunk ${index + 1} tidak lengkap")
                }
            }
        } else {
            val part = File(partsDir, "part-0")
            if (!part.exists() || part.length() <= 0L) {
                throw IOException("0 byte terunduh")
            }
        }
        return publish(request, partsDir, ranges.size, onProgress)
    }

    private suspend fun downloadChunk(
        url: String,
        range: Pair<Long, Long>,
        partFile: File,
        useRange: Boolean,
        onProgress: (Long) -> Unit
    ) {
        val (rangeStart, rangeEnd) = range
        val existing = if (partFile.exists()) partFile.length() else 0L
        if (useRange) {
            if (existing > rangeEnd - rangeStart + 1L) {
                // Part basi (lebih panjang dari chunk) — mulai dari nol.
                partFile.delete()
            } else if (existing == rangeEnd - rangeStart + 1L) {
                downloaded.addAndGet(existing)
                reportProgress(onProgress)
                return
            }
        } else if (existing > 0L) {
            // Single-stream: part yang ada dipakai sebagai resume
            // hanya kalau server mendukungnya (di sini tidak — mulai ulang).
            partFile.delete()
        }
        val start = rangeStart + if (partFile.exists()) partFile.length() else 0L
        if (useRange && start > rangeEnd) return
        // Nama lengkap okhttp3.Request — WAJIB: data class Request
        // bersarang di bawah menutupi import okhttp3.Request di
        // dalam scope class ini, jadi Request.Builder() saja
        // akan dicari di model itu (tidak punya Builder).
        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
        if (useRange) builder.header("Range", "bytes=$start-$rangeEnd")
        val response = http.newCall(builder.build()).execute()
        response.use { resp ->
            if (useRange && resp.code == 200) {
                // Server mengabaikan Range (mengembalikan file penuh).
                throw RangeIgnoredException()
            }
            if (!resp.isSuccessful && resp.code != 206) {
                throw IOException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IOException("body kosong")
            FileOutputStream(partFile, true).use { out ->
                val buffer = ByteArray(64 * 1024)
                val stream = body.byteStream()
                while (true) {
                    if (cancelled.get()) throw CancellationException("dibatalkan")
                    while (paused.get()) {
                        delay(200)
                        if (cancelled.get()) throw CancellationException("dibatalkan")
                    }
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    reportProgress(onProgress, downloaded.addAndGet(read.toLong()))
                }
            }
        }
    }

    /** Publish: salin part berurutan ke tujuan final + hitung MD5 sambil menyalin. */
    private fun publish(
        request: Request,
        partsDir: File,
        partCount: Int,
        onProgress: (Long) -> Unit
    ): Outcome {
        val fileName = uniqueFileName(request.fileName)
        val mime = mimeFor(request.fileName)
        // Jalur utama: MediaStore Downloads/UnduhKu (API 29+, tanpa izin).
        val mediaResult = publishToMediaStore(request, partsDir, partCount, fileName, mime)
        if (mediaResult != null) return mediaResult
        // Fallback: folder file eksternal app (selalu bisa ditulis).
        return publishToAppFiles(request, partsDir, partCount, fileName)
    }

    private fun publishToMediaStore(
        request: Request,
        partsDir: File,
        partCount: Int,
        fileName: String,
        mime: String
    ): Outcome? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/UnduhKu")
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            val digest = MessageDigest.getInstance("MD5")
            var bytesWritten = 0L
            try {
                val out = context.contentResolver.openOutputStream(uri)
                    ?: throw IOException("tidak bisa membuka tujuan MediaStore")
                out.use { o ->
                    val buffer = ByteArray(64 * 1024)
                    for (i in 0 until partCount) {
                        File(partsDir, "part-$i").inputStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                o.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                bytesWritten += read
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e2: Exception) { /* abaikan */
                }
                return null // jatuh ke fallback file app
            }
            if (request.totalBytes > 0L && bytesWritten != request.totalBytes) {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e: Exception) { /* abaikan */
                }
                return Outcome(ok = false, reason = "ukuran hasil (${bytesWritten} byte) != ukuran server (${request.totalBytes} byte)")
            }
            val actualMd5 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!request.expectedMd5.isNullOrBlank() &&
                !Checksum.matches(request.expectedMd5, actualMd5)
            ) {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e: Exception) { /* abaikan */
                }
                deleteParts(request)
                return Outcome(
                    ok = false,
                    reason = "MD5 tidak cocok (file korup saat download) — part dihapus, unduh ulang"
                )
            }
            deleteParts(request)
            Outcome(
                ok = true,
                storageUri = uri.toString(),
                displayPath = "Download/UnduhKu/$fileName",
                actualMd5 = actualMd5
            )
        } catch (e: Exception) {
            null // jatuh ke fallback file app
        }
    }

    private fun publishToAppFiles(
        request: Request,
        partsDir: File,
        partCount: Int,
        fileName: String
    ): Outcome {
        return try {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "UnduhKu"
            ).apply { mkdirs() }
            val target = File(dir, fileName)
            val digest = MessageDigest.getInstance("MD5")
            var bytesWritten = 0L
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                for (i in 0 until partCount) {
                    File(partsDir, "part-$i").inputStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytesWritten += read
                        }
                    }
                }
            }
            if (request.totalBytes > 0L && bytesWritten != request.totalBytes) {
                try {
                    target.delete()
                } catch (e: Exception) { /* abaikan */
                }
                return Outcome(ok = false, reason = "ukuran hasil != ukuran server")
            }
            val actualMd5 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!request.expectedMd5.isNullOrBlank() &&
                !Checksum.matches(request.expectedMd5, actualMd5)
            ) {
                try {
                    target.delete()
                } catch (e: Exception) { /* abaikan */
                }
                deleteParts(request)
                return Outcome(
                    ok = false,
                    reason = "MD5 tidak cocok (file korup saat download) — part dihapus, unduh ulang"
                )
            }
            deleteParts(request)
            Outcome(
                ok = true,
                storageUri = "file:${target.absolutePath}",
                displayPath = target.absolutePath,
                actualMd5 = actualMd5
            )
        } catch (e: Exception) {
            Outcome(ok = false, reason = shortReason(e))
        }
    }

    /** Nama unik di Downloads: kalau sudah ada, tambah suffix _2, _3, … */
    private fun uniqueFileName(fileName: String): String {
        if (!mediaStoreNameExists(fileName)) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var n = 2
        while (n <= 99) {
            val candidate = "${base}_$n$ext"
            if (!mediaStoreNameExists(candidate)) return candidate
            n++
        }
        return "${base}_${System.currentTimeMillis()}$ext"
    }

    private fun mediaStoreNameExists(name: String): Boolean {
        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(name),
                null
            )
            cursor?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun mimeFor(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    private fun hasFreeSpace(totalBytes: Long): Boolean {
        return try {
            val stat = StatFs(context.cacheDir.path)
            val free = stat.availableBytes
            free >= totalBytes * 2L + 100L * 1024L * 1024L
        } catch (e: Exception) {
            true // tidak bisa dicek — jangan blokir download karenanya
        }
    }

    /** Direktori part: deterministik dari URL+nama+ukuran (resume lintas restart). */
    private fun partsDir(request: Request): File {
        val key = Checksum.md5Hex(request.url.toByteArray(Charsets.UTF_8)).take(8)
        val safeName = LinkUtils.sanitizeFileName(request.fileName).take(40)
        return File(File(context.cacheDir, "unduhku-parts"), "${safeName}-${request.totalBytes}-$key")
    }

    private fun deleteParts(request: Request) {
        try {
            partsDir(request).deleteRecursively()
        } catch (e: Exception) { /* abaikan */
        }
    }

    private fun reportProgress(onProgress: (Long) -> Unit, total: Long = downloaded.get()) {
        val now = System.currentTimeMillis()
        val last = lastReportMs.get()
        if (now - last >= 250L || total == downloaded.get()) {
            if (lastReportMs.compareAndSet(last, now)) {
                try {
                    onProgress(total)
                } catch (e: Exception) { /* callback UI tidak boleh membunuh download */
                }
            }
        }
    }

    private fun shortReason(e: Exception): String {
        if (e is java.net.UnknownHostException) return "host gagal resolve (DNS)"
        if (e is java.net.SocketTimeoutException) return "timeout"
        return (e.message ?: e.javaClass.simpleName).take(100)
    }

    private class RangeIgnoredException : IOException("server mengabaikan Range")

    companion object {
        const val CHUNK_COUNT = 8
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

        private val http: OkHttpClient = OkHttpClient.Builder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 16
                    // WAJIB: default maxRequestsPerHost=5 akan mencekik
                    // 8 chunk paralel ke host yang sama jadi 5.
                    maxRequestsPerHost = 16
                }
            )
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
