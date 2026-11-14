package com.daftech.unduhku

import com.daftech.unduhku.importer.VideoImporter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Logika murni pemilih video (v1.2.0): sanitasi nama file + nama
 * unik anti-tabrakan. Tanpa Android — jalan di JVM biasa.
 */
class VideoImporterTest {

    @Test
    fun sanitize_removesIllegalChars() {
        assertEquals("a_b_c.mp4", VideoImporter.sanitizeFileName("a/b:c.mp4"))
        assertEquals("a_b_c_d_e_f_g.mp4", VideoImporter.sanitizeFileName("a\\b*c?d\"e<f>g.mp4"))
    }

    @Test
    fun sanitize_emptyBecomesVideo() {
        assertEquals("video", VideoImporter.sanitizeFileName(""))
        assertEquals("video", VideoImporter.sanitizeFileName("   "))
    }

    @Test
    fun sanitize_trimsWhitespace() {
        assertEquals("clip.mp4", VideoImporter.sanitizeFileName("  clip.mp4  "))
    }

    @Test
    fun sanitize_keepsExtensionAndSpaces() {
        // Spasi BUKAN karakter terlarang — dipertahankan.
        assertEquals("film 2024.mkv", VideoImporter.sanitizeFileName("film 2024.mkv"))
    }

    @Test
    fun unique_freeReturnsBase() {
        assertEquals("a.mp4", VideoImporter.uniqueFileName("a.mp4") { false })
    }

    @Test
    fun unique_takenAppends2() {
        assertEquals(
            "a (2).mp4",
            VideoImporter.uniqueFileName("a.mp4") { it == "a.mp4" }
        )
    }

    @Test
    fun unique_takenTwiceAppends3() {
        assertEquals(
            "a (3).mp4",
            VideoImporter.uniqueFileName("a.mp4") {
                it == "a.mp4" || it == "a (2).mp4"
            }
        )
    }

    @Test
    fun unique_noExtension() {
        assertEquals("video (2)", VideoImporter.uniqueFileName("video") { it == "video" })
    }

    @Test
    fun supportedExtensions_areClipSources() {
        assertEquals(setOf("mp4", "mkv", "webm"), VideoImporter.SUPPORTED_EXTENSIONS)
    }

    @Test
    fun importFolder_isUnderDownload() {
        assertEquals("Download/UnduhKu/import", VideoImporter.IMPORT_FOLDER)
    }
}
