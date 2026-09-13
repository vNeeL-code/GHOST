package com.ghost.api.hardware

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
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
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
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
}
