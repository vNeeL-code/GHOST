package com.ghost.api.hardware

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
import com.ghost.api.Constants

/**
 * Manages device hardware properties and handles dynamic "Quotas".
 */
class HardwarePropertiesManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    enum class ThermalState { COOL, WARM, HOT, CRITICAL }

    private val _thermalState = MutableStateFlow(ThermalState.COOL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    private val THERMAL_CACHE_TTL_MS = 5000L

    private var nativeThermalAvailable = false
    private var lastInferenceEndTime: Long = 0L

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
                }
                nativeThermalAvailable = true
            } catch (e: Exception) {
                Timber.w("HardwarePropertiesManager: Native thermal unavailable")
            }
        }
        if (!nativeThermalAvailable) {
            startPolling()
        }
    }

    private fun startPolling() {
        scope.launch {
            while (true) {
                if (!nativeThermalAvailable) pollSysfsThermal()
                delay(THERMAL_CACHE_TTL_MS) 
            }
        }
    }

    private fun pollSysfsThermal() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val pm = context.getSystemService(PowerManager::class.java)
                val headroom = pm?.getThermalHeadroom(0) ?: Float.NaN
                if (!headroom.isNaN() && headroom > 0f) {
                    val newState = when {
                        headroom >= 1.0f -> ThermalState.CRITICAL
                        headroom >= 0.85f -> ThermalState.HOT
                        headroom >= 0.70f -> ThermalState.WARM
                        else -> ThermalState.COOL
                    }
                    if (_thermalState.value != newState) _thermalState.value = newState
                    return
                }
            }
            val tempC = Constants.readMaxSocThermalCelsius()
            if (tempC != null) {
                val newState = when {
                    tempC >= Constants.THERMAL_LIMIT_CELSIUS -> ThermalState.CRITICAL
                    tempC > 52f -> ThermalState.HOT
                    tempC > 43f -> ThermalState.WARM
                    else -> ThermalState.COOL
                }
                if (_thermalState.value != newState) _thermalState.value = newState
            }
        } catch (_: Exception) {}
    }

    fun markInferenceFinished() {
        lastInferenceEndTime = System.currentTimeMillis()
    }

    fun getThermalThrottleDelay(): Long {
        val state = _thermalState.value
        val now = System.currentTimeMillis()
        val timeSinceLastInference = now - lastInferenceEndTime
        val requiredCooldown = when (state) {
            ThermalState.CRITICAL -> 15000L
            ThermalState.HOT -> 8000L
            ThermalState.WARM -> 3000L
            else -> 0L
        }
        return (requiredCooldown - timeSinceLastInference).coerceAtLeast(0L)
    }

    fun getDeviceMemoryMB(): Long {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    /**
     * Gallery-Aligned Quotas for SD8G3:
     */
    fun getOptimalTokenBudget(backend: String): Int {
        val ramMB = getDeviceMemoryMB()
        // Capture 12GB devices that report ~11GB usable
        val isHighEnd = ramMB >= 8000 
        
        return when (backend) {
            "GPU" -> {
                 // 8k is a safe baseline for GPU while vision is also enabled
                 if (isHighEnd) 8192 else 4096
            }
            else -> {
                // CPU fallback
                if (isHighEnd) 8192 else 4096
            }
        }
    }

    fun isConservativeDevice(): Boolean {
        val model = android.os.Build.MODEL.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        
        // Known-problematic devices from PokeClaw standards
        return model.contains("xiaomi") || 
               model.contains("redmi") || 
               manufacturer.contains("xiaomi") ||
               model.contains("poco")
    }
}
