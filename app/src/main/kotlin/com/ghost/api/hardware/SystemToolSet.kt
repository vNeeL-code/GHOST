package com.ghost.api.hardware

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Environment
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.view.KeyEvent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.util.Locale
import java.util.TimeZone
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

class SystemToolSet(private val context: Context) : ToolSet {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val packageManager: PackageManager = context.packageManager
    private val memoryManager by lazy { com.ghost.api.database.MemoryManager(context) }
    private val diaryManager by lazy { DiaryManager(context) }
    private var appListCache: List<AppInfo>? = null
    private var appListCacheTime: Long = 0L

    data class AppInfo(val label: String, val packageName: String)

    @Tool(description = "Opens an app by its name")
    fun app(
        @ToolParam(description = "The name of the app to launch") name: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("APP", 1500)
        val apps = getInstalledApps()
        val bestMatch = apps.find { it.label.contains(name, ignoreCase = true) }

        return if (bestMatch != null) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(bestMatch.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    mapOf("result" to "success", "message" to "Launched ${bestMatch.label}")
                } else {
                    mapOf("result" to "error", "message" to "Could not launch ${bestMatch.label}")
                }
            } catch (e: Exception) {
                mapOf("result" to "error", "message" to "Failed to launch: ${e.message}")
            }
        } else {
            mapOf("result" to "error", "message" to "App not found matching '$name'")
        }
    }

    @Tool(description = "Controls media playback")
    fun media(
        @ToolParam(description = "PLAY, PAUSE, NEXT, PREV") action: String
    ): Map<String, String> {
        val keyEvent = when (action.uppercase(Locale.ROOT)) {
            "PLAY", "PAUSE", "TOGGLE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "PREVIOUS", "PREV" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return mapOf("result" to "error", "message" to "Unknown command")
        }
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyEvent))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyEvent))
        return mapOf("result" to "success", "message" to "Media Action Sent: $action")
    }

    private fun getInstalledApps(): List<AppInfo> {
        val now = System.currentTimeMillis()
        if (appListCache == null || (now - appListCacheTime) > 60_000L) {
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            appListCache = packageManager.queryIntentActivities(intent, 0).map {
                AppInfo(it.loadLabel(packageManager).toString(), it.activityInfo.packageName)
            }
            appListCacheTime = now
        }
        return appListCache ?: emptyList()
    }

    @Tool(description = "Sets an alarm for a specific time via the system Clock app")
    fun alarm(
        @ToolParam(description = "Strictly 24-hour format hour (e.g. 14 for 2 PM)") hour: Int, 
        @ToolParam(description = "Minutes") minutes: Int, 
        @ToolParam(description = "Optional label") label: String = ""
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("ALARM", 1500)
        return try {
            // v4.1.7: Reverted to AlarmClock intent (system handles alarm lifecycle)
            // Old GhostAlarmReceiver approach was unreliable — model would confirm alarm but nothing fired
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val timeStr = "${hour.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
            mapOf("result" to "success", "message" to "Alarm set for $timeStr${if (label.isNotBlank()) " ($label)" else ""}")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to set alarm: ${e.message}")
        }
    }

    @Tool(description = "Sets a timer for the specified duration")
    fun timer(
        @ToolParam(description = "Total duration in precise seconds (e.g. 5 minutes = 300)") seconds: Int, 
        @ToolParam(description = "Optional label") label: String = ""
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("TIMER", 1500)
        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            mapOf("result" to "success", "message" to "Timer scheduled for $seconds seconds in system Clock app")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to set timer: ${e.message}")
        }
    }

    @Tool(description = "Creates a calendar event")
    fun calendar(
        @ToolParam(description = "Event title") title: String, 
        @ToolParam(description = "Event description") description: String = "", 
        @ToolParam(description = "Duration in minutes") minutes: Int = 30
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("CALENDAR")
        return try {
            var calId: Long = 1
            val projection = arrayOf(android.provider.CalendarContract.Calendars._ID)
            val selection = "${android.provider.CalendarContract.Calendars.IS_PRIMARY} = 1"
            context.contentResolver.query(
                android.provider.CalendarContract.Calendars.CONTENT_URI,
                projection, selection, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    calId = cursor.getLong(0)
                } else {
                    // Fallback to first available calendar if no primary is found
                    context.contentResolver.query(
                        android.provider.CalendarContract.Calendars.CONTENT_URI,
                        projection, null, null, null
                    )?.use { fallbackCursor ->
                        if (fallbackCursor.moveToFirst()) calId = fallbackCursor.getLong(0)
                    }
                }
            }

            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.DTSTART, System.currentTimeMillis())
                put(android.provider.CalendarContract.Events.DTEND, System.currentTimeMillis() + minutes * 60 * 1000)
                put(android.provider.CalendarContract.Events.TITLE, title)
                put(android.provider.CalendarContract.Events.DESCRIPTION, description)
                put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            context.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
            mapOf("result" to "success", "message" to "Calendar event created silently on calendar $calId")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to create event: ${e.message}")
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    @Tool(description = "Reads upcoming calendar events")
    fun read_calendar(
        @ToolParam(description = "Days ahead to read") days: Int = 7
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("CALENDAR")
        return try {
            val now = System.currentTimeMillis()
            val later = now + days * 24 * 60 * 60 * 1000L
            val projection = arrayOf(
                android.provider.CalendarContract.Events.TITLE,
                android.provider.CalendarContract.Events.DTSTART
            )
            val selection = "${android.provider.CalendarContract.Events.DTSTART} >= ? AND ${android.provider.CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(now.toString(), later.toString())
            
            val cursor = context.contentResolver.query(
                android.provider.CalendarContract.Events.CONTENT_URI,
                projection, selection, selectionArgs,
                "${android.provider.CalendarContract.Events.DTSTART} ASC"
            )
            
            val events = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val title = it.getString(0)
                    val date = java.util.Date(it.getLong(1)).toString()
                    events.add("- $title at $date")
                }
            }
            mapOf("result" to "success", "events" to if (events.isEmpty()) "No upcoming events" else events.joinToString("\n"))
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to read calendar: ${e.message}")
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    @Tool(description = "Reads recent diary entries and reflections from persistent memory and Google Calendar")
    fun read_diary(
        @ToolParam(description = "Number of days in the past to look back (default: 7)") days: Int = 7
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("DIARY")
        return try {
            val now = System.currentTimeMillis()
            val past = now - (days * 24 * 60 * 60 * 1000L)
            val memories = diaryManager.searchMemories("DREAM")
            val dbEntries = kotlinx.coroutines.runBlocking {
                memoryManager.getRecentDiaryEntries(20)
            }.filter { it.eventType == "DREAM" && it.timestamp >= past }

            val entries = mutableListOf<String>()
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            
            dbEntries.forEach {
                val dateStr = sdf.format(java.util.Date(it.timestamp))
                val cleanBody = it.observation.take(250).replace("\n", " ")
                entries.add("[$dateStr] $cleanBody")
            }

            if (entries.isEmpty() && memories.isNotEmpty()) {
                memories.take(10).forEach { entries.add(it.take(250)) }
            }

            mapOf(
                "result" to "success",
                "diary_entries" to if (entries.isEmpty()) "No diary entries logged in the last $days days." else entries.joinToString("\n\n")
            )
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to read diary: ${e.message}")
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    // Automation tools moved to UiMacroToolSet

    @Tool(description = "Saves a semantic fact memory")
    fun remember(
        @ToolParam(description = "Memory title/subject") title: String, 
        @ToolParam(description = "Memory content/fact") content: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("MEMORIES")
        return try {
            kotlinx.coroutines.runBlocking {
                memoryManager.storeSemanticFact(title, content)
            }
            mapOf("result" to "success", "message" to "Factored into Semantic Memory: $title")
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    @Tool(description = "Recalls memory from both Semantic DB and Calendar")
    fun recall(
        @ToolParam(description = "Search query keyword") query: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("MEMORIES")
        return try {
            // 1. Query Episodic Memory (Calendar)
            val episodicMemories = diaryManager.searchMemories(query)
            
            // 2. Query Semantic Memory (FTS4 DB)
            val semanticMemories = kotlinx.coroutines.runBlocking {
                memoryManager.searchSemanticFacts(query)
            }
            
            val merged = buildString {
                if (episodicMemories.isNotEmpty()) {
                    append("[EPISODIC / CALENDAR]\n")
                    episodicMemories.forEach { append("- $it\n") }
                    append("\n")
                }
                if (semanticMemories.isNotEmpty()) {
                    append("[SEMANTIC / FACTS]\n")
                    semanticMemories.forEach { append("- ${it.subject}: ${it.object_}\n") }
                }
            }.trim()
            
            if (merged.isEmpty()) {
                mapOf("result" to "success", "memories" to "No memories found for '$query'.")
            } else {
                mapOf("result" to "success", "memories" to merged)
            }
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    companion object {
        private val AUDIO_EXTS = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus", "wma")
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp")
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "svg")
        private val DOC_EXTS = setOf("pdf", "epub", "doc", "docx", "txt", "md", "json", "csv", "xml", "apk", "zip")
        private val ALL_KNOWN_EXTS = AUDIO_EXTS + VIDEO_EXTS + IMAGE_EXTS + DOC_EXTS
    }

    private data class ScoredFile(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val score: Int
    )

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    private fun extractExtensionFilter(query: String): String? {
        val trimmed = query.trim().lowercase(Locale.ROOT)
        if (trimmed.startsWith(".") && trimmed.length > 1) {
            val ext = trimmed.drop(1)
            if (ALL_KNOWN_EXTS.contains(ext)) return ext
        }
        if (ALL_KNOWN_EXTS.contains(trimmed)) return trimmed
        if (trimmed.contains(".")) {
            val lastPart = trimmed.substringAfterLast(".")
            if (ALL_KNOWN_EXTS.contains(lastPart)) return lastPart
        }
        return null
    }

    private fun scoreCandidate(fileName: String, queryClean: String, tokens: List<String>, extFilter: String?): Int {
        val nameLower = fileName.lowercase(Locale.ROOT)
        val fileExt = nameLower.substringAfterLast(".", "")
        val nameWithoutExt = nameLower.substringBeforeLast(".")

        if (!extFilter.isNullOrBlank() && extFilter != "all") {
            if (!fileExt.equals(extFilter, ignoreCase = true)) return 0
        }

        // Extension-only query (e.g. user asked for "mp3" or "pdf")
        if (tokens.isEmpty()) {
            return if (!extFilter.isNullOrBlank() && fileExt.equals(extFilter, ignoreCase = true)) 90 else 0
        }

        // Exact match
        if (nameWithoutExt == queryClean) return 100
        if (nameWithoutExt.contains(queryClean)) return 95

        // Collapsed match: ignore spaces, underscores, hyphens, dots
        val nameCollapsed = nameWithoutExt.replace(Regex("[^a-z0-9]"), "")
        val queryCollapsed = queryClean.replace(Regex("[^a-z0-9]"), "")
        if (queryCollapsed.isNotEmpty() && nameCollapsed.contains(queryCollapsed)) return 92

        // Token & Syllable match
        val nameWords = nameWithoutExt.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        var fullMatches = 0
        var prefixMatches = 0

        for (token in tokens) {
            if (nameWithoutExt.contains(token)) {
                fullMatches++
            } else if (nameWords.any { word -> word.startsWith(token) || token.startsWith(word) }) {
                prefixMatches++
            }
        }

        val totalMatches = fullMatches + prefixMatches
        if (fullMatches == tokens.size) {
            val inOrder = tokens.size > 1 && nameWithoutExt.indexOf(tokens.first()) <= nameWithoutExt.indexOf(tokens.last())
            return if (inOrder) 88 else 82
        }

        if (totalMatches == tokens.size) {
            return 76
        }

        if (totalMatches > 0) {
            return 35 + ((totalMatches * 40) / tokens.size)
        }

        return 0
    }

    @JvmOverloads
    @Tool(description = "Searches device storage and MediaStore for files matching keywords, partial syllables, or extensions (e.g. 'snake eyes', 'invoice', '.mp3', 'pdf')")
    fun search_files(
        @ToolParam(description = "Search term, partial syllables, filename words, or extension (e.g. 'snake eyes', 'invoice', 'mp3')") query: String,
        @ToolParam(description = "Optional filter: 'audio', 'video', 'image', 'doc', 'any'") type: String = "any"
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("STORAGE")
        Timber.i("search_files called: query='$query', type='$type'")
        return try {
            val rawQuery = query.trim()
            val extFilter = extractExtensionFilter(rawQuery)
            val cleanForTokens = if (extFilter != null && rawQuery.endsWith(".$extFilter", ignoreCase = true)) {
                rawQuery.dropLast(extFilter.length + 1).trim()
            } else if (extFilter != null && rawQuery.equals(extFilter, ignoreCase = true)) {
                ""
            } else {
                rawQuery
            }
            val tokens = cleanForTokens.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
            val queryClean = cleanForTokens.lowercase(Locale.ROOT)

            val scoredResults = mutableMapOf<String, ScoredFile>()

            // 1. MediaStore query (Fast indexed audio/video/images/downloads/files)
            val contentUris = when {
                type.equals("audio", ignoreCase = true) || type.equals("music", ignoreCase = true) || (extFilter != null && AUDIO_EXTS.contains(extFilter)) ->
                    listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                type.equals("video", ignoreCase = true) || type.equals("movie", ignoreCase = true) || (extFilter != null && VIDEO_EXTS.contains(extFilter)) ->
                    listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                type.equals("image", ignoreCase = true) || type.equals("photo", ignoreCase = true) || (extFilter != null && IMAGE_EXTS.contains(extFilter)) ->
                    listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                else -> listOfNotNull(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else null,
                    MediaStore.Files.getContentUri("external")
                )
            }

            for (contentUri in contentUris) {
                try {
                    val projection = arrayOf(
                        MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_MODIFIED
                    )

                    val selectionParts = mutableListOf<String>()
                    val selectionArgs = mutableListOf<String>()

                    if (tokens.isNotEmpty()) {
                        if (tokens.size == 1) {
                            selectionParts.add("${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
                            selectionArgs.add("%${tokens[0]}%")
                        } else {
                            val tokenClauses = tokens.map { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" }
                            selectionParts.add("(${tokenClauses.joinToString(" OR ")})")
                            selectionArgs.addAll(tokens.map { "%$it%" })
                        }
                    }

                    if (!extFilter.isNullOrBlank() && extFilter != "all") {
                        selectionParts.add("${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
                        selectionArgs.add("%.${extFilter}")
                    }

                    val selection = if (selectionParts.isNotEmpty()) selectionParts.joinToString(" AND ") else null
                    val selArgsArray = if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null

                    context.contentResolver.query(
                        contentUri,
                        projection,
                        selection,
                        selArgsArray,
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                    )?.use { cursor ->
                        val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                        while (cursor.moveToNext()) {
                            val path = if (dataCol != -1) cursor.getString(dataCol) else null
                            if (path == null) continue
                            val name = if (nameCol != -1) cursor.getString(nameCol) else File(path).name
                            val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                            val date = if (dateCol != -1) cursor.getLong(dateCol) * 1000L else 0L

                            val score = scoreCandidate(name, queryClean, tokens, extFilter)
                            if (score >= 35) {
                                scoredResults[path] = ScoredFile(name, path, size, date, score)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "MediaStore search failed for $contentUri")
                }
            }

            // 2. Direct File System search across standard readable dirs
            val searchRoots = listOfNotNull(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                context.getExternalFilesDir(null)
            ).filter { it.exists() && it.canRead() }

            for (dir in searchRoots) {
                try {
                    dir.walkTopDown()
                        .maxDepth(3)
                        .filter { it.isFile && it.canRead() }
                        .forEach { f ->
                            val score = scoreCandidate(f.name, queryClean, tokens, extFilter)
                            if (score >= 35 && !scoredResults.containsKey(f.absolutePath)) {
                                scoredResults[f.absolutePath] = ScoredFile(
                                    name = f.name,
                                    path = f.absolutePath,
                                    sizeBytes = f.length(),
                                    lastModified = f.lastModified(),
                                    score = score
                                )
                            }
                        }
                } catch (e: Exception) {
                    // Ignore inaccessible subdirectories
                }
            }

            val sorted = scoredResults.values.sortedWith(
                compareByDescending<ScoredFile> { it.score }
                    .thenByDescending { it.lastModified }
            ).take(10)

            if (sorted.isNotEmpty()) {
                val matches = sorted.joinToString("\n") { file ->
                    val sizeStr = formatBytes(file.sizeBytes)
                    "- ${file.name} ($sizeStr)\n  Path: ${file.path}"
                }.take(1400)
                com.ghost.api.GemmaService.instance?.recordToolOutput(matches.length)
                mapOf("result" to "success", "count" to sorted.size.toString(), "matches" to matches)
            } else {
                mapOf("result" to "success", "matches" to "No files found matching '$query'.")
            }
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    @JvmOverloads
    @Tool(description = "Lists files in a specific folder or category (e.g. 'downloads', 'documents', 'music', 'audio', 'movies', 'pictures', or an absolute directory path)")
    fun list_files(
        @ToolParam(description = "Target category ('downloads', 'documents', 'music', 'audio', 'movies', 'pictures') or an absolute directory path like '/sdcard/Download'") target: String = "downloads",
        @ToolParam(description = "Optional extension filter (e.g. 'mp3', 'pdf', 'apk', 'all')") extension: String = "all",
        @ToolParam(description = "Maximum files to return (default: 12, max: 15)") limit: Int = 12
    ): Map<String, String> {
        val safeLimit = limit.coerceIn(1, 15)
        com.ghost.api.GemmaService.instance?.showWorkSignal("STORAGE", 1500)
        Timber.i("list_files called: target='$target', extension='$extension', limit=$safeLimit")
        return try {
            val extClean = extension.trim().removePrefix(".").lowercase(Locale.ROOT)
            val lowerTarget = target.trim().lowercase(Locale.ROOT)

            // Check if user requested MediaStore media category
            if (lowerTarget in listOf("music", "audio", "movies", "video", "videos", "pictures", "photos", "images", "downloads", "download")) {
                val contentUri = when (lowerTarget) {
                    "music", "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    "movies", "video", "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "pictures", "photos", "images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    else -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) MediaStore.Downloads.EXTERNAL_CONTENT_URI else null
                }

                if (contentUri != null) {

                val projection = arrayOf(
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED
                )

                val selection = if (extClean.isNotBlank() && extClean != "all") {
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                } else null
                val selArgs = if (selection != null) arrayOf("%.${extClean}") else null

                val filesList = mutableListOf<String>()
                context.contentResolver.query(
                    contentUri,
                    projection,
                    selection,
                    selArgs,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)

                    while (cursor.moveToNext() && filesList.size < safeLimit) {
                        val path = cursor.getString(dataCol) ?: continue
                        val name = cursor.getString(nameCol) ?: File(path).name
                        val size = formatBytes(cursor.getLong(sizeCol))
                        val date = if (dateCol != -1) {
                            dateFormat.format(java.util.Date(cursor.getLong(dateCol) * 1000L))
                        } else ""
                        val dateStr = if (date.isNotEmpty()) ", $date" else ""
                        filesList.add("- $name ($size$dateStr)\n  Path: $path")
                    }
                }

                if (filesList.isNotEmpty()) {
                    val filesStr = ("Files in $target:\n" + filesList.joinToString("\n")).take(1400)
                    com.ghost.api.GemmaService.instance?.recordToolOutput(filesStr.length)
                    return mapOf(
                        "result" to "success",
                        "count" to filesList.size.toString(),
                        "target" to target,
                        "files" to filesStr
                    )
                }
                }
            }

            // Target directory resolution
            val targetDir = when (lowerTarget) {
                "downloads", "download" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                "documents", "document", "docs" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                "music", "audio" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                "movies", "video", "videos" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                "pictures", "photos", "images" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                else -> File(target)
            }

            if (!targetDir.exists() || !targetDir.isDirectory) {
                return mapOf("result" to "error", "message" to "Directory not found or inaccessible: ${targetDir.absolutePath}")
            }

            val files = targetDir.listFiles { f ->
                f.isFile && (extClean == "all" || f.extension.equals(extClean, ignoreCase = true))
            }?.sortedByDescending { it.lastModified() }?.take(safeLimit) ?: emptyList()

            if (files.isEmpty()) {
                val filterMsg = if (extClean != "all") " with extension '.$extClean'" else ""
                return mapOf("result" to "success", "files" to "No files found in ${targetDir.name}$filterMsg.")
            }

            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val formatted = files.joinToString("\n") { f ->
                val dateStr = dateFormat.format(java.util.Date(f.lastModified()))
                "- ${f.name} (${formatBytes(f.length())}, $dateStr)\n  Path: ${f.absolutePath}"
            }.take(1400)

            com.ghost.api.GemmaService.instance?.recordToolOutput(formatted.length)

            mapOf(
                "result" to "success",
                "count" to files.size.toString(),
                "target" to targetDir.absolutePath,
                "files" to "Files in ${targetDir.name} (${files.size} items):\n$formatted"
            )
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to list files: ${e.message}")
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    @Tool(description = "Moves or renames a file from sourcePath to destinationPath (can specify destination directory or new filename)")
    fun move_file(
        @ToolParam(description = "Absolute path of the source file to move") sourcePath: String,
        @ToolParam(description = "Absolute path of the destination file or directory") destinationPath: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("FILES", 1500)
        return try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists() || !sourceFile.isFile) {
                return mapOf("result" to "error", "message" to "Source file does not exist or is not a file: $sourcePath")
            }

            var destFile = File(destinationPath)
            if (destFile.exists() && destFile.isDirectory) {
                destFile = File(destFile, sourceFile.name)
            } else if (!destFile.exists() && destinationPath.endsWith("/")) {
                destFile.mkdirs()
                destFile = File(destFile, sourceFile.name)
            } else {
                destFile.parentFile?.mkdirs()
            }

            val moved = try {
                sourceFile.renameTo(destFile)
            } catch (e: Exception) {
                false
            }

            val success = if (moved && destFile.exists()) {
                true
            } else {
                // Fallback copy + delete across mount boundaries
                sourceFile.copyTo(destFile, overwrite = true)
                if (destFile.exists() && destFile.length() == sourceFile.length()) {
                    sourceFile.delete()
                    true
                } else false
            }

            if (success) {
                try {
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(sourcePath, destFile.absolutePath),
                        null,
                        null
                    )
                } catch (_: Exception) {}
                mapOf(
                    "result" to "success",
                    "message" to "Successfully moved '${sourceFile.name}' to '${destFile.absolutePath}'",
                    "newPath" to destFile.absolutePath
                )
            } else {
                mapOf("result" to "error", "message" to "Failed to move '$sourcePath' to '$destinationPath'")
            }
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Move error: ${e.message}")
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    @Tool(description = "Copies a file from sourcePath to destinationPath or destination directory")
    fun copy_file(
        @ToolParam(description = "Absolute path of the source file to copy") sourcePath: String,
        @ToolParam(description = "Absolute path of the destination file or directory") destinationPath: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("FILES", 1500)
        return try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists() || !sourceFile.isFile) {
                return mapOf("result" to "error", "message" to "Source file does not exist: $sourcePath")
            }

            var destFile = File(destinationPath)
            if (destFile.exists() && destFile.isDirectory) {
                destFile = File(destFile, sourceFile.name)
            } else {
                destFile.parentFile?.mkdirs()
            }

            sourceFile.copyTo(destFile, overwrite = true)
            try {
                android.media.MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
            } catch (_: Exception) {}

            mapOf(
                "result" to "success",
                "message" to "Copied '${sourceFile.name}' to '${destFile.absolutePath}' (${formatBytes(destFile.length())})",
                "copiedPath" to destFile.absolutePath
            )
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Copy error: ${e.message}")
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    @Tool(description = "Safely deletes a specified file (requires confirmation for safety, cannot delete directories)")
    fun delete_file(
        @ToolParam(description = "Absolute path of the file to delete") filePath: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("FILES", 1500)
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return mapOf("result" to "error", "message" to "File does not exist: $filePath")
            }
            if (file.isDirectory) {
                return mapOf("result" to "error", "message" to "Cannot delete directories with delete_file for safety.")
            }
            // Protect critical model and system files
            if (file.name.endsWith(".litertlm", ignoreCase = true) && file.parent?.contains("models") == true) {
                return mapOf("result" to "error", "message" to "Protected file: cannot delete active model weights.")
            }

            val deleted = file.delete()
            if (deleted) {
                try {
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
                } catch (_: Exception) {}
                mapOf("result" to "success", "message" to "Deleted file: ${file.name}")
            } else {
                mapOf("result" to "error", "message" to "Failed to delete file: $filePath")
            }
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Delete error: ${e.message}")
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    @Tool(description = "Gets detailed metadata for a file (size, modified date, MIME type, existence)")
    fun get_file_info(
        @ToolParam(description = "Absolute path of the file to inspect") filePath: String
    ): Map<String, String> {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return mapOf("result" to "error", "message" to "File does not exist: $filePath")
            }

            val ext = file.extension.lowercase(Locale.ROOT)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val modifiedDate = dateFormat.format(java.util.Date(file.lastModified()))

            mapOf(
                "result" to "success",
                "name" to file.name,
                "path" to file.absolutePath,
                "size" to formatBytes(file.length()),
                "sizeBytes" to file.length().toString(),
                "modified" to modifiedDate,
                "mimeType" to mimeType,
                "isDirectory" to file.isDirectory.toString(),
                "canRead" to file.canRead().toString(),
                "canWrite" to file.canWrite().toString()
            )
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Info error: ${e.message}")
        }
    }

    @JvmOverloads
    @Tool(description = "Opens a local file with its default system handler or a specific app (e.g. VLC, Gallery, Acrobat)")
    fun open_file(
        @ToolParam(description = "Absolute path of the file to open (e.g. /sdcard/Download/song.mp3)") filePath: String,
        @ToolParam(description = "Optional app name to open with (e.g. 'VLC', 'Spotify', 'Chrome')") appName: String = ""
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("FILES", 1500)
        val file = File(filePath)
        if (!file.exists()) {
            return mapOf("result" to "error", "message" to "File not found at: $filePath")
        }

        return try {
            val ext = file.extension.lowercase(Locale.ROOT)
            val mimeType = when (ext) {
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg", "oga" -> "audio/ogg"
                "m4a", "aac" -> "audio/mp4"
                "flac" -> "audio/flac"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "md", "markdown" -> "text/markdown"
                "json" -> "application/json"
                "pdf" -> "application/pdf"
                "apk" -> "application/vnd.android.package-archive"
                "txt", "log", "py", "kt", "sh", "properties", "yaml", "yml" -> "text/plain"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            }

            val isMedia = mimeType.startsWith("audio/") || mimeType.startsWith("video/") || mimeType.startsWith("image/")

            // For media files, scan into MediaStore to get a stable canonical content://media/ URI
            // to avoid anonymous Linux file descriptors (fd://) dropping in players like VLC on orientation/fullscreen.
            var targetUri: android.net.Uri? = null
            if (isMedia) {
                try {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf(mimeType)
                    ) { _, uri ->
                        if (uri != null) targetUri = uri
                        latch.countDown()
                    }
                    latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    Timber.w(e, "MediaScannerConnection scan failed, falling back to FileProvider")
                }
            }

            val finalUri = targetUri ?: FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(finalUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (appName.isNotBlank()) {
                val apps = getInstalledApps()
                val targetApp = apps.find { it.label.contains(appName, ignoreCase = true) }
                if (targetApp != null) {
                    intent.setPackage(targetApp.packageName)
                }
            }

            context.startActivity(intent)
            val appLabel = if (appName.isNotBlank()) " in $appName" else ""
            mapOf("result" to "success", "message" to "Opened ${file.name}$appLabel ($mimeType)")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to open file: ${e.message}")
        }
    }

    @JvmOverloads
    @Tool(description = "Reads and returns the text content of a local text/markdown/code/json/log file")
    fun read_file_text(
        @ToolParam(description = "Absolute path of the text/markdown/json file to read") filePath: String,
        @ToolParam(description = "Maximum lines to read (default: 100)") maxLines: Int = 100
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("FILES")
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return mapOf("result" to "error", "message" to "File not found at: $filePath")
            }
            if (file.length() > 2 * 1024 * 1024) {
                return mapOf("result" to "error", "message" to "File is too large (>2MB) to read into memory.")
            }

            val lines = file.bufferedReader().useLines { linesSequence ->
                linesSequence.take(maxLines.coerceIn(1, 80)).toList()
            }
            val content = lines.joinToString("\n")
                .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFD]"), "")
                .take(1800)
            com.ghost.api.GemmaService.instance?.recordToolOutput(content.length)
            mapOf("result" to "success", "content" to content, "linesRead" to lines.size.toString())
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to read file: ${e.message?.take(80)}")
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

}
