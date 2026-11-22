package com.ghost.api.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.ghost.api.GemmaService
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified Corner Flavor-Text HUD Dispatcher.
 *
 * Replaces legacy Android Toast.makeText(...) calls with cohesive corner HUD micro-notices,
 * while preserving:
 * 1. Automatic fallback to system Toast when SYSTEM_ALERT_WINDOW overlay permission is missing
 *    or GemmaService is not yet bound (so errors/confirmations never vanish silently).
 * 2. TalkBack accessibility announcements via the HUD overlay view.
 * 3. Zero-LLM, debounced non-verbal ambient "barks" (e.g. 👋 on unlock, 👁️ on sensor/screen watch)
 *    that never pollute conversation history or burn context tokens.
 */
object GhostHudDispatcher {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val barkCooldowns = ConcurrentHashMap<String, Long>()

    private const val SHORT_DURATION_MS = 2200L
    private const val LONG_DURATION_MS = 3800L

    /**
     * Routes a status ping, error notice, or quick confirmation to the corner flavor-text HUD.
     * Falls back to Android Toast if overlay permission is unavailable or service is offline.
     */
    fun show(context: Context, message: String, isLong: Boolean = false) {
        if (message.isBlank()) return
        val appCtx = context.applicationContext
        val durationMs = if (isLong) LONG_DURATION_MS else SHORT_DURATION_MS

        mainHandler.post {
            val canOverlay = Settings.canDrawOverlays(appCtx)
            val service = GemmaService.instance
            val shown = if (canOverlay && service != null) {
                service.showHudMessage(message, durationMs)
            } else {
                false
            }
            if (!shown) {
                Toast.makeText(
                    appCtx,
                    message,
                    if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Dispatches a zero-LLM, non-verbal ambient micro-signal ("bark") to the corner HUD.
     * Examples: 👋 on device unlock/pickup, 👁️ on active sensor/screen watch, emotion glyph signals.
     *
     * Never writes to conversation history and never falls back to intrusive system Toasts.
     */
    fun bark(
        context: Context,
        glyph: String,
        debounceKey: String? = null,
        debounceMs: Long = 0L,
        durationMs: Long = 2000L
    ) {
        if (glyph.isBlank()) return
        if (debounceKey != null && debounceMs > 0L) {
            val now = System.currentTimeMillis()
            val prev = barkCooldowns[debounceKey] ?: 0L
            if (now - prev < debounceMs) return
            barkCooldowns[debounceKey] = now
        }

        val appCtx = context.applicationContext
        mainHandler.post {
            if (!Settings.canDrawOverlays(appCtx)) return@post
            GemmaService.instance?.showHudMessage(glyph, durationMs, announceForA11y = false)
        }
    }
}
