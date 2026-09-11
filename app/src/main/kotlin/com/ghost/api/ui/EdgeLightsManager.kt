package com.ghost.api.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import com.ghost.api.audio.SystemVisualizer
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

@Suppress("DEPRECATION")
object EdgeLightsManager : SystemVisualizer.AudioListener {

    private var windowManager: WindowManager? = null
    private var displayManager: DisplayManager? = null
    private var appContext: Context? = null
    private var topView: TopEdgeView? = null
    private var bottomView: BottomEdgeView? = null
    var isShowing = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            handleDisplayChanged()
        }
    }

    /**
     * Rock-solid landscape detection across all Android API levels, orientations, and form factors.
     * Service contexts do not reliably update resources.configuration on rotation, so we directly
     * query display rotation, window metrics bounds, and real display metrics.
     */
    fun isLandscape(context: Context? = appContext): Boolean {
        val ctx = context ?: topView?.context ?: bottomView?.context ?: return false
        val wm = windowManager ?: ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        // 1. Check Display rotation (90° or 270° is landscape on any handheld device)
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                ctx.display?.rotation ?: wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            } catch (e: Exception) {
                wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } else {
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            return true
        }

        // 2. Physical display dimensions / window bounds check (width > height)
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val bounds = wm.currentWindowMetrics.bounds
                    if (bounds.width() > bounds.height()) return true
                } catch (e: Exception) {}
            }
            try {
                val metrics = android.util.DisplayMetrics()
                wm.defaultDisplay.getRealMetrics(metrics)
                if (metrics.widthPixels > metrics.heightPixels) return true
            } catch (e: Exception) {}
        }

        // 3. View resources configuration check
        val viewConfig = topView?.resources?.configuration ?: bottomView?.resources?.configuration
        if (viewConfig?.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            return true
        }

        // 4. Context configuration check
        if (ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            return true
        }

        return false
    }

    private fun handleDisplayChanged() {
        if (!isShowing) return
        val landscape = isLandscape()
        val targetVis = if (landscape) View.GONE else View.VISIBLE

        mainHandler.post {
            topView?.let {
                if (it.visibility != targetVis) {
                    it.visibility = targetVis
                }
                if (!landscape) it.invalidate()
            }
            bottomView?.let {
                if (it.visibility != targetVis) {
                    it.visibility = targetVis
                }
                if (!landscape) it.invalidate()
            }
        }
    }

    fun restoreState(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { restoreState(context) }
            return
        }
        val prefs = context.getSharedPreferences(com.ghost.api.Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(com.ghost.api.Constants.PREF_EDGE_LIGHTS_ENABLED, false)) {
            show(context)
        }
    }

    fun toggle(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { toggle(context) }
            return
        }
        if (isShowing) hide(context) else show(context)
    }

    fun show(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(context) }
            return
        }
        if (isShowing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(context)) {
            return
        }

        appContext = context.applicationContext
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density
        // 52dp strip height covers subtle equalizer rim lighting along top/bottom bezels
        // while leaving the screen completely free of overlay window conflicts.
        val stripHeight = (52 * density).toInt()

        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR

        val topParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            stripHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            baseFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val bottomParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            stripHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            baseFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        topView = TopEdgeView(context)
        bottomView = BottomEdgeView(context)

        // Set initial visibility based on current orientation
        val initialLandscape = isLandscape(context)
        val initialVis = if (initialLandscape) View.GONE else View.VISIBLE
        topView?.visibility = initialVis
        bottomView?.visibility = initialVis

        try {
            windowManager?.addView(topView, topParams)
            windowManager?.addView(bottomView, bottomParams)

            // Register DisplayListener for instant rotation detection
            if (displayManager == null) {
                displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                displayManager?.registerDisplayListener(displayListener, mainHandler)
            }

            SystemVisualizer.init(context)
            SystemVisualizer.addListener(this)
            isShowing = true
            context.getSharedPreferences(com.ghost.api.Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(com.ghost.api.Constants.PREF_EDGE_LIGHTS_ENABLED, true).apply()
        } catch (e: Exception) {
            Timber.e(e, "Failed to show edge lights overlay strips")
            hide(context)
        }
    }

    fun hide(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide(context) }
            return
        }
        context.getSharedPreferences(com.ghost.api.Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(com.ghost.api.Constants.PREF_EDGE_LIGHTS_ENABLED, false).apply()
        if (!isShowing && topView == null && bottomView == null) return
        SystemVisualizer.removeListener(this)

        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering DisplayListener")
        }
        displayManager = null

        try {
            topView?.let {
                if (it.isAttachedToWindow) windowManager?.removeView(it)
            }
            bottomView?.let {
                if (it.isAttachedToWindow) windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error removing edge light views")
        }
        topView = null
        bottomView = null
        isShowing = false
    }

    override fun onAudioData(waveform: ByteArray, fft: ByteArray, intensity: Float, bass: Float) {
        if (!isShowing || isLandscape()) return
        topView?.updateAudioData(fft, intensity, bass)
        bottomView?.updateAudioData(fft, intensity, bass)
    }

    override fun onColorsChanged(colors: IntArray?) {
        if (!isShowing || isLandscape()) return
        topView?.updateColors(colors)
        bottomView?.updateColors(colors)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Base Edge Strip View
    // ─────────────────────────────────────────────────────────────────────────

    private abstract class BaseEdgeView(context: Context) : View(context) {
        protected val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }

        protected val mainHandler = Handler(Looper.getMainLooper())
        protected var smoothedBass = 0f

        protected val LAYERS = 3
        protected val fftHistory = ArrayDeque<ByteArray>(LAYERS)
        protected val bassHistory = ArrayDeque<Float>(LAYERS)

        protected val layerAlphas = intArrayOf(160, 90, 45)
        protected val heightScales = floatArrayOf(1.00f, 0.72f, 0.46f)
        protected val layerWidthScales = floatArrayOf(0.65f, 0.85f, 1.05f)
        protected val layerPaletteIdx = intArrayOf(0, 1, 2)

        protected val defaultColors = intArrayOf(
            Color.parseColor("#A78BFA"),
            Color.parseColor("#4285F4"),
            Color.parseColor("#34A853"),
            Color.parseColor("#FBBC05"),
            Color.parseColor("#EA4335")
        )
        protected var targetColors: IntArray = defaultColors
        protected var currentColors: IntArray = defaultColors.clone()

        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            super.onConfigurationChanged(newConfig)
            post {
                val landscape = isLandscape(context)
                val targetVis = if (landscape) GONE else VISIBLE
                if (visibility != targetVis) {
                    visibility = targetVis
                }
                if (!landscape) invalidate()
            }
        }

        fun updateAudioData(fft: ByteArray, intensity: Float, bass: Float) {
            if (isLandscape(context)) {
                if (visibility != GONE) {
                    mainHandler.post { visibility = GONE }
                }
                return
            }

            if (visibility != VISIBLE) {
                mainHandler.post { visibility = VISIBLE }
            }

            smoothedBass = smoothedBass * 0.72f + bass * 0.28f

            fftHistory.addFirst(fft.copyOf())
            bassHistory.addFirst(bass)
            while (fftHistory.size > LAYERS) fftHistory.removeLast()
            while (bassHistory.size > LAYERS) bassHistory.removeLast()

            mainHandler.post { invalidate() }
        }

        fun updateColors(colors: IntArray?) {
            targetColors = colors ?: defaultColors
            if (!isLandscape(context)) {
                mainHandler.post { invalidate() }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Top Edge View (Bars grow downward from top)
    // ─────────────────────────────────────────────────────────────────────────

    private class TopEdgeView(context: Context) : BaseEdgeView(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isLandscape(context)) {
                if (visibility != GONE) {
                    visibility = GONE
                }
                return
            }

            // Smoothly blend edge light colors towards target
            val limit = minOf(currentColors.size, targetColors.size)
            for (c in 0 until limit) {
                currentColors[c] = ColorUtils.blendARGB(currentColors[c], targetColors[c], 0.08f)
            }

            val numBars = 32
            val spacing = width.toFloat() / numBars
            val histFft = fftHistory.toList()
            val histBass = bassHistory.toList()

            for (layerIdx in (histFft.indices).reversed()) {
                val fft = histFft[layerIdx]
                val layBass = histBass.getOrElse(layerIdx) { smoothedBass }
                val baseAlpha = layerAlphas.getOrElse(layerIdx) { 30 }
                val hScale = heightScales.getOrElse(layerIdx) { 0.3f }
                val wScale = layerWidthScales.getOrElse(layerIdx) { 0.65f }
                val palIdx = layerPaletteIdx.getOrElse(layerIdx) { 0 }
                val color = currentColors[palIdx % currentColors.size]

                paint.color = color
                paint.strokeWidth = spacing * wScale
                paint.clearShadowLayer()
                val maxBarHeight = (height.toFloat() - (paint.strokeWidth / 2f) - 2f).coerceAtLeast(4f)

                for (i in 0 until numBars) {
                    val binBase = ((i * 2) % max(1, fft.size / 2)) * 2
                    val mag = if (fft.size > binBase + 1) {
                        val re = fft[binBase].toInt()
                        val im = fft[binBase + 1].toInt()
                        Math.hypot(re.toDouble(), im.toDouble()).toFloat()
                    } else 0f

                    val glow = (mag * 1.2f) + (layBass / 5f)
                    val rawBarHeight = (6f + glow * 0.8f) * hScale
                    val barHeight = rawBarHeight.coerceIn(4f, maxBarHeight)
                    val heightBoost = min(25, (glow * 0.8f).toInt())
                    paint.alpha = (baseAlpha + heightBoost).coerceIn(0, 255)

                    val x = (i * spacing) + (spacing / 2f)
                    canvas.drawLine(x, 0f, x, barHeight, paint)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bottom Edge View (Bars grow upward from bottom)
    // ─────────────────────────────────────────────────────────────────────────

    private class BottomEdgeView(context: Context) : BaseEdgeView(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isLandscape(context)) {
                if (visibility != GONE) {
                    visibility = GONE
                }
                return
            }

            // Smoothly blend edge light colors towards target
            val limit = minOf(currentColors.size, targetColors.size)
            for (c in 0 until limit) {
                currentColors[c] = ColorUtils.blendARGB(currentColors[c], targetColors[c], 0.08f)
            }

            val numBars = 32
            val spacing = width.toFloat() / numBars
            val histFft = fftHistory.toList()
            val histBass = bassHistory.toList()
            val bottomY = height.toFloat()

            for (layerIdx in (histFft.indices).reversed()) {
                val fft = histFft[layerIdx]
                val layBass = histBass.getOrElse(layerIdx) { smoothedBass }
                val baseAlpha = layerAlphas.getOrElse(layerIdx) { 30 }
                val hScale = heightScales.getOrElse(layerIdx) { 0.3f }
                val wScale = layerWidthScales.getOrElse(layerIdx) { 0.65f }
                val palIdx = layerPaletteIdx.getOrElse(layerIdx) { 0 }
                val color = currentColors[palIdx % currentColors.size]

                paint.color = color
                paint.strokeWidth = spacing * wScale
                paint.clearShadowLayer()
                val maxBarHeight = (height.toFloat() - (paint.strokeWidth / 2f) - 2f).coerceAtLeast(4f)

                for (i in 0 until numBars) {
                    val binBase = ((i * 2) % max(1, fft.size / 2)) * 2
                    val mag = if (fft.size > binBase + 1) {
                        val re = fft[binBase].toInt()
                        val im = fft[binBase + 1].toInt()
                        Math.hypot(re.toDouble(), im.toDouble()).toFloat()
                    } else 0f

                    val glow = (mag * 1.2f) + (layBass / 5f)
                    val rawBarHeight = (6f + glow * 0.8f) * hScale
                    val barHeight = rawBarHeight.coerceIn(4f, maxBarHeight)
                    val heightBoost = min(25, (glow * 0.8f).toInt())
                    paint.alpha = (baseAlpha + heightBoost).coerceIn(0, 255)

                    val x = (i * spacing) + (spacing / 2f)
                    val bx = width.toFloat() - x
                    canvas.drawLine(bx, bottomY, bx, bottomY - barHeight, paint)
                }
            }
        }
    }
}

