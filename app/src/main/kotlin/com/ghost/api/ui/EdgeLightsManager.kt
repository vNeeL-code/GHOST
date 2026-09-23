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

    fun invalidate() {
        mainHandler.post {
            topView?.invalidate()
            bottomView?.invalidate()
        }
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

        protected val cachedPath = android.graphics.Path()
        protected val mainHandler = Handler(Looper.getMainLooper())
        protected var smoothedBass = 0f
        protected var strobeFlash = 0f

        protected val LAYERS = 3
        protected val fftHistory = ArrayDeque<ByteArray>(LAYERS)
        protected val bassHistory = ArrayDeque<Float>(LAYERS)

        protected val layerAlphas = intArrayOf(160, 90, 45)
        protected val heightScales = floatArrayOf(1.00f, 0.72f, 0.46f)
        protected val layerWidthScales = floatArrayOf(0.65f, 0.85f, 1.05f)
        protected val layerPaletteIdx = intArrayOf(0, 1, 2)

        // Palette order: Layer 0 (top/front) is Pale Teal/Cyan matching the avatar's outer bloom.
        // Iris spectrum follows in subsequent layers (purple, blue, green, gold, red).
        protected val defaultColors = intArrayOf(
            Color.parseColor("#38BDF8"), // Pale Teal / Cyan (matches avatar outer bloom)
            Color.parseColor("#A78BFA"), // Purple
            Color.parseColor("#4285F4"), // Blue
            Color.parseColor("#34A853"), // Green
            Color.parseColor("#FBBC05"), // Gold
            Color.parseColor("#EA4335")  // Red
        )
        protected var targetColors: IntArray = defaultColors
        protected var currentColors: IntArray = defaultColors.clone()

        protected fun getActiveStyle(): String {
            return context.getSharedPreferences(com.ghost.api.Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(com.ghost.api.Constants.PREF_EDGE_LIGHT_STYLE, com.ghost.api.Constants.EDGE_STYLE_BARS)
                ?: com.ghost.api.Constants.EDGE_STYLE_BARS
        }

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

            // Snare / transient attack detection in mid-high spectrum
            var snareEnergy = 0f
            if (fft.size >= 32) {
                for (b in 12 until 28 step 2) {
                    val re = fft[b].toInt()
                    val im = fft[b + 1].toInt()
                    snareEnergy += Math.hypot(re.toDouble(), im.toDouble()).toFloat()
                }
            }
            val avgSnare = snareEnergy / 8f
            if (avgSnare > 18f) {
                strobeFlash = maxOf(strobeFlash, (avgSnare / 45f).coerceIn(0f, 1f))
            } else {
                strobeFlash = (strobeFlash * 0.85f).coerceAtLeast(0f)
            }

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

        protected fun renderVisualizer(canvas: Canvas, isTop: Boolean) {
            val limit = minOf(currentColors.size, targetColors.size)
            for (c in 0 until limit) {
                currentColors[c] = ColorUtils.blendARGB(currentColors[c], targetColors[c], 0.035f)
            }

            val histFft = fftHistory.toList()
            val histBass = bassHistory.toList()
            if (histFft.isEmpty()) return

            when (getActiveStyle()) {
                com.ghost.api.Constants.EDGE_STYLE_BOOM -> drawBoom(canvas, isTop, histFft, histBass)
                com.ghost.api.Constants.EDGE_STYLE_WIREFRAME -> drawWireframe(canvas, isTop, histFft, histBass)
                com.ghost.api.Constants.EDGE_STYLE_HEX -> drawHex(canvas, isTop, histFft, histBass)
                else -> drawBars(canvas, isTop, histFft, histBass)
            }
        }

        // 1. STYLE_BARS: Multi-layer rounded equalizer bars
        private fun drawBars(canvas: Canvas, isTop: Boolean, histFft: List<ByteArray>, histBass: List<Float>) {
            val numBars = 32
            val spacing = width.toFloat() / numBars
            val bottomY = height.toFloat()

            for (layerIdx in (histFft.indices).reversed()) {
                val fft = histFft[layerIdx]
                val layBass = histBass.getOrElse(layerIdx) { smoothedBass }
                val baseAlpha = layerAlphas.getOrElse(layerIdx) { 30 }
                val hScale = heightScales.getOrElse(layerIdx) { 0.3f }
                val wScale = layerWidthScales.getOrElse(layerIdx) { 0.65f }
                val palIdx = layerPaletteIdx.getOrElse(layerIdx) { 0 }
                val rawColor = currentColors[palIdx % currentColors.size]
                val color = if (layerIdx == 0 && strobeFlash > 0.05f) {
                    ColorUtils.blendARGB(rawColor, Color.WHITE, (strobeFlash * 0.85f).coerceIn(0f, 1f))
                } else {
                    rawColor
                }

                paint.style = Paint.Style.STROKE
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
                    val flashBoost = if (layerIdx == 0 && strobeFlash > 0.05f) (strobeFlash * 60f).toInt() else 0
                    paint.alpha = (baseAlpha + heightBoost + flashBoost).coerceIn(0, 255)

                    val x = (i * spacing) + (spacing / 2f)
                    if (isTop) {
                        canvas.drawLine(x, 0f, x, barHeight, paint)
                    } else {
                        val bx = width.toFloat() - x
                        canvas.drawLine(bx, bottomY, bx, bottomY - barHeight, paint)
                    }
                }
            }
        }

        // 2. STYLE_BOOM (Waveworms): Symmetrically mirrored serpentine ribbons hugging side walls
        // Surges high up the corner flanks per user sketch while staying calm in the center
        private fun drawBoom(canvas: Canvas, isTop: Boolean, histFft: List<ByteArray>, histBass: List<Float>) {
            val fft = histFft[0]
            val layBass = histBass.getOrElse(0) { smoothedBass }
            val bassPower = (layBass / 5f).coerceIn(0f, 25f)
            val maxH = (height.toFloat() - 3f).coerceAtLeast(10f)
            val edgeY = if (isTop) 0f else height.toFloat()
            val segments = 40
            val segW = width.toFloat() / segments

            // Layer 1: Ambient wash / deep background harmonic swell
            cachedPath.reset()
            cachedPath.moveTo(0f, edgeY)
            for (col in 0..segments) {
                val screenX = col * segW
                val distFromCenter = kotlin.math.abs((col.toFloat() / segments) - 0.5f) * 2f
                val edgeCurve = distFromCenter * distFromCenter
                val bin = (((1.0f - distFromCenter) * (fft.size / 4f)).toInt() * 2).coerceIn(0, max(1, fft.size / 2 - 2))
                val mag = if (fft.size > bin + 1) Math.hypot(fft[bin].toDouble(), fft[bin + 1].toDouble()).toFloat() else 0f
                val peakSurge = 0.06f + (edgeCurve * 0.94f)
                val waveH1 = (4f + (mag * 0.75f + bassPower * 3.8f) * peakSurge).coerceIn(3f, maxH * 0.85f)
                val y = if (isTop) waveH1 else height.toFloat() - waveH1
                cachedPath.lineTo(screenX, y)
            }
            cachedPath.lineTo(width.toFloat(), edgeY)
            cachedPath.close()

            paint.style = Paint.Style.FILL
            paint.color = currentColors[1 % currentColors.size]
            paint.alpha = (40 + (bassPower * 5f).toInt()).coerceIn(25, 140)
            canvas.drawPath(cachedPath, paint)

            // Layer 2: Main dynamic serpentine wave ribbon (Waveworm)
            cachedPath.reset()
            cachedPath.moveTo(0f, edgeY)
            for (col in 0..segments) {
                val screenX = col * segW
                val distFromCenter = kotlin.math.abs((col.toFloat() / segments) - 0.5f) * 2f
                val edgeCurve = distFromCenter * distFromCenter
                val bin = (((1.0f - distFromCenter) * (fft.size / 3.5f)).toInt() * 2).coerceIn(0, max(1, fft.size / 2 - 2))
                val mag = if (fft.size > bin + 1) Math.hypot(fft[bin].toDouble(), fft[bin + 1].toDouble()).toFloat() else 0f
                val peakSurge = 0.08f + (edgeCurve * 0.92f)
                val waveH2 = (6f + (mag * 1.35f + bassPower * 5.2f) * peakSurge).coerceIn(5f, maxH * 0.98f)
                val y = if (isTop) waveH2 else height.toFloat() - waveH2
                cachedPath.lineTo(screenX, y)
            }
            cachedPath.lineTo(width.toFloat(), edgeY)
            cachedPath.close()

            paint.style = Paint.Style.FILL
            paint.color = currentColors[0]
            paint.alpha = (75 + (bassPower * 6f).toInt()).coerceIn(50, 185)
            canvas.drawPath(cachedPath, paint)

            // Layer 3: Neon razor crest highlight stroke
            cachedPath.reset()
            for (col in 0..segments) {
                val screenX = col * segW
                val distFromCenter = kotlin.math.abs((col.toFloat() / segments) - 0.5f) * 2f
                val edgeCurve = distFromCenter * distFromCenter
                val bin = (((1.0f - distFromCenter) * (fft.size / 3.5f)).toInt() * 2).coerceIn(0, max(1, fft.size / 2 - 2))
                val mag = if (fft.size > bin + 1) Math.hypot(fft[bin].toDouble(), fft[bin + 1].toDouble()).toFloat() else 0f
                val peakSurge = 0.08f + (edgeCurve * 0.92f)
                val waveH2 = (6f + (mag * 1.35f + bassPower * 5.2f) * peakSurge).coerceIn(5f, maxH * 0.98f)
                val y = if (isTop) waveH2 else height.toFloat() - waveH2
                if (col == 0) cachedPath.moveTo(screenX, y) else cachedPath.lineTo(screenX, y)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f
            val baseCrestColor = currentColors[0]
            val crestColor = if (strobeFlash > 0.05f) {
                ColorUtils.blendARGB(baseCrestColor, Color.WHITE, (strobeFlash * 0.90f).coerceIn(0f, 1f))
            } else if (bassPower > 8f) Color.WHITE else baseCrestColor
            val crestFlashBoost = if (strobeFlash > 0.05f) (strobeFlash * 60f).toInt() else 0
            paint.color = crestColor
            paint.alpha = (180 + (bassPower * 4f).toInt() + crestFlashBoost).coerceIn(160, 255)
            canvas.drawPath(cachedPath, paint)

            // Layer 4: Razor bezel rim glow
            paint.strokeWidth = 3.2f
            paint.color = currentColors[0]
            paint.alpha = (170 + (bassPower * 4f).toInt()).coerceIn(150, 255)
            val rimY = if (isTop) 1.6f else height.toFloat() - 1.6f
            canvas.drawLine(0f, rimY, width.toFloat(), rimY, paint)
        }

        // 3. STYLE_WIREFRAME (Bezel Half-Embedded Cubes & Holographic Shooting Chevrons):
        // Cubes are half-submerged in the bezel rim (sawtooth teeth), leaving ample room for bright
        // chromatic chevrons to shoot into the screen. Cubes flash white on beats while chevrons flash vivid color.
        private fun drawWireframe(canvas: Canvas, isTop: Boolean, histFft: List<ByteArray>, histBass: List<Float>) {
            val fft = histFft[0]
            val layBass = histBass.getOrElse(0) { smoothedBass }
            val bassPower = (layBass / 5f).coerceIn(0f, 25f)
            val totalH = height.toFloat()
            val density = resources.displayMetrics.density

            // 1. Spacing and Geometry: 10 clean nodes with breathing room
            val count = 10
            val stepX = width.toFloat() / count
            val diamondRadius = 13.0f * density
            // Bezel half-embedding: center placed right on the display edge so only the inward tooth is visible
            val dy = if (isTop) 0f else totalH

            for (i in 0 until count) {
                val dx = i * stepX + (stepX / 2f)
                val distFromCenter = kotlin.math.abs((dx / width.toFloat()) - 0.5f) * 2f
                val bin = (((1.0f - distFromCenter) * (fft.size / 4f)).toInt() * 2).coerceIn(0, max(1, fft.size / 2 - 2))
                val mag = if (fft.size > bin + 1) Math.hypot(fft[bin].toDouble(), fft[bin + 1].toDouble()).toFloat() else 0f
                val edgeBias = 0.25f + (distFromCenter * 0.75f)
                val nodeColor = currentColors[i % currentColors.size]

                // Audio-reactive flash: Snare transient strobe flashes cubes and lead chevrons crisp white
                val isStrobe = strobeFlash > 0.05f
                val cubeFlash = if (isStrobe) (strobeFlash * 0.90f).coerceIn(0f, 1f) else (mag / 16f + bassPower / 10f).coerceIn(0f, 1f)
                val cubeFillColor = ColorUtils.blendARGB(nodeColor, Color.WHITE, cubeFlash * 0.80f)
                val cubeStrokeColor = ColorUtils.blendARGB(nodeColor, Color.WHITE, cubeFlash)

                // --- SHOOTING FADING CHECKMARKS / CHEVRONS (^) ---
                // Stepped staircase: Outer flanks climb up to 5 tiers high to cap all the way up into the corners
                // (matching Hexagon's corner architecture), while keeping the center open.
                val maxClones = when {
                    distFromCenter > 0.65f -> 5  // Outer corner flank
                    distFromCenter > 0.45f -> 4  // Mid flank
                    distFromCenter > 0.28f -> 2  // Inner flank
                    distFromCenter > 0.15f -> 1  // Near center
                    else -> 0                    // Calm center gap
                }

                val activeClones = if (maxClones > 0) {
                    val energy = (mag / 4.5f + bassPower * 0.42f).coerceIn(0f, 1.8f)
                    val countClones = (energy * maxClones).toInt()
                    val minFlank = if (distFromCenter > 0.65f && (mag > 3.5f || bassPower > 2.5f)) 2
                        else if (distFromCenter > 0.45f && (mag > 4f || bassPower > 3.0f)) 1
                        else 0
                    countClones.coerceIn(minFlank, maxClones)
                } else 0

                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND

                // Mathematical 90° angle: wingSpread MUST equal wingH so chevron slope is exactly 1.0,
                // strictly parallel to the 45° faces of the dragon teeth (satisfying OCD)!
                val wingSpan = diamondRadius * 0.88f

                val tierStep = diamondRadius * 0.52f
                for (k in 1..activeClones) {
                    val offset = diamondRadius * 1.05f + (k - 1) * tierStep
                    val alphaRatio = 1f - ((k - 1f) / 5.2f)

                    cachedPath.reset()
                    if (isTop) {
                        // Top view: chevron points DOWN into screen (v)
                        val apexY = dy + offset
                        val wingY = apexY - wingSpan
                        cachedPath.moveTo(dx - wingSpan, wingY)
                        cachedPath.lineTo(dx, apexY)
                        cachedPath.lineTo(dx + wingSpan, wingY)
                    } else {
                        // Bottom view: chevron points UP into screen (^)
                        val apexY = dy - offset
                        val wingY = apexY + wingSpan
                        cachedPath.moveTo(dx - wingSpan, wingY)
                        cachedPath.lineTo(dx, apexY)
                        cachedPath.lineTo(dx + wingSpan, wingY)
                    }

                    val leadChevronColor = if (k == 1 && isStrobe) {
                        ColorUtils.blendARGB(nodeColor, Color.WHITE, (strobeFlash * 0.85f).coerceIn(0f, 1f))
                    } else nodeColor

                    // Outer neon aura for lead chevron (bright chromatic color)
                    if (k == 1) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 4.8f
                        paint.color = leadChevronColor
                        val strobeAuraBoost = if (isStrobe) (strobeFlash * 60f).toInt() else 0
                        paint.alpha = (75 * alphaRatio + mag * 1.5f + strobeAuraBoost).toInt().coerceIn(25, 230)
                        canvas.drawPath(cachedPath, paint)
                    }

                    // Razor chevron stroke (bright chromatic color)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.4f
                    paint.color = leadChevronColor
                    val strobeLeadBoost = if (k == 1 && isStrobe) (strobeFlash * 50f).toInt() else 0
                    paint.alpha = (220 * alphaRatio + mag * 2.2f + strobeLeadBoost).toInt().coerceIn(60, 255)
                    canvas.drawPath(cachedPath, paint)
                }

                // --- BEZEL HALF-EMBEDDED CUBE DIAMOND ---
                // Clipped naturally by display bounds: only the inward-facing triangular tooth is drawn
                cachedPath.reset()
                cachedPath.moveTo(dx, dy - diamondRadius)
                cachedPath.lineTo(dx + diamondRadius, dy)
                cachedPath.lineTo(dx, dy + diamondRadius)
                cachedPath.lineTo(dx - diamondRadius, dy)
                cachedPath.close()

                // Translucent neon aura fill (flashing white on peaks)
                paint.style = Paint.Style.FILL
                paint.color = cubeFillColor
                val baseCubeAlpha = (50 + (mag * 3.5f * edgeBias) + (cubeFlash * 90f)).toInt().coerceIn(35, 230)
                paint.alpha = baseCubeAlpha
                canvas.drawPath(cachedPath, paint)

                // Razor diamond stroke (flashing crisp white on peaks)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.6f + (cubeFlash * 1.2f)
                paint.color = cubeStrokeColor
                paint.alpha = (180 + (mag * 2.5f) + (cubeFlash * 75f)).toInt().coerceIn(140, 255)
                canvas.drawPath(cachedPath, paint)
            }
        }

        // 4. STYLE_HEX (True Honeycomb Lattice):
        // Mathematically exact interlocking hexagonal honeycomb ("3 rows occupy 2 row space").
        // Half-hexagon teeth along the bezel edge, with a 5-tier stepped staircase climbing up into the corners
        // and an open, uncluttered center so honeycomb architecture hugs both sides of the screen.
        private fun drawHex(canvas: Canvas, isTop: Boolean, histFft: List<ByteArray>, histBass: List<Float>) {
            val fft = histFft[0]
            val layBass = histBass.getOrElse(0) { smoothedBass }
            val bassPower = (layBass / 6f).coerceIn(0f, 18f)
            val density = resources.displayMetrics.density

            // Hexagon geometry: flat-topped hexagons
            // Radius R: distance from center to vertices
            val hexR = 13.5f * density
            val deltaX = 1.5f * hexR
            val deltaY = (kotlin.math.sqrt(3.0) / 2.0 * hexR).toFloat()
            val totalH = height.toFloat()

            val midX = width.toFloat() / 2f
            val maxK = (midX / deltaX).toInt() + 2

            for (k in -maxK..maxK) {
                val cx = midX + (k * deltaX)
                if (cx < -hexR * 1.5f || cx > width.toFloat() + hexR * 1.5f) continue

                val absK = kotlin.math.abs(k)
                val distFromCenter = ((absK * deltaX) / midX).coerceIn(0f, 1f)
                val bin = (((1.0f - distFromCenter) * (fft.size / 4f)).toInt() * 2).coerceIn(0, max(1, fft.size / 2 - 2))
                val mag = if (fft.size > bin + 1) Math.hypot(fft[bin].toDouble(), fft[bin + 1].toDouble()).toFloat() else 0f
                val edgeBias = 0.35f + (distFromCenter * 0.65f)

                val color0 = currentColors[absK % currentColors.size]
                val color1 = currentColors[(absK + 1) % currentColors.size]
                val color2 = currentColors[(absK + 2) % currentColors.size]
                val color3 = currentColors[(absK + 3) % currentColors.size]

                val isEven = (absK % 2 == 0)

                if (isEven) {
                    // --- ROW 0 (Even columns, cy = 0 / totalH): Half-hexagons along bezel ---
                    val cy0 = if (isTop) 0f else totalH
                    drawHexCell(canvas, cx, cy0, hexR, color0, mag, edgeBias, bassPower, popThreshold = 5f)

                    // --- ROW 2 (Even columns, cy = 2 * deltaY): Appears on flanks (dist >= 0.38f) ---
                    if (distFromCenter >= 0.38f) {
                        val cy2 = if (isTop) 2f * deltaY else totalH - (2f * deltaY)
                        drawHexCell(canvas, cx, cy2, hexR, color2, mag, edgeBias, bassPower, popThreshold = 10f)
                    }
                } else {
                    // --- ROW 1 (Odd columns, cy = deltaY): Touches bezel with bottom flat edge ---
                    // Center gap: in the middle 28% of the screen, suppressed unless energy pops
                    if (distFromCenter >= 0.28f || mag > 12f) {
                        val cy1 = if (isTop) deltaY else totalH - deltaY
                        drawHexCell(canvas, cx, cy1, hexR, color1, mag, edgeBias, bassPower, popThreshold = 7f)
                    }

                    // --- ROW 3 (Odd columns, cy = 3 * deltaY): Flank step-up (dist >= 0.55f) ---
                    if (distFromCenter >= 0.55f) {
                        val cy3 = if (isTop) 3f * deltaY else totalH - (3f * deltaY)
                        drawHexCell(canvas, cx, cy3, hexR, color3, mag, edgeBias, bassPower, popThreshold = 11f)
                    }
                }
            }
        }

        private fun drawHexCell(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            radius: Float,
            color: Int,
            mag: Float,
            edgeBias: Float,
            bassPower: Float,
            popThreshold: Float
        ) {
            val isStrobe = strobeFlash > 0.05f
            val effMag = mag * edgeBias
            val pops = effMag > popThreshold || bassPower > (popThreshold / 2.5f) || isStrobe

            if (pops) {
                val fillColor = if (isStrobe) {
                    ColorUtils.blendARGB(color, Color.WHITE, (strobeFlash * 0.60f).coerceIn(0f, 1f))
                } else color
                val strokeColor = if (isStrobe) {
                    ColorUtils.blendARGB(color, Color.WHITE, (strobeFlash * 0.85f).coerceIn(0f, 1f))
                } else if (effMag > 24f || bassPower > 7f) Color.WHITE else color

                paint.style = Paint.Style.FILL
                paint.color = fillColor
                val strobeFillBoost = if (isStrobe) (strobeFlash * 45f).toInt() else 0
                paint.alpha = (25 + (effMag * 2.2f + bassPower * 3f).toInt() + strobeFillBoost).coerceIn(20, 200)
                drawHexagon(canvas, cx, cy, radius * 0.88f, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = if (isStrobe) 2.2f else 1.6f
                paint.color = strokeColor
                val strobeStrokeBoost = if (isStrobe) (strobeFlash * 60f).toInt() else 0
                paint.alpha = (75 + (effMag * 2.0f).toInt() + strobeStrokeBoost).coerceIn(50, 255)
                drawHexagon(canvas, cx, cy, radius, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.0f
                paint.color = color
                paint.alpha = 24
                drawHexagon(canvas, cx, cy, radius, paint)
            }
        }

        private fun drawHexagon(canvas: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint) {
            cachedPath.reset()
            for (i in 0 until 6) {
                val angle = (i * Math.PI / 3.0).toFloat()
                val px = cx + (radius * kotlin.math.cos(angle))
                val py = cy + (radius * kotlin.math.sin(angle))
                if (i == 0) cachedPath.moveTo(px, py) else cachedPath.lineTo(px, py)
            }
            cachedPath.close()
            canvas.drawPath(cachedPath, paint)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Top Edge View
    // ─────────────────────────────────────────────────────────────────────────

    private class TopEdgeView(context: Context) : BaseEdgeView(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isLandscape(context)) {
                if (visibility != GONE) visibility = GONE
                return
            }
            renderVisualizer(canvas, isTop = true)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bottom Edge View
    // ─────────────────────────────────────────────────────────────────────────

    private class BottomEdgeView(context: Context) : BaseEdgeView(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isLandscape(context)) {
                if (visibility != GONE) visibility = GONE
                return
            }
            renderVisualizer(canvas, isTop = false)
        }
    }
}

