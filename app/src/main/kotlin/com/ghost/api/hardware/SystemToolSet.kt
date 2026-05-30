package com.ghost.api.hardware

import android.content.ContentValues
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
    private var appListCache: List<AppInfo>? = null

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
        if (appListCache == null) {
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            appListCache = packageManager.queryIntentActivities(intent, 0).map {
                AppInfo(it.loadLabel(packageManager).toString(), it.activityInfo.packageName)
            }
        }
        return appListCache ?: emptyList()
    }

    @Tool(description = "Sets an alarm")
    fun alarm(
        @ToolParam(description = "Hour in 24h format") hour: Int, 
        @ToolParam(description = "Minutes") minutes: Int, 
        @ToolParam(description = "Optional label") label: String = ""
    ): Map<String, String> {
        return try {
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minutes)
                set(java.util.Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }
            val intent = Intent(context, GhostAlarmReceiver::class.java).apply {
                putExtra("LABEL", label)
            }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = android.app.PendingIntent.getBroadcast(context, hour * 60 + minutes, intent, flags)
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            mapOf("result" to "success", "message" to "Background alarm scheduled for $hour:$minutes")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to "Failed to set alarm: ${e.message}")
        }
    }

    @Tool(description = "Sets a timer")
    fun timer(
        @ToolParam(description = "Length in seconds") seconds: Int, 
        @ToolParam(description = "Optional label") label: String = ""
    ): Map<String, String> {
        return try {
            val targetTime = System.currentTimeMillis() + (seconds * 1000L)
            val intent = Intent(context, GhostAlarmReceiver::class.java).apply {
                putExtra("LABEL", label.ifEmpty { "Timer" })
            }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = android.app.PendingIntent.getBroadcast(context, seconds, intent, flags)
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, targetTime, pendingIntent)
            mapOf("result" to "success", "message" to "Background timer scheduled for $seconds seconds")
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
            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.DTSTART, System.currentTimeMillis())
                put(android.provider.CalendarContract.Events.DTEND, System.currentTimeMillis() + minutes * 60 * 1000)
                put(android.provider.CalendarContract.Events.TITLE, title)
                put(android.provider.CalendarContract.Events.DESCRIPTION, description)
                put(android.provider.CalendarContract.Events.CALENDAR_ID, 1) // Default primary calendar
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            context.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
            mapOf("result" to "success", "message" to "Calendar event created silently")
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

    @Tool(description = "Takes a screenshot")
    fun take_screenshot(): Map<String, String> {
        val intent = Intent("com.ghost.api.ACTION_REQUEST_SCREENSHOT").setPackage(context.packageName)
        context.sendBroadcast(intent)
        return mapOf("result" to "success", "message" to "Screenshot requested")
    }

    @Tool(description = "Click UI element")
    fun click(
        @ToolParam(description = "Text or description of element to click") target: String
    ): Map<String, String> {
        val success = com.ghost.api.GemmaAccessibilityService.instance?.performClick(target) ?: false
        return if (success) mapOf("result" to "success") else mapOf("result" to "error")
    }

    @Tool(description = "Scroll screen")
    fun scroll(
        @ToolParam(description = "Direction: up, down, left, right") direction: String
    ): Map<String, String> {
        val success = com.ghost.api.GemmaAccessibilityService.instance?.performScroll(direction) ?: false
        return if (success) mapOf("result" to "success") else mapOf("result" to "error")
    }

    @Tool(description = "Navigate Android")
    fun navigate(
        @ToolParam(description = "Action: home, back, recents, notifications") action: String
    ): Map<String, String> {
        val success = com.ghost.api.GemmaAccessibilityService.instance?.performGlobal(action) ?: false
        return if (success) mapOf("result" to "success") else mapOf("result" to "error")
    }

    @Tool(description = "Read current screen text semantics")
    fun read_screen(): Map<String, String> {
        val content = com.ghost.api.GemmaAccessibilityService.getSemantics() ?: "[[SCREEN NOT ACCESSIBLE]]"
        return mapOf("result" to "success", "content" to content)
    }

    @Tool(description = "Type text into focused element")
    fun type(text: String): Map<String, String> {
        val success = com.ghost.api.GemmaAccessibilityService.instance?.performType(text) ?: false
        return if (success) mapOf("result" to "success") else mapOf("result" to "error")
    }

    @Tool(description = "Open email app to compose an email")
    fun open_email(email: String, subject: String = "", body: String = ""): Map<String, String> {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return mapOf("result" to "success", "message" to "Email app opened")
    }

    @Tool(description = "Bash command")
    fun bash(
        @ToolParam(description = "Shell command to execute") command: String
    ): Map<String, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            mapOf("result" to "success", "output" to output.take(2000))
        } catch (e: Exception) { mapOf("result" to "error", "message" to e.message.toString()) }
    }

    @Tool(description = "Saves memory")
    fun remember(
        @ToolParam(description = "Memory title") title: String, 
        @ToolParam(description = "Memory content") content: String
    ): Map<String, String> {
        DiaryManager(context).storeMemory(title, content)
        return mapOf("result" to "success", "message" to "Stored: $title")
    }

    @Tool(description = "Recalls memory")
    fun recall(
        @ToolParam(description = "Search query") query: String
    ): Map<String, String> {
        val memories = DiaryManager(context).searchMemories(query)
        return mapOf("result" to "success", "memories" to memories.joinToString("\n---\n"))
    }
}
