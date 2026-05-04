package com.ghost.api.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.ghost.api.Constants
import com.ghost.api.GemmaService
import timber.log.Timber

class GemmaNotificationManager(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL_ID = "gemma_responses"
    private val RESPONSE_NOTIF_ID = 2

    init {
        createChannel()
    }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "\u2727 GHOST Responses",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "On-device AI assistant responses"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showThinking() {
        try {
            val notification = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
                    .setContentTitle("Δ 👾 ∇")
                    .setContentText("Δ ✧ 🧠 ✧ Thinking...")
                    .setSmallIcon(android.R.drawable.ic_popup_sync)
                    .setProgress(0, 0, true)
                    .setOngoing(true)  // Can't swipe away while thinking
                    .build()
            } else {
                Notification.Builder(context)
                    .setContentTitle("Δ 👾 ∇")
                    .setContentText("Δ ✧ 🧠 ✧ Thinking...")
                    .setSmallIcon(android.R.drawable.ic_popup_sync)
                    .setProgress(0, 0, true)
                    .setOngoing(true)
                    .build()
            }
            notificationManager.notify(RESPONSE_NOTIF_ID, notification)
        } catch (e: Exception) {
            Timber.e(e, "Failed to show thinking notification")
        }
    }

    fun showResponse(response: String) {
        try {
            // Extract just the main content for notification title
            val shortResponse = extractMainContent(response).take(100)

            val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                Notification.Builder(context)
            }

            // Clean text for TTS (strip think tags, tool markers)
            val ttsText = cleanForTTS(response)

            // Build notification with action buttons
            val notification = builder
                .setSubText("GHOST Agentic Hardware")
                .setContentTitle("Δ 👾 ∇")
                .setContentText(shortResponse)
                .setStyle(Notification.BigTextStyle()
                    .bigText(response)
                    .setBigContentTitle("Δ 👾 ∇")
                    .setSummaryText("GHOST Agentic Hardware"))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(false)  // Don't auto-dismiss
                .setOngoing(false)     // Can swipe away now
                .addAction(buildCopyAction(response))
                .addAction(buildReadAgainAction(ttsText))
                .build()

            notificationManager.notify(RESPONSE_NOTIF_ID, notification)
            Timber.d("Response notification shown: ${shortResponse.take(30)}...")
        } catch (e: Exception) {
            Timber.e(e, "Failed to show response notification")
        }
    }


    private fun extractMainContent(response: String): String {
        // Try to extract the 🔴 block content for cleaner display
        return if (response.contains("🔴")) {
            val start = response.indexOf("🔴") + 1
            val end = response.indexOf("🟦").takeIf { it > start }
                ?: response.indexOf("∇", start).takeIf { it > start }
                ?: response.length
            response.substring(start, end).trim()
        } else {
            response
        }
    }

    private fun cleanForTTS(response: String): String {
        // Remove tool markers for clean TTS
        return response
            .replace(Regex("\\[\\[[A-Z_]+(?::[^\\]]+)?\\]\\]"), "")
            .trim()
    }

    private fun buildCopyAction(text: String): Notification.Action {
        val copyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "com.ghost.api.ACTION_COPY"
            putExtra("text", text)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(
            android.R.drawable.ic_menu_save,
            "Copy",
            pendingIntent
        ).build()
    }

    private fun buildReadAgainAction(text: String): Notification.Action {
        val replayIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "com.ghost.api.ACTION_TTS_REPLAY"
            putExtra("text", text)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 2, replayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return Notification.Action.Builder(
            android.R.drawable.ic_media_play,
            "Read Again",
            pendingIntent
        ).build()
    }

    fun cancel() {
        notificationManager.cancel(RESPONSE_NOTIF_ID)
    }
}
