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

/**
 * System Tools - Apps, Media, and Volume
 */
class SystemToolSet(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val packageManager: PackageManager = context.packageManager

    // Cache of installed apps (lazy load or refresh on demand)
    private var appListCache: List<AppInfo>? = null

    data class AppInfo(val label: String, val packageName: String)

    /**
     * Open an app by name (Fuzzy Match)
     */
    fun openApp(query: String): String {
        val apps = getInstalledApps()
        val bestMatch = findBestMatch(query, apps)

        return if (bestMatch != null) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(bestMatch.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    
                    // 1. Try Direct Launch (if permitted)
                    if (android.provider.Settings.canDrawOverlays(context)) {
                        try {
                            context.startActivity(intent)
                            return "Launched ${bestMatch.label}"
                        } catch (e: Exception) {
                            Timber.w(e, "Direct launch failed, trying fallback")
                        }
                    } else {
                         Timber.w("No Overlay Permission - using Notification Fallback for App Launch")
                    }

                    // 2. Fallback: Notification
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        context, 
                        bestMatch.packageName.hashCode(), 
                        intent, 
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )

                    val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
                    val channelId = "gemma_actions"

                     // Channel creation handled by GemmaService central logic
                     

                    val builder = android.app.Notification.Builder(context, channelId)
                        .setSmallIcon(android.R.drawable.sym_def_app_icon)
                        .setContentTitle("Launch ${bestMatch.label}")
                        .setContentText("Tap to open app")
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        
                    notificationManager.notify(bestMatch.packageName.hashCode(), builder.build())
                    
                    "Posted notification to launch ${bestMatch.label} (Background restriction)"
                } else {
                    "Could not launch ${bestMatch.label} (No Intent found)"
                }
            } catch (e: Exception) {
                Timber.e(e, "Launch failed")
                "Failed to launch ${bestMatch.label}: ${e.message}"
            }
        } else {
            "App not found matching '$query'"
        }
    }

    /**
     * Media Controls using AudioManager key injection
     */
    fun mediaControl(action: String): String {
        val keyEvent = when (action.uppercase(Locale.ROOT)) {
            "PLAY", "PAUSE", "PLAYPAUSE", "TOGGLE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "NEXT", "SKIP" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "PREVIOUS", "PREV", "BACK" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "STOP" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return "Unknown media command: $action"
        }

        return try {
            // Emulate press down and up
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyEvent)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyEvent)
            
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
            
            "Media Action Sent: $action"
        } catch (e: Exception) {
            Timber.e(e, "Media control failed")
            "Media failed: ${e.message}"
        }
    }

    private fun getInstalledApps(): List<AppInfo> {
        if (appListCache == null) {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            appListCache = resolveInfos.map {
                val label = it.loadLabel(packageManager).toString()
                val pkg = it.activityInfo.packageName
                AppInfo(label, pkg)
            }
        }
        return appListCache ?: emptyList()
    }

    private fun findBestMatch(query: String, apps: List<AppInfo>): AppInfo? {
        val lowerQuery = query.lowercase(Locale.ROOT)
        // 1. Exact Match
        apps.find { it.label.equals(query, ignoreCase = true) }?.let { return it }
        
        // 2. Starts With
        apps.find { it.label.startsWith(query, ignoreCase = true) }?.let { return it }
        
        // 3. Contains
        apps.find { it.label.contains(query, ignoreCase = true) }?.let { return it }
        
        // 4. Levenshtein for typos? (Simple implementation for now: minimal checks)
        // If simple checks fail, return null for now to avoid wrong launches.
        return null
    }


    /**
     * Set an alarm via Android AlarmClock intent
     */
    fun setAlarm(hour: Int, minutes: Int, label: String = ""): String {
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
            "Alarm set for $timeStr${if (label.isNotBlank()) " ($label)" else ""}"
        } catch (e: Exception) {
            Timber.e(e, "Set alarm failed")
            "Failed to set alarm: ${e.message}"
        }
    }

    /**
     * Set a countdown timer via Android AlarmClock intent
     */
    fun setTimer(seconds: Int, label: String = ""): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val display = if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
            "Timer set for $display${if (label.isNotBlank()) " ($label)" else ""}"
        } catch (e: Exception) {
            Timber.e(e, "Set timer failed")
            "Failed to set timer: ${e.message}"
        }
    }

    /**
     * Create a calendar event silently via ContentProvider.
     * Used by both the CALENDAR tool and diary-to-calendar integration.
     */
    fun createCalendarEvent(
        title: String,
        description: String = "",
        startTimeMillis: Long = System.currentTimeMillis(),
        durationMinutes: Int = 30
    ): String {
        return try {
            val calendarId = getDefaultCalendarId()
                ?: return "No calendar account found — user needs to set up a Google account"

            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, startTimeMillis)
                put(CalendarContract.Events.DTEND, startTimeMillis + durationMinutes * 60 * 1000L)
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                Timber.i("Calendar event created: $title (uri=$uri)")
                "Calendar event created: $title"
            } else {
                "Failed to create calendar event (insert returned null)"
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Calendar permission denied")
            "Calendar permission not granted — user needs to allow calendar access"
        } catch (e: Exception) {
            Timber.e(e, "Calendar event creation failed")
            "Failed to create calendar event: ${e.message}"
        }
    }

    /**
     * Find the default (primary) calendar ID on the device
     */
    private fun getDefaultCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null, null, null
        ) ?: return null

        cursor.use {
            // Try to find primary calendar first
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val isPrimary = it.getInt(1) == 1
                if (isPrimary) return id
            }
            // Fallback: use first available calendar
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        return null
    }

    /**
     * Read upcoming calendar events.
     * Tool Name: READ_CALENDAR
     */
    fun readCalendarEvents(daysAhead: Int = 7): String {
        return try {
            val calendarId = getDefaultCalendarId()
                ?: return "No calendar account found — user needs to set up a Google account"

            val now = System.currentTimeMillis()
            val end = now + (daysAhead * 24 * 60 * 60 * 1000L)

            val projection = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.DESCRIPTION
            )

            val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(calendarId.toString(), now.toString(), end.toString())

            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            ) ?: return "Failed to query calendar"

            val events = mutableListOf<String>()
            val dateFormat = java.text.SimpleDateFormat("MMM dd, HH:mm", Locale.US)

            cursor.use {
                while (it.moveToNext()) {
                    val title = it.getString(0) ?: "Untitled Event"
                    val dtStart = it.getLong(1)
                    val dtEnd = it.getLong(2)
                    val desc = it.getString(3) ?: ""
                    
                    val timeStartStr = dateFormat.format(java.util.Date(dtStart))
                    val timeEndStr = java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(dtEnd))
                    
                    val eventStr = "- [$timeStartStr - $timeEndStr] $title" + if (desc.isNotBlank()) " ($desc)" else ""
                    events.add(eventStr)
                }
            }

            if (events.isEmpty()) {
                "No upcoming events found in the next $daysAhead days."
            } else {
                "Upcoming Events (Next $daysAhead days):\n" + events.joinToString("\n")
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Calendar read permission denied")
            "Calendar permission not granted — user needs to allow calendar access"
        } catch (e: Exception) {
            Timber.e(e, "Calendar read failed")
            "Failed to read calendar events: ${e.message}"
        }
    }

    /**
     * Request Screenshot via accessibility service
     * Tool Name: SCREENSHOT
     */
    fun takeScreenshot(): String {
        return try {
            val intent = Intent(context, com.gemma.api.GemmaService::class.java).apply {
                action = "com.gemma.api.ACTION_REQUEST_SCREENSHOT"
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            "Screenshot requested... Check clipboard/vision." 
        } catch (e: Exception) {
            "Failed to request screenshot: ${e.message}"
        }
    }
}
