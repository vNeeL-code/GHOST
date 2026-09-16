package com.ghost.api

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
        
        // Cache reply action if available
        storeReplyAction(pkg, sbn)
        
        // Passive Context Injection: Send to Gemma to process instead of direct TTS
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(Constants.PREF_PASSIVE_TTS, false) && text.isNotBlank()) {
            val isMedia = pkg.contains("music") || pkg.contains("audio") || pkg.contains("player") || title.contains("playing", ignoreCase = true)
            val isMessaging = pkg.contains("chat") || pkg.contains("msg") || pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("discord")

            if (isMessaging) {
                val appName = pkg.split('.').lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg
                var prompt = "Δ 👾 ∇ GHOST: Incoming message on $appName from $title: \"$text\". Briefly tell the user about this message in your own words"
                
                if (replyCache.containsKey(pkg)) {
                    prompt += ", and ask if they would like you to reply to it."
                } else {
                    prompt += "."
                }
                
                // Fire and forget to GemmaService
                GemmaService.instance?.processNotificationContext(prompt)
            }
        }

    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        replyCache.remove(pkg)
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
        
        // Cache for pending reply intents: packageName -> ReplyAction
        private val replyCache = java.util.concurrent.ConcurrentHashMap<String, ReplyAction>()

        data class ReplyAction(
            val pendingIntent: android.app.PendingIntent,
            val remoteInput: android.app.RemoteInput
        )

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

        fun storeReplyAction(pkg: String, sbn: StatusBarNotification) {
            val actions = sbn.notification.actions ?: return
            for (action in actions) {
                val remoteInputs = action.remoteInputs ?: continue
                for (remoteInput in remoteInputs) {
                    if (remoteInput.allowFreeFormInput) {
                        replyCache[pkg] = ReplyAction(action.actionIntent, remoteInput)
                        return
                    }
                }
            }
        }

        fun replyTo(pkg: String, replyText: String): Boolean {
            val replyAction = replyCache[pkg] ?: return false
            val localIntent = android.content.Intent().apply {
                val resultsBundle = android.os.Bundle().apply {
                    putCharSequence(replyAction.remoteInput.resultKey, replyText)
                }
                android.app.RemoteInput.addResultsToIntent(arrayOf(replyAction.remoteInput), this, resultsBundle)
            }
            return try {
                replyAction.pendingIntent.send(instance, 0, localIntent)
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to send reply to $pkg")
                false
            }
        }
    }
}
