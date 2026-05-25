package com.ghost.api.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import timber.log.Timber

/**
 * Handles hardware UI toggle intents from the MainActivity dropdown.
 * Routes actions to the system wallpaper/edge lighting services or provides fallback feedback.
 */
class HardwareToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        when (action) {
            "com.ghost.api.ACTION_SET_TRANSPARENT_WALLPAPER" -> {
                Timber.i("Hardware Toggle: Transparent Wallpaper requested")
                Toast.makeText(context, "Setting Transparent Wallpaper...", Toast.LENGTH_SHORT).show()
                // TODO: Interface with live wallpaper service
            }
            "com.ghost.api.ACTION_TOGGLE_EDGE_LIGHTS" -> {
                Timber.i("Hardware Toggle: Edge Lights requested")
                Toast.makeText(context, "Toggling Edge Lights...", Toast.LENGTH_SHORT).show()
                // TODO: Interface with edge lighting overlay
            }
            "com.ghost.api.ACTION_SET_MILKDROP_WALLPAPER" -> {
                Timber.i("Hardware Toggle: Milkdrop Wallpaper requested")
                Toast.makeText(context, "Setting Milkdrop Wallpaper...", Toast.LENGTH_SHORT).show()
                // TODO: Interface with Milkdrop engine
            }
        }
    }
}
