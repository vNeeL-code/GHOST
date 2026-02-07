package com.gemma.api

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import timber.log.Timber
import java.io.File
import java.io.InputStream

/**
 * Transparent activity that receives shared content from Android share menu.
 * Passes images/files to GemmaService for processing.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            Intent.ACTION_SEND -> handleSend()
            else -> {
                Timber.w("Unknown action: ${intent?.action}")
                finish()
            }
        }
    }

    private fun handleSend() {
        val mimeType = intent.type ?: ""
        Timber.i("Share received: $mimeType")

        when {
            mimeType.startsWith("image/") -> handleImage()
            mimeType.startsWith("text/") -> handleText()
            mimeType.startsWith("video/") -> handleVideo()
            mimeType.startsWith("audio/") -> handleAudio()
            mimeType == "application/pdf" -> handlePdf()
            else -> {
                Toast.makeText(this, "Unsupported type: $mimeType", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun handleImage() {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null) {
            Toast.makeText(this, "No image received", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Move I/O to background thread to prevent ANR/Crash on Main Thread
        kotlin.concurrent.thread {
            try {
                // STREAM COPY: Don't decode! Just copy bytes.
                val inputStream: InputStream? = contentResolver.openInputStream(uri)

                if (inputStream == null) {
                    runOnUiThread { 
                        Toast.makeText(this, "Unreadable content", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@thread
                }

                val cacheFile = File(cacheDir, "shared_image_${System.currentTimeMillis()}.jpg")
                cacheFile.deleteOnExit() // Audit Fix: Prevent disk bloat

                inputStream.use { input ->
                    java.io.FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }

                Timber.i("Image streamed to disk: ${cacheFile.length()} bytes")

                // Store global state
                SharedMediaHolder.pendingImagePath = cacheFile.absolutePath
                SharedMediaHolder.pendingType = "image"


                // Start service — use startService() to avoid foreground type re-validation on Android 14+
                // The service is already running as foreground from its own onCreate.
                val serviceIntent = Intent(this, GemmaService::class.java).apply {
                    action = "com.gemma.api.ACTION_SHARE_MEDIA"
                    putExtra("media_type", "image")
                    putExtra("image_path", cacheFile.absolutePath)
                }
                try {
                    startService(serviceIntent)
                } catch (e: IllegalStateException) {
                    // Fallback: if background start restricted, try foreground
                    startForegroundService(serviceIntent)
                }

                runOnUiThread {
                    Toast.makeText(this, "Image shared with Gemma", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Timber.e(e, "Share failed")
                runOnUiThread {
                    Toast.makeText(this, "Share Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    // ... (rest of methods unchanged) -> RESTORING HANDLERS

    private fun handleText() {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText.isNullOrEmpty()) {
            Toast.makeText(this, "No text received", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Timber.i("Text shared: ${sharedText.take(50)}...")

        // Send directly as a query
        val serviceIntent = Intent(this, GemmaService::class.java).apply {
            action = "com.gemma.api.ACTION_QUERY_TEXT" // Fixed action name if needed, or stick to generic
            // Wait, previous code used Constants.ACTION_QUERY. 
            // Constants might be missing context? 
            action = "com.gemma.api.ACTION_QUERY" 
            putExtra("query", "Shared text:\n$sharedText\n\nWhat would you like me to do with this?")
        }
        startService(serviceIntent)

        Toast.makeText(this, "Text shared with Gemma", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun handleVideo() {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null) {
            Toast.makeText(this, "No video received", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // For now, just acknowledge - video processing is complex
        Toast.makeText(this, "Video shared - extracting frames not yet implemented", Toast.LENGTH_LONG).show()
        Timber.i("Video URI: $uri")
        finish()
    }

    private fun handleAudio() {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null) {
            Toast.makeText(this, "No audio received", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // For now, just acknowledge - need to decode audio to PCM
        Toast.makeText(this, "Audio shared - transcription not yet implemented", Toast.LENGTH_LONG).show()
        Timber.i("Audio URI: $uri")
        finish()
    }

    private fun handlePdf() {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null) {
            Toast.makeText(this, "No PDF received", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // For now, just acknowledge
        Toast.makeText(this, "PDF shared - text extraction not yet implemented", Toast.LENGTH_LONG).show()
        Timber.i("PDF URI: $uri")
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't clear SharedMediaHolder here — service reads it via intent extra (reliable)
        // but clearing the singleton too early can race with onStartCommand in same process
        Timber.d("ShareReceiverActivity destroyed")
    }



}

/**
 * Temporary holder for shared media between Activity and Service.
 */
object SharedMediaHolder {
    // FIX: Memory Leak - Use WeakReference or ensure manual clearing. 
    // Since we pass it quickly, just making it nullable and ensuring clear() is called is enough.
    // But audit flagged static reference.
    // pendingBitmap removed to fix Memory Leak (Audit Round 4)
    var pendingImagePath: String? = null // Path based sharing
    var pendingType: String? = null

    fun clear() {
        // No bitmap to recycle
        pendingImagePath = null
        pendingType = null
    }
}
