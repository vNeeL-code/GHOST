package com.ghost.api

import android.app.DownloadManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Automates downloading of Gemma .litertlm models from HuggingFace using in-app coroutines.
 * Completely eliminates Android's system DownloadManager to prevent external truncation or deletion.
 *
 * Downloads stream to an isolated `.tmp` file and only atomically rename upon 100% completion & validation.
 */
class ModelDownloader(
    private val context: Context,
    private val scope: CoroutineScope
) {
    // UI can observe this state for progress bars
    private val _downloadStatus = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadStatus: StateFlow<DownloadState> = _downloadStatus.asStateFlow()

    private var downloadJob: Job? = null

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
        data class Success(val file: File) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    init {
        // Clean up any stale legacy DownloadManager tasks from previous app versions
        // so system DownloadProvider never touches our directories.
        purgeLegacyDownloadManagerTasks()
    }

    private fun purgeLegacyDownloadManagerTasks() {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
            val query = DownloadManager.Query()
            val cursor = dm.query(query)
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                    val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                    if (idCol != -1 && titleCol != -1) {
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol) ?: ""
                        if (title.contains("GHOST", ignoreCase = true) || title.contains("gemma", ignoreCase = true)) {
                            dm.remove(id)
                            Timber.i("🧹 Purged legacy system DownloadManager task #$id ($title)")
                        }
                    }
                }
                cursor.close()
            }
        } catch (e: Exception) {
            Timber.d("Legacy DownloadManager purge ignored: ${e.message}")
        }
    }

    /**
     * Start downloading a model from a HuggingFace repository directly via coroutines.
     * Writes to a `.tmp` file to guarantee that live or existing weights are NEVER overwritten or truncated.
     */
    fun startDownload(hfRepo: String, fileName: String, hfToken: String? = null) {
        if (downloadJob?.isActive == true) {
            _downloadStatus.value = DownloadState.Error("A download is already in progress.")
            return
        }

        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        // 0. Check cached model path from SharedPreferences first
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

        // 1A. DESTINATION RETENTION GUARD: Under no circumstances download over an existing valid model file (>200MB)
        val destFile = File(modelsDir, fileName)
        val existingInDest = if (destFile.exists() && destFile.length() > 200 * 1024 * 1024L) {
            destFile
        } else {
            modelsDir.listFiles { f ->
                f.name.equals(fileName, ignoreCase = true) && f.length() > 200 * 1024 * 1024L
            }?.firstOrNull()
        }
        if (existingInDest != null) {
            Timber.i("🛡️ OVERWRITE GUARD: Valid model already exists at ${existingInDest.absolutePath} (${existingInDest.length()} bytes). Adopting.")
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

        // 2. Initiate in-app coroutine download writing to .tmp
        val tmpFile = File(modelsDir, "$fileName.tmp")
        val initialUrl = "https://huggingface.co/$hfRepo/resolve/main/$fileName?download=true"

        Timber.i("Starting direct streaming download for $fileName -> ${tmpFile.name}")
        _downloadStatus.value = DownloadState.Downloading(0, 0L, 0L)

        downloadJob = scope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                var currentUrl = initialUrl
                var redirectCount = 0

                // Resolve redirects (HuggingFace -> CDN Cloudfront/S3)
                while (redirectCount < 10) {
                    val urlObj = URL(currentUrl)
                    connection = (urlObj.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 30000
                        readTimeout = 60000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "GHOST-Android-Agent/1.0")
                        if (!hfToken.isNullOrBlank() && currentUrl.contains("huggingface.co")) {
                            setRequestProperty("Authorization", "Bearer $hfToken")
                        }
                        // Resume support if partial .tmp exists
                        if (tmpFile.exists() && tmpFile.length() > 0) {
                            setRequestProperty("Range", "bytes=${tmpFile.length()}-")
                        }
                    }
                    connection.connect()

                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == 307 || code == 308) {
                        val location = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (location.isNullOrBlank()) {
                            throw IOException("Redirect received with empty Location header")
                        }
                        currentUrl = if (location.startsWith("http")) location else URL(urlObj, location).toString()
                        redirectCount++
                        continue
                    }
                    break
                }

                val conn = connection ?: throw IOException("Failed to establish HTTP connection")
                val responseCode = conn.responseCode

                val isPartial = (responseCode == HttpURLConnection.HTTP_PARTIAL)
                if (responseCode != HttpURLConnection.HTTP_OK && !isPartial) {
                    if (responseCode == 416) {
                        // Range Not Satisfiable: partial file corrupt or complete, reset and restart
                        tmpFile.delete()
                        throw IOException("HTTP 416: Partial file corrupt, reset. Please retry download.")
                    }
                    throw IOException("HTTP error $responseCode: ${conn.responseMessage}")
                }

                val totalBytes = if (isPartial) {
                    val rangeHeader = conn.getHeaderField("Content-Range")
                    val totalFromRange = rangeHeader?.substringAfterLast('/')?.toLongOrNull()
                    totalFromRange ?: (conn.contentLengthLong.takeIf { it > 0 }?.plus(tmpFile.length()) ?: 0L)
                } else {
                    conn.contentLengthLong.takeIf { it > 0 } ?: 0L
                }

                val input = conn.inputStream.buffered(128 * 1024)
                val output = FileOutputStream(tmpFile, isPartial).buffered(128 * 1024)
                var downloadedBytes = if (isPartial) tmpFile.length() else 0L
                val buffer = ByteArray(64 * 1024)
                var lastProgressUpdate = System.currentTimeMillis()

                try {
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate >= 500) {
                            lastProgressUpdate = now
                            val pct = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                            _downloadStatus.value = DownloadState.Downloading(pct, downloadedBytes, totalBytes)
                        }
                    }
                    output.flush()
                } finally {
                    try { output.close() } catch (_: Exception) {}
                    try { input.close() } catch (_: Exception) {}
                    try { conn.disconnect() } catch (_: Exception) {}
                }

                // Verify downloaded file integrity
                if (tmpFile.length() < 200 * 1024 * 1024L) {
                    throw IOException("Downloaded model weights corrupted or too small (${tmpFile.length()} bytes)")
                }
                if (totalBytes > 0 && downloadedBytes < totalBytes) {
                    throw IOException("Download incomplete ($downloadedBytes / $totalBytes bytes)")
                }

                // Atomic swap to final file
                if (destFile.exists()) {
                    destFile.delete()
                }
                val renamed = tmpFile.renameTo(destFile)
                val finalFile = if (renamed) destFile else {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                    destFile
                }

                prefs.edit().putString(Constants.PREF_LAST_KNOWN_MODEL_PATH, finalFile.absolutePath).apply()
                Timber.i("🎉 Model download completed successfully: ${finalFile.absolutePath} (${finalFile.length()} bytes)")
                _downloadStatus.value = DownloadState.Success(finalFile)

            } catch (ce: CancellationException) {
                Timber.w("Model download cancelled by user")
                _downloadStatus.value = DownloadState.Error("Download manually cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Model download failed")
                _downloadStatus.value = DownloadState.Error(e.message ?: "Download failed")
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun cancelDownload() {
        if (downloadJob?.isActive == true) {
            downloadJob?.cancel()
            downloadJob = null
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
        fun findAnyLocalModel(context: Context, targetVariant: String? = null): File? {
            val tier = Constants.resolveHardwareModelTier(context)
            val effectiveTarget = targetVariant ?: if (tier == "E2B") "E2B" else null

            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val cachedPath = prefs.getString(Constants.PREF_LAST_KNOWN_MODEL_PATH, null)
            if (!cachedPath.isNullOrBlank()) {
                val cachedFile = File(cachedPath)
                if (cachedFile.exists() && cachedFile.length() > 200 * 1024 * 1024L) {
                    val isE4b = cachedFile.name.contains("e4b", ignoreCase = true)
                    // If device is strictly E2B tier, never accept E4B cached model
                    if (tier != "E2B" || !isE4b) {
                        return cachedFile
                    }
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
            val standardNames = when {
                effectiveTarget?.equals("E2B", ignoreCase = true) == true -> listOf(Constants.MODEL_NAME_E2B)
                effectiveTarget?.equals("E4B", ignoreCase = true) == true -> listOf(Constants.MODEL_NAME_E4B, Constants.MODEL_NAME_E2B)
                else -> listOf(Constants.MODEL_NAME_E4B, Constants.MODEL_NAME_E2B)
            }

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
                    val isCandidate = (name.endsWith(".litertlm", ignoreCase = true) ||
                     name.endsWith(".gguf", ignoreCase = true) ||
                     name.endsWith(".nexa", ignoreCase = true)) &&
                    file.length() > 200 * 1024 * 1024L

                    if (!isCandidate) false
                    else if (tier == "E2B" && name.contains("e4b", ignoreCase = true)) false // Strictly reject E4B on E2B tier
                    else true
                }?.toList() ?: emptyList()
            }.sortedWith(compareByDescending<File> { 
                if (effectiveTarget != null && it.name.contains(effectiveTarget, ignoreCase = true)) 1 else 0
            }.thenByDescending { it.length() }).firstOrNull()

            if (found != null) {
                prefs.edit().putString(Constants.PREF_LAST_KNOWN_MODEL_PATH, found.absolutePath).apply()
            }
            return found
        }
    }
}
