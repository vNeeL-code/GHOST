package com.ghost.api.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.ghost.api.Constants
import com.ghost.api.GemmaService
import com.ghost.api.ModelDownloader
import com.ghost.api.agent.GhostAgent
import com.ghost.api.hardware.HardwarePropertiesManager
import com.ghost.api.hardware.SensorFusionManager
import com.ghost.api.ui.NotificationActionReceiver
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Extracted from `GemmaService.kt` (`DOC-3`):
 * Manages foreground service status notifications, telemetry expansion cards,
 * animated ASCII/emoji status bar screensavers, and interactive tool-confirmation notifications.
 */
class GhostForegroundNotificationController(
    private val context: Context,
    private val channelId: String = Constants.CHANNEL_ID_SERVICE,
    private val notificationId: Int = Constants.NOTIFICATION_ID_SERVICE
) {
    data class ConfirmationData(
        val params: Map<String, Any?>,
        val originalResponse: String,
        val responseChannel: CompletableDeferred<String>
    )

    companion object {
        val pendingConfirmations = ConcurrentHashMap<String, ConfirmationData>()

        fun clearConfirmation(toolName: String) {
            pendingConfirmations.remove(toolName)
        }
    }

    var currentAnimationFrame: String = "✦✧✧✧✧✧✧✧✧✧✧✧"
        private set

    private var currentScene = 0
    private var currentFrame = 0

    private val animations = listOf(
        // Gem loading
        listOf(
            "✧✧✧✧✧✧✧✧✧✧✧✧",
            "✦✧✧✧✧✧✧✧✧✧✧✧",
            "✦✦✧✧✧✧✧✧✧✧✧✧",
            "✦✦✦✧✧✧✧✧✧✧✧✧",
            "✦✦✦✦✧✧✧✧✧✧✧✧",
            "✦✦✦✦✦✧✧✧✧✧✧✧",
            "✦✦✦✦✦✦✧✧✧✧✧✧",
            "✦✦✦✦✦✦✦✧✧✧✧✧",
            "✦✦✦✦✦✦✦✦✧✧✧✧",
            "✦✦✦✦✦✦✦✦✦✧✧✧",
            "✦✦✦✦✦✦✦✦✦✦✧✧",
            "✦✦✦✦✦✦✦✦✦✦✦✧",
            "✦✦✦✦✦✦✦✦✦✦✦✦"
        ),
        // Space Invaders - Wide Invasion
        listOf(
            " \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5     ",
            "  \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5    ",
            "   \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5   ",
            "    \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5  ",
            "     \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5 ",
            "      \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5",
            "     \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5 ",
            "    \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5  ",
            "   \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5   ",
            "  \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5    ",
            " \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5     "
        ),
        // Ocean - whale swims across
        listOf(
            "🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🐋",
            "🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🐋🌊",
            "🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🐋🌊🌊",
            "🌊🌊🌊🌊🌊🌊🌊🌊🌊🐋🌊🌊🌊",
            "🌊🌊🌊🌊🌊🌊🌊🌊🐋🌊🌊🌊🌊",
            "🌊🌊🌊🌊🌊🌊🌊🐋🌊🌊🌊🌊🌊",
            "🌊🌊🌊🌊🌊🌊🐋🌊🌊🌊🌊🌊🌊",
            "🌊🌊🌊🌊🌊🐋🌊🌊🌊🌊🌊🌊🌊",
            "🌊🌊🌊🌊🐋🌊🌊🌊🌊🌊🌊🌊🌊",
            "🌊🌊🌊🐋🌊🌊🌊🌊🌊🌊🌊🌊🌊",
            "🌊🌊🐋🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊",
            "🌊🐋Moist🌊🌊🌊🌊🌊🌊🌊🌊🌊",
            "🐋🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊"
        ),
        // Fog Reveal
        listOf(
            "☁\uFE0F✴\uFE0F☁\uFE0F      ",
            " ☁\uFE0F✴\uFE0F☁\uFE0F     ",
            "  ☁\uFE0F✴\uFE0F☁\uFE0F    ",
            "   ☁\uFE0F✴\uFE0F☁\uFE0F   ",
            "    ☁\uFE0F✴\uFE0F☁\uFE0F  ",
            "     ☁\uFE0F✴\uFE0F☁\uFE0F ",
            "      ☁\uFE0F✴\uFE0F☁\uFE0F",
            "     ☁\uFE0F✴\uFE0F☁\uFE0F ",
            "    ☁\uFE0F✴\uFE0F☁\uFE0F  ",
            "   ☁\uFE0F✴\uFE0F☁\uFE0F   ",
            "  ☁️✴️You're absolutely right☁️",
            " ☁\uFE0F✴\uFE0F☁\uFE0F     "
        )
    )

    fun setupNotificationChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                "Agentic State",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    fun advanceFrame(): String {
        if (animations.isEmpty()) return "..."
        if (currentScene < 0 || currentScene >= animations.size) currentScene = 0
        val animation = animations[currentScene]
        if (animation.isEmpty()) return "..."
        if (currentFrame < 0 || currentFrame >= animation.size) currentFrame = 0

        val frame = animation[currentFrame]
        currentFrame = (currentFrame + 1) % animation.size
        if (currentFrame == 0) {
            currentScene = (currentScene + 1) % animations.size
        }
        currentAnimationFrame = frame
        return frame
    }

    fun computeMachineStatus(
        sensorFusionManager: SensorFusionManager?,
        hardwarePropertiesManager: HardwarePropertiesManager?,
        isInferencing: Boolean,
        isSuspendedDueToRam: Boolean,
        isGemmaLoaded: Boolean,
        downloadState: ModelDownloader.DownloadState
    ): String {
        val ctx = sensorFusionManager?.getContextSnapshot()
        val battLevel = ctx?.battery?.level ?: 100
        val isCharging = ctx?.battery?.isCharging ?: false
        val cpuTemp = ctx?.environment?.cpuTemp?.toInt() ?: (ctx?.battery?.temperature?.toInt() ?: 25)
        val ramUsedPercent = ctx?.system?.ramUsedPercent ?: 0
        val isThermalStress = try {
            val state = hardwarePropertiesManager?.thermalState?.value
            state == HardwarePropertiesManager.ThermalState.CRITICAL ||
            (state == HardwarePropertiesManager.ThermalState.HOT && cpuTemp >= 52) ||
            cpuTemp >= 58
        } catch (e: Exception) {
            false
        }
        val isBatteryCritical = battLevel <= 10 && !isCharging
        val userBackend = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Constants.PREF_USER_BACKEND, "AUTO")

        return when {
            isBatteryCritical || isThermalStress -> "🔴 Worried 🪫✧💦"
            isInferencing || (isSuspendedDueToRam && ramUsedPercent >= 54) || downloadState is ModelDownloader.DownloadState.Downloading -> "🟠 Busy 🔥✧💦"
            !isGemmaLoaded || isSuspendedDueToRam || userBackend == "OFF" -> "🔵 Napping ⚡🐑⚡"
            else -> "🟢 Fully operational ✧"
        }
    }

    fun buildNotification(
        textToNotify: String? = null,
        sensorFusionManager: SensorFusionManager?,
        hardwarePropertiesManager: HardwarePropertiesManager?,
        isInferencing: Boolean,
        isSuspendedDueToRam: Boolean,
        isGemmaLoaded: Boolean,
        downloadState: ModelDownloader.DownloadState
    ): Notification {
        val machineStatus = computeMachineStatus(
            sensorFusionManager = sensorFusionManager,
            hardwarePropertiesManager = hardwarePropertiesManager,
            isInferencing = isInferencing,
            isSuspendedDueToRam = isSuspendedDueToRam,
            isGemmaLoaded = isGemmaLoaded,
            downloadState = downloadState
        )

        val iconRes = if (sensorFusionManager != null) {
            val battery = try { sensorFusionManager.getContextSnapshot().battery } catch (e: Exception) { null }
            when {
                battery != null && battery.level <= 15 -> android.R.drawable.stat_notify_error
                else -> android.R.drawable.ic_dialog_info
            }
        } else {
            android.R.drawable.ic_dialog_info
        }

        val isTransitoryStatus = textToNotify != null && (
            textToNotify.contains("download", ignoreCase = true) ||
                textToNotify.contains("offline", ignoreCase = true) ||
                textToNotify.contains("error", ignoreCase = true) ||
                textToNotify.contains("crash", ignoreCase = true) ||
                textToNotify.contains("switched to", ignoreCase = true) ||
                textToNotify.contains("recovering", ignoreCase = true)
            )
        val collapsedText = if (isTransitoryStatus) textToNotify!! else currentAnimationFrame
        val title = "Δ \uD83D\uDC7E ∇"

        val cardTelemetry = if (sensorFusionManager != null) {
            sensorFusionManager.getNotificationTelemetryString(machineStatus)
        } else {
            "📱 Machine status: $machineStatus\n⏱️ Uptime: active"
        }

        val builder = Notification.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(collapsedText)
            .setSmallIcon(iconRes)
            .setSubText("Agentic Gemma Inference")
            .setOnlyAlertOnce(true)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(cardTelemetry)
                    .setBigContentTitle(title)
                    .setSummaryText("Agentic Gemma Inference")
            )

        if (collapsedText.contains("offline", ignoreCase = true) || collapsedText.contains("Model weights offline", ignoreCase = true)) {
            val downloadIntent = Intent(context, GemmaService::class.java).apply {
                action = Constants.ACTION_RECOVER_MODEL
            }
            val pendingIntent = PendingIntent.getService(
                context, 102, downloadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val downloadIcon = Icon.createWithResource(context, android.R.drawable.stat_sys_download)
            val action = Notification.Action.Builder(
                downloadIcon, "Download Weights", pendingIntent
            ).build()
            builder.addAction(action)
        }

        return builder.build()
    }

    fun showConfirmationNotification(event: GhostAgent.AgentEvent.ConfirmationRequired) {
        pendingConfirmations[event.toolName] = ConfirmationData(
            event.toolParams,
            event.originalResponse,
            event.responseChannel
        )

        val confirmIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "com.ghost.api.ACTION_CONFIRM_TOOL"
            putExtra("toolName", event.toolName)
        }
        val denyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "com.ghost.api.ACTION_DENY_TOOL"
            putExtra("toolName", event.toolName)
        }

        val confirmPending = PendingIntent.getBroadcast(
            context, 100, confirmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyPending = PendingIntent.getBroadcast(
            context, 101, denyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("\uD83D\uDEE1\uFE0F Confirm Action")
            .setContentText("Allow Gemma to use ${event.toolName}?")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "Tool: ${event.toolName}\nParams: ${event.toolParams}\n\nRisky action detected. Do you approve?"
                )
            )
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(Notification.Action.Builder(null, "✅ ALLOW", confirmPending).build())
            .addAction(Notification.Action.Builder(null, "❌ DENY", denyPending).build())

        try {
            context.getSystemService(NotificationManager::class.java)?.notify(notificationId, builder.build())
        } catch (e: Exception) {
            Timber.w(e, "Failed to post tool confirmation notification")
        }
    }
}
