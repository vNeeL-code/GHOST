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
        }
    }

    @Tool(description = "Reads upcoming calendar events")
    fun read_calendar(
        @ToolParam(description = "Days ahead to read") days: Int = 7
    ): Map<String, String> {
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
        }
    }

    @Tool(description = "Reads recent diary entries and reflections from persistent memory and Google Calendar")
    fun read_diary(
        @ToolParam(description = "Number of days in the past to look back (default: 7)") days: Int = 7
    ): Map<String, String> {
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
        }
    }

    // Automation tools moved to UiMacroToolSet

    @Tool(description = "Saves a semantic fact memory")
    fun remember(
        @ToolParam(description = "Memory title/subject") title: String, 
        @ToolParam(description = "Memory content/fact") content: String
    ): Map<String, String> {
        kotlinx.coroutines.runBlocking {
            memoryManager.storeSemanticFact(title, content)
        }
        return mapOf("result" to "success", "message" to "Factored into Semantic Memory: $title")
    }

    @Tool(description = "Recalls memory from both Semantic DB and Calendar")
    fun recall(
        @ToolParam(description = "Search query keyword") query: String
    ): Map<String, String> {
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
            return mapOf("result" to "success", "memories" to "No memories found for '$query'.")
        }
        return mapOf("result" to "success", "memories" to merged)
    }

    @Tool(description = "Searches device storage and MediaStore for files matching a keyword/extension (e.g. mp3, pdf, md, video)")
    fun search_files(
        @ToolParam(description = "Filename keyword, pattern, or title (e.g. 'breakbeat', 'invoice', '.md')") query: String,
        @ToolParam(description = "Optional filter: 'audio', 'video', 'image', 'doc', 'any'") type: String = "any"
    ): Map<String, String> {
        val results = mutableListOf<String>()
        val lowerQuery = query.lowercase(Locale.ROOT)

        // 1. Search MediaStore for fast indexed media
        try {
            val contentUri = when (type.lowercase(Locale.ROOT)) {
                "audio", "music" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                "video", "movie" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "image", "photo" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Files.getContentUri("external")
            }

            val projection = arrayOf(
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
            )
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")

            context.contentResolver.query(
                contentUri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

                while (cursor.moveToNext() && results.size < 15) {
                    val path = cursor.getString(dataCol) ?: continue
                    val name = cursor.getString(nameCol) ?: File(path).name
                    val sizeMb = String.format(Locale.US, "%.1f MB", (cursor.getLong(sizeCol).toDouble() / (1024 * 1024)))
                    results.add("- $name ($sizeMb)\n  Path: $path")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "MediaStore search failed")
        }

        // 2. Direct File System search across standard external dirs for docs/markdown/text
        if (results.size < 10) {
            val searchRoots = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStorageDirectory()
            ).filterNotNull().filter { it.exists() && it.canRead() }

            for (dir in searchRoots) {
                if (results.size >= 15) break
                try {
                    dir.walkTopDown()
                        .maxDepth(3)
                        .filter { it.isFile && it.name.lowercase(Locale.ROOT).contains(lowerQuery) }
                        .take(15 - results.size)
                        .forEach { f ->
                            val sizeMb = String.format(Locale.US, "%.1f MB", (f.length().toDouble() / (1024 * 1024)))
                            val entry = "- ${f.name} ($sizeMb)\n  Path: ${f.absolutePath}"
                            if (!results.contains(entry)) {
                                results.add(entry)
                            }
                        }
                } catch (e: Exception) {
                    // Ignore inaccessible subdirectories
                }
            }
        }

        return if (results.isNotEmpty()) {
            mapOf("result" to "success", "matches" to results.joinToString("\n"))
        } else {
            mapOf("result" to "success", "matches" to "No files found matching '$query'.")
        }
    }

    @Tool(description = "Opens a local file with its default system handler or a specific app (e.g. VLC, Gallery, Acrobat)")
    fun open_file(
        @ToolParam(description = "Absolute path of the file to open (e.g. /sdcard/Download/song.mp3)") filePath: String,
        @ToolParam(description = "Optional app name to open with (e.g. 'VLC', 'Spotify', 'Chrome')") appName: String = ""
    ): Map<String, String> {
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

    @Tool(description = "Reads and returns the text content of a local text/markdown/code/json/log file")
    fun read_file_text(
        @ToolParam(description = "Absolute path of the text/markdown/json file to read") filePath: String,
        @ToolParam(description = "Maximum lines to read (default: 100)") maxLines: Int = 100
    ): Map<String, String> {
        val file = File(filePath)
        if (!file.exists()) {
            return mapOf("result" to "error", "message" to "File not found at: $filePath")
        }
        if (file.length() > 2 * 1024 * 1024) {
            return mapOf("result" to "error", "message" to "File is too large (>2MB) to read into memory.")
        }

        return try {
            val lines = file.bufferedReader().useLines { linesSequence ->
                linesSequence.take(maxLines).toList()
            }
            val content = lines.joinToString("\n")
            mapOf("result" to "success", "content" to content, "linesRead" to lines.size.toString())
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to read file: ${e.message}")
        }
    }

}
