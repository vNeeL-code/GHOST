package com.gemma.api.hardware

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import timber.log.Timber
import kotlin.math.sqrt

/**
 * Shake detector for "no UI" summon.
 * Shake phone → summon Gemma input overlay
 */
class ShakeDetector(
    private val context: Context,
    private val onShake: () -> Unit
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    private var lastShakeTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastUpdate = 0L

    // Tuning
    private val shakeThreshold = 15f  // Acceleration delta to trigger
    private val shakeCooldown = 1500L // ms between shakes
    private val updateInterval = 100L // ms between sensor reads

    fun start() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            Timber.d("ShakeDetector started")
        } ?: Timber.w("No accelerometer available")
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        Timber.d("ShakeDetector stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        val now = System.currentTimeMillis()
        if (now - lastUpdate < updateInterval) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val deltaX = x - lastX
        val deltaY = y - lastY
        val deltaZ = z - lastZ

        lastX = x
        lastY = y
        lastZ = z
        lastUpdate = now

        val acceleration = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)

        if (acceleration > shakeThreshold) {
            if (now - lastShakeTime > shakeCooldown) {
                lastShakeTime = now
                Timber.i("Shake detected! Acceleration: $acceleration")
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Don't care
    }
}
