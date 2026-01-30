package com.gemma.api.hardware

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import timber.log.Timber
import java.util.Locale

/**
 * System Tools - Apps, Media, and Volume
 */
class SystemToolSet(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val packageManager: PackageManager = context.packageManager

    // Cache of installed apps (lazy load or refresh on demand)
    private var appListCache: List<AppInfo>? = null

    data class AppInfo(val label: String, val packageName: String)

    /**
     * Open an app by name (Fuzzy Match)
     */
    fun openApp(query: String): String {
        val apps = getInstalledApps()
        val bestMatch = findBestMatch(query, apps)

        return if (bestMatch != null) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(bestMatch.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "Launched ${bestMatch.label}"
                } else {
                    "Could not launch ${bestMatch.label} (No UI found)"
                }
            } catch (e: Exception) {
                Timber.e(e, "Launch failed")
                "Failed to launch ${bestMatch.label}: ${e.message}"
            }
        } else {
            "App not found matching '$query'"
        }
    }

    /**
     * Media Controls using AudioManager key injection
     */
    fun mediaControl(action: String): String {
        val keyEvent = when (action.uppercase(Locale.ROOT)) {
            "PLAY", "PAUSE", "PLAYPAUSE", "TOGGLE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "NEXT", "SKIP" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "PREVIOUS", "PREV", "BACK" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "STOP" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return "Unknown media command: $action"
        }

        return try {
            // Emulate press down and up
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyEvent)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyEvent)
            
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
            
            "Media Action Sent: $action"
        } catch (e: Exception) {
            Timber.e(e, "Media control failed")
            "Media failed: ${e.message}"
        }
    }

    private fun getInstalledApps(): List<AppInfo> {
        if (appListCache == null) {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            appListCache = resolveInfos.map {
                val label = it.loadLabel(packageManager).toString()
                val pkg = it.activityInfo.packageName
                AppInfo(label, pkg)
            }
        }
        return appListCache!!
    }

    private fun findBestMatch(query: String, apps: List<AppInfo>): AppInfo? {
        val lowerQuery = query.lowercase(Locale.ROOT)
        // 1. Exact Match
        apps.find { it.label.equals(query, ignoreCase = true) }?.let { return it }
        
        // 2. Starts With
        apps.find { it.label.startsWith(query, ignoreCase = true) }?.let { return it }
        
        // 3. Contains
        apps.find { it.label.contains(query, ignoreCase = true) }?.let { return it }
        
        // 4. Levenshtein for typos? (Simple implementation for now: minimal checks)
        // If simple checks fail, return null for now to avoid wrong launches.
        return null
    }
}
