package com.gemma.api.hardware

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Manages device hardware properties, primarily thermal state.
 *
 * providing a unified source of truth for "How hot am I?"
 * - Uses native Android Q+ Thermal API if available
 * - Falls back to legacy sysfs polling if not
 * - Exposes StateFlow for reactive updates
 */
class HardwarePropertiesManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    enum class ThermalState { COOL, WARM, HOT, CRITICAL }

    private val _thermalState = MutableStateFlow(ThermalState.COOL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    // Thermal caching to reduce file I/O (sysfs)
    private var lastThermalReadTime: Long = 0L
    private val THERMAL_CACHE_TTL_MS = 5000L
    private val thermalPath = "/sys/class/thermal/thermal_zone0/temp" // Default path, check if correct for device

    private var nativeThermalAvailable = false

    init {
        initializeThermalMonitoring()
    }

    private fun initializeThermalMonitoring() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val pm = context.getSystemService(PowerManager::class.java)
                pm?.addThermalStatusListener { status ->
                    val newState = when (status) {
                        PowerManager.THERMAL_STATUS_CRITICAL,
                        PowerManager.THERMAL_STATUS_EMERGENCY,
                        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                        PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.HOT
                        PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.WARM
                        else -> ThermalState.COOL
                    }
                    _thermalState.value = newState
                    
                    if (newState == ThermalState.CRITICAL) {
                        Timber.w("🔥 NATIVE THERMAL: CRITICAL")
                    }
                }
                nativeThermalAvailable = true
                Timber.i("HardwarePropertiesManager: Native thermal listener registered")
            } catch (e: Exception) {
                Timber.w("HardwarePropertiesManager: Native thermal unavailable, falling back to sysfs")
            }
        }

        // Start polling if native is not available (or as a backup/check)
        // We run this anyway to log stats occasionally or if native fails quietly
        startPolling()
    }

    private fun startPolling() {
        scope.launch {
            while (true) {
                if (!nativeThermalAvailable) {
                    pollSysfsThermal()
                }
                delay(THERMAL_CACHE_TTL_MS) 
            }
        }
    }

    private fun pollSysfsThermal() {
        try {
            val file = File(thermalPath)
            if (file.exists()) {
                val tempStr = file.readText().trim()
                val temp = tempStr.toIntOrNull() ?: 0
                // Sysfs usually reports in millidegrees Celsius
                val tempC = temp / 1000

                val newState = when {
                    tempC > 60 -> ThermalState.CRITICAL
                    tempC > 50 -> ThermalState.HOT
                    tempC > 42 -> ThermalState.WARM
                    else -> ThermalState.COOL
                }

                if (_thermalState.value != newState) {
                    _thermalState.value = newState
                    Timber.d("Sysfs Thermal Update: ${newState.name} ($tempC°C)")
                }
            }
        } catch (e: Exception) {
            // Suppress logs to avoid spam if sensor missing
        }
    }

    /**
     * Enforce thermal-aware rate limiting
     * Returns delay needed in ms
     */
    fun getThermalThrottleDelay(lastInferenceTime: Long): Long {
        val state = _thermalState.value
        val now = System.currentTimeMillis()
        val timeSinceLastInference = now - lastInferenceTime

        // Never hard-block — GPU/NPU has its own thermal management.
        // Just add cooldown delays between inferences.
        val requiredCooldown = when (state) {
            ThermalState.CRITICAL -> 15000L
            ThermalState.HOT -> 8000L
            ThermalState.WARM -> 3000L
            ThermalState.COOL -> 0L
        }

        return (requiredCooldown - timeSinceLastInference).coerceAtLeast(0L)
    }
}
