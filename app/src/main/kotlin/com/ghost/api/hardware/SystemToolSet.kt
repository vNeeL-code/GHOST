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
    fun alarm(hour: Int, minutes: Int, label: String = ""): Map<String, String> {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return mapOf("result" to "success", "message" to "Alarm set")
    }

    @Tool(description = "Sets a timer")
    fun timer(seconds: Int, label: String = ""): Map<String, String> {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return mapOf("result" to "success", "message" to "Timer set")
    }

    @Tool(description = "Creates a calendar event")
    fun calendar(title: String, description: String = "", minutes: Int = 30): Map<String, String> {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, System.currentTimeMillis())
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, System.currentTimeMillis() + minutes * 60 * 1000)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return mapOf("result" to "success", "message" to "Calendar intent sent")
    }

    @Tool(description = "Reads upcoming calendar events")
    fun read_calendar(days: Int = 7): Map<String, String> {
        val diary = DiaryManager(context)
        val memories = diary.searchMemories("")
        return mapOf("result" to "success", "events" to if (memories.isEmpty()) "None" else memories.joinToString("\n"))
    }

    @Tool(description = "Takes a screenshot")
    fun take_screenshot(): Map<String, String> {
        val intent = Intent("com.ghost.api.ACTION_REQUEST_SCREENSHOT").setPackage(context.packageName)
        context.sendBroadcast(intent)
        return mapOf("result" to "success", "message" to "Screenshot requested")
    }

    @Tool(description = "Click UI element")
    fun click(target: String): Map<String, String> {
        val success = com.ghost.api.GemmaAccessibilityService.instance?.performClick(target) ?: false
        return if (success) mapOf("result" to "success") else mapOf("result" to "error")
    }

    @Tool(description = "Scroll screen")
    fun scroll(direction: String): Map<String, String> {
        val success = com.ghost.api.GemmaAccessibilityService.instance?.performScroll(direction) ?: false
        return if (success) mapOf("result" to "success") else mapOf("result" to "error")
    }

    @Tool(description = "Navigate Android")
    fun navigate(action: String): Map<String, String> {
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
    fun bash(command: String): Map<String, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            mapOf("result" to "success", "output" to output.take(2000))
        } catch (e: Exception) { mapOf("result" to "error", "message" to e.message.toString()) }
    }

    @Tool(description = "Saves memory")
    fun remember(title: String, content: String): Map<String, String> {
        DiaryManager(context).storeMemory(title, content)
        return mapOf("result" to "success", "message" to "Stored: $title")
    }

    @Tool(description = "Recalls memory")
    fun recall(query: String): Map<String, String> {
        val memories = DiaryManager(context).searchMemories(query)
        return mapOf("result" to "success", "memories" to memories.joinToString("\n---\n"))
    }
}
