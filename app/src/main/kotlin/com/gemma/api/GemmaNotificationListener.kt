package com.gemma.api

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Notification Listener - Feeds notifications into Gemma's context
 *
 * User is just another notification to react to.
 */
class GemmaNotificationListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.d("NotificationListener created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Timber.d("NotificationListener destroyed")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val timestamp = sbn.postTime

        // Skip our own notifications
        if (pkg == packageName) return

        // Skip low-priority system noise
        if (pkg in IGNORED_PACKAGES) return

        val entry = NotificationEntry(
            packageName = pkg,
            title = title.take(50),
            text = text.take(100),
            timestamp = timestamp
        )

        synchronized(recentNotifications) {
            recentNotifications.addFirst(entry)
            // Keep only last 20
            while (recentNotifications.size > 20) {
                recentNotifications.removeLast()
            }
        }

        Timber.d("Notification: $pkg - $title")

        // TODO: Trigger proactive Gemma response for interesting notifications
        // Could check if Gemma is idle and inject commentary
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Could track dismissals if useful
    }

    data class NotificationEntry(
        val packageName: String,
        val title: String,
        val text: String,
        val timestamp: Long
    ) {
        fun toContextString(): String {
            val appName = packageName.split('.').lastOrNull() ?: packageName
            val timeAgo = (System.currentTimeMillis() - timestamp) / 1000 / 60
            val timeStr = if (timeAgo < 1) "just now" else "${timeAgo}m ago"
            return "[$appName] $title: $text ($timeStr)"
        }
    }

    companion object {
        var instance: GemmaNotificationListener? = null
        private val recentNotifications = ConcurrentLinkedDeque<NotificationEntry>()

        // Packages to ignore (system noise)
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.providers.downloads"
        )

        fun getRecentNotifications(limit: Int = 5): List<String> {
            return synchronized(recentNotifications) {
                recentNotifications.take(limit).map { it.toContextString() }
            }
        }

        fun getAllNotifications(): List<NotificationEntry> {
            return synchronized(recentNotifications) {
                recentNotifications.toList()
            }
        }

        fun clear() {
            synchronized(recentNotifications) {
                recentNotifications.clear()
            }
        }
    }
}
