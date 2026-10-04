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
        if (instance == this) instance = null
        Timber.d("NotificationListener destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Timber.i("NotificationListener connected")
        syncActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
        Timber.d("NotificationListener disconnected")
    }

    fun syncActiveNotifications() {
        try {
            val sbns = activeNotifications ?: return
            for (sbn in sbns) {
                val pkg = sbn.packageName
                if (pkg == packageName || pkg in IGNORED_PACKAGES) continue
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim()
                    ?: extras.getCharSequence("android.title")?.toString()?.trim() ?: ""
                val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim()
                    ?: extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
                    ?: extras.getCharSequence("android.text")?.toString()?.trim() ?: ""
                if (title.isBlank() && text.isBlank()) continue

                val entry = NotificationEntry(
                    packageName = pkg,
                    title = title.take(80),
                    text = text.take(160),
                    timestamp = sbn.postTime
                )
                synchronized(recentNotifications) {
                    if (recentNotifications.none { it.packageName == pkg && it.title == entry.title && it.text == entry.text }) {
                        recentNotifications.addFirst(entry)
                    }
                }
                storeReplyAction(pkg, sbn)
            }
        } catch (e: Exception) {
            Timber.w(e, "NotificationListener: failed to sync active notifications")
        }
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
            val appLabel = resolveAppLabel(instance, packageName)
            val timeAgo = (System.currentTimeMillis() - timestamp) / 1000 / 60
            val timeStr = if (timeAgo < 1) "just now" else "${timeAgo}m ago"
            val body = if (text.isNotBlank() && title.isNotBlank() && !title.equals(text, ignoreCase = true)) {
                "$title: $text"
            } else {
                title.ifBlank { text }
            }
            return "[$appLabel] $body ($timeStr)"
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

        fun ensureConnected(context: android.content.Context) {
            if (instance == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    val comp = android.content.ComponentName(context, GemmaNotificationListener::class.java)
                    requestRebind(comp)
                    Timber.d("GemmaNotificationListener: requested rebind")
                } catch (e: Exception) {
                    Timber.w(e, "Failed to request rebind for GemmaNotificationListener")
                }
            }
        }

        fun resolveAppLabel(context: android.content.Context?, pkg: String): String {
            return when (pkg) {
                "ai.x.grok" -> "Grok"
                "com.google.android.youtube" -> "YouTube"
                "com.whatsapp" -> "WhatsApp"
                "org.telegram.messenger" -> "Telegram"
                "com.discord" -> "Discord"
                "com.google.android.gm" -> "Gmail"
                "com.google.android.apps.messaging" -> "Messages"
                else -> {
                    try {
                        val pm = context?.packageManager
                        if (pm != null) {
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        } else {
                            pkg.split('.').lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg
                        }
                    } catch (e: Exception) {
                        pkg.split('.').lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg
                    }
                }
            }
        }

        fun getActiveNotificationsContext(limit: Int = 6): List<String> {
            val currentInstance = instance
            if (currentInstance != null) {
                try {
                    val sbns = currentInstance.activeNotifications
                    if (!sbns.isNullOrEmpty()) {
                        val activeEntries = mutableListOf<String>()
                        val sorted = sbns.sortedByDescending { it.postTime }
                        for (sbn in sorted) {
                            val pkg = sbn.packageName
                            if (pkg == currentInstance.packageName || pkg in IGNORED_PACKAGES) continue

                            // Skip ongoing media session since audio tracker covers it
                            val isMedia = sbn.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)
                            if (isMedia && sbn.isOngoing) continue

                            val extras = sbn.notification.extras
                            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim()
                                ?: extras.getCharSequence("android.title")?.toString()?.trim() ?: ""
                            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim()
                                ?: extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
                                ?: extras.getCharSequence("android.text")?.toString()?.trim() ?: ""

                            if (title.isBlank() && text.isBlank()) continue

                            val appLabel = resolveAppLabel(currentInstance, pkg)
                            val timeAgo = (System.currentTimeMillis() - sbn.postTime) / 1000 / 60
                            val timeStr = if (timeAgo < 1) "just now" else "${timeAgo}m ago"
                            val body = if (text.isNotBlank() && title.isNotBlank() && !title.equals(text, ignoreCase = true)) {
                                "$title: $text"
                            } else {
                                title.ifBlank { text }
                            }
                            activeEntries.add("[$appLabel] $body ($timeStr)")
                            storeReplyAction(pkg, sbn)
                            if (activeEntries.size >= limit) break
                        }
                        if (activeEntries.isNotEmpty()) return activeEntries
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to query activeNotifications from service")
                }
            }

            return getRecentNotifications(limit)
        }

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
