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
                com.ghost.api.ui.EdgeLightsManager.toggle(context)
                val status = if (com.ghost.api.ui.EdgeLightsManager.isShowing) "ON" else "OFF"
                Toast.makeText(context, "Edge Lights $status", Toast.LENGTH_SHORT).show()
            }
            "com.ghost.api.ACTION_SET_MILKDROP_WALLPAPER" -> {
                Timber.i("Hardware Toggle: Milkdrop Wallpaper requested")
                Toast.makeText(context, "Launching Wallpaper Chooser...", Toast.LENGTH_SHORT).show()
                val wpIntent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, 
                        android.content.ComponentName(context, com.ghost.api.ui.MilkdropWallpaperService::class.java))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(wpIntent)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to launch wallpaper chooser")
                    Toast.makeText(context, "Error launching wallpaper chooser", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
