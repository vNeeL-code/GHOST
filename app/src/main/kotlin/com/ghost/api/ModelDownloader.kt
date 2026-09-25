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
        
        // 0. Check cached model path from SharedPreferences first
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val cachedPath = prefs.getString(Constants.PREF_LAST_KNOWN_MODEL_PATH, null)
        if (!cachedPath.isNullOrBlank()) {
            val cachedFile = File(cachedPath)
            if (cachedFile.exists() && cachedFile.length() > 200 * 1024 * 1024L) {
                Timber.i("Model already exists at cached path: ${cachedFile.absolutePath} (${cachedFile.length()} bytes)")
                _downloadStatus.value = DownloadState.Success(cachedFile)
                return
            }
        }

        val modelsDir = context.getExternalFilesDir("models") 
            ?: context.getExternalFilesDir(null)?.let { File(it, "models") }
            ?: File(context.filesDir, "models")
        modelsDir.mkdirs()

        // 1. Check all candidate search directories for the requested model file
        val allSearchDirs = listOfNotNull(
            modelsDir,
            context.getExternalFilesDir(null),
            File("/storage/emulated/0/Android/data/${context.packageName}/files/models"),
            File("/sdcard/Android/data/${context.packageName}/files/models"),
            File(context.filesDir, "models"),
            context.filesDir,
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "models"),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        ).distinct()

        // 1A. DESTINATION OVERWRITE GUARD: Under no circumstances allow DownloadManager to enqueue
        // over an existing valid model file (>200MB), which would truncate it to 0 bytes!
        val destFile = File(modelsDir, fileName)
        val existingInDest = if (destFile.exists() && destFile.length() > 200 * 1024 * 1024L) {
            destFile
        } else {
            modelsDir.listFiles { f ->
                f.name.equals(fileName, ignoreCase = true) && f.length() > 200 * 1024 * 1024L
            }?.firstOrNull()
        }
        if (existingInDest != null) {
            Timber.i("🛡️ OVERWRITE GUARD: Valid model already exists at ${existingInDest.absolutePath} (${existingInDest.length()} bytes). Halting download and adopting.")
            prefs.edit().putString(Constants.PREF_LAST_KNOWN_MODEL_PATH, existingInDest.absolutePath).apply()
            _downloadStatus.value = DownloadState.Success(existingInDest)
            return
        }

        // 1B. Search across all candidate storage directories
        for (dir in allSearchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val candidate = File(dir, fileName)
            if (candidate.exists() && candidate.length() > 200 * 1024 * 1024L) {
                Timber.i("Model $fileName already exists in ${candidate.absolutePath} (${candidate.length()} bytes)")
                prefs.edit().putString(Constants.PREF_LAST_KNOWN_MODEL_PATH, candidate.absolutePath).apply()
                _downloadStatus.value = DownloadState.Success(candidate)
                return
            }
            val ciMatch = dir.listFiles { f ->
                f.name.equals(fileName, ignoreCase = true) && f.length() > 200 * 1024 * 1024L
            }?.firstOrNull()
            if (ciMatch != null) {
                Timber.i("Model $fileName (case-insensitive) already exists in ${ciMatch.absolutePath} (${ciMatch.length()} bytes)")
                prefs.edit().putString(Constants.PREF_LAST_KNOWN_MODEL_PATH, ciMatch.absolutePath).apply()
                _downloadStatus.value = DownloadState.Success(ciMatch)
                return
            }
        }

        // 2. Fallback check: If ANY valid model exists on disk (>200MB), adopt it instead of downloading
        val anyModel = findAnyLocalModel(context)
        if (anyModel != null) {
            Timber.i("Found local alternative model: ${anyModel.absolutePath} (${anyModel.length()} bytes). Adopting instead of redundant download.")
            _downloadStatus.value = DownloadState.Success(anyModel)
            return
        }

        // 3. Check DownloadManager for existing downloads of the same file
        val existingId = findExistingDownloadId(fileName)
        if (existingId != -1L) {
            Timber.i("Found existing download for $fileName (ID: $existingId). Attaching...")
            activeDownloadId = existingId
            observeProgress(fileName)
            return
        }

        try {
            // Save to app-specific protected dir (Android/data/com.ghost.api/files/models/)
            // Survives all app updates and APK patches without being wiped.
            // Protected from accidental user Downloads purges and requires zero storage permissions.
            Timber.i("Starting model download → models/$fileName")
            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri).apply {
                setTitle("GHOST Model: $fileName")
                setDescription("Downloading $fileName to protected storage")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, "models", fileName)
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
                                val destinationFile = File(
                                    context.getExternalFilesDir("models") ?: File(context.getExternalFilesDir(null), "models"),
                                    expectedFileName
                                )
                                _downloadStatus.value = DownloadState.Success(destinationFile)
                            }
                            DownloadManager.STATUS_FAILED -> {
                                isDownloading = false
                                activeDownloadId = -1L
                                val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                val reason = if (reasonCol != -1) cursor.getInt(reasonCol) else -1
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

    fun downloadE4B(hfToken: String? = null) {
        startDownload(Constants.MODEL_REPO_E4B, Constants.MODEL_NAME_E4B, hfToken)
    }

    fun downloadE2B(hfToken: String? = null) {
        startDownload(Constants.MODEL_REPO_E2B, Constants.MODEL_NAME_E2B, hfToken)
    }

    fun isModelDownloaded(fileName: String): Boolean {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val cachedPath = prefs.getString(Constants.PREF_LAST_KNOWN_MODEL_PATH, null)
        if (!cachedPath.isNullOrBlank()) {
            val cachedFile = File(cachedPath)
            if (cachedFile.exists() && cachedFile.length() > 200 * 1024 * 1024L && cachedFile.name.equals(fileName, ignoreCase = true)) {
                return true
            }
        }

        val allSearchDirs = listOfNotNull(
            context.getExternalFilesDir("models"),
            context.getExternalFilesDir(null)?.let { File(it, "models") },
            context.getExternalFilesDir(null),
            File("/storage/emulated/0/Android/data/${context.packageName}/files/models"),
            File("/sdcard/Android/data/${context.packageName}/files/models"),
            File(context.filesDir, "models"),
            context.filesDir,
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "models"),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        ).distinct()

        return allSearchDirs.any { dir ->
            if (!dir.exists() || !dir.isDirectory) return@any false
            val f = File(dir, fileName)
            (f.exists() && f.length() > 200 * 1024 * 1024L) ||
            dir.listFiles { file -> file.name.equals(fileName, ignoreCase = true) && file.length() > 200 * 1024 * 1024L }?.isNotEmpty() == true
        }
    }

    companion object {
        fun findAnyLocalModel(context: Context): File? {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val cachedPath = prefs.getString(Constants.PREF_LAST_KNOWN_MODEL_PATH, null)
            if (!cachedPath.isNullOrBlank()) {
                val cachedFile = File(cachedPath)
                if (cachedFile.exists() && cachedFile.length() > 200 * 1024 * 1024L) {
                    return cachedFile
                }
            }

            val allSearchDirs = listOfNotNull(
                context.getExternalFilesDir("models"),
                context.getExternalFilesDir(null)?.let { File(it, "models") },
                context.getExternalFilesDir(null),
                File("/storage/emulated/0/Android/data/${context.packageName}/files/models"),
                File("/sdcard/Android/data/${context.packageName}/files/models"),
                File(context.filesDir, "models"),
                context.filesDir,
                File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "models"),
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            ).distinct()

            // Check standard file names directly first (with case-insensitive fallback)
            val standardNames = listOf(Constants.MODEL_NAME_E4B, Constants.MODEL_NAME_E2B)
            for (name in standardNames) {
                for (dir in allSearchDirs) {
                    if (!dir.exists() || !dir.isDirectory) continue
                    val candidate = File(dir, name)
                    if (candidate.exists() && candidate.length() > 200 * 1024 * 1024L) {
                        prefs.edit().putString(Constants.PREF_LAST_KNOWN_MODEL_PATH, candidate.absolutePath).apply()
                        return candidate
                    }
                    val ciMatch = dir.listFiles { file ->
                        file.name.equals(name, ignoreCase = true) && file.length() > 200 * 1024 * 1024L
                    }?.firstOrNull()
                    if (ciMatch != null) {
                        prefs.edit().putString(Constants.PREF_LAST_KNOWN_MODEL_PATH, ciMatch.absolutePath).apply()
                        return ciMatch
                    }
                }
            }

            val found = allSearchDirs.flatMap { dir ->
                dir.listFiles { file ->
                    val name = file.name
                    (name.endsWith(".litertlm", ignoreCase = true) ||
                     name.endsWith(".gguf", ignoreCase = true) ||
                     name.endsWith(".nexa", ignoreCase = true)) &&
                    file.length() > 200 * 1024 * 1024L
                }?.toList() ?: emptyList()
            }.sortedByDescending { it.length() }.firstOrNull()

            if (found != null) {
                prefs.edit().putString(Constants.PREF_LAST_KNOWN_MODEL_PATH, found.absolutePath).apply()
            }
            return found
        }
    }
}
