package com.daftech.unduhku.history

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Repository riwayat — jembatan tipis di atas Room.
 * Semua operasi suspend (dipanggil dari Dispatchers.IO / viewModelScope).
 */
class HistoryRepository(context: Context) {
    private val dao = UnduhkuDatabase.get(context).unduhkuDao()

    fun observeAllDownloads(): Flow<List<DownloadHistoryEntity>> = dao.getAllDownloads()

    fun observeAllExports(): Flow<List<ExportedClipEntity>> = dao.getAllExports()

    suspend fun getDownload(id: Long): DownloadHistoryEntity? = dao.getDownload(id)

    suspend fun getCompletedDownloads(): List<DownloadHistoryEntity> =
        dao.getCompletedDownloads()

    suspend fun insertDownload(entity: DownloadHistoryEntity): Long =
        dao.insertDownload(entity)

    suspend fun updateDownload(entity: DownloadHistoryEntity) = dao.updateDownload(entity)

    suspend fun insertExport(entity: ExportedClipEntity): Long = dao.insertExport(entity)

    suspend fun getExportsFor(downloadId: Long): List<ExportedClipEntity> =
        dao.getExportsFor(downloadId)

    suspend fun deleteDownload(id: Long) = dao.deleteDownload(id)

    suspend fun deleteAll() = dao.clearAll()
}
