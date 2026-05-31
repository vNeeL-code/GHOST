package com.ghost.api.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.ghost.api.audio.SystemVisualizer
import kotlin.math.max
import kotlin.math.min

@Suppress("DEPRECATION")
object EdgeLightsManager : SystemVisualizer.AudioListener {

    private var windowManager: WindowManager? = null
    private var overlayView: EdgeLightsView? = null
    var isShowing = false
        private set

    fun toggle(context: Context) {
        if (isShowing) hide(context) else show(context)
    }

    fun show(context: Context) {
        if (isShowing) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = EdgeLightsView(context)

        val metrics = android.util.DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(metrics)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            metrics.heightPixels,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager?.addView(overlayView, params)
        SystemVisualizer.init(context)
        SystemVisualizer.addListener(this)
        isShowing = true
    }

    fun hide(context: Context) {
        if (!isShowing) return
        SystemVisualizer.removeListener(this)
        try { windowManager?.removeView(overlayView) } catch (e: Exception) {}
        overlayView = null
        isShowing = false
    }

    override fun onAudioData(waveform: ByteArray, fft: ByteArray, intensity: Float, bass: Float) {
        overlayView?.updateAudioData(fft, intensity, bass)
    }

    override fun onColorsChanged(colors: IntArray?) {
        overlayView?.updateColors(colors)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View
    // ─────────────────────────────────────────────────────────────────────────

    private class EdgeLightsView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            style     = Paint.Style.STROKE
        }

        private var smoothedBass = 0f

        // ── 3-frame temporal history ──────────────────────────────────────────
        // Frame 0 = most recent (current), Frame 1 = -1, Frame 2 = -2 frames ago.
        // Drawn oldest→newest so the newest layer sits on top.
        private val LAYERS     = 3
        private val fftHistory  = ArrayDeque<ByteArray>(LAYERS)
        private val bassHistory = ArrayDeque<Float>(LAYERS)

        // Layer 0 = most recent
        // Layer 2 = oldest ghost
        // Reduced base alpha so you can see older frames bleeding through newer frames
        private val layerAlphas  = intArrayOf(160, 90, 45)
        private val heightScales = floatArrayOf(1.00f, 0.72f, 0.46f)
        // Make ghost layers slightly wider so they bleed out the sides like a true physical echo
        private val layerWidthScales = floatArrayOf(0.65f, 0.85f, 1.05f)
        
        // Each layer picks a different palette swatch for the color-depth effect
        private val layerPaletteIdx = intArrayOf(0, 1, 2)

        // Default: Google / Ghost brand palette used when no album art is present
        private val defaultColors = intArrayOf(
            Color.parseColor("#A78BFA"),   // violet  – dominant
            Color.parseColor("#4285F4"),   // blue    – vibrant
            Color.parseColor("#34A853"),   // green   – muted
            Color.parseColor("#FBBC05"),   // yellow
            Color.parseColor("#EA4335"),   // red
        )
        private var targetColors: IntArray = defaultColors

        // ── Public update API ─────────────────────────────────────────────────

        fun updateAudioData(fft: ByteArray, intensity: Float, bass: Float) {
            smoothedBass = smoothedBass * 0.72f + bass * 0.28f

            fftHistory.addFirst(fft.copyOf())
            bassHistory.addFirst(bass)
            while (fftHistory.size  > LAYERS) fftHistory.removeLast()
            while (bassHistory.size > LAYERS) bassHistory.removeLast()

            Handler(Looper.getMainLooper()).post { invalidate() }
        }

        fun updateColors(colors: IntArray?) {
            targetColors = colors ?: defaultColors
            Handler(Looper.getMainLooper()).post { invalidate() }
        }

        // ── Draw ──────────────────────────────────────────────────────────────

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Hide edge lights in landscape mode (e.g. fullscreen videos)
            if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                return
            }

            val numBars  = 32
            val spacing  = width.toFloat() / numBars
            val barWidth = spacing * 0.65f

            // Cache the real physical height to bypass navigation bar constraints
            // Add a 30px overflow bleed to guarantee we eat up any tiny lingering rendering gaps
            val metrics = android.util.DisplayMetrics()
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val physicalBottom = metrics.heightPixels.toFloat() + 30f

            val histFft  = fftHistory.toList()
            val histBass = bassHistory.toList()

            // Render oldest layer first so newest renders on top
            for (layerIdx in (histFft.indices).reversed()) {
                val fft      = histFft[layerIdx]
                val layBass  = histBass.getOrElse(layerIdx) { smoothedBass }
                val baseAlpha = layerAlphas.getOrElse(layerIdx) { 30 }
                val hScale   = heightScales.getOrElse(layerIdx) { 0.3f }
                val wScale   = layerWidthScales.getOrElse(layerIdx) { 0.65f }
                val palIdx   = layerPaletteIdx.getOrElse(layerIdx) { 0 }
                val color    = targetColors[palIdx % targetColors.size]

                paint.color       = color
                paint.strokeWidth = spacing * wScale
                paint.clearShadowLayer()

                for (i in 0 until numBars) {
                    // Map bar index to FFT bin (stereo-interleaved: step 2)
                    val binBase = ((i * 2) % max(1, fft.size / 2)) * 2
                    val mag = if (fft.size > binBase + 1) {
                        val re = fft[binBase].toInt()
                        val im = fft[binBase + 1].toInt()
                        Math.hypot(re.toDouble(), im.toDouble()).toFloat()
                    } else 0f

                    // Height: quiet = barely visible, loud = tall spikes
                    val glow      = (mag * 1.5f) + (layBass / 4f)
                    val barHeight = (8f + glow * 1.2f) * hScale

                    // Brighten taller bars slightly (perceptual pop)
                    val heightBoost = min(25, (glow * 1.0f).toInt())
                    paint.alpha = (baseAlpha + heightBoost).coerceIn(0, 255)

                    val x = (i * spacing) + (spacing / 2f)

                    // Top edge – bars grow downward
                    canvas.drawLine(x, 0f, x, barHeight, paint)

                    // Bottom edge – horizontal mirror so diagonally opposed bars
                    // respond to the same frequency bin (creates V-shape on bass hits)
                    val bx = width.toFloat() - x
                    canvas.drawLine(bx, physicalBottom, bx, physicalBottom - barHeight, paint)
                }
            }
        }
    }
}
