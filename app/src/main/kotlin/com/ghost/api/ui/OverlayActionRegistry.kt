package com.ghost.api.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import com.ghost.api.Constants
import com.ghost.api.GemmaAccessibilityService
import com.ghost.api.GemmaService
import com.ghost.api.MainActivity
import timber.log.Timber

/**
 * OHO+ (One Hand Operation+) Unified Action & Puck Configuration Registry.
 *
 * Replaces the legacy hardcoded app-only shortcuts and heavy CS:GO AppReel with:
 * - Dual Edge Handle gesture bindings (Short/Long/Up/Down swipes on Left & Right edges)
 * - Per-Puck enable/disable toggles so disabled pucks consume zero CPU/GPU canvas draw calls
 * - Custom puck radial spread and vertical one-hand offset
 * - System-wide action execution (Back, Forward, Home, Recents, Last App, Quick Settings,
 *   Notifications, Flashlight, Screenshot, Screen Recorder, Media Play/Pause/Next/Prev, Mute,
 *   Scratchpad, App Folder, or any installed App).
 */
object OverlayActionRegistry {

    // Action Identifiers
    const val ACTION_NONE = "NONE"
    const val ACTION_SUMMON_GHOST = "SUMMON_GHOST"
    const val ACTION_VOICE_PTT = "VOICE_PTT"
    const val ACTION_OPEN_CHAT = "OPEN_CHAT"
    const val ACTION_SCRATCHPAD = "SCRATCHPAD"
    const val ACTION_APP_FOLDER = "APP_FOLDER"

    const val ACTION_NAV_BACK = "NAV_BACK"
    const val ACTION_NAV_FORWARD = "NAV_FORWARD"
    const val ACTION_NAV_HOME = "NAV_HOME"
    const val ACTION_NAV_RECENTS = "NAV_RECENTS"
    const val ACTION_LAST_APP = "LAST_APP"

    const val ACTION_QUICK_SETTINGS = "QUICK_SETTINGS"
    const val ACTION_NOTIFICATIONS = "NOTIFICATIONS"
    const val ACTION_FLASHLIGHT = "FLASHLIGHT"
    const val ACTION_SCREENSHOT = "SCREENSHOT"
    const val ACTION_SCREEN_RECORDER = "SCREEN_RECORDER"

    const val ACTION_MEDIA_PLAY_PAUSE = "MEDIA_PLAY_PAUSE"
    const val ACTION_MEDIA_NEXT = "MEDIA_NEXT"
    const val ACTION_MEDIA_PREV = "MEDIA_PREV"
    const val ACTION_VOLUME_MUTE = "VOLUME_MUTE"

    const val APP_PREFIX = "APP:"

    data class BuiltInAction(
        val id: String,
        val label: String,
        val shortGlyph: String,
        val category: String
    )

    val BUILT_IN_ACTIONS: List<BuiltInAction> = listOf(
        BuiltInAction(ACTION_NONE, "None (Disabled)", "—", "General"),
        BuiltInAction(ACTION_APP_FOLDER, "App Drawer", "📂", "Quick Access"),
        BuiltInAction(ACTION_SCRATCHPAD, "Notepad", "📝", "Quick Access"),
        BuiltInAction(ACTION_FLASHLIGHT, "Flashlight", "🔦", "Tools"),
        BuiltInAction(ACTION_SCREENSHOT, "Screenshot", "📸", "Tools"),

        BuiltInAction(ACTION_NAV_BACK, "Back", "◀", "Navigation"),
        BuiltInAction(ACTION_NAV_HOME, "Home", "⏺", "Navigation"),
        BuiltInAction(ACTION_NAV_RECENTS, "Recent Apps", "▣", "Navigation"),
        BuiltInAction(ACTION_LAST_APP, "Switch Previous App", "⇄", "Navigation"),

        BuiltInAction(ACTION_QUICK_SETTINGS, "Quick Settings", "⚙️", "System"),
        BuiltInAction(ACTION_NOTIFICATIONS, "Notification Shade", "🔔", "System"),

        BuiltInAction(ACTION_MEDIA_PLAY_PAUSE, "Play / Pause", "⏯", "Media"),
        BuiltInAction(ACTION_MEDIA_NEXT, "Next Track", "⏭", "Media"),
        BuiltInAction(ACTION_MEDIA_PREV, "Previous Track", "⏮", "Media"),
        BuiltInAction(ACTION_VOLUME_MUTE, "Mute Audio", "🔇", "Media")
    )

    private val builtInMap = BUILT_IN_ACTIONS.associateBy { it.id }

    enum class PuckId(
        val key: String,
        val title: String,
        val enabledPrefKey: String,
        val colorHex: String
    ) {
        CENTER("center", "Center Cobalt Sparkle", Constants.PREF_PUCK_CENTER_ENABLED, "#8BB4F6"),
        TOP("top", "Top Orange Star", Constants.PREF_PUCK_TOP_ENABLED, "#F97316"),
        BOTTOM("bottom", "Bottom Cyan Star", Constants.PREF_PUCK_BOTTOM_ENABLED, "#00F0FF"),
        TL("tl", "Top-Left Blue Puck", Constants.PREF_PUCK_TL_ENABLED, "#4285F4"),
        TR("tr", "Top-Right Red Puck", Constants.PREF_PUCK_TR_ENABLED, "#EA4335"),
        BL("bl", "Bottom-Left Yellow Puck", Constants.PREF_PUCK_BL_ENABLED, "#FBBC05"),
        BR("br", "Bottom-Right Green Puck", Constants.PREF_PUCK_BR_ENABLED, "#34A853")
    }

    enum class DirectionSlot(val key: String, val label: String, val arrowGlyph: String) {
        TAP("tap", "Tap", "●"),
        UP("up", "Swipe Up", "▲"),
        DOWN("down", "Swipe Down", "▼"),
        LEFT("left", "Swipe Left", "◀"),
        RIGHT("right", "Swipe Right", "▶")
    }

    @Volatile
    private var torchEnabled = false

    fun prefKeyForSlot(puck: PuckId, slot: DirectionSlot): String {
        return "shortcut_pkg_${puck.key}_${slot.key}"
    }

    fun isPuckEnabled(prefs: SharedPreferences, puck: PuckId): Boolean {
        return prefs.getBoolean(puck.enabledPrefKey, true)
    }

    fun defaultActionForSlot(puck: PuckId, slot: DirectionSlot): String {
        return when (puck) {
            PuckId.CENTER -> when (slot) {
                DirectionSlot.TAP -> ACTION_APP_FOLDER
                DirectionSlot.UP -> ACTION_SCRATCHPAD
                DirectionSlot.DOWN -> ACTION_FLASHLIGHT
                else -> ACTION_NONE
            }
            PuckId.TOP -> when (slot) {
                DirectionSlot.TAP -> ACTION_APP_FOLDER
                DirectionSlot.UP -> ACTION_QUICK_SETTINGS
                DirectionSlot.DOWN -> ACTION_NOTIFICATIONS
                DirectionSlot.LEFT -> ACTION_MEDIA_PREV
                DirectionSlot.RIGHT -> ACTION_MEDIA_NEXT
            }
            PuckId.BOTTOM -> when (slot) {
                DirectionSlot.TAP -> ACTION_SCRATCHPAD
                DirectionSlot.UP -> ACTION_FLASHLIGHT
                DirectionSlot.DOWN -> ACTION_SCREENSHOT
                DirectionSlot.LEFT -> ACTION_NAV_BACK
                DirectionSlot.RIGHT -> ACTION_LAST_APP
            }
            PuckId.TL -> when (slot) {
                DirectionSlot.TAP -> ACTION_NAV_BACK
                DirectionSlot.UP -> ACTION_NOTIFICATIONS
                DirectionSlot.DOWN -> ACTION_NAV_RECENTS
                DirectionSlot.LEFT -> ACTION_MEDIA_PREV
                DirectionSlot.RIGHT -> ACTION_LAST_APP
            }
            PuckId.TR -> when (slot) {
                DirectionSlot.TAP -> ACTION_MEDIA_PLAY_PAUSE
                DirectionSlot.UP -> ACTION_QUICK_SETTINGS
                DirectionSlot.DOWN -> ACTION_SCREENSHOT
                DirectionSlot.LEFT -> ACTION_LAST_APP
                DirectionSlot.RIGHT -> ACTION_MEDIA_NEXT
            }
            PuckId.BL -> when (slot) {
                DirectionSlot.TAP -> ACTION_FLASHLIGHT
                DirectionSlot.UP -> ACTION_NAV_HOME
                DirectionSlot.DOWN -> ACTION_VOLUME_MUTE
                DirectionSlot.LEFT -> ACTION_NAV_BACK
                DirectionSlot.RIGHT -> ACTION_SCRATCHPAD
            }
            PuckId.BR -> when (slot) {
                DirectionSlot.TAP -> ACTION_APP_FOLDER
                DirectionSlot.UP -> ACTION_SCRATCHPAD
                DirectionSlot.DOWN -> ACTION_SCREENSHOT
                DirectionSlot.LEFT -> ACTION_LAST_APP
                DirectionSlot.RIGHT -> ACTION_NOTIFICATIONS
            }
        }
    }

    fun getSlotAction(prefs: SharedPreferences, puck: PuckId, slot: DirectionSlot): String {
        val raw = prefs.getString(prefKeyForSlot(puck, slot), null)
        if (!raw.isNullOrBlank()) return raw
        if (puck == PuckId.CENTER) {
            val legacySparkle = prefs.getString("BIND_Sparkle_${slot.name}", null)
            if (!legacySparkle.isNullOrBlank()) return legacySparkle
        }
        // Legacy fallback for single-key puck pref (`shortcut_pkg_tl`, etc.)
        if (slot == DirectionSlot.TAP) {
            val legacy = prefs.getString("shortcut_pkg_${puck.key}", null)
            if (!legacy.isNullOrBlank()) return legacy
        }
        return defaultActionForSlot(puck, slot)
    }

    fun setSlotAction(prefs: SharedPreferences, puck: PuckId, slot: DirectionSlot, actionSpec: String) {
        prefs.edit().putString(prefKeyForSlot(puck, slot), actionSpec).apply()
    }

    fun isBuiltInAction(actionSpec: String): Boolean {
        return builtInMap.containsKey(actionSpec)
    }

    fun extractPackageName(actionSpec: String): String? {
        if (actionSpec.isBlank() || isBuiltInAction(actionSpec)) return null
        return if (actionSpec.startsWith(APP_PREFIX)) {
            actionSpec.removePrefix(APP_PREFIX).takeIf { it.isNotBlank() }
        } else {
            actionSpec
        }
    }

    fun getDisplayLabel(context: Context?, actionSpec: String): String {
        builtInMap[actionSpec]?.let { return "${it.shortGlyph}  ${it.label}" }
        val pkg = extractPackageName(actionSpec) ?: return "None"
        if (context == null) return pkg
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
    }

    fun getShortGlyph(actionSpec: String): String {
        return builtInMap[actionSpec]?.shortGlyph ?: "✧"
    }

    /**
     * Executes an OHO+ action specification (either a built-in system/GHOST action or an app launch).
     * Returns true if the overlay should auto-dismiss after execution.
     */
    fun executeAction(
        context: Context,
        actionSpec: String,
        onSummonOverlay: (() -> Unit)? = null,
        onOpenAppFolder: (() -> Unit)? = null
    ): Boolean {
        if (actionSpec.isBlank() || actionSpec == ACTION_NONE) return false

        when (actionSpec) {
            ACTION_SUMMON_GHOST -> {
                if (onSummonOverlay != null) {
                    onSummonOverlay()
                } else {
                    GemmaService.instance?.summonOverlay()
                }
                return false
            }

            ACTION_VOICE_PTT -> {
                GemmaService.instance?.summonOverlay()
                return false
            }

            ACTION_OPEN_CHAT -> {
                return try {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                    true
                } catch (e: Exception) {
                    Timber.e(e, "Failed to open MainActivity from overlay action")
                    false
                }
            }

            ACTION_SCRATCHPAD -> {
                GemmaService.instance?.overlayManager?.showScratchpad()
                return false
            }

            ACTION_APP_FOLDER -> {
                if (onOpenAppFolder != null) {
                    onOpenAppFolder()
                } else {
                    GemmaService.instance?.overlayManager?.showQuickAppFolder()
                }
                return false
            }

            ACTION_NAV_BACK -> {
                val a11y = GemmaAccessibilityService.instance
                if (a11y != null) {
                    a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                } else {
                    Toast.makeText(context, "Enable GHOST Accessibility for Back gesture", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            ACTION_NAV_FORWARD -> {
                dispatchMediaOrSystemKey(context, KeyEvent.KEYCODE_FORWARD)
                return true
            }

            ACTION_NAV_HOME -> {
                val a11y = GemmaAccessibilityService.instance
                if (a11y != null) {
                    a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                } else {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(homeIntent)
                }
                return true
            }

            ACTION_NAV_RECENTS -> {
                val a11y = GemmaAccessibilityService.instance
                if (a11y != null) {
                    a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                } else {
                    Toast.makeText(context, "Enable GHOST Accessibility for Recents", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            ACTION_LAST_APP -> {
                val a11y = GemmaAccessibilityService.instance
                if (a11y != null) {
                    a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        GemmaAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    }, 180L)
                } else {
                    Toast.makeText(context, "Enable GHOST Accessibility for Last App switch", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            ACTION_QUICK_SETTINGS -> {
                val a11y = GemmaAccessibilityService.instance
                if (a11y != null) {
                    a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                } else {
                    Toast.makeText(context, "Enable GHOST Accessibility for Quick Settings", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            ACTION_NOTIFICATIONS -> {
                val a11y = GemmaAccessibilityService.instance
                if (a11y != null) {
                    a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                } else {
                    Toast.makeText(context, "Enable GHOST Accessibility for Notifications", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            ACTION_FLASHLIGHT -> {
                toggleFlashlight(context)
                return false
            }

            ACTION_SCREENSHOT -> {
                val a11y = GemmaAccessibilityService.instance
                if (a11y != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                } else {
                    Toast.makeText(context, "Enable GHOST Accessibility for Screenshot", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            ACTION_SCREEN_RECORDER -> {
                launchScreenRecorder(context)
                return true
            }

            ACTION_MEDIA_PLAY_PAUSE -> {
                dispatchMediaOrSystemKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                return false
            }

            ACTION_MEDIA_NEXT -> {
                dispatchMediaOrSystemKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                return false
            }

            ACTION_MEDIA_PREV -> {
                dispatchMediaOrSystemKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                return false
            }

            ACTION_VOLUME_MUTE -> {
                return try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_TOGGLE_MUTE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    false
                } catch (e: Exception) {
                    Timber.w(e, "Failed to toggle mute")
                    false
                }
            }
        }

        val pkg = extractPackageName(actionSpec) ?: return false
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                true
            } else {
                Toast.makeText(context, "App not found: $pkg", Toast.LENGTH_SHORT).show()
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch package $pkg")
            false
        }
    }

    private fun toggleFlashlight(context: Context) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            val cameraId = cm.cameraIdList.firstOrNull() ?: return
            val nextState = !torchEnabled
            cm.setTorchMode(cameraId, nextState)
            torchEnabled = nextState
        } catch (e: Exception) {
            Timber.w(e, "Failed to toggle flashlight")
            Toast.makeText(context, "Flashlight unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dispatchMediaOrSystemKey(context: Context, keyCode: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (e: Exception) {
            Timber.w(e, "Failed to dispatch key event $keyCode")
        }
    }

    private fun launchScreenRecorder(context: Context) {
        val candidates = listOf(
            Intent("com.android.systemui.screenrecord.START"),
            Intent("android.media.projection.action.LAUNCH_CAPTURE_CONTENT_ACTIVITY_FOR_NOTE")
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        // Fallback: open Quick Settings shade so user can tap Screen Record tile immediately
        GemmaAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }
}
