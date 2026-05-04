package com.ghost.api.hardware

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * HARDWARE BRIDGE - The "Hands" of Oracle_OS
 * Direct Android hardware manipulation helper properly aligned with LiteRT @Tool Architecture.
 */
class HardwareToolSet(
    private val context: Context,
    private val sensorFusionManager: com.ghost.api.hardware.SensorFusionManager? = null
) : ToolSet {

    @Tool(description = "Controls the device flashlight")
    fun flashlight(
        @ToolParam(description = "Set to 'ON' to turn on, 'OFF' to turn off") state: String
    ): Map<String, String> {
        return try {
            val enable = state.equals("ON", ignoreCase = true)
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            } ?: return mapOf("result" to "error", "message" to "No camera with flash available")
            
            cameraManager.setTorchMode(cameraId, enable)
            mapOf("result" to "success", "message" to "Flashlight turned ${if (enable) "ON" else "OFF"}")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "Unknown error"))
        }
    }

    @Tool(description = "Vibrates the device using a named pattern")
    fun vibrate(
        @ToolParam(description = "Vibration pattern (e.g., 'SHORT', 'SOS', or a list of timings '[100, 200]')") pattern: String
    ): Map<String, String> {
        return try {
            val patternList = when (pattern.uppercase()) {
                "SHORT" -> listOf(0L, 100L)
                "SOS" -> listOf(0L, 100L, 100L, 100L, 100L, 100L, 300L, 100L, 300L, 100L, 300L, 100L, 100L, 100L, 100L, 100L, 100L)
                else -> pattern.replace("[", "").replace("]", "").split(",").map { it.trim().toLong() }
            }
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (!vibrator.hasVibrator()) {
                Timber.w("Vibration failed: Device has no vibrator")
                return mapOf("result" to "error", "message" to "No vibrator hardware")
            }

            Timber.i("Executing vibration pattern: $pattern")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(patternList.toLongArray(), -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(patternList.toLongArray(), -1)
            }
            mapOf("result" to "success", "message" to "Vibration executed")
        } catch (e: Exception) {
            Timber.e(e, "Vibration tool error")
            mapOf("result" to "error", "message" to (e.message ?: "Pattern error"))
        }
    }

    @Tool(description = "Clear KV cache and reset context when responses become slow or confused")
    fun flush(): Map<String, String> {
        return mapOf("result" to "success", "message" to "Flush requested. Context will be reset shortly.")
    }

    @Tool(description = "Enter low-power mode to cool down when device is overheating")
    fun cooldown(): Map<String, String> {
        return mapOf("result" to "success", "message" to "Cooldown requested. Reducing brain activity.")
    }

    @Tool(description = "Reads all device sensory telemetry (battery, temperature, environment, motion) as a string. Call this when checking 'sensors'.")
    fun getDeviceSensors(): Map<String, String> {
        return try {
            val data = sensorFusionManager?.getContextString() ?: "Sensors unavailable"
            mapOf("result" to "success", "data" to data)
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "Sensor read failed"))
        }
    }
}
