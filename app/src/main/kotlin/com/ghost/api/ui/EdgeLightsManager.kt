package com.ghost.api.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
    private var topView: TopEdgeView? = null
    private var bottomView: BottomEdgeView? = null
    var isShowing = false
        private set

    fun toggle(context: Context) {
        if (isShowing) hide(context) else show(context)
    }

    fun show(context: Context) {
        if (isShowing) return

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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            baseFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val bottomParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            stripHeight,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            baseFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        topView = TopEdgeView(context)
        bottomView = BottomEdgeView(context)

        try {
            windowManager?.addView(topView, topParams)
            windowManager?.addView(bottomView, bottomParams)
            SystemVisualizer.init(context)
            SystemVisualizer.addListener(this)
            isShowing = true
        } catch (e: Exception) {
            Timber.e(e, "Failed to show edge lights overlay strips")
            hide(context)
        }
    }

    fun hide(context: Context) {
        if (!isShowing && topView == null && bottomView == null) return
        SystemVisualizer.removeListener(this)
        try {
            topView?.let { windowManager?.removeView(it) }
            bottomView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Timber.w(e, "Error removing edge light views")
        }
        topView = null
        bottomView = null
        isShowing = false
    }

    override fun onAudioData(waveform: ByteArray, fft: ByteArray, intensity: Float, bass: Float) {
        topView?.updateAudioData(fft, intensity, bass)
        bottomView?.updateAudioData(fft, intensity, bass)
    }

    override fun onColorsChanged(colors: IntArray?) {
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

        fun updateAudioData(fft: ByteArray, intensity: Float, bass: Float) {
            smoothedBass = smoothedBass * 0.72f + bass * 0.28f

            fftHistory.addFirst(fft.copyOf())
            bassHistory.addFirst(bass)
            while (fftHistory.size > LAYERS) fftHistory.removeLast()
            while (bassHistory.size > LAYERS) bassHistory.removeLast()

            mainHandler.post { invalidate() }
        }

        fun updateColors(colors: IntArray?) {
            targetColors = colors ?: defaultColors
            mainHandler.post { invalidate() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Top Edge View (Bars grow downward from top)
    // ─────────────────────────────────────────────────────────────────────────

    private class TopEdgeView(context: Context) : BaseEdgeView(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
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
            if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
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
