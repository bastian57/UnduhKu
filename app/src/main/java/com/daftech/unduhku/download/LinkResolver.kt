package com.daftech.unduhku.download

import com.daftech.unduhku.model.ArchiveFile
import com.daftech.unduhku.model.LinkKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Deteksi link (network) — dipanggil ViewModel saat user menekan
 * "Periksa Link". TIDAK mengunduh isi file, hanya:
 *  - DIRECT_FILE: HEAD request -> nama + ukuran + dukung Range?
 *  - ARCHIVE_ITEM: GET https://archive.org/metadata/<id> -> daftar file.
 *  - BLOCKED / UNKNOWN: pesan penolakan jujur, tanpa network lebih lanjut.
 *
 * Pesan error TIDAK PERNAH memuat URL lengkap (hanya host) — URL
 * bisa memuat token / path privat.
 */
object LinkResolver {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        data class Direct(
            val url: String,
            val fileName: String,
            val sizeBytes: Long,
            val supportsRanges: Boolean
        ) : Result()

        data class Archive(val id: String, val files: List<ArchiveFile>) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun resolve(rawUrl: String): Result = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        when (LinkUtils.detect(url).kind) {
            LinkKind.BLOCKED -> Result.Error(
                "Link YouTube / TikTok / Instagram tidak didukung UnduhKu. " +
                    "Tempel link file langsung atau halaman archive.org."
            )
            LinkKind.UNKNOWN -> Result.Error(
                "Link tidak dikenali. Tempel URL file langsung " +
                    "(mis. https://…/video.mp4) atau halaman archive.org/details/…"
            )
            LinkKind.ARCHIVE_ITEM -> resolveArchive(url)
            LinkKind.DIRECT_FILE -> resolveDirect(url)
        }
    }

    private fun resolveArchive(url: String): Result {
        val id = LinkUtils.extractArchiveId(url)
            ?: return Result.Error("ID item archive.org tidak bisa dibaca dari link.")
        return try {
            val request = Request.Builder()
                .url("https://archive.org/metadata/$id")
                .header("User-Agent", BROWSER_UA)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.Error("archive.org menjawab HTTP ${response.code}.")
                }
                val body = response.body?.string().orEmpty()
                val files = LinkUtils.parseArchiveFiles(body)
                if (files.isEmpty()) {
                    Result.Error("Item archive.org \"$id\" tidak berisi file yang bisa diunduh.")
                } else {
                    Result.Archive(id, files)
                }
            }
        } catch (e: Exception) {
            Result.Error("Gagal membaca metadata archive.org (${shortReason(e)}).")
        }
    }

    private fun resolveDirect(url: String): Result {
        // Percobaan 1: HEAD (murah, tanpa body).
        val head = headProbe(url)
        if (head != null) return head
        // Percobaan 2: server menolak HEAD (405/501/403) — probe
        // Range 0-0 lewat GET; total dibaca dari Content-Range.
        return rangeProbe(url)
            ?: Result.Error("Server tidak memberi info file (HEAD + Range gagal).")
    }

    private fun headProbe(url: String): Result? {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", BROWSER_UA)
                .build()
            http.newCall(request).execute().use { response ->
                if (response.code == 405 || response.code == 501) return null
                if (!response.isSuccessful) {
                    return Result.Error("Server menjawab HTTP ${response.code}.")
                }
                buildDirectFromHeaders(url, response)
            }
        } catch (e: Exception) {
            null // jatuh ke rangeProbe; alasan asli dicatat di sana kalau ikut gagal
        }
    }

    private fun rangeProbe(url: String): Result? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Range", "bytes=0-0")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    return Result.Error("Server menjawab HTTP ${response.code}.")
                }
                val contentRange = response.header("Content-Range") // "bytes 0-0/12345"
                val total = contentRange
                    ?.substringAfterLast('/', "")
                    ?.toLongOrNull() ?: -1L
                val name = LinkUtils.fileNameFromContentDisposition(
                    response.header("Content-Disposition")
                ) ?: LinkUtils.fileNameFromUrl(url)
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.startsWith("text/html", ignoreCase = true)) {
                    return Result.Error(
                        "Link ini mengembalikan halaman web, bukan file. " +
                            "Tempel link file langsung."
                    )
                }
                Result.Direct(
                    url = url,
                    fileName = LinkUtils.sanitizeFileName(name),
                    sizeBytes = total,
                    supportsRanges = response.code == 206
                )
            }
        } catch (e: Exception) {
            Result.Error("Gagal memeriksa link (${shortReason(e)}).")
        }
    }

    private fun buildDirectFromHeaders(
        url: String,
        response: okhttp3.Response
    ): Result {
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.startsWith("text/html", ignoreCase = true)) {
            return Result.Error(
                "Link ini mengembalikan halaman web, bukan file. " +
                    "Tempel link file langsung."
            )
        }
        val name = LinkUtils.fileNameFromContentDisposition(
            response.header("Content-Disposition")
        ) ?: LinkUtils.fileNameFromUrl(url)
        val size = response.header("Content-Length")?.toLongOrNull() ?: -1L
        val supportsRanges = response.header("Accept-Ranges")
            ?.equals("bytes", ignoreCase = true) == true
        return Result.Direct(
            url = url,
            fileName = LinkUtils.sanitizeFileName(name),
            sizeBytes = size,
            supportsRanges = supportsRanges
        )
    }

    private fun shortReason(e: Exception): String {
        if (e is java.net.UnknownHostException) return "host gagal resolve (DNS)"
        if (e is java.net.SocketTimeoutException) return "timeout"
        return (e.message ?: e.javaClass.simpleName).take(80)
    }
}
