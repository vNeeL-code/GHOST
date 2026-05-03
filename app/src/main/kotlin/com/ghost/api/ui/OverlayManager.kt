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
 * - SPARKLE: Minimal bar with ✦ for direct audio recording (audio-first)
 */
class OverlayManager(private val context: Context) {

    enum class OverlayStyle {
        CLASSIC,  // Original horizontal bar
        PILL,     // Gemini-style expandable pill
        SPARKLE   // Minimal ✦ bar with direct audio recording
    }

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayInputView? = null
    private var pillView: PillOverlayView? = null
    private var inputOverlay: InputOverlay? = null
    private var browserBubble: BrowserBubbleOverlay? = null
    private var currentStyle: OverlayStyle = OverlayStyle.SPARKLE
    private var isShowing = false

    // Callbacks
    private var audioQueryCallback: ((ByteArray) -> Unit)? = null

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

    fun showOverlay(onQuery: (String) -> Unit) {
        if (isShowing) {
            Timber.d("Overlay already showing")
            return
        }

        if (!canDrawOverlay()) {
            Timber.w("No overlay permission")
            return
        }

        try {
            when (currentStyle) {
                OverlayStyle.CLASSIC -> showClassicOverlay(onQuery)
                OverlayStyle.PILL -> showPillOverlay(onQuery)
                OverlayStyle.SPARKLE -> showInputOverlay(onQuery)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to show overlay")
            // CRITICAL: Reset state if show*() failed, preventing stuck isShowing flag
            isShowing = false
            overlayView = null
            pillView = null
            inputOverlay = null
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
            }
        )

        val params = getInteractiveLayoutParams().apply {
            gravity = Gravity.CENTER
            y = dpToPx(-100)  // Offset upward from center
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

        val params = getPassiveLayoutParams().apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            this.y = 100 // Top margin
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

    /**
     * Spawns or updates a floating Browser Bubble for supervised AI research
     */
    fun showBrowserBubble(url: String, onContentScraped: (String) -> Unit) {
        if (!canDrawOverlay()) {
            Timber.w("No overlay permission for Browser Bubble")
            return
        }
        
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (browserBubble == null) {
                browserBubble = BrowserBubbleOverlay(context, windowManager!!) {
                    hideBrowserBubble()
                }
                windowManager?.addView(browserBubble, browserBubble!!.windowParams)
            }
            
            browserBubble?.loadUrl(url)
            
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                browserBubble?.scrapeContent { scrapedText ->
                    onContentScraped(scrapedText)
                    hideBrowserBubble()
                }
            }, 5000)
        }
    }
    
    fun hideBrowserBubble() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            browserBubble?.let {
                if (it.windowToken != null) {
                    windowManager?.removeView(it)
                }
            }
            browserBubble = null
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

            isShowing = false
            Timber.i("Overlay hidden")
        } catch (e: Exception) {
            Timber.e(e, "Failed to hide overlay")
            isShowing = false
            overlayView = null
            pillView = null
            inputOverlay = null
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

    private fun getPassiveLayoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // FIX: Prevent overlay from moving when keyboard spawns
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
    }

    private fun getInteractiveLayoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dpToPx(400),
            type,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            // FIX: Use ADJUST_NOTHING to prevent keyboard from pushing the bar overlay
            // Removed ALWAYS_VISIBLE to respect user's manual tap intent
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
    }

    fun isVisible(): Boolean = isShowing
}
