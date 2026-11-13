package com.daftech.unduhku.clip

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
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
 * TIDAK ADA re-encode penuh sebagai jalur utama, TIDAK ADA FFmpegKit
 * (pensiun Jan 2025).
 *
 * FALLBACK CHAIN (fix3, 2026-11-13): export 100% gagal di HP
 * (Helio G95) saat DUA flag eksperimental dinyalakan sekaligus —
 * edit-list trim melarang transcode saat trimming, sementara trim
 * optimization justru men-transcode GOP pertama. Jadi tiap clip
 * dicoba 2 jalur berurutan:
 *  - Percobaan 1: edit-list trim ON, trim optimization OFF —
 *    transmux murni, NOL encode (bypass encoder perangkat).
 *  - Percobaan 2: KEDUA flag OFF — Transformer polos, transcode
 *    penuh (jalur paling andal, lebih lambat).
 * Alasan gagal yang dikembalikan SELALU dari percobaan TERAKHIR,
 * dan tiap kegagalan ditulis ke Log.e("UnduhKu") supaya bisa
 * didiagnosis via logcat.
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

    private const val TAG = "UnduhKu"

    /** Satu jalur percobaan export (lihat fallback chain di KDoc atas). */
    private data class Attempt(
        val editListTrim: Boolean,
        val trimOptimization: Boolean,
        val label: String
    )

    /**
     * Potong [inputUri] (content:// MediaStore atau FileProvider)
     * ke [output] (file di cache — pemanggil yang mem-publish ke
     * MediaStore). Mengembalikan Pair(ok, alasanGagalSingkat).
     * Kalau percobaan 1 gagal (termasuk gagal validasi), dicoba
     * percobaan 2; alasan yang dikembalikan dari percobaan TERAKHIR.
     */
    suspend fun exportClip(
        context: Context,
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        output: File
    ): Pair<Boolean, String?> {
        if (endMs <= startMs) return false to "rentang clip tidak valid"
        val attempts = listOf(
            Attempt(
                editListTrim = true,
                trimOptimization = false,
                label = "edit-list saja (transmux)"
            ),
            Attempt(
                editListTrim = false,
                trimOptimization = false,
                label = "polos (transcode penuh)"
            )
        )
        var lastReason: String? = "Transformer gagal"
        for ((index, attempt) in attempts.withIndex()) {
            val attemptNo = index + 1
            try {
                if (output.exists()) output.delete()
                val ok = transform(
                    context, inputUri, startMs, endMs, output,
                    attempt.editListTrim, attempt.trimOptimization
                )
                if (!ok) {
                    lastReason = "Transformer gagal " +
                        "(percobaan $attemptNo: ${attempt.label})"
                    Log.e(TAG, "Export clip ${startMs}–${endMs}ms gagal " +
                        "percobaan $attemptNo (${attempt.label}): " +
                        "transform() mengembalikan false")
                    try {
                        output.delete()
                    } catch (e: Exception) { /* abaikan */
                    }
                    continue
                }
                if (!validateClip(output, endMs - startMs)) {
                    lastReason = "output clip tidak valid " +
                        "(percobaan $attemptNo: ${attempt.label})"
                    Log.e(TAG, "Export clip ${startMs}–${endMs}ms gagal " +
                        "percobaan $attemptNo (${attempt.label}): " +
                        "output tidak lolos validasi MediaExtractor")
                    try {
                        output.delete()
                    } catch (e: Exception) { /* abaikan */
                    }
                    continue
                }
                Log.i(TAG, "Export clip ${startMs}–${endMs}ms sukses " +
                    "percobaan $attemptNo (${attempt.label}), " +
                    "${output.length()} byte")
                return true to null
            } catch (e: Exception) {
                lastReason = shortReason(e) +
                    " (percobaan $attemptNo: ${attempt.label})"
                Log.e(TAG, "Export clip ${startMs}–${endMs}ms gagal " +
                    "percobaan $attemptNo (${attempt.label})", e)
                try {
                    output.delete()
                } catch (e2: Exception) { /* abaikan */
                }
            }
        }
        return false to lastReason
    }

    private suspend fun transform(
        context: Context,
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        output: File,
        editListTrim: Boolean,
        trimOptimization: Boolean
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
                // Flag eksperimental dipasang dari parameter (fallback
                // chain fix3): percobaan 1 edit-list saja (transmux),
                // percobaan 2 keduanya OFF (transcode penuh).
                val transformer = Transformer.Builder(context)
                    .experimentalSetMp4EditListTrimEnabled(editListTrim)
                    .experimentalSetTrimOptimizationEnabled(trimOptimization)
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
