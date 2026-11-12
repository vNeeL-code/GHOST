package com.ghost.api.service

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import com.ghost.api.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extracted from `GemmaService.kt` (`DOC-3`):
 * Manages the 94% RAM Critical Suspension Safety Valve (`RAM_CRITICAL_UTILIZATION_THRESHOLD`)
 * and the 54% zRAM Auto-Reload Safety Valve (`RAM_RELOAD_UTILIZATION_THRESHOLD`),
 * protecting GHOST from OEM Low Memory Killer (LMK) / ZTE SPKL terminations while gaming.
 */
class GhostRamValveController(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    val isSuspendedDueToRam: AtomicBoolean,
    private val isInferencing: () -> Boolean,
    private val isSpeaking: () -> Boolean,
    private val isGemmaLoaded: () -> Boolean,
    private val isAppInForeground: () -> Boolean,
    private val onCheckpointRequested: suspend () -> Unit,
    private val onUnloadEngine: suspend () -> Unit,
    private val onEnsureEngineReady: suspend () -> Boolean,
    private val onUpdateNotification: (String) -> Unit,
    private val onRefreshTelemetry: () -> Unit
) {
    private var lastAutoReloadAttemptTime = 0L

    /**
     * RAM Safety Valve: Proactively suspends Gemma engine weights if system memory utilization
     * reaches >= 94% or available memory drops below 400MB while GHOST is idle.
     */
    fun checkRamPressureAndSuspendIfNeeded(): Boolean {
        if (isInferencing() || isSpeaking() || !isGemmaLoaded() || isSuspendedDueToRam.get()) return false
        if (isAppInForeground()) return false

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val usedMem = memInfo.totalMem - memInfo.availMem
        val utilization = usedMem.toDouble() / memInfo.totalMem.toDouble()

        if (utilization >= Constants.RAM_CRITICAL_UTILIZATION_THRESHOLD ||
            memInfo.availMem < Constants.RAM_CRITICAL_MIN_FREE_BYTES ||
            memInfo.lowMemory
        ) {
            Timber.w("🚨 RAM critical pressure detected: ${(utilization * 100).toInt()}% utilized (${memInfo.availMem / (1024 * 1024)}MB free). Suspending engine weights...")
            suspendEngineWeights("Critical system RAM pressure (${(utilization * 100).toInt()}% utilized, ${memInfo.availMem / (1024 * 1024)}MB free)")
            return true
        }
        return false
    }

    /**
     * Auto-Reload Safety Valve: Proactively reloads Gemma as soon as memory cools below 54%
     * and available RAM >= 2.0GB.
     */
    fun checkAutoReloadIfCooled(nowMs: Long = System.currentTimeMillis()) {
        val userBackend = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Constants.PREF_USER_BACKEND, "AUTO")
        if (!isSuspendedDueToRam.get() || userBackend == "OFF" || isInferencing() || (nowMs - lastAutoReloadAttemptTime <= 15000L)) {
            return
        }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val usedMem = memInfo.totalMem - memInfo.availMem
        val utilization = usedMem.toDouble() / memInfo.totalMem.toDouble()

        if (utilization < Constants.RAM_RELOAD_UTILIZATION_THRESHOLD &&
            memInfo.availMem >= Constants.RAM_RELOAD_MIN_FREE_BYTES &&
            !memInfo.lowMemory
        ) {
            lastAutoReloadAttemptTime = nowMs
            Timber.i("♻️ System RAM cooled to ${(utilization * 100).toInt()}% (${memInfo.availMem / (1024 * 1024)}MB free). Proactively auto-reloading suspended engine weights...")
            isSuspendedDueToRam.set(false)
            serviceScope.launch {
                val ready = onEnsureEngineReady()
                if (!ready) {
                    Timber.w("Auto-reload attempt failed; cooling down for 20s")
                    isSuspendedDueToRam.set(true)
                } else {
                    onRefreshTelemetry()
                }
            }
        }
    }

    fun suspendEngineWeights(reason: String) {
        serviceScope.launch {
            if (isInferencing() || isSpeaking()) {
                Timber.w("Skipping engine suspension: currently active (inferencing=${isInferencing()}, speaking=${isSpeaking()})")
                return@launch
            }
            try {
                onCheckpointRequested()
            } catch (e: Exception) {
                Timber.w(e, "Failed to checkpoint GhostAgent before suspend")
            }
            isSuspendedDueToRam.set(true)
            onUnloadEngine()
            onUpdateNotification("✧ Gemma: Suspended (Freed RAM for other apps)")
            Timber.i("✧ Gemma engine weights unloaded due to: $reason")
        }
    }

    fun handleTrimMemory(level: Int) {
        Timber.i("onTrimMemory received: level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            if (!isInferencing() && !isSpeaking() && isGemmaLoaded()) {
                if (isAppInForeground()) {
                    Timber.i("Ignoring onTrimMemory: GHOST is currently active in foreground")
                    return
                }
                Timber.w("🚨 TRIM_MEMORY critical pressure ($level). Suspending engine weights...")
                suspendEngineWeights("System TRIM_MEMORY ($level)")
            }
        }
    }

    fun handleLowMemory() {
        Timber.w("🚨 onLowMemory received from OS! Suspending engine weights immediately...")
        if (!isInferencing() && !isSpeaking() && isGemmaLoaded()) {
            if (isAppInForeground()) {
                Timber.i("Ignoring onLowMemory: GHOST is currently active in foreground")
                return
            }
            suspendEngineWeights("System onLowMemory")
        }
    }
}
