package com.gemma.api.hardware

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore

/**
 * HARDWARE BRIDGE - The "Hands" of Oracle_OS
 * Direct Android hardware manipulation helper.
 * Note: ToolSet API requires newer LiteRT-LM version. Using manual dispatch for now.
 */
class HardwareToolSet(private val context: Context) {

    // === FLASHLIGHT CONTROL ===
    fun controlFlashlight(enable: Boolean): Map<String, Any> {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            } ?: return mapOf("error" to "No camera with flash available")
            
            cameraManager.setTorchMode(cameraId, enable)
            mapOf("success" to true, "flashlight" to if (enable) "ON" else "OFF")
        } catch (e: Exception) {
            mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
        }
    }

    // === VIBRATION FEEDBACK ===
    fun vibrate(pattern: List<Long>, repeat: Int = -1): Map<String, Any> {
        return try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(
                    pattern.toLongArray(),
                    repeat
                )
                vibrator.vibrate(effect)
            } else {
                vibrator.vibrate(pattern.toLongArray(), repeat)
            }
            
            mapOf("success" to true, "pattern" to pattern)
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }
}
