package com.ghost.api

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import timber.log.Timber
import java.io.File

/**
 * Automates downloading of Gemma .litertlm models from HuggingFace.
 * Replaces manual drag-and-drop workflow.
 */
class ModelDownloader(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    
    // UI can observe this state for progress bars
    private val _downloadStatus = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadStatus: StateFlow<DownloadState> = _downloadStatus.asStateFlow()

    private var activeDownloadId: Long = -1L

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
        data class Success(val file: File) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    /**
     * Start downloading a model from a HuggingFace repository.
     */
    fun startDownload(hfRepo: String, fileName: String, hfToken: String? = null) {
        // 1. Check internal state first
        if (activeDownloadId != -1L) {
            _downloadStatus.value = DownloadState.Error("A download is already in progress.")
            return
        }

        val url = "https://huggingface.co/$hfRepo/resolve/main/$fileName?download=true"
        
        // 2. Check DownloadManager for existing downloads of the same file
        val existingId = findExistingDownloadId(fileName)
        if (existingId != -1L) {
            Timber.i("Found existing download for $fileName (ID: $existingId). Attaching...")
            activeDownloadId = existingId
            observeProgress(fileName)
            return
        }

        try {
            // Save to stable public dir — survives APK reinstalls/patches.
            // App-private external dirs (getExternalFilesDir) are wiped on reinstall.
            Timber.i("Starting model download → Downloads/$fileName")
            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri).apply {
                setTitle("GHOST Model: $fileName")
                setDescription("Downloading $fileName")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)

                if (!hfToken.isNullOrBlank()) {
                    addRequestHeader("Authorization", "Bearer $hfToken")
                }
            }

            activeDownloadId = downloadManager.enqueue(request)
            _downloadStatus.value = DownloadState.Downloading(0, 0L, 0L)
            
            observeProgress(fileName)
        } catch (e: Exception) {
            Timber.e(e, "Download setup failed")
            _downloadStatus.value = DownloadState.Error(e.message ?: "Failed to start download")
        }
    }

    private fun findExistingDownloadId(fileName: String): Long {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or 
            DownloadManager.STATUS_RUNNING or 
            DownloadManager.STATUS_PAUSED
        )
        val cursor = downloadManager.query(query)
        if (cursor != null) {
            while (cursor.moveToNext()) {
                val titleColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                if (titleColumn != -1) {
                    val title = cursor.getString(titleColumn)
                    if (title != null && title.contains(fileName)) {
                        val idColumn = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                        val id = cursor.getLong(idColumn)
                        cursor.close()
                        return id
                    }
                }
            }
            cursor.close()
        }
        return -1L
    }

    private fun observeProgress(expectedFileName: String) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var isDownloading = true
            while (isDownloading && activeDownloadId != -1L) {
                val query = DownloadManager.Query().setFilterById(activeDownloadId)
                val cursor = downloadManager.query(query)
                
                if (cursor != null && cursor.moveToFirst()) {
                    val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val downloadedBytesColumn = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalBytesColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                    if (statusColumn != -1 && downloadedBytesColumn != -1 && totalBytesColumn != -1) {
                        val status = cursor.getInt(statusColumn)
                        val bytesDownloaded = cursor.getLong(downloadedBytesColumn)
                        val bytesTotal = cursor.getLong(totalBytesColumn)

                        when (status) {
                            DownloadManager.STATUS_RUNNING -> {
                                val progress = if (bytesTotal > 0) ((bytesDownloaded * 100) / bytesTotal).toInt() else 0
                                _downloadStatus.value = DownloadState.Downloading(progress, bytesDownloaded, bytesTotal)
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                isDownloading = false
                                activeDownloadId = -1L
                                val destinationFile = java.io.File(
                                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                                    expectedFileName
                                )
                                _downloadStatus.value = DownloadState.Success(destinationFile)
                            }
                            DownloadManager.STATUS_FAILED -> {
                                isDownloading = false
                                activeDownloadId = -1L
                                val reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
                                _downloadStatus.value = DownloadState.Error("Download failed with code: $reason")
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                _downloadStatus.value = DownloadState.Downloading(0, bytesDownloaded, bytesTotal)
                            }
                        }
                    }
                    cursor.close()
                } else {
                    cursor?.close()
                    isDownloading = false
                    activeDownloadId = -1L
                    _downloadStatus.value = DownloadState.Error("Download cancelled or interrupted")
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun cancelDownload() {
        if (activeDownloadId != -1L) {
            downloadManager.remove(activeDownloadId)
            activeDownloadId = -1L
            _downloadStatus.value = DownloadState.Error("Download manually cancelled")
        }
    }
}
