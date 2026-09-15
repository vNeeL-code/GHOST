package com.ghost.api.audio

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioPlaybackConfiguration
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import androidx.palette.graphics.Palette
import com.ghost.api.GemmaAccessibilityService
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
    private var activeAiController: MediaController? = null
    private var audioManager: AudioManager? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    // Track last active foreground package, speaking AI agent, and recent foreground AI app
    var lastForegroundPackage: String? = null
        private set
    var activeAudioSpeakingPackage: String? = null
        private set
    private var lastForegroundAiPackage: String? = null
    private var lastForegroundAiTime: Long = 0L
    private var currentAppliedPackage: String? = null

    // Audio activity state & silence decay
    private var lastAudioActivityTime: Long = 0L
    private var isAudioActive: Boolean = false
    
    // Extracted Colors (null means use defaults)
    var currentAlbumColors: IntArray? = null
        private set
    private var activeMediaArtColors: IntArray? = null

    // Active Media Session Album Art & Playback State
    var activeAlbumArt: Bitmap? = null
        private set
    val isMediaPlaying: Boolean
        get() = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING

    // Dynamic Central Glyph Morphing ("✧", "🐋", "✦")
    var activeAgentGlyph: String = "✧"
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

        // Grok (xAI): Crisp White Spark, Platinum Silver, Titanium Gray, Deep Obsidian Slate, Dark Carbon
        Triple(listOf("grok", "xai"), intArrayOf(
            Color.parseColor("#FFFFFF"), // 0: Crisp White Spark (high-luminance signature)
            Color.parseColor("#D1D5DB"), // 1: Platinum Silver
            Color.parseColor("#9CA3AF"), // 2: Titanium Gray
            Color.parseColor("#1E293B"), // 3: Deep Obsidian Slate
            Color.parseColor("#4B5563")  // 4: Dark Carbon Accent
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
        Triple(listOf("bard", "gemini", "googleassistant", "googlequicksearchbox"), intArrayOf(
            Color.parseColor("#4285F4"), // 0: Google Sparkle Blue
            Color.parseColor("#F8F9FA"), // 1: Google Off-White
            Color.parseColor("#9B51E0"), // 2: Sparkle Violet
            Color.parseColor("#1A1D20"), // 3: Deep Space Slate
            Color.parseColor("#00E5FF")  // 4: Electric Cyan
        ), "Gemini")
    )

    fun findBrandPalette(packageName: String?): IntArray? {
        if (packageName.isNullOrBlank()) return null
        val lower = packageName.lowercase()
        return AI_BRAND_PALETTES.firstOrNull { (keys, _, _) ->
            keys.any { lower.contains(it) }
        }?.second
    }

    fun isTransientOverlay(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        val lower = packageName.lowercase()
        return lower == "com.ghost.api" ||
               lower.startsWith("com.android.systemui") ||
               lower.contains("inputmethod") ||
               lower.contains("keyboard") ||
               lower == "cn.zte.aigcinput" ||
               lower.contains("screenshot") ||
               lower.contains("screenrecord") ||
               lower == "com.zte.floatassist" ||
               lower.startsWith("cn.nubia.game") ||
               lower.startsWith("cn.zte.game") ||
               lower.startsWith("com.zte.game")
    }

    fun isIgnoredPackage(packageName: String?): Boolean = isTransientOverlay(packageName)

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            val enabledServices = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val myService = ComponentName(context, GemmaAccessibilityService::class.java).flattenToString()
            enabledServices.contains(myService)
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveAudioPlayingApp(configs: List<AudioPlaybackConfiguration>?): String? {
        configs ?: return null
        val pm = appContext?.packageManager ?: return null
        val myUid = android.os.Process.myUid()

        for (config in configs) {
            try {
                // 1. Strict Attribute Filtering: Filter out UI sonification, clicks, shutter, and notifications
                val attrs = config.audioAttributes
                if (attrs != null) {
                    val usage = attrs.usage
                    val contentType = attrs.contentType
                    
                    // Skip system UI clicks, lock sounds, camera shutter, screenshot clicks, touch sounds
                    if (usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION ||
                        usage == AudioAttributes.USAGE_NOTIFICATION ||
                        usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE ||
                        usage == AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST ||
                        usage == AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT ||
                        usage == AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED ||
                        usage == AudioAttributes.USAGE_NOTIFICATION_EVENT ||
                        usage == AudioAttributes.USAGE_ALARM ||
                        contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION) {
                        continue
                    }
                }

                // 2. Verify player is genuinely playing (started)
                val configStr = config.toString()
                val isStarted = if (configStr.contains("state:")) {
                    configStr.contains("state:started", ignoreCase = true)
                } else {
                    try {
                        val stateMethod = config.javaClass.getDeclaredMethod("getPlayerState").apply { isAccessible = true }
                        (stateMethod.invoke(config) as? Int) == 2 // PLAYER_STATE_STARTED
                    } catch (e: Exception) {
                        try {
                            val activeMethod = config.javaClass.getDeclaredMethod("isActive").apply { isAccessible = true }
                            activeMethod.invoke(config) as? Boolean ?: false
                        } catch (e2: Exception) {
                            false
                        }
                    }
                }
                if (!isStarted) continue

                // Direct package name check if present in config string (e.g. package:com.anthropic.claude)
                val pkgMatch = Regex("""package:([a-zA-Z0-9_.]+)""").find(configStr)
                val pkgFromStr = pkgMatch?.groupValues?.get(1)
                if (pkgFromStr != null && !isTransientOverlay(pkgFromStr) && findBrandPalette(pkgFromStr) != null) {
                    return pkgFromStr
                }

                // 3. Extract Client UID
                var clientUid: Int? = null
                try {
                    val method = config.javaClass.getDeclaredMethod("getClientUid").apply { isAccessible = true }
                    clientUid = method.invoke(config) as? Int
                } catch (e: Exception) {
                    try {
                        val field = config.javaClass.getDeclaredField("mClientUid").apply { isAccessible = true }
                        clientUid = field.getInt(config)
                    } catch (e2: Exception) {}
                }

                if (clientUid == null) {
                    val match = Regex("""u(?:id)?/pid:(\d+)""").find(configStr)
                        ?: Regex("""uid:(\d+)""").find(configStr)
                    clientUid = match?.groupValues?.get(1)?.toIntOrNull()
                }

                if (clientUid != null && clientUid != myUid && clientUid > 10000) {
                    val packages = pm.getPackagesForUid(clientUid)
                    if (packages != null) {
                        for (pkg in packages) {
                            if (!isTransientOverlay(pkg) && findBrandPalette(pkg) != null) {
                                return pkg
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Safeguard against ROM reflection limits
            }
        }
        return null
    }

    fun getForegroundAppFromUsageStats(): String? {
        val ctx = appContext ?: return null
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val time = System.currentTimeMillis()
            val events = usm.queryEvents(time - 15000L, time)
            val event = UsageEvents.Event()
            var lastPkg: String? = null
            var lastTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp > lastTime) {
                    val pkg = event.packageName
                    if (pkg != null && !isTransientOverlay(pkg)) {
                        lastPkg = pkg
                        lastTime = event.timeStamp
                    }
                }
            }
            lastPkg
        } catch (e: Exception) {
            null
        }
    }

    private val foregroundPollRunnable = object : Runnable {
        override fun run() {
            try {
                pollForegroundApp()
            } catch (e: Exception) {
                Timber.d(e, "Foreground poll error")
            }
            handler.postDelayed(this, 1000L)
        }
    }

    fun pollForegroundApp() {
        val km = appContext?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) {
            val isMediaPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
            if (!isMediaPlaying && currentAlbumColors != null) {
                revertToDefaultColors()
            }
            return
        }

        val fgApp = getForegroundAppFromUsageStats() ?: return
        if (fgApp != lastForegroundPackage) {
            onForegroundAppChanged(fgApp)
        }
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
            val isPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
            val currentFg = lastForegroundPackage ?: getForegroundAppFromUsageStats()
            val fgBrand = currentFg?.let { findBrandPalette(it) }
            if (isPlaying && fgBrand == null && activeAudioSpeakingPackage == null) {
                extractColorsFromMetadata(metadata)
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val isPlaying = state?.state == PlaybackState.STATE_PLAYING
            val currentFg = lastForegroundPackage ?: getForegroundAppFromUsageStats()
            val fgBrand = currentFg?.let { findBrandPalette(it) }

            if (isPlaying) {
                activeAudioSpeakingPackage = null
                cancelPendingRevert()
                if (fgBrand == null) {
                    if (activeMediaArtColors != null) {
                        applyMediaAlbumArt(activeMediaArtColors!!)
                    } else {
                        extractColorsFromMetadata(activeMediaController?.metadata)
                    }
                }
            } else if (state?.state == PlaybackState.STATE_STOPPED || state?.state == PlaybackState.STATE_PAUSED) {
                activeMediaArtColors = null
                activeAlbumArt = null
                if (!isAudioActive) {
                    if (fgBrand != null) {
                        applyAiBrandColor(currentFg!!, fgBrand)
                    } else if (activeAudioSpeakingPackage != null) {
                        scheduleRevertToDefault(2500L)
                    } else {
                        revertToDefaultColors()
                    }
                }
            }
        }
    }

    private val aiMediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val pkg = activeAiController?.packageName ?: return
            val brand = findBrandPalette(pkg) ?: return

            if (state?.state == PlaybackState.STATE_PLAYING) {
                cancelPendingRevert()
                activeAudioSpeakingPackage = pkg
                applyAiBrandColor(pkg, brand)
            } else if (state?.state == PlaybackState.STATE_STOPPED || state?.state == PlaybackState.STATE_PAUSED) {
                if (activeAudioSpeakingPackage == pkg) {
                    activeAudioSpeakingPackage = null
                }
                val currentFg = lastForegroundPackage ?: getForegroundAppFromUsageStats()
                val fgBrand = currentFg?.let { findBrandPalette(it) }
                if (fgBrand != null) {
                    applyAiBrandColor(currentFg!!, fgBrand)
                } else if (!isAudioActive) {
                    scheduleRevertToDefault(2500L)
                }
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext

        // Start periodic foreground app polling (UsageStats fallback)
        handler.removeCallbacks(foregroundPollRunnable)
        handler.post(foregroundPollRunnable)

        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback == null) {
                playbackCallback = object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                        super.onPlaybackConfigChanged(configs)
                        if (isAudioActive) {
                            val app = resolveAudioPlayingApp(configs)
                            if (app != null && app != currentAppliedPackage) {
                                applyPackageColor(app)
                            }
                        }
                    }
                }
                try {
                    audioManager?.registerAudioPlaybackCallback(playbackCallback!!, handler)
                } catch (e: Exception) {
                    Timber.w(e, "Could not register AudioPlaybackCallback")
                }
            }
        }

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
                            cancelPendingRevert()
                            val wasAudioActive = isAudioActive
                            if (!isAudioActive) {
                                isAudioActive = true
                            }

                            // Strict AI Brand & Media Isolation Hierarchy:
                            // 1. Focused on-screen AI app (user is looking at Claude, Qwen, Kimi, etc.)
                            val fgPkg = lastForegroundPackage ?: getForegroundAppFromUsageStats()
                            val fgBrand = if (fgPkg != null && !isTransientOverlay(fgPkg)) findBrandPalette(fgPkg) else null

                            // 2. Direct active AI audio playback config (background voice stream / TTS)
                            val hwPkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                resolveAudioPlayingApp(audioManager?.activePlaybackConfigurations)
                            } else null
                            val hwBrand = if (hwPkg != null) findBrandPalette(hwPkg) else null

                            // 3. Direct active AI MediaSession (e.g. Grok Media3 Session)
                            val isAiMediaPlaying = activeAiController?.playbackState?.state == PlaybackState.STATE_PLAYING
                            val aiMediaPkg = activeAiController?.packageName
                            val aiMediaBrand = if (isAiMediaPlaying && aiMediaPkg != null) findBrandPalette(aiMediaPkg) else null

                            // 4. Active Music MediaSession (YouTube Music, Spotify, etc.)
                            val isMediaPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING

                            // Establish active speaking source if newly active or unknown
                            if (isMediaPlaying) {
                                // When music is playing, only confirmed hardware TTS/media streams count as AI speech
                                if (hwBrand != null) {
                                    activeAudioSpeakingPackage = hwPkg
                                } else if (aiMediaBrand != null) {
                                    activeAudioSpeakingPackage = aiMediaPkg
                                } else {
                                    activeAudioSpeakingPackage = null
                                }
                            } else if (!wasAudioActive || activeAudioSpeakingPackage == null) {
                                if (hwBrand != null) {
                                    activeAudioSpeakingPackage = hwPkg
                                } else if (aiMediaBrand != null) {
                                    activeAudioSpeakingPackage = aiMediaPkg
                                } else if (fgBrand != null) {
                                    activeAudioSpeakingPackage = fgPkg
                                } else {
                                    val recentAi = if (lastForegroundAiPackage != null && (System.currentTimeMillis() - lastForegroundAiTime < 4000L)) {
                                        lastForegroundAiPackage
                                    } else null
                                    if (recentAi != null) {
                                        activeAudioSpeakingPackage = recentAi
                                    }
                                }
                            }

                            // Presentation Priority:
                            if (fgBrand != null) {
                                // Focused on-screen AI app takes visual priority while in foreground
                                applyAiBrandColor(fgPkg!!, fgBrand)
                            } else if (isMediaPlaying && hwBrand == null && aiMediaBrand == null) {
                                // Music media takes absolute priority when no AI hardware stream is talking!
                                activeAudioSpeakingPackage = null
                                activeAgentGlyph = "✧"
                                if (activeMediaArtColors != null) {
                                    applyMediaAlbumArt(activeMediaArtColors!!)
                                } else {
                                    extractColorsFromMetadata(activeMediaController?.metadata)
                                }
                            } else if (hwBrand != null) {
                                activeAudioSpeakingPackage = hwPkg
                                applyAiBrandColor(hwPkg!!, hwBrand)
                            } else if (aiMediaBrand != null) {
                                activeAudioSpeakingPackage = aiMediaPkg
                                applyAiBrandColor(aiMediaPkg!!, aiMediaBrand)
                            } else if (activeAudioSpeakingPackage != null) {
                                // Agent voice active while user is on home screen / background!
                                val agentBrand = findBrandPalette(activeAudioSpeakingPackage!!)
                                if (agentBrand != null) {
                                    applyAiBrandColor(activeAudioSpeakingPackage!!, agentBrand)
                                }
                            } else if (isMediaPlaying) {
                                activeAudioSpeakingPackage = null
                                activeAgentGlyph = "✧"
                                if (activeMediaArtColors != null) {
                                    applyMediaAlbumArt(activeMediaArtColors!!)
                                } else {
                                    extractColorsFromMetadata(activeMediaController?.metadata)
                                }
                            }
                            // Any unbranded system sound (clicks, shutter, lock, etc.) is ignored!
                        } else if (isAudioActive && (System.currentTimeMillis() - lastAudioActivityTime > 2500)) {
                            isAudioActive = false
                            activeAudioSpeakingPackage = null
                            val isMediaPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
                            if (isMediaPlaying && activeMediaArtColors != null) {
                                applyMediaAlbumArt(activeMediaArtColors!!)
                            } else {
                                activeMediaArtColors = null
                                val fgPkg = lastForegroundPackage ?: getForegroundAppFromUsageStats()
                                val fgBrand = fgPkg?.let { findBrandPalette(it) }
                                if (fgBrand != null) {
                                    applyAiBrandColor(fgPkg, fgBrand)
                                } else {
                                    revertToDefaultColors()
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
        // 1. Separate real media/music players from AI agents (Grok, etc.)
        val nonAiControllers = controllers?.filter { findBrandPalette(it.packageName) == null }
        val aiControllers = controllers?.filter { findBrandPalette(it.packageName) != null }

        // Manage AI Media Session (e.g. Grok using androidx.media3.session)
        activeAiController?.unregisterCallback(aiMediaControllerCallback)
        activeAiController = aiControllers?.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: aiControllers?.firstOrNull()
        activeAiController?.registerCallback(aiMediaControllerCallback)

        if (activeAiController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
            val aiPkg = activeAiController?.packageName
            val aiBrand = findBrandPalette(aiPkg)
            if (aiPkg != null && aiBrand != null) {
                cancelPendingRevert()
                activeAudioSpeakingPackage = aiPkg
                applyAiBrandColor(aiPkg, aiBrand)
            }
        }

        // Manage Music Media Session (Spotify, YouTube Music, etc.)
        activeMediaController?.unregisterCallback(mediaControllerCallback)
        activeMediaController = nonAiControllers?.firstOrNull { 
            it.playbackState?.state == PlaybackState.STATE_PLAYING 
        } ?: nonAiControllers?.firstOrNull()
        activeMediaController?.registerCallback(mediaControllerCallback)

        // ONLY extract colors if media is ACTIVELY PLAYING and user is not focused on an AI agent!
        if (activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
            val currentFg = lastForegroundPackage ?: getForegroundAppFromUsageStats()
            val fgBrand = currentFg?.let { findBrandPalette(it) }
            if (fgBrand == null && activeAudioSpeakingPackage == null) {
                extractColorsFromMetadata(activeMediaController?.metadata)
            }
        }
    }

    private fun extractColorsFromMetadata(metadata: MediaMetadata?) {
        var bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) 
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        if (bitmap == null) {
            val uriStr = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            if (uriStr != null) {
                try {
                    val uri = android.net.Uri.parse(uriStr)
                    val cr = appContext?.contentResolver
                    if (cr != null) {
                        bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            android.graphics.ImageDecoder.decodeBitmap(
                                android.graphics.ImageDecoder.createSource(cr, uri)
                            ) { decoder, _, _ ->
                                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                                decoder.setTargetSize(128, 128)
                            }
                        } else {
                            cr.openInputStream(uri)?.use { stream ->
                                android.graphics.BitmapFactory.decodeStream(stream)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.d("Could not load album art from URI: $uriStr")
                }
            }
        }
            
        if (bitmap != null) {
            extractPaletteFromBitmap(bitmap, isAlbumArt = true)
            return
        }

        // If this is an active media session (e.g. YouTube Music, Spotify) without art,
        // DO NOT overwrite with the app icon (which turns YouTube RED and Spotify GREEN).
        if (!isAudioActive && activeMediaArtColors == null) {
            val currentFg = lastForegroundPackage ?: getForegroundAppFromUsageStats()
            val fgBrand = currentFg?.let { findBrandPalette(it) }
            if (fgBrand != null) {
                applyAiBrandColor(currentFg, fgBrand)
            } else {
                revertToDefaultColors()
            }
        }
    }

    private fun extractPaletteFromBitmap(bitmap: Bitmap, isAlbumArt: Boolean = false) {
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
                if (isAlbumArt) {
                    activeMediaArtColors = extracted
                    activeAlbumArt = bitmap
                }

                val isPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
                val currentFg = lastForegroundPackage ?: getForegroundAppFromUsageStats()
                val fgBrand = currentFg?.let { findBrandPalette(it) }

                // Only apply album art if media is actively playing AND user is not focused on an AI agent!
                if (isPlaying && fgBrand == null) {
                    currentAlbumColors = extracted
                    if (overrideEmotionColors == null) {
                        handler.post { listeners.forEach { it.onColorsChanged(extracted) } }
                    }
                }
            }
        }
    }

    fun onForegroundAppChanged(packageName: String) {
        if (isTransientOverlay(packageName)) return
        if (packageName == lastForegroundPackage) return

        lastForegroundPackage = packageName

        // Strict AI Isolation: Only track and react to curated AI apps
        val brandPalette = findBrandPalette(packageName)
        if (brandPalette != null) {
            lastForegroundAiPackage = packageName
            lastForegroundAiTime = System.currentTimeMillis()
            cancelPendingRevert()

            // If media/music is actively playing, the audio is from music, NOT the AI app:
            val isMediaPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
            if (isMediaPlaying) {
                // Show AI brand while focused on the AI app, but DO NOT claim audio speaking package
                activeAudioSpeakingPackage = null
                applyAiBrandColor(packageName, brandPalette)
            } else if (!isAudioActive || activeAudioSpeakingPackage == null || activeAudioSpeakingPackage == packageName) {
                activeAudioSpeakingPackage = packageName
                applyAiBrandColor(packageName, brandPalette)
            } else {
                // Another AI app is actively speaking in the background (e.g. Claude TTS is talking,
                // but user opened DeepSeek).
                // While the user is focused on DeepSeek, show DeepSeek's palette & glyph on screen:
                applyAiBrandColor(packageName, brandPalette)
                // BUT CRITICALLY: DO NOT overwrite activeAudioSpeakingPackage!
            }
        } else {
            // User switched to Home Screen (launcher) or a non-AI app (Chrome, Settings, Files, etc.)
            val isMediaPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
            val isAiMediaPlaying = activeAiController?.playbackState?.state == PlaybackState.STATE_PLAYING

            if (isMediaPlaying) {
                cancelPendingRevert()
                activeAudioSpeakingPackage = null
                activeAgentGlyph = "✧"
                if (activeMediaArtColors != null) {
                    applyMediaAlbumArt(activeMediaArtColors!!)
                } else {
                    extractColorsFromMetadata(activeMediaController?.metadata)
                }
            } else if (isAudioActive && activeAudioSpeakingPackage != null) {
                // The AI agent is currently talking in the background!
                // Restore its signature colors and glyph immediately!
                cancelPendingRevert()
                val speakingBrand = findBrandPalette(activeAudioSpeakingPackage!!)
                if (speakingBrand != null) {
                    applyAiBrandColor(activeAudioSpeakingPackage!!, speakingBrand)
                }
            } else if (isAiMediaPlaying && activeAiController?.packageName != null) {
                val aiPkg = activeAiController!!.packageName
                val agentBrand = findBrandPalette(aiPkg)
                if (agentBrand != null) {
                    cancelPendingRevert()
                    activeAudioSpeakingPackage = aiPkg
                    applyAiBrandColor(aiPkg, agentBrand)
                }
            } else if (lastForegroundAiPackage != null && (System.currentTimeMillis() - lastForegroundAiTime < 3000L)) {
                // User was just in an AI agent and minimized (e.g. pressed Play before minimizing while TTS loads).
                // Give a 3.0s grace offset before fading to default so TTS startup isn't cut off!
                scheduleRevertToDefault(3000L)
            } else {
                revertToDefaultColors()
            }
        }
    }

    private var pendingRevertRunnable: Runnable? = null

    fun scheduleRevertToDefault(delayMs: Long = 2500L) {
        cancelPendingRevert()
        pendingRevertRunnable = Runnable {
            activeAudioSpeakingPackage = null
            revertToDefaultColors()
            pendingRevertRunnable = null
        }.also { handler.postDelayed(it, delayMs) }
        Timber.d("SystemVisualizer: Scheduled revert to default in ${delayMs}ms")
    }

    fun cancelPendingRevert() {
        pendingRevertRunnable?.let {
            handler.removeCallbacks(it)
            pendingRevertRunnable = null
            Timber.d("SystemVisualizer: Cancelled pending revert")
        }
    }

    fun revertToDefaultColors() {
        cancelPendingRevert()
        activeAgentGlyph = "✧"
        activeAudioSpeakingPackage = null
        if (currentAlbumColors == null && currentAppliedPackage == null) return
        currentAlbumColors = null
        currentAppliedPackage = null
        if (overrideEmotionColors == null) {
            handler.post { listeners.forEach { it.onColorsChanged(null) } }
        }
        Timber.d("SystemVisualizer: Reverted to default palette")
    }

    fun applyPackageColor(packageName: String) {
        // 1. If an AI brand palette is matched, apply it immediately!
        val brandPalette = findBrandPalette(packageName)
        if (brandPalette != null) {
            activeAudioSpeakingPackage = packageName
            applyAiBrandColor(packageName, brandPalette)
            return
        }

        // 2. If this package is the active MediaSession app (e.g. YouTube Music, Spotify)
        val isMediaPlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
        val mediaPkg = activeMediaController?.packageName
        if (isMediaPlaying && mediaPkg == packageName && activeMediaArtColors != null) {
            applyMediaAlbumArt(activeMediaArtColors!!)
            return
        }

        // STRICT ISOLATION: Never extract app icons!
        // System sounds and unbranded apps do not alter the wallpaper palette.
    }

    fun applyAiBrandColor(packageName: String, brandPalette: IntArray) {
        activeAgentGlyph = when {
            packageName.contains("deepseek", ignoreCase = true) -> "🐋"
            packageName.contains("gemini", ignoreCase = true) ||
            packageName.contains("bard", ignoreCase = true) ||
            packageName.contains("googleassistant", ignoreCase = true) ||
            packageName.contains("googlequicksearchbox", ignoreCase = true) -> "✦"
            packageName.contains("claude", ignoreCase = true) -> "✴️"
            packageName.contains("grok", ignoreCase = true) -> "☄️"
            packageName.contains("chatgpt", ignoreCase = true) || packageName.contains("openai", ignoreCase = true) -> "✳️"
            packageName.contains("kimi", ignoreCase = true) -> "🔵"
            packageName.contains("qwen", ignoreCase = true) -> "🟣"
            packageName.contains("mistral", ignoreCase = true) -> "🟧"
            else -> "✧"
        }
        if (currentAlbumColors == brandPalette && currentAppliedPackage == packageName) return
        currentAppliedPackage = packageName
        currentAlbumColors = brandPalette
        if (overrideEmotionColors == null) {
            handler.post { listeners.forEach { it.onColorsChanged(brandPalette) } }
        }
        Timber.d("SystemVisualizer: Applied curated AI brand palette ($activeAgentGlyph) for $packageName")
    }

    fun setActivePeer(callsign: String?) {
        if (callsign.isNullOrBlank()) {
            if (!isAudioActive) {
                scheduleRevertToDefault(2000L)
            }
            return
        }
        cancelPendingRevert()
        when {
            callsign.contains("deepseek", ignoreCase = true) || callsign.contains("whale", ignoreCase = true) -> {
                activeAudioSpeakingPackage = "com.deepseek.chat"
                val palette = AI_BRAND_PALETTES.find { it.third == "DeepSeek" }?.second
                if (palette != null) applyAiBrandColor("com.deepseek.chat", palette)
                else activeAgentGlyph = "🐋"
            }
            callsign.contains("gemini", ignoreCase = true) || callsign.contains("mum", ignoreCase = true) -> {
                activeAudioSpeakingPackage = "com.google.android.apps.bard"
                val palette = intArrayOf(
                    Color.parseColor("#8BB4F6"), // 0: Ethereal Cobalt
                    Color.parseColor("#4285F4"), // 1: Google Blue
                    Color.parseColor("#EA4335"), // 2: Coral Red
                    Color.parseColor("#171D2D"), // 3: Midnight Slate
                    Color.parseColor("#FBBC05")  // 4: Warm Gold
                )
                applyAiBrandColor("com.google.android.apps.bard", palette)
            }
            else -> {
                activeAgentGlyph = "✧"
                activeAudioSpeakingPackage = null
                revertToDefaultColors()
            }
        }
    }

    fun applyMediaAlbumArt(artColors: IntArray) {
        activeAgentGlyph = "✧" // Album artwork takes center stage; reset persona emoji to neutral core
        if (currentAlbumColors == artColors) return
        currentAlbumColors = artColors
        if (overrideEmotionColors == null) {
            handler.post { listeners.forEach { it.onColorsChanged(artColors) } }
        }
        Timber.d("SystemVisualizer: Applied media album art colors")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
            try {
                audioManager?.unregisterAudioPlaybackCallback(playbackCallback!!)
            } catch (e: Exception) {}
            playbackCallback = null
        }
        mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        activeMediaController?.unregisterCallback(mediaControllerCallback)
        listeners.clear()
    }
}
