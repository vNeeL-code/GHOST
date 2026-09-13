package com.ghost.api.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ghost.api.Constants
import com.ghost.api.GemmaService
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.TimeUnit

class DiaryWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.i("📔 DiaryWorker: doWork started")
        
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.PREF_AUTONOMOUS_DIARY, true)) {
            Timber.i("📔 DiaryWorker skipped: Disabled by user settings toggle")
            return Result.success()
        }

        val cadence = prefs.getString(Constants.PREF_DIARY_CADENCE, "12") ?: "12"
        if (cadence == "OFF") {
            Timber.i("📔 DiaryWorker skipped: Cadence set to OFF")
            return Result.success()
        }

        val lastRun = prefs.getLong("last_diary_execution_time", 0L)
        val cadenceHours = when (cadence) {
            "1" -> 1L
            "3" -> 3L
            "6" -> 6L
            "24" -> 24L
            else -> 12L
        }
        val minElapsedMs = (cadenceHours * 3600 * 1000L) - (10 * 60 * 1000L)
        val elapsed = System.currentTimeMillis() - lastRun
        if (lastRun > 0 && elapsed < minElapsedMs) {
            Timber.i("📔 DiaryWorker skipped: Only ${elapsed / 60000} mins elapsed (cadence is ${cadenceHours}h)")
            return Result.success()
        }

        val service = GemmaService.instance
        if (service == null) {
            Timber.w("📔 DiaryWorker skipped: GemmaService is not active")
            return Result.success()
        }

        return try {
            val success = withTimeoutOrNull(5 * 60 * 1000L) {
                service.runDiaryCycleSuspend()
            }
            
            if (success == true) {
                prefs.edit().putLong("last_diary_execution_time", System.currentTimeMillis()).apply()
                Timber.i("📔 DiaryWorker: doWork successful (next cycle in ${cadenceHours}h)")
                Result.success()
            } else {
                Timber.w("📔 DiaryWorker: doWork completed without new entry")
                Result.success()
            }
        } catch (e: Exception) {
            Timber.e(e, "📔 DiaryWorker: doWork threw an exception")
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "com.ghost.api.DIARY_PERIODIC_WORK"

        fun schedule(context: Context) {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val cadence = prefs.getString(Constants.PREF_DIARY_CADENCE, "12") ?: "12"
            
            if (cadence == "OFF" || !prefs.getBoolean(Constants.PREF_AUTONOMOUS_DIARY, true)) {
                cancel(context)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = when (cadence) {
                "1" -> {
                    PeriodicWorkRequestBuilder<DiaryWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build()
                }
                "3" -> {
                    PeriodicWorkRequestBuilder<DiaryWorker>(3, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build()
                }
                "6" -> {
                    PeriodicWorkRequestBuilder<DiaryWorker>(6, TimeUnit.HOURS, 20, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build()
                }
                "24" -> {
                    val now = java.time.ZonedDateTime.now()
                    val nextMidnight = (if (now.hour >= 0) now.plusDays(1) else now)
                        .withHour(0).withMinute(0).withSecond(0).withNano(0)
                    val initialDelayMs = java.time.Duration.between(now, nextMidnight).toMillis().coerceAtLeast(0)
                    PeriodicWorkRequestBuilder<DiaryWorker>(24, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
                        .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                        .setConstraints(constraints)
                        .build()
                }
                else -> { // "12" Default (Noon & Midnight)
                    val now = java.time.ZonedDateTime.now()
                    val nextNoon = now.withHour(12).withMinute(0).withSecond(0).withNano(0)
                    val nextMidnight = (if (now.hour >= 12) now.plusDays(1) else now)
                        .withHour(0).withMinute(0).withSecond(0).withNano(0)

                    val nextTarget = when {
                        now.isBefore(nextNoon) -> nextNoon
                        now.isBefore(nextMidnight) -> nextMidnight
                        else -> nextNoon.plusDays(1)
                    }

                    val initialDelayMs = java.time.Duration.between(now, nextTarget).toMillis().coerceAtLeast(0)
                    PeriodicWorkRequestBuilder<DiaryWorker>(12, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
                        .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                        .setConstraints(constraints)
                        .build()
                }
            }

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Timber.i("📔 DiaryWorker: Scheduled with cadence: $cadence hours")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.i("📔 DiaryWorker: Periodic work cancelled")
        }
    }
}
