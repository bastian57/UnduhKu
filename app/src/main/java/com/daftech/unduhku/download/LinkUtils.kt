package com.daftech.unduhku.download

import com.daftech.unduhku.model.ArchiveFile
import com.daftech.unduhku.model.DetectedLink
import com.daftech.unduhku.model.LinkKind
import java.net.URLDecoder
import org.json.JSONObject

/**
 * Logika MURNI deteksi + parsing link (tanpa Android, tanpa network).
 * Semua fungsi di sini bisa di-unit-test di JVM (lihat LinkUtilsTest).
 *
 * ATURAN SCOPE (TERKUNCI):
 *  - YouTube / TikTok / Instagram = BLOCKED, bukan fitur yang
 *    belum jadi. Jangan pernah menambahkan host itu ke daftar dukung.
 *  - archive.org/details/<id> = ARCHIVE_ITEM, daftar file diambil
 *    dari https://archive.org/metadata/<id> (LinkResolver).
 *  - Selain itu = DIRECT_FILE, diverifikasi lewat HEAD request
 *    (LinkResolver): nama dari Content-Disposition / path URL,
 *    ukuran dari Content-Length, tolak content-type text/html.
 */
object LinkUtils {

    private val BLOCKED_HOSTS = listOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
        "tiktok.com", "www.tiktok.com", "vt.tiktok.com",
        "instagram.com", "www.instagram.com"
    )

    /** File metadata archive.org yang TIDAK ditampilkan ke user. */
    private val ARCHIVE_SIDECARS = listOf(
        "_files.xml", "_meta.xml", "_meta.sqlite", ".torrent", "__ia_thumb.jpg"
    )

    fun hostOf(rawUrl: String): String {
        return try {
            java.net.URL(rawUrl).host.lowercase()
        } catch (e: Exception) {
            ""
        }
    }

    fun isBlockedHost(rawUrl: String): Boolean {
        val host = hostOf(rawUrl)
        return BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** Ekstrak <id> dari https://archive.org/details/<id> (toleran trailing slash/query). */
    fun extractArchiveId(rawUrl: String): String? {
        return try {
            val url = java.net.URL(rawUrl)
            if (!url.host.lowercase().endsWith("archive.org")) return null
            val path = url.path.trim().trimEnd('/')
            val prefix = "/details/"
            if (!path.startsWith(prefix)) return null
            val id = path.removePrefix(prefix).trim()
            if (id.isEmpty() || id.contains("/")) null else id
        } catch (e: Exception) {
            null
        }
    }

    fun detect(rawUrl: String): DetectedLink {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return DetectedLink(LinkKind.UNKNOWN)
        if (isBlockedHost(trimmed)) return DetectedLink(LinkKind.BLOCKED)
        val archiveId = extractArchiveId(trimmed)
        if (archiveId != null) return DetectedLink(LinkKind.ARCHIVE_ITEM, archiveId)
        return try {
            java.net.URL(trimmed)
            DetectedLink(LinkKind.DIRECT_FILE)
        } catch (e: Exception) {
            DetectedLink(LinkKind.UNKNOWN)
        }
    }

    /** Nama file dari segmen terakhir path URL (URL-decoded). */
    fun fileNameFromUrl(rawUrl: String): String {
        return try {
            val path = java.net.URL(rawUrl).path
            val last = path.substringAfterLast('/').trim()
            if (last.isEmpty()) "unduhan" else URLDecoder.decode(last, "UTF-8")
        } catch (e: Exception) {
            "unduhan"
        }
    }

    /** Nama file dari header Content-Disposition (...filename="x.mp4" / filename*=UTF-8''x.mp4). */
    fun fileNameFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.getOrNull(1)
        if (!encoded.isNullOrBlank()) {
            return try {
                URLDecoder.decode(encoded.trim(), "UTF-8")
            } catch (e: Exception) {
                null
            }
        }
        val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.getOrNull(1)?.trim()
        return plain?.takeIf { it.isNotEmpty() }
    }

    /** Nama file aman untuk storage: buang karakter yang dilarang di nama file. */
    fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return clean.ifEmpty { "unduhan" }.take(120)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return "%.1f %s".format(value, units[unit])
    }

    fun formatDurationMs(ms: Long): String {
        if (ms < 0) return "?:??"
        val totalSec = ms / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%d:%02d".format(minutes, seconds)
    }

    fun formatSpeed(bytesPerSec: Double): String {
        if (bytesPerSec <= 0.0) return "0 B/dtk"
        return formatBytes(bytesPerSec.toLong()) + "/dtk"
    }

    fun formatEtaMs(remainingMs: Long): String {
        if (remainingMs < 0) return "--"
        val sec = remainingMs / 1000
        if (sec < 60) return "${sec} dtk"
        return "%d mnt %02d dtk".format(sec / 60, sec % 60)
    }

    /**
     * Parse JSON metadata archive.org -> daftar file yang bisa diunduh.
     * Disaring: tanpa ukuran (size null/0) dibuang; sidecar metadata
     * (_files.xml, _meta.xml, _meta.sqlite, .torrent, __ia_thumb.jpg)
     * dan folder thumbnail (.thumbs/) dibuang — user memilih file
     * media/dokumen, bukan file internal archive.org.
     * Diurutkan ukuran menurun (file utama biasanya paling besar).
     * JSON rusak = list kosong, bukan crash.
     */
    fun parseArchiveFiles(json: String): List<ArchiveFile> {
        return try {
            val root = JSONObject(json)
            val files = root.optJSONArray("files") ?: return emptyList()
            val out = ArrayList<ArchiveFile>(files.length())
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                val name = item.optString("name", "").trim()
                if (name.isEmpty()) continue
                if (ARCHIVE_SIDECARS.any { name.endsWith(it) }) continue
                if (name.contains(".thumbs/")) continue
                val size = try {
                    item.optString("size", "").toLong()
                } catch (e: Exception) {
                    -1L
                }
                if (size <= 0L) continue
                out.add(
                    ArchiveFile(
                        name = name,
                        sizeBytes = size,
                        format = item.optString("format", ""),
                        md5 = item.optString("md5", "").takeIf { it.isNotBlank() }
                    )
                )
            }
            out.sortedByDescending { it.sizeBytes }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** URL download file archive.org (nama file di-encode, "~" jadi %7E). */
    fun archiveDownloadUrl(archiveId: String, fileName: String): String {
        val encoded = fileName.split("/").joinToString("/") { segment ->
            try {
                java.net.URLEncoder.encode(segment, "UTF-8")
                    .replace("+", "%20")
            } catch (e: Exception) {
                segment
            }
        }
        return "https://archive.org/download/$archiveId/$encoded"
    }

    /**
     * Bagi [totalBytes] jadi [chunkCount] rentang byte [start, end]
     * inklusif, sedatar mungkin (sisa byte dibagi ke chunk awal).
     * Dipakai downloader + unit test — pembagian HARUS deterministik
     * supaya resume lintas restart menghitung batas yang sama.
     */
    fun splitRanges(totalBytes: Long, chunkCount: Int): List<Pair<Long, Long>> {
        require(totalBytes > 0L) { "totalBytes harus > 0" }
        require(chunkCount > 0) { "chunkCount harus > 0" }
        val count = minOf(chunkCount, totalBytes.toInt().coerceAtLeast(1))
        val base = totalBytes / count
        val remainder = (totalBytes % count).toInt()
        val out = ArrayList<Pair<Long, Long>>(count)
        var start = 0L
        for (i in 0 until count) {
            val size = base + if (i < remainder) 1L else 0L
            out.add(start to (start + size - 1L))
            start += size
        }
        return out
    }
}
