package com.gemma.api.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.gemma.api.Constants
import com.gemma.api.GemmaService
import timber.log.Timber

/**
 * Manages the floating input overlay for "no UI" mode.
 * Shake to summon → type → dismiss on send
 */
class OverlayManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayInputView? = null
    private var isShowing = false
    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
                gravity = Gravity.CENTER // Center of screen, above keyboard
                y = -200 // Offset upward from center (negative = up)
            }

            windowManager?.addView(overlayView, params)
            isShowing = true

            // Focus and show keyboard
            overlayView?.post {
                overlayView?.focusInput()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(overlayView, InputMethodManager.SHOW_IMPLICIT)
            }

            Timber.i("Overlay shown")
        } catch (e: Exception) {
            Timber.e(e, "Failed to show overlay")
        }
    }

    fun hideOverlay() {
        if (!isShowing) return

        try {
            // Hide keyboard first
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            overlayView?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }

            // Cleanup speech recognizer
            overlayView?.cleanup()

            windowManager?.removeView(overlayView)
            overlayView = null
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
