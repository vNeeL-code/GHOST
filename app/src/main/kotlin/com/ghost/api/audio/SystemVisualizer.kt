package com.ghost.api.audio

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.palette.graphics.Palette
import com.ghost.api.GemmaNotificationListener
import timber.log.Timber

/**
 * Singleton manager that hooks into the global system audio mix (Session 0).
 * Captures FFT data, active media album art, or audio-producing app icon colors,
 * then notifies listeners (Wallpaper, EdgeLights, etc.).
 */
object SystemVisualizer {

    private var visualizer: Visualizer? = null
    private var isEnabled = false

    private var appContext: Context? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeMediaController: MediaController? = null

    // Track last active foreground package and last known AI package
    var lastForegroundPackage: String? = null
        private set
    var lastActiveAiPackage: String? = null
        private set
    private var currentAppliedPackage: String? = null

    // Audio activity state & silence decay
    private var lastAudioActivityTime: Long = 0L
    private var isAudioActive: Boolean = false
    
    // Extracted Colors (null means use defaults)
    var currentAlbumColors: IntArray? = null
        private set

    private var overrideEmotionColors: IntArray? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clearEmotionRunnable = Runnable {
        overrideEmotionColors = null
        listeners.forEach { it.onColorsChanged(currentAlbumColors) }
    }

    // Curated 5-color harmonic palettes for AI brands
    // Eliminates muddy programmatic darkening and introduces signature secondary highlights (e.g. Claude's warm cream/light gray)
    private val AI_BRAND_PALETTES = listOf(
        // Claude (Anthropic): Terracotta, Warm Linen Light Gray, Warm Amber Coral, Espresso Slate, Soft Parchment
        Triple(listOf("claude", "anthropic"), intArrayOf(
            Color.parseColor("#D97757"), // 0: Terracotta Accent
            Color.parseColor("#E8E5DE"), // 1: Claude Warm Linen / Light Gray
            Color.parseColor("#E59866"), // 2: Warm Amber Coral
            Color.parseColor("#3B322C"), // 3: Warm Espresso Slate
            Color.parseColor("#F5EBE6")  // 4: Soft Parchment Glow
        ), "Claude"),

        // Qwen (Alibaba): Royal Iris Purple, Soft Lavender, Electric Violet, Deep Nebula, Cyber Indigo
        Triple(listOf("qwen", "qwenlm", "tongyi"), intArrayOf(
            Color.parseColor("#7C4DFF"), // 0: Royal Iris Purple
            Color.parseColor("#E9D5FF"), // 1: Soft Lavender Frost
            Color.parseColor("#A855F7"), // 2: Electric Violet
            Color.parseColor("#241442"), // 3: Deep Nebula Obsidian
            Color.parseColor("#6366F1")  // 4: Cyber Indigo
        ), "Qwen"),

        // Kimi (Moonshot AI): Moonlight Cobalt, Ice Pale Blue, Vivid Cyan Flare, Midnight Slate, Sky Aqua
        Triple(listOf("kimi", "kimichat", "moonshot"), intArrayOf(
            Color.parseColor("#2B5CFF"), // 0: Moonlight Cobalt
            Color.parseColor("#E0F2FE"), // 1: Ice Pale Blue / Crisp White
            Color.parseColor("#00D2FF"), // 2: Vivid Cyan Flare
            Color.parseColor("#0F172A"), // 3: Midnight Slate
            Color.parseColor("#38BDF8")  // 4: Sky Aqua
        ), "Kimi"),

        // Mistral (Le Chat): Solar Flame Orange, Champagne Sand, Amber Gold, Volcanic Basalt, Deep Fire
        Triple(listOf("mistral", "lechat"), intArrayOf(
            Color.parseColor("#FF7000"), // 0: Solar Flame Orange
            Color.parseColor("#FFF3E0"), // 1: Champagne Sand
            Color.parseColor("#FFB300"), // 2: Amber Gold
            Color.parseColor("#261C14"), // 3: Volcanic Basalt
            Color.parseColor("#FF5722")  // 4: Deep Fire Orange
        ), "Mistral"),

        // Grok (xAI): Gunmetal Slate, Platinum Silver Spark, Titanium Gray, Deep Obsidian, Dark Carbon
        Triple(listOf("grok", "xai"), intArrayOf(
            Color.parseColor("#4A5568"), // 0: Gunmetal Slate
            Color.parseColor("#F3F4F6"), // 1: Platinum Silver / White Spark
            Color.parseColor("#9CA3AF"), // 2: Titanium Gray
            Color.parseColor("#111827"), // 3: Deep Obsidian
            Color.parseColor("#374151")  // 4: Dark Carbon
        ), "Grok"),

        // ChatGPT (OpenAI): Signature Mint, Crisp Off-White Slate, Emerald Glow, Charcoal Slate, Pine Forest
        Triple(listOf("chatgpt", "openai"), intArrayOf(
            Color.parseColor("#10A37F"), // 0: Signature Mint Green
            Color.parseColor("#F7F7F8"), // 1: Crisp Off-White Slate
            Color.parseColor("#74AA9C"), // 2: Soft Emerald Glow
            Color.parseColor("#202123"), // 3: Charcoal Slate
            Color.parseColor("#054E3B")  // 4: Deep Pine Forest
        ), "ChatGPT"),

        // DeepSeek: Electric Whale Blue, Polar Ice Slate, Bioluminescent Cyan, Abyssal Trench, Ocean Blue
        Triple(listOf("deepseek"), intArrayOf(
            Color.parseColor("#4D6BFE"), // 0: Electric Whale Blue
            Color.parseColor("#E2E8F0"), // 1: Polar Ice Slate
            Color.parseColor("#00F2FE"), // 2: Bioluminescent Cyan
            Color.parseColor("#0B132B"), // 3: Abyssal Trench Navy
            Color.parseColor("#3B82F6")  // 4: Ocean Blue
        ), "DeepSeek"),

        // Perplexity: Seafoam Teal, Clean Teal Mist, Deep Jade, Dark Pine, Turquoise
        Triple(listOf("perplexity"), intArrayOf(
            Color.parseColor("#20B2AA"), // 0: Seafoam Teal
            Color.parseColor("#F0FDFA"), // 1: Clean Teal Mist
            Color.parseColor("#0D9488"), // 2: Deep Jade
            Color.parseColor("#134E4A"), // 3: Dark Pine
            Color.parseColor("#5EEAD4")  // 4: Bright Turquoise
        ), "Perplexity"),

        // Meta AI / Llama: Vivid Meta Blue, Soft Azure White, Electric Indigo, Deep Midnight, Royal Cyan
        Triple(listOf("meta.ai", "llama"), intArrayOf(
            Color.parseColor("#0081FB"), // 0: Vivid Meta Blue
            Color.parseColor("#EFF6FF"), // 1: Soft Azure White
            Color.parseColor("#6366F1"), // 2: Electric Indigo
            Color.parseColor("#0B132B"), // 3: Deep Midnight
            Color.parseColor("#06B6D4")  // 4: Royal Cyan
        ), "Meta AI"),

        // Microsoft Copilot: Copilot Violet, Lavender Frost, Coral Accent, Deep Obsidian, Royal Purple
        Triple(listOf("copilot"), intArrayOf(
            Color.parseColor("#6B46C1"), // 0: Copilot Violet
            Color.parseColor("#F3E8FF"), // 1: Lavender Frost
            Color.parseColor("#FF6B6B"), // 2: Coral Accent
            Color.parseColor("#1E1035"), // 3: Deep Obsidian
            Color.parseColor("#9333EA")  // 4: Royal Purple
        ), "Copilot"),

        // Google Gemini / Bard: Google Sparkle Blue, Google Off-White, Sparkle Violet, Deep Space Slate, Electric Cyan
        Triple(listOf("bard", "gemini"), intArrayOf(
            Color.parseColor("#4285F4"), // 0: Google Sparkle Blue
            Color.parseColor("#F8F9FA"), // 1: Google Off-White
            Color.parseColor("#9B51E0"), // 2: Sparkle Violet
            Color.parseColor("#1A1D20"), // 3: Deep Space Slate
            Color.parseColor("#00E5FF")  // 4: Electric Cyan
        ), "Gemini")
    )

    private fun findBrandPalette(packageName: String): IntArray? {
        val lower = packageName.lowercase()
        return AI_BRAND_PALETTES.firstOrNull { (keys, _, _) ->
            keys.any { lower.contains(it) }
        }?.second
    }

    interface AudioListener {
        fun onAudioData(waveform: ByteArray, fft: ByteArray, intensity: Float, bass: Float)
        fun onColorsChanged(colors: IntArray?) {}
    }

    private val listeners = mutableListOf<AudioListener>()

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveMediaController(controllers)
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            extractColorsFromMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state == PlaybackState.STATE_PLAYING) {
                if (currentAlbumColors == null) {
                    extractColorsFromMetadata(activeMediaController?.metadata)
                }
            } else if (state?.state == PlaybackState.STATE_STOPPED || state?.state == PlaybackState.STATE_PAUSED) {
                if (!isAudioActive) {
                    currentAlbumColors = null
                    currentAppliedPackage = null
                    if (overrideEmotionColors == null) {
                        handler.post { listeners.forEach { it.onColorsChanged(null) } }
                    }
                }
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (visualizer != null) return

        try {
            // Attach to session 0 (system mix)
            visualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1] // Max capture size
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null) return
                        
                        var totalMag = 0f
                        var bassMag = 0f
                        
                        val n = fft.size
                        for (i in 0 until n / 2) {
                            val r = fft[2 * i]
                            val i_comp = fft[2 * i + 1]
                            val mag = Math.hypot(r.toDouble(), i_comp.toDouble()).toFloat()
                            totalMag += mag
                            
                            if (i < 10) bassMag += mag
                        }
                        
                        val intensity = totalMag / (n / 2)
                        val bass = bassMag / 10f

                        // Audio Activity & Speech Detection
                        if (intensity > 0.04f) {
                            lastAudioActivityTime = System.currentTimeMillis()
                            if (!isAudioActive) {
                                isAudioActive = true
                            }

                            // Robust Attribution:
                            // 1. If user is currently looking at a non-launcher app on screen, that app ALWAYS takes priority!
                            // 2. Otherwise (user on home screen), check if active media session is truly in STATE_PLAYING.
                            // 3. If media session is idle/stale, fall back to lastActiveAiPackage.
                            val isMediaPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
                            val isScreenApp = lastForegroundPackage != null && 
                                             !lastForegroundPackage!!.contains("launcher") && 
                                             lastForegroundPackage != "com.android.systemui"

                            val pkgToApply = when {
                                isScreenApp -> lastForegroundPackage
                                isMediaPlaying -> activeMediaController?.packageName ?: lastActiveAiPackage
                                else -> lastActiveAiPackage ?: lastForegroundPackage ?: activeMediaController?.packageName
                            }

                            if (pkgToApply != null && pkgToApply != "com.ghost.api" && pkgToApply != currentAppliedPackage) {
                                applyPackageColor(pkgToApply)
                            }
                        } else if (isAudioActive && (System.currentTimeMillis() - lastAudioActivityTime > 3000)) {
                            isAudioActive = false
                            currentAppliedPackage = null
                            val isMediaPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
                            if (!isMediaPlaying) {
                                currentAlbumColors = null
                                if (overrideEmotionColors == null) {
                                    handler.post { listeners.forEach { it.onColorsChanged(null) } }
                                }
                            }
                        }
                        
                        listeners.forEach { it.onAudioData(ByteArray(0), fft, intensity, bass) }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
            }
            
            // Setup Media Session Listener
            mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listenerComponent = ComponentName(context, GemmaNotificationListener::class.java)
            try {
                mediaSessionManager?.addOnActiveSessionsChangedListener(activeSessionsListener, listenerComponent)
                updateActiveMediaController(mediaSessionManager?.getActiveSessions(listenerComponent))
            } catch (e: SecurityException) {
                Timber.w("MediaSession access denied - NotificationListener not enabled?")
            }
            
            Timber.i("SystemVisualizer initialized successfully.")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize SystemVisualizer")
        }
    }

    private fun updateActiveMediaController(controllers: List<MediaController>?) {
        activeMediaController?.unregisterCallback(mediaControllerCallback)
        
        activeMediaController = controllers?.firstOrNull { 
            it.playbackState?.state == PlaybackState.STATE_PLAYING 
        } ?: controllers?.firstOrNull()

        activeMediaController?.registerCallback(mediaControllerCallback)
        extractColorsFromMetadata(activeMediaController?.metadata)
    }

    private fun extractColorsFromMetadata(metadata: MediaMetadata?) {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) 
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            
        if (bitmap == null) {
            val isPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
            val pkg = activeMediaController?.packageName
            if (isPlaying && pkg != null && pkg != "com.ghost.api") {
                applyPackageColor(pkg)
                return
            }
            if (!isAudioActive) {
                currentAlbumColors = null
                currentAppliedPackage = null
                listeners.forEach { it.onColorsChanged(null) }
            }
            return
        }

        extractPaletteFromBitmap(bitmap)
    }

    private fun extractPaletteFromBitmap(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            if (palette != null) {
                val dominant = palette.getDominantColor(0)
                val vibrant = palette.getVibrantColor(0)
                val muted = palette.getMutedColor(0)
                val darkVibrant = palette.getDarkVibrantColor(0)
                val lightVibrant = palette.getLightVibrantColor(0)
                
                // Select best non-black primary color
                val primary = when {
                    vibrant != 0 && !isTooDark(vibrant) && !isTooLight(vibrant) -> vibrant
                    dominant != 0 && !isTooDark(dominant) && !isTooLight(dominant) -> dominant
                    lightVibrant != 0 && !isTooDark(lightVibrant) -> lightVibrant
                    muted != 0 && !isTooDark(muted) -> muted
                    dominant != 0 -> dominant
                    else -> colorFallback(0)
                }

                // Construct a 5-color array to match our visualizer arrays
                val extracted = intArrayOf(
                    primary,
                    if (vibrant != 0) vibrant else if (lightVibrant != 0) lightVibrant else primary,
                    if (muted != 0) muted else primary,
                    if (darkVibrant != 0) darkVibrant else primary,
                    if (lightVibrant != 0) lightVibrant else primary
                )
                currentAlbumColors = extracted
                if (overrideEmotionColors == null) {
                    handler.post { listeners.forEach { it.onColorsChanged(extracted) } }
                }
            }
        }
    }

    fun onForegroundAppChanged(packageName: String) {
        if (packageName == "com.ghost.api") return
        val isLauncher = packageName.contains("launcher") || packageName == "com.android.systemui"
        if (!isLauncher) {
            lastForegroundPackage = packageName
            if (findBrandPalette(packageName) != null) {
                lastActiveAiPackage = packageName
            }
        }

        // If audio is actively playing, immediately adopt this foreground app's palette
        if (isAudioActive && !isLauncher) {
            applyPackageColor(packageName)
        }
    }

    fun applyPackageColor(packageName: String) {
        currentAppliedPackage = packageName
        val brandPalette = findBrandPalette(packageName)

        if (brandPalette != null) {
            currentAlbumColors = brandPalette
            if (overrideEmotionColors == null) {
                handler.post { listeners.forEach { it.onColorsChanged(brandPalette) } }
            }
            Timber.d("SystemVisualizer: Applied curated AI brand palette for $packageName")
        } else {
            extractPaletteFromAppIcon(packageName)
        }
    }

    private fun extractPaletteFromAppIcon(packageName: String) {
        try {
            val pm = appContext?.packageManager ?: return
            val icon = pm.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(icon)
            if (bitmap != null) {
                extractPaletteFromBitmap(bitmap)
                Timber.d("SystemVisualizer: Extracted agnostic app icon palette for $packageName")
            }
        } catch (e: Exception) {
            Timber.w("SystemVisualizer: Could not extract icon palette for $packageName: ${e.message}")
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        return try {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth.coerceIn(48, 128) else 96
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight.coerceIn(48, 128) else 96
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun buildPaletteFromColor(baseColor: Int): IntArray {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        val c1 = baseColor
        val hsv2 = hsv.clone().apply { this[2] = (this[2] * 0.85f).coerceIn(0f, 1f) }
        val c2 = Color.HSVToColor(hsv2)
        val hsv3 = hsv.clone().apply { this[2] = (this[2] * 0.65f).coerceIn(0f, 1f) }
        val c3 = Color.HSVToColor(hsv3)
        val hsv4 = hsv.clone().apply { this[2] = (this[2] * 0.45f).coerceIn(0f, 1f) }
        val c4 = Color.HSVToColor(hsv4)
        val hsv5 = hsv.clone().apply { this[1] = (this[1] * 0.60f).coerceIn(0f, 1f) }
        val c5 = Color.HSVToColor(hsv5)
        return intArrayOf(c1, c2, c3, c4, c5)
    }

    private fun isTooDark(color: Int): Boolean {
        if (color == 0) return true
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return lum < 0.12
    }

    private fun isTooLight(color: Int): Boolean {
        if (color == 0) return false
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return lum > 0.88
    }

    fun pushEmotionColor(emoji: String) {
        val baseColor = when (emoji) {
            "😡", "😠", "🤬", "🛑", "❗" -> Color.parseColor("#FF1744") // Red
            "💙", "🧊", "❄️", "💧", "🔵" -> Color.parseColor("#00E5FF") // Cyan/Blue
            "💚", "🌿", "🔋", "🤢", "🟢" -> Color.parseColor("#00E676") // Green
            "💛", "☀️", "🌟", "⚡", "🟡" -> Color.parseColor("#FFEA00") // Yellow
            "💜", "🔮", "😈", "☂️", "🟣" -> Color.parseColor("#D500F9") // Purple
            "🩷", "🌸", "💕", "🧠" -> Color.parseColor("#F50057") // Pink
            "🧡", "🔥", "🦊", "🎃", "🟠" -> Color.parseColor("#FF9100") // Orange
            "🤍", "☁️", "👻", "💀", "⚪" -> Color.parseColor("#FFFFFF") // White
            else -> Color.parseColor("#A78BFA") // Default Purple
        }
        
        overrideEmotionColors = buildPaletteFromColor(baseColor)
        listeners.forEach { it.onColorsChanged(overrideEmotionColors) }
        
        // Revert to album art / app color after 2 seconds
        handler.removeCallbacks(clearEmotionRunnable)
        handler.postDelayed(clearEmotionRunnable, 2000)
    }

    private fun colorFallback(index: Int): Int {
        val defaultColors = intArrayOf(
            Color.parseColor("#A78BFA"), // 0: Purple
            Color.parseColor("#4285F4"), // 1: Blue
            Color.parseColor("#EA4335"), // 2: Red
            Color.parseColor("#FBBC05"), // 3: Yellow
            Color.parseColor("#34A853")  // 4: Green
        )
        return defaultColors[index % defaultColors.size]
    }

    fun start() {
        if (!isEnabled && listeners.isNotEmpty()) {
            try {
                visualizer?.enabled = true
                isEnabled = true
            } catch (e: Exception) {}
        }
    }

    fun stop() {
        if (isEnabled && listeners.isEmpty()) {
            try {
                visualizer?.enabled = false
                isEnabled = false
            } catch (e: Exception) {}
        }
    }

    fun addListener(listener: AudioListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            // Immediately dispatch current colors
            listener.onColorsChanged(overrideEmotionColors ?: currentAlbumColors)
        }
        if (listeners.isNotEmpty()) start()
    }

    fun removeListener(listener: AudioListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) stop()
    }

    fun release() {
        visualizer?.release()
        visualizer = null
        isEnabled = false
        mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        activeMediaController?.unregisterCallback(mediaControllerCallback)
        listeners.clear()
    }
}
