package com.daftech.unduhku

import com.daftech.unduhku.download.DirectVideoLink
import com.daftech.unduhku.download.LinkResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectVideoLinkTest {

    // ---------- validateUrl ----------

    @Test
    fun validateEmpty() {
        assertEquals(
            "Tempel tautan video dulu.",
            DirectVideoLink.validateUrl("")
        )
    }

    @Test
    fun validateBlank() {
        assertEquals(
            "Tempel tautan video dulu.",
            DirectVideoLink.validateUrl("   ")
        )
    }

    @Test
    fun validateFtpRejected() {
        assertEquals(
            "Tautan harus diawali http:// atau https://.",
            DirectVideoLink.validateUrl("ftp://example.com/v.mp4")
        )
    }

    @Test
    fun validateGarbage() {
        assertEquals(
            "Tautan tidak valid — harus diawali http:// atau https://.",
            DirectVideoLink.validateUrl("bukan tautan")
        )
    }

    @Test
    fun validateHttpOk() {
        assertNull(DirectVideoLink.validateUrl("http://example.com/v.mp4"))
    }

    @Test
    fun validateHttpsOk() {
        assertNull(DirectVideoLink.validateUrl("https://example.com/v.mp4"))
    }

    @Test
    fun validateUpperSchemeOk() {
        assertNull(DirectVideoLink.validateUrl("HTTPS://example.com/v.mp4"))
    }

    @Test
    fun validateTrimsWhitespace() {
        assertNull(DirectVideoLink.validateUrl("  https://example.com/v.mp4  "))
    }

    // ---------- isVideoContentType ----------

    @Test
    fun ctMp4Accepted() {
        assertTrue(DirectVideoLink.isVideoContentType("video/mp4", "https://h/v.mp4"))
    }

    @Test
    fun ctMatroskaAccepted() {
        assertTrue(
            DirectVideoLink.isVideoContentType("video/x-matroska", "https://h/v.mkv")
        )
    }

    @Test
    fun ctWebmAccepted() {
        assertTrue(DirectVideoLink.isVideoContentType("video/webm", "https://h/v.webm"))
    }

    @Test
    fun ctWithParamsAccepted() {
        assertTrue(
            DirectVideoLink.isVideoContentType(
                "video/mp4; charset=binary",
                "https://h/v.mp4"
            )
        )
    }

    @Test
    fun ctUpperCaseAccepted() {
        assertTrue(DirectVideoLink.isVideoContentType("VIDEO/MP4", "https://h/v.mp4"))
    }

    @Test
    fun ctOtherVideoAccepted() {
        assertTrue(
            DirectVideoLink.isVideoContentType("video/quicktime", "https://h/v.mov")
        )
    }

    @Test
    fun ctHtmlRejected() {
        assertFalse(
            DirectVideoLink.isVideoContentType(
                "text/html; charset=utf-8",
                "https://h/v.mp4"
            )
        )
    }

    @Test
    fun ctPdfRejected() {
        assertFalse(
            DirectVideoLink.isVideoContentType("application/pdf", "https://h/v.mp4")
        )
    }

    @Test
    fun ctZipRejected() {
        assertFalse(
            DirectVideoLink.isVideoContentType("application/zip", "https://h/v.mp4")
        )
    }

    @Test
    fun ctOctetStreamMp4Accepted() {
        assertTrue(
            DirectVideoLink.isVideoContentType(
                "application/octet-stream",
                "https://h/v.mp4"
            )
        )
    }

    @Test
    fun ctOctetStreamMkvAccepted() {
        assertTrue(
            DirectVideoLink.isVideoContentType(
                "application/octet-stream",
                "https://h/v.mkv"
            )
        )
    }

    @Test
    fun ctOctetStreamWebmAccepted() {
        assertTrue(
            DirectVideoLink.isVideoContentType(
                "application/octet-stream",
                "https://h/v.webm"
            )
        )
    }

    @Test
    fun ctOctetStreamZipRejected() {
        assertFalse(
            DirectVideoLink.isVideoContentType(
                "application/octet-stream",
                "https://h/f.zip"
            )
        )
    }

    @Test
    fun ctOctetStreamNoExtRejected() {
        assertFalse(
            DirectVideoLink.isVideoContentType(
                "application/octet-stream",
                "https://h/download"
            )
        )
    }

    @Test
    fun ctNullRejected() {
        assertFalse(DirectVideoLink.isVideoContentType(null, "https://h/v.mp4"))
    }

    @Test
    fun ctEmptyRejected() {
        assertFalse(DirectVideoLink.isVideoContentType("", "https://h/v.mp4"))
    }

    @Test
    fun ctVideoWinsOverZipPath() {
        assertTrue(DirectVideoLink.isVideoContentType("video/mp4", "https://h/f.zip"))
    }

    // ---------- fileNameFor ----------

    @Test
    fun fileNameFromDisposition() {
        assertEquals(
            "clip.mp4",
            DirectVideoLink.fileNameFor(
                "attachment; filename=\"clip.mp4\"",
                "https://h/other.mp4"
            )
        )
    }

    @Test
    fun fileNameFromDispositionEncoded() {
        assertEquals(
            "video keren.mp4",
            DirectVideoLink.fileNameFor(
                "attachment; filename*=UTF-8''video%20keren.mp4",
                "https://h/x.mp4"
            )
        )
    }

    @Test
    fun fileNameFromUrl() {
        assertEquals(
            "v deo.mp4",
            DirectVideoLink.fileNameFor(null, "https://h/x/v%20deo.mp4")
        )
    }

    @Test
    fun fileNameEmptyPath() {
        assertEquals("unduhan", DirectVideoLink.fileNameFor(null, "https://h/"))
    }

    @Test
    fun fileNameSanitized() {
        assertEquals(
            "a_b_c.mp4",
            DirectVideoLink.fileNameFor(
                "attachment; filename=\"a/b:c.mp4\"",
                "https://h/x.mp4"
            )
        )
    }

    // ---------- resolve() tanpa network ----------

    @Test
    fun resolveInvalidUrlIsError() = runBlocking {
        val result = DirectVideoLink.resolve("bukan tautan")
        assertTrue(result is LinkResolver.Result.Error)
    }

    @Test
    fun resolveFtpIsError() = runBlocking {
        val result = DirectVideoLink.resolve("ftp://example.com/v.mp4")
        assertTrue(result is LinkResolver.Result.Error)
    }
}
