package com.ghost.api.ui

import android.content.Context
import android.content.SharedPreferences
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

    companion object {
        // Universal maximum parallax shift for gyroscope/accelerometer tilt tracking
        private const val PARALLAX_MAX = 100f

        // Pre-computed static colors to eliminate runtime String parsing in 60fps render loop
        private val COLOR_BACKGROUND = Color.parseColor("#0A0A0A")
        private val COLOR_VOID = Color.parseColor("#060A10")
        private val COLOR_SINGULARITY = Color.parseColor("#05050A")
        private val COLOR_STAR_CORE = Color.parseColor("#F8FAFC")
        private val COLOR_CYAN_ACCENT = Color.parseColor("#38BDF8")
        private val COLOR_PALE_SLATE = Color.parseColor("#F1F5F9")
        private val COLOR_SOFT_BLUE = Color.parseColor("#93C5FD")
        private val COLOR_INDIGO = Color.parseColor("#818CF8")
        private val COLOR_ORBIT_HALO = Color.parseColor("#E0F2FE")
        private val COLOR_GOLD_RIM = Color.parseColor("#FBBF24")
        private val COLOR_ELECTRIC_PURPLE = Color.parseColor("#A78BFA")
        private val COLOR_COBALT_GLOW = Color.parseColor("#8BB4F6")

        private val SUN_BLOOM_COLORS = intArrayOf(
            Color.parseColor("#991B1B"), // 0: Deep crimson outer storm
            Color.parseColor("#DC2626"), // 1: Neon red flare
            Color.parseColor("#EA580C"), // 2: Blazing solar orange
            Color.parseColor("#FBBF24")  // 3: Electric gold inner rim
        )
        private val SUN_BLOOM_MULTIPLIERS = floatArrayOf(2.2f, 1.75f, 1.4f, 1.15f)
        private val SUN_BLOOM_ALPHAS = intArrayOf(25, 45, 80, 140)

        private val SATELLITE_COLORS = intArrayOf(
            Color.parseColor("#F97316"), // 0: Orange Apex
            Color.parseColor("#4285F4"), // 1: Google Blue
            Color.parseColor("#FBBC05"), // 2: Google Yellow
            Color.parseColor("#00F0FF"), // 3: Cyan Apex
            Color.parseColor("#34A853"), // 4: Google Green
            Color.parseColor("#EA4335")  // 5: Google Red
        )

        private val BLOOM_ALPHAS_OPTION_A = intArrayOf(18, 32, 52, 80)

        private val HEX_HALO_RADII_MULTS = floatArrayOf(1.80f, 2.80f, 4.20f, 6.00f)
        private val HEX_HALO_BASS_MULTS = floatArrayOf(0.50f, 1.10f, 2.00f, 3.20f)
        private val HEX_HALO_ALPHAS = intArrayOf(90, 65, 45, 25)
        private val HEX_HALO_WIDTHS = floatArrayOf(3.5f, 2.8f, 2.2f, 1.8f)

        private val HEX_BLOOM_CORE_MULTS = floatArrayOf(2.50f, 1.95f, 1.50f, 1.20f)
        private val HEX_BLOOM_BASS_MULTS = floatArrayOf(2.20f, 1.60f, 1.05f, 0.50f)
        private val HEX_BLOOM_ALPHAS = intArrayOf(35, 65, 110, 160)
        private val HEX_BLOOM_WIDTHS = floatArrayOf(5.5f, 4.2f, 3.2f, 2.5f)

        private val TRI_BLOOM_FOCAL_MULTS = floatArrayOf(2.40f, 1.85f, 1.45f, 1.18f)
        private val TRI_BLOOM_MELODY_MULTS = floatArrayOf(1.20f, 0.85f, 0.50f, 0.25f)
        private val TRI_BLOOM_ALPHAS = intArrayOf(35, 65, 110, 160)
        private val TRI_BLOOM_WIDTHS = floatArrayOf(5.5f, 4.2f, 3.2f, 2.2f)

        private val CUBE_BLOOM_CORE_MULTS = floatArrayOf(2.40f, 1.85f, 1.45f, 1.18f)
        private val CUBE_BLOOM_BASS_MULTS = floatArrayOf(1.30f, 0.90f, 0.55f, 0.25f)
        private val CUBE_BLOOM_ALPHAS = intArrayOf(35, 70, 115, 160)
        private val CUBE_BLOOM_WIDTHS = floatArrayOf(5.5f, 4.2f, 3.2f, 2.2f)
    }

    override fun onCreateEngine(): Engine {
        return AvatarEngine()
    }

    inner class AvatarEngine : Engine(), SystemVisualizer.AudioListener, SensorEventListener {
        
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Zero-GC cached Path objects for 60fps geometry rendering
        private val cachedDiamondPath = Path()
        private val cachedTrianglePath = Path()
        private val cachedHexPath = Path()
        private val cachedWallPath = Path()
        private val cachedFlowerPath = Path()

        // Cached Preferences - avoid framework disk/mutex hits inside Choreographer loop
        private var cachedBackend = "AUTO"
        private var cachedPreset = "OPTION_A"
        private var cachedSafeMode = false

        private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                Constants.PREF_USER_BACKEND -> cachedBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO") ?: "AUTO"
                Constants.PREF_VISUALIZER_PRESET -> cachedPreset = prefs.getString(Constants.PREF_VISUALIZER_PRESET, "OPTION_A") ?: "OPTION_A"
                "safe_mode" -> cachedSafeMode = prefs.getBoolean("safe_mode", false)
            }
        }

        private fun updateCachedPreferences() {
            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            cachedBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO") ?: "AUTO"
            cachedPreset = prefs.getString(Constants.PREF_VISUALIZER_PRESET, "OPTION_A") ?: "OPTION_A"
            cachedSafeMode = prefs.getBoolean("safe_mode", false)
        }
        
        private var currentFft = ByteArray(0)
        private var smoothedIntensity = 0f
        private var smoothedBass = 0f
        private var smoothedMelody = 0f
        
        // Default Google Colors + Accent Purple
        private val defaultColors = intArrayOf(
            COLOR_ELECTRIC_PURPLE,       // 0: Electric Purple (Subconscious Turing Core)
            SATELLITE_COLORS[1],         // 1: Google Blue
            SATELLITE_COLORS[5],         // 2: Google Red
            SATELLITE_COLORS[2],         // 3: Google Yellow
            SATELLITE_COLORS[4]          // 4: Google Green
        )

        // Default neutral star glow matches Ethereal Off-White Cobalt (#8BB4F6) from the App Icon & HUD Sparkle
        private val colorCobaltGlow = COLOR_COBALT_GLOW
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
                    val backend = cachedBackend
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

                    // Exponential strobe decay (sharp beat onset, sustained laser trail)
                    strobeFlash = (strobeFlash * 0.90f).coerceAtLeast(0f)

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
            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            updateCachedPreferences()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            if (visible) {
                updateCachedPreferences()
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

        // Snare transient detector (mid-band 1.5kHz - 4.5kHz energy spike tracker)
        private var rollingSnareEnergy = 15f
        private var lastSnareTimestamp = 0L

        override fun onAudioData(waveform: ByteArray, fft: ByteArray, intensity: Float, bass: Float) {
            currentFft = fft
            val now = System.currentTimeMillis()

            // 1. CENTRE BOOMING: Dedicated Sub-Bass & Kick tracking
            // Controls the central mother geometry expansion, concentric bloom pulses, and breathing
            smoothedBass = smoothedBass * 0.70f + bass * 0.30f

            if (fft.isNotEmpty() && fft.size >= 16) {
                val totalBins = (fft.size / 2) - 1

                // 2. LIGHT FLASHES: Snare & Drum Attack Transient Detector
                // In Android's Visualizer FFT, values in the byte array are signed 8-bit integers (-128..127) or unsigned (0..255).
                // Taking abs() ensures we measure raw amplitude regardless of byte signedness.
                // Snare drums and rimshots peak in the lower-mid / presence spectrum (bins 8..28).
                val snareBinStart = (totalBins * 0.08f).toInt().coerceIn(3, totalBins - 6)
                val snareBinEnd = (totalBins * 0.28f).toInt().coerceIn(snareBinStart + 2, totalBins - 1)
                var currentSnareEnergy = 0f
                var count = 0
                for (b in snareBinStart..snareBinEnd) {
                    val r = Math.abs(fft[b * 2].toInt()).toDouble()
                    val im = Math.abs(fft[b * 2 + 1].toInt()).toDouble()
                    currentSnareEnergy += Math.hypot(r, im).toFloat()
                    count++
                }
                val avgSnare = if (count > 0) currentSnareEnergy / count else 0f

                // Dynamic snare trigger:
                // Compares current hit against rolling energy baseline with 120ms debounce.
                // Also triggers on strong audio intensity beats so lasers never stay dead/dark!
                val snareThreshold = (rollingSnareEnergy * 1.25f).coerceAtLeast(6f)
                val isSnareHit = (avgSnare > snareThreshold && avgSnare > 8f && (now - lastSnareTimestamp > 120L))
                val isIntensitySpike = (intensity > 60f && bass > 60f && (now - lastSnareTimestamp > 180L))

                if (isSnareHit || isIntensitySpike) {
                    strobeFlash = 1f // Trigger theatrical stage spotlight / laser flash!
                    lastSnareTimestamp = now
                } else if (avgSnare > 12f) {
                    // Continuous subtle laser energy proportional to mid-band presence so it never stays 100% dead
                    strobeFlash = maxOf(strobeFlash, (avgSnare / 50f).coerceIn(0f, 0.45f))
                }
                rollingSnareEnergy = rollingSnareEnergy * 0.85f + avgSnare * 0.15f

                // 3. INDIVIDUAL HEXAGONS / NODES POPPING: Highs, Harmonics & Melody (NO Sub-Bass!)
                // Nodes are dedicated strictly to melody lines, synths, vocals, guitars, hi-hats, and cymbals.
                // We start above bass (bin 10+ / ~450Hz) and logarithmically cover up to 14kHz.
                val minMelodyBin = (totalBins * 0.06f).toInt().coerceAtLeast(8)
                val melodySpan = (totalBins - minMelodyBin).coerceAtLeast(1)

                for (n in nodeMagnitudes.indices) {
                    val frac = (n + 1).toFloat() / 30f
                    // Exponent 1.8 gives smooth musical octave distribution across chords, vocal formants, and crisp air
                    val binIndex = minMelodyBin + (Math.pow(frac.toDouble(), 1.8) * (melodySpan - 2)).toInt().coerceIn(0, melodySpan - 1)
                    
                    val real = Math.abs(fft[binIndex * 2].toInt()).toDouble()
                    val imag = Math.abs(fft[binIndex * 2 + 1].toInt()).toDouble()
                    val rawMag = Math.hypot(real, imag).toFloat()

                    // Equalization gain to boost high frequencies so delicate hi-hats/melodies pop as strongly as chords
                    val eqGain = 1.3f + (frac * 3.2f)
                    val normalizedMag = (rawMag * eqGain).coerceIn(0f, 100f)

                    // Fast attack for crisp individual note pops
                    if (normalizedMag > nodeMagnitudes[n]) {
                        nodeMagnitudes[n] = normalizedMag
                    }
                }

                // Melodic activity level (average of mid/high active notes)
                var melodySum = 0f
                for (n in 0 until 18) {
                    melodySum += nodeMagnitudes[n]
                }
                val instantMelody = melodySum / 18f
                smoothedMelody = smoothedMelody * 0.75f + instantMelody * 0.25f
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
                val backend = cachedBackend
                val isSafeMode = cachedSafeMode

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
                    
                    // Baseline geometric center coordinates
                    val baseCx = width / 2f - 15f
                    val baseCy = height / 2f - 75f

                    canvas.drawColor(COLOR_BACKGROUND)
                    
                    rotationAngle += 0.2f + (smoothedBass / 100f)
                    
                    val isNoisy = smoothedBass > 100f || smoothedIntensity > 80f
                    
                    val preset = cachedPreset
                    
                    when (preset) {
                        "OPTION_B" -> {
                            drawOptionBHexLattice(canvas, baseCx, baseCy, dynamicBaseRadius, width, height)
                        }
                        "OPTION_C" -> {
                            drawOptionCDeltaTunnel(canvas, baseCx, baseCy, dynamicBaseRadius)
                        }
                        "OPTION_D" -> {
                            drawOptionDCubeLattice(canvas, baseCx, baseCy, dynamicBaseRadius, width, height)
                        }
                        else -> {
                            drawOptionAOrbitalStar(canvas, baseCx, baseCy, dynamicBaseRadius, width, height, isNoisy)
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
                
                cachedFlowerPath.reset()
                for (i in 0..numPoints) {
                    val angle = (i * Math.PI * 2 / numPoints).toFloat()
                    
                    val binIndex = (i * 2) % (if (currentFft.isEmpty()) 1 else currentFft.size / 2)
                    val mag = if (currentFft.isNotEmpty() && (binIndex * 2 + 1) < currentFft.size) {
                        val r = Math.abs(currentFft[binIndex * 2].toInt()).toDouble()
                        val i_comp = Math.abs(currentFft[binIndex * 2 + 1].toInt()).toDouble()
                        Math.hypot(r, i_comp).toFloat()
                    } else 0f
                    
                    val rOffset = mag * 5f + (c * 45f)
                    val r = currentRadius + rOffset
                    
                    val x = cos(angle) * r
                    val y = sin(angle) * r
                    
                    if (i == 0) cachedFlowerPath.moveTo(x, y)
                    else cachedFlowerPath.lineTo(x, y)
                }
                cachedFlowerPath.close()
                canvas.drawPath(cachedFlowerPath, paint)
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
                
                val radius = startRadius + (i * 130f) + (smoothedBass * (i * 0.6f))
                
                if (i == 0) {
                    drawOscilloscopeFlower(canvas, startRadius)
                } else {
                    canvas.drawCircle(0f, 0f, radius, paint)
                }
            }
        }

        /**
         * Option A: Orbital Star / Iris (Radial Baseline)
         * Enhanced with:
         * 1. Theatrical Square Cyber Corridor & Corner Laser Guide Rails flashing on snare drum attacks
         * 2. High-definition wireframe contour outlines around the glow corona and central white sparkle
         * 3. Geometric wireframe diamond cage framing the star core
         * 4. Dynamic booming bass expansion, ambient grounding pool, and harmonic breathing
         */
        /**
         * Option A: Orbital Star / Iris (Radial Baseline)
         * Upgraded Architecture:
         * 1. Multi-Layer Stereoscopic Parallax:
         *    - Corridor Background: 0.08x depth (ultra-slow vanishing point)
         *    - Oscilloscope Flower / Iris: 0.55x depth (mid-field harmonics)
         *    - Diamond Cage & Bloom Corona: 0.90x depth (avatar chamber container)
         *    - Central ✧ Sparkle: 0.96x depth (floating jewel, cannot breach container)
         * 2. Concentric Zero-Offset Alignment:
         *    - Bloom layers, diamond cage, and star core aligned with exact font metrics
         */
        private fun drawOptionAOrbitalStar(canvas: Canvas, baseCx: Float, baseCy: Float, dynamicBaseRadius: Float, width: Float, height: Float, isNoisy: Boolean) {
            val bassBoost = smoothedBass * 3.2f // Booming expansion on audio beats!
            val idleBreath = sin(rotationAngle * 0.4f) * 25f
            val corridorColor = if (isCustomPaletteActive) currentColors[0] else COLOR_CYAN_ACCENT

            val tiltX = rollOffset * PARALLAX_MAX
            val tiltY = pitchOffset * PARALLAX_MAX

            // Multi-layer optical center coordinates
            val corrCx = baseCx + tiltX * 0.08f
            val corrCy = baseCy + tiltY * 0.08f

            val starCx = baseCx + tiltX * 0.96f
            val starCy = baseCy + tiltY * 0.96f

            // 1. Perspective Square Cyber Corridor & Corner Laser Guide Rails (Snare / Transient Lighting Flashes)
            val hallwayFlashAlpha = if (strobeFlash > 0.03f) {
                (strobeFlash * 255f).toInt().coerceIn(0, 255)
            } else {
                0
            }

            if (hallwayFlashAlpha > 0) {
                canvas.save()
                paint.clearShadowLayer()

                val nearSpan = (dynamicBaseRadius * 0.92f + (smoothedBass * 1.2f).coerceAtLeast(0f) * 0.45f).coerceIn(40f, 220f)
                val nTLx = corrCx - nearSpan; val nTLy = corrCy - nearSpan
                val nTRx = corrCx + nearSpan; val nTRy = corrCy - nearSpan
                val nBRx = corrCx + nearSpan; val nBRy = corrCy + nearSpan
                val nBLx = corrCx - nearSpan; val nBLy = corrCy + nearSpan

                val cTLx = 0f; val cTLy = 0f
                val cTRx = width; val cTRy = 0f
                val cBRx = width; val cBRy = height
                val cBLx = 0f; val cBLy = height

                // Top Perspective Corridor Facet - Zero-GC
                cachedWallPath.reset()
                cachedWallPath.moveTo(nTLx, nTLy)
                cachedWallPath.lineTo(cTLx, cTLy)
                cachedWallPath.lineTo(cTRx, cTRy)
                cachedWallPath.lineTo(nTRx, nTRy)
                cachedWallPath.close()
                paint.style = Paint.Style.FILL
                paint.color = corridorColor
                paint.alpha = (hallwayFlashAlpha * 0.16f).toInt().coerceIn(0, 50)
                canvas.drawPath(cachedWallPath, paint)

                // Right Perspective Corridor Facet
                cachedWallPath.reset()
                cachedWallPath.moveTo(nTRx, nTRy)
                cachedWallPath.lineTo(cTRx, cTRy)
                cachedWallPath.lineTo(cBRx, cBRy)
                cachedWallPath.lineTo(nBRx, nBRy)
                cachedWallPath.close()
                paint.alpha = (hallwayFlashAlpha * 0.22f).toInt().coerceIn(0, 65)
                canvas.drawPath(cachedWallPath, paint)

                // Bottom Perspective Corridor Facet
                cachedWallPath.reset()
                cachedWallPath.moveTo(nBRx, nBRy)
                cachedWallPath.lineTo(cBRx, cBRy)
                cachedWallPath.lineTo(cBLx, cBLy)
                cachedWallPath.lineTo(nBLx, nBLy)
                cachedWallPath.close()
                paint.alpha = (hallwayFlashAlpha * 0.16f).toInt().coerceIn(0, 50)
                canvas.drawPath(cachedWallPath, paint)

                // Left Perspective Corridor Facet
                cachedWallPath.reset()
                cachedWallPath.moveTo(nBLx, nBLy)
                cachedWallPath.lineTo(cBLx, cBLy)
                cachedWallPath.lineTo(cTLx, cTLy)
                cachedWallPath.lineTo(nTLx, nTLy)
                cachedWallPath.close()
                paint.alpha = (hallwayFlashAlpha * 0.22f).toInt().coerceIn(0, 65)
                canvas.drawPath(cachedWallPath, paint)

                // 4 Corner Laser Guide Rails shooting into the 4 screen corners (snare drum attack flashes!)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f + (strobeFlash * 4.5f)
                val laserColor = if (strobeFlash > 0.35f) {
                    ColorUtils.blendARGB(corridorColor, Color.WHITE, ((strobeFlash - 0.35f) / 0.65f).coerceIn(0f, 0.9f))
                } else {
                    corridorColor
                }
                paint.color = laserColor
                paint.alpha = (hallwayFlashAlpha * 0.92f).toInt().coerceIn(0, 245)
                canvas.drawLine(nTLx, nTLy, cTLx, cTLy, paint)
                canvas.drawLine(nTRx, nTRy, cTRx, cTRy, paint)
                canvas.drawLine(nBRx, nBRy, cBRx, cBRy, paint)
                canvas.drawLine(nBLx, nBLy, cBLx, cBLy, paint)

                // Concentric square corridor frames stepping along perspective depth
                for (step in 1..2) {
                    val t = step * 0.38f
                    val fx1 = nTLx + (cTLx - nTLx) * t
                    val fy1 = nTLy + (cTLy - nTLy) * t
                    val fx2 = nBRx + (cBRx - nBRx) * t
                    val fy2 = nBRy + (cBRy - nBRy) * t
                    paint.strokeWidth = 2f + (strobeFlash * 1.5f)
                    paint.alpha = (hallwayFlashAlpha * 0.55f).toInt().coerceIn(0, 160)
                    canvas.drawRect(fx1, fy1, fx2, fy2, paint)
                }

                canvas.restore()
            }

            // 2. Harmonic Oscilloscope Flower / Iris centered inside the ✧ glyph aperture
            canvas.save()
            // Nudge rings slightly right and down (+12f, +40f) to optically align with the ✧ glyph aperture (exact vanilla alignment)
            canvas.translate(starCx + 12f, starCy + 40f)
            canvas.rotate(rotationAngle)
            
            // Faint idle celestial resonance ring (gives ambient life even in silence)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = if (isCustomPaletteActive) currentColors[1 % currentColors.size] else COLOR_CYAN_ACCENT
            paint.alpha = 25
            canvas.drawCircle(0f, 0f, dynamicBaseRadius * 2.8f + (sin(rotationAngle * 0.3f) * 12f), paint)

            if (isNoisy) {
                drawIris(canvas, dynamicBaseRadius)
            } else {
                drawOscilloscopeFlower(canvas, dynamicBaseRadius)
            }
            
            canvas.restore()

            val baseStarSize = 1250f

            // 3. Multi-pass bloom glow:
            // Soft radiant ethereal glow - strictly concentric with core star
            logoPaint.clearShadowLayer()
            logoPaint.style = Paint.Style.FILL
            for (i in 0 until 4) {
                val bloomSize = when (i) {
                    0 -> baseStarSize + 550f + bassBoost + idleBreath         // 0: Outermost Corona
                    1 -> baseStarSize + 340f + bassBoost + (idleBreath * 0.6f) // 1: Mid-Outer Halo
                    2 -> baseStarSize + 170f + (bassBoost * 0.7f)              // 2: Mid-Inner Aura
                    else -> baseStarSize + 50f + (bassBoost * 0.3f)            // 3: Inner (Closest to Star)
                }
                val layerColor = if (isCustomPaletteActive) {
                    val swatchIndex = when (i) {
                        3 -> 1 % currentColors.size // Vibrant
                        2 -> 0 % currentColors.size // Dominant
                        1 -> 2 % currentColors.size // Muted
                        else -> 3 % currentColors.size // Dark Vibrant
                    }
                    val rawColor = currentColors[swatchIndex]
                    ensureVisibleBloomColor(rawColor, COLOR_COBALT_GLOW)
                } else {
                    COLOR_COBALT_GLOW
                }

                // Set textSize BEFORE calculating descent/ascent so optical center is EXACT!
                logoPaint.color = layerColor
                logoPaint.textSize = bloomSize
                logoPaint.alpha = BLOOM_ALPHAS_OPTION_A[i]
                val bloomCenterOffset = (logoPaint.descent() + logoPaint.ascent()) / 2f
                canvas.drawText("✧", starCx, starCy - bloomCenterOffset, logoPaint)
            }
            
            // 4. Crisp Core star (pure solid white sparkle)
            logoPaint.style = Paint.Style.FILL
            logoPaint.color = COLOR_STAR_CORE
            logoPaint.alpha = 255
            logoPaint.textSize = baseStarSize
            val starCenterOffset = (logoPaint.descent() + logoPaint.ascent()) / 2f
            canvas.drawText("✧", starCx, starCy - starCenterOffset, logoPaint)
        }

        /**
         * Option B: Hex Lattice & Stage Lighting (Hexagonal Sacred Geometry)
         * Architectural Roles:
         * 1. Multi-Layer Stereoscopic Parallax Separation:
         *    - Deep Background Corridor (0.08x): Perspective hallway guide rails, corridor facets, and
         *      concentric wireframe hallway halos all locked to (corrCx, corrCy). Ultra-slow, deep, steady!
         *    - Floating Foreground Avatar (1.20x): Obsidian mother hexagon chamber, radiant bloom auras,
         *      and 6-arm logarithmic sacred geometry flower all locked to (avatarCx, avatarCy).
         *      Sweeps over the deep hallway with dramatic 3D stereoscopic depth!
         *    - Central ✧ Sparkle (1.22x): Jewel depth with subtle float, fully contained inside the chamber.
         * 2. Cohesive Sacred Geometry Lattice (No Bloated Individual Pops):
         *    - Arms and nodes expand and scale as a unified, harmonious flower.
         *    - Discrete musical notes add a subtle, elegant accent (+2px to +5px), retaining rich stolen palette colors
         *      without ever ballooning into giant, clunky white shapes!
         * 3. Hallway Illumination:
         *    - Restrained physical spread for corridor halos.
         *    - Reactive lighting intensity: alphas, stroke sheen, and strobe flashes pulse with audio.
         */
        private fun drawOptionBHexLattice(canvas: Canvas, baseCx: Float, baseCy: Float, baseRadius: Float, width: Float, height: Float) {
            val bassKick = (smoothedBass * 1.5f).coerceAtLeast(0f)
            
            // 1. REFINED REACTOR CORE SCALING (True to original blue proportions)
            val coreRadius = (baseRadius * 1.25f + bassKick * 0.50f).coerceIn(95f, 220f)

            // 2. GENTLE HALLWAY SPREAD (Corridor wireframes remain architecturally stable)
            val hallwayGentleSpread = (bassKick * 0.20f + strobeFlash * 12f).coerceIn(0f, 40f)

            // Multi-Layer Stereoscopic Parallax Offsets
            val tiltX = rollOffset * PARALLAX_MAX
            val tiltY = pitchOffset * PARALLAX_MAX

            // ==========================================
            // LAYER 1: DEEP BACKGROUND CORRIDOR (0.08x)
            // Ultra-slow deep vanishing point anchor
            // ==========================================
            val corrCx = baseCx + tiltX * 0.08f
            val corrCy = baseCy + tiltY * 0.08f

            val ambientIllumination = (smoothedIntensity * 0.40f).coerceIn(0f, 60f)
            val hallwayFlashAlpha = maxOf((strobeFlash * 255f), ambientIllumination).toInt().coerceIn(0, 255)

            val nearR = coreRadius * 1.05f
            val farR = maxOf(width, height) * 0.88f
            val wallColor = if (isCustomPaletteActive) currentColors[0] else COLOR_CYAN_ACCENT

            if (hallwayFlashAlpha > 0) {
                canvas.save()
                canvas.translate(corrCx, corrCy)
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

                    // Perspective wall quad/trapezoid - Zero-GC
                    cachedWallPath.reset()
                    cachedWallPath.moveTo(n1x, n1y)
                    cachedWallPath.lineTo(f1x, f1y)
                    cachedWallPath.lineTo(f2x, f2y)
                    cachedWallPath.lineTo(n2x, n2y)
                    cachedWallPath.close()

                    // Alternating subtle wall facet shading
                    val facetFactor = if (v % 2 == 0) 1.0f else 0.65f
                    paint.style = Paint.Style.FILL
                    paint.color = wallColor
                    paint.alpha = (hallwayFlashAlpha * 0.22f * facetFactor).toInt().coerceIn(0, 75)
                    canvas.drawPath(cachedWallPath, paint)

                    // Crisp architectural perspective corner guide lines
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

            paint.clearShadowLayer()

            // Concentric Vertex-Aligned Hexagonal Halos (Hallway Corridor Rings)
            // Centered on the background corridor vanishing point (corrCx, corrCy) with subtle progressive depth (0.08x - 0.16x)
            // Mults (1.8f, 2.8f, 4.2f, 6.0f) give grand architectural corridor reach across the screen
            paint.style = Paint.Style.STROKE
            for (h in 0 until 4) {
                val haloDepth = 0.08f + (h * 0.025f)
                val haloCx = baseCx + tiltX * haloDepth
                val haloCy = baseCy + tiltY * haloDepth

                val radius = coreRadius * HEX_HALO_RADII_MULTS[h] + hallwayGentleSpread * (0.35f + h * 0.25f)
                paint.strokeWidth = HEX_HALO_WIDTHS[h] + (strobeFlash * 3.0f) + (smoothedIntensity * 0.035f)
                paint.color = if (isCustomPaletteActive) currentColors[h % currentColors.size] else COLOR_CYAN_ACCENT
                val surgeAlpha = (HEX_HALO_ALPHAS[h] + (strobeFlash * 120f).toInt() + (smoothedIntensity * 0.60f).toInt()).coerceIn(0, 255)
                paint.alpha = surgeAlpha
                drawHexagon(canvas, haloCx, haloCy, radius, paint)
            }

            // High-energy strobe halo on prominent outer hex during beat flash
            if (strobeFlash > 0.08f) {
                val strobeCx = baseCx + tiltX * 0.12f
                val strobeCy = baseCy + tiltY * 0.12f
                paint.strokeWidth = 4.5f + (strobeFlash * 3.5f)
                paint.color = wallColor
                paint.alpha = (strobeFlash * 255f).toInt().coerceIn(0, 255)
                drawHexagon(canvas, strobeCx, strobeCy, coreRadius * 2.8f + hallwayGentleSpread * 0.6f, paint)
            }

            // ==========================================
            // LAYER 2: FLOATING FOREGROUND AVATAR (1.20x)
            // Strong 3D float over the deep background!
            // ==========================================
            val avatarCx = baseCx + tiltX * 1.20f
            val avatarCy = baseCy + tiltY * 1.20f

            // 1. Stepped Concentric Blooming Hexagons behind Mother Hexagon
            for (hb in 0 until 4) {
                val radius = coreRadius * HEX_BLOOM_CORE_MULTS[hb] + (hallwayGentleSpread * (0.20f + hb * 0.12f))
                val swatchIndex = when (hb) {
                    3 -> 1 % currentColors.size
                    2 -> 0 % currentColors.size
                    1 -> 2 % currentColors.size
                    else -> 3 % currentColors.size
                }
                val rawColor = if (isCustomPaletteActive) currentColors[swatchIndex] else COLOR_COBALT_GLOW
                val hexGlowColor = ensureVisibleBloomColor(rawColor, COLOR_COBALT_GLOW)

                // Translucent aura fill
                paint.style = Paint.Style.FILL
                paint.color = hexGlowColor
                val fillAlphaMult = 0.35f + (smoothedIntensity / 140f) + (strobeFlash * 0.30f)
                paint.alpha = (HEX_BLOOM_ALPHAS[hb] * fillAlphaMult).toInt().coerceIn(15, 140)
                drawHexagon(canvas, avatarCx, avatarCy, radius, paint)

                // Neon contour
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = HEX_BLOOM_WIDTHS[hb] + (strobeFlash * 2.0f) + (smoothedIntensity * 0.02f)
                paint.color = hexGlowColor
                val strokeAlpha = (HEX_BLOOM_ALPHAS[hb] + (strobeFlash * 70f).toInt() + (smoothedIntensity * 0.45f).toInt()).coerceIn(0, 255)
                paint.alpha = strokeAlpha
                drawHexagon(canvas, avatarCx, avatarCy, radius, paint)
            }

            // 2. Rotating Sacred Geometry Core & Flower Lattice
            canvas.save()
            canvas.translate(avatarCx, avatarCy)
            canvas.rotate(rotationAngle * 0.35f)

            // 2A. Mother Hexagon (Hollow Obsidian Cyber Chamber)
            paint.style = Paint.Style.FILL
            paint.color = COLOR_VOID
            paint.alpha = 245
            drawHexagon(canvas, 0f, 0f, coreRadius, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = COLOR_STAR_CORE
            paint.alpha = 255
            drawHexagon(canvas, 0f, 0f, coreRadius, paint)

            paint.strokeWidth = 2.5f
            paint.color = if (isCustomPaletteActive) currentColors[0] else COLOR_CYAN_ACCENT
            paint.alpha = 200
            drawHexagon(canvas, 0f, 0f, coreRadius * 0.82f, paint)

            // 2B. Six Logarithmic Spiral Hex Arms
            // Decoupled Architecture:
            // 1. Macroscopic Spread: Arms open wide on music energy, creating 70-90px buffer between dots.
            // 2. Base Idle Sizing: Calibrated weight (~13px to ~19px radius) - matching original blue reference.
            // 3. Individual Node Reactivity: When a frequency fires and there is room, that node dynamically
            //    flashes bright white and expands (+0..14px) without colliding with its neighbors!
            val numArms = 6
            val hexesPerArm = 5
            val spiralTwist = 0.22f

            // Dynamic high-energy lattice spread: bass kicks + volume intensity + melodic harmonics
            val latticeSpread = (bassKick * 1.5f + smoothedIntensity * 1.0f + smoothedMelody * 0.8f).coerceIn(0f, 300f)

            for (arm in 0 until numArms) {
                val baseAngle = (arm * Math.PI * 2.0 / numArms).toFloat()

                for (step in 1..hexesPerArm) {
                    val progress = step.toFloat() / hexesPerArm.toFloat()

                    val nodeIndex = ((step - 1) * numArms + arm).coerceIn(0, nodeMagnitudes.size - 1)
                    val nodeMag = nodeMagnitudes[nodeIndex]

                    val innerDampener = if (step == 1) 0.65f else 1.0f

                    // 1. MACROSCOPIC ARM SPREAD (Buffer creation)
                    // Spreads out 38px in idle to 80px+ on beats, providing open room for individual pops
                    val stepSpread = step * (38f + latticeSpread * 0.28f)
                    val distance = (coreRadius * 1.30f) + stepSpread
                    val angle = baseAngle + (step * spiralTwist) + (smoothedIntensity * 0.003f)

                    val hx = (cos(angle) * distance).toFloat()
                    val hy = (sin(angle) * distance).toFloat()

                    // 2. BASE IDLE SIZING (Calibrated baseline thickness: ~19px inner down to ~13px outer)
                    val baseHexSize = coreRadius * 0.18f * (1.08f - progress * 0.42f)

                    // 3. INDIVIDUAL NODE EXPANSION (Fires into the open space!)
                    val cohesiveBreath = 1.0f + (latticeSpread / 250f) * 0.18f
                    val individualPop = (nodeMag * 0.22f * innerDampener).coerceIn(0f, 14f)
                    val hexSize = baseHexSize * cohesiveBreath + individualPop

                    val colorIdx = (arm + step) % currentColors.size
                    val baseArmColor = if (isCustomPaletteActive) currentColors[colorIdx] else {
                        when (step) {
                            1 -> COLOR_PALE_SLATE
                            2 -> COLOR_SOFT_BLUE
                            3 -> COLOR_CYAN_ACCENT
                            4 -> COLOR_INDIGO
                            else -> COLOR_ELECTRIC_PURPLE
                        }
                    }

                    // 4. INDIVIDUAL WHITE FLASH (Crisp electric onset on frequency spikes)
                    val whiteFlashRatio = if (nodeMag > 16f) {
                        ((nodeMag - 16f) / 38f).coerceIn(0f, 0.95f)
                    } else {
                        0f
                    }
                    val armColor = if (whiteFlashRatio > 0f) {
                        ColorUtils.blendARGB(baseArmColor, Color.WHITE, whiteFlashRatio)
                    } else {
                        baseArmColor
                    }

                    val baseAlpha = ((1f - progress * 0.30f) * 205).toInt()
                    val nodeAlpha = (baseAlpha + (nodeMag * 0.8f).toInt()).coerceIn(50, 255)

                    // Translucent fill
                    paint.style = Paint.Style.FILL
                    paint.color = armColor
                    paint.alpha = nodeAlpha
                    drawHexagon(canvas, hx, hy, hexSize, paint)

                    // Crisp contour: flashes pure white border on high frequency notes
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.8f + (nodeMag * 0.025f)
                    paint.color = if (whiteFlashRatio > 0.20f) Color.WHITE else armColor
                    paint.alpha = (nodeAlpha + 25).coerceIn(60, 255)
                    drawHexagon(canvas, hx, hy, hexSize, paint)
                }
            }

            canvas.restore() // Restores unrotated frame

            // 3. Model Unicode Glyph (✧) Centered, Crisp White & Contained
            val glyphCx = baseCx + tiltX * 1.22f
            val glyphCy = baseCy + tiltY * 1.22f

            logoPaint.clearShadowLayer()
            logoPaint.style = Paint.Style.FILL
            logoPaint.color = Color.WHITE
            logoPaint.alpha = 255
            logoPaint.textSize = coreRadius * 1.35f
            val off = (logoPaint.descent() + logoPaint.ascent()) / 2f
            canvas.drawText("✧", glyphCx, glyphCy - off, logoPaint)
        }

        /**
         * Option C: Delta Tunnel & Prisms (Prismatic Delta Geometry)
         * Audio architecture:
         * 1. Speed: Continuous tunnel zoom and corkscrewing rotation driven non-stop by sub-bass/kicks.
         * 2. Central Expansion & Bloom: Driven by melody and harmonics (vocals, synths, chords).
         *    The glow is a permanent, living, breathing part of the triangle housing that swells and contracts with melody.
         * 3. Kick / Snare: Fires corner perspective lasers and lights up the translucent colored tunnel facets
         *    as triangles corkscrew past, producing an immersive "color wall" corridor in motion.
         * 4. Central Glyph (✧): Clean, centered, crisp white core.
         */
        /**
         * Option C: Delta Tunnel & Prisms (Prismatic Delta Geometry)
         * Upgraded with stereoscopic parallax:
         * - Corner laser guide rails: 0.08x depth (ultra-slow vanishing point anchor)
         * - Corkscrewing tunnel triangles: 0.20x - 0.70x progressive depth
         * - Focal triangle housing: 0.90x depth (avatar chamber container)
         * - Central ✧ Sparkle: 0.96x jewel depth (cannot breach container)
         */
        private fun drawOptionCDeltaTunnel(canvas: Canvas, baseCx: Float, baseCy: Float, baseRadius: Float) {
            val bassKick = (smoothedBass * 1.5f).coerceAtLeast(0f)
            val melodyExpansion = (smoothedMelody * 1.3f).coerceAtLeast(0f)
            val numTriangles = 9
            val baseSize = (baseRadius * 0.75f + melodyExpansion * 0.35f).coerceIn(30f, 180f)

            val tiltX = rollOffset * PARALLAX_MAX
            val tiltY = pitchOffset * PARALLAX_MAX

            val corrCx = baseCx + tiltX * 0.08f
            val corrCy = baseCy + tiltY * 0.08f

            // 1. Perspective Corner Laser Guide Rails (Shooting through the 3 vertices into deep space)
            // Ultra-slow vanishing point anchor (0.08x depth)
            if (strobeFlash > 0.03f) {
                canvas.save()
                canvas.translate(corrCx, corrCy)
                val reach = (canvas.width + canvas.height) * 0.95f
                val wallFlashAlpha = (strobeFlash * 255f).toInt().coerceIn(0, 255)
                val primaryColor = if (isCustomPaletteActive) currentColors[0] else COLOR_CYAN_ACCENT

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.2f + (strobeFlash * 3.0f)
                paint.color = primaryColor
                paint.alpha = wallFlashAlpha
                for (v in 0..2) {
                    val railAngle = (v * Math.PI * 2.0 / 3.0 - Math.PI / 2.0).toFloat()
                    val rx = (cos(railAngle) * reach).toFloat()
                    val ry = (sin(railAngle) * reach).toFloat()
                    canvas.drawLine(0f, 0f, rx, ry, paint)
                }
                canvas.restore()
            }

            // 2. Continuous Corkscrewing Infinite Triangular Tunnel (Seven Nation Army Corridor)
            // Vanishing point anchored steadily to corrCx, corrCy so corridor perspective is rock-solid and never jiggles on tilt
            val triangleStep = (Math.PI * 2.0 / 3.0).toFloat() // 120° rotational symmetry
            for (i in 0 until numTriangles) {
                val rawP = (tunnelPhase + (i.toFloat() / numTriangles))
                val p = (rawP % 1.0f + 1.0f) % 1.0f // strictly [0, 1)
                val scale = (baseSize * exp(p * 3.4f)).toFloat()

                val window = sin(p * Math.PI.toFloat())
                val smoothEnvelope = (window * window).coerceIn(0f, 1f)
                val totalAlpha = (smoothEnvelope * 255f).toInt().coerceIn(0, 255)

                val cycleIdx = ((rawP * numTriangles).toInt() % currentColors.size + currentColors.size) % currentColors.size
                val baseColor = if (isCustomPaletteActive) currentColors[cycleIdx] else {
                    if (i % 2 == 0) COLOR_CYAN_ACCENT else COLOR_PALE_SLATE
                }

                val corkscrewAngle = (p * triangleStep * 2f) + (animTime * 0.5f)

                val baseFacetAlpha = (14f * smoothEnvelope).toInt()
                val flashFacetAlpha = if (strobeFlash > 0.04f) ((strobeFlash * 95f) * smoothEnvelope).toInt() else 0
                val facetAlpha = (baseFacetAlpha + flashFacetAlpha).coerceIn(0, 115)

                if (facetAlpha > 0) {
                    paint.style = Paint.Style.FILL
                    paint.color = baseColor
                    paint.alpha = facetAlpha
                    drawEquilateralTriangle(canvas, corrCx, corrCy, scale, corkscrewAngle, paint)
                }

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (3.5f * (1f - p * 0.4f) + (strobeFlash * 1.5f)).coerceIn(1.5f, 6.0f)
                paint.color = baseColor
                paint.alpha = totalAlpha
                drawEquilateralTriangle(canvas, corrCx, corrCy, scale, corkscrewAngle, paint)
            }

            // 3. Vanishing Point Focal Core & Central Triangle Housing
            // Strong foreground parallax (1.25x depth vs 0.08x background): dramatic 3D float!
            val focalRadius = (baseSize * 1.25f + melodyExpansion * 0.65f).coerceIn(40f, 180f)
            val focalCx = baseCx + tiltX * 1.25f
            val focalCy = baseCy + tiltY * 1.25f

            canvas.save()
            canvas.translate(focalCx, focalCy)

            for (tb in 0 until 4) {
                val radius = focalRadius * TRI_BLOOM_FOCAL_MULTS[tb] + (melodyExpansion * TRI_BLOOM_MELODY_MULTS[tb])
                val swatchIndex = when (tb) {
                    3 -> 1 % currentColors.size
                    2 -> 0 % currentColors.size
                    1 -> 2 % currentColors.size
                    else -> 3 % currentColors.size
                }
                val rawColor = if (isCustomPaletteActive) currentColors[swatchIndex] else COLOR_COBALT_GLOW
                val bloomColor = ensureVisibleBloomColor(rawColor, COLOR_COBALT_GLOW)

                paint.style = Paint.Style.FILL
                paint.color = bloomColor
                paint.alpha = (TRI_BLOOM_ALPHAS[tb] * 0.45f).toInt().coerceIn(15, 95)
                drawEquilateralTriangle(canvas, 0f, 0f, radius, 0f, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = TRI_BLOOM_WIDTHS[tb]
                paint.color = bloomColor
                paint.alpha = TRI_BLOOM_ALPHAS[tb]
                drawEquilateralTriangle(canvas, 0f, 0f, radius, 0f, paint)
            }

            // Solid Obsidian Cyber Chamber for Central Triangle
            paint.style = Paint.Style.FILL
            paint.color = COLOR_VOID
            paint.alpha = 240
            drawEquilateralTriangle(canvas, 0f, 0f, focalRadius, 0f, paint)

            val innerColor = if (isCustomPaletteActive) currentColors[0] else COLOR_CYAN_ACCENT
            val baseInnerAlpha = (25 + (melodyExpansion * 1.8f).toInt()).coerceIn(20, 90)
            val kickInnerAlpha = if (strobeFlash > 0.04f) (strobeFlash * 70f).toInt() else 0
            paint.style = Paint.Style.FILL
            paint.color = innerColor
            paint.alpha = (baseInnerAlpha + kickInnerAlpha).coerceIn(20, 160)
            drawEquilateralTriangle(canvas, 0f, 0f, focalRadius * 0.85f, 0f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.8f + (strobeFlash * 2.5f)
            paint.color = if (strobeFlash > 0.05f) Color.WHITE else COLOR_STAR_CORE
            paint.alpha = 255
            drawEquilateralTriangle(canvas, 0f, 0f, focalRadius, 0f, paint)

            paint.strokeWidth = 2.2f
            paint.color = innerColor
            paint.alpha = 200
            drawEquilateralTriangle(canvas, 0f, 0f, focalRadius * 0.80f, 0f, paint)

            // 4. Center Model Unicode Glyph (✧): Clean, Crisp, Centered White Core
            // Jewel depth 0.96f (subtle float, cannot breach container)
            val glyphSize = focalRadius * 1.15f
            logoPaint.clearShadowLayer()
            logoPaint.color = COLOR_STAR_CORE
            logoPaint.textSize = glyphSize
            logoPaint.alpha = 255
            val off = (logoPaint.descent() + logoPaint.ascent()) / 2f
            val shiftX = tiltX * 0.06f
            val shiftY = tiltY * 0.06f
            canvas.drawText("✧", shiftX, -off - (focalRadius * 0.12f) + shiftY, logoPaint)

            canvas.restore()
        }

        /**
         * Option D: Cyber Matrix / Cube Lattice (Salvation of the Black Sun)
         * Upgraded with stereoscopic parallax:
         * - Deep space Black Sun flares & singularity: 0.15x depth
         * - Orbital halo circle: 0.55x depth
         * - Central Mother Cube & Fake Bloom: 0.90x depth (avatar chamber container)
         * - Central ✧ Sparkle: 0.96x jewel depth (cannot breach container)
         * - Satellites: 0.92x depth
         */
        private fun drawOptionDCubeLattice(canvas: Canvas, baseCx: Float, baseCy: Float, dynamicBaseRadius: Float, width: Float, height: Float) {
            val bassKick = (smoothedBass * 1.6f).coerceAtLeast(0f)
            val coreCubeRadius = (dynamicBaseRadius * 1.85f + bassKick * 0.45f).coerceIn(40f, 200f)

            val tiltX = rollOffset * PARALLAX_MAX
            val tiltY = pitchOffset * PARALLAX_MAX

            // 1. Center Singularities: Parallax-anchored to screen / widget center (0.15x depth)
            val sunRadius = (min(width, height) * 0.22f + bassKick * 0.25f).coerceIn(60f, 240f)
            val flareBoom = (bassKick * 0.75f) + (strobeFlash * 80f)

            val sunCx = baseCx + tiltX * 0.15f
            val sunCy = baseCy + tiltY * 0.15f

            // Deep Space Black Sun with Hot Concentric Solar Flares (Salvation of the Sun)
            paint.style = Paint.Style.FILL
            paint.shader = null
            for (sb in 0 until 4) {
                val r = (sunRadius * SUN_BLOOM_MULTIPLIERS[sb]) + flareBoom
                val flareColor = if (isCustomPaletteActive) currentColors[sb % currentColors.size] else SUN_BLOOM_COLORS[sb]
                paint.color = ensureVisibleBloomColor(flareColor, COLOR_COBALT_GLOW)
                paint.alpha = ((SUN_BLOOM_ALPHAS[sb] + (strobeFlash * 75f).toInt())).coerceIn(15, 240)
                canvas.drawCircle(sunCx, sunCy, r, paint)
            }

            // Black Hole Singularity Core (Deep obsidian dark void)
            paint.style = Paint.Style.FILL
            paint.color = COLOR_SINGULARITY
            paint.alpha = 255
            canvas.drawCircle(sunCx, sunCy, sunRadius, paint)

            // Fiery Accretion Rim
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f + (strobeFlash * 3f)
            paint.color = if (strobeFlash > 0.05f) Color.WHITE else COLOR_GOLD_RIM
            paint.alpha = 240
            canvas.drawCircle(sunCx, sunCy, sunRadius, paint)

            // 2. Orbital Halo Enclosing Circle (0.55x depth)
            val orbitCircleRadius = sunRadius * 1.08f
            val orbitCx = baseCx + tiltX * 0.55f
            val orbitCy = baseCy + tiltY * 0.55f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f + (strobeFlash * 2.0f)
            paint.color = if (isCustomPaletteActive) currentColors[1 % currentColors.size] else COLOR_ORBIT_HALO
            paint.alpha = (140 + (strobeFlash * 100f).toInt()).coerceIn(100, 255)
            canvas.drawCircle(orbitCx, orbitCy, orbitCircleRadius, paint)

            // 3. Central Mother Cube / Diamond (45° diamond inside orbital ring, 0.90x depth)
            val cubeCx = baseCx + tiltX * 0.90f
            val cubeCy = baseCy + tiltY * 0.90f

            canvas.save()
            canvas.translate(cubeCx, cubeCy)

            for (cb in 0 until 4) {
                val radius = coreCubeRadius * CUBE_BLOOM_CORE_MULTS[cb] + (bassKick * CUBE_BLOOM_BASS_MULTS[cb])
                val swatchIndex = when (cb) {
                    3 -> 1 % currentColors.size
                    2 -> 0 % currentColors.size
                    1 -> 2 % currentColors.size
                    else -> 3 % currentColors.size
                }
                val rawColor = if (isCustomPaletteActive) currentColors[swatchIndex] else COLOR_COBALT_GLOW
                val bloomColor = ensureVisibleBloomColor(rawColor, COLOR_COBALT_GLOW)

                paint.style = Paint.Style.FILL
                paint.color = bloomColor
                paint.alpha = (CUBE_BLOOM_ALPHAS[cb] * 0.45f).toInt().coerceIn(15, 95)
                drawDiamond(canvas, 0f, 0f, radius, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = CUBE_BLOOM_WIDTHS[cb]
                paint.color = bloomColor
                paint.alpha = CUBE_BLOOM_ALPHAS[cb]
                drawDiamond(canvas, 0f, 0f, radius, paint)
            }

            paint.style = Paint.Style.FILL
            paint.color = COLOR_VOID
            paint.alpha = 240
            drawDiamond(canvas, 0f, 0f, coreCubeRadius, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f + (strobeFlash * 2.5f)
            paint.color = if (strobeFlash > 0.05f) Color.WHITE else COLOR_STAR_CORE
            paint.alpha = 255
            drawDiamond(canvas, 0f, 0f, coreCubeRadius, paint)

            paint.strokeWidth = 2.5f
            paint.color = if (isCustomPaletteActive) currentColors[0] else COLOR_CYAN_ACCENT
            paint.alpha = 200
            drawDiamond(canvas, 0f, 0f, coreCubeRadius * 0.82f, paint)

            // Centered crisp white ✧ star glyph (0.96x depth, cannot breach cube container)
            logoPaint.clearShadowLayer()
            logoPaint.color = Color.WHITE
            logoPaint.alpha = 255
            logoPaint.textSize = coreCubeRadius * 1.35f
            val cOff = (logoPaint.descent() + logoPaint.ascent()) / 2f
            val shiftX = tiltX * 0.06f
            val shiftY = tiltY * 0.06f
            canvas.drawText("✧", shiftX, -cOff + shiftY, logoPaint)

            canvas.restore()

            // 4. Six Satellite Diamonds / Cubes (Aligned Exactly with Overlay Nodes, 0.92x depth)
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

            val diagDist = Math.hypot(nodeDx.toDouble(), nodeDy.toDouble()).toFloat().coerceAtLeast(1f)
            val diagUnitX = nodeDx / diagDist
            val diagUnitY = nodeDy / diagDist

            val satelliteBaseSize = 24f * density // matches 48dp button radius

            val satBaseX = baseCx + tiltX * 0.92f
            val satBaseY = baseCy + tiltY * 0.92f

            for (s in 0 until numSatellites) {
                // Interleaved spectral pooling across all 30 nodeMagnitudes:
                // Each satellite samples 5 interleaved bands across the entire frequency range,
                // taking the peak transient hit so every cube (top, bottom, left, right) responds
                // actively to melody notes, solos, chord hits, and rhythm!
                // s=0 gets [0, 6, 12, 18, 24], s=1 gets [1, 7, 13, 19, 25], etc.
                var peakSatMag = 0f
                for (b in 0 until 5) {
                    val idx = (b * numSatellites + s).coerceIn(0, nodeMagnitudes.size - 1)
                    if (nodeMagnitudes[idx] > peakSatMag) {
                        peakSatMag = nodeMagnitudes[idx]
                    }
                }
                val satMag = peakSatMag
                val satPop = (satMag * 0.22f).coerceIn(0f, 14f)

                // Active radial outward spreading on bass kicks and frequency pops:
                // Tightly bounded range (0 to 18dp), active and dynamic!
                val spread = (bassKick * 0.18f + satMag * 0.28f).coerceIn(0f, 18f * density)

                // Zero-allocation coordinate computation with dynamic radial breathing
                val offsetX = when (s) {
                    1, 2 -> nodeDx + diagUnitX * spread
                    4, 5 -> -(nodeDx + diagUnitX * spread)
                    else -> 0f
                }
                val offsetY = when (s) {
                    0 -> -(nodeApex + spread)
                    3 -> (nodeApex + spread)
                    1, 5 -> -(nodeDy + diagUnitY * spread)
                    else -> (nodeDy + diagUnitY * spread)
                }
                val satX = satBaseX + offsetX
                val satY = satBaseY + offsetY

                val satSize = satelliteBaseSize + satPop

                // Distinct stolen palette color per satellite
                val colorIdx = s % currentColors.size
                val rawSatColor = if (isCustomPaletteActive) currentColors[colorIdx] else SATELLITE_COLORS[s]
                val baseSatColor = ensureVisibleBloomColor(rawSatColor, COLOR_COBALT_GLOW)

                // Layered Colorful Glass Fill:
                // On hit, the translucent glass interior flashes intensely in stolen color / tinted white,
                // not just the outline!
                val satColor = if (satMag > 15f) {
                    val ratio = (satMag / 60f).coerceIn(0f, 0.75f)
                    ColorUtils.blendARGB(baseSatColor, Color.WHITE, ratio)
                } else {
                    baseSatColor
                }

                // Vibrant translucent layered colored glass fill
                val glassFillAlpha = (40 + (satMag * 2.2f).toInt()).coerceIn(35, 230)
                paint.style = Paint.Style.FILL
                paint.color = satColor
                paint.alpha = glassFillAlpha
                drawDiamond(canvas, satX, satY, satSize, paint)

                // Concentric inner glass facet for layered depth
                paint.style = Paint.Style.FILL
                paint.color = baseSatColor
                paint.alpha = (glassFillAlpha * 0.45f).toInt().coerceIn(15, 120)
                drawDiamond(canvas, satX, satY, satSize * 0.62f, paint)

                // Glowing neon contour
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.2f + (satMag * 0.05f)
                paint.color = if (satMag > 20f) Color.WHITE else satColor
                paint.alpha = (160 + (satMag * 1.4f).toInt()).coerceIn(140, 255)
                drawDiamond(canvas, satX, satY, satSize, paint)
            }
        }

        /**
         * Regular 4-vertex diamond generator (rotated 45° square) - Zero-GC
         */
        private fun drawDiamond(canvas: Canvas, x: Float, y: Float, radius: Float, paint: Paint) {
            cachedDiamondPath.reset()
            cachedDiamondPath.moveTo(x, y - radius)      // Top
            cachedDiamondPath.lineTo(x + radius, y)      // Right
            cachedDiamondPath.lineTo(x, y + radius)      // Bottom
            cachedDiamondPath.lineTo(x - radius, y)      // Left
            cachedDiamondPath.close()
            canvas.drawPath(cachedDiamondPath, paint)
        }

        /**
         * Regular 3-vertex equilateral triangle generator (Seven Nation Army / Delta) - Zero-GC
         */
        private fun drawEquilateralTriangle(canvas: Canvas, x: Float, y: Float, radius: Float, rotation: Float, paint: Paint) {
            cachedTrianglePath.reset()
            for (i in 0 until 3) {
                val angle = (i * Math.PI * 2.0 / 3.0 - Math.PI / 2.0).toFloat() + rotation
                val px = x + (radius * cos(angle))
                val py = y + (radius * sin(angle))
                if (i == 0) cachedTrianglePath.moveTo(px, py) else cachedTrianglePath.lineTo(px, py)
            }
            cachedTrianglePath.close()
            canvas.drawPath(cachedTrianglePath, paint)
        }

        /**
         * Regular 6-vertex regular polygon generator - Zero-GC
         */
        private fun drawHexagon(canvas: Canvas, x: Float, y: Float, radius: Float, paint: Paint) {
            cachedHexPath.reset()
            for (i in 0 until 6) {
                val angle = (i * Math.PI / 3.0).toFloat()
                val px = x + (radius * cos(angle))
                val py = y + (radius * sin(angle))
                if (i == 0) cachedHexPath.moveTo(px, py) else cachedHexPath.lineTo(px, py)
            }
            cachedHexPath.close()
            canvas.drawPath(cachedHexPath, paint)
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
            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            SystemVisualizer.removeListener(this)
            sensorManager?.unregisterListener(this)
            try {
                android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
            } catch (e: Exception) {}
        }
    }
}
