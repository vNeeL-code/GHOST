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
            
            smoothedBass = smoothedBass * 0.7f + bass * 0.3f

            // High/Mid/Treble Audio Reactive Constellation (Equalized Logarithmic FFT Bands):
            // FFT format: pairs of [real, imag] bytes (signed -128..127).
            // Android capture size is typically 1024 or 512, meaning 256 to 512 complex bins.
            // In real audio, natural energy falls off by ~6dB/octave (pink noise distribution),
            // so raw high bins only reach 5-15 while bass reaches 80-120.
            // To make individual hexagons and cubes pop vibrantly across the ENTIRE frequency spectrum
            // (kicks, snares, vocals, synths, hi-hats, and cymbals), we:
            // 1. Assign each of the 30 nodes to a distinct logarithmic frequency band across the spectrum.
            // 2. Apply frequency equalization (boost high/mid bins by up to 3.5x).
            // 3. Subtract a running baseline so continuous background tones don't keep nodes lit,
            //    making transients (notes, hits, vocal syllables) pop viscerally!
            if (fft.isNotEmpty() && fft.size >= 16) {
                val totalBins = (fft.size / 2) - 1
                for (n in nodeMagnitudes.indices) {
                    // Logarithmic distribution across bins 2 to (totalBins - 2)
                    // Low nodes (0..5): upper bass / kicks (bins 2..8)
                    // Mid nodes (6..18): snares, guitars, vocals, synths (bins 9..45)
                    // High nodes (19..29): hi-hats, percussions, air, cymbals (bins 46..totalBins)
                    val frac = (n + 1).toFloat() / 30f
                    // Exponent 2.2 gives natural musical octave spacing
                    val binIndex = (2 + (Math.pow(frac.toDouble(), 2.2) * (totalBins - 4)).toInt()).coerceIn(2, totalBins)
                    
                    val real = fft[binIndex * 2].toDouble()
                    val imag = fft[binIndex * 2 + 1].toDouble()
                    val rawMag = Math.hypot(real, imag).toFloat()

                    // Equalization gain: higher frequencies get boosted to match perceived loudness of bass
                    val eqGain = 1.0f + (frac * 2.8f) // 1.0x at bass up to 3.8x at treble
                    val normalizedMag = (rawMag * eqGain).coerceIn(0f, 100f)

                    // Fast attack on musical transients, decaying naturally in frameCallback
                    if (normalizedMag > nodeMagnitudes[n]) {
                        nodeMagnitudes[n] = normalizedMag
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
                        "OPTION_D", "AUDIOSURF", "MATRIX" -> {
                            drawOptionDCubeLattice(canvas, cx + 12f, cy + 40f, dynamicBaseRadius, width, height)
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

            // 3. Stepped Concentric Blooming Hexagons behind Mother Hexagon (Layered Fake Bloom)
            // Mirrors the rich, vibrant layered fake bloom of Option A, grabbing the stolen wallpaper palette colors
            // Booms outward dramatically on bass hits so the center is the primary reactor!
            val hexBloomRadii = floatArrayOf(
                coreRadius * 2.50f + (bassKick * 1.50f),
                coreRadius * 1.95f + (bassKick * 1.10f),
                coreRadius * 1.50f + (bassKick * 0.70f),
                coreRadius * 1.20f + (bassKick * 0.35f)
            )
            val hexBloomAlphas = intArrayOf(35, 65, 110, 160)
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
                paint.alpha = (hexBloomAlphas[hb] * 0.45f).toInt().coerceIn(15, 90)
                drawHexagon(canvas, 0f, 0f, hexBloomRadii[hb], paint)

                // Rich neon boundary contour
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = hexBloomBorderWidths[hb]
                paint.color = hexGlowColor
                paint.alpha = hexBloomAlphas[hb]
                drawHexagon(canvas, 0f, 0f, hexBloomRadii[hb], paint)
            }

            // 4. Six Logarithmic Spiral Hex Arms (Mixture of Experts / Neural Parameter Activation)
            // Drawn in the foreground with controlled activation pops so they never drown out the center mother hex!
            val numArms = 6
            val hexesPerArm = 5
            val spiralTwist = 0.22f

            for (arm in 0 until numArms) {
                val baseAngle = (arm * Math.PI * 2.0 / numArms).toFloat()

                for (step in 1..hexesPerArm) {
                    val progress = step.toFloat() / hexesPerArm.toFloat()

                    // Interleaved spectral sampling: Each arm samples across the entire frequency range!
                    // Arm 0 gets [0, 6, 12, 18, 24], Arm 1 gets [1, 7, 13, 19, 25], etc.
                    // This distributes kicks, snares, guitars, vocals, synths, and hi-hats across ALL 6 arms!
                    val nodeIndex = ((step - 1) * numArms + arm).coerceIn(0, nodeMagnitudes.size - 1)
                    val nodeMag = nodeMagnitudes[nodeIndex]

                    // Dynamic activation pop: scales from +0 to +11px when frequencies hit!
                    // Inner ring (step 1) uses a 0.60x dampener so it doesn't collide with the mother hex,
                    // but still pops noticeably on hits!
                    val innerDampener = if (step == 1) 0.60f else 1.0f
                    val activationPop = (nodeMag * 0.16f * innerDampener).coerceIn(0f, 11f)
                    val distance = coreRadius * 1.35f + (step * (42f + bassKick * 0.30f)) + (activationPop * 0.4f)
                    val angle = baseAngle + (step * spiralTwist) + (smoothedIntensity * 0.003f)

                    val hx = (cos(angle) * distance).toFloat()
                    val hy = (sin(angle) * distance).toFloat()

                    // Controlled size scaling: stays proportional and pops visibly with musical notes
                    val baseHexSize = coreRadius * 0.22f * (1.0f - progress * 0.45f)
                    val hexSize = (baseHexSize + activationPop).coerceIn(6f, coreRadius * 0.45f)

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

                    // Active nodes flash brighter toward white when their frequency slice hits (dynamic flash trigger at 16f)
                    val armColor = if (nodeMag > 16f) {
                        val blendRatio = (nodeMag / 65f).coerceIn(0f, 0.85f)
                        ColorUtils.blendARGB(baseArmColor, Color.WHITE, blendRatio)
                    } else {
                        baseArmColor
                    }

                    val baseAlpha = ((1f - progress * 0.35f) * 200).toInt()
                    val nodeAlpha = (baseAlpha + (nodeMag * 1.5f).toInt()).coerceIn(50, 255)

                    paint.style = Paint.Style.FILL
                    paint.color = armColor
                    paint.alpha = nodeAlpha
                    drawHexagon(canvas, hx, hy, hexSize, paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.2f + (nodeMag * 0.03f)
                    paint.color = if (nodeMag > 22f) Color.WHITE else baseArmColor
                    paint.alpha = ((1f - progress * 0.5f) * 180 + (nodeMag * 1.2f)).toInt().coerceIn(40, 255)
                    drawHexagon(canvas, hx, hy, hexSize, paint)
                }
            }

            // 5. Mother Hexagon (Hollow Obsidian Cyber Chamber)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#060A10")
            paint.alpha = 240
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

            // 6. Model Unicode Glyph (✧) Centered, Crisp White & Unrotated
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
         * Option D: Cyber Matrix / Cube Lattice (Salvation of the Black Sun)
         * Inspired by sacred cube geometry and the overlay controller layout:
         * - Majestic Black Hole Sun singularity at center with explosive solar flare rings
         * - Faint orbital halo ring encircling the singularity
         * - Centered Mother Diamond/Cube with 3 stepped fake bloom layers & white unicode ✧ glyph
         * - 6 Orbiting Satellite Cubes / Diamonds (Mixture of Experts) that pop and flash
         *   independently to discrete live audio FFT bands with subtle parallax drift
         */
        private fun drawOptionDCubeLattice(canvas: Canvas, cx: Float, cy: Float, dynamicBaseRadius: Float, width: Float, height: Float) {
            val bassKick = (smoothedBass * 1.6f).coerceAtLeast(0f)
            val coreCubeRadius = (dynamicBaseRadius * 1.85f + bassKick * 0.45f).coerceIn(40f, 200f)

            // 1. Center Singularities: Parallax-anchored to screen / widget center
            val sunRadius = (min(width, height) * 0.22f + bassKick * 0.25f).coerceIn(60f, 240f)
            val flareBoom = (bassKick * 0.75f) + (strobeFlash * 80f)

            // Deep Space Black Sun with Hot Concentric Solar Flares (Salvation of the Sun)
            val sunBloomMultipliers = floatArrayOf(2.2f, 1.75f, 1.4f, 1.15f)
            val sunBloomAlphas = intArrayOf(25, 45, 80, 140)
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
                val flareColor = if (isCustomPaletteActive) currentColors[sb % currentColors.size] else sunBloomColors[sb]
                paint.color = ensureVisibleBloomColor(flareColor, colorCobaltGlow)
                paint.alpha = ((sunBloomAlphas[sb] + (strobeFlash * 75f).toInt())).coerceIn(15, 240)
                canvas.drawCircle(cx, cy, r, paint)
            }

            // Black Hole Singularity Core (Deep obsidian dark void)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#05050A")
            paint.alpha = 255
            canvas.drawCircle(cx, cy, sunRadius, paint)

            // Fiery Accretion Rim
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f + (strobeFlash * 3f)
            paint.color = if (strobeFlash > 0.05f) Color.WHITE else Color.parseColor("#FBBF24")
            paint.alpha = 240
            canvas.drawCircle(cx, cy, sunRadius, paint)

            // 2. Orbital Halo Enclosing Circle (matching user diagram around mother cube)
            val orbitCircleRadius = sunRadius * 1.08f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f + (strobeFlash * 2.0f)
            paint.color = if (isCustomPaletteActive) currentColors[1 % currentColors.size] else Color.parseColor("#E0F2FE")
            paint.alpha = (140 + (strobeFlash * 100f).toInt()).coerceIn(100, 255)
            canvas.drawCircle(cx, cy, orbitCircleRadius, paint)

            // 3. Central Mother Cube / Diamond (45° diamond inside orbital ring)
            canvas.save()
            canvas.translate(cx, cy)

            // 4-Layer Stepped Concentric Fake Bloom behind Central Cube
            // Booms massively on bass kicks and extends far beyond the central pill,
            // matching the radiant bloom presence of Option A
            val cubeBloomRadii = floatArrayOf(
                coreCubeRadius * 2.40f + (bassKick * 1.30f),
                coreCubeRadius * 1.85f + (bassKick * 0.90f),
                coreCubeRadius * 1.45f + (bassKick * 0.55f),
                coreCubeRadius * 1.18f + (bassKick * 0.25f)
            )
            val cubeBloomAlphas = intArrayOf(35, 70, 115, 160)
            val cubeBloomWidths = floatArrayOf(5.5f, 4.2f, 3.2f, 2.2f)

            for (cb in cubeBloomRadii.indices) {
                val swatchIndex = when (cb) {
                    3 -> 1 % currentColors.size // Vibrant / Inner
                    2 -> 0 % currentColors.size // Dominant
                    1 -> 2 % currentColors.size // Muted
                    else -> 3 % currentColors.size // Outer
                }
                val rawColor = if (isCustomPaletteActive) currentColors[swatchIndex] else colorCobaltGlow
                val bloomColor = ensureVisibleBloomColor(rawColor, colorCobaltGlow)

                // Translucent fill for planar glow volume
                paint.style = Paint.Style.FILL
                paint.color = bloomColor
                paint.alpha = (cubeBloomAlphas[cb] * 0.45f).toInt().coerceIn(15, 95)
                drawDiamond(canvas, 0f, 0f, cubeBloomRadii[cb], paint)

                // Crisp border contour
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = cubeBloomWidths[cb]
                paint.color = bloomColor
                paint.alpha = cubeBloomAlphas[cb]
                drawDiamond(canvas, 0f, 0f, cubeBloomRadii[cb], paint)
            }

            // Solid Obsidian Cyber Chamber for Central Cube
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#060A10")
            paint.alpha = 240
            drawDiamond(canvas, 0f, 0f, coreCubeRadius, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f + (strobeFlash * 2.5f)
            paint.color = if (strobeFlash > 0.05f) Color.WHITE else Color.parseColor("#F8FAFC")
            paint.alpha = 255
            drawDiamond(canvas, 0f, 0f, coreCubeRadius, paint)

            paint.strokeWidth = 2.5f
            paint.color = if (isCustomPaletteActive) currentColors[0] else Color.parseColor("#38BDF8")
            paint.alpha = 200
            drawDiamond(canvas, 0f, 0f, coreCubeRadius * 0.82f, paint)

            // Centered crisp white ✧ star glyph
            logoPaint.clearShadowLayer()
            logoPaint.color = Color.WHITE
            logoPaint.alpha = 255
            logoPaint.textSize = coreCubeRadius * 1.35f
            val cOff = (logoPaint.descent() + logoPaint.ascent()) / 2f
            canvas.drawText("✧", 0f, -cOff, logoPaint)

            canvas.restore()

            // 4. Six Satellite Diamonds / Cubes (Aligned Exactly with Overlay Nodes)
            // Blueprint mapping:
            // 0: Top Apex (Orange Tape Reel Launcher) -> (0, -apex)
            // 1: Top-Right (Blue Search)               -> (+dx, -dy)
            // 2: Bottom-Right (Yellow Tools)          -> (+dx, +dy)
            // 3: Bottom Apex (Cyan 4-Way Puck)         -> (0, +apex)
            // 4: Bottom-Left (Green Diary)            -> (-dx, +dy)
            // 5: Top-Left (Red Camera)                -> (-dx, -dy)
            val numSatellites = 6
            val density = canvas.density.toFloat().let { if (it > 0f) it / 160f else width / 360f }
            val isLandscape = width > height

            val horizontalSpread = if (isLandscape) 1.55f else 1.0f
            val nodeDx = 90f * density * horizontalSpread
            val nodeDy = (if (isLandscape) 56f else 105f) * density
            val nodeApex = (if (isLandscape) 102f else 182f) * density

            val satelliteOffsets = arrayOf(
                Pair(0f, -nodeApex),           // 0: Top Apex
                Pair(nodeDx, -nodeDy),         // 1: Top-Right
                Pair(nodeDx, nodeDy),          // 2: Bottom-Right
                Pair(0f, nodeApex),            // 3: Bottom Apex
                Pair(-nodeDx, nodeDy),         // 4: Bottom-Left
                Pair(-nodeDx, -nodeDy)         // 5: Top-Left
            )

            val satelliteBaseSize = 24f * density // matches 48dp button radius

            for (s in 0 until numSatellites) {
                val (offsetX, offsetY) = satelliteOffsets[s]
                val satX = cx + offsetX
                val satY = cy + offsetY

                // Each satellite samples distinct FFT bins across the spectrum:
                // s=0 (Top Apex): node 1 (upper bass / kicks)
                // s=1 (Top-Right): node 6 (mid-low rhythm)
                // s=2 (Bottom-Right): node 12 (vocals / lead synths)
                // s=3 (Bottom Apex): node 18 (snare / clap transient)
                // s=4 (Bottom-Left): node 23 (hi-hats / percussion)
                // s=5 (Top-Left): node 28 (high cymbals / air)
                val nodeIdx = when (s) {
                    0 -> 1
                    1 -> 6
                    2 -> 12
                    3 -> 18
                    4 -> 23
                    else -> 28
                }.coerceIn(0, nodeMagnitudes.size - 1)
                val satMag = nodeMagnitudes[nodeIdx]
                val satPop = (satMag * 0.16f).coerceIn(0f, 10f)

                val satSize = satelliteBaseSize + satPop

                // Distinct stolen palette color per satellite
                val colorIdx = s % currentColors.size
                val rawSatColor = if (isCustomPaletteActive) currentColors[colorIdx] else {
                    when (s) {
                        0 -> Color.parseColor("#F97316") // Orange Apex
                        1 -> Color.parseColor("#4285F4") // Google Blue
                        2 -> Color.parseColor("#FBBC05") // Google Yellow
                        3 -> Color.parseColor("#00F0FF") // Cyan Apex
                        4 -> Color.parseColor("#34A853") // Google Green
                        else -> Color.parseColor("#EA4335") // Google Red
                    }
                }
                val baseSatColor = ensureVisibleBloomColor(rawSatColor, colorCobaltGlow)

                // Active popping satellites flash brighter toward white on notes & hits
                val satColor = if (satMag > 16f) {
                    val ratio = (satMag / 65f).coerceIn(0f, 0.85f)
                    ColorUtils.blendARGB(baseSatColor, Color.WHITE, ratio)
                } else {
                    baseSatColor
                }

                // Vibrant translucent planar fill using the stolen color!
                paint.style = Paint.Style.FILL
                paint.color = satColor
                paint.alpha = (55 + (satMag * 1.4f).toInt()).coerceIn(45, 180)
                drawDiamond(canvas, satX, satY, satSize, paint)

                // Glowing neon contour
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.2f + (satMag * 0.04f)
                paint.color = if (satMag > 22f) Color.WHITE else satColor
                paint.alpha = (180 + (satMag * 1.1f).toInt()).coerceIn(160, 255)
                drawDiamond(canvas, satX, satY, satSize, paint)
            }
        }

        /**
         * Regular 4-vertex diamond generator (rotated 45° square)
         */
        private fun drawDiamond(canvas: Canvas, x: Float, y: Float, radius: Float, paint: Paint) {
            val path = Path().apply {
                moveTo(x, y - radius)      // Top
                lineTo(x + radius, y)      // Right
                lineTo(x, y + radius)      // Bottom
                lineTo(x - radius, y)      // Left
                close()
            }
            canvas.drawPath(path, paint)
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
