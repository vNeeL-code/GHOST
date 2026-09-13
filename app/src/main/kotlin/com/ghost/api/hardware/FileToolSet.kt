package com.ghost.api.hardware

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.util.Locale
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * FileToolSet - MediaStore & Local Storage Management
 * 
 * Extracted from SystemToolSet into a Tier 2 Lazy-Loaded ToolSet.
 * Injected into Gemma's KV cache only when file/storage intents are detected.
 */
class FileToolSet(private val context: Context) : ToolSet {

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        return String.format(Locale.ROOT, "%.1f %s", bytes / Math.pow(1024.0, index.toDouble()), units[index])
    }

    private fun extractExtensionFilter(query: String): String? {
        val extRegex = Regex("""\b(?:in\s+)?(\.?[a-zA-Z0-9]{2,5})\b""")
        val commonExtensions = setOf("pdf", "mp3", "m4a", "wav", "flac", "ogg", "mp4", "mkv", "mov", "jpg", "jpeg", "png", "webp", "doc", "docx", "txt", "zip", "apk", "json", "csv")
        for (m in extRegex.findAll(query)) {
            val candidate = m.groupValues[1].removePrefix(".").lowercase(Locale.ROOT)
            if (candidate in commonExtensions) return candidate
        }
        return null
    }

    private fun scoreCandidate(fileName: String, queryClean: String, tokens: List<String>, extFilter: String?): Int {
        var score = 0
        val lowerName = fileName.lowercase(Locale.ROOT)
        val candidateExt = lowerName.substringAfterLast('.', "")

        if (extFilter != null) {
            if (candidateExt.equals(extFilter, ignoreCase = true)) {
                score += 30
            } else {
                return -100
            }
        }

        if (lowerName == queryClean) score += 100
        else if (lowerName.startsWith(queryClean)) score += 60
        else if (lowerName.contains(queryClean)) score += 40

        var matchedTokens = 0
        for (token in tokens) {
            if (token.length < 2) continue
            if (lowerName.contains(token)) {
                matchedTokens++
                score += 15
                if (lowerName.startsWith(token)) score += 10
            }
        }

        if (tokens.isNotEmpty() && matchedTokens == tokens.size) {
            score += 25
        }

        return score
    }

    @Tool(description = "Searches device storage and MediaStore for files matching keywords, partial syllables, or extensions (e.g. invoice, mp3, pdf)")
    fun search_files(
        @ToolParam(description = "Keywords, syllables, or extension to search (e.g. invoice, mp3, pdf)") query: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("FILES", 1500)
        return try {
            val queryClean = query.trim().lowercase(Locale.ROOT)
            val extFilter = extractExtensionFilter(queryClean)
            val searchTokens = queryClean.split(Regex("""[\s_\-.]+""")).filter { it.length >= 2 }

            data class MatchCandidate(val name: String, val path: String, val size: Long, val modified: Long, val score: Int)
            val candidates = mutableListOf<MatchCandidate>()

            fun scanDir(dir: File, currentDepth: Int = 0, maxDepth: Int = 3) {
                if (currentDepth > maxDepth || !dir.exists() || !dir.isDirectory || !dir.canRead()) return
                val files = dir.listFiles() ?: return
                for (f in files) {
                    if (f.name.startsWith(".")) continue
                    if (f.isDirectory) {
                        scanDir(f, currentDepth + 1, maxDepth)
                    } else if (f.isFile) {
                        val score = scoreCandidate(f.name, queryClean, searchTokens, extFilter)
                        if (score > 0) {
                            candidates.add(MatchCandidate(f.name, f.absolutePath, f.length(), f.lastModified(), score))
                        }
                    }
                }
            }

            // 1. Scan standard storage roots
            scanDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            scanDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
            scanDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
            scanDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
            scanDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES))

            // 2. Query MediaStore Audio
            try {
                val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED)
                context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { cursor ->
                    val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                    val modCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                    while (cursor.moveToNext()) {
                        val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                        val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                        val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        val mod = if (modCol >= 0) cursor.getLong(modCol) * 1000L else 0L
                        if (name.isNotBlank()) {
                            val score = scoreCandidate(name, queryClean, searchTokens, extFilter)
                            if (score > 0 && candidates.none { it.path == path }) {
                                candidates.add(MatchCandidate(name, path, size, mod, score))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "MediaStore audio query failed")
            }

            val ranked = candidates
                .sortedWith(compareByDescending<MatchCandidate> { it.score }.thenByDescending { it.modified })
                .distinctBy { it.path }
                .take(15)

            if (ranked.isEmpty()) {
                mapOf("result" to "success", "count" to "0", "message" to "No files found matching '$query'")
            } else {
                val formatted = ranked.mapIndexed { i, c ->
                    "${i + 1}. [${c.name}] (${formatBytes(c.size)})\n   Path: ${c.path}"
                }.joinToString("\n")
                mapOf("result" to "success", "count" to ranked.size.toString(), "files" to formatted)
            }
        } catch (e: Exception) {
            Timber.e(e, "search_files failed")
            mapOf("result" to "error", "message" to "File search error: ${e.message}")
        }
    }

    @Tool(description = "Lists files in a specific folder or category (downloads, documents, music, pictures, or an absolute directory path)")
    fun list_files(
        @ToolParam(description = "Folder name or path: downloads, documents, music, pictures, or full directory path") folder: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("FILES", 1200)
        return try {
            val targetClean = folder.trim().lowercase(Locale.ROOT)
            val dir = when {
                targetClean.contains("download") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                targetClean.contains("doc") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                targetClean.contains("music") || targetClean.contains("audio") || targetClean.contains("song") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                targetClean.contains("movie") || targetClean.contains("video") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                targetClean.contains("pic") || targetClean.contains("photo") || targetClean.contains("image") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                targetClean.isBlank() -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                else -> File(folder.trim())
            }

            if (!dir.exists() || !dir.isDirectory) {
                return mapOf("result" to "error", "message" to "Directory not found: ${dir.absolutePath}")
            }

            val files = dir.listFiles()
                ?.filter { f -> !f.name.startsWith(".") }
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() })
                ?.take(20)
                ?: emptyList()

            if (files.isEmpty()) {
                mapOf("result" to "success", "count" to "0", "message" to "Folder is empty: ${dir.absolutePath}")
            } else {
                val formatted = files.mapIndexed { i, f ->
                    val typeTag = if (f.isDirectory) "[DIR]" else "[${formatBytes(f.length())}]"
                    "${i + 1}. $typeTag ${f.name}\n   ${f.absolutePath}"
                }.joinToString("\n")
                mapOf("result" to "success", "directory" to dir.absolutePath, "count" to files.size.toString(), "files" to formatted)
            }
        } catch (e: Exception) {
            Timber.e(e, "list_files failed")
            mapOf("result" to "error", "message" to "Failed to list files: ${e.message}")
        }
    }

    @Tool(description = "Moves or renames a file from sourcePath to destinationPath")
    fun move_file(
        @ToolParam(description = "Absolute path of existing source file") sourcePath: String,
        @ToolParam(description = "Absolute destination directory or new absolute file path") destinationPath: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("FILES", 1500)
        return try {
            val src = File(sourcePath.trim())
            if (!src.exists() || !src.isFile) {
                return mapOf("result" to "error", "message" to "Source file does not exist: $sourcePath")
            }

            var dest = File(destinationPath.trim())
            if (dest.isDirectory) {
                dest = File(dest, src.name)
            }

            dest.parentFile?.mkdirs()
            val success = src.renameTo(dest)
            if (success) {
                mapOf("result" to "success", "message" to "Moved '${src.name}' to '${dest.absolutePath}'")
            } else {
                src.copyTo(dest, overwrite = true)
                src.delete()
                mapOf("result" to "success", "message" to "Moved (via copy/delete) to '${dest.absolutePath}'")
            }
        } catch (e: Exception) {
            Timber.e(e, "move_file failed")
            mapOf("result" to "error", "message" to "Failed to move file: ${e.message}")
        }
    }

    @Tool(description = "Copies a file from sourcePath to destinationPath or destination directory")
    fun copy_file(
        @ToolParam(description = "Absolute path of existing source file") sourcePath: String,
        @ToolParam(description = "Absolute destination directory or destination file path") destinationPath: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("FILES", 1500)
        return try {
            val src = File(sourcePath.trim())
            if (!src.exists() || !src.isFile) {
                return mapOf("result" to "error", "message" to "Source file does not exist: $sourcePath")
            }

            var dest = File(destinationPath.trim())
            if (dest.isDirectory) {
                dest = File(dest, src.name)
            }

            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            mapOf("result" to "success", "message" to "Copied '${src.name}' to '${dest.absolutePath}'")
        } catch (e: Exception) {
            Timber.e(e, "copy_file failed")
            mapOf("result" to "error", "message" to "Failed to copy file: ${e.message}")
        }
    }

    @Tool(description = "Safely deletes a specified file (requires confirmation for safety, cannot delete directories)")
    fun delete_file(
        @ToolParam(description = "Absolute path of file to delete") filePath: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("FILES", 1500)
        return try {
            val file = File(filePath.trim())
            if (!file.exists()) {
                return mapOf("result" to "error", "message" to "File does not exist: $filePath")
            }
            if (file.isDirectory) {
                return mapOf("result" to "error", "message" to "Cannot delete directories with delete_file")
            }

            val fileName = file.name
            val deleted = file.delete()
            if (deleted) {
                mapOf("result" to "success", "message" to "Deleted file '$fileName'")
            } else {
                mapOf("result" to "error", "message" to "OS refused deletion of '$fileName'")
            }
        } catch (e: Exception) {
            Timber.e(e, "delete_file failed")
            mapOf("result" to "error", "message" to "Failed to delete file: ${e.message}")
        }
    }

    @Tool(description = "Gets detailed metadata for a file (size, modified date, MIME type, existence)")
    fun get_file_info(
        @ToolParam(description = "Absolute path of file") filePath: String
    ): Map<String, String> {
        return try {
            val file = File(filePath.trim())
            if (!file.exists()) {
                return mapOf("result" to "error", "message" to "File does not exist: $filePath")
            }

            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "application/octet-stream"
            val lastModDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(java.util.Date(file.lastModified()))

            mapOf(
                "result" to "success",
                "name" to file.name,
                "path" to file.absolutePath,
                "size" to formatBytes(file.length()),
                "bytes" to file.length().toString(),
                "modified" to lastModDate,
                "mimeType" to mime,
                "isDirectory" to file.isDirectory.toString()
            )
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Error reading metadata: ${e.message}")
        }
    }

    @Tool(description = "Opens a local file with its default system handler")
    fun open_file(
        @ToolParam(description = "Absolute path of local file to open") filePath: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("APP", 1500)
        return try {
            val file = File(filePath.trim())
            if (!file.exists()) {
                return mapOf("result" to "error", "message" to "File does not exist: $filePath")
            }

            val ext = file.extension.lowercase(Locale.ROOT)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
                "mp3", "m4a", "wav", "flac", "ogg" -> "audio/*"
                "mp4", "mkv", "mov", "webm" -> "video/*"
                "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
                "pdf" -> "application/pdf"
                "txt", "log", "md", "json" -> "text/plain"
                else -> "*/*"
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            mapOf("result" to "success", "message" to "Opened '${file.name}' with MIME $mime")
        } catch (e: Exception) {
            Timber.e(e, "open_file failed")
            mapOf("result" to "error", "message" to "Failed to open file: ${e.message}")
        }
    }

    @Tool(description = "Reads and returns the text content of a local text or markdown file")
    fun read_file_text(
        @ToolParam(description = "Absolute path of the text or code file to read") filePath: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("FILES", 1500)
        return try {
            val file = File(filePath.trim())
            if (!file.exists() || !file.isFile) {
                return mapOf("result" to "error", "message" to "File does not exist: $filePath")
            }

            val lines = file.bufferedReader().useLines { linesSeq ->
                linesSeq.take(100).toList()
            }

            val content = lines.joinToString("\n")
            mapOf(
                "result" to "success",
                "filePath" to file.absolutePath,
                "linesRead" to lines.size.toString(),
                "content" to content
            )
        } catch (e: Exception) {
            Timber.e(e, "read_file_text failed")
            mapOf("result" to "error", "message" to "Failed to read file: ${e.message}")
        }
    }
}
