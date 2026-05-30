package com.ghost.api.audio

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import androidx.palette.graphics.Palette
import com.ghost.api.GemmaNotificationListener
import timber.log.Timber

/**
 * Singleton manager that hooks into the global system audio mix (Session 0).
 * Captures FFT data and active Media colors, then notifies listeners.
 */
object SystemVisualizer {

    private var visualizer: Visualizer? = null
    private var isEnabled = false

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeMediaController: MediaController? = null
    
    // Extracted Colors (null means use defaults)
    var currentAlbumColors: IntArray? = null
        private set

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
    }

    fun init(context: Context) {
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
            it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING 
        } ?: controllers?.firstOrNull()

        activeMediaController?.registerCallback(mediaControllerCallback)
        extractColorsFromMetadata(activeMediaController?.metadata)
    }

    private fun extractColorsFromMetadata(metadata: MediaMetadata?) {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) 
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            
        if (bitmap == null) {
            currentAlbumColors = null
            listeners.forEach { it.onColorsChanged(null) }
            return
        }

        Palette.from(bitmap).generate { palette ->
            if (palette != null) {
                val dominant = palette.getDominantColor(0)
                val vibrant = palette.getVibrantColor(0)
                val muted = palette.getMutedColor(0)
                val darkVibrant = palette.getDarkVibrantColor(0)
                val lightVibrant = palette.getLightVibrantColor(0)
                
                // Construct a 5-color array to match our visualizer arrays
                val extracted = intArrayOf(
                    if (dominant != 0) dominant else colorFallback(0),
                    if (vibrant != 0) vibrant else if (dominant != 0) dominant else colorFallback(1),
                    if (muted != 0) muted else if (dominant != 0) dominant else colorFallback(2),
                    if (darkVibrant != 0) darkVibrant else if (dominant != 0) dominant else colorFallback(3),
                    if (lightVibrant != 0) lightVibrant else if (dominant != 0) dominant else colorFallback(4)
                )
                currentAlbumColors = extracted
                listeners.forEach { it.onColorsChanged(extracted) }
            }
        }
    }

    private fun colorFallback(index: Int): Int {
        val defaultColors = intArrayOf(
            android.graphics.Color.parseColor("#A78BFA"), // Purple
            android.graphics.Color.parseColor("#4285F4"), // Blue
            android.graphics.Color.parseColor("#EA4335"), // Red
            android.graphics.Color.parseColor("#FBBC05"), // Yellow
            android.graphics.Color.parseColor("#34A853")  // Green
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
            listener.onColorsChanged(currentAlbumColors)
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
