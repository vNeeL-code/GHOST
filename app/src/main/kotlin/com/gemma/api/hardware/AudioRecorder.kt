package com.gemma.api.hardware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Records audio for Gemma's hearing capability
 * Outputs raw PCM samples for direct feeding to LiteRT-LM
 */
class AudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000  // Gemma 3n expects 16kHz
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Record audio for specified duration
     * @param durationSeconds How long to record (max 30 seconds)
     * @return ShortArray of PCM samples, or null on failure
     */
    suspend fun record(durationSeconds: Int): ShortArray? = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            Timber.w("No audio recording permission")
            return@withContext null
        }

        val duration = durationSeconds.coerceIn(1, 30)
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Timber.e("Invalid buffer size: $bufferSize")
            return@withContext null
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Timber.e("AudioRecord failed to initialize")
                return@withContext null
            }

            val totalSamples = SAMPLE_RATE * duration
            val samples = ShortArray(totalSamples)
            var samplesRead = 0

            isRecording = true
            audioRecord?.startRecording()
            Timber.i("Recording ${duration}s of audio...")

            val startTime = System.currentTimeMillis()
            val endTime = startTime + (duration * 1000)

            while (isRecording && samplesRead < totalSamples && System.currentTimeMillis() < endTime) {
                val remaining = totalSamples - samplesRead
                val toRead = minOf(bufferSize, remaining)
                val read = audioRecord?.read(samples, samplesRead, toRead) ?: 0

                if (read > 0) {
                    samplesRead += read
                } else if (read < 0) {
                    Timber.w("AudioRecord read error: $read")
                    break
                }
            }

            Timber.i("Recorded $samplesRead samples (${samplesRead / SAMPLE_RATE}s)")

            // Trim to actual samples read
            if (samplesRead < totalSamples) {
                samples.copyOf(samplesRead)
            } else {
                samples
            }

        } catch (e: SecurityException) {
            Timber.e(e, "Security exception recording audio")
            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to record audio")
            null
        } finally {
            stopRecording()
        }
    }

    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping audio record")
        }
        audioRecord = null
    }

    /**
     * Quick listen - record 3 seconds
     */
    suspend fun quickListen(): ShortArray? = record(3)

    /**
     * Extended listen - record up to 10 seconds
     */
    suspend fun extendedListen(): ShortArray? = record(10)
}
