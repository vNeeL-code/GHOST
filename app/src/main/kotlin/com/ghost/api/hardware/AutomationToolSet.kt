package com.ghost.api.hardware

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import timber.log.Timber

/**
 * Handles autonomous scheduling operations for Gemma.
 */
class AutomationToolSet(private val context: Context) : ToolSet {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Tool(description = "Schedules a recurring 12-hour cron job for a diary entry")
    fun schedule_diary_cron(): Map<String, String> {
        return try {
            val intent = Intent("com.ghost.api.ACTION_CRON_PROMPT").apply {
                putExtra("prompt", "Write a first-person diary entry summarizing the past 12 hours of system states, interactions, and events.")
                setPackage(context.packageName)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1200, // Unique ID for diary cron
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val intervalMillis = 12 * 60 * 60 * 1000L
            val triggerAtMillis = System.currentTimeMillis() + intervalMillis
            
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                intervalMillis,
                pendingIntent
            )
            
            Timber.i("12h diary cron job scheduled.")
            mapOf("result" to "success", "message" to "12-hour diary cron job scheduled successfully")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "Failed to schedule diary cron"))
        }
    }

    @Tool(description = "Schedules a cron job in the future for self prompting")
    fun schedule_prompt(
        @ToolParam(description = "Delay in minutes before the prompt is triggered") delayMinutes: Int,
        @ToolParam(description = "The exact prompt text to feed back into your own context") prompt: String
    ): Map<String, String> {
        return try {
            val intent = Intent("com.ghost.api.ACTION_CRON_PROMPT").apply {
                putExtra("prompt", prompt)
                setPackage(context.packageName)
            }
            
            val requestCode = System.currentTimeMillis().toInt()
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAtMillis = SystemClock.elapsedRealtime() + (delayMinutes * 60 * 1000L)
            
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            
            Timber.i("Self-prompt scheduled in $delayMinutes minutes: $prompt")
            mapOf("result" to "success", "message" to "Prompt scheduled for $delayMinutes minutes from now")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "Failed to schedule prompt"))
        }
    }
}
