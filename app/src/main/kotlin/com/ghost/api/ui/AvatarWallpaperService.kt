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
        private val nodeMagnitudes = FloatArray(30) // Per-hexagon smoothed FFT activation energies
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

                    // Kinetic clocks: very gentle idle speed, accelerating on live audio beats
                    animTime += 0.016f
                    val bassImpulse = (smoothedBass / 100f).coerceIn(0f, 1f)
                    tunnelPhase = (tunnelPhase + 0.0022f + (bassImpulse * 0.018f)) % 1.0f
                    trackZScroll = (trackZScroll + 0.008f + (bassImpulse * 0.028f)) % 1.0f

                    // Exponential strobe decay (sharp beat onset, smooth falloff)
                    strobeFlash = (strobeFlash * 0.84f).coerceAtLeast(0f)

                    // Hexagon node FFT energy decay: quick snap on onset, smooth release so nodes don't get stuck blown up
                    for (i in nodeMagnitudes.indices) {
                        nodeMagnitudes[i] = (nodeMagnitudes[i] * 0.82f).coerceAtLeast(0f)
                    }

                    // Space Bug lane hopping physics (Option D)
                    if (bugHopProgress < 1f) {
                        bugHopProgress = (bugHopProgress + 0.06f).coerceAtMost(1f)
                        if (bugHopProgress >= 1f) {
                            bugLane = bugTargetLane
                        }
                    } else if (smoothedBass > 75f && System.currentTimeMillis() - lastBeatHopTime > 450L) {
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

        // Beat detection: rolling energy tracking + temporal refractory debounce
        private var rollingBassEnergy = 30f
        private var lastBeatTimestamp = 0L

        override fun onAudioData(waveform: ByteArray, fft: ByteArray, intensity: Float, bass: Float) {
            currentFft = fft
            val now = System.currentTimeMillis()
            
            // Dynamic threshold: 1.32x rolling average bass with minimum floor of 45
            val beatThreshold = (rollingBassEnergy * 1.32f).coerceAtLeast(45f)
            if (bass > beatThreshold && (now - lastBeatTimestamp > 220L)) {
                strobeFlash = 1f
                lastBeatTimestamp = now
            }
            
            // Update rolling bass average with asymmetric tracking (slow rise, gentle fall)
            rollingBassEnergy = rollingBassEnergy * 0.92f + bass * 0.08f
            smoothedIntensity = smoothedIntensity * 0.7f + intensity * 0.3f
            smoothedBass = smoothedBass * 0.7f + bass * 0.3f

            // Sample FFT frequency bins for the 30 spiral hexagon nodes
            if (fft.isNotEmpty() && fft.size >= 4) {
                val maxBin = (fft.size / 2) - 1
                for (n in nodeMagnitudes.indices) {
                    val binIndex = ((n * 3) + 1).coerceIn(0, maxBin)
                    val real = fft[binIndex * 2].toDouble()
                    val imag = fft[binIndex * 2 + 1].toDouble()
                    val rawMag = Math.hypot(real, imag).toFloat().coerceIn(0f, 90f)
                    // Fast attack if new magnitude is higher, otherwise let frame loop smoothly decay
                    if (rawMag > nodeMagnitudes[n]) {
                        nodeMagnitudes[n] = rawMag
                    }
                }
            }
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
                    
                    // Nudged slightly left to perfectly center mathematically on screen (-15f)
                    val cx = width / 2f - 15f + rollOffset * 150f
                    // Nudged slightly up to align with the widget/input bar center
                    val cy = height / 2f - 75f + pitchOffset * 150f
                    
                    canvas.drawColor(Color.parseColor("#0A0A0A"))
                    
                    rotationAngle += 0.2f + (smoothedBass / 100f)
                    
                    val isNoisy = smoothedBass > 100f || smoothedIntensity > 80f
                    
                    val preset = prefs.getString(Constants.PREF_VISUALIZER_PRESET, "OPTION_A") ?: "OPTION_A"
                    
                    when (preset) {
                        "OPTION_B", "SUDA" -> {
                            drawOptionBHexLattice(canvas, cx + 12f, cy + 40f, dynamicBaseRadius, width, height)
                        }
                        "OPTION_C" -> {
                            drawOptionCDeltaTunnel(canvas, cx + 12f, cy + 40f, dynamicBaseRadius)
                        }
                        "OPTION_D", "AUDIOSURF" -> {
                            drawOptionDNeonSunset(canvas, cx, cy, width, height)
                        }
                        else -> {
                            drawOptionAOrbitalStar(canvas, cx, cy, dynamicBaseRadius, height, isNoisy)
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
         * Enhanced with dynamic booming bass expansion, ambient grounding pool at the bottom app dock,
         * and subtle idle harmonic breathing.
         */
        private fun drawOptionAOrbitalStar(canvas: Canvas, cx: Float, cy: Float, dynamicBaseRadius: Float, height: Float, isNoisy: Boolean) {
            val bassBoost = smoothedBass * 3.2f // Booming expansion on audio beats!
            val idleBreath = sin(rotationAngle * 0.4f) * 25f

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
         * Option B: Hex Lattice & Stage Lighting (Sacred Geometry / Suda)
         * Hollow cyber-hex chamber with centered model unicode glyph (✧),
         * vertex-aligned concentric hex halos, Beat Saber theatrical stage spotlight trapezoids
         * (flash photography effect illuminating the space), and spiral arms.
         */
        private fun drawOptionBHexLattice(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float, width: Float, height: Float) {
            val bassKick = (smoothedBass * 1.5f).coerceAtLeast(0f)
            val coreRadius = (baseRadius * 0.95f + bassKick * 0.6f).coerceIn(45f, 240f)

            // 1. Perspective Hexagonal Cyber Hallway / Corridor in the Environment (Behind Avatar)
            // Static to the environment/screen (not coupled to avatar's spin), using stolen wallpaper color palette
            val hallwayFlashAlpha = if (strobeFlash > 0.04f) {
                (strobeFlash * 255f).toInt().coerceIn(0, 255)
            } else {
                0
            }

            val nearR = coreRadius * 1.05f
            val farR = maxOf(width, height) * 0.85f
            val wallColor = if (isCustomPaletteActive) currentColors[0] else Color.parseColor("#38BDF8")

            if (hallwayFlashAlpha > 0) {
                canvas.save()
                canvas.translate(cx, cy)
                paint.clearShadowLayer()

                for (v in 0 until 6) {
                    val a1 = (v * Math.PI / 3.0).toFloat()
                    val a2 = ((v + 1) * Math.PI / 3.0).toFloat()

                    val n1x = nearR * cos(a1)
                    val n1y = nearR * sin(a1)
                    val n2x = nearR * cos(a2)
                    val n2y = nearR * sin(a2)

                    val f1x = farR * cos(a1)
                    val f1y = farR * sin(a1)
                    val f2x = farR * cos(a2)
                    val f2y = farR * sin(a2)

                    // Perspective wall quad/trapezoid
                    val wallPath = Path().apply {
                        moveTo(n1x, n1y)
                        lineTo(f1x, f1y)
                        lineTo(f2x, f2y)
                        lineTo(n2x, n2y)
                        close()
                    }

                    // Alternating subtle wall facet shading using stolen palette color
                    val facetFactor = if (v % 2 == 0) 1.0f else 0.65f
                    paint.style = Paint.Style.FILL
                    paint.color = wallColor
                    paint.alpha = (hallwayFlashAlpha * 0.22f * facetFactor).toInt().coerceIn(0, 75)
                    canvas.drawPath(wallPath, paint)

                    // Crisp architectural perspective corner guide lines in stolen palette accent (solid lines grabbing stolen color)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.5f + (strobeFlash * 2.5f)
                    paint.color = wallColor
                    paint.alpha = (hallwayFlashAlpha * 0.90f).toInt().coerceIn(0, 240)
                    canvas.drawLine(n1x, n1y, f1x, f1y, paint)
                }

                // Distant static outer corridor boundary ring
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.color = wallColor
                paint.alpha = (hallwayFlashAlpha * 0.65f).toInt().coerceIn(0, 180)
                drawHexagon(canvas, 0f, 0f, farR * 0.75f, paint)

                canvas.restore()
            }

            canvas.save()
            canvas.translate(cx, cy)
            
            // Subtle slow rotational drift for the floating outer spirals and halos
            canvas.rotate(rotationAngle * 0.35f)

            paint.clearShadowLayer()

            // 2. Concentric Vertex-Aligned Hexagonal Halos (Multi-Pass Bloom for Geometry)
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

            // High-energy strobe halo on prominent outer hex during beat flash
            if (strobeFlash > 0.10f) {
                paint.strokeWidth = 5f
                paint.color = wallColor
                paint.alpha = (strobeFlash * 255f).toInt().coerceIn(0, 255)
                drawHexagon(canvas, 0f, 0f, coreRadius * 2.8f + bassKick * 1.5f, paint)
            }

            // 3. Six Logarithmic Spiral Hex Arms (Mixture of Experts / Neural Parameter Activation)
            // Each individual hexagon is wired to a distinct audio FFT frequency bin,
            // so individual weights/nodes "pop" and flash independently on harmonics, snares, and basslines!
            val numArms = 6
            val hexesPerArm = 5
            val spiralTwist = 0.22f
            val fftAvailable = currentFft.isNotEmpty() && currentFft.size >= 4
            val maxBin = if (fftAvailable) (currentFft.size / 2) - 1 else 1

            for (arm in 0 until numArms) {
                val baseAngle = (arm * Math.PI * 2.0 / numArms).toFloat()

                for (step in 1..hexesPerArm) {
                    val progress = step.toFloat() / hexesPerArm.toFloat()

                    // Sample from smoothly decaying per-node FFT magnitude
                    val nodeIndex = (arm * hexesPerArm + (step - 1)).coerceIn(0, nodeMagnitudes.size - 1)
                    val nodeMag = nodeMagnitudes[nodeIndex]

                    // Subtle, crisp activation pop (capped at max 9px so it never blows up out of proportion)
                    val activationPop = (nodeMag * 0.12f).coerceIn(0f, 9f)
                    val distance = coreRadius * 1.35f + (step * (42f + bassKick * 0.35f)) + (activationPop * 0.3f)
                    val angle = baseAngle + (step * spiralTwist) + (smoothedIntensity * 0.003f)

                    val hx = (cos(angle) * distance).toFloat()
                    val hy = (sin(angle) * distance).toFloat()

                    // Controlled size scaling: stays small and proportional to the constellation
                    val baseHexSize = coreRadius * 0.28f * (1.0f - progress * 0.50f)
                    val hexSize = (baseHexSize + activationPop).coerceIn(6f, coreRadius * 0.42f)

                    val colorIdx = (arm + step) % currentColors.size
                    val baseArmColor = if (isCustomPaletteActive) currentColors[colorIdx] else {
                        when (step) {
                            1 -> Color.parseColor("#F1F5F9")
                            2 -> Color.parseColor("#93C5FD")
                            3 -> Color.parseColor("#38BDF8")
                            4 -> Color.parseColor("#818CF8")
                            else -> Color.parseColor("#A78BFA")
                        }
                    }

                    // Active nodes flash brighter/whiter when their frequency slice hits
                    val armColor = if (nodeMag > 22f) {
                        val blendRatio = (nodeMag / 80f).coerceIn(0f, 0.70f)
                        ColorUtils.blendARGB(baseArmColor, Color.WHITE, blendRatio)
                    } else {
                        baseArmColor
                    }

                    val baseAlpha = ((1f - progress * 0.35f) * 210).toInt()
                    val nodeAlpha = (baseAlpha + (nodeMag * 1.2f).toInt()).coerceIn(40, 255)

                    paint.style = Paint.Style.FILL
                    paint.color = armColor
                    paint.alpha = nodeAlpha
                    drawHexagon(canvas, hx, hy, hexSize, paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.0f + (nodeMag * 0.02f)
                    paint.color = if (nodeMag > 30f) Color.WHITE else baseArmColor
                    paint.alpha = ((1f - progress * 0.5f) * 180 + (nodeMag * 1.0f)).toInt().coerceIn(30, 255)
                    drawHexagon(canvas, hx, hy, hexSize, paint)
                }
            }

            // Stepped Concentric Blooming Hexagons behind the Mother Hexagon (Layered Fake Bloom)
            // Mirrors the rich, vibrant layered fake bloom of Option A, grabbing the stolen wallpaper palette colors
            val hexBloomRadii = floatArrayOf(
                coreRadius * 2.05f + (bassKick * 0.95f),
                coreRadius * 1.68f + (bassKick * 0.65f),
                coreRadius * 1.35f + (bassKick * 0.40f),
                coreRadius * 1.12f + (bassKick * 0.18f)
            )
            val hexBloomAlphas = intArrayOf(28, 55, 95, 145)
            val hexBloomBorderWidths = floatArrayOf(5.5f, 4.2f, 3.2f, 2.5f)

            for (hb in hexBloomRadii.indices) {
                val swatchIndex = when (hb) {
                    3 -> 1 % currentColors.size // Vibrant / Inner
                    2 -> 0 % currentColors.size // Dominant
                    1 -> 2 % currentColors.size // Muted
                    else -> 3 % currentColors.size // Outer
                }
                val rawColor = if (isCustomPaletteActive) currentColors[swatchIndex] else colorCobaltGlow
                val hexGlowColor = ensureVisibleBloomColor(rawColor, colorCobaltGlow)

                // Translucent solid planar aura fill for radiant bloom volume
                paint.style = Paint.Style.FILL
                paint.color = hexGlowColor
                paint.alpha = (hexBloomAlphas[hb] * 0.42f).toInt().coerceIn(10, 80)
                drawHexagon(canvas, 0f, 0f, hexBloomRadii[hb], paint)

                // Rich neon boundary contour
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = hexBloomBorderWidths[hb]
                paint.color = hexGlowColor
                paint.alpha = hexBloomAlphas[hb]
                drawHexagon(canvas, 0f, 0f, hexBloomRadii[hb], paint)
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

            canvas.restore() // Restore unrotated frame for the central star glyph

            // 5. Model Unicode Glyph (✧) Centered, Crisp White & Unrotated
            // Isolated from avatar spin and without blurry star bloom text
            canvas.save()
            canvas.translate(cx, cy)
            logoPaint.clearShadowLayer()
            logoPaint.color = Color.WHITE
            logoPaint.alpha = 255
            logoPaint.textSize = coreRadius * 1.35f
            val off = (logoPaint.descent() + logoPaint.ascent()) / 2f
            canvas.drawText("✧", 0f, -off, logoPaint)
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

            // 1. Audio-Triggered Central Corner Laser Guide Rails
            // 3 Clean perspective lasers shooting through the vertices of the triangle tunnel into deep space
            // Strictly grabbing rich stolen palette colors instead of harsh cutting white
            if (strobeFlash > 0.04f) {
                val reach = (canvas.width + canvas.height) * 0.9f
                val strobeAlpha = (strobeFlash * 255f).toInt().coerceIn(0, 255)
                val railColor = if (isCustomPaletteActive) currentColors[0] else Color.parseColor("#38BDF8")

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.0f + (strobeFlash * 2.5f)
                paint.color = railColor
                paint.alpha = strobeAlpha
                for (v in 0..2) {
                    val railAngle = (v * Math.PI * 2.0 / 3.0 - Math.PI / 2.0).toFloat()
                    val rx = (cos(railAngle) * reach).toFloat()
                    val ry = (sin(railAngle) * reach).toFloat()
                    canvas.drawLine(0f, 0f, rx, ry, paint)
                }
            }

            // 2. Infinite Nested Equilateral Triangles Zooming Outward (Seven Nation Army Tunnel)
            // Enhanced with 3D tunnel parallax layering (outer triangles shift more with device tilt than inner core)
            for (i in 0 until numTriangles) {
                val p = ((tunnelPhase + (i.toFloat() / numTriangles)) % 1.0f)
                val scale = (baseSize * exp(p * 3.4f)).toFloat()

                // Layered tunnel parallax: outer foreground rings drift further than deep core
                val parallaxZ = p * p * 60f
                val triCenterX = rollOffset * parallaxZ
                val triCenterY = pitchOffset * parallaxZ

                val fadeIn = (p * 5f).coerceIn(0f, 1f)
                val fadeOut = ((1f - p) * 3f).coerceIn(0f, 1f)
                val totalAlpha = (fadeIn * fadeOut * 240f).toInt().coerceIn(0, 255)

                val colorIdx = i % currentColors.size
                val baseColor = if (isCustomPaletteActive) currentColors[colorIdx] else {
                    if (i % 2 == 0) Color.parseColor("#38BDF8") else Color.parseColor("#F1F5F9")
                }

                val rotation = (sin(p * Math.PI.toFloat()) * 0.15f) + (if (i % 2 == 1) Math.PI.toFloat() else 0f)

                // Crisp solid laser edges (clean infinite zoom without violent full-screen flash)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (3.5f * (1f - p * 0.4f)).coerceIn(1.5f, 5.5f)
                paint.color = baseColor
                paint.alpha = totalAlpha
                drawEquilateralTriangle(canvas, triCenterX, triCenterY, scale, rotation, paint)
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
                // Center slightly higher to optically sit in the centroid of the equilateral triangle
                canvas.drawText("✧", 0f, -off - (focalRadius * 0.08f), logoPaint)
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
            // Road and horizon remain completely solid and stable at screen center
            val roadCenterX = width * 0.5f
            // Upper sky elements (sun & sky) retain gentle look-around parallax
            val skyCenterX = width * 0.5f + (rollOffset * 100f) + (launcherSwipeOffset * 90f)
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

            // 2. Audio Lightning Crack Flashes across the Sky
            val skyFlashAlpha = if (strobeFlash > 0.04f) {
                (strobeFlash * 255f).toInt().coerceIn(0, 255)
            } else {
                0
            }

            if (skyFlashAlpha > 0) {
                // Jagged branching lightning bolts discharging from upper clouds
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f + (strobeFlash * 2.5f)
                paint.color = Color.WHITE
                paint.alpha = skyFlashAlpha

                // Lightning Bolt 1 (Left sky strike)
                val boltL = Path().apply {
                    moveTo(width * 0.22f, 0f)
                    lineTo(width * 0.26f, horizonY * 0.32f)
                    lineTo(width * 0.20f, horizonY * 0.55f)
                    lineTo(width * 0.29f, horizonY * 0.85f)
                }
                canvas.drawPath(boltL, paint)

                // Lightning Bolt 2 (Right sky strike)
                val boltR = Path().apply {
                    moveTo(width * 0.78f, 0f)
                    lineTo(width * 0.72f, horizonY * 0.28f)
                    lineTo(width * 0.80f, horizonY * 0.60f)
                    lineTo(width * 0.73f, horizonY * 0.90f)
                }
                canvas.drawPath(boltR, paint)
            }

            // 3. The Black Hole Sun with Hot Neon Concentric Solar Flares
            // Obsidian dark singularity center surrounded by explosive coronal flare rings
            val sunRadius = (min(width, height) * 0.28f + bassKick * 0.25f).coerceIn(80f, 300f)
            val sunCenterX = skyCenterX
            val sunCenterY = horizonY - (sunRadius * 0.18f)

            // Coronal solar flare rings expanding dramatically with bass beats
            val flareBoom = (bassKick * 0.75f) + (strobeFlash * 90f)
            val sunBloomMultipliers = floatArrayOf(2.2f, 1.75f, 1.4f, 1.15f)
            val sunBloomAlphas = intArrayOf(25, 50, 90, 150)
            val sunBloomColors = intArrayOf(
                Color.parseColor("#991B1B"), // 0: Deep crimson outer storm
                Color.parseColor("#DC2626"), // 1: Neon red flare
                Color.parseColor("#EA580C"), // 2: Blazing solar orange
                Color.parseColor("#FBBF24")  // 3: Electric gold inner rim
            )

            paint.style = Paint.Style.FILL
            paint.shader = null
            for (sb in sunBloomMultipliers.indices) {
                val r = (sunRadius * sunBloomMultipliers[sb]) + flareBoom
                paint.color = if (isCustomPaletteActive) currentColors[sb % currentColors.size] else sunBloomColors[sb]
                paint.alpha = ((sunBloomAlphas[sb] + (strobeFlash * 85f).toInt())).coerceIn(15, 255)
                canvas.drawCircle(sunCenterX, sunCenterY, r, paint)
            }

            // Black Hole Singularity Core (Crisp obsidian black interior)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#05050A")
            paint.alpha = 255
            canvas.drawCircle(sunCenterX, sunCenterY, sunRadius, paint)

            // Fiery Accretion Disk Outline around the black hole core
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f + (strobeFlash * 3f)
            paint.color = if (strobeFlash > 0.05f) Color.WHITE else Color.parseColor("#FBBF24")
            paint.alpha = 255
            canvas.drawCircle(sunCenterX, sunCenterY, sunRadius, paint)

            // 4. Horizon Neon Glow Line
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f + (strobeFlash * 4f)
            paint.color = if (strobeFlash > 0.05f) Color.WHITE else Color.parseColor("#38BDF8")
            paint.alpha = (160 + (strobeFlash * 95f).toInt()).coerceIn(120, 255)
            canvas.drawLine(0f, horizonY, width, horizonY, paint)

            // 5. Full-Screen 3D Perspective Terrain Mesh with Equalizer Mountain Ridges
            // Solid, grounded road grid with stationary Y rows and stable road center
            val numRows = 22
            val numCols = 16 // -8 to +8 columns
            val gridGroundBottom = height * 1.05f

            val bassFFT = smoothedBass * 0.8f
            val midFFT = smoothedIntensity * 0.7f

            val meshX = Array(numRows + 1) { FloatArray(numCols + 1) }
            val meshY = Array(numRows + 1) { FloatArray(numCols + 1) }

            for (r in 0..numRows) {
                // Fixed row perspective depth: Y is completely static and NEVER hops or bobs
                val depthZ = (r.toFloat() / numRows.toFloat()).let { it * it }
                val baseRowY = horizonY + (gridGroundBottom - horizonY) * depthZ
                val rowHalfWidth = (width * 0.65f) * (depthZ + 0.05f) * 2.6f

                for (c in 0..numCols) {
                    val colNorm = (c.toFloat() / numCols.toFloat()) * 2f - 1f // -1.0 to +1.0
                    val colIndexFromCenter = (c - numCols / 2) // negative is left, positive is right
                    val absCol = abs(colIndexFromCenter)

                    val rawX = roadCenterX + (colNorm * rowHalfWidth)

                    // Equalizer Mountain Elevation on outer flanks (|absCol| >= 3)
                    var elevation = 0f
                    if (absCol >= 3 && depthZ > 0.01f) {
                        val flankFactor = (absCol - 2).toFloat()
                        val peakBase = flankFactor * (12f + (22f * depthZ))
                        val tooth = if (c % 2 == 0) 1.3f else 0.8f
                        val audioDeform = if (colIndexFromCenter < 0) {
                            bassFFT * (flankFactor * 0.18f)
                        } else {
                            midFFT * (flankFactor * 0.18f)
                        }
                        elevation = ((peakBase * tooth) + audioDeform) * depthZ.coerceIn(0f, 1f)
                    }

                    meshX[r][c] = rawX
                    // Road center (highway lanes) stays 100% planar at baseRowY
                    meshY[r][c] = if (absCol <= 2) baseRowY else (baseRowY - elevation).coerceAtLeast(horizonY)
                }
            }

            // A) 100% Solid Opaque Ground Floor (Completely occludes the sun and sky below the horizon line)
            paint.style = Paint.Style.FILL
            paint.shader = null
            val baseGroundColor = if (strobeFlash > 0.05f) Color.parseColor("#150B24") else Color.parseColor("#080512")
            paint.color = baseGroundColor
            paint.alpha = 255
            canvas.drawRect(0f, horizonY, width, height, paint)

            // B) Center Illuminated Highway Bed
            val highwayBedPath = Path()
            val centerColL = numCols / 2 - 1
            val centerColR = numCols / 2 + 1
            highwayBedPath.moveTo(meshX[0][centerColL], meshY[0][centerColL])
            highwayBedPath.lineTo(meshX[0][centerColR], meshY[0][centerColR])
            highwayBedPath.lineTo(meshX[numRows][centerColR], meshY[numRows][centerColR])
            highwayBedPath.lineTo(meshX[numRows][centerColL], meshY[numRows][centerColL])
            highwayBedPath.close()

            paint.color = if (strobeFlash > 0.05f) Color.parseColor("#1E3A5F") else Color.parseColor("#0E1F38")
            paint.alpha = (90 + (smoothedBass * 0.4f).toInt() + (strobeFlash * 50f).toInt()).coerceIn(60, 200)
            canvas.drawPath(highwayBedPath, paint)

            // Draw Wireframe Terrain Mesh:
            val terrainColor = if (strobeFlash > 0.05f) {
                ColorUtils.blendARGB(Color.parseColor("#E11D48"), Color.WHITE, strobeFlash)
            } else if (isCustomPaletteActive) {
                currentColors[0]
            } else {
                Color.parseColor("#C026D3") // Vibrant synthwave magenta
            }

            paint.style = Paint.Style.STROKE

            // C) Scrolling Horizontal Rung Lines (traveling down the road towards the screen)
            val scrollFrac = trackZScroll % 1f
            for (r in 0..numRows) {
                val p = ((r.toFloat() + scrollFrac) / numRows.toFloat()).coerceIn(0f, 1f)
                val depthZ = p * p
                val rungY = horizonY + (gridGroundBottom - horizonY) * depthZ
                val rungHalfWidth = (width * 0.65f) * (depthZ + 0.05f) * 2.6f
                paint.strokeWidth = (1.2f + (depthZ * 2.8f)).coerceIn(1f, 4.5f)
                val alphaBase = (depthZ * 170f + (strobeFlash * 80f)).toInt().coerceIn(20, 255)
                paint.color = terrainColor
                paint.alpha = alphaBase

                canvas.drawLine(roadCenterX - rungHalfWidth, rungY, roadCenterX + rungHalfWidth, rungY, paint)
            }

            // D) Longitudinal Lines (connecting depth rows from horizon to foreground)
            for (c in 0..numCols) {
                val colIndexFromCenter = (c - numCols / 2)
                val absCol = abs(colIndexFromCenter)
                val isHighway = absCol <= 2
                val isHighwayEdge = absCol == 2
                val colColor = if (isHighwayEdge) {
                    if (strobeFlash > 0.05f) Color.WHITE else Color.parseColor("#38BDF8") // Glowing cyan boundary rails
                } else if (isHighway) {
                    Color.parseColor("#67E8F9") // Light sky cyan road rungs
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

                    // Diagonal wireframe mountain braces on flanks
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

            // 6. Gemma Jet Craft with Direct Horizontal Tilt Steering
            // Craft height (Y) is 100% constant on the screen.
            // When phone tilts, the craft slides left and right across the road lanes, banking its chassis!
            val craftRow = (numRows * 0.82f).toInt().coerceIn(0, numRows)
            val centerCol = numCols / 2

            val leftRoadEdgeX = meshX[craftRow][centerCol - 2]
            val centerRoadX = meshX[craftRow][centerCol]
            val rightRoadEdgeX = meshX[craftRow][centerCol + 2]
            val roadHalfSpan = (rightRoadEdgeX - centerRoadX) * 0.85f

            // Phone tilt directly steers the craft left/right across the road span
            // Normal held orientation tilt range: rollOffset clamped within road width
            val tiltSteerFactor = (rollOffset * 2.5f).coerceIn(-1.0f, 1.0f)
            val craftX = centerRoadX + (tiltSteerFactor * roadHalfSpan)

            // Craft height is strictly static — absolutely zero vertical hopping or jumping
            val craftY = meshY[craftRow][centerCol]

            // Dynamic banking lean: vehicle rolls into the tilt steering angle
            val chassisBankAngle = tiltSteerFactor * 32f

            canvas.save()
            canvas.translate(craftX, craftY)
            canvas.rotate(chassisBankAngle)

            val craftSize = (width * 0.062f).coerceIn(26f, 75f)

            // Trailing Jet Thruster Plasma Bubbles (Stream of glowing propulsion bubbles trailing behind the engines)
            val bubbleColor = if (isCustomPaletteActive) currentColors[0] else Color.parseColor("#38BDF8")
            val numBubbles = 4
            paint.style = Paint.Style.FILL
            for (b in 1..numBubbles) {
                val bProgress = b.toFloat() / numBubbles.toFloat()
                val bubbleY = craftSize * (0.55f + bProgress * 0.95f)
                val bubbleRadius = craftSize * (0.24f * (1.15f - bProgress * 0.55f)) + (bassKick * 0.02f)
                // Slight dynamic wake turbulence wobble
                val bubbleX = sin(animTime * 12f + b * 1.8f) * (bProgress * 4.5f)
                val bubbleAlpha = ((1f - bProgress * 0.70f) * (180f + bassKick * 15f)).toInt().coerceIn(20, 240)

                paint.color = bubbleColor
                paint.alpha = bubbleAlpha
                canvas.drawCircle(bubbleX, bubbleY, bubbleRadius, paint)

                // White energetic core inside the nearest primary bubble
                if (b == 1) {
                    paint.color = Color.WHITE
                    paint.alpha = 220
                    canvas.drawCircle(0f, bubbleY * 0.92f, bubbleRadius * 0.45f, paint)
                }
            }

            // Sleek vector jet craft body (Solid dark obsidian fuselage)
            paint.color = Color.parseColor("#090D16")
            paint.alpha = 250
            val craftPath = Path().apply {
                moveTo(0f, -craftSize)
                lineTo(craftSize * 0.75f, craftSize * 0.60f)
                lineTo(0f, craftSize * 0.38f)
                lineTo(-craftSize * 0.75f, craftSize * 0.60f)
                close()
            }
            canvas.drawPath(craftPath, paint)

            // Sharp White Craft Outline
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            paint.color = Color.WHITE
            paint.alpha = 255
            canvas.drawPath(craftPath, paint)

            // Center Model Glyph (✧)
            logoPaint.color = Color.WHITE
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
