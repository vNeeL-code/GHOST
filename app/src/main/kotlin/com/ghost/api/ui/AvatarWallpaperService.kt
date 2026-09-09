package com.ghost.api.ui

import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.core.graphics.ColorUtils
import com.ghost.api.Constants
import com.ghost.api.GemmaService
import com.ghost.api.audio.SystemVisualizer
import kotlin.math.*

class AvatarWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return AvatarEngine()
    }

    inner class AvatarEngine : Engine(), SystemVisualizer.AudioListener, SensorEventListener {
        
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
            Color.parseColor("#A78BFA"), // 0: Electric Purple (Subconscious Turing Core)
            Color.parseColor("#4285F4"), // 1: Google Blue
            Color.parseColor("#EA4335"), // 2: Google Red
            Color.parseColor("#FBBC05"), // 3: Google Yellow
            Color.parseColor("#34A853")  // 4: Google Green
        )

        // Default neutral star glow matches Ethereal Off-White Cobalt (#8BB4F6) from the App Icon & HUD Sparkle
        private val colorCobaltGlow = Color.parseColor("#8BB4F6")
        private var isCustomPaletteActive = false
        
        // Target and Current colors for smooth transitions
        private var targetColors: IntArray = defaultColors.copyOf()
        private var currentColors: IntArray = defaultColors.copyOf()
        
        // Parallax offsets
        private var baselinePitch = 0f
        private var baselineRoll = 0f
        private var pitchOffset = 0f
        private var rollOffset = 0f
        private var isBaselineSet = false
        private var sensorManager: SensorManager? = null
        private var rotationSensor: Sensor? = null
        
        // Animation loop
        private var rotationAngle = 0f
        private var isVisible = false
        private var frameSkipCounter = 0
        private val frameCallback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (isVisible) {
                    val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    val backend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO") ?: "AUTO"
                    val isInferencing = GemmaService.isInferencing

                    // Dynamic Substrate Throttling: If CPU inference is active on weak devices, cap wallpaper to 30fps
                    if (backend == "CPU" && isInferencing) {
                        frameSkipCounter++
                        if (frameSkipCounter % 2 != 0) {
                            try {
                                android.view.Choreographer.getInstance().postFrameCallback(this)
                            } catch (e: Exception) {}
                            return
                        }
                    }

                    interpolateColors()
                    drawFrame()
                    try {
                        android.view.Choreographer.getInstance().postFrameCallback(this)
                    } catch (e: Exception) {}
                }
            }
        }
        
        init {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            
            logoPaint.color = Color.WHITE
            logoPaint.textSize = 320f
            logoPaint.textAlign = Paint.Align.CENTER
            logoPaint.typeface = Typeface.DEFAULT_BOLD
            logoPaint.clearShadowLayer()
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
                try {
                    android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
                } catch (e: Exception) {}
            } else {
                SystemVisualizer.removeListener(this)
                sensorManager?.unregisterListener(this)
                try {
                    android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
                } catch (e: Exception) {}
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            this.isVisible = false
            try {
                android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
            } catch (e: Exception) {}
        }

        override fun onAudioData(waveform: ByteArray, fft: ByteArray, intensity: Float, bass: Float) {
            currentFft = fft
            smoothedIntensity = smoothedIntensity * 0.7f + intensity * 0.3f
            smoothedBass = smoothedBass * 0.7f + bass * 0.3f
        }

        override fun onColorsChanged(colors: IntArray?) {
            isCustomPaletteActive = (colors != null)
            targetColors = colors ?: defaultColors.copyOf()
        }

        private fun interpolateColors() {
            val limit = minOf(currentColors.size, targetColors.size)
            for (i in 0 until limit) {
                currentColors[i] = ColorUtils.blendARGB(currentColors[i], targetColors[i], 0.05f)
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                val backend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO") ?: "AUTO"
                val isSafeMode = prefs.getBoolean("safe_mode", false)

                // Substrate Guardrail Inversion:
                // 1. GPU/NPU/AUTO backend -> software CPU canvas (lockCanvas) to preserve 100% GPU VRAM & ALUs for LiteRT-LM.
                // 2. CPU backend or Safe Mode (weak devices) -> hardware GPU canvas (lockHardwareCanvas) to offload
                //    geometry rendering to GPU, freeing CPU cores for INT4 matrix multiplication.
                canvas = if ((backend == "CPU" || isSafeMode) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        holder.lockHardwareCanvas()
                    } catch (e: Exception) {
                        holder.lockCanvas()
                    }
                } else {
                    holder.lockCanvas()
                }

                if (canvas != null) {
                    val width = canvas.width.toFloat()
                    val height = canvas.height.toFloat()
                    
                    // Small base so rings start tight to center and explode outward on beats
                    val dynamicBaseRadius = min(width, height) * 0.08f
                    
                    // Nudged slightly left to perfectly center mathematically on screen
                    val cx = width / 2f - 12f + rollOffset * 150f
                    // Nudged slightly up to align with the widget/input bar center
                    val cy = height / 2f - 75f + pitchOffset * 150f
                    
                    canvas.drawColor(Color.parseColor("#0A0A0A"))
                    
                    rotationAngle += 0.2f + (smoothedBass / 100f)
                    
                    val isNoisy = smoothedBass > 100f || smoothedIntensity > 80f
                    
                    val preset = prefs.getString(Constants.PREF_VISUALIZER_PRESET, "GHOST") ?: "GHOST"
                    
                    if (preset == "SUDA") {
                        // === PRESET 1: CEPHALON SUDA (Warframe Hexagonal Lattice & Sacred Geometry) ===
                        drawCephalonSuda(canvas, cx + 12f, cy + 40f, dynamicBaseRadius)
                    } else {
                        // === PRESET 0: CLASSIC GHOST (Default Baseline — 100% untouched) ===
                        canvas.save()
                        // Nudge rings slightly right and down to optically align with the ✧ glyph
                        canvas.translate(cx + 12f, cy + 40f)
                        canvas.rotate(rotationAngle)
                        
                        if (isNoisy) {
                            drawIris(canvas, dynamicBaseRadius)
                        } else {
                            drawOscilloscopeFlower(canvas, dynamicBaseRadius)
                        }
                        
                        canvas.restore()
                        
                        // Multi-pass bloom glow:
                        // 1. When IDLE: Pure, deterministic single-tone Ethereal Cobalt (#8BB4F6) across all passes
                        // 2. When PLAYING MUSIC: Dynamic multi-layer album art swatch extraction (Vibrant, Dominant, Muted, DarkVibrant)
                        val bassBoost = smoothedBass * 1.5f
                        val baseStarSize = 1200f 
                        val bloomSizes   = floatArrayOf(
                            baseStarSize + 500f + bassBoost, // 0: Outermost Corona
                            baseStarSize + 300f + bassBoost, // 1: Mid-Outer Halo
                            baseStarSize + 150f + bassBoost, // 2: Mid-Inner Aura
                            baseStarSize + 50f + bassBoost   // 3: Inner (Closest to Star)
                        )
                        val bloomAlphas  = intArrayOf(35, 60, 95, 140)
                        
                        logoPaint.clearShadowLayer()
                        for (i in bloomSizes.indices) {
                            val layerColor = if (isCustomPaletteActive) {
                                // Extract multi-swatch palette from album art
                                val swatchIndex = when (i) {
                                    3 -> 1 % currentColors.size // Vibrant
                                    2 -> 0 % currentColors.size // Dominant
                                    1 -> 2 % currentColors.size // Muted
                                    else -> 3 % currentColors.size // Dark Vibrant
                                }
                                val rawColor = currentColors[swatchIndex]
                                ensureVisibleBloomColor(rawColor, colorCobaltGlow)
                            } else {
                                // Default idle state: Pure deterministic Ethereal Cobalt (#8BB4F6)
                                colorCobaltGlow
                            }

                            logoPaint.color = layerColor
                            logoPaint.textSize = bloomSizes[i]
                            logoPaint.alpha    = bloomAlphas[i]
                            val off = (logoPaint.descent() + logoPaint.ascent()) / 2f
                            canvas.drawText("✧", cx, cy - off, logoPaint)
                        }
                        
                        // Crisp Core star
                        logoPaint.color    = Color.parseColor("#F8FAFC")
                        logoPaint.alpha    = 255
                        logoPaint.textSize = baseStarSize
                        val textOffset = (logoPaint.descent() + logoPaint.ascent()) / 2f
                        canvas.drawText("✧", cx, cy - textOffset, logoPaint)
                    }
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
        }

        private fun ensureVisibleBloomColor(color: Int, fallback: Int): Int {
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            return if (luminance < 0.14) {
                ColorUtils.blendARGB(color, fallback, 0.70f)
            } else {
                color
            }
        }

        private fun drawOscilloscopeFlower(canvas: Canvas, baseRadius: Float) {
            val numPoints = 64
            val currentRadius = baseRadius + (smoothedBass * 2.5f)
            
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 8f
            paint.clearShadowLayer()
            
            for (c in currentColors.indices) {
                val color = currentColors[c]
                paint.color = color
                paint.alpha = max(0, 220 - (c * 15))
                
                val path = Path()
                for (i in 0..numPoints) {
                    val angle = (i * Math.PI * 2 / numPoints).toFloat()
                    
                    val binIndex = (i * 2) % (if (currentFft.isEmpty()) 1 else currentFft.size / 2)
                    val mag = if (currentFft.isNotEmpty()) {
                        val r = currentFft[binIndex]
                        val i_comp = currentFft[binIndex + 1]
                        Math.hypot(r.toDouble(), i_comp.toDouble()).toFloat()
                    } else 0f
                    
                    val rOffset = mag * 5f + (c * 45f)
                    val r = currentRadius + rOffset
                    
                    val x = cos(angle) * r
                    val y = sin(angle) * r
                    
                    if (i == 0) path.moveTo(x, y)
                    else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        }

        private fun drawIris(canvas: Canvas, baseRadius: Float) {
            val startRadius = baseRadius * 0.2f + smoothedBass * 1.5f
            
            paint.style = Paint.Style.STROKE
            paint.clearShadowLayer()
            
            for (i in 0 until 7) {
                val color = currentColors[i % currentColors.size]
                paint.color = color
                paint.strokeWidth = 20f + (smoothedIntensity / 8f) - (i * 1.5f)
                
                val radius = startRadius + (i * 140f) + (smoothedBass * (i * 0.9f))
                
                if (i == 0) {
                    drawOscilloscopeFlower(canvas, startRadius)
                } else {
                    canvas.drawCircle(0f, 0f, radius, paint)
                }
            }
        }

        /**
         * Preset 1: Cephalon Suda (Warframe)
         * 6-fold radial symmetry with procedural spiral arms of audio-reactive hexagons.
         * Math: Pure polar trigonometry on a 6-phase axis, modulated by FFT bass & treble.
         */
        private fun drawCephalonSuda(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
            canvas.save()
            canvas.translate(cx, cy)
            
            // Subtle slow rotational drift
            canvas.rotate(rotationAngle * 0.4f)

            val bassKick = (smoothedBass * 1.2f).coerceAtLeast(0f)
            val coreRadius = (baseRadius * 0.85f + bassKick * 0.5f).coerceIn(40f, 220f)

            paint.style = Paint.Style.FILL
            paint.clearShadowLayer()

            // 1. Mother Hexagon (Center Core of Suda)
            paint.color = Color.parseColor("#F8FAFC")
            paint.alpha = 240
            drawHexagon(canvas, 0f, 0f, coreRadius, paint)

            // Inner core shadow / accent ring
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            paint.color = if (isCustomPaletteActive) currentColors[0] else Color.parseColor("#38BDF8")
            paint.alpha = 220
            drawHexagon(canvas, 0f, 0f, coreRadius * 0.72f, paint)

            // 2. Six Spiral Hex Arms of Suda
            val numArms = 6
            val hexesPerArm = 5
            val spiralTwist = 0.22f // Farbrausch domain warp curvature

            for (arm in 0 until numArms) {
                val baseAngle = (arm * Math.PI * 2.0 / numArms).toFloat()

                for (step in 1..hexesPerArm) {
                    val progress = step.toFloat() / hexesPerArm.toFloat()
                    
                    // Curved logarithmic spiral distance
                    val distance = coreRadius * 1.45f + (step * (42f + bassKick * 0.45f))
                    val angle = baseAngle + (step * spiralTwist) + (smoothedIntensity * 0.003f)

                    val hx = (cos(angle) * distance).toFloat()
                    val hy = (sin(angle) * distance).toFloat()

                    // Hex sizes shrink outward toward arm tips
                    val hexSize = (coreRadius * 0.32f * (1.1f - progress * 0.55f) + (smoothedIntensity * 0.1f)).coerceAtLeast(10f)

                    // FFT frequency mapping: inner hexes react to bass, outer hexes to treble
                    val fftIdx = (step * 2).coerceIn(0, if (currentFft.isEmpty()) 0 else currentFft.size / 2 - 1)
                    val fftMag = if (currentFft.isNotEmpty()) {
                        val r = currentFft[fftIdx * 2].toInt()
                        val ic = currentFft[fftIdx * 2 + 1].toInt()
                        kotlin.math.hypot(r.toDouble(), ic.toDouble()).toFloat()
                    } else 10f

                    val colorIdx = (arm + step) % currentColors.size
                    val armColor = if (isCustomPaletteActive) currentColors[colorIdx] else {
                        when (step) {
                            1 -> Color.parseColor("#F1F5F9") // Pure white near center
                            2 -> Color.parseColor("#93C5FD") // Pale Cobalt
                            3 -> Color.parseColor("#38BDF8") // Vivid Cyan
                            4 -> Color.parseColor("#818CF8") // Indigo
                            else -> Color.parseColor("#A78BFA") // Electric Purple tip
                        }
                    }

                    // Filled hex with subtle alpha decay
                    paint.style = Paint.Style.FILL
                    paint.color = armColor
                    paint.alpha = ((1f - progress * 0.35f) * 230).toInt().coerceIn(40, 255)
                    drawHexagon(canvas, hx, hy, hexSize + (fftMag * 0.08f), paint)

                    // Crisp geometric stroke outline
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3f
                    paint.color = Color.WHITE
                    paint.alpha = ((1f - progress * 0.5f) * 180).toInt().coerceIn(20, 200)
                    drawHexagon(canvas, hx, hy, hexSize + (fftMag * 0.08f), paint)
                }
            }

            // 3. Ethereal Suda Hexagonal Corona Glow (Multi-pass)
            paint.style = Paint.Style.STROKE
            val glowRadius = coreRadius * 2.8f + bassKick * 1.5f
            for (g in 1..3) {
                paint.strokeWidth = 4f * g
                paint.color = if (isCustomPaletteActive) currentColors[g % currentColors.size] else Color.parseColor("#38BDF8")
                paint.alpha = (90 / g).coerceIn(10, 100)
                drawHexagon(canvas, 0f, 0f, glowRadius + (g * 35f), paint)
            }

            canvas.restore()
        }

        /**
         * Regular 6-vertex regular polygon generator
         */
        private fun drawHexagon(canvas: Canvas, x: Float, y: Float, radius: Float, paint: Paint) {
            val path = Path()
            for (i in 0 until 6) {
                val angle = (i * Math.PI / 3.0).toFloat()
                val px = x + (radius * cos(angle))
                val py = y + (radius * sin(angle))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            canvas.drawPath(path, paint)
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                // Read tilt directly from the rotation matrix instead of getOrientation().
                // R[7] = -sin(pitch_euler), R[6] = -sin(roll)*cos(pitch)
                // Both are naturally bounded to [-1, 1] with zero discontinuities or
                // gimbal lock, so no Euler angle wrap-around can fling the canvas off-screen.
                val tiltX = -rotationMatrix[6]  // left-right tilt
                val tiltY = -rotationMatrix[7]  // forward-back tilt

                if (!isBaselineSet) {
                    baselinePitch = tiltY
                    baselineRoll  = tiltX
                    isBaselineSet = true
                    return
                }

                // Slow leaky integrator: anchors baseline to how the phone is usually held
                baselinePitch = baselinePitch * 0.99f + tiltY * 0.01f
                baselineRoll  = baselineRoll  * 0.99f + tiltX * 0.01f

                // Clamp raw delta so a sudden orientation change can't spike the canvas,
                // then smooth the final offset to kill any remaining jitter
                val rawPitch = (tiltY - baselinePitch).coerceIn(-0.25f, 0.25f)
                val rawRoll  = (tiltX - baselineRoll ).coerceIn(-0.25f, 0.25f)
                pitchOffset = pitchOffset * 0.8f + rawPitch * 0.2f
                rollOffset  = rollOffset  * 0.8f + rawRoll  * 0.2f
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        
        override fun onDestroy() {
            super.onDestroy()
            SystemVisualizer.removeListener(this)
            sensorManager?.unregisterListener(this)
            try {
                android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
            } catch (e: Exception) {}
        }
    }
}
