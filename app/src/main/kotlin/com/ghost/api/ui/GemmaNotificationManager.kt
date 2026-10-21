package com.ghost.api.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.ghost.api.R
import timber.log.Timber

/**
 * Manages dual-pipe notifications for GHOST:
 * 1. Status Channel: High-frequency system status & premium animations
 * 2. Conversation Channel: Low-frequency AI responses & Bubbles
 */
class GemmaNotificationManager(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    private val STATUS_CHANNEL_ID = "ghost_status_v1"
    private val CONVO_CHANNEL_ID = "ghost_convo_v5"
    
    private val STATUS_NOTIF_ID = 101
    private val RESPONSE_NOTIF_ID = 102

    init {
        pruneStaleChannels()
        createChannels()
    }

    /**
     * Delete channel IDs created by older versions of the app.
     * Android persists channels across installs — this clears the graveyard.
     */
    private fun pruneStaleChannels() {
        val activeIds = setOf(
            STATUS_CHANNEL_ID,
            CONVO_CHANNEL_ID,
            "gemma_instance_service" // foreground service channel owned by GemmaService
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notificationManager.notificationChannels
                .filter { it.id !in activeIds }
                .forEach { stale ->
                    Timber.d("Pruning stale channel: ${stale.id} (${stale.name})")
                    notificationManager.deleteNotificationChannel(stale.id)
                }
        }
    }

    private fun createChannels() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val statusChannel = NotificationChannel(
                STATUS_CHANNEL_ID,
                "GHOST Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GHOST background status and telemetry"
                setShowBadge(false)
            }
            val convoChannel = NotificationChannel(
                CONVO_CHANNEL_ID,
                "GHOST Responses",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Agentic companion responses, interactions, and alerts"
                setShowBadge(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(statusChannel)
            notificationManager.createNotificationChannel(convoChannel)
        }
    }

    fun showThinking(telemetry: String = "Processing") {
        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setContentTitle("GHOST")
            .setContentText(telemetry)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(20000)
            .build()
        notificationManager.notify(STATUS_NOTIF_ID, notification)
    }

    fun updateStatus(title: String, text: String, isOffline: Boolean = false) {
        val icon = if (isOffline) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_popup_sync
        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setSilent(true)
            .build()
        notificationManager.notify(STATUS_NOTIF_ID, notification)
    }

    fun cancelThinking() {
        notificationManager.cancel(STATUS_NOTIF_ID)
    }

    /**
     * Posts the actual AI response.
     * This triggers the heavy Conversation/Bubble logic.
     */
    fun showResponse(response: String, enableBubble: Boolean = false) {
        val cleanResponse = response.replace(Regex("<\\|channel>thought.*?<channel\\|>", RegexOption.DOT_MATCHES_ALL), "").trim()
        val shortResponse = cleanResponse.take(200)

        val cleanDeviceName = com.ghost.api.logic.ContextManager.resolveDeviceCallSign(context)
        val notifTitle = "✧ $cleanDeviceName:"

        val notification = NotificationCompat.Builder(context, CONVO_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(notifTitle)
            .setContentText(shortResponse)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cleanResponse))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(
                context, 0,
                Intent(context, com.ghost.api.MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            ))
            .build()

        notificationManager.notify(RESPONSE_NOTIF_ID, notification)
    }

    /**
     * Posts an interactive notification for opening a URL.
     * Guaranteed fallback on restricted OEM ROMs (RedMagic, MIUI) that block background activity starts.
     */
    fun showUrlNotification(url: String, title: String = "Opening link in browser") {
        val cleanDeviceName = com.ghost.api.logic.ContextManager.resolveDeviceCallSign(context)
        val notifTitle = "✧ $cleanDeviceName: $title"
        val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (System.currentTimeMillis() % 10000).toInt(),
            browserIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CONVO_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(notifTitle)
            .setContentText(url)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Tap to open $url in external browser"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_compass, "Open Browser ↗", pendingIntent)
            .build()
        notificationManager.notify(RESPONSE_NOTIF_ID, notification)
    }

    fun pushStartupShortcut() {
        // No-op: shortcut registration not needed
    }

    fun cancel() {
        notificationManager.cancel(RESPONSE_NOTIF_ID)
    }
}
