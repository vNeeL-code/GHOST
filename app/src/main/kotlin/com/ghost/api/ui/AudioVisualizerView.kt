package com.ghost.api.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Destiny-Ghost inspired Spectral Audio Visualizer.
 * Features:
 * - Center-out symmetric waveform pulse
 * - Dual-hue gradient (Cobalt #38BDF8 to Electric Purple #A78BFA with Ghost Core #22C55E highlights)
 * - Physics-based inertia/decay (smooth spring return, no harsh jarring drops)
 */
class AudioVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
    }

    private val numBars = 17 // Odd count ensures a true center bar for Ghost eye symmetry
    private val currentHeights = FloatArray(numBars) { 6f }
    private val targetHeights = FloatArray(numBars) { 6f }
    private var isPlaying = false
    private var phase = 0f

    private var gradient: LinearGradient? = null

    private val animateRunnable = object : Runnable {
        override fun run() {
            phase += 0.15f
            val centerIndex = numBars / 2
            val maxH = (height.toFloat() * 0.9f).coerceAtLeast(10f)

            if (isPlaying) {
                // Generate natural voice envelope (bell curve concentrated around center)
                for (i in 0 until numBars) {
                    val distFromCenter = abs(i - centerIndex).toFloat() / centerIndex.toFloat()
                    val falloff = (1f - distFromCenter * 0.65f).coerceIn(0.2f, 1f)
                    val waveMod = (sin(phase + i * 0.45f) + 1f) / 2f
                    val noise = Random.nextFloat() * 0.35f + 0.65f
                    targetHeights[i] = (maxH * falloff * waveMod * noise).coerceAtLeast(8f)
                }
            } else {
                for (i in 0 until numBars) {
                    targetHeights[i] = 6f // Resting Ghost idle baseline
                }
            }

            // Smooth spring damping interpolation
            var needsInvalidate = false
            for (i in 0 until numBars) {
                val diff = targetHeights[i] - currentHeights[i]
                currentHeights[i] += diff * 0.28f // 28% decay per tick = organic fluid inertia
                if (abs(diff) > 0.5f) {
                    needsInvalidate = true
                }
            }

            invalidate()

            if (isPlaying || needsInvalidate) {
                postDelayed(this, 30) // ~33 FPS smooth fluid animation loop
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            gradient = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(
                    Color.parseColor("#38BDF8"), // Neon Cobalt (wings)
                    Color.parseColor("#A78BFA"), // Electric Purple
                    Color.parseColor("#4ADE80"), // Ghost Core Green (center)
                    Color.parseColor("#A78BFA"), // Electric Purple
                    Color.parseColor("#38BDF8")  // Neon Cobalt (wings)
                ),
                floatArrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f),
                Shader.TileMode.CLAMP
            )
            barPaint.shader = gradient
            glowPaint.shader = gradient
        }
    }

    fun startAnimating() {
        if (!isPlaying) {
            isPlaying = true
            removeCallbacks(animateRunnable)
            post(animateRunnable)
        }
    }

    fun stopAnimating() {
        isPlaying = false
        // Let it run slightly to damp down to resting baseline
        post(animateRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val barWidth = (width / (numBars * 2.2f)).coerceAtLeast(4f)
        barPaint.strokeWidth = barWidth
        glowPaint.strokeWidth = barWidth * 1.5f

        val startX = barWidth
        val step = (width - startX * 2) / (numBars - 1).toFloat()
        val centerY = height / 2f

        for (i in 0 until numBars) {
            val x = startX + (i * step)
            val barH = currentHeights[i]
            val top = centerY - barH / 2f
            val bottom = centerY + barH / 2f

            // Draw ethereal glow behind active bars if high intensity
            if (barH > 14f) {
                glowPaint.alpha = ((barH / height.toFloat()) * 140).toInt().coerceIn(0, 160)
                canvas.drawLine(x, top, x, bottom, glowPaint)
            }

            barPaint.alpha = 240
            canvas.drawLine(x, top, x, bottom, barPaint)
        }
    }
}
