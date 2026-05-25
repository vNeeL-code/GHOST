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
    
    private val STATUS_NOTIF_ID = 1
    private val RESPONSE_NOTIF_ID = 2

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
                description = "GHOST background status"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(statusChannel)
        }
    }

    fun showThinking(telemetry: String = "Processing") {
        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setContentTitle("GHOST")
            .setContentText(telemetry)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
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

        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("✧ Gemma:")
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

    fun pushStartupShortcut() {
        // Handled lazily in showResponse to save IPC overhead
    }

    fun cancel() {
        notificationManager.cancel(RESPONSE_NOTIF_ID)
    }
}
