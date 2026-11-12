package com.ghost.api.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.ghost.api.Constants
import timber.log.Timber

/**
 * Manages the floating input overlay (`InputOverlay`), satellite `ScratchpadOverlay`,
 * edge summon handle (`edgeNubOverlay`), and background HUD work indicator (`GhostWorkIndicatorOverlay`).
 */
class OverlayManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var inputOverlay: InputOverlay? = null
    private var ghostWorkIndicator: GhostWorkIndicatorOverlay? = null
    private var edgeNubOverlay: EdgeNubOverlay? = null
    private val activeWorkCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private var isShowing = false
    private var lastSummonCallback: (() -> Unit)? = null

    // Callbacks
    private var audioQueryCallback: ((ByteArray) -> Unit)? = null

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    /**
     * Set callback for audio queries
     */
    fun setAudioQueryCallback(callback: (ByteArray) -> Unit) {
        audioQueryCallback = callback
    }

    fun canDrawOverlay(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Attaches or refreshes the Edge Nub summon handle based on summon_method setting.
     */
    fun attachEdgeNub(onSummon: () -> Unit) {
        lastSummonCallback = onSummon
        if (!canDrawOverlay()) return
        val wm = windowManager ?: return

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (edgeNubOverlay == null) {
                edgeNubOverlay = EdgeNubOverlay(context, wm, onSummon)
            }
            edgeNubOverlay?.attach()
            if (isShowing) edgeNubOverlay?.setNubVisibility(false)
        }
    }

    fun refreshEdgeHandles() {
        val cb = lastSummonCallback ?: return
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val method = prefs.getString(Constants.PREF_SUMMON_METHOD, Constants.SUMMON_METHOD_SHAKE)
        if (method == Constants.SUMMON_METHOD_EDGE_NUB || method == Constants.SUMMON_METHOD_BOTH) {
            attachEdgeNub(cb)
        } else {
            detachEdgeNub()
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
            showInputOverlay(onQuery)
        } catch (e: Exception) {
            Timber.e(e, "Failed to show overlay")
            isShowing = false
            inputOverlay = null
            setEdgeNubVisible(true)
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

        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val userVerticalOffsetDp = prefs.getInt(Constants.PREF_PUCK_VERTICAL_OFFSET_DP, 0)

        val params = getInteractiveLayoutParams().apply {
            val (screenHeight, screenWidth) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
                height = screenHeight
                gravity = Gravity.CENTER
                y = -dpToPx(38) + dpToPx(userVerticalOffsetDp)
            } else {
                height = dpToPx(520)
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                val windowHeight = dpToPx(520)
                y = (screenHeight - windowHeight) / 2 - dpToPx(15) + dpToPx(userVerticalOffsetDp)
            }
        }

        windowManager?.addView(inputOverlay, params)
        isShowing = true

        Timber.i("Input overlay shown")
    }

    /**
     * Opens the lightweight Quick App Folder inside the active InputOverlay (or summons it first).
     */
    fun showQuickAppFolder() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val active = inputOverlay
            if (active != null && isShowing) {
                active.toggleAppFolderPopup()
            } else {
                com.ghost.api.GemmaService.instance?.summonOverlay()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    inputOverlay?.toggleAppFolderPopup()
                }, 120L)
            }
        }
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

    fun showError(error: String) {
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
        if (isAppInForeground) return

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

            inputOverlay?.let { view ->
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                view.cleanup()
                windowManager?.removeView(view)
            }
            inputOverlay = null

            hideScratchpad()

            isShowing = false
            setEdgeNubVisible(true)
            Timber.i("Overlay hidden")
        } catch (e: Exception) {
            Timber.e(e, "Failed to hide overlay")
            isShowing = false
            inputOverlay = null
            hideScratchpad()
            setEdgeNubVisible(true)
        }
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

        hideOverlay()
        showInputOverlay(callback)

        if (currentText.isNotBlank()) {
            inputOverlay?.setInputText(currentText)
        }
        Timber.i("Overlay dynamically re-oriented to current configuration")
    }

    private fun getInteractiveLayoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dpToPx(520),
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
    }

    fun requestOverlayFocus() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val view = inputOverlay ?: return@post
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager?.updateViewLayout(view, params)
            Timber.d("Overlay focus requested")
        }
    }

    fun releaseOverlayFocus() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val view = inputOverlay ?: return@post
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
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
}
