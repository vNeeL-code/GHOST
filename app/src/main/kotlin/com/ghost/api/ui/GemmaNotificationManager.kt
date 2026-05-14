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
 * 1. Status Channel: High-frequency system status & animations (Low Importance)
 * 2. Conversation Channel: Low-frequency AI responses & Bubbles (High Importance)
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
            // 1. Status Channel (Silent, Low Priority for Animations)
            val statusChannel = NotificationChannel(
                STATUS_CHANNEL_ID,
                "System Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GHOST background status and animations"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(statusChannel)

            // 2. Conversation Channel (High Priority for Bubbles)
            val convoChannel = NotificationChannel(
                CONVO_CHANNEL_ID,
                "✧ Conversations",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "GHOST AI messaging pipeline"
                setAllowBubbles(true)
                setShowBadge(false) // suppresses the white outer ring on the bubble head
            }
            notificationManager.createNotificationChannel(convoChannel)
        }
    }

    /**
     * Updates the status notification. 
     * Safe to call at high frequency (e.g. for animations).
     */
    fun showThinking(statusText: String = "Processing...") {
        try {
            val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
                .setContentTitle("Δ 👾 ∇")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true) // Prevent sound/vibration on every animation frame
                .build()
            notificationManager.notify(STATUS_NOTIF_ID, notification)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update status")
        }
    }

    fun cancelThinking() {
        notificationManager.cancel(STATUS_NOTIF_ID)
    }

    /**
     * Posts the actual AI response.
     * This triggers the heavy Conversation/Bubble logic.
     */
    fun showResponse(response: String) {
        val cleanResponse = response.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
        val shortResponse = cleanResponse.take(100)
        val shortcutId = "ghost_convo_final"

        // 1. Build Person
        val person = Person.Builder()
            .setName("Δ 👾 ∇")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setImportant(true)
            .setUri("mailto:gemma@ghost.ai")
            .build()

        // 2. Build Shortcut (Lazy push)
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel("✧")
            .setLongLived(true)
            .setPerson(person)
            .setIntent(Intent(context, com.ghost.api.MainActivity::class.java).apply { action = Intent.ACTION_VIEW })
            .setCategories(setOf("android.shortcut.conversation"))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

        // 3. Build Bubble Metadata
        val bubbleIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, com.ghost.api.BubbleActivity::class.java).apply { action = Intent.ACTION_VIEW },
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val bubbleData = NotificationCompat.BubbleMetadata.Builder(bubbleIntent, IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setDesiredHeight(600)
            .setSuppressNotification(true) // Don't flash the notification when bubble is open
            .build()

        // 4. Build Notification
        val notification = NotificationCompat.Builder(context, CONVO_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setShortcutId(shortcutId)
            .addPerson(person)
            .setStyle(NotificationCompat.MessagingStyle(person)
                .addMessage(shortResponse, System.currentTimeMillis(), person))
            .setBubbleMetadata(bubbleData)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
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
