package com.ghost.api.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class AudioVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A78BFA") // Accent purple
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 10f
    }

    private val numBars = 15
    private val heights = FloatArray(numBars)
    private var isPlaying = false

    private val animateRunnable = object : Runnable {
        override fun run() {
            if (isPlaying) {
                for (i in 0 until numBars) {
                    heights[i] = Random.nextFloat() * height
                }
                invalidate()
                postDelayed(this, 100)
            } else {
                for (i in 0 until numBars) {
                    heights[i] = 10f // Flat line
                }
                invalidate()
            }
        }
    }

    fun startAnimating() {
        if (!isPlaying) {
            isPlaying = true
            post(animateRunnable)
        }
    }

    fun stopAnimating() {
        isPlaying = false
        post(animateRunnable) // Trigger one last update to flatten
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val barWidth = width / (numBars * 2f)
        paint.strokeWidth = barWidth
        
        val startX = barWidth / 2
        val step = width / numBars.toFloat()
        
        for (i in 0 until numBars) {
            val x = startX + (i * step)
            val barHeight = if (isPlaying) heights[i] else 10f
            val top = height / 2f - barHeight / 2f
            val bottom = height / 2f + barHeight / 2f
            
            canvas.drawLine(x, top, x, bottom, paint)
        }
    }
}
