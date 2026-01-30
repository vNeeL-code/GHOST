package com.gemma.api.ui

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
import com.gemma.api.Constants
import com.gemma.api.GemmaService
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
    private var sparkleBar: SparkleBar? = null
    private var currentStyle: OverlayStyle = OverlayStyle.SPARKLE  // Default to sparkle (audio-first)
    private var isShowing = false

    // Callbacks
    private var audioQueryCallback: ((ShortArray) -> Unit)? = null

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
    fun setAudioQueryCallback(callback: (ShortArray) -> Unit) {
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
                OverlayStyle.SPARKLE -> showSparkleBar(onQuery)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to show overlay")
        }
    }

    private fun showSparkleBar(onTextQuery: (String) -> Unit) {
        sparkleBar = SparkleBar(
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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER  // Center of screen, above keyboard
            y = dpToPx(-100)  // Offset upward from center
        }

        windowManager?.addView(sparkleBar, params)
        isShowing = true

        sparkleBar?.post {
            sparkleBar?.focusInput()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(sparkleBar, InputMethodManager.SHOW_IMPLICIT)
        }

        Timber.i("SparkleBar overlay shown")
    }

    private fun showClassicOverlay(onQuery: (String) -> Unit) {
        overlayView = OverlayInputView(context) { query ->
            onQuery(query)
            hideOverlay()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            y = -200
        }

        windowManager?.addView(overlayView, params)
        isShowing = true

        overlayView?.post {
            overlayView?.focusInput()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(overlayView, InputMethodManager.SHOW_IMPLICIT)
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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(100)  // Offset from bottom
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

    /**
     * Show error in pill overlay
     */
    fun showError(error: String) {
        pillView?.showError(error)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    fun hideOverlay() {
        if (!isShowing) return

        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            // Hide classic overlay
            overlayView?.let { view ->
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                view.cleanup()
                windowManager?.removeView(view)
            }
            overlayView = null

            // Hide pill overlay
            pillView?.let { view ->
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                view.cleanup()
                windowManager?.removeView(view)
            }
            pillView = null

            // Hide sparkle bar
            sparkleBar?.let { view ->
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                view.cleanup()
                windowManager?.removeView(view)
            }
            sparkleBar = null

            isShowing = false
            Timber.i("Overlay hidden")
        } catch (e: Exception) {
            Timber.e(e, "Failed to hide overlay")
        }
    }

    /**
     * Append text to the current input field (e.g., from external STT)
     */
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

    fun isVisible(): Boolean = isShowing
}
