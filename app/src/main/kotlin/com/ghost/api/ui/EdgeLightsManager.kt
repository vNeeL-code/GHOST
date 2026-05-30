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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        windowManager?.addView(overlayView, params)
        SystemVisualizer.init(context)
        SystemVisualizer.addListener(this)
        isShowing = true
    }

    fun hide(context: Context) {
        if (!isShowing) return
        SystemVisualizer.removeListener(this)
        try {
            windowManager?.removeView(overlayView)
        } catch (e: Exception) {}
        overlayView = null
        isShowing = false
    }

    override fun onAudioData(waveform: ByteArray, fft: ByteArray, intensity: Float, bass: Float) {
        overlayView?.updateAudioData(fft, intensity, bass)
    }
    
    override fun onColorsChanged(colors: IntArray?) {
        overlayView?.updateColors(colors)
    }
    
    private class EdgeLightsView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
        }
        private var smoothedBass = 0f
        private var currentFft = ByteArray(0)
        
        // Google Colors + Accent Purple (Fallback)
        private val colorRed = Color.parseColor("#EA4335")
        private val colorBlue = Color.parseColor("#4285F4")
        private val colorYellow = Color.parseColor("#FBBC05")
        private val colorGreen = Color.parseColor("#34A853")
        private val colorPurple = Color.parseColor("#A78BFA")
        private val defaultColors = intArrayOf(colorPurple, colorBlue, colorRed, colorYellow, colorGreen)
        private var targetColors: IntArray = defaultColors
        
        fun updateAudioData(fft: ByteArray, intensity: Float, bass: Float) {
            smoothedBass = smoothedBass * 0.7f + bass * 0.3f
            currentFft = fft
            Handler(Looper.getMainLooper()).post { invalidate() }
        }
        
        fun updateColors(colors: IntArray?) {
            targetColors = colors ?: defaultColors
            Handler(Looper.getMainLooper()).post { invalidate() }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            val numDots = 32
            val spacing = width.toFloat() / numDots
            
            for (i in 0 until numDots) {
                // Extract specific frequency bin for this dot
                val binIndex = (i * 2) % (if (currentFft.isEmpty()) 1 else currentFft.size / 2)
                val mag = if (currentFft.isNotEmpty()) {
                    val r = currentFft[binIndex]
                    val i_comp = currentFft[binIndex + 1]
                    Math.hypot(r.toDouble(), i_comp.toDouble()).toFloat()
                } else 0f
                
                val glow = (mag * 2f) + (smoothedBass / 5f)
                val barWidth = spacing * 0.6f // Fixed width with clear gaps
                val x = (i * spacing) + (spacing / 2f)
                
                // Determine color based on index
                val color = targetColors[i % targetColors.size]
                
                paint.color = color
                paint.strokeWidth = barWidth
                paint.clearShadowLayer()
                paint.alpha = 100 + kotlin.math.min(155, (glow * 4f).toInt())
                
                val barHeight = 5f + (glow * 0.4f)
                
                // Top edge
                canvas.drawLine(x, 0f, x, barHeight, paint)
                
                // Bottom edge (mirrored 180 diagonally)
                val bottomX = width.toFloat() - x
                canvas.drawLine(bottomX, height.toFloat() - barHeight, bottomX, height.toFloat(), paint)
            }
        }
    }
}
