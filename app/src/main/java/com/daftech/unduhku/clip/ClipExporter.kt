package com.daftech.unduhku.clip

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Export SATU clip virtual jadi file MP4 (Media3 Transformer 1.9.0).
 *
 * TIDAK ADA re-encode penuh, TIDAK ADA FFmpegKit (pensiun Jan 2025).
 * Transformer dikonfigurasi:
 *  - experimentalSetMp4EditListTrimEnabled(true): trim-only pakai
 *    MP4 edit list — sample lama dipakai ulang, tanpa transcode penuh.
 *  - experimentalSetTrimOptimizationEnabled(true): hanya GOP di awal
 *    potongan yang di-decode/re-encode, sisanya stream-copy.
 * Kalau format input-output sudah cocok, Transformer otomatis transmux
 * (menyalin sample terkompresi tanpa modifikasi). Batas potong mengikuti
 * keyframe terdekat (snap ±2 detik) — itu batas yang diterima scope,
 * bukan bug; UI tidak boleh mengklaim potongan akurat-frame.
 *
 * THREAD (pelajaran ClipKu v1.1.0): Transformer menangkap
 * Looper.myLooper() saat konstruksi dan menolak akses dari thread lain.
 * SEMUA akses (konstruksi, start, cancel, callback) jalan di SATU
 * HandlerThread khusus ("UnduhkuTransformer") yang di-quit tepat sekali.
 * Output TIDAK PERNAH dikembalikan tanpa validasi: file dicek dengan
 * MediaExtractor (track video ada + durasi 0.4x-1.6x yang diminta);
 * kalau gagal / tidak valid, file output dihapus dan hasilnya false.
 */
object ClipExporter {

    /**
     * Potong [inputUri] (content:// MediaStore atau FileProvider)
     * ke [output] (file di cache — pemanggil yang mem-publish ke
     * MediaStore). Mengembalikan Pair(ok, alasanGagalSingkat).
     */
    suspend fun exportClip(
        context: Context,
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        output: File
    ): Pair<Boolean, String?> {
        if (endMs <= startMs) return false to "rentang clip tidak valid"
        return try {
            val ok = transform(context, inputUri, startMs, endMs, output)
            if (!ok) {
                try {
                    output.delete()
                } catch (e: Exception) { /* abaikan */
                }
                return false to "Transformer gagal"
            }
            if (!validateClip(output, endMs - startMs)) {
                try {
                    output.delete()
                } catch (e: Exception) { /* abaikan */
                }
                false to "output clip tidak valid"
            } else {
                true to null
            }
        } catch (e: Exception) {
            try {
                output.delete()
            } catch (e2: Exception) { /* abaikan */
            }
            false to shortReason(e)
        }
    }

    private suspend fun transform(
        context: Context,
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        output: File
    ): Boolean = suspendCancellableCoroutine { continuation ->
        if (output.exists()) output.delete()
        val thread = HandlerThread("UnduhkuTransformer").apply { start() }
        val handler = Handler(thread.looper)
        val transformerRef = AtomicReference<Transformer?>(null)
        val finished = AtomicBoolean(false)
        fun finish(ok: Boolean, error: Throwable?) {
            if (!finished.compareAndSet(false, true)) return
            try {
                thread.quitSafely()
            } catch (e: Exception) { /* abaikan */
            }
            if (!continuation.isActive) return
            if (error != null) continuation.resumeWithException(error)
            else continuation.resume(ok)
        }
        handler.post {
            try {
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
                val item = MediaItem.Builder()
                    .setUri(inputUri)
                    .setClippingConfiguration(clipping)
                    .build()
                // media3 1.9.0: ctor EditedMediaItemSequence(...) private —
                // pakai factory withAudioAndVideoFrom (pola ClipKu fix2).
                val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(
                    listOf(EditedMediaItem.Builder(item).build())
                )
                val composition = Composition.Builder(listOf(sequence)).build()
                val transformer = Transformer.Builder(context)
                    .experimentalSetMp4EditListTrimEnabled(true)
                    .experimentalSetTrimOptimizationEnabled(true)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            finish(true, null)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            finish(false, exportException)
                        }
                    })
                    .build()
                transformerRef.set(transformer)
                transformer.start(composition, output.absolutePath)
            } catch (e: Exception) {
                finish(false, e)
            }
        }
        continuation.invokeOnCancellation {
            handler.post {
                try {
                    transformerRef.get()?.cancel()
                } catch (e: Exception) { /* abaikan */
                }
            }
            handler.post { finish(false, null) }
        }
    }

    /** Validasi output: file ada + berisi, track video ada, durasi masuk akal. */
    fun validateClip(file: File, expectedDurationMs: Long): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        if (expectedDurationMs <= 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            var durationUs = -1L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) hasVideo = true
                if (durationUs < 0) {
                    try {
                        val d = format.getLong(MediaFormat.KEY_DURATION)
                        if (d > 0) durationUs = d
                    } catch (e: Exception) { /* format tanpa durasi */
                    }
                }
            }
            if (!hasVideo) return false
            if (durationUs > 0) {
                val durationMs = durationUs / 1000L
                val minMs = (expectedDurationMs * 0.4).toLong()
                val maxMs = (expectedDurationMs * 1.6).toLong()
                if (durationMs < minMs || durationMs > maxMs) return false
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) { /* abaikan */
            }
        }
    }

    private fun shortReason(e: Exception): String {
        val raw = (e.message ?: "").trim()
            .replace(Regex("https?://\\S+"), "<url>")
            .replace(Regex("\\s+"), " ")
            .take(120)
        return if (raw.isBlank()) e.javaClass.simpleName else raw
    }
}
