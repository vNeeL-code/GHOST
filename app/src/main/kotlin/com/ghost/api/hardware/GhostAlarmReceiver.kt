package com.ghost.api.hardware

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import timber.log.Timber

class GhostAlarmReceiver : BroadcastReceiver() {
    
    companion object {
        private var currentRingtone: Ringtone? = null
        const val ACTION_DISMISS = "com.ghost.api.ACTION_ALARM_DISMISS"
        const val ACTION_SNOOZE = "com.ghost.api.ACTION_ALARM_SNOOZE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val label = intent.getStringExtra("LABEL") ?: "Alarm"
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (action) {
            ACTION_DISMISS -> {
                Timber.i("Alarm dismissed")
                currentRingtone?.stop()
                currentRingtone = null
                notifManager.cancel(1001)
            }
            ACTION_SNOOZE -> {
                Timber.i("Alarm snoozed")
                currentRingtone?.stop()
                currentRingtone = null
                notifManager.cancel(1001)
                
                // Set another alarm for 5 minutes later
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val snoozeIntent = Intent(context, GhostAlarmReceiver::class.java).apply {
                    putExtra("LABEL", label)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 1001, snoozeIntent, 
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                
                val triggerTime = System.currentTimeMillis() + (5 * 60 * 1000)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                    } else {
                        alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                    }
                } catch (e: SecurityException) {
                    Timber.w(e, "setExactAndAllowWhileIdle failed in snooze; falling back to setAndAllowWhileIdle")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                    }
                }
            }
            else -> {
                // Firing the alarm
                Timber.i("Alarm ringing: $label")
                
                try {
                    currentRingtone?.stop()
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    currentRingtone = RingtoneManager.getRingtone(context, uri)
                    currentRingtone?.play()
                    
                    // Create Notification Channel
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel = NotificationChannel(
                            "ghost_alarm", 
                            "Ghost Alarms", 
                            NotificationManager.IMPORTANCE_HIGH
                        )
                        notifManager.createNotificationChannel(channel)
                    }

                    // Intents for actions
                    val dismissIntent = Intent(context, GhostAlarmReceiver::class.java).apply {
                        this.action = ACTION_DISMISS
                    }
                    val dismissPending = PendingIntent.getBroadcast(
                        context, 0, dismissIntent, 
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val snoozeIntent = Intent(context, GhostAlarmReceiver::class.java).apply {
                        this.action = ACTION_SNOOZE
                        putExtra("LABEL", label)
                    }
                    val snoozePending = PendingIntent.getBroadcast(
                        context, 1, snoozeIntent, 
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val notification = NotificationCompat.Builder(context, "ghost_alarm")
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentTitle("⏰ $label")
                        .setContentText("Alarm is ringing!")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPending)
                        .addAction(android.R.drawable.ic_popup_sync, "Snooze (5m)", snoozePending)
                        .setOngoing(true)
                        .build()

                    notifManager.notify(1001, notification)
                    
                } catch (e: Exception) {
                    Timber.e(e, "Failed to play alarm")
                }
            }
        }
    }
}
