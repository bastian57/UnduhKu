package com.daftech.unduhku.model

/** Jenis link yang dikenali UnduhKu (scope v1, TERKUNCI). */
enum class LinkKind { DIRECT_FILE, ARCHIVE_ITEM, BLOCKED, UNKNOWN }

/** Satu file dari metadata archive.org, siap dipilih user. */
data class ArchiveFile(
    val name: String,
    val sizeBytes: Long,
    val format: String,
    /** MD5 dari metadata archive.org; null kalau tidak disediakan. */
    val md5: String?
)

/** Hasil deteksi link (tanpa network di fungsi murni — lihat LinkResolver). */
data class DetectedLink(val kind: LinkKind, val archiveId: String? = null)

/** Satu clip VIRTUAL — DATA saja, bukan file video (scope v1). */
data class VirtualClip(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val isSelected: Boolean = false,
    /** Path hasil export, null kalau belum diekspor. */
    val exportedPath: String? = null,
    val isExporting: Boolean = false
) {
    val durationMs: Long get() = endMs - startMs
}

/** Satu clip hasil export (riwayat). */
data class ExportedClipItem(
    val id: Long = 0,
    val downloadId: Long,
    val clipIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val outputPath: String,
    val createdAt: Long = System.currentTimeMillis()
)
