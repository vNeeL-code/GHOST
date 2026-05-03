package com.gemma.api.hardware

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

/**
 * System Tools - Apps, Media, and Volume aligned with LiteRT @Tool Architecture
 */
class SystemToolSet(private val context: Context) : ToolSet {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val packageManager: PackageManager = context.packageManager
    private var appListCache: List<AppInfo>? = null

    data class AppInfo(val label: String, val packageName: String)

    @Tool(description = "Opens an app by its name")
    fun app(
        @ToolParam(description = "The name of the app to launch (e.g., Spotify, Chrome)") name: String
    ): Map<String, String> {
        val apps = getInstalledApps()
        val bestMatch = findBestMatch(name, apps)

        return if (bestMatch != null) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(bestMatch.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (android.provider.Settings.canDrawOverlays(context)) {
                        context.startActivity(intent)
                        mapOf("result" to "success", "message" to "Launched ${bestMatch.label}")
                    } else {
                        val pendingIntent = android.app.PendingIntent.getActivity(
                            context, bestMatch.packageName.hashCode(), intent, 
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
                        val builder = android.app.Notification.Builder(context, "gemma_actions")
                            .setSmallIcon(android.R.drawable.sym_def_app_icon)
                            .setContentTitle("Launch ${bestMatch.label}")
                            .setContentText("Tap to open app")
                            .setContentIntent(pendingIntent)
                            .setAutoCancel(true)
                        notificationManager.notify(bestMatch.packageName.hashCode(), builder.build())
                        mapOf("result" to "success", "message" to "Posted notification to launch ${bestMatch.label}")
                    }
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

    @Tool(description = "Controls media playback (Play, Pause, Next, Previous, Stop)")
    fun media(
        @ToolParam(description = "The media action to perform (e.g., PLAY, PAUSE, NEXT, PREV)") action: String
    ): Map<String, String> {
        val keyEvent = when (action.uppercase(Locale.ROOT)) {
            "PLAY", "PAUSE", "PLAYPAUSE", "TOGGLE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "NEXT", "SKIP" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "PREVIOUS", "PREV", "BACK" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "STOP" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return mapOf("result" to "error", "message" to "Unknown command")
        }

        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyEvent))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyEvent))
            mapOf("result" to "success", "message" to "Media Action Sent: $action")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "Failed"))
        }
    }

    private fun getInstalledApps(): List<AppInfo> {
        if (appListCache == null) {
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            appListCache = resolveInfos.map {
                AppInfo(it.loadLabel(packageManager).toString(), it.activityInfo.packageName)
            }
        }
        return appListCache ?: emptyList()
    }

    private fun findBestMatch(query: String, apps: List<AppInfo>): AppInfo? {
        val lowerQuery = query.lowercase(Locale.ROOT)
        apps.find { it.label.equals(query, ignoreCase = true) }?.let { return it }
        apps.find { it.label.startsWith(query, ignoreCase = true) }?.let { return it }
        apps.find { it.label.contains(query, ignoreCase = true) }?.let { return it }
        return null
    }

    @Tool(description = "Sets an alarm")
    fun alarm(
        @ToolParam(description = "The hour of the alarm (0-23)") hour: Int,
        @ToolParam(description = "The minutes of the alarm (0-59)") minutes: Int,
        @ToolParam(description = "Optional label for the alarm") label: String = ""
    ): Map<String, String> {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                putExtra(AlarmClock.EXTRA_VIBRATE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            mapOf("result" to "success", "message" to "Alarm set for $hour:$minutes")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "error").toString())
        }
    }

    @Tool(description = "Sets a countdown timer")
    fun timer(
        @ToolParam(description = "The duration in seconds") seconds: Int,
        @ToolParam(description = "Optional label") label: String = ""
    ): Map<String, String> {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            mapOf("result" to "success", "message" to "Timer set for $seconds seconds")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "error").toString())
        }
    }

    private fun getDefaultCalendarId(): Long? {
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            null, null, null
        ) ?: return null
        cursor.use {
            while (it.moveToNext()) {
                if (it.getInt(1) == 1) return it.getLong(0)
            }
            if (it.moveToFirst()) return it.getLong(0)
        }
        return null
    }

    @Tool(description = "Creates a calendar event")
    fun calendar(
        @ToolParam(description = "Event title") title: String,
        @ToolParam(description = "Event description") description: String = "",
        @ToolParam(description = "Duration in minutes") minutes: Int = 30
    ): Map<String, String> {
        return try {
            val calendarId = getDefaultCalendarId() ?: return mapOf("result" to "error", "message" to "No account")
            val startTimeMillis = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, startTimeMillis)
                put(CalendarContract.Events.DTEND, startTimeMillis + minutes * 60 * 1000L)
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) mapOf("result" to "success", "message" to "Created $title")
            else mapOf("result" to "error", "message" to "Insert failed")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "fail").toString())
        }
    }

    @Tool(description = "Reads upcoming calendar events")
    fun read_calendar(
        @ToolParam(description = "Days ahead to check") days: Int = 7
    ): Map<String, String> {
        return try {
            val calendarId = getDefaultCalendarId() ?: return mapOf("result" to "error", "message" to "No account")
            val now = System.currentTimeMillis()
            val end = now + (days * 24 * 60 * 60 * 1000L)
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.DESCRIPTION),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(calendarId.toString(), now.toString(), end.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            ) ?: return mapOf("result" to "error", "message" to "Query failed")
            val events = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    events.add("${it.getString(0)} from ${it.getLong(1)} to ${it.getLong(2)}")
                }
            }
            mapOf("result" to "success", "events" to if (events.isEmpty()) "None" else events.joinToString("\n"))
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "fail").toString())
        }
    }

    @Tool(description = "Takes a screenshot")
    fun take_screenshot(): Map<String, String> {
        return try {
            val intent = Intent(context, com.gemma.api.GemmaService::class.java).apply {
                action = "com.gemma.api.ACTION_REQUEST_SCREENSHOT"
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            mapOf("result" to "success", "message" to "Screenshot requested")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "failed").toString())
        }
    }
    @Tool(description = "Click a button or text visible on the screen")
    fun click(
        @ToolParam(description = "The exact text or description of the UI element to click") target: String
    ): Map<String, String> {
        val success = com.gemma.api.GemmaAccessibilityService.instance?.performClick(target) ?: false
        return if (success) {
            mapOf("result" to "success", "message" to "Clicked on \'$target\'")
        } else {
            mapOf("result" to "error", "message" to "Failed to click \'$target\', element not found or Accessibility disabled.")
        }
    }

    @Tool(description = "Scrolls the currently active visual screen")
    fun scroll(
        @ToolParam(description = "Direction to scroll: UP or DOWN") direction: String
    ): Map<String, String> {
        val success = com.gemma.api.GemmaAccessibilityService.instance?.performScroll(direction) ?: false
        return if (success) {
            mapOf("result" to "success", "message" to "Scrolled screen $direction")
        } else {
            mapOf("result" to "error", "message" to "Failed to scroll, no scrollable nodes or Accessibility disabled.")
        }
    }

    @Tool(description = "Performs global Android navigation")
    fun navigate(
        @ToolParam(description = "Global action: HOME, BACK, RECENTS, NOTIFICATIONS, QUICK_SETTINGS") action: String
    ): Map<String, String> {
        val success = com.gemma.api.GemmaAccessibilityService.instance?.performGlobal(action) ?: false
        return if (success) {
            mapOf("result" to "success", "message" to "Navigated $action")
        } else {
            mapOf("result" to "error", "message" to "Navigation failed")
        }
    }

    @Tool(description = "Returns a structural dump of the current app screen to 'read' what is open")
    fun resources_screen(): Map<String, String> {
        val dump = com.gemma.api.GemmaAccessibilityService.instance?.getSemanticScreenDump() ?: "Accessibility Service not bound."
        return mapOf("result" to "success", "screen_state" to dump)
    }

    @Tool(description = "Executes a bash/shell command on the device and returns the output.")
    fun bash(
        @ToolParam(description = "The command to execute (e.g., 'id', 'ls /sdcard', 'curl -I google.com')") command: String
    ): Map<String, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                mapOf("result" to "success", "output" to output.take(4000))
            } else {
                mapOf("result" to "error", "message" to "Exit code $exitCode", "output" to output, "error" to error)
            }
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "Execution failed"))
        }
    }
}
