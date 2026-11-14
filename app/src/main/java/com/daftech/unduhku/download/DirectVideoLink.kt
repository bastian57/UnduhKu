package com.daftech.unduhku.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Tautan video langsung (v1.3.0) — kolom khusus di tab Unduh untuk
 * URL yang menunjuk LANGSUNG ke satu file video (.mp4/.mkv/.webm),
 * mis. dari Wikimedia Commons.
 *
 * BEDA dengan [LinkResolver] umum: resolver ini HANYA menerima
 * file video. Content-Type diperiksa lewat HEAD sebelum download:
 * terima kalau diawali "video/" (video/mp4, video/x-matroska,
 * video/webm, …); terima juga application/octet-stream HANYA kalau
 * path URL final berakhiran .mp4/.mkv/.webm. Selain itu ditolak
 * dengan pesan ramah — tautan halaman web, dokumen, atau arsip
 * bukan file video langsung.
 *
 * Alur download-nya SAMA dengan tautan lain: hasil sukses adalah
 * [LinkResolver.Result.Direct] yang masuk ke kartu Download yang
 * sudah ada, lalu [MultiChunkDownloader] (8 chunk, resume, jeda)
 * yang mengunduhnya ke Download/UnduhKu. Tabrakan nama file
 * ditangani downloader itu sendiri (suffix _2, _3, …) saat publish.
 *
 * Pesan error TIDAK PERNAH memuat URL lengkap (hanya tipe konten) —
 * URL bisa memuat token / path privat.
 */
object DirectVideoLink {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    /**
     * Client HTTP dibuat LAZY: logika murni (validasi URL,
     * Content-Type, nama file) tidak boleh membangun OkHttpClient —
     * selain hemat thread pool, unit test logika murni juga jalan
     * tanpa client. Dibangun saat resolve() pertama kali dipakai.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Validasi client: null = OK, selain itu pesan error ramah. */
    fun validateUrl(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return "Tempel tautan video dulu."
        return try {
            when (java.net.URL(url).protocol.lowercase()) {
                "http", "https" -> null
                else -> "Tautan harus diawali http:// atau https://."
            }
        } catch (e: Exception) {
            "Tautan tidak valid — harus diawali http:// atau https://."
        }
    }

    /**
     * Aturan Content-Type (v1.3.0): terima kalau diawali "video/";
     * terima juga application/octet-stream HANYA kalau path URL final
     * berakhiran .mp4/.mkv/.webm. Selain itu tolak (termasuk
     * text/html, application/pdf, application/zip, dan tanpa header).
     */
    fun isVideoContentType(contentType: String?, finalUrl: String): Boolean {
        val ct = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (ct.startsWith("video/")) return true
        if (ct == "application/octet-stream") {
            val path = try {
                java.net.URL(finalUrl).path.lowercase()
            } catch (e: Exception) {
                ""
            }
            return path.endsWith(".mp4") ||
                path.endsWith(".mkv") ||
                path.endsWith(".webm")
        }
        return false
    }

    /** Nama file: Content-Disposition dulu, lalu segmen terakhir URL final. */
    fun fileNameFor(contentDisposition: String?, finalUrl: String): String {
        val name = LinkUtils.fileNameFromContentDisposition(contentDisposition)
            ?: LinkUtils.fileNameFromUrl(finalUrl)
        return LinkUtils.sanitizeFileName(name)
    }

    /**
     * Periksa tautan video langsung. Mengembalikan
     * [LinkResolver.Result.Direct] kalau server menyajikan file video,
     * atau [LinkResolver.Result.Error] kalau bukan (bukan URL http(s),
     * bukan video, atau server tidak memberi info file).
     */
    suspend fun resolve(rawUrl: String): LinkResolver.Result =
        withContext(Dispatchers.IO) {
            val invalid = validateUrl(rawUrl)
            if (invalid != null) return@withContext LinkResolver.Result.Error(invalid)
            val url = rawUrl.trim()
            // Percobaan 1: HEAD (murah, tanpa body).
            val head = headProbe(url)
            if (head != null) return@withContext head
            // Percobaan 2: server menolak HEAD (405/501) — probe
            // Range 0-0 lewat GET; total dibaca dari Content-Range.
            rangeProbe(url)
                ?: LinkResolver.Result.Error("Server tidak memberi info file (HEAD + Range gagal).")
        }

    private fun headProbe(url: String): LinkResolver.Result? {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", BROWSER_UA)
                .build()
            http.newCall(request).execute().use { response ->
                if (response.code == 405 || response.code == 501) return null
                if (!response.isSuccessful) {
                    return LinkResolver.Result.Error("Server menjawab HTTP ${response.code}.")
                }
                buildDirectFromHeaders(response)
            }
        } catch (e: Exception) {
            null // jatuh ke rangeProbe; alasan asli dicatat di sana kalau ikut gagal
        }
    }

    private fun rangeProbe(url: String): LinkResolver.Result? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Range", "bytes=0-0")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    return LinkResolver.Result.Error("Server menjawab HTTP ${response.code}.")
                }
                val finalUrl = response.request.url.toString()
                val contentType = response.header("Content-Type").orEmpty()
                if (!isVideoContentType(contentType, finalUrl)) {
                    return LinkResolver.Result.Error(notVideoMessage(contentType))
                }
                val contentRange = response.header("Content-Range") // "bytes 0-0/12345"
                val total = contentRange
                    ?.substringAfterLast('/', "")
                    ?.toLongOrNull() ?: -1L
                val name = fileNameFor(
                    response.header("Content-Disposition"),
                    finalUrl
                )
                LinkResolver.Result.Direct(
                    url = finalUrl,
                    fileName = name,
                    sizeBytes = total,
                    supportsRanges = response.code == 206
                )
            }
        } catch (e: Exception) {
            LinkResolver.Result.Error("Gagal memeriksa tautan (${shortReason(e)}).")
        }
    }

    private fun buildDirectFromHeaders(
        response: okhttp3.Response
    ): LinkResolver.Result {
        // OkHttp mengikuti redirect secara default — request.url
        // adalah URL FINAL, dan nama file diambil dari situ.
        val finalUrl = response.request.url.toString()
        val contentType = response.header("Content-Type").orEmpty()
        if (!isVideoContentType(contentType, finalUrl)) {
            return LinkResolver.Result.Error(notVideoMessage(contentType))
        }
        val name = fileNameFor(
            response.header("Content-Disposition"),
            finalUrl
        )
        val size = response.header("Content-Length")?.toLongOrNull() ?: -1L
        val supportsRanges = response.header("Accept-Ranges")
            ?.equals("bytes", ignoreCase = true) == true
        return LinkResolver.Result.Direct(
            url = finalUrl,
            fileName = name,
            sizeBytes = size,
            supportsRanges = supportsRanges
        )
    }

    private fun notVideoMessage(contentType: String): String {
        val label = contentType
            .substringBefore(';')
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: "tipe tak dikenal"
        return "Tautan ini bukan file video langsung — server " +
            "mengembalikan $label. Tempel tautan yang menunjuk " +
            "langsung ke file .mp4, .mkv, atau .webm."
    }

    private fun shortReason(e: Exception): String {
        if (e is java.net.UnknownHostException) return "host gagal resolve (DNS)"
        if (e is java.net.SocketTimeoutException) return "timeout"
        return (e.message ?: e.javaClass.simpleName).take(80)
    }
}
