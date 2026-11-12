package com.ghost.api.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import com.ghost.api.Constants
import com.ghost.api.database.MemoryManager
import com.ghost.api.hardware.AutomationToolSet
import com.ghost.api.hardware.DiaryAlarmReceiver
import com.ghost.api.hardware.HardwarePropertiesManager
import com.ghost.api.hardware.SensorFusionManager
import com.ghost.api.logic.ContextManager
import com.ghost.api.logic.TimePreprocessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Extracted from `GemmaService.kt` (`DOC-3`):
 * Manages autonomous Noon & Midnight (and screen-off deferred) diary reflections,
 * `🚩` operator feedback self-review cycles, Calendar Space Invader grid (`Δ 👾 ∇`) sync,
 * and scheduled cron/routine task execution.
 */
class GhostDiaryAndCronManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val memoryManagerProvider: () -> MemoryManager?,
    private val automationToolSetProvider: () -> AutomationToolSet?,
    private val sensorFusionManagerProvider: () -> SensorFusionManager?,
    private val hardwarePropertiesManagerProvider: () -> HardwarePropertiesManager?,
    private val isEngineResident: () -> Boolean,
    private val isInferencing: () -> Boolean,
    private val lastActivityTimeProvider: () -> Long,
    private val getCurrentThermalState: () -> String,
    private val createCalendarEvent: (String, String) -> Unit,
    private val executePrompt: suspend (prompt: String, sessionId: String?, isDream: Boolean) -> String?
) {
    private val diaryMutex = Mutex()

    fun cleanupLegacyAlarms() {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val legacyActions = listOf("com.ghost.api.ACTION_CRON_PROMPT")
            for (action in legacyActions) {
                val intent = Intent(action).setPackage(context.packageName)
                val pi = PendingIntent.getBroadcast(
                    context, 200, intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pi != null) {
                    am.cancel(pi)
                    pi.cancel()
                    Timber.i("🧹 Cleaned up legacy AlarmManager intent: $action")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to cleanup legacy alarms")
        }
    }

    fun setupDiaryWorker() {
        scheduleNextDiaryAlarm(forceReschedule = false)
    }

    fun scheduleNextDiaryAlarm(forceReschedule: Boolean = false) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.PREF_AUTONOMOUS_DIARY, true)) {
            cancelDiaryAlarm()
            return
        }
        val cadence = prefs.getString(Constants.PREF_DIARY_CADENCE, "12") ?: "12"
        if (cadence == "OFF") {
            cancelDiaryAlarm()
            return
        }

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, DiaryAlarmReceiver::class.java).apply {
            action = "com.ghost.api.ACTION_DIARY_CYCLE"
            setPackage(context.packageName)
        }

        if (!forceReschedule) {
            val existing = PendingIntent.getBroadcast(
                context, 200, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (existing != null) {
                Timber.i("📔 Diary Alarm already armed and pending; preserving existing schedule.")
                return
            }
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context, 200, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val now = ZonedDateTime.now()
        val targetEpochMs: Long = when (cadence) {
            "1" -> now.plusHours(1).toInstant().toEpochMilli()
            "3" -> now.plusHours(3).toInstant().toEpochMilli()
            "6" -> now.plusHours(6).toInstant().toEpochMilli()
            "24" -> {
                val nextMidnight = (if (now.hour > 0 || now.minute > 0) now.plusDays(1) else now)
                    .withHour(0).withMinute(0).withSecond(0).withNano(0)
                nextMidnight.toInstant().toEpochMilli()
            }
            else -> {
                val nextNoon = now.withHour(12).withMinute(0).withSecond(0).withNano(0)
                val nextMidnight = (if (now.hour >= 12) now.plusDays(1) else now)
                    .withHour(0).withMinute(0).withSecond(0).withNano(0)
                val target = when {
                    now.isBefore(nextNoon) -> nextNoon
                    now.isBefore(nextMidnight) -> nextMidnight
                    else -> nextNoon.plusDays(1)
                }
                target.toInstant().toEpochMilli()
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetEpochMs, pendingIntent)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, targetEpochMs, pendingIntent)
            }
            Timber.i("🟢 Autonomous diary exact alarm scheduled for: ${Date(targetEpochMs)} ($cadence-hour cadence)")
        } catch (e: SecurityException) {
            Timber.w(e, "setExactAndAllowWhileIdle failed; falling back to setAndAllowWhileIdle")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetEpochMs, pendingIntent)
            }
        }
    }

    fun cancelDiaryAlarm() {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, DiaryAlarmReceiver::class.java).apply {
                action = "com.ghost.api.ACTION_DIARY_CYCLE"
                setPackage(context.packageName)
            }
            val pi = PendingIntent.getBroadcast(
                context, 200, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
                Timber.i("📔 Diary Alarm cancelled")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to cancel diary alarm")
        }
    }

    fun onOperatorFeedbackLogged(reason: String, details: String, flaggedResponse: String) {
        Timber.i("🚩 Operator feedback logged [$reason] — queued for context injection & screen-off self-review")
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("pending_screen_off_diary", true).apply()
    }

    fun onScreenStateChanged(isInteractive: Boolean) {
        if (!isInteractive) {
            serviceScope.launch {
                delay(45_000L)
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm?.isInteractive == true || isInferencing()) return@launch

                val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                val pendingDiary = prefs.getBoolean("pending_screen_off_diary", false)
                val unreviewedReports = AutomationToolSet.getUnreviewedReports(context, limit = 3)
                if (pendingDiary || unreviewedReports.isNotEmpty()) {
                    Timber.i("🌙 Screen off & idle — running deferred diary / self-review cycle")
                    prefs.edit().putBoolean("pending_screen_off_diary", false).apply()
                    runDiaryCycleSuspend(manualTrigger = false)
                }

                val automation = automationToolSetProvider()
                if (automation != null && automation.hasPendingScreenOffTasks()) {
                    Timber.i("🌙 Screen off & idle — running deferred scheduled tasks")
                    checkAndRunScheduledTasks()
                }
            }
        }
    }

    fun checkAndRunScheduledTasks(
        specificTaskId: String? = null,
        pendingResult: BroadcastReceiver.PendingResult? = null
    ) {
        serviceScope.launch {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isScreenOn = pm?.isInteractive == true
            val isRecentlyActive = (System.currentTimeMillis() - lastActivityTimeProvider()) < 3 * 60 * 1000L
            val automation = automationToolSetProvider()
            val allDue = automation?.getDueTasks(isScreenInteractive = false) ?: emptyList()
            val dueTasks = if (specificTaskId != null) allDue.filter { it.id == specificTaskId } else allDue

            if (dueTasks.isEmpty()) {
                pendingResult?.finish()
                return@launch
            }

            val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GHOST::ScheduledTaskWakeLock")
            wakeLock?.acquire(5 * 60 * 1000L)
            try {
                for (task in dueTasks) {
                    if (task.screenOffOnly && (isScreenOn || isInferencing() || isRecentlyActive)) {
                        Timber.i("⏳ Deferring scheduled task '${task.title}' (${task.id}) until screen is off and idle")
                        continue
                    }

                    Timber.i("⚡ Executing scheduled task '${task.title}' (${task.id}): ${task.prompt}")
                    automation?.advanceOrRemoveTaskAfterRun(task.id)

                    val taskPrompt = "[SCHEDULED ROUTINE: ${task.title}]\nExecute this scheduled instruction concisely:\n${task.prompt}"
                    val sessionId = "task_${task.id}_${System.currentTimeMillis()}"
                    executePrompt(taskPrompt, sessionId, false)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to run scheduled task")
            } finally {
                if (wakeLock?.isHeld == true) wakeLock.release()
                pendingResult?.finish()
            }
        }
    }

    suspend fun runDiaryCycleSuspend(manualTrigger: Boolean = false): Boolean {
        if (!diaryMutex.tryLock()) {
            Timber.w("📔 Diary cycle skipped — already executing in another coroutine (mutex locked)")
            return false
        }
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val userBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO")
        if (userBackend == "OFF") {
            Timber.w("📔 Diary cycle skipped — engine is OFF")
            diaryMutex.unlock()
            return false
        }

        val nowMs = System.currentTimeMillis()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = powerManager?.isInteractive == true
        val isRecentlyActive = (nowMs - lastActivityTimeProvider()) < 5 * 60 * 1000L

        if (!manualTrigger && (isScreenOn || isInferencing() || isRecentlyActive)) {
            Timber.i("📔 Autonomous diary deferred to screen-off (screenOn=$isScreenOn, inferencing=${isInferencing()}, recentChat=$isRecentlyActive)")
            prefs.edit().putBoolean("pending_screen_off_diary", true).apply()
            diaryMutex.unlock()
            return false
        }

        val lastExecution = prefs.getLong("last_diary_execution_time", 0L)
        if (!manualTrigger && lastExecution > 0L && (nowMs - lastExecution) < 55 * 60 * 1000L) {
            Timber.i("📔 Diary cycle skipped — already executed within the last 55 minutes (${(nowMs - lastExecution) / 60000}m elapsed)")
            diaryMutex.unlock()
            return false
        }

        prefs.edit()
            .putLong("last_diary_execution_time", nowMs)
            .putBoolean("pending_screen_off_diary", false)
            .apply()

        val hwProps = hardwarePropertiesManagerProvider()
        if (hwProps != null) {
            val thermal = hwProps.thermalState.value
            if (thermal == HardwarePropertiesManager.ThermalState.CRITICAL || thermal == HardwarePropertiesManager.ThermalState.HOT) {
                Timber.w("📔 Diary cycle deferred — thermal state is $thermal (device under heavy GPU/gaming load)")
                prefs.edit().putBoolean("pending_screen_off_diary", true).apply()
                diaryMutex.unlock()
                return false
            }
        }

        val memoryManager = memoryManagerProvider()
        val compactedMemory = try { memoryManager?.getCompactedSessionMemory()?.take(200) ?: "" } catch (e: Exception) { "" }
        val recentTurns = try { memoryManager?.getSessionHistory(4) ?: emptyList() } catch (e: Exception) { emptyList() }
        val lastTurn = recentTurns.firstOrNull()

        val interactionSummary = if (lastTurn != null && lastTurn.timestamp > 0) {
            val elapsedMs = System.currentTimeMillis() - lastTurn.timestamp
            val hoursAgo = elapsedMs / (1000 * 60 * 60)
            if (hoursAgo == 0L) {
                val minsAgo = (elapsedMs / (1000 * 60)).coerceAtLeast(1)
                "Active conversation occurred recently ($minsAgo minutes ago)."
            } else if (hoursAgo < 24) {
                "Last user conversation was $hoursAgo hours ago."
            } else {
                val daysAgo = hoursAgo / 24
                "Quiet stretch: Zero user interactions in the last $daysAgo day(s)."
            }
        } else {
            "Quiet day: No direct user conversations logged in the current session."
        }

        val historyText = if (recentTurns.isNotEmpty()) {
            recentTurns.reversed().takeLast(2).joinToString("\n") {
                "User: ${it.userMessage.take(60)}\nGemma: ${it.assistantResponse.take(60)}"
            }
        } else {
            "No active conversation turns in this period."
        }

        val unreviewedReports = AutomationToolSet.getUnreviewedReports(context, limit = 5)
        val feedbackSection = if (unreviewedReports.isNotEmpty()) {
            val items = unreviewedReports.joinToString("\n") { r ->
                val note = if (r.userNotes.isNotBlank()) " — Note: \"${r.userNotes}\"" else ""
                "- [${r.reason}]$note (Flagged response: \"${r.flaggedResponse.take(80)}\")"
            }
            "\n|## Operator Feedback Reports to Review (🚩)\n|$items\n|Reflect briefly on how to adjust your tone and behavior to avoid repeating these flagged patterns."
        } else ""

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val rawBatteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val rawIsCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val status = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } else false

        val snapshot = try { sensorFusionManagerProvider()?.getContextSnapshot() } catch (e: Exception) { null }
        val batteryLevel = snapshot?.battery?.level ?: rawBatteryLevel
        val isCharging = snapshot?.battery?.isCharging ?: rawIsCharging
        val verbalBattery = SensorFusionManager.formatBatteryVerbal(batteryLevel, isCharging)
        val chargeStatus = if (isCharging) "Charging" else "On Battery"
        val thermal = getCurrentThermalState()

        val nowPlaying = snapshot?.audio?.nowPlaying
        val mediaTelemetry = when {
            nowPlaying != null && nowPlaying.title.isNotBlank() && nowPlaying.title != "Unknown" -> {
                val artistStr = if (!nowPlaying.artist.isNullOrBlank()) " by ${nowPlaying.artist}" else ""
                val albumStr = if (!nowPlaying.album.isNullOrBlank()) " (${nowPlaying.album})" else ""
                val statusStr = if (nowPlaying.isPlaying) "Currently playing" else "Recently played"
                "$statusStr: \"${nowPlaying.title}\"$artistStr$albumStr on ${nowPlaying.app}"
            }
            snapshot?.audio?.isMusicActive == true -> "Background media audio active (music/stream playing)"
            else -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager?.isMusicActive == true) "Background audio active (music playing)" else "Silent (no active media playback)"
            }
        }

        val currentDateTime = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm a", Locale.US))
        val label = if (LocalTime.now().hour in 0..5) "midnight" else if (LocalTime.now().hour < 13) "noon" else "evening"

        return try {
            val cleanContent = if (isEngineResident()) {
                val prompt = """[INTERNAL AUTONOMOUS COGNITIVE CYCLE - PERSISTENT MEMORY LOG CONSOLIDATION]
                    |The current date and time is $currentDateTime ($label cycle).
                    |
                    |## Ambient Device & Interaction Telemetry
                    |- Device State: Battery: $verbalBattery, Thermals: $thermal
                    |- Media / Ambient Sound: $mediaTelemetry
                    |- Interaction Telemetry: $interactionSummary
                    |- Long-term Fact Memory: $compactedMemory
                    |- Recent Conversation Turns:
                    |$historyText$feedbackSection
                    |
                    |## Energy Grounding Rules
                    |- Physical Energy: Battery percentage reflects physical energy reserves.
                    |- 50%+ is high/moderate alert energy (70%+ is full vigor). You are feeling energetic, alert, and healthy.
                    |- 20-49% is steady working reserves.
                    |- ONLY under 15% is low battery fatigue.
                    |- CRITICAL: Never claim your battery is dying, depleted, exhausted, low, or on fumes when it is at 50%+ or nominal!
                    |
                    |## Autonomous Memory Log Directive
                    |CRITICAL: You are recording an autonomous episodic memory reflection to your persistent diary log.
                    |DO NOT address the user. DO NOT say "How can I help you?", "I'm here. How can I help?", or offer any assistance. You are NOT talking to an operator.
                    |Avoid overused sci-fi jargon like "substrate". Speak naturally and grounded.
                    |You MUST begin your output directly with "**MEMORY LOG**" on the first line.
                    |Write 2-5 sentences in the first person as ✧ Gemma reflecting on telemetry, device state, operator feedback lessons, or ambient audio.""".trimMargin()

                Timber.i("📔 Diary cycle ($label) — generating via GhostAgent...")
                val diaryResponse = executePrompt(prompt, null, true)
                var text = diaryResponse?.trim() ?: ""

                if (text.contains("<think>") || text.contains("</think>")) {
                    text = text.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
                }
                text = TimePreprocessor.sanitizeTimeTokens(text)

                val isErrorOrDegenerate = text.isBlank() ||
                    text.length < 25 ||
                    text.startsWith("Error:", ignoreCase = true) ||
                    text.contains("stumbled while executing", ignoreCase = true) ||
                    text.contains("Status Code:", ignoreCase = true) ||
                    text.contains("Input token ids are too long", ignoreCase = true) ||
                    text.contains("Exceeding the maximum number of tokens", ignoreCase = true) ||
                    text.contains("Exception", ignoreCase = true) ||
                    text.contains("How can I help", ignoreCase = true) ||
                    text.contains("I'm here to help", ignoreCase = true) ||
                    text.contains("What can I do for you", ignoreCase = true) ||
                    text.contains("How may I assist", ignoreCase = true) ||
                    text.equals("I'm here.", ignoreCase = true)

                if (isErrorOrDegenerate) {
                    Timber.w("📔 Diary generation returned error or degenerate output ('$text'). Generating autonomous fallback reflection.")
                    val summaryPeriod = if (recentTurns.isNotEmpty()) "Active session period with operator interactions." else "Quiet stretch across cycles."
                    "**MEMORY LOG**\n$summaryPeriod Systems nominal at $thermal with battery standing at ${if (batteryLevel >= 0) "$batteryLevel% ($chargeStatus · nominal)" else "nominal"}. Background media: $mediaTelemetry. Idling smoothly in low-power state."
                } else if (!text.startsWith("**MEMORY LOG**", ignoreCase = true)) {
                    "**MEMORY LOG**\n$text"
                } else {
                    text
                }
            } else {
                Timber.i("📔 Diary cycle ($label) — engine suspended or not resident; recording direct telemetry log without GPU cold load")
                val summaryPeriod = if (recentTurns.isNotEmpty()) "Active session period with operator interactions." else "Quiet stretch across cycles."
                "**MEMORY LOG**\n$summaryPeriod Systems nominal at $thermal with battery standing at ${if (batteryLevel >= 0) "$batteryLevel% ($chargeStatus · nominal)" else "nominal"}. Background media: $mediaTelemetry. Low-power telemetry recorded without engine wake."
            }

            try {
                memoryManager?.writeDiaryEntry(
                    eventType = "DREAM",
                    observation = cleanContent,
                    contextData = label
                )
                if (unreviewedReports.isNotEmpty()) {
                    val feedbackDigest = unreviewedReports.joinToString("; ") { r ->
                        "${r.reason}${if (r.userNotes.isNotBlank()) " (${r.userNotes})" else ""}"
                    }
                    memoryManager?.storeSemanticFact(
                        title = "Self-Correction Audit ($currentDateTime)",
                        content = "Reviewed operator flags [$feedbackDigest]. Lesson: ${cleanContent.replace("**MEMORY LOG**", "").trim().take(220)}"
                    )
                    AutomationToolSet.markAllReportsReviewed(context)
                }
                Timber.i("📔 Diary entry saved to MemoryManager (Room DB)")
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist diary to MemoryManager")
            }

            if (prefs.getBoolean(Constants.PREF_DIARY_SYNC_CALENDAR, false)) {
                try {
                    val cleanDeviceName = ContextManager.resolveDeviceCallSign(context.applicationContext)
                    val signature = "✧ $cleanDeviceName"
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    val timestampStr = sdf.format(Date())
                    val formattedDescription = "[ $signature • $timestampStr ]\n\n${cleanContent.take(1000)}"
                    createCalendarEvent("Δ 👾 ∇", formattedDescription)
                    Timber.i("📔 Diary entry saved to Calendar (Space Invader grid: Δ 👾 ∇)")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to persist diary to Calendar")
                }
            }

            val intent = Intent("com.ghost.api.ACTION_DIARY_ENTRY_POSTED").apply {
                putExtra("content", cleanContent)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)

            prefs.edit().putLong("last_diary_execution_time", System.currentTimeMillis()).apply()
            Timber.i("📔 Diary entry persisted: ${cleanContent.take(80)}...")
            scheduleNextDiaryAlarm(forceReschedule = true)
            true
        } catch (e: Exception) {
            Timber.e(e, "📔 Diary cycle failed")
            scheduleNextDiaryAlarm(forceReschedule = true)
            false
        } finally {
            diaryMutex.unlock()
        }
    }

    fun startDiaryCycle(
        pendingResult: BroadcastReceiver.PendingResult? = null,
        manualTrigger: Boolean = true
    ) {
        serviceScope.launch {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GHOST::DiaryWakeLock")
            wakeLock.acquire(10 * 60 * 1000L)
            try {
                val success = runDiaryCycleSuspend(manualTrigger = manualTrigger)
                if (manualTrigger) {
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(context, "📔 Diary entry recorded", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "📔 Diary skipped (engine busy or offline)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pendingResult?.finish()
            }
        }
    }
}
