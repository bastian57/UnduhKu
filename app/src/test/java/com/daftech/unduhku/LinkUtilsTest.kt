package com.daftech.unduhku

import com.daftech.unduhku.download.LinkUtils
import com.daftech.unduhku.model.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkUtilsTest {

    @Test
    fun detectDirectFile() {
        val result = LinkUtils.detect("https://example.com/video.mp4")
        assertEquals(LinkKind.DIRECT_FILE, result.kind)
        assertNull(result.archiveId)
    }

    @Test
    fun detectArchiveItem() {
        val result = LinkUtils.detect("https://archive.org/details/dbph-1")
        assertEquals(LinkKind.ARCHIVE_ITEM, result.kind)
        assertEquals("dbph-1", result.archiveId)
    }

    @Test
    fun detectArchiveItemTrailingSlashAndQuery() {
        val result = LinkUtils.detect("https://archive.org/details/dbph-1/?view=theater")
        assertEquals(LinkKind.ARCHIVE_ITEM, result.kind)
        assertEquals("dbph-1", result.archiveId)
    }

    @Test
    fun detectBlockedHosts() {
        assertEquals(
            LinkKind.BLOCKED,
            LinkUtils.detect("https://www.youtube.com/watch?v=abc").kind
        )
        assertEquals(
            LinkKind.BLOCKED,
            LinkUtils.detect("https://youtu.be/abc").kind
        )
        assertEquals(
            LinkKind.BLOCKED,
            LinkUtils.detect("https://www.tiktok.com/@x/video/1").kind
        )
        assertEquals(
            LinkKind.BLOCKED,
            LinkUtils.detect("https://www.instagram.com/reel/abc/").kind
        )
    }

    @Test
    fun detectUnknown() {
        assertEquals(LinkKind.UNKNOWN, LinkUtils.detect("bukan-url").kind)
        assertEquals(LinkKind.UNKNOWN, LinkUtils.detect("").kind)
    }

    @Test
    fun fileNameFromUrlDecodes() {
        assertEquals(
            "video saya.mp4",
            LinkUtils.fileNameFromUrl("https://example.com/a%20b/video%20saya.mp4")
        )
    }

    @Test
    fun fileNameFromContentDisposition() {
        assertEquals(
            "film.mkv",
            LinkUtils.fileNameFromContentDisposition("attachment; filename=\"film.mkv\"")
        )
        assertEquals(
            "video saya.mp4",
            LinkUtils.fileNameFromContentDisposition(
                "attachment; filename*=UTF-8''video%20saya.mp4"
            )
        )
        assertNull(LinkUtils.fileNameFromContentDisposition(null))
    }

    @Test
    fun sanitizeFileName() {
        assertEquals("a_b_c.mp4", LinkUtils.sanitizeFileName("a/b:c.mp4"))
        assertEquals("unduhan", LinkUtils.sanitizeFileName("   "))
    }

    @Test
    fun formatBytes() {
        assertEquals("0 B", LinkUtils.formatBytes(0))
        assertEquals("512 B", LinkUtils.formatBytes(512))
        assertEquals("1.0 KB", LinkUtils.formatBytes(1024))
        assertEquals("256.1 MB", LinkUtils.formatBytes(268549871))
        assertEquals("4.5 GB", LinkUtils.formatBytes(4849240599))
    }

    @Test
    fun formatDurationMs() {
        assertEquals("0:00", LinkUtils.formatDurationMs(0))
        assertEquals("0:10", LinkUtils.formatDurationMs(10_000))
        assertEquals("46:59", LinkUtils.formatDurationMs(2_819_240))
    }

    @Test
    fun parseArchiveFilesFiltersSidecars() {
        val json = """
            {"files": [
              {"name": "_DBPH_~1.mp4", "size": "268549871", "format": "h.264", "md5": "b7ebeb003962fe9caad44c2c6d4b95fd"},
              {"name": "_DBPH_~1.MKV", "size": "4849240599", "format": "Matroska", "md5": "1c350ed2d80b4854128c968f234c35fe"},
              {"name": "dbph-1_files.xml", "size": "1234", "format": "Metadata"},
              {"name": "dbph-1_meta.sqlite", "size": "81920", "format": "Metadata"},
              {"name": "dbph-1_archive.torrent", "size": "27458", "format": "Archive BitTorrent"},
              {"name": "dbph-1.thumbs/_DBPH_~1_000001.jpg", "size": "41396", "format": "Thumbnail"},
              {"name": "tanpa-ukuran.mp4", "format": "h.264"}
            ]}
        """.trimIndent()
        val files = LinkUtils.parseArchiveFiles(json)
        assertEquals(2, files.size)
        // Diurutkan ukuran menurun: MKV dulu.
        assertEquals("_DBPH_~1.MKV", files[0].name)
        assertEquals(4849240599L, files[0].sizeBytes)
        assertEquals("1c350ed2d80b4854128c968f234c35fe", files[0].md5)
        assertEquals("_DBPH_~1.mp4", files[1].name)
    }

    @Test
    fun parseArchiveFilesBrokenJsonIsEmpty() {
        assertTrue(LinkUtils.parseArchiveFiles("bukan json").isEmpty())
        assertTrue(LinkUtils.parseArchiveFiles("{}").isEmpty())
    }

    @Test
    fun archiveDownloadUrlEncodesTilde() {
        assertEquals(
            "https://archive.org/download/dbph-1/_DBPH_%7E1.mp4",
            LinkUtils.archiveDownloadUrl("dbph-1", "_DBPH_~1.mp4")
        )
    }

    @Test
    fun splitRangesIsContiguousAndDeterministic() {
        val ranges = LinkUtils.splitRanges(100L, 8)
        assertEquals(8, ranges.size)
        // 100 = 4x13 + 4x12 (sisa dibagi ke chunk awal).
        assertEquals(0L to 12L, ranges[0])
        assertEquals(13L to 25L, ranges[1])
        assertEquals(88L to 99L, ranges[7])
        var cursor = 0L
        for ((start, end) in ranges) {
            assertEquals(cursor, start)
            assertTrue(end >= start)
            cursor = end + 1
        }
        assertEquals(100L, cursor)
        // Deterministik: panggil dua kali, hasil sama persis.
        assertEquals(ranges, LinkUtils.splitRanges(100L, 8))
    }

    @Test
    fun splitRangesSmallFileFewerChunks() {
        val ranges = LinkUtils.splitRanges(3L, 8)
        assertEquals(3, ranges.size)
        assertEquals(0L to 0L, ranges[0])
        assertEquals(2L to 2L, ranges[2])
    }
}
