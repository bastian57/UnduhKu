package com.daftech.unduhku.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Riwayat UnduhKu (Room 2.6.1, database versi 1).
 *
 * Satu baris per download + satu baris per clip hasil export.
 * Disimpan: URL, nama file, ukuran, status, Uri/path penyimpanan,
 * MD5 harapan vs aktual, pesan error. TIDAK disimpan: isi file,
 * thumbnail clip (dibuat ulang on-demand oleh ThumbnailLoader).
 * TIDAK ADA fallbackToDestructiveMigration: DB baru versi 1, dan
 * perubahan skema berikutnya WAJIB Migration eksplisit — riwayat
 * user tidak boleh hilang saat update.
 */
@Entity(tableName = "downloads")
data class DownloadHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val totalBytes: Long,
    val downloadedBytes: Long = 0L,
    /** DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED. */
    val status: String,
    /** Uri MediaStore (content://...) atau "file:/abs/path" (fallback). */
    val storageUri: String? = null,
    val displayPath: String,
    val expectedMd5: String? = null,
    val actualMd5: String? = null,
    val errorMessage: String? = null,
    /** Epoch ms — urutan daftar. */
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "exported_clips",
    foreignKeys = [ForeignKey(
        entity = DownloadHistoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["downloadId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("downloadId")]
)
data class ExportedClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val downloadId: Long,
    val clipIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val outputPath: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface UnduhkuDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadHistoryEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getDownload(id: Long): DownloadHistoryEntity?

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY createdAt DESC")
    suspend fun getCompletedDownloads(): List<DownloadHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(entity: DownloadHistoryEntity): Long

    @Update
    suspend fun updateDownload(entity: DownloadHistoryEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownload(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun deleteAllDownloads()

    @Query("SELECT * FROM exported_clips ORDER BY createdAt DESC")
    fun getAllExports(): Flow<List<ExportedClipEntity>>

    @Query("SELECT * FROM exported_clips WHERE downloadId = :downloadId ORDER BY clipIndex ASC")
    suspend fun getExportsFor(downloadId: Long): List<ExportedClipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExport(entity: ExportedClipEntity): Long

    @Query("DELETE FROM exported_clips")
    suspend fun deleteAllExports()

    @Transaction
    suspend fun clearAll() {
        deleteAllExports()
        deleteAllDownloads()
    }
}

@Database(
    entities = [DownloadHistoryEntity::class, ExportedClipEntity::class],
    version = 1,
    exportSchema = false
)
abstract class UnduhkuDatabase : RoomDatabase() {
    abstract fun unduhkuDao(): UnduhkuDao

    companion object {
        @Volatile
        private var INSTANCE: UnduhkuDatabase? = null

        fun get(context: Context): UnduhkuDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UnduhkuDatabase::class.java,
                    "unduhku-history.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
