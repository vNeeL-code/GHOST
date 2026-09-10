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

    // Track last active foreground package reported by accessibility
    var lastForegroundPackage: String? = null
        private set

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
                                if (currentAlbumColors == null) {
                                    val pkg = activeMediaController?.packageName ?: lastForegroundPackage
                                    if (pkg != null && pkg != "com.ghost.api") {
                                        applyPackageColor(pkg)
                                    }
                                }
                            }
                        } else if (isAudioActive && (System.currentTimeMillis() - lastAudioActivityTime > 3500)) {
                            isAudioActive = false
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
            val pkg = activeMediaController?.packageName
            if (pkg != null && pkg != "com.ghost.api") {
                applyPackageColor(pkg)
                return
            }
            currentAlbumColors = null
            listeners.forEach { it.onColorsChanged(null) }
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
        lastForegroundPackage = packageName
        val isMediaPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (isAudioActive && !isMediaPlaying) {
            applyPackageColor(packageName)
        }
    }

    fun applyPackageColor(packageName: String) {
        val lower = packageName.lowercase()
        val brandHex = when {
            lower.contains("claude") || lower.contains("anthropic") -> "#D97757" // Claude Terracotta
            lower.contains("chatgpt") || lower.contains("openai") -> "#10A37F" // ChatGPT Mint Green
            lower.contains("deepseek") -> "#4D6BFE" // DeepSeek Electric Whale Blue
            lower.contains("qwen") || lower.contains("tongyi") -> "#7C4DFF" // Qwen Royal Purple
            lower.contains("kimi") || lower.contains("moonshot") -> "#2B5CFF" // Kimi Moonlight Cobalt
            lower.contains("mistral") || lower.contains("lechat") -> "#FF7000" // Mistral Solar Flame Orange
            lower.contains("perplexity") -> "#20B2AA" // Perplexity Seafoam Teal
            lower.contains("grok") || lower.contains("xai") -> "#3F4E4F" // Grok Gunmetal Slate
            lower.contains("meta.ai") || lower.contains("llama") -> "#0081FB" // Meta Indigo-Blue
            lower.contains("copilot") -> "#6B46C1" // Copilot Violet
            lower.contains("bard") || lower.contains("gemini") -> "#4285F4" // Google Sparkle Blue
            else -> null
        }

        if (brandHex != null) {
            val baseColor = Color.parseColor(brandHex)
            val palette = buildPaletteFromColor(baseColor)
            currentAlbumColors = palette
            if (overrideEmotionColors == null) {
                handler.post { listeners.forEach { it.onColorsChanged(palette) } }
            }
            Timber.d("SystemVisualizer: Applied AI brand palette for $packageName ($brandHex)")
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
