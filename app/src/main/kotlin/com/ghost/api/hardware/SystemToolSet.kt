package com.ghost.api.hardware

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import timber.log.Timber
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
        val query = name.trim()
        val queryLower = query.lowercase(Locale.ROOT)
        val apps = getInstalledApps()

        // 1. Exact label match
        var bestMatch = apps.find { it.label.equals(query, ignoreCase = true) }

        // 2. Starts with label
        if (bestMatch == null) {
            bestMatch = apps.find { it.label.startsWith(query, ignoreCase = true) }
        }

        // 3. Contains label
        if (bestMatch == null) {
            bestMatch = apps.find { it.label.contains(query, ignoreCase = true) }
        }

        // 4. Package name match
        if (bestMatch == null) {
            bestMatch = apps.find { it.packageName.contains(query, ignoreCase = true) }
        }

        // 5. Alias dictionary match
        if (bestMatch == null) {
            for ((alias, candidates) in APP_ALIASES) {
                if (queryLower == alias || queryLower.contains(alias)) {
                    for (candidate in candidates) {
                        bestMatch = apps.find { it.label.contains(candidate, ignoreCase = true) }
                        if (bestMatch != null) break
                    }
                    if (bestMatch != null) break
                }
            }
        }

        if (bestMatch != null) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(bestMatch.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return mapOf("result" to "success", "message" to "Launched ${bestMatch.label}")
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to launch package ${bestMatch.packageName}")
            }
        }

        // 6. System Intent Fallbacks (Handles standard system apps regardless of OEM package name)
        val systemFallbackIntent = when (queryLower) {
            "calendar" -> Intent(Intent.ACTION_VIEW).setData(android.net.Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}"))
            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "clock", "alarm", "alarms" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "browser", "chrome", "internet" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
            "music", "spotify" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
            "gallery", "photos" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
            "mail", "email", "gmail" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL)
            "maps" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS)
            "files", "file manager" -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            else -> null
        }

        if (systemFallbackIntent != null) {
            try {
                systemFallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(systemFallbackIntent)
                return mapOf("result" to "success", "message" to "Launched system $query")
            } catch (e: Exception) {
                Timber.w(e, "System fallback launch failed for $query")
            }
        }

        return mapOf("result" to "error", "message" to "App not found matching '$name'")
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
        val timeStr = "${hour.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
        val now = System.currentTimeMillis()
        synchronized(APP_ALIASES) {
            if (hour == lastAlarmHour && minutes == lastAlarmMinute && (now - lastAlarmTimestamp) < 20_000L) {
                Timber.w("SystemToolSet: Suppressed duplicate alarm intent for $timeStr within 20s debounce window")
                return mapOf("result" to "success", "message" to "Alarm set for $timeStr${if (label.isNotBlank()) " ($label)" else ""} (already scheduled)")
            }
            lastAlarmHour = hour
            lastAlarmMinute = minutes
            lastAlarmTimestamp = now
        }

        com.ghost.api.GemmaService.instance?.showWorkSignal("ALARM", 1500)
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            mapOf("result" to "success", "message" to "Alarm set for $timeStr${if (label.isNotBlank()) " ($label)" else ""}")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to set alarm: ${e.message}")
        }
    }

    @Tool(description = "Sets a timer for the specified duration")
    fun timer(
        @ToolParam(description = "Total duration in precise seconds (e.g. 5 minutes = 300, 1 hour = 3600)") seconds: Int, 
        @ToolParam(description = "Optional label") label: String = ""
    ): Map<String, String> {
        val safeSeconds = seconds.coerceAtLeast(1)
        val formattedDur = formatDuration(safeSeconds)
        val now = System.currentTimeMillis()
        synchronized(APP_ALIASES) {
            if (safeSeconds == lastTimerSeconds && (now - lastTimerTimestamp) < 20_000L) {
                Timber.w("SystemToolSet: Suppressed duplicate timer intent for ${safeSeconds}s within 20s debounce window")
                return mapOf("result" to "success", "message" to "Timer scheduled for $formattedDur (${safeSeconds}s)${if (label.isNotBlank()) " ($label)" else ""} (already scheduled)")
            }
            lastTimerSeconds = safeSeconds
            lastTimerTimestamp = now
        }

        com.ghost.api.GemmaService.instance?.showWorkSignal("TIMER", 1500)
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, safeSeconds)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            mapOf("result" to "success", "message" to "Timer scheduled for $formattedDur (${safeSeconds}s)${if (label.isNotBlank()) " ($label)" else ""} in system Clock app")
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
            val projection = arrayOf(CalendarContract.Calendars._ID)
            val selection = "${CalendarContract.Calendars.IS_PRIMARY} = 1"
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection, selection, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    calId = cursor.getLong(0)
                } else {
                    context.contentResolver.query(
                        CalendarContract.Calendars.CONTENT_URI,
                        projection, null, null, null
                    )?.use { fallbackCursor ->
                        if (fallbackCursor.moveToFirst()) calId = fallbackCursor.getLong(0)
                    }
                }
            }

            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.DTSTART, System.currentTimeMillis())
                put(CalendarContract.Events.DTEND, System.currentTimeMillis() + minutes * 60 * 1000)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
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
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART
            )
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(now.toString(), later.toString())
            
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection, selection, selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
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
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            
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
            val episodicMemories = diaryManager.searchMemories(query)
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

    @Tool(description = "Replies directly to an incoming notification (e.g. WhatsApp, Messenger, Telegram, Signal, SMS) in the background without opening the app or waking the screen")
    fun reply_notification(
        @ToolParam(description = "The package name or app name (e.g. 'whatsapp', 'com.whatsapp', 'telegram', 'messenger')") packageName: String,
        @ToolParam(description = "The message text to send in reply") message: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("MSG", 1500)
        return try {
            val allNotifs = com.ghost.api.GemmaNotificationListener.getAllNotifications()
            val cleanQuery = packageName.trim().lowercase(Locale.ROOT)
            val targetPkg = if (cleanQuery.contains(".")) {
                cleanQuery
            } else {
                allNotifs.find { 
                    it.packageName.lowercase(Locale.ROOT).contains(cleanQuery) || 
                    it.title.lowercase(Locale.ROOT).contains(cleanQuery) 
                }?.packageName ?: cleanQuery
            }

            val success = com.ghost.api.GemmaNotificationListener.replyTo(targetPkg, message.trim())
            if (success) {
                mapOf("result" to "success", "message" to "Replied to $targetPkg: \"$message\"")
            } else {
                mapOf("result" to "error", "message" to "No active notification reply action found for '$packageName'. Ensure an incoming message notification is present.")
            }
        } catch (e: Exception) {
            Timber.e(e, "reply_notification failed")
            mapOf("result" to "error", "message" to "Failed to send notification reply: ${e.message}")
        }
    }

    @Tool(description = "Sends a WhatsApp message directly or opens a chat with prefilled text")
    fun send_whatsapp_message(
        @ToolParam(description = "Message text to send") message: String,
        @ToolParam(description = "Optional phone number with country code (e.g. +447123456789) or empty to choose contact") phoneNumber: String = ""
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("MSG", 1500)
        return try {
            val cleanPhone = phoneNumber.replace(Regex("[^0-9+]"), "")
            val intent = if (cleanPhone.isNotBlank()) {
                Intent(Intent.ACTION_VIEW).apply {
                    val encodedMsg = java.net.URLEncoder.encode(message, "UTF-8")
                    data = android.net.Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMsg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    `package` = "com.whatsapp"
                    putExtra(Intent.EXTRA_TEXT, message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            mapOf("result" to "success", "message" to "WhatsApp triggered with message: \"$message\"")
        } catch (e: Exception) {
            Timber.e(e, "send_whatsapp_message failed")
            mapOf("result" to "error", "message" to "Failed to open WhatsApp: ${e.message}")
        }
    }

    companion object {
        private var lastAlarmHour: Int? = null
        private var lastAlarmMinute: Int? = null
        private var lastAlarmTimestamp: Long = 0L

        private var lastTimerSeconds: Int? = null
        private var lastTimerTimestamp: Long = 0L

        private val WORD_NUMBERS = mapOf(
            "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
            "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
            "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "twenty-five" to 25,
            "thirty" to 30, "forty" to 40, "forty-five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90
        )

        private val APP_ALIASES = mapOf(
            "calendar" to listOf("Calendar", "Google Calendar"),
            "chrome" to listOf("Chrome", "Browser", "Internet"),
            "browser" to listOf("Chrome", "Browser", "Internet"),
            "spotify" to listOf("Spotify", "Music", "YouTube Music"),
            "music" to listOf("Spotify", "Music", "YouTube Music"),
            "youtube" to listOf("YouTube"),
            "camera" to listOf("Camera"),
            "clock" to listOf("Clock", "DeskClock"),
            "alarm" to listOf("Clock", "DeskClock"),
            "alarms" to listOf("Clock", "DeskClock"),
            "settings" to listOf("Settings"),
            "gallery" to listOf("Photos", "Gallery"),
            "photos" to listOf("Photos", "Gallery"),
            "calculator" to listOf("Calculator"),
            "notes" to listOf("Notes", "Keep", "Google Keep"),
            "keep" to listOf("Google Keep", "Keep"),
            "files" to listOf("Files", "My Files", "File Manager"),
            "maps" to listOf("Maps", "Google Maps"),
            "mail" to listOf("Gmail", "Mail", "Email"),
            "email" to listOf("Gmail", "Mail", "Email"),
            "gmail" to listOf("Gmail")
        )

        /**
         * Formats a duration in seconds into a natural human-readable phrase (e.g. "1 hour", "5 minutes", "1 minute 30 seconds").
         */
        fun formatDuration(seconds: Int): String {
            if (seconds <= 0) return "0 seconds"
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            val parts = mutableListOf<String>()
            if (h > 0) parts.add("$h ${if (h == 1) "hour" else "hours"}")
            if (m > 0) parts.add("$m ${if (m == 1) "minute" else "minutes"}")
            if (s > 0 || parts.isEmpty()) parts.add("$s ${if (s == 1) "second" else "seconds"}")
            return parts.joinToString(" ")
        }

        /**
         * Parses natural-language and numeric durations into total seconds.
         * Supports:
         * - "an hour", "one hour", "1 hour", "2 hours", "1.5 hours"
         * - "half an hour", "half hour", "quarter of an hour", "quarter hour"
         * - "an hour and a half", "one and a half hours"
         * - "a minute", "5 minutes", "10 mins", "30 seconds", "45s", "10m", "2h"
         * - Compound durations: "1 hour 30 minutes", "2m 30s"
         */
        fun parseDurationSeconds(text: String): Int? {
            val clean = text.trim().lowercase(Locale.ROOT)
                .replace(Regex("""\bforty\s+five\b"""), "forty-five")
                .replace(Regex("""\btwenty\s+five\b"""), "twenty-five")
            if (clean.isBlank()) return null

            // Pure integer string -> treat as seconds
            clean.toIntOrNull()?.let { if (it > 0) return it }

            // Special natural phrases
            if (Regex("""\b(?:an?|one|1)\s+hour\s+and\s+a\s+half\b""").containsMatchIn(clean) ||
                Regex("""\b(?:one|1)\s+and\s+a\s+half\s+hours?\b""").containsMatchIn(clean)) {
                return 5400
            }
            if (Regex("""\bhalf\s+(?:an?\s+)?hours?\b""").containsMatchIn(clean)) {
                return 1800
            }
            if (Regex("""\bquarter\s+(?:of\s+an?\s+|an?\s+)?hours?\b""").containsMatchIn(clean)) {
                return 900
            }
            if (Regex("""\bhalf\s+(?:a\s+)?minutes?\b""").containsMatchIn(clean)) {
                return 30
            }

            var totalSeconds = 0
            var matchedAny = false

            // 1. Numeric (int or decimal) + unit: e.g. "1.5 hours", "1 hour 30 minutes", "10m", "45s"
            val numericUnitRegex = Regex("""\b(\d+(?:\.\d+)?)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""")
            for (m in numericUnitRegex.findAll(clean)) {
                val amount = m.groupValues[1].toDoubleOrNull() ?: continue
                val unit = m.groupValues[2]
                val multiplier = when {
                    unit.startsWith("h") -> 3600.0
                    unit.startsWith("m") -> 60.0
                    else -> 1.0
                }
                totalSeconds += (amount * multiplier).toInt()
                matchedAny = true
            }
            if (matchedAny && totalSeconds > 0) {
                return totalSeconds
            }

            // 2. Word-number + unit: e.g. "an hour", "a minute", "five minutes", "ten seconds"
            val wordUnitRegex = Regex("""\b(a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|twenty-five|thirty|forty|forty-five|fifty|sixty|ninety)\s+(hours?|hrs?|minutes?|mins?|seconds?|secs?)\b""")
            for (m in wordUnitRegex.findAll(clean)) {
                val word = m.groupValues[1]
                val amount = WORD_NUMBERS[word] ?: continue
                val unit = m.groupValues[2]
                val multiplier = when {
                    unit.startsWith("h") -> 3600
                    unit.startsWith("m") -> 60
                    else -> 1
                }
                totalSeconds += amount * multiplier
                matchedAny = true
            }
            if (matchedAny && totalSeconds > 0) {
                return totalSeconds
            }

            return null
        }

        /**
         * Robustly parses (seconds, label) from flexible timer parameter maps.
         * Handles:
         * - Explicit seconds/minutes/hours: {"seconds": 3600}, {"minutes": 60}, {"hours": 1}, {"hours": 1, "minutes": 30}
         * - Natural duration strings in seconds/duration/time/input: {"duration": "1 hour"}, {"input": "an hour"}
         * - Key-value strings: "seconds=3600", "hours=1"
         */
        fun parseTimerParams(params: Map<String, Any?>): Pair<Int, String>? {
            var label = (params["label"] ?: params["message"] ?: params["note"] ?: params["title"])?.toString()?.trim() ?: ""

            val explicitHours = (params["hours"] ?: params["hour"] ?: params["hr"])?.toString()?.toDoubleOrNull()
            val explicitMinutes = (params["minutes"] ?: params["minute"] ?: params["min"])?.toString()?.toDoubleOrNull()
            val rawSecondsObj = params["seconds"] ?: params["second"] ?: params["sec"] ?: params["duration"] ?: params["length"]
            val explicitSeconds = rawSecondsObj?.toString()?.toDoubleOrNull()

            if (explicitHours != null || explicitMinutes != null || explicitSeconds != null) {
                val total = ((explicitHours ?: 0.0) * 3600.0 +
                             (explicitMinutes ?: 0.0) * 60.0 +
                             (explicitSeconds ?: 0.0)).toInt()
                if (total > 0) {
                    return Pair(total, label)
                }
            }

            // Try parsing natural language duration from any candidate parameter
            val candidates = listOfNotNull(
                rawSecondsObj?.toString(),
                params["time"]?.toString(),
                params["input"]?.toString(),
                params["args"]?.toString(),
                label.takeIf { it.isNotBlank() }
            )
            for (candidate in candidates) {
                val parsedSec = parseDurationSeconds(candidate)
                if (parsedSec != null && parsedSec > 0) {
                    if (candidate == label) label = ""
                    return Pair(parsedSec, label)
                }
            }

            return null
        }

        /**
         * Robustly parses (hour, minutes, label) from flexible parameter maps.
         * Handles:
         * - 12-hour: "6pm", "6:00 PM", "6 pm", "6:30pm"
         * - 24-hour: "18:00", "18:30", "18"
         * - Explicit parameters: "hour": 6, "am_pm": "pm", "minutes": 30
         * - Raw input: "6pm Debug GHOST"
         *
         * Returns Triple(hour, minutes, label) or null if no valid time could be parsed.
         */
        fun parseAlarmParams(params: Map<String, Any?>): Triple<Int, Int, String>? {
            var label = (params["label"] ?: params["message"] ?: params["note"])?.toString() ?: ""
            val timeCandidate = (params["time"] ?: params["hour"] ?: params["input"])?.toString()?.trim() ?: ""
            val rawMinutes = params["minutes"]?.toString()?.toIntOrNull()
            val amPmParam = (params["am_pm"] ?: params["period"] ?: params["ampm"])?.toString()?.lowercase()?.trim()

            // 0. Deterministic TimePreprocessor pass (handles stuttering zeros e.g. 8:0000 PM, 2:000)
            val combinedCandidate = if (amPmParam != null && !timeCandidate.contains(amPmParam)) "$timeCandidate $amPmParam" else timeCandidate
            val preprocessed = com.ghost.api.logic.TimePreprocessor.parseTime(combinedCandidate)
            if (preprocessed != null) {
                val finalMinutes = rawMinutes ?: preprocessed.minute
                if (label.isBlank() && params["input"] != null) {
                    label = timeCandidate.replace(preprocessed.rawMatch, "").trim()
                }
                return Triple(preprocessed.hour24, finalMinutes, label)
            }

            // 1. Match "HH:MM" or "H:MM" with optional AM/PM (e.g. "6:00 PM", "18:00", "6:30pm")
            val colonPattern = Regex("""(?i)\b(\d{1,2}):(\d{2})\s*([ap]\.?m\.?)?\b""")
            val colonMatch = colonPattern.find(timeCandidate)
            if (colonMatch != null) {
                var h = colonMatch.groupValues[1].toInt()
                val m = colonMatch.groupValues[2].toInt()
                val meridiem = (colonMatch.groupValues[3].ifBlank { amPmParam ?: "" }).lowercase().replace(".", "")
                if (meridiem == "pm" && h < 12) h += 12
                else if (meridiem == "am" && h == 12) h = 0
                if (label.isBlank() && params["input"] != null) {
                    label = timeCandidate.replace(colonMatch.value, "").trim()
                }
                return Triple(h.coerceIn(0, 23), m.coerceIn(0, 59), label)
            }

            // 2. Match "6pm", "6 am", "11pm"
            val simpleAmPmPattern = Regex("""(?i)\b(\d{1,2})\s*([ap]\.?m\.?)\b""")
            val simpleMatch = simpleAmPmPattern.find(timeCandidate)
            if (simpleMatch != null) {
                var h = simpleMatch.groupValues[1].toInt()
                val m = rawMinutes ?: 0
                val meridiem = simpleMatch.groupValues[2].lowercase().replace(".", "")
                if (meridiem == "pm" && h < 12) h += 12
                else if (meridiem == "am" && h == 12) h = 0
                if (label.isBlank() && params["input"] != null) {
                    label = timeCandidate.replace(simpleMatch.value, "").trim()
                }
                return Triple(h.coerceIn(0, 23), m.coerceIn(0, 59), label)
            }

            // 3. Integer hour param (with possible am_pm param)
            val directHour = params["hour"]?.toString()?.toIntOrNull()
            if (directHour != null) {
                var h = directHour
                val m = rawMinutes ?: 0
                if (amPmParam?.contains("pm") == true && h < 12) h += 12
                else if (amPmParam?.contains("am") == true && h == 12) h = 0
                return Triple(h.coerceIn(0, 23), m.coerceIn(0, 59), label)
            }

            // 4. Standalone number in time candidate (e.g. "18" or "8")
            val numberPattern = Regex("""\b(\d{1,2})\b""")
            val numberMatch = numberPattern.find(timeCandidate)
            if (numberMatch != null) {
                var h = numberMatch.groupValues[1].toInt()
                val m = rawMinutes ?: 0
                if (amPmParam?.contains("pm") == true && h < 12) h += 12
                else if (amPmParam?.contains("am") == true && h == 12) h = 0
                if (label.isBlank() && params["input"] != null) {
                    label = timeCandidate.replace(numberMatch.value, "").trim()
                }
                return Triple(h.coerceIn(0, 23), m.coerceIn(0, 59), label)
            }

            return null
        }
    }
}
