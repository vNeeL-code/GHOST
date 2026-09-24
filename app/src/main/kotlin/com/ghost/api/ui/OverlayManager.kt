package com.ghost.api.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.ghost.api.Constants
import com.ghost.api.GemmaService
import timber.log.Timber

/**
 * Manages the floating input overlay for "no UI" mode.
 * Supports three styles:
 * - CLASSIC: Original horizontal input bar (STT-based)
 * - PILL: Gemini-style expandable pill (STT-based)
 * - SPARKLE: Minimal bar with ✧ for direct audio recording (audio-first)
 */
class OverlayManager(private val context: Context) {

    enum class OverlayStyle {
        CLASSIC,  // Original horizontal bar
        PILL,     // Gemini-style expandable pill
        SPARKLE   // Minimal ✧ bar with direct audio recording
    }

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayInputView? = null
    private var pillView: PillOverlayView? = null
    private var inputOverlay: InputOverlay? = null
    private var ghostWorkIndicator: GhostWorkIndicatorOverlay? = null
    private var edgeNubOverlay: EdgeNubOverlay? = null
    private val activeWorkCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private var currentStyle: OverlayStyle = OverlayStyle.SPARKLE
    private var isShowing = false

    // Callbacks
    private var audioQueryCallback: ((ByteArray) -> Unit)? = null

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        AppReelOverlay.AppListCache.preload(context)
    }

    fun setStyle(style: OverlayStyle) {
        if (isShowing) {
            Timber.w("Cannot change style while overlay is showing")
            return
        }
        currentStyle = style
        Timber.i("Overlay style set to: $style")
    }

    /**
     * Set callback for audio queries (used by SPARKLE style)
     */
    fun setAudioQueryCallback(callback: (ByteArray) -> Unit) {
        audioQueryCallback = callback
    }

    fun canDrawOverlay(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun attachEdgeNub(onSummon: () -> Unit) {
        if (!canDrawOverlay()) return
        val wm = windowManager ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (edgeNubOverlay == null) {
                edgeNubOverlay = EdgeNubOverlay(context, wm, onSummon)
            }
            edgeNubOverlay?.attach()
            if (isShowing) {
                edgeNubOverlay?.setNubVisibility(false)
            }
        }
    }

    fun detachEdgeNub() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            edgeNubOverlay?.detach()
            edgeNubOverlay = null
        }
    }

    fun setEdgeNubVisible(visible: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            edgeNubOverlay?.setNubVisibility(visible)
        }
    }

    private var lastTextQueryCallback: ((String) -> Unit)? = null

    fun showOverlay(onQuery: (String) -> Unit) {
        if (isShowing) {
            Timber.d("Overlay already showing")
            return
        }

        if (!canDrawOverlay()) {
            Timber.w("No overlay permission")
            return
        }

        lastTextQueryCallback = onQuery

        try {
            AvatarWallpaperService.isOverlayShowing = true
            when (currentStyle) {
                OverlayStyle.CLASSIC -> showClassicOverlay(onQuery)
                OverlayStyle.PILL -> showPillOverlay(onQuery)
                OverlayStyle.SPARKLE -> showInputOverlay(onQuery)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to show overlay")
            // CRITICAL: Reset state if show*() failed, preventing stuck isShowing flag
            isShowing = false
            AvatarWallpaperService.isOverlayShowing = false
            overlayView = null
            pillView = null
            inputOverlay = null
            edgeNubOverlay?.setNubVisibility(true)
        }
    }

    private fun showInputOverlay(onTextQuery: (String) -> Unit) {
        inputOverlay = InputOverlay(
            context = context,
            onTextQuery = { query ->
                onTextQuery(query)
                hideOverlay()
            },
            onAudioQuery = { audio ->
                audioQueryCallback?.invoke(audio)
                hideOverlay()
            },
            onDismiss = {
                hideOverlay()
            },
            onFocusRequest = {
                requestOverlayFocus()
            }
        )

        val params = getInteractiveLayoutParams().apply {
            val (screenHeight, screenWidth) = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bounds = windowManager!!.currentWindowMetrics.bounds
                bounds.height() to bounds.width()
            } else {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager!!.defaultDisplay.getRealMetrics(dm)
                dm.heightPixels to dm.widthPixels
            }
            val isLandscape = screenWidth > screenHeight
            
            if (isLandscape) {
                // In landscape, match screen height and apply upward lift so top orange touches tips with the app reel
                height = screenHeight
                gravity = Gravity.CENTER
                y = -dpToPx(38) // Upward lift: closes gap to app reel, pulls bottom cyan 136dp away from bottom edge
            } else {
                // Anchor to TOP in portrait to prevent keyboard resize jumps
                height = dpToPx(520)
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                val windowHeight = dpToPx(520)
                y = (screenHeight - windowHeight) / 2 - dpToPx(15)
            }
        }

        // Add AppReel beneath InputOverlay in WindowManager so Orange Star takes Z-order priority
        try {
            val wm = windowManager
            if (wm != null) {
                val reel = AppReelOverlay(
                    context = context,
                    windowManager = wm,
                    onDismiss = { hideAppReel() }
                ).apply {
                    visibility = View.GONE
                }
                appReelOverlay = reel
                wm.addView(reel, reel.windowParams)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to preload AppReel beneath InputOverlay")
        }

        windowManager?.addView(inputOverlay, params)
        isShowing = true

        inputOverlay?.post {
            // Keyboard will spawn only on explicit tap now
            // inputOverlay?.focusInput()
        }

        Timber.i("Input overlay shown")
    }

    private fun showClassicOverlay(onQuery: (String) -> Unit) {
        overlayView = OverlayInputView(context) { query ->
            onQuery(query)
            hideOverlay()
        }

        val params = getInteractiveLayoutParams().apply {
            gravity = Gravity.CENTER
            this.y = -200
        }

        windowManager?.addView(overlayView, params)
        isShowing = true

        overlayView?.post {
            // Keyboard will spawn only on explicit tap now
            // overlayView?.focusInput()
        }

        Timber.i("Classic overlay shown")
    }

    private fun showPillOverlay(onQuery: (String) -> Unit) {
        pillView = PillOverlayView(
            context = context,
            onQuery = { query ->
                onQuery(query)
                // Don't hide - let it show thinking state
            },
            onDismiss = {
                hideOverlay()
            }
        )

        // Switch to interactive params so we can actually tap the pill!
        val params = getInteractiveLayoutParams().apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            this.y = dpToPx(10) // Small top margin
            width = WindowManager.LayoutParams.WRAP_CONTENT
        }

        windowManager?.addView(pillView, params)
        isShowing = true

        Timber.i("Pill overlay shown")
    }

    /**
     * Show response in pill overlay (only works in PILL mode)
     */
    fun showResponse(text: String) {
        pillView?.showResponse(text)
    }
    
    fun appendToken(token: String) {
        pillView?.appendResponseToken(token)
    }

    /**
     * Set the overlay's thinking state (pulsing sparkle, disabled input)
     */
    fun setThinking(thinking: Boolean) {
        inputOverlay?.setThinking(thinking)
    }

    fun setLoading(loading: Boolean) {
        inputOverlay?.setLoading(loading)
    }

    /**
     * Show error in pill overlay
     */
    fun showError(error: String) {
        pillView?.showError(error)
        if (inputOverlay != null) {
            Toast.makeText(context, "Inference: $error", Toast.LENGTH_LONG).show()
            hideOverlay()
        }
    }

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    fun setAppInForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (inForeground) {
            // Dismiss corner indicator immediately so it doesn't double-render over foreground chat
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ghostWorkIndicator?.hide(force = true)
            }
        }
    }

    /**
     * Shows the Destiny Ghost HUD work signal in the screen corner during background operations.
     */
    fun showWorkSignal(tag: String = "WORKING", durationMs: Long = 0) {
        if (!canDrawOverlay()) return
        if (isAppInForeground) {
            // When in foreground chat screen, the indicator is rendered directly inside ChatScreen down there
            return
        }

        if (durationMs == 0L) {
            activeWorkCounter.incrementAndGet()
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val wm = windowManager ?: return@post
            if (ghostWorkIndicator == null) {
                ghostWorkIndicator = GhostWorkIndicatorOverlay(context, wm)
            }
            ghostWorkIndicator?.show(tag, durationMs)
        }
    }

    /**
     * Hides the Destiny Ghost HUD work signal when background operations complete.
     */
    fun hideWorkSignal(force: Boolean = false) {
        val count = if (force) 0 else activeWorkCounter.decrementAndGet()
        if (count <= 0) {
            activeWorkCounter.set(0)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ghostWorkIndicator?.hide(force)
            }
        }
    }


    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    fun hideOverlay() {
        if (!isShowing) return

        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            overlayView?.let { view ->
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                view.cleanup()
                windowManager?.removeView(view)
            }
            overlayView = null

            pillView?.let { view ->
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                view.cleanup()
                windowManager?.removeView(view)
            }
            pillView = null

            inputOverlay?.let { view ->
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                view.cleanup()
                windowManager?.removeView(view)
            }
            inputOverlay = null

            // Clean up any satellite overlays (App Reel tape, Scratchpad)
            hideAppReel()
            hideScratchpad()

            isShowing = false
            AvatarWallpaperService.isOverlayShowing = false
            edgeNubOverlay?.setNubVisibility(true)
            Timber.i("Overlay hidden")
        } catch (e: Exception) {
            Timber.e(e, "Failed to hide overlay")
            isShowing = false
            AvatarWallpaperService.isOverlayShowing = false
            overlayView = null
            pillView = null
            inputOverlay = null
            hideAppReel()
            hideScratchpad()
            edgeNubOverlay?.setNubVisibility(true)
        }
    }

    fun appendToInput(text: String) {
        overlayView?.appendText(text)
    }

    fun toggle(onQuery: (String) -> Unit) {
        if (isShowing) {
            hideOverlay()
        } else {
            showOverlay(onQuery)
        }
    }

    fun handleConfigurationChanged() {
        if (!isShowing) return
        val callback = lastTextQueryCallback ?: return
        val currentText = inputOverlay?.getInputText() ?: ""
        val wasReelVisible = isAppReelVisible()

        hideOverlay()
        showInputOverlay(callback)

        if (currentText.isNotBlank()) {
            inputOverlay?.setInputText(currentText)
        }
        if (wasReelVisible) {
            showAppReel()
        }
        Timber.i("Overlay dynamically re-oriented to current configuration")
    }


    private fun getInteractiveLayoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        // FLAG_NOT_FOCUSABLE by default prevents the overlay from hijacking input from underlying apps.
        // Focus is granted on-demand via requestOverlayFocus() when the user taps the input field.
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dpToPx(520),
            type,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
    }

    fun requestOverlayFocus() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val view = inputOverlay ?: overlayView ?: return@post
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
            
            // Remove NOT_FOCUSABLE to allow keyboard interaction
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager?.updateViewLayout(view, params)
            Timber.d("Overlay focus requested")
        }
    }

    fun releaseOverlayFocus() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val view = inputOverlay ?: overlayView ?: return@post
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
            
            // Re-add NOT_FOCUSABLE to return focus to underlying apps
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager?.updateViewLayout(view, params)
            Timber.d("Overlay focus released")
        }
    }

    fun isVisible(): Boolean = isShowing

    private var scratchpadOverlay: ScratchpadOverlay? = null

    fun showScratchpad() {
        if (!canDrawOverlay()) return
        if (scratchpadOverlay != null) {
            hideScratchpad()
            return
        }
        try {
            val wm = windowManager ?: return
            scratchpadOverlay = ScratchpadOverlay(
                context = context,
                windowManager = wm,
                onDismiss = { hideScratchpad() }
            )
            wm.addView(scratchpadOverlay, scratchpadOverlay?.windowParams)
            Timber.i("Scratchpad overlay opened")
        } catch (e: Exception) {
            Timber.e(e, "Failed to show Scratchpad overlay")
        }
    }

    fun hideScratchpad() {
        scratchpadOverlay?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Timber.w(e, "Failed to remove Scratchpad overlay")
            }
            scratchpadOverlay = null
        }
    }

    private var appReelOverlay: AppReelOverlay? = null

    fun showAppReel() {
        if (!canDrawOverlay()) return
        val reel = appReelOverlay
        if (reel != null) {
            if (reel.visibility == View.VISIBLE) {
                hideAppReel()
            } else {
                reel.alpha = 0f
                reel.visibility = View.VISIBLE
                reel.animate().alpha(1f).setDuration(160).start()
                Timber.i("App Reel overlay opened (revealed underneath star)")
            }
            return
        }
        try {
            val wm = windowManager ?: return
            val newReel = AppReelOverlay(
                context = context,
                windowManager = wm,
                onDismiss = { hideAppReel() }
            )
            appReelOverlay = newReel
            wm.addView(newReel, newReel.windowParams)
            // Re-order inputOverlay so Orange Star is on top
            inputOverlay?.let { input ->
                val currentParams = input.layoutParams as? WindowManager.LayoutParams
                if (currentParams != null) {
                    try {
                        wm.removeView(input)
                        wm.addView(input, currentParams)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to re-order inputOverlay")
                    }
                }
            }
            Timber.i("App Reel overlay opened")
        } catch (e: Exception) {
            Timber.e(e, "Failed to show App Reel overlay")
        }
    }

    fun hideAppReel() {
        appReelOverlay?.let { reel ->
            if (isShowing) {
                // Keep preloaded under layer, smoothly fade out
                reel.animate().alpha(0f).setDuration(140).withEndAction {
                    reel.visibility = View.GONE
                }.start()
            } else {
                try {
                    windowManager?.removeView(reel)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to remove App Reel overlay")
                }
                appReelOverlay = null
            }
        }
    }

    fun isAppReelVisible(): Boolean = appReelOverlay?.visibility == View.VISIBLE

    fun scrubAppReel(deltaX: Float) {
        appReelOverlay?.scrubRelative(deltaX)
    }

    fun stepAppReel(step: Int) {
        appReelOverlay?.stepDirection(step)
    }

    fun setAppReelJoystickVelocity(vx: Float) {
        appReelOverlay?.setJoystickVelocity(vx)
    }

    fun launchAppReelSelected(): Boolean {
        return appReelOverlay?.launchCurrentlySelected() ?: false
    }
}
