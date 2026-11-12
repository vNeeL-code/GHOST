package com.ghost.api

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.widget.Toast

class GemmaVoiceInteractionService : VoiceInteractionService()

class GemmaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return GemmaVoiceInteractionSession(this)
    }
}

class GemmaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private var inputView: com.ghost.api.ui.OverlayInputView? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val view = com.ghost.api.ui.OverlayInputView(context) { query ->
            Toast.makeText(context, "Processing...", Toast.LENGTH_SHORT).show()

            val intent = Intent(context, GemmaService::class.java).apply {
                action = Constants.ACTION_QUERY
                putExtra(Constants.EXTRA_QUERY, query)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            finish()
        }

        inputView = view
        setContentView(view)
        view.focusInput()
    }

    override fun onHide() {
        super.onHide()
        inputView?.cleanup()
        inputView = null
    }
}

