package com.gemma.api.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.gemma.api.GemmaService
import timber.log.Timber

/**
 * Handles notification action button clicks
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        intent ?: return

        when (intent.action) {
            "com.gemma.api.ACTION_COPY" -> {
                val text = intent.getStringExtra("text") ?: return
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Gemma Response", text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "✦ Copied to clipboard", Toast.LENGTH_SHORT).show()
                    Timber.d("Response copied via notification action")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to copy from notification action")
                }
            }
            "com.gemma.api.ACTION_TTS_REPLAY" -> {
                val text = intent.getStringExtra("text") ?: return
                Timber.d("TTS replay requested: ${text.take(30)}...")
                // Forward to GemmaService for TTS
                val serviceIntent = Intent(context, GemmaService::class.java).apply {
                    action = "com.gemma.api.ACTION_TTS_SPEAK"
                    putExtra("text", text)
                }
                try {
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start TTS service")
                }
            }
            "com.gemma.api.ACTION_SHOW_OVERLAY" -> {
                // Summon overlay for follow-up input
                Timber.d("Show overlay requested")
                val serviceIntent = Intent(context, GemmaService::class.java).apply {
                    action = "com.gemma.api.ACTION_SHOW_OVERLAY"
                }
                try {
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to show overlay")
                }
            }
            "com.gemma.api.ACTION_CONFIRM_TOOL", "com.gemma.api.ACTION_DENY_TOOL" -> {
                Timber.d("Confirmation action: ${intent.action}")
                val toolName = intent.getStringExtra("toolName") ?: return
                val isApproved = (intent.action == "com.gemma.api.ACTION_CONFIRM_TOOL")
                
                // Forward to Service
                val serviceIntent = Intent(context, GemmaService::class.java).apply {
                    action = intent.action
                    putExtra("toolName", toolName)
                }
                try {
                    context.startService(serviceIntent)
                    // Close notification panel
                    val closeIntent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                    context.sendBroadcast(closeIntent)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to forward confirmation to service")
                }
            }
        }
    }
}
