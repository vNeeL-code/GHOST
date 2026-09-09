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
        private var strobeFlash = 0f // Audio threshold strobe trigger (0f = silent, 1f = blinding beat strobe)
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

                    // Exponential strobe decay (sharp beat onset, smooth falloff)
                    strobeFlash = (strobeFlash * 0.84f).coerceAtLeast(0f)

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
            val bassDelta = bass - smoothedBass
            // Audio beat threshold: sudden bass kick above threshold triggers strobe flash
            if (bassDelta > 26f && bass > 50f) {
                strobeFlash = 1f
            }
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
                            drawOptionDNeonSunset(canvas, cx, cy, width, height)
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

            // 2. Beat Saber Laser Trapezoids & Vertex Beams (Strobelight Trigger on Beat Threshold)
            val maxReach = (canvas.width + canvas.height) * 0.75f
            val laserStrobeAlpha = if (strobeFlash > 0.04f) {
                (strobeFlash * 255f).toInt().coerceIn(0, 255)
            } else {
                0 // 100% OFF during silence / non-beat!
            }

            if (laserStrobeAlpha > 0) {
                for (arm in 0 until 6) {
                    val vertexAngle = (arm * Math.PI / 3.0).toFloat()
                    val cosA = cos(vertexAngle).toFloat()
                    val sinA = sin(vertexAngle).toFloat()
                    val normX = -sinA
                    val normY = cosA

                    val startDist = coreRadius * 1.05f
                    val endDist = maxReach

                    val wStart = 6f
                    val wEnd = 60f + (smoothedBass * 0.3f)

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
                    paint.alpha = (laserStrobeAlpha * 0.45f).toInt().coerceIn(0, 110)
                    canvas.drawPath(laserPath, paint)

                    // Two razor-sharp solid outer laser edges (Beat Saber look)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.2f + (strobeFlash * 2.5f)
                    paint.color = if (strobeFlash > 0.4f) Color.WHITE else Color.parseColor("#E0F2FE")
                    paint.alpha = laserStrobeAlpha
                    canvas.drawLine(p1x, p1y, p4x, p4y, paint)
                    canvas.drawLine(p2x, p2y, p3x, p3y, paint)
                }
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

            // 1. Audio-Triggered Beat Saber Laser Strobe in Top Corners
            if (strobeFlash > 0.04f) {
                val reach = (canvas.width + canvas.height) * 0.9f
                val strobeAlpha = (strobeFlash * 255f).toInt().coerceIn(0, 255)

                // Left Corner Laser Fan Triangle
                val leftFan = Path()
                leftFan.moveTo(0f, 0f)
                leftFan.lineTo(-reach * 0.75f, -reach * 0.90f)
                leftFan.lineTo(-reach * 0.40f, -reach * 0.90f)
                leftFan.close()

                paint.style = Paint.Style.FILL
                paint.color = if (isCustomPaletteActive) currentColors[0] else Color.parseColor("#38BDF8")
                paint.alpha = (strobeAlpha * 0.45f).toInt().coerceIn(0, 110)
                canvas.drawPath(leftFan, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f + (strobeFlash * 3f)
                paint.color = Color.WHITE
                paint.alpha = strobeAlpha
                canvas.drawLine(0f, 0f, -reach * 0.75f, -reach * 0.90f, paint)
                canvas.drawLine(0f, 0f, -reach * 0.40f, -reach * 0.90f, paint)

                // Right Corner Laser Fan Triangle
                val rightFan = Path()
                rightFan.moveTo(0f, 0f)
                rightFan.lineTo(reach * 0.40f, -reach * 0.90f)
                rightFan.lineTo(reach * 0.75f, -reach * 0.90f)
                rightFan.close()

                paint.style = Paint.Style.FILL
                paint.alpha = (strobeAlpha * 0.45f).toInt().coerceIn(0, 110)
                canvas.drawPath(rightFan, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f + (strobeFlash * 3f)
                paint.color = Color.WHITE
                paint.alpha = strobeAlpha
                canvas.drawLine(0f, 0f, reach * 0.40f, -reach * 0.90f, paint)
                canvas.drawLine(0f, 0f, reach * 0.75f, -reach * 0.90f, paint)

                // Corner Laser Guide Rails
                paint.strokeWidth = 2.5f
                paint.color = Color.WHITE
                paint.alpha = strobeAlpha
                for (v in 0..2) {
                    val railAngle = (v * Math.PI * 2.0 / 3.0 - Math.PI / 2.0).toFloat()
                    val rx = (cos(railAngle) * reach).toFloat()
                    val ry = (sin(railAngle) * reach).toFloat()
                    canvas.drawLine(0f, 0f, rx, ry, paint)
                }
            }

            // 2. Infinite Nested Equilateral Triangles Zooming Outward (Seven Nation Army Tunnel)
            for (i in 0 until numTriangles) {
                val p = ((tunnelPhase + (i.toFloat() / numTriangles)) % 1.0f)
                val scale = (baseSize * exp(p * 3.4f)).toFloat()

                val fadeIn = (p * 5f).coerceIn(0f, 1f)
                val fadeOut = ((1f - p) * 3f).coerceIn(0f, 1f)
                val totalAlpha = (fadeIn * fadeOut * 240f).toInt().coerceIn(0, 255)

                val colorIdx = i % currentColors.size
                val baseColor = if (isCustomPaletteActive) currentColors[colorIdx] else {
                    if (i % 2 == 0) Color.parseColor("#38BDF8") else Color.parseColor("#F1F5F9")
                }
                // On beat strobe: blend color into blinding white!
                val triangleColor = if (strobeFlash > 0.05f) {
                    ColorUtils.blendARGB(baseColor, Color.WHITE, strobeFlash)
                } else {
                    baseColor
                }

                val rotation = (sin(p * Math.PI.toFloat()) * 0.15f) + (if (i % 2 == 1) Math.PI.toFloat() else 0f)

                // Translucent Beat Saber laser body fill
                paint.style = Paint.Style.FILL
                paint.color = triangleColor
                paint.alpha = (totalAlpha * 0.12f + (smoothedBass * 0.15f) + (strobeFlash * 40f)).toInt().coerceIn(5, 120)
                drawEquilateralTriangle(canvas, 0f, 0f, scale, rotation, paint)

                // Crisp solid laser edges
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (4f * (1f - p * 0.4f) + (smoothedBass * 0.02f) + (strobeFlash * 2.5f)).coerceIn(1.5f, 7.5f)
                paint.color = triangleColor
                paint.alpha = totalAlpha
                drawEquilateralTriangle(canvas, 0f, 0f, scale, rotation, paint)

                // Beat Saber Laser Strobe Flash on prominent rungs
                if (i % 2 == 0 && strobeFlash > 0.15f) {
                    paint.strokeWidth = 6.5f
                    paint.color = Color.WHITE
                    paint.alpha = (strobeFlash * 255f).toInt().coerceIn(0, 255)
                    drawEquilateralTriangle(canvas, 0f, 0f, scale, rotation, paint)
                }
            }

            // 3. Vanishing Point Focal Core (Center Triangle with Model Glyph ✧)
            val focalRadius = (baseSize * 1.15f + bassKick * 0.4f + (strobeFlash * 15f)).coerceIn(35f, 160f)

            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#060A10")
            paint.alpha = 240
            drawEquilateralTriangle(canvas, 0f, 0f, focalRadius, 0f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f + (strobeFlash * 3f)
            paint.color = if (strobeFlash > 0.05f) Color.WHITE else Color.parseColor("#F8FAFC")
            paint.alpha = 255
            drawEquilateralTriangle(canvas, 0f, 0f, focalRadius, 0f, paint)

            paint.strokeWidth = 2f
            paint.color = if (isCustomPaletteActive) currentColors[0] else Color.parseColor("#38BDF8")
            paint.alpha = 200
            drawEquilateralTriangle(canvas, 0f, 0f, focalRadius * 0.78f, 0f, paint)

            // Center Model Unicode Glyph (✧) with Multi-Pass Bloom
            val glyphSize = focalRadius * 1.25f + (strobeFlash * 20f)
            val glyphBloomSizes = floatArrayOf(
                glyphSize * 1.8f + (bassKick * 0.6f) + (strobeFlash * 35f),
                glyphSize * 1.45f + (bassKick * 0.3f) + (strobeFlash * 20f),
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
         * Option D: Neon Sunset (Outrun / Synthwave Horizon)
         * Inspired by the classic Wallpaper Engine "Neon Sunset":
         * - Giant striped synthwave sun with horizontal perspective scanline slits
         * - Expansive full-width 3D wireframe terrain mesh
         * - Left & right equalizer mountain ridges bouncing on live audio FFT
         * - Dynamic ~90° look-around parallax via device tilt & launcher swipe
         * - Audio-triggered strobelight threshold bursts
         */
        private fun drawOptionDNeonSunset(canvas: Canvas, cx: Float, cy: Float, width: Float, height: Float) {
            val horizonY = height * 0.50f
            val vpX = width * 0.5f + (rollOffset * 240f) + (launcherSwipeOffset * 200f)
            val bassKick = (smoothedBass * 1.5f).coerceAtLeast(0f)

            // 1. Deep Space Night Sky & Twinkling Stars
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.color = Color.parseColor("#05050D")
            canvas.drawRect(0f, 0f, width, horizonY, paint)

            // Faint retro starfield (upper sky)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            val numStars = 28
            for (s in 0 until numStars) {
                val seed = s * 73
                val starX = ((seed * 19) % width.toInt()).toFloat()
                val starY = ((seed * 31) % (horizonY * 0.75f).toInt()).toFloat()
                val twinkle = (sin(animTime * 2f + s) * 0.5f + 0.5f)
                paint.color = Color.WHITE
                paint.alpha = (twinkle * 130).toInt().coerceIn(20, 180)
                canvas.drawPoint(starX, starY, paint)
            }

            // 2. The Giant Striped Synthwave Sun (Horizon)
            val sunRadius = (min(width, height) * 0.30f + bassKick * 0.25f).coerceIn(85f, 320f)
            val sunCenterX = width * 0.5f + (rollOffset * 100f) + (launcherSwipeOffset * 90f)
            val sunCenterY = horizonY - (sunRadius * 0.40f)

            // Sun Ambient Corona / Radiant Bloom behind sun
            val coronaExtra = (bassKick * 0.6f) + (strobeFlash * 90f)
            paint.style = Paint.Style.FILL
            val coronaColors = intArrayOf(
                Color.parseColor("#F43F5E"), // Hot pink
                Color.parseColor("#FB923C"), // Solar amber
                Color.TRANSPARENT
            )
            paint.shader = RadialGradient(
                sunCenterX, sunCenterY, sunRadius + 60f + coronaExtra,
                coronaColors, floatArrayOf(0.4f, 0.75f, 1f), Shader.TileMode.CLAMP
            )
            paint.alpha = (100 + (smoothedBass * 0.4f).toInt() + (strobeFlash * 140f).toInt()).coerceIn(60, 255)
            canvas.drawCircle(sunCenterX, sunCenterY, sunRadius + 60f + coronaExtra, paint)
            paint.shader = null

            // Sun Body with Vertical Gradient: Yellow -> Orange -> Neon Magenta
            val sunShader = LinearGradient(
                sunCenterX, sunCenterY - sunRadius,
                sunCenterX, sunCenterY + sunRadius,
                intArrayOf(
                    Color.parseColor("#FEF08A"), // Electric pale yellow
                    Color.parseColor("#F59E0B"), // Golden orange
                    Color.parseColor("#E11D48")  // Hot pink / red
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            paint.shader = sunShader
            paint.alpha = 255
            canvas.drawCircle(sunCenterX, sunCenterY, sunRadius, paint)
            paint.shader = null

            // Horizontal Perspective Scanline Slits across the lower 65% of the sun
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#05050D") // Matches dark sky/space
            paint.alpha = 255
            val slitStartRelY = sunCenterY - (sunRadius * 0.15f)
            val numSlits = 7
            for (slit in 0 until numSlits) {
                val progress = slit.toFloat() / (numSlits - 1).toFloat()
                val slitY = slitStartRelY + (progress * (sunRadius * 1.15f))
                val slitHeight = 2.5f + (progress * progress * 14f) // Progressively thicker toward bottom
                canvas.drawRect(
                    sunCenterX - sunRadius - 10f,
                    slitY,
                    sunCenterX + sunRadius + 10f,
                    slitY + slitHeight,
                    paint
                )
            }

            // 3. Horizon Neon Glow Line (The divide between sky and wireframe floor)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f + (strobeFlash * 4f)
            paint.color = if (strobeFlash > 0.05f) Color.WHITE else Color.parseColor("#38BDF8")
            paint.alpha = (160 + (strobeFlash * 95f).toInt()).coerceIn(120, 255)
            canvas.drawLine(0f, horizonY, width, horizonY, paint)

            // 4. Full-Screen 3D Perspective Terrain Mesh with Equalizer Mountain Ridges
            val numRows = 22
            val numCols = 16 // -8 to +8 columns
            val gridGroundBottom = height * 1.05f

            val bassFFT = smoothedBass * 0.8f
            val midFFT = smoothedIntensity * 0.7f

            val meshX = Array(numRows + 1) { FloatArray(numCols + 1) }
            val meshY = Array(numRows + 1) { FloatArray(numCols + 1) }

            for (r in 0..numRows) {
                val p = ((r.toFloat() + (trackZScroll % 1f)) / numRows.toFloat()).coerceIn(0f, 1f)
                val depthZ = p * p // Quadratic perspective depth

                val baseRowY = horizonY + (gridGroundBottom - horizonY) * depthZ
                val rowHalfWidth = (width * 0.65f) * (depthZ + 0.05f) * 2.6f

                for (c in 0..numCols) {
                    val colNorm = (c.toFloat() / numCols.toFloat()) * 2f - 1f // -1.0 to +1.0
                    val colIndexFromCenter = (c - numCols / 2) // negative is left, positive is right
                    val absCol = abs(colIndexFromCenter)

                    val rawX = vpX + (colNorm * rowHalfWidth)

                    // Equalizer Mountain Elevation:
                    // Center columns (|absCol| <= 2) are flat highway valley
                    // Flank columns (|absCol| >= 3) rise into jagged wireframe peaks
                    var elevation = 0f
                    if (absCol >= 3) {
                        val flankFactor = (absCol - 2).toFloat()
                        val peakBase = flankFactor * (18f + (25f * depthZ))
                        val tooth = if (c % 2 == 0) 1.4f else 0.75f
                        val audioDeform = if (colIndexFromCenter < 0) {
                            bassFFT * (flankFactor * 0.35f) // Left mountain bounces on bass
                        } else {
                            midFFT * (flankFactor * 0.35f)  // Right mountain bounces on mids/treble
                        }
                        elevation = (peakBase * tooth) + audioDeform
                    }

                    meshX[r][c] = rawX
                    meshY[r][c] = baseRowY - elevation
                }
            }

            // Draw Wireframe Terrain Mesh:
            val terrainColor = if (strobeFlash > 0.05f) {
                ColorUtils.blendARGB(Color.parseColor("#E11D48"), Color.WHITE, strobeFlash)
            } else if (isCustomPaletteActive) {
                currentColors[0]
            } else {
                Color.parseColor("#C026D3") // Vibrant synthwave magenta
            }

            paint.style = Paint.Style.STROKE

            // A) Horizontal Rung Lines (across columns for each depth row)
            for (r in 0..numRows) {
                val p = r.toFloat() / numRows.toFloat()
                val depthZ = p * p
                paint.strokeWidth = (1.2f + (depthZ * 2.8f)).coerceIn(1f, 4.5f)
                val alphaBase = (depthZ * 170f + (strobeFlash * 80f)).toInt().coerceIn(20, 255)
                paint.color = terrainColor
                paint.alpha = alphaBase

                for (c in 0 until numCols) {
                    canvas.drawLine(meshX[r][c], meshY[r][c], meshX[r][c + 1], meshY[r][c + 1], paint)
                }
            }

            // B) Longitudinal Lines (connecting depth rows from horizon to foreground)
            for (c in 0..numCols) {
                val colIndexFromCenter = (c - numCols / 2)
                val absCol = abs(colIndexFromCenter)
                val isHighway = absCol <= 2
                val colColor = if (isHighway) {
                    if (strobeFlash > 0.05f) Color.WHITE else Color.parseColor("#38BDF8")
                } else {
                    terrainColor
                }

                for (r in 0 until numRows) {
                    val p = r.toFloat() / numRows.toFloat()
                    val depthZ = p * p
                    paint.strokeWidth = (1.2f + (depthZ * 2.5f)).coerceIn(1f, 4f)
                    val alphaBase = (depthZ * 160f + (strobeFlash * 90f)).toInt().coerceIn(15, 255)
                    paint.color = colColor
                    paint.alpha = alphaBase

                    canvas.drawLine(meshX[r][c], meshY[r][c], meshX[r + 1][c], meshY[r + 1][c], paint)

                    // Diagonal wireframe mountain braces on flanks for low-poly synthwave terrain
                    if (absCol >= 3 && r % 2 == 0) {
                        val nextC = if (colIndexFromCenter < 0) c + 1 else c - 1
                        if (nextC in 0..numCols) {
                            paint.strokeWidth = 1f
                            paint.alpha = (alphaBase * 0.6f).toInt().coerceIn(10, 150)
                            canvas.drawLine(meshX[r][c], meshY[r][c], meshX[r + 1][nextC], meshY[r + 1][nextC], paint)
                        }
                    }
                }
            }

            // 5. Center Outrun Hovercraft / Avatar
            val craftRow = (numRows * 0.82f).toInt().coerceIn(0, numRows)
            val craftX = (meshX[craftRow][numCols / 2] + meshX[craftRow][numCols / 2 + 1]) * 0.5f
            val craftY = (meshY[craftRow][numCols / 2] + meshY[craftRow][numCols / 2 + 1]) * 0.5f

            canvas.save()
            canvas.translate(craftX, craftY)
            canvas.rotate(rollOffset * 40f)

            val craftSize = (width * 0.06f + (bassKick * 0.08f)).coerceIn(24f, 75f)

            // Neon thruster flame
            paint.style = Paint.Style.FILL
            paint.color = if (strobeFlash > 0.05f) Color.WHITE else Color.parseColor("#38BDF8")
            paint.alpha = (160 + (smoothedBass * 0.8f).toInt() + (strobeFlash * 90f).toInt()).coerceIn(100, 255)
            canvas.drawCircle(0f, craftSize * 0.7f, craftSize * 0.32f + (strobeFlash * 8f), paint)

            // Sleek vector craft body
            paint.color = Color.parseColor("#090D16")
            paint.alpha = 245
            val craftPath = Path()
            craftPath.moveTo(0f, -craftSize)
            craftPath.lineTo(craftSize * 0.75f, craftSize * 0.65f)
            craftPath.lineTo(0f, craftSize * 0.40f)
            craftPath.lineTo(-craftSize * 0.75f, craftSize * 0.65f)
            craftPath.close()
            canvas.drawPath(craftPath, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            paint.color = if (strobeFlash > 0.05f) Color.WHITE else Color.parseColor("#F8FAFC")
            paint.alpha = 255
            canvas.drawPath(craftPath, paint)

            // Center Model Glyph (✧)
            logoPaint.color = if (strobeFlash > 0.05f) Color.parseColor("#38BDF8") else Color.WHITE
            logoPaint.alpha = 255
            logoPaint.textSize = craftSize * 0.95f
            val cOff = (logoPaint.descent() + logoPaint.ascent()) / 2f
            canvas.drawText("✧", 0f, -cOff - (craftSize * 0.12f), logoPaint)

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
