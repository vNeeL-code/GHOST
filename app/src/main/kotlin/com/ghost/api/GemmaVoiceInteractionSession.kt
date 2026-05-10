package com.ghost.api

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

class GemmaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private var inputView: com.ghost.api.ui.OverlayInputView? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        
        // This is called when the user triggers the assistant (Long-press home / Hey Google)
        
        // Create the overlay UI
        val view = com.ghost.api.ui.OverlayInputView(context) { query ->
            // On Send:
            Toast.makeText(context, "Processing...", Toast.LENGTH_SHORT).show()

            // Send query to GemmaService
            val intent = Intent(context, GemmaService::class.java).apply {
                action = Constants.ACTION_QUERY
                putExtra(Constants.EXTRA_QUERY, query)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            finish() // Close overlay
        }

        // Set the content view of the session window
        inputView = view
        setContentView(view)
        view.focusInput()
    }

    override fun onHide() {
        super.onHide()
        // Cleanup speech recognizer
        inputView?.cleanup()
        inputView = null
    }
}
