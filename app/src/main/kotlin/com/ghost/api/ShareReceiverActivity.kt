package com.ghost.api

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
        val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
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

                val imagesDir = File(filesDir, "chat_images").apply { mkdirs() }
                val persistentFile = File(imagesDir, "shared_${System.currentTimeMillis()}.jpg")

                inputStream.use { input ->
                    java.io.FileOutputStream(persistentFile).use { output ->
                        input.copyTo(output)
                    }
                }

                Timber.i("Image streamed to disk: ${persistentFile.length()} bytes at ${persistentFile.absolutePath}")

                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)

                // Store global state
                SharedMediaHolder.pendingImagePath = persistentFile.absolutePath
                SharedMediaHolder.pendingType = "image"
                SharedMediaHolder.pendingQuery = sharedText

                // Start service — use startService() to avoid foreground type re-validation on Android 14+
                // The service is already running as foreground from its own onCreate.
                val serviceIntent = Intent(this, GemmaService::class.java).apply {
                    action = "com.ghost.api.ACTION_SHARE_MEDIA"
                    putExtra("media_type", "image")
                    putExtra("image_path", persistentFile.absolutePath)
                    if (!sharedText.isNullOrEmpty()) {
                        putExtra("query", sharedText)
                    }
                }
                try {
                    startService(serviceIntent)
                } catch (e: IllegalStateException) {
                    // Fallback: if background start restricted, try foreground
                    startForegroundService(serviceIntent)
                }

                runOnUiThread {
                    Toast.makeText(this, if (!sharedText.isNullOrEmpty()) "Analyzing shared image..." else "Image shared with Gemma ✨", Toast.LENGTH_SHORT).show()
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
            action = "com.ghost.api.ACTION_QUERY"
            putExtra("query", "Shared text:\n$sharedText\n\nWhat would you like me to do with this?")
        }
        startService(serviceIntent)

        Toast.makeText(this, "Text shared with Gemma", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun handleVideo() {
        Toast.makeText(this, "Video sharing not yet supported", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun handleAudio() {
        Toast.makeText(this, "Audio sharing not yet supported", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun handlePdf() {
        Toast.makeText(this, "PDF sharing not yet supported", Toast.LENGTH_SHORT).show()
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
    var pendingImagePath: String? = null // Path based sharing
    var pendingType: String? = null
    var pendingQuery: String? = null

    fun clear() {
        // No bitmap to recycle
        pendingImagePath = null
        pendingType = null
        pendingQuery = null
    }
}
