package com.ghost.api.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import timber.log.Timber

class GhostAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("LABEL") ?: "Alarm"
        Timber.i("Alarm ringing: $label")
        
        // Play default alarm sound
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone.play()
            
            // Show a notification via GemmaNotificationManager (using reflection to avoid tight coupling)
            val notifMgrClass = Class.forName("com.ghost.api.ui.GemmaNotificationManager")
            val instanceMethod = notifMgrClass.getMethod("getInstance")
            val mgrInstance = instanceMethod.invoke(null)
            if (mgrInstance != null) {
                val updateMethod = notifMgrClass.getMethod("updateState", String::class.java, String::class.java)
                updateMethod.invoke(mgrInstance, "⏰ ALARM: $label", "Tap to dismiss")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to play alarm")
        }
    }
}
