package com.ghost.api.services

import android.app.KeyguardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*

/**
 * State-Aware TTS Manager
 *
 * Intelligently manages speech output based on device context:
 * - Pocket Mode: Suppresses speech when phone is in pocket (proximity/light sensors)
 * - Screen State: Adjusts behavior based on locked/unlocked
 * - Audio Routing: Detects headphones/bluetooth for private speech
 * - Smart Queue: Manages speech queue to avoid interrupting media
 */
class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val LUX_POCKET_THRESHOLD = 10f
    }

    private var tts: TextToSpeech? = null
    private var isReady = false

    // Device state managers
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    // Pocket/proximity detection
    @Volatile private var isInPocket = false
    @Volatile private var proximityNear = false
    @Volatile private var ambientLightLow = false

    // Speech queue for deferred playback
    private val deferredQueue = ConcurrentLinkedQueue<DeferredSpeech>()
    private var lastSpeechTime = 0L

    // Configuration
    var pocketModeEnabled = true
    var respectScreenLock = true
    var privateAudioOnly = false // Only speak if headphones/bluetooth connected

    data class DeferredSpeech(
        val text: String,
        val priority: Priority = Priority.NORMAL,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class Priority {
        LOW,      // Can be dropped if queue is full
        NORMAL,   // Standard speech
        HIGH,     // Urgent (alarms, critical notifications)
        IMMEDIATE // Bypass all checks (emergency)
    }

    enum class DeviceState {
        ACTIVE,           // Screen on, unlocked, user engaged
        LOCKED_VISIBLE,   // Screen on but locked (AOD, lock screen)
        SCREEN_OFF,       // Screen completely off
        POCKET,           // In pocket (proximity near + low light)
        PRIVATE_AUDIO     // Headphones/bluetooth connected
    }

    // Preferred engines in order (Samsung TTS tends to sound smoother on Samsung devices)
    private val preferredEngines = listOf(
        "com.samsung.SMT",           // Samsung TTS
        "com.google.android.tts",    // Google TTS
        "com.svox.pico"              // Pico TTS (fallback)
    )

    init {
        // Offload heavy discovery to background to prevent service-boot ANR
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            initWithPreferredEngine()
            // Start pocket detection sensors (sensors are fine on main/io)
            withContext(Dispatchers.Main) {
                initProximitySensor()
            }
        }
    }

    private fun initProximitySensor() {
        val sm = sensorManager ?: return

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_PROXIMITY -> {
                        val maxRange = event.sensor.maximumRange
                        proximityNear = event.values[0] < maxRange
                        updatePocketState()
                    }
                    Sensor.TYPE_LIGHT -> {
                        ambientLightLow = event.values[0] < LUX_POCKET_THRESHOLD // Less than 10 lux = very dark
                        updatePocketState()
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
            Timber.d("TTS: Proximity sensor registered")
        }
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
            Timber.d("TTS: Light sensor registered")
        }
    }

    private fun updatePocketState() {
        val wasPocket = isInPocket
        // In pocket = proximity near AND light very low AND screen off
        isInPocket = proximityNear && ambientLightLow && !powerManager.isInteractive
        if (wasPocket != isInPocket) {
            Timber.d("TTS: Pocket state changed: $isInPocket")
            if (!isInPocket) {
                // Came out of pocket - play deferred speech
                playDeferredQueue()
            }
        }
    }

    private fun initWithPreferredEngine() {
        val throwawayTts = TextToSpeech(context, null)
        val availableEngines = throwawayTts.engines.map { it.name }
        throwawayTts.shutdown() // CRITICAL: Stop memory leak
        
        Timber.d("Available TTS engines: $availableEngines")

        val engineToUse = preferredEngines.firstOrNull { it in availableEngines }

        if (engineToUse != null) {
            Timber.i("Using TTS engine: $engineToUse")
            tts = TextToSpeech(context, this, engineToUse)
        } else {
            Timber.i("Using default TTS engine")
            tts = TextToSpeech(context, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US) // US English for consistency
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Timber.w("Language not supported, falling back to default")
                tts?.setLanguage(Locale.getDefault())
            }

            // Tune voice parameters for smoother output
            tts?.setSpeechRate(0.95f)  // Slightly slower than default (1.0)
            tts?.setPitch(1.0f)        // Normal pitch

            // Try to find a high-quality voice
            selectBestVoice()

            isReady = true
            Timber.i("TTS initialized successfully")
        } else {
            Timber.e("TTS initialization failed with status: $status")
        }
    }

    private fun selectBestVoice() {
        try {
            val voices = tts?.voices ?: return

            // Prefer network/high-quality voices over local/low-quality
            val preferredVoice = voices
                .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                .filter { it.quality >= Voice.QUALITY_HIGH }
                .minByOrNull { it.latency }

            if (preferredVoice != null) {
                tts?.voice = preferredVoice
                Timber.i("Selected voice: ${preferredVoice.name} (quality: ${preferredVoice.quality})")
            } else {
                // Fallback: any English voice that's not network-required
                val fallbackVoice = voices
                    .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                    .firstOrNull()
                if (fallbackVoice != null) {
                    tts?.voice = fallbackVoice
                    Timber.i("Selected fallback voice: ${fallbackVoice.name}")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to select voice, using default")
        }
    }

    /**
     * Get current device state for TTS decisions
     *
     * Priority order (highest to lowest):
     * 1. PRIVATE_AUDIO - Headphones/BT connected = ALWAYS safe to speak
     * 2. POCKET - In pocket without headphones = defer
     * 3. SCREEN_OFF/LOCKED - Various screen states
     * 4. ACTIVE - Normal operation
     */
    fun getDeviceState(): DeviceState {
        return when {
            // CRITICAL: Check headphones FIRST - they override pocket/screen state
            // User's workflow: message -> pocket -> listen through headphones
            hasPrivateAudio() -> DeviceState.PRIVATE_AUDIO

            // Only suppress for pocket if NO headphones connected
            isInPocket && pocketModeEnabled -> DeviceState.POCKET
            !powerManager.isInteractive -> DeviceState.SCREEN_OFF
            keyguardManager.isKeyguardLocked -> DeviceState.LOCKED_VISIBLE
            else -> DeviceState.ACTIVE
        }
    }

    /**
     * Check if external audio output is connected
     *
     * Includes headphones, bluetooth speakers, USB audio, docks, etc.
     * When ANY external audio is connected, TTS should always speak
     * (user's main workflow: message -> pocket -> listen through headphones/speakers)
     */
    private fun hasPrivateAudio(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||      // USB audio interface
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||   // USB accessory mode
                device.type == AudioDeviceInfo.TYPE_DOCK ||            // Docking station
                device.type == AudioDeviceInfo.TYPE_LINE_ANALOG ||     // Aux out to speaker
                device.type == AudioDeviceInfo.TYPE_HDMI ||            // HDMI audio out
                device.type == AudioDeviceInfo.TYPE_AUX_LINE           // Aux line out
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
        }
    }

    /**
     * Smart speak - considers device state before speaking and handles long text chunking
     *
     * @param text Text to speak
     * @param priority Priority level (affects queueing behavior)
     * @return true if speech was initiated, false if deferred or suppressed
     */
    fun smartSpeak(text: String, priority: Priority = Priority.NORMAL): Boolean {
        if (!isReady || text.isBlank()) return false

        val maxLen = TextToSpeech.getMaxSpeechInputLength()
        if (text.length > maxLen) {
            val chunks = chunkText(text, maxLen)
            var lastResult = false
            for ((index, chunk) in chunks.withIndex()) {
                val p = if (index == 0) priority else Priority.NORMAL
                lastResult = doSmartSpeak(chunk, p, isChunk = index > 0)
            }
            return lastResult
        } else {
            return doSmartSpeak(text, priority, isChunk = false)
        }
    }

    private fun chunkText(text: String, maxLen: Int): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            if (start + maxLen >= text.length) {
                chunks.add(text.substring(start))
                break
            }
            var end = start + maxLen
            var boundary = text.lastIndexOfAny(charArrayOf('.', '!', '?'), end)
            if (boundary <= start) {
                boundary = text.lastIndexOf(' ', end)
                if (boundary <= start) {
                    boundary = end
                }
            } else {
                boundary += 1
            }
            chunks.add(text.substring(start, boundary).trim())
            start = boundary
        }
        return chunks
    }

    private fun doSmartSpeak(text: String, priority: Priority, isChunk: Boolean): Boolean {
        if (!isReady || text.isBlank()) return false

        val state = getDeviceState()
        Timber.d("TTS smartSpeak: state=$state, priority=$priority, text=${text.take(30)}...")

        // IMMEDIATE priority bypasses all checks
        if (priority == Priority.IMMEDIATE) {
            val queueMode = if (isChunk) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
            tts?.speak(text, queueMode, null, "GemmaImmediate")
            return true
        }

        return when (state) {
            DeviceState.POCKET -> {
                // In pocket - defer speech
                Timber.d("TTS: Deferring speech (pocket mode)")
                deferSpeech(text, priority)
                false
            }
            DeviceState.SCREEN_OFF -> {
                if (respectScreenLock) {
                    // Screen off - defer unless high priority
                    if (priority == Priority.HIGH) {
                        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "GemmaHigh")
                        true
                    } else {
                        Timber.d("TTS: Deferring speech (screen off)")
                        deferSpeech(text, priority)
                        false
                    }
                } else {
                    tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "GemmaResponse")
                    true
                }
            }
            DeviceState.LOCKED_VISIBLE -> {
                // Lock screen visible - speak at lower volume for HIGH, defer NORMAL/LOW
                if (priority >= Priority.NORMAL) {
                    tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "GemmaLocked")
                    true
                } else {
                    deferSpeech(text, priority)
                    false
                }
            }
            DeviceState.PRIVATE_AUDIO -> {
                // Headphones connected - always safe to speak
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "GemmaPrivate")
                true
            }
            DeviceState.ACTIVE -> {
                // Active use - check if we should interrupt media
                val queueMode = if (isChunk || (audioManager.isMusicActive && priority < Priority.HIGH)) {
                    TextToSpeech.QUEUE_ADD
                } else {
                    TextToSpeech.QUEUE_FLUSH
                }
                tts?.speak(text, queueMode, null, if (queueMode == TextToSpeech.QUEUE_ADD) "GemmaQueued" else "GemmaResponse")
                lastSpeechTime = System.currentTimeMillis()
                true
            }
        }
    }

    private fun deferSpeech(text: String, priority: Priority) {
        // Limit queue size - drop LOW priority if full
        if (deferredQueue.size >= 10) {
            if (priority == Priority.LOW) {
                Timber.d("TTS: Dropping LOW priority speech (queue full)")
                return
            }
            // Remove oldest LOW priority item
            deferredQueue.removeIf { it.priority == Priority.LOW }
        }
        deferredQueue.add(DeferredSpeech(text, priority))
        Timber.d("TTS: Speech deferred, queue size: ${deferredQueue.size}")
    }

    private fun playDeferredQueue() {
        if (deferredQueue.isEmpty()) return

        Timber.i("TTS: Playing ${deferredQueue.size} deferred speeches")
        val now = System.currentTimeMillis()

        // Play only speeches from last 5 minutes, newest first
        val recent = deferredQueue
            .filter { now - it.timestamp < 5 * 60 * 1000 }
            .sortedByDescending { it.priority }

        deferredQueue.clear()

        if (recent.isEmpty()) {
            Timber.d("TTS: All deferred speeches expired")
            return
        }

        // Announce we have messages
        if (recent.size > 1) {
            tts?.speak("You have ${recent.size} messages.", TextToSpeech.QUEUE_ADD, null, "GemmaAnnounce")
        }

        // Play most important ones (max 3)
        recent.take(3).forEach { speech ->
            tts?.speak(speech.text, TextToSpeech.QUEUE_ADD, null, "GemmaDeferred_${speech.timestamp}")
        }
    }

    /**
     * Original speak method - now delegates to smartSpeak for backwards compatibility
     */
    fun speak(text: String) {
        if (isReady && text.isNotBlank()) {
            smartSpeak(text, Priority.NORMAL)
        }
    }

    /**
     * Force speak regardless of state (for testing or urgent messages)
     */
    fun forceSpeak(text: String) {
        if (isReady && text.isNotBlank()) {
            smartSpeak(text, Priority.IMMEDIATE)
        }
    }

    fun speakQueued(text: String) {
        if (isReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "GemmaResponse_${System.currentTimeMillis()}")
        }
    }

    /**
     * Check if it's currently appropriate to speak
     */
    fun canSpeakNow(): Boolean {
        val state = getDeviceState()
        return state != DeviceState.POCKET &&
               (state == DeviceState.ACTIVE || state == DeviceState.PRIVATE_AUDIO)
    }

    /**
     * Get reason why speech might be deferred
     */
    fun getSpeechStatus(): String {
        return when (getDeviceState()) {
            DeviceState.POCKET -> "Phone in pocket - speech deferred"
            DeviceState.SCREEN_OFF -> "Screen off - speech queued"
            DeviceState.LOCKED_VISIBLE -> "Screen locked - normal priority"
            DeviceState.PRIVATE_AUDIO -> "Headphones connected - private mode"
            DeviceState.ACTIVE -> "Active - ready to speak"
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true
}
