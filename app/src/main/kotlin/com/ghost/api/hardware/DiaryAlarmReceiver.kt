package com.ghost.api.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber
import com.ghost.api.GemmaService

/**
 * High-reliability exact alarm receiver for autonomous diary reflections.
 * Wakes up via AlarmManager.RTC_WAKEUP, guarantees execution through Doze mode,
 * and delegates to GemmaService.startDiaryCycle().
 */
class DiaryAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.ghost.api.ACTION_DIARY_CYCLE") {
            Timber.i("📔 Diary Alarm triggered by AlarmManager (RTC_WAKEUP)")
            val service = GemmaService.instance
            if (service != null) {
                service.startDiaryCycle()
            } else {
                // Ensure service is running if it was killed by aggressive OS background management
                Timber.w("📔 GemmaService instance was null on alarm trigger — starting foreground service")
                val serviceIntent = Intent(context, GemmaService::class.java).apply {
                    action = "com.ghost.api.ACTION_START_SERVICE"
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
