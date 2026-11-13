package com.daftech.unduhku.prompt

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.inspector.FrameExtractor
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ekstraksi keyframe untuk jalur Groq (HANYA jalur itu — Gemini
 * menerima file video langsung, tidak butuh frame).
 *
 * Aturan sampling (riset, pola PromptLab): 3 frame di 5% / 50% / 95%
 * durasi — hindari frame hitam di 0.0 dtk dan 10.0 dtk. Untuk clip
 * 10 detik: 0,5 / 5,0 / 9,5 detik.
 *
 * Jalur utama: Media3 1.9.0 FrameExtractor (media3-inspector).
 * Fallback: MediaMetadataRetriever.getScaledFrameAtTime (pola
 * ThumbnailLoader yang sudah terbukti di app ini).
 * Output: JPEG ~960px sisi terpanjang, kualitas 80 — sekitar
 * 150–350 KB per frame, total ~1 MB per request Groq.
 */
object ClipFrameExtractor {

    private const val TAG = "UnduhKu"

    /** Waktu sampling (ms) untuk durasi tertentu — logika MURNI (unit-test). */
    fun sampleTimesMs(durationMs: Long): List<Long> {
        if (durationMs <= 0L) return emptyList()
        val t1 = (durationMs * 5 / 100).coerceAtLeast(100L)
        val t2 = durationMs / 2
        val t3 = (durationMs * 95 / 100).coerceAtLeast(t2 + 100L)
        return listOf(t1, t2, t3).distinct().sorted()
    }

    /**
     * Ekstrak 3 keyframe sebagai JPEG byte array. Mengembalikan list
     * yang BISA lebih pendek dari 3 kalau ada frame yang gagal —
     * pemanggil (Groq) tetap jalan dengan frame yang ada, minimal 1.
     * List kosong = ekstraksi gagal total.
     */
    suspend fun extractKeyframes(
        context: Context,
        uri: Uri,
        durationMs: Long
    ): List<ByteArray> = withContext(Dispatchers.IO) {
        val times = sampleTimesMs(durationMs)
        if (times.isEmpty()) return@withContext emptyList()
        val viaFrameExtractor = extractViaFrameExtractor(context, uri, times)
        if (viaFrameExtractor.isNotEmpty()) return@withContext viaFrameExtractor
        Log.i(TAG, "ClipFrameExtractor: FrameExtractor kosong, " +
            "fallback ke MediaMetadataRetriever")
        extractViaRetriever(context, uri, times)
    }

    private fun extractViaFrameExtractor(
        context: Context,
        uri: Uri,
        timesMs: List<Long>
    ): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        try {
            FrameExtractor.Builder(context, MediaItem.fromUri(uri)).build().use { fe ->
                for (t in timesMs) {
                    try {
                        val frame = fe.getFrame(t * 1000L)
                            .get(10, TimeUnit.SECONDS)
                        frame?.bitmap?.let { bmp ->
                            toJpeg(bmp, 960)?.let { out += it }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "ClipFrameExtractor: getFrame $t ms gagal", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ClipFrameExtractor: FrameExtractor gagal", e)
            return emptyList()
        }
        return out
    }

    private fun extractViaRetriever(
        context: Context,
        uri: Uri,
        timesMs: List<Long>
    ): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            for (t in timesMs) {
                try {
                    // getScaledFrameAtTime butuh API 27+ (minSdk app 26) —
                    // di API 26 pakai getFrameAtTime biasa, toJpeg yang
                    // mengecilkan ke 960px.
                    val bmp = if (android.os.Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(
                            t * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            960,
                            540
                        )
                    } else {
                        retriever.getFrameAtTime(
                            t * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }
                    bmp?.let { toJpeg(it, 960)?.let { bytes -> out += bytes } }
                } catch (e: Exception) {
                    Log.w(TAG, "ClipFrameExtractor: retriever $t ms gagal", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ClipFrameExtractor: retriever gagal", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) { /* abaikan */
            }
        }
        return out
    }

    /** Bitmap → JPEG (sisi terpanjang [maxSide] px, kualitas 80). */
    private fun toJpeg(bitmap: Bitmap, maxSide: Int): ByteArray? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val scaled = if (maxOf(w, h) > maxSide) {
                val scale = maxSide.toFloat() / maxOf(w, h)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            ByteArrayOutputStream().use { baos ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)) {
                    return null
                }
                baos.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ClipFrameExtractor: encode JPEG gagal", e)
            null
        }
    }
}
