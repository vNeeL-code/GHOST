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
 * Outputs WAV bytes (Header + PCM 16kHz Mono 16-bit)
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
     * @return ByteArray of WAV data, or null on failure
     */
    suspend fun record(durationSeconds: Int, rawPcm: Boolean = false): ByteArray? {
        if (!hasPermission()) {
            Timber.w("No audio recording permission")
            return null
        }
        
        val duration = durationSeconds.coerceIn(1, 30)
        // 16kHz Mono 16-bit PCM (Standard)
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        return withContext(Dispatchers.IO) {
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    maxOf(bufferSize * 2, SAMPLE_RATE * duration * 2) 
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Timber.e("AudioRecord failed to initialize")
                    return@withContext null
                }

                // Estimate samples needed
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
                // Read chunks to allow cancellation/updating
                val toRead = minOf(bufferSize / 2, remaining) 
                
                val read = audioRecord?.read(samples, samplesRead, toRead) ?: 0

                if (read > 0) {
                    samplesRead += read
                } else if (read < 0) {
                    Timber.w("AudioRecord read error: $read")
                    break
                }
            }

            Timber.i("Recorded $samplesRead samples")

            // Trim logic (PCM only)
             val pcmData = if (samplesRead < totalSamples) {
                samples.copyOf(samplesRead)
            } else {
                samples
            }
            
            // Convert ShortArray to ByteArray (Little Endian)
            val byteData = ByteArray(pcmData.size * 2)
            for (i in pcmData.indices) {
                byteData[i * 2] = (pcmData[i].toInt() and 0x00FF).toByte()
                byteData[i * 2 + 1] = ((pcmData[i].toInt() shr 8) and 0x00FF).toByte()
            }

            // Wrap in WAV if not requesting raw PCM
            return@withContext if (rawPcm) {
                byteData
            } else {
                this@AudioRecorder.addWavHeader(byteData)
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to record audio")
            null
        } finally {
            this@AudioRecorder.stopRecording()
        }
        }
    }

    // Add WAV Header suitable for 16kHz 16-bit Mono PCM
    private fun addWavHeader(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = SAMPLE_RATE * 16 * 1 / 8
        
        val header = ByteArray(44)
        
        // RIFF
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        
        // WAVE
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        
        // fmt 
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        
        header[20] = 1 // Format = PCM
        header[21] = 0
        
        header[22] = 1 // Channels = 1 (Mono)
        header[23] = 0
        
        header[24] = (SAMPLE_RATE and 0xff).toByte()
        header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
        header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
        header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
        
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        
        header[32] = 2 // BlockAlign
        header[33] = 0
        
        header[34] = 16 // BitsPerSample
        header[35] = 0
        
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        
        header[40] = (pcmData.size and 0xff).toByte()
        header[41] = ((pcmData.size shr 8) and 0xff).toByte()
        header[42] = ((pcmData.size shr 16) and 0xff).toByte()
        header[43] = ((pcmData.size shr 24) and 0xff).toByte()
        
        return header + pcmData
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
     * Quick listen - record 3 seconds (WAV)
     */
    suspend fun quickListen(): ByteArray? = record(3)

    /**
     * Extended listen - record up to 10 seconds (WAV)
     */
    suspend fun extendedListen(): ByteArray? = record(10)
}
