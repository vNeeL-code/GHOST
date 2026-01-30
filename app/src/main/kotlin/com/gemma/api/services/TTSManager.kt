package com.gemma.api.services

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import timber.log.Timber
import java.util.Locale

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false

    // Preferred engines in order (Samsung TTS tends to sound smoother on Samsung devices)
    private val preferredEngines = listOf(
        "com.samsung.SMT",           // Samsung TTS
        "com.google.android.tts",    // Google TTS
        "com.svox.pico"              // Pico TTS (fallback)
    )

    init {
        // Try preferred engines first
        initWithPreferredEngine()
    }

    private fun initWithPreferredEngine() {
        val availableEngines = TextToSpeech(context, null).engines.map { it.name }
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

    fun speak(text: String) {
        if (isReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "GemmaResponse")
        }
    }

    fun speakQueued(text: String) {
        if (isReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "GemmaResponse_${System.currentTimeMillis()}")
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
