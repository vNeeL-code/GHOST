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
        private var launcherSwipeOffset = 0f
        private var sensorManager: SensorManager? = null
        private var rotationSensor: Sensor? = null
        
        // Animation loop & kinetic state
        private var rotationAngle = 0f
        private var animTime = 0f
        private var tunnelPhase = 0f
        private var trackZScroll = 0f
        private var bugLane = 0 // -1: Left, 0: Center, 1: Right
        private var bugTargetLane = 0
        private var bugHopProgress = 1f
        private var lastBeatHopTime = 0L
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

                    // Kinetic clocks
                    animTime += 0.016f
                    val bassImpulse = (smoothedBass / 100f).coerceIn(0f, 1f)
                    tunnelPhase = (tunnelPhase + 0.007f + (bassImpulse * 0.012f)) % 1.0f
                    trackZScroll = (trackZScroll + 0.018f + (bassImpulse * 0.025f)) % 1.0f

                    // Space Bug lane hopping physics (Option D)
                    if (bugHopProgress < 1f) {
                        bugHopProgress = (bugHopProgress + 0.08f).coerceAtMost(1f)
                        if (bugHopProgress >= 1f) {
                            bugLane = bugTargetLane
                        }
                    } else if (smoothedBass > 70f && System.currentTimeMillis() - lastBeatHopTime > 400L) {
                        lastBeatHopTime = System.currentTimeMillis()
                        val nextLane = when (bugLane) {
                            0 -> if (Math.random() > 0.5) 1 else -1
                            1 -> if (Math.random() > 0.6) 0 else -1
                            else -> if (Math.random() > 0.6) 0 else 1
                        }
                        bugTargetLane = nextLane
                        bugHopProgress = 0f
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

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            launcherSwipeOffset = (xOffset - 0.5f) * 2f
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
                    
                    val preset = prefs.getString(Constants.PREF_VISUALIZER_PRESET, "OPTION_A") ?: "OPTION_A"
                    
                    when (preset) {
                        "OPTION_B", "SUDA" -> {
                            drawOptionBHexLattice(canvas, cx + 12f, cy + 40f, dynamicBaseRadius)
                        }
                        "OPTION_C" -> {
                            drawOptionCDeltaTunnel(canvas, cx + 12f, cy + 40f, dynamicBaseRadius)
                        }
                        "OPTION_D", "AUDIOSURF" -> {
                            drawOptionDCyberHighway(canvas, cx, cy, width, height)
                        }
                        else -> {
                            drawOptionAOrbitalStar(canvas, cx, cy, dynamicBaseRadius, isNoisy)
                        }
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
         * Option A: Orbital Star / Iris (Classic Baseline)
         * Enhanced with dynamic booming bass expansion and subtle idle harmonic breathing.
         */
        private fun drawOptionAOrbitalStar(canvas: Canvas, cx: Float, cy: Float, dynamicBaseRadius: Float, isNoisy: Boolean) {
            canvas.save()
            // Nudge rings slightly right and down to optically align with the ✧ glyph
            canvas.translate(cx + 12f, cy + 40f)
            canvas.rotate(rotationAngle)
            
            // Faint idle celestial resonance ring (gives ambient life even in silence)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = if (isCustomPaletteActive) currentColors[1 % currentColors.size] else Color.parseColor("#38BDF8")
            paint.alpha = 25
            canvas.drawCircle(0f, 0f, dynamicBaseRadius * 2.8f + (sin(rotationAngle * 0.3f) * 12f), paint)

            if (isNoisy) {
                drawIris(canvas, dynamicBaseRadius)
            } else {
                drawOscilloscopeFlower(canvas, dynamicBaseRadius)
            }
            
            canvas.restore()
            
            // Multi-pass bloom glow:
            // Idle harmonic breathing + explosive booming on audio kicks
            val idleBreath = sin(rotationAngle * 0.4f) * 25f
            val bassBoost = smoothedBass * 3.2f // Booming expansion on audio beats!
            val baseStarSize = 1200f 
            val bloomSizes = floatArrayOf(
                baseStarSize + 550f + bassBoost + idleBreath,         // 0: Outermost Corona
                baseStarSize + 340f + bassBoost + (idleBreath * 0.6f), // 1: Mid-Outer Halo
                baseStarSize + 170f + (bassBoost * 0.7f),              // 2: Mid-Inner Aura
                baseStarSize + 50f + (bassBoost * 0.3f)                // 3: Inner (Closest to Star)
            )
            val bloomAlphas = intArrayOf(38, 65, 105, 150)
            
            logoPaint.clearShadowLayer()
            for (i in bloomSizes.indices) {
                val layerColor = if (isCustomPaletteActive) {
                    val swatchIndex = when (i) {
                        3 -> 1 % currentColors.size // Vibrant
                        2 -> 0 % currentColors.size // Dominant
                        1 -> 2 % currentColors.size // Muted
                        else -> 3 % currentColors.size // Dark Vibrant
                    }
                    val rawColor = currentColors[swatchIndex]
                    ensureVisibleBloomColor(rawColor, colorCobaltGlow)
                } else {
                    colorCobaltGlow
                }

                logoPaint.color = layerColor
                logoPaint.textSize = bloomSizes[i]
                logoPaint.alpha = bloomAlphas[i]
                val off = (logoPaint.descent() + logoPaint.ascent()) / 2f
                canvas.drawText("✧", cx, cy - off, logoPaint)
            }
            
            // Crisp Core star
            logoPaint.color = Color.parseColor("#F8FAFC")
            logoPaint.alpha = 255
            logoPaint.textSize = baseStarSize
            val textOffset = (logoPaint.descent() + logoPaint.ascent()) / 2f
            canvas.drawText("✧", cx, cy - textOffset, logoPaint)
        }

        /**
         * Option B: Hex Lattice & Lasers (Sacred Geometry / Suda)
         * Hollow cyber-hex chamber with centered model unicode glyph (✧),
         * vertex-aligned concentric hex halos, Beat Saber laser fan trapezoids, and spiral arms.
         */
        private fun drawOptionBHexLattice(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
            canvas.save()
            canvas.translate(cx, cy)
            
            // Subtle slow rotational drift
            canvas.rotate(rotationAngle * 0.35f)

            val bassKick = (smoothedBass * 1.5f).coerceAtLeast(0f)
            val coreRadius = (baseRadius * 0.95f + bassKick * 0.6f).coerceIn(45f, 240f)

            paint.clearShadowLayer()

            // 1. Concentric Vertex-Aligned Hexagonal Halos (Multi-Pass Bloom for Geometry)
            paint.style = Paint.Style.STROKE
            val haloRadii = floatArrayOf(
                coreRadius * 1.8f + bassKick * 0.8f,
                coreRadius * 2.8f + bassKick * 1.5f,
                coreRadius * 4.2f + bassKick * 2.4f,
                coreRadius * 6.0f + bassKick * 3.5f
            )
            val haloAlphas = intArrayOf(75, 45, 25, 12)
            val haloWidths = floatArrayOf(3.5f, 2.5f, 1.8f, 1.2f)

            for (h in haloRadii.indices) {
                paint.strokeWidth = haloWidths[h]
                paint.color = if (isCustomPaletteActive) currentColors[h % currentColors.size] else Color.parseColor("#38BDF8")
                paint.alpha = haloAlphas[h]
                drawHexagon(canvas, 0f, 0f, haloRadii[h], paint)
            }

            // 2. Beat Saber Laser Trapezoids & Vertex Beams
            val maxReach = (canvas.width + canvas.height) * 0.75f
            val laserStrobeAlpha = (20f + (smoothedBass * 0.8f).coerceIn(0f, 120f)).toInt()

            for (arm in 0 until 6) {
                val vertexAngle = (arm * Math.PI / 3.0).toFloat()
                val cosA = cos(vertexAngle).toFloat()
                val sinA = sin(vertexAngle).toFloat()
                val normX = -sinA
                val normY = cosA

                val startDist = coreRadius * 1.05f
                val endDist = maxReach

                val wStart = 6f
                val wEnd = 50f + (smoothedBass * 0.2f)

                val p1x = (cosA * startDist) - (normX * wStart * 0.5f)
                val p1y = (sinA * startDist) - (normY * wStart * 0.5f)
                val p2x = (cosA * startDist) + (normX * wStart * 0.5f)
                val p2y = (sinA * startDist) + (normY * wStart * 0.5f)

                val p3x = (cosA * endDist) + (normX * wEnd * 0.5f)
                val p3y = (sinA * endDist) + (normY * wEnd * 0.5f)
                val p4x = (cosA * endDist) - (normX * wEnd * 0.5f)
                val p4y = (sinA * endDist) - (normY * wEnd * 0.5f)

                // Translucent laser beam interior fill
                val laserPath = Path()
                laserPath.moveTo(p1x, p1y)
                laserPath.lineTo(p2x, p2y)
                laserPath.lineTo(p3x, p3y)
                laserPath.lineTo(p4x, p4y)
                laserPath.close()

                paint.style = Paint.Style.FILL
                paint.color = if (isCustomPaletteActive) currentColors[arm % currentColors.size] else Color.parseColor("#38BDF8")
                paint.alpha = (laserStrobeAlpha * 0.4f).toInt().coerceIn(6, 60)
                canvas.drawPath(laserPath, paint)

                // Two razor-sharp solid outer laser edges (Beat Saber look)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.8f
                paint.color = Color.parseColor("#E0F2FE")
                paint.alpha = laserStrobeAlpha.coerceIn(30, 240)
                canvas.drawLine(p1x, p1y, p4x, p4y, paint)
                canvas.drawLine(p2x, p2y, p3x, p3y, paint)
            }

            // 3. Six Logarithmic Spiral Hex Arms
            val numArms = 6
            val hexesPerArm = 5
            val spiralTwist = 0.22f

            for (arm in 0 until numArms) {
                val baseAngle = (arm * Math.PI * 2.0 / numArms).toFloat()

                for (step in 1..hexesPerArm) {
                    val progress = step.toFloat() / hexesPerArm.toFloat()
                    val distance = coreRadius * 1.35f + (step * (42f + bassKick * 0.45f))
                    val angle = baseAngle + (step * spiralTwist) + (smoothedIntensity * 0.003f)

                    val hx = (cos(angle) * distance).toFloat()
                    val hy = (sin(angle) * distance).toFloat()
                    val hexSize = (coreRadius * 0.30f * (1.1f - progress * 0.55f) + (smoothedIntensity * 0.1f)).coerceAtLeast(8f)

                    val colorIdx = (arm + step) % currentColors.size
                    val armColor = if (isCustomPaletteActive) currentColors[colorIdx] else {
                        when (step) {
                            1 -> Color.parseColor("#F1F5F9")
                            2 -> Color.parseColor("#93C5FD")
                            3 -> Color.parseColor("#38BDF8")
                            4 -> Color.parseColor("#818CF8")
                            else -> Color.parseColor("#A78BFA")
                        }
                    }

                    paint.style = Paint.Style.FILL
                    paint.color = armColor
                    paint.alpha = ((1f - progress * 0.35f) * 230).toInt().coerceIn(40, 255)
                    drawHexagon(canvas, hx, hy, hexSize, paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.5f
                    paint.color = Color.WHITE
                    paint.alpha = ((1f - progress * 0.5f) * 180).toInt().coerceIn(20, 200)
                    drawHexagon(canvas, hx, hy, hexSize, paint)
                }
            }

            // 4. Mother Hexagon (Hollow Obsidian Cyber Chamber)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#060A10")
            paint.alpha = 235
            drawHexagon(canvas, 0f, 0f, coreRadius, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.parseColor("#F8FAFC")
            paint.alpha = 255
            drawHexagon(canvas, 0f, 0f, coreRadius, paint)

            paint.strokeWidth = 2.5f
            paint.color = if (isCustomPaletteActive) currentColors[0] else Color.parseColor("#38BDF8")
            paint.alpha = 200
            drawHexagon(canvas, 0f, 0f, coreRadius * 0.82f, paint)

            // 5. Model Unicode Glyph (✧) Centered with Layered Multi-Pass Bloom
            val glyphBaseSize = coreRadius * 1.35f
            val glyphBloomSizes = floatArrayOf(
                glyphBaseSize * 1.9f + (bassKick * 0.8f),
                glyphBaseSize * 1.55f + (bassKick * 0.5f),
                glyphBaseSize * 1.25f,
                glyphBaseSize
            )
            val glyphBloomAlphas = intArrayOf(40, 75, 120, 255)

            logoPaint.clearShadowLayer()
            for (i in glyphBloomSizes.indices) {
                val color = if (i == 3) {
                    Color.parseColor("#F8FAFC")
                } else if (isCustomPaletteActive) {
                    ensureVisibleBloomColor(currentColors[i % currentColors.size], colorCobaltGlow)
                } else {
                    colorCobaltGlow
                }

                logoPaint.color = color
                logoPaint.textSize = glyphBloomSizes[i]
                logoPaint.alpha = glyphBloomAlphas[i]
                val off = (logoPaint.descent() + logoPaint.ascent()) / 2f
                canvas.drawText("✧", 0f, -off, logoPaint)
            }

            canvas.restore()
        }

        /**
         * Option C: Delta Tunnel (Seven Nation Army / Infinite Triangle Mirror)
         * Continuous logarithmic zoom through nested equilateral triangles,
         * 3 corner laser guide rails, center focal triangle with unicode glyph (✧),
         * and audio-reactive Beat Saber laser strobe flashes.
         */
        private fun drawOptionCDeltaTunnel(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
            canvas.save()
            canvas.translate(cx, cy)

            val bassKick = (smoothedBass * 1.6f).coerceAtLeast(0f)
            val numTriangles = 8
            val baseSize = (baseRadius * 0.7f + bassKick * 0.3f).coerceIn(30f, 180f)

            // 1. Three Outer Corner Laser Guide Rails (Beat Saber Rails)
            val railReach = (canvas.width + canvas.height) * 0.85f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = if (isCustomPaletteActive) currentColors[0] else Color.parseColor("#38BDF8")
            paint.alpha = (30 + (smoothedBass * 0.5f).toInt()).coerceIn(20, 140)

            for (v in 0..2) {
                val railAngle = (v * Math.PI * 2.0 / 3.0 - Math.PI / 2.0).toFloat()
                val rx = (cos(railAngle) * railReach).toFloat()
                val ry = (sin(railAngle) * railReach).toFloat()
                canvas.drawLine(0f, 0f, rx, ry, paint)
            }

            // 2. Infinite Nested Equilateral Triangles Zooming Outward (Seven Nation Army Tunnel)
            for (i in 0 until numTriangles) {
                val p = ((tunnelPhase + (i.toFloat() / numTriangles)) % 1.0f)
                val scale = (baseSize * exp(p * 3.4f)).toFloat()

                val fadeIn = (p * 5f).coerceIn(0f, 1f)
                val fadeOut = ((1f - p) * 3f).coerceIn(0f, 1f)
                val totalAlpha = (fadeIn * fadeOut * 240f).toInt().coerceIn(0, 255)

                val colorIdx = i % currentColors.size
                val triangleColor = if (isCustomPaletteActive) currentColors[colorIdx] else {
                    if (i % 2 == 0) Color.parseColor("#38BDF8") else Color.parseColor("#F1F5F9")
                }

                val rotation = (sin(p * Math.PI.toFloat()) * 0.15f) + (if (i % 2 == 1) Math.PI.toFloat() else 0f)

                // Translucent Beat Saber laser body fill
                paint.style = Paint.Style.FILL
                paint.color = triangleColor
                paint.alpha = (totalAlpha * 0.12f + (smoothedBass * 0.15f)).toInt().coerceIn(5, 70)
                drawEquilateralTriangle(canvas, 0f, 0f, scale, rotation, paint)

                // Crisp solid laser edges
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (4f * (1f - p * 0.4f) + (smoothedBass * 0.02f)).coerceIn(1.5f, 6f)
                paint.color = triangleColor
                paint.alpha = totalAlpha
                drawEquilateralTriangle(canvas, 0f, 0f, scale, rotation, paint)

                // Beat Saber Laser Strobe Flash on prominent rungs
                if (i % 2 == 0 && smoothedBass > 60f) {
                    paint.strokeWidth = 5.5f
                    paint.color = Color.WHITE
                    paint.alpha = (smoothedBass * 0.8f).toInt().coerceIn(60, 220)
                    drawEquilateralTriangle(canvas, 0f, 0f, scale, rotation, paint)
                }
            }

            // 3. Vanishing Point Focal Core (Center Triangle with Model Glyph ✧)
            val focalRadius = (baseSize * 1.15f + bassKick * 0.4f).coerceIn(35f, 140f)

            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#060A10")
            paint.alpha = 240
            drawEquilateralTriangle(canvas, 0f, 0f, focalRadius, 0f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f
            paint.color = Color.parseColor("#F8FAFC")
            paint.alpha = 255
            drawEquilateralTriangle(canvas, 0f, 0f, focalRadius, 0f, paint)

            paint.strokeWidth = 2f
            paint.color = if (isCustomPaletteActive) currentColors[0] else Color.parseColor("#38BDF8")
            paint.alpha = 200
            drawEquilateralTriangle(canvas, 0f, 0f, focalRadius * 0.78f, 0f, paint)

            // Center Model Unicode Glyph (✧) with Multi-Pass Bloom
            val glyphSize = focalRadius * 1.25f
            val glyphBloomSizes = floatArrayOf(
                glyphSize * 1.8f + (bassKick * 0.6f),
                glyphSize * 1.45f + (bassKick * 0.3f),
                glyphSize * 1.18f,
                glyphSize
            )
            val glyphBloomAlphas = intArrayOf(35, 70, 115, 255)

            logoPaint.clearShadowLayer()
            for (g in glyphBloomSizes.indices) {
                val color = if (g == 3) {
                    Color.parseColor("#F8FAFC")
                } else if (isCustomPaletteActive) {
                    ensureVisibleBloomColor(currentColors[g % currentColors.size], colorCobaltGlow)
                } else {
                    colorCobaltGlow
                }
                logoPaint.color = color
                logoPaint.textSize = glyphBloomSizes[g]
                logoPaint.alpha = glyphBloomAlphas[g]
                val off = (logoPaint.descent() + logoPaint.ascent()) / 2f
                canvas.drawText("✧", 0f, -off + (focalRadius * 0.12f), logoPaint)
            }

            canvas.restore()
        }

        /**
         * Option D: Cyber Highway (Audiosurf × Thumper)
         * 2.5D perspective undulating audio track ("equalizer rug"), 3 lanes,
         * rhythm-reactive space beetle avatar hopping between lanes on beats, and parallax POV.
         */
        private fun drawOptionDCyberHighway(canvas: Canvas, cx: Float, cy: Float, width: Float, height: Float) {
            val vpX = cx + (launcherSwipeOffset * 90f)
            val vpY = cy * 0.72f
            val trackBottomY = height * 0.95f
            val bassKick = (smoothedBass * 1.5f).coerceAtLeast(0f)

            // 1. Horizon Glow & Vanishing Point Sun
            val horizonRadius = (width * 0.22f + (bassKick * 0.4f)).coerceIn(40f, 220f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = if (isCustomPaletteActive) currentColors[0] else Color.parseColor("#38BDF8")
            paint.alpha = (40 + (smoothedBass * 0.4f).toInt()).coerceIn(20, 140)
            canvas.drawCircle(vpX, vpY, horizonRadius, paint)

            // 2. Faint Perspective Speed Streaks (Warp Stars rushing past)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            val numStars = 16
            for (s in 0 until numStars) {
                val starAngle = (s * Math.PI * 2.0 / numStars).toFloat()
                val starProgress = ((animTime * 0.8f + (s * 0.37f)) % 1.0f)
                val starDist = (starProgress * starProgress * width * 0.85f) + 20f
                val sx = vpX + (cos(starAngle) * starDist).toFloat()
                val sy = vpY + (sin(starAngle) * starDist).toFloat()
                val trailLen = 15f + (starProgress * 40f)
                paint.alpha = ((1f - starProgress) * 90).toInt().coerceIn(10, 120)
                paint.color = if (isCustomPaletteActive) currentColors[s % currentColors.size] else Color.parseColor("#93C5FD")
                canvas.drawLine(sx, sy, sx + (cos(starAngle) * trailLen).toFloat(), sy + (sin(starAngle) * trailLen).toFloat(), paint)
            }

            // 3. Perspective Highway Rungs ("Equalizer Rug")
            val numRungs = 22
            val trackMaxWidth = width * 0.82f

            for (i in 0 until numRungs) {
                val p = ((i.toFloat() + (trackZScroll % 1.0f)) / numRungs.toFloat())
                val depthZ = p * p // Perspective projection compression

                val rungY = vpY + (trackBottomY - vpY) * depthZ
                val halfW = (trackMaxWidth * 0.5f) * depthZ + 8f

                val rippleWave = (sin((depthZ * 14f) - (animTime * 6f)) * (smoothedBass * 0.35f)).toFloat()
                val finalY = rungY + rippleWave

                val curveX = (sin((animTime * 1.2f) + (depthZ * 3.5f)) * (35f * depthZ)).toFloat()
                val rungCenterX = vpX + curveX

                val pLeft = rungCenterX - halfW
                val pRight = rungCenterX + halfW

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (1.5f + (depthZ * 3.5f)).coerceIn(1.2f, 5f)
                paint.color = if (isCustomPaletteActive) currentColors[i % currentColors.size] else Color.parseColor("#38BDF8")
                paint.alpha = (depthZ * 180f + (smoothedBass * 0.4f)).toInt().coerceIn(15, 230)
                canvas.drawLine(pLeft, finalY, pRight, finalY, paint)

                val laneWidth = (halfW * 2f) / 3f
                paint.strokeWidth = 1.2f
                paint.alpha = (depthZ * 110f).toInt().coerceIn(10, 140)
                canvas.drawPoint(pLeft + laneWidth, finalY, paint)
                canvas.drawPoint(pLeft + (laneWidth * 2f), finalY, paint)
            }

            // Outer Highway Glowing Guardrails
            val leftRailPath = Path()
            val rightRailPath = Path()
            for (step in 0..numRungs) {
                val p = step.toFloat() / numRungs.toFloat()
                val depthZ = p * p
                val rungY = vpY + (trackBottomY - vpY) * depthZ
                val halfW = (trackMaxWidth * 0.5f) * depthZ + 8f
                val rippleWave = (sin((depthZ * 14f) - (animTime * 6f)) * (smoothedBass * 0.35f)).toFloat()
                val curveX = (sin((animTime * 1.2f) + (depthZ * 3.5f)) * (35f * depthZ)).toFloat()
                val rungCenterX = vpX + curveX

                val lx = rungCenterX - halfW
                val rx = rungCenterX + halfW
                val ly = rungY + rippleWave

                if (step == 0) {
                    leftRailPath.moveTo(lx, ly)
                    rightRailPath.moveTo(rx, ly)
                } else {
                    leftRailPath.lineTo(lx, ly)
                    rightRailPath.lineTo(rx, ly)
                }
            }

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f
            paint.color = Color.parseColor("#E0F2FE")
            paint.alpha = 210
            canvas.drawPath(leftRailPath, paint)
            canvas.drawPath(rightRailPath, paint)

            // 4. The Thumper "Space Beetle" Surfer
            val bugDepthZ = 0.85f
            val bugBaseY = vpY + (trackBottomY - vpY) * bugDepthZ
            val bugHalfW = (trackMaxWidth * 0.5f) * bugDepthZ + 8f
            val bugCurveX = (sin((animTime * 1.2f) + (bugDepthZ * 3.5f)) * (35f * bugDepthZ)).toFloat()
            val bugTrackCenterX = vpX + bugCurveX
            val bugLaneWidth = (bugHalfW * 2f) / 3f

            val currentLaneX = bugTrackCenterX + (bugLane * bugLaneWidth * 0.7f)
            val targetLaneX = bugTrackCenterX + (bugTargetLane * bugLaneWidth * 0.7f)
            val bugX = currentLaneX + (targetLaneX - currentLaneX) * bugHopProgress
            val hopArc = (sin(bugHopProgress * Math.PI.toFloat()) * 35f).toFloat()
            val bugY = bugBaseY - hopArc

            val bankAngle = (targetLaneX - currentLaneX) * 0.25f * (1f - bugHopProgress)

            canvas.save()
            canvas.translate(bugX, bugY)
            canvas.rotate(bankAngle)

            val bugScale = (width * 0.055f + (bassKick * 0.08f)).coerceIn(24f, 75f)

            // Jet propulsion flame on track behind the beetle
            paint.style = Paint.Style.FILL
            paint.color = if (isCustomPaletteActive) currentColors[1 % currentColors.size] else Color.parseColor("#38BDF8")
            paint.alpha = (140 + (smoothedBass * 0.8f).toInt()).coerceIn(80, 240)
            canvas.drawCircle(0f, bugScale * 0.8f, bugScale * 0.35f, paint)

            // Segmented Beetle Carapace (Thumper Metallic Space Bug)
            paint.color = Color.parseColor("#0A0E17")
            paint.alpha = 250
            canvas.drawCircle(0f, 0f, bugScale, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.parseColor("#F8FAFC")
            paint.alpha = 255
            canvas.drawCircle(0f, 0f, bugScale, paint)

            // Glowing Wing Plates that flare on audio intensity
            val wingFlare = (smoothedIntensity * 0.15f).coerceIn(0f, 25f)
            paint.strokeWidth = 2.5f
            paint.color = if (isCustomPaletteActive) currentColors[0] else Color.parseColor("#38BDF8")
            canvas.drawLine(-bugScale * 0.7f, -bugScale * 0.3f, -bugScale * 1.3f - wingFlare, -bugScale * 0.7f, paint)
            canvas.drawLine(bugScale * 0.7f, -bugScale * 0.3f, bugScale * 1.3f + wingFlare, -bugScale * 0.7f, paint)

            // Center Model Glyph (✧) on Bug Shell
            logoPaint.color = Color.WHITE
            logoPaint.alpha = 255
            logoPaint.textSize = bugScale * 1.1f
            val bOff = (logoPaint.descent() + logoPaint.ascent()) / 2f
            canvas.drawText("✧", 0f, -bOff, logoPaint)

            canvas.restore()
        }

        /**
         * Regular 3-vertex equilateral triangle generator (Seven Nation Army / Delta)
         */
        private fun drawEquilateralTriangle(canvas: Canvas, x: Float, y: Float, radius: Float, rotation: Float, paint: Paint) {
            val path = Path()
            for (i in 0 until 3) {
                val angle = (i * Math.PI * 2.0 / 3.0 - Math.PI / 2.0).toFloat() + rotation
                val px = x + (radius * cos(angle))
                val py = y + (radius * sin(angle))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            canvas.drawPath(path, paint)
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
