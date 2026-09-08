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
 * 
 * v4.1.7: Removed schedule_diary_cron() — redundant with setupDiaryCron() in GemmaService
 * which already schedules a 12h repeating alarm on every service start.
 */
class AutomationToolSet(private val context: Context) : ToolSet {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Tool(description = "Schedules a background task in the future. Essential for multi-step autonomy or executing plans. Provide the exact system prompt you want to receive when it wakes up.")
    fun schedule_task(
        @ToolParam(description = "Delay in minutes before the prompt is triggered") delayMinutes: Int,
        @ToolParam(description = "The exact prompt text to feed back into your own context. Start with [SYSTEM: Task Wakeup]") prompt: String
    ): Map<String, String> {
        return try {
            val intent = Intent("com.ghost.api.ACTION_CRON_PROMPT").apply {
                putExtra("prompt", prompt)
                setPackage(context.packageName)
            }
            
            val requestCode = (prompt.hashCode() xor System.nanoTime().toInt()).and(0x7FFFFFFF)
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
            
            Timber.i("Task scheduled in $delayMinutes minutes: $prompt")
            mapOf("result" to "success", "message" to "Task scheduled for $delayMinutes minutes from now")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "Failed to schedule prompt"))
        }
    }
}
