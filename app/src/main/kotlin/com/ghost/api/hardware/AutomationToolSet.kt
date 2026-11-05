package com.ghost.api.hardware

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ghost.api.Constants
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScheduledTask(
    val id: String,
    val title: String,
    val prompt: String,
    val intervalMinutes: Int, // 0 = one-shot, >0 = recurring cron interval
    val nextRunTimeMs: Long,
    val screenOffOnly: Boolean,
    val createdAtMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("prompt", prompt)
        put("intervalMinutes", intervalMinutes)
        put("nextRunTimeMs", nextRunTimeMs)
        put("screenOffOnly", screenOffOnly)
        put("createdAtMs", createdAtMs)
    }

    companion object {
        fun fromJson(obj: JSONObject): ScheduledTask = ScheduledTask(
            id = obj.optString("id", "task_${System.currentTimeMillis() % 10000}"),
            title = obj.optString("title", "Scheduled Task"),
            prompt = obj.optString("prompt", ""),
            intervalMinutes = obj.optInt("intervalMinutes", 0),
            nextRunTimeMs = obj.optLong("nextRunTimeMs", 0L),
            screenOffOnly = obj.optBoolean("screenOffOnly", true),
            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
        )
    }
}

data class AiFeedbackReport(
    val timestamp: Long,
    val reason: String,
    val userNotes: String,
    val flaggedResponse: String,
    val precedingUserPrompt: String,
    val reviewed: Boolean
)

/**
 * Handles autonomous task/routine scheduling, teardown, and screen-off deferred execution for GHOST,
 * as well as reading/resolving operator-flagged AI feedback reports (ai_reports.jsonl).
 */
class AutomationToolSet(private val context: Context) : ToolSet {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        private const val PREF_SCHEDULED_TASKS = "scheduled_tasks_json"
        private val lock = Any()

        fun loadTasks(context: Context): MutableList<ScheduledTask> = synchronized(lock) {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(PREF_SCHEDULED_TASKS, "[]") ?: "[]"
            val list = mutableListOf<ScheduledTask>()
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val task = ScheduledTask.fromJson(item)
                    if (task.prompt.isNotBlank()) list.add(task)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse scheduled_tasks_json")
            }
            list
        }

        fun saveTasks(context: Context, tasks: List<ScheduledTask>) = synchronized(lock) {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val arr = JSONArray()
            tasks.forEach { arr.put(it.toJson()) }
            prefs.edit().putString(PREF_SCHEDULED_TASKS, arr.toString()).apply()
        }

        fun getUnreviewedReports(context: Context, limit: Int = 5): List<AiFeedbackReport> {
            val file = File(context.filesDir, "ai_reports.jsonl")
            if (!file.exists()) return emptyList()
            return try {
                file.readLines()
                    .asReversed()
                    .mapNotNull { line ->
                        if (line.isBlank()) return@mapNotNull null
                        try {
                            val j = JSONObject(line)
                            if (j.optBoolean("reviewed", false)) return@mapNotNull null
                            AiFeedbackReport(
                                timestamp = j.optLong("timestamp", 0L),
                                reason = j.optString("reason", "Flagged output"),
                                userNotes = j.optString("user_notes", ""),
                                flaggedResponse = j.optString("flagged_response", ""),
                                precedingUserPrompt = j.optString("preceding_user_prompt", ""),
                                reviewed = false
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }
                    .take(limit)
            } catch (e: Exception) {
                Timber.w(e, "Failed to read ai_reports.jsonl")
                emptyList()
            }
        }

        fun markAllReportsReviewed(context: Context): Int {
            val file = File(context.filesDir, "ai_reports.jsonl")
            if (!file.exists()) return 0
            return synchronized(lock) {
                try {
                    val lines = file.readLines()
                    var updatedCount = 0
                    val updatedLines = lines.mapNotNull { line ->
                        if (line.isBlank()) return@mapNotNull null
                        try {
                            val j = JSONObject(line)
                            if (!j.optBoolean("reviewed", false)) {
                                j.put("reviewed", true)
                                j.put("reviewed_at", System.currentTimeMillis())
                                updatedCount++
                            }
                            j.toString()
                        } catch (_: Exception) {
                            line
                        }
                    }
                    // Keep last 50 reports max to prevent unbounded file growth
                    val trimmed = if (updatedLines.size > 50) updatedLines.takeLast(50) else updatedLines
                    file.writeText(trimmed.joinToString("\n") + if (trimmed.isNotEmpty()) "\n" else "")
                    updatedCount
                } catch (e: Exception) {
                    Timber.w(e, "Failed to mark reports reviewed")
                    0
                }
            }
        }
    }

    private fun buildTaskPendingIntent(taskId: String): PendingIntent {
        val intent = Intent(context, DiaryAlarmReceiver::class.java).apply {
            action = "com.ghost.api.ACTION_SCHEDULED_TASK"
            putExtra("task_id", taskId)
            setPackage(context.packageName)
        }
        val requestCode = (taskId.hashCode() and 0x7FFFFFFF).coerceAtLeast(10_000)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun armTaskAlarm(task: ScheduledTask) {
        try {
            val pi = buildTaskPendingIntent(task.id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextRunTimeMs, pi)
                } catch (_: SecurityException) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextRunTimeMs, pi)
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, task.nextRunTimeMs, pi)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to arm alarm for task ${task.id}")
        }
    }

    private fun cancelTaskAlarm(taskId: String) {
        try {
            val pi = buildTaskPendingIntent(taskId)
            alarmManager.cancel(pi)
        } catch (e: Exception) {
            Timber.w(e, "Failed to cancel alarm for task $taskId")
        }
    }

    @Tool(description = "Schedules a background routine, cron job, or deferred task. Set intervalMinutes > 0 for recurring cron jobs, or 0 for one-shot tasks. Set screenOffOnly = true so complex tasks wait until the screen is off and don't interrupt active conversations.")
    fun schedule_task(
        @ToolParam(description = "Delay in minutes before first execution (default 30)") delayMinutes: Int = 30,
        @ToolParam(description = "The task instructions or routine prompt to execute") prompt: String,
        @ToolParam(description = "Short title for the task (e.g. 'Nightly Audit', 'Weather Check')") title: String = "Routine Task",
        @ToolParam(description = "Recurring interval in minutes (0 for one-shot, e.g. 60 for hourly, 720 for 12h, 1440 for daily)") intervalMinutes: Int = 0,
        @ToolParam(description = "If true (default), defers execution until the screen is off so it never interrupts active chat") screenOffOnly: Boolean = true
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("SCHEDULE", 1500)
        return try {
            val safeDelay = delayMinutes.coerceAtLeast(1)
            val safeInterval = intervalMinutes.coerceAtLeast(0)
            val now = System.currentTimeMillis()
            val tasks = loadTasks(context)
            val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(16).ifBlank { "task" }
            val taskId = "${slug}_${(now / 1000) % 10000}"

            val newTask = ScheduledTask(
                id = taskId,
                title = title.ifBlank { prompt.take(28) },
                prompt = prompt,
                intervalMinutes = safeInterval,
                nextRunTimeMs = now + safeDelay * 60_000L,
                screenOffOnly = screenOffOnly,
                createdAtMs = now
            )
            tasks.add(newTask)
            saveTasks(context, tasks)
            armTaskAlarm(newTask)

            val cadenceLabel = if (safeInterval > 0) "every ${safeInterval}m" else "one-shot"
            val modeLabel = if (screenOffOnly) "deferred to screen-off" else "active wakeup"
            val msg = "Scheduled '${newTask.title}' (id: $taskId) in ${safeDelay}m [$cadenceLabel, $modeLabel]."
            Timber.i("⏱️ $msg")
            mapOf("result" to "success", "message" to msg, "id" to taskId)
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "Failed to schedule task"))
        }
    }

    @Tool(description = "Lists all scheduled routines, cron jobs, deferred screen-off tasks, and pending AI feedback reports.")
    fun list_tasks(): Map<String, String> {
        val tasks = loadTasks(context)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val diaryEnabled = prefs.getBoolean(Constants.PREF_AUTONOMOUS_DIARY, true)
        val diaryCadence = prefs.getString(Constants.PREF_DIARY_CADENCE, "12") ?: "12"
        val pendingScreenOffDiary = prefs.getBoolean("pending_screen_off_diary", false)
        val pendingReports = getUnreviewedReports(context, limit = 10)
        val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.US)

        val sb = StringBuilder()
        sb.appendLine("📋 Scheduled Routines & Cron Status:")
        sb.appendLine("• Built-in Diary & Self-Review Cron: ${if (diaryEnabled && diaryCadence != "OFF") "Active (every ${diaryCadence}h, screen-off deferred)" else "OFF"}${if (pendingScreenOffDiary) " [QUEUED FOR SCREEN-OFF]" else ""}")
        sb.appendLine("• Pending Flagged Reports (🚩) for Self-Review: ${pendingReports.size}")

        if (tasks.isEmpty()) {
            sb.appendLine("• Custom Scheduled Tasks: None")
        } else {
            sb.appendLine("• Custom Scheduled Tasks (${tasks.size}):")
            tasks.forEach { t ->
                val dueStr = sdf.format(Date(t.nextRunTimeMs))
                val recurStr = if (t.intervalMinutes > 0) "every ${t.intervalMinutes}m" else "one-shot"
                val screenStr = if (t.screenOffOnly) "screen-off" else "anytime"
                sb.appendLine("  - [${t.id}] ${t.title} | Next: $dueStr ($recurStr, $screenStr)")
                sb.appendLine("    Prompt: ${t.prompt.take(90)}")
            }
        }
        return mapOf("result" to "success", "output" to sb.toString().trim())
    }

    @Tool(description = "Cancels and tears down a scheduled task/routine by ID or title, or pass 'all' to tear down all custom scheduled tasks.")
    fun cancel_task(
        @ToolParam(description = "Task ID, title substring, or 'all'") target: String
    ): Map<String, String> {
        val clean = target.trim()
        if (clean.isEmpty()) {
            return mapOf("result" to "error", "message" to "Specify a task ID, title, or 'all' to cancel.")
        }
        val tasks = loadTasks(context)
        if (tasks.isEmpty()) {
            return mapOf("result" to "success", "message" to "No custom scheduled tasks are currently active.")
        }

        val toRemove = if (clean.equals("all", ignoreCase = true) || clean.equals("*", ignoreCase = true)) {
            tasks.toList()
        } else {
            tasks.filter {
                it.id.equals(clean, ignoreCase = true) ||
                    it.title.contains(clean, ignoreCase = true) ||
                    it.prompt.contains(clean, ignoreCase = true)
            }
        }

        if (toRemove.isEmpty()) {
            return mapOf("result" to "error", "message" to "No scheduled task matched '$clean'. Use list_tasks to inspect active IDs.")
        }

        toRemove.forEach { cancelTaskAlarm(it.id) }
        tasks.removeAll(toRemove.toSet())
        saveTasks(context, tasks)

        val removedNames = toRemove.joinToString(", ") { "'${it.title}' (${it.id})" }
        val msg = "Tore down ${toRemove.size} scheduled task(s): $removedNames."
        Timber.i("🗑️ $msg")
        return mapOf("result" to "success", "message" to msg)
    }

    fun getDueTasks(nowMs: Long = System.currentTimeMillis(), isScreenInteractive: Boolean): List<ScheduledTask> {
        val tasks = loadTasks(context)
        return tasks.filter { task ->
            task.nextRunTimeMs <= nowMs && (!task.screenOffOnly || !isScreenInteractive)
        }
    }

    fun hasPendingScreenOffTasks(nowMs: Long = System.currentTimeMillis()): Boolean {
        val tasks = loadTasks(context)
        return tasks.any { it.nextRunTimeMs <= nowMs && it.screenOffOnly }
    }

    fun advanceOrRemoveTaskAfterRun(taskId: String, nowMs: Long = System.currentTimeMillis()) {
        val tasks = loadTasks(context)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return
        val task = tasks[idx]
        if (task.intervalMinutes > 0) {
            val updated = task.copy(nextRunTimeMs = nowMs + task.intervalMinutes * 60_000L)
            tasks[idx] = updated
            saveTasks(context, tasks)
            armTaskAlarm(updated)
        } else {
            cancelTaskAlarm(task.id)
            tasks.removeAt(idx)
            saveTasks(context, tasks)
        }
    }
}
