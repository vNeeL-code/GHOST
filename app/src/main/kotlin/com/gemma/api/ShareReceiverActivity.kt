package com.gemma.api

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import timber.log.Timber
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

        try {
            // Read bitmap from URI
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                Timber.i("Image loaded: ${bitmap.width}x${bitmap.height}")

                // Store bitmap temporarily and send to service
                SharedMediaHolder.pendingBitmap = bitmap
                SharedMediaHolder.pendingType = "image"

                // Start service with share action
                val serviceIntent = Intent(this, GemmaService::class.java).apply {
                    action = "com.gemma.api.ACTION_SHARE_MEDIA"
                    putExtra("media_type", "image")
                }
                startService(serviceIntent)

                Toast.makeText(this, "Image shared with Gemma", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle shared image")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        finish()
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
            action = Constants.ACTION_QUERY
            putExtra(Constants.EXTRA_QUERY, "Shared text:\n$sharedText\n\nWhat would you like me to do with this?")
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
}

/**
 * Temporary holder for shared media between Activity and Service.
 * Using object singleton since we can't pass Bitmap through Intent extras reliably.
 */
object SharedMediaHolder {
    var pendingBitmap: Bitmap? = null
    var pendingType: String? = null

    fun clear() {
        pendingBitmap?.recycle()
        pendingBitmap = null
        pendingType = null
    }
}
