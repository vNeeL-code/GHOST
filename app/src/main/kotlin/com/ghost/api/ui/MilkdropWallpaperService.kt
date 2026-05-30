package com.ghost.api.ui

import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.core.graphics.ColorUtils
import com.ghost.api.audio.SystemVisualizer
import kotlin.math.*

class MilkdropWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return MilkdropEngine()
    }

    inner class MilkdropEngine : Engine(), SystemVisualizer.AudioListener, SensorEventListener {
        
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        private var currentFft = ByteArray(0)
        private var smoothedIntensity = 0f
        private var smoothedBass = 0f
        
        // Default Google Colors + Accent Purple
        private val defaultColors = intArrayOf(
            Color.parseColor("#A78BFA"),
            Color.parseColor("#4285F4"),
            Color.parseColor("#EA4335"),
            Color.parseColor("#FBBC05"),
            Color.parseColor("#34A853")
        )
        
        // Target and Current colors for smooth transitions
        private var targetColors: IntArray = defaultColors.copyOf()
        private var currentColors: IntArray = defaultColors.copyOf()
        
        // Parallax offsets
        private var baselinePitch = 0f
        private var baselineRoll = 0f
        private var pitchOffset = 0f
        private var rollOffset = 0f
        private var sensorManager: SensorManager? = null
        private var rotationSensor: Sensor? = null
        
        // Animation loop
        private var rotationAngle = 0f
        private var isVisible = false
        private val handler = Handler(Looper.getMainLooper())
        private val drawRunnable = object : Runnable {
            override fun run() {
                if (isVisible) {
                    interpolateColors()
                    drawFrame()
                    handler.postDelayed(this, 16L) // ~60fps
                }
            }
        }
        
        init {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            
            logoPaint.color = Color.WHITE
            logoPaint.textSize = 140f
            logoPaint.textAlign = Paint.Align.CENTER
            logoPaint.typeface = Typeface.DEFAULT_BOLD
            logoPaint.setShadowLayer(30f, 0f, 0f, defaultColors[0])
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            SystemVisualizer.init(applicationContext)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            if (visible) {
                SystemVisualizer.addListener(this)
                sensorManager?.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
                handler.post(drawRunnable)
            } else {
                SystemVisualizer.removeListener(this)
                sensorManager?.unregisterListener(this)
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            this.isVisible = false
            handler.removeCallbacks(drawRunnable)
        }

        override fun onAudioData(waveform: ByteArray, fft: ByteArray, intensity: Float, bass: Float) {
            currentFft = fft
            smoothedIntensity = smoothedIntensity * 0.7f + intensity * 0.3f
            smoothedBass = smoothedBass * 0.7f + bass * 0.3f
        }

        override fun onColorsChanged(colors: IntArray?) {
            targetColors = colors ?: defaultColors.copyOf()
        }

        private fun interpolateColors() {
            for (i in currentColors.indices) {
                // Smoothly blend from current to target
                currentColors[i] = ColorUtils.blendARGB(currentColors[i], targetColors[i], 0.05f)
            }
            // Update logo glow color
            logoPaint.setShadowLayer(30f, 0f, 0f, currentColors[0])
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    val width = canvas.width.toFloat()
                    val height = canvas.height.toFloat()
                    
                    val dynamicBaseRadius = min(width, height) * 0.35f
                    
                    val cx = width / 2f + rollOffset * 150f
                    val cy = height / 2f + pitchOffset * 150f
                    
                    canvas.drawColor(Color.parseColor("#0A0A0A"))
                    
                    rotationAngle += 0.2f + (smoothedBass / 100f)
                    
                    val isNoisy = smoothedBass > 100f || smoothedIntensity > 80f
                    
                    canvas.save()
                    canvas.translate(cx, cy)
                    canvas.rotate(rotationAngle)
                    
                    if (isNoisy) {
                        drawIris(canvas, dynamicBaseRadius)
                    } else {
                        drawOscilloscopeFlower(canvas, dynamicBaseRadius)
                    }
                    
                    canvas.restore()
                    
                    val textOffset = (logoPaint.descent() + logoPaint.ascent()) / 2f
                    canvas.drawText("✧", cx, cy - textOffset, logoPaint)
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
        }

        private fun drawOscilloscopeFlower(canvas: Canvas, baseRadius: Float) {
            val numPoints = 120
            val currentRadius = baseRadius + (smoothedBass * 1.5f)
            
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            
            for (c in currentColors.indices) {
                val color = currentColors[c]
                paint.color = color
                paint.alpha = 200 - (c * 20)
                paint.setShadowLayer(30f + (smoothedBass/5f), 0f, 0f, color)
                
                val path = Path()
                for (i in 0..numPoints) {
                    val angle = (i * Math.PI * 2 / numPoints).toFloat()
                    
                    val binIndex = (i * 2) % (if (currentFft.isEmpty()) 1 else currentFft.size / 2)
                    val mag = if (currentFft.isNotEmpty()) {
                        val r = currentFft[binIndex]
                        val i_comp = currentFft[binIndex + 1]
                        Math.hypot(r.toDouble(), i_comp.toDouble()).toFloat()
                    } else 0f
                    
                    val rOffset = mag * 3f + (c * 15f)
                    val r = currentRadius + rOffset
                    
                    val x = cos(angle) * r
                    val y = sin(angle) * r
                    
                    if (i == 0) path.moveTo(x, y)
                    else path.lineTo(x, y)
                }
                path.close()
                paint.strokeWidth = 6f
                paint.alpha = max(0, 200 - (c * 20))
                canvas.drawPath(path, paint)
            }
        }

        private fun drawIris(canvas: Canvas, baseRadius: Float) {
            val startRadius = baseRadius * 0.8f + smoothedBass
            
            paint.style = Paint.Style.STROKE
            
            for (i in 0 until 5) {
                val color = currentColors[i % currentColors.size]
                paint.color = color
                paint.strokeWidth = 15f + (smoothedIntensity / 15f)
                paint.setShadowLayer(50f + (smoothedBass/2f), 0f, 0f, color)
                
                val radius = startRadius + (i * 70f) + (smoothedBass * (i * 0.4f))
                
                if (i == 0) {
                    drawOscilloscopeFlower(canvas, startRadius)
                } else {
                    canvas.drawCircle(0f, 0f, radius, paint)
                }
            }
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                
                val currentPitch = orientation[1]
                val currentRoll = orientation[2]

                // Leaky integrator to anchor the baseline to the current orientation
                if (baselinePitch == 0f && baselineRoll == 0f) {
                    baselinePitch = currentPitch
                    baselineRoll = currentRoll
                } else {
                    baselinePitch = baselinePitch * 0.98f + currentPitch * 0.02f
                    baselineRoll = baselineRoll * 0.98f + currentRoll * 0.02f
                }
                
                pitchOffset = currentPitch - baselinePitch
                rollOffset = currentRoll - baselineRoll
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        
        override fun onDestroy() {
            super.onDestroy()
            SystemVisualizer.removeListener(this)
            sensorManager?.unregisterListener(this)
            handler.removeCallbacks(drawRunnable)
        }
    }
}
