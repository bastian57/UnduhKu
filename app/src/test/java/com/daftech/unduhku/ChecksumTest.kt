package com.daftech.unduhku

import com.daftech.unduhku.download.Checksum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumTest {

    @Test
    fun md5KnownVector() {
        // MD5("abc") — vektor standar.
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            Checksum.md5Hex("abc".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun matchesIgnoresCase() {
        assertTrue(Checksum.matches("ABCDEF", "abcdef"))
        assertTrue(
            Checksum.matches(
                "b7ebeb003962fe9caad44c2c6d4b95fd",
                "B7EBEB003962FE9CAAD44C2C6D4B95FD"
            )
        )
    }

    @Test
    fun matchesRejectsNullAndMismatch() {
        assertFalse(Checksum.matches(null, "abc"))
        assertFalse(Checksum.matches("abc", null))
        assertFalse(Checksum.matches("abc", "abd"))
    }
}
