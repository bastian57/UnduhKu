package com.daftech.unduhku.download

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * MD5 — dipakai verifikasi download (metadata archive.org
 * menyediakan MD5 per file; kalau tidak ada, verifikasi dilewati
 * dan hanya ukuran yang dicek). Fungsi murni, bisa di-unit-test.
 */
object Checksum {

    fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Hitung MD5 sambil membaca stream — dipakai saat publish ke
     * MediaStore supaya file besar tidak dibaca dua kali.
     */
    fun md5Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun md5Hex(file: File): String {
        file.inputStream().use { return md5Hex(it) }
    }

    /** Bandingkan MD5 tanpa peduli huruf besar/kecil; null = tidak bisa dibandingkan. */
    fun matches(expected: String?, actual: String?): Boolean {
        if (expected.isNullOrBlank() || actual.isNullOrBlank()) return false
        return expected.equals(actual, ignoreCase = true)
    }
}
