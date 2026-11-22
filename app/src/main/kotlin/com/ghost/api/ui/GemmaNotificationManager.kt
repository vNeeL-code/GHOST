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
     * Supports inline generated cards / QR codes via NotificationCompat.BigPictureStyle
     * (with 2:1 white quiet-zone padding so Android System UI never crops square QR finder patterns).
     */
    fun showResponse(
        response: String,
        enableBubble: Boolean = false,
        cardBitmap: android.graphics.Bitmap? = null,
        webviewUrl: String? = null
    ) {
        val cleanResponse = response.replace(Regex("<\\|channel>thought.*?<channel\\|>", RegexOption.DOT_MATCHES_ALL), "").trim()
        val shortResponse = cleanResponse.take(200)

        val cleanDeviceName = com.ghost.api.logic.ContextManager.resolveDeviceCallSign(context)
        val notifTitle = "✧ $cleanDeviceName:"

        val rawBitmap = cardBitmap ?: decodeInlineDataImage(webviewUrl)
        val builder = NotificationCompat.Builder(context, CONVO_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(notifTitle)
            .setContentText(shortResponse)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(
                context, 0,
                Intent(context, com.ghost.api.MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            ))

        if (rawBitmap != null) {
            val paddedBanner = padSquareCardToBanner(rawBitmap)
            builder
                .setLargeIcon(rawBitmap)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(paddedBanner)
                        .bigLargeIcon(null as android.graphics.Bitmap?)
                        .setSummaryText(shortResponse)
                )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(cleanResponse))
        }

        notificationManager.notify(RESPONSE_NOTIF_ID, builder.build())
    }

    private fun decodeInlineDataImage(webviewUrl: String?): android.graphics.Bitmap? {
        if (webviewUrl.isNullOrBlank() || !webviewUrl.startsWith("data:image/")) return null
        return try {
            val base64Data = webviewUrl.substringAfter(",")
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Timber.w(e, "GemmaNotificationManager: Failed to decode inline data:image card")
            null
        }
    }

    /**
     * Android System UI crops BigPictureStyle to ~2:1 landscape.
     * For square/tall generated cards (especially QR codes), pad onto a 2:1 white canvas with
     * quiet-zone margins so scanner finder squares are never clipped in the notification shade.
     */
    private fun padSquareCardToBanner(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val ratio = src.width.toFloat() / src.height.coerceAtLeast(1)
        if (ratio >= 1.75f) return src
        val targetHeight = (src.height * 1.16f).toInt().coerceIn(360, 520)
        val targetWidth = targetHeight * 2
        val padded = android.graphics.Bitmap.createBitmap(targetWidth, targetHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(padded)
        canvas.drawColor(android.graphics.Color.WHITE)
        val innerMax = (targetHeight * 0.86f).toInt()
        val scale = minOf(innerMax.toFloat() / src.width.coerceAtLeast(1), innerMax.toFloat() / src.height.coerceAtLeast(1))
        val drawW = (src.width * scale).toInt()
        val drawH = (src.height * scale).toInt()
        val left = (targetWidth - drawW) / 2
        val top = (targetHeight - drawH) / 2
        val paint = android.graphics.Paint().apply {
            isFilterBitmap = false
            isAntiAlias = false
        }
        canvas.drawBitmap(src, null, android.graphics.Rect(left, top, left + drawW, top + drawH), paint)
        return padded
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
