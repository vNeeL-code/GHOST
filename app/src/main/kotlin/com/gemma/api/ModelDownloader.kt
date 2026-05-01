package com.gemma.api

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
class ModelDownloader(private val context: Context) {

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
     * 
     * @param hfRepo e.g., "google/gemma-3n-litert"
     * @param fileName e.g., "gemma-3n-it.litertlm"
     * @param hfToken Optional HuggingFace Access Token for gated models
     */
    fun startDownload(hfRepo: String, fileName: String, hfToken: String? = null) {
        if (activeDownloadId != -1L) {
            _downloadStatus.value = DownloadState.Error("A download is already in progress.")
            return
        }

        try {
            val url = "https://huggingface.co/$hfRepo/resolve/main/$fileName?download=true"
            Timber.i("Starting model download from: $url")

            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri).apply {
                setTitle("Gemma AI Model")
                setDescription("Downloading $fileName securely")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)

                // Inject HuggingFace Auth Token if provided
                if (!hfToken.isNullOrBlank()) {
                    addRequestHeader("Authorization", "Bearer $hfToken")
                }
            }

            activeDownloadId = downloadManager.enqueue(request)
            _downloadStatus.value = DownloadState.Downloading(0, 0L, 0L)
            
            // Kick off progress observer
            observeProgress(fileName)

        } catch (e: Exception) {
            Timber.e(e, "Download setup failed")
            _downloadStatus.value = DownloadState.Error(e.message ?: "Failed to start download")
        }
    }

    private fun observeProgress(expectedFileName: String) {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
                                val destinationFile = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), expectedFileName)
                                _downloadStatus.value = DownloadState.Success(destinationFile)
                            }
                            DownloadManager.STATUS_FAILED -> {
                                isDownloading = false
                                activeDownloadId = -1L
                                val reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
                                _downloadStatus.value = DownloadState.Error("Download failed with code: $reason")
                            }
                        }
                    }
                    cursor.close()
                } else {
                    cursor?.close()
                    // Download might have been manually cancelled from the notification bar
                    isDownloading = false
                    activeDownloadId = -1L
                    _downloadStatus.value = DownloadState.Error("Download cancelled or interrupted")
                }
                kotlinx.coroutines.delay(1000) // Poll every second
            }
        }
    }

    /**
     * Cancel the active download if one is running.
     */
    fun cancelDownload() {
        if (activeDownloadId != -1L) {
            downloadManager.remove(activeDownloadId)
            activeDownloadId = -1L
            _downloadStatus.value = DownloadState.Error("Download manually cancelled")
        }
    }
}
