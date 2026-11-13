package com.daftech.unduhku.clip

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thumbnail + durasi video dari Uri (MediaStore / FileProvider).
 * Semua kerja retriever jalan di Dispatchers.IO; Bitmap dikembalikan
 * ke pemanggil yang menaruhnya ke Compose state DI MAIN (ViewModel
 * mem-post hasilnya lewat Main — jangan tulis state dari sini).
 * Cache memori max 32 thumbnail (satu layar LazyColumn) — thumbnail
 * TIDAK disimpan ke disk di v1, dibuat ulang saat layar dibuka lagi.
 */
object ThumbnailLoader {

    private val cache = LruCache<String, Bitmap>(32)

    /** Durasi video (ms) via METADATA_KEY_DURATION; null kalau tidak terbaca. */
    suspend fun videoDurationMs(context: Context, uri: Uri): Long? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()
            } catch (e: Exception) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) { /* abaikan */
                }
            }
        }

    /** Frame di [timeMs] (keyframe terdekat); null kalau gagal. */
    suspend fun thumbnail(
        context: Context,
        uri: Uri,
        timeMs: Long,
        cacheKey: String
    ): Bitmap? {
        cache.get(cacheKey)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            } catch (e: Exception) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) { /* abaikan */
                }
            }
        }
        if (bitmap != null) cache.put(cacheKey, bitmap)
        return bitmap
    }

    fun clearCache() {
        cache.evictAll()
    }
}
