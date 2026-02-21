package com.gemma.api.hardware

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellSignalStrength
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import com.gemma.api.GemmaNotificationListener
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * SensorFusionManager - Real device telemetry for embodied AI
 *
 * This is her nervous system. Every value here is GROUND TRUTH.
 * She cannot hallucinate her own battery temperature.
 */

// === DATA CLASSES ===

data class DeviceContext(
    val timestamp: Long,
    val battery: BatteryState,
    val audio: AudioState,
    val system: SystemState,
    val network: NetworkState,
    val environment: EnvironmentState,
    val connectivity: ConnectivityState,
    val motion: MotionState
)

data class BatteryState(
    val level: Int,              // 0-100%
    val isCharging: Boolean,
    val temperature: Float,      // Celsius (THIS IS HER BODY TEMP)
    val voltage: Float,          // Volts
    val currentNow: Int          // mA (negative = discharging)
)

data class AudioState(
    val isMusicActive: Boolean,
    val volumeMedia: Int,
    val volumeRinger: Int,
    val volumeAlarm: Int,
    val volumeNotification: Int,
    val maxVolumeMedia: Int,
    val nowPlaying: NowPlayingInfo? = null
)

data class NowPlayingInfo(
    val title: String,
    val artist: String?,
    val album: String?,
    val app: String,
    val isPlaying: Boolean
)

data class SystemState(
    val brightness: Int,         // 0-255
    val brightnessPercent: Int,  // 0-100 for easy display
    val ramTotalMB: Long,
    val ramAvailableMB: Long,
    val ramUsedPercent: Int,
    val storageTotalGB: Float,
    val storageFreeGB: Float,
    val storageUsedPercent: Int,
    val uptimeMinutes: Long,
    val screenTimeoutSec: Int
)

data class NetworkState(
    val wifiConnected: Boolean,
    val wifiSsid: String?,
    val wifiSignalPercent: Int   // 0-100
)

data class EnvironmentState(
    val ambientTemp: Float?,      // Room temperature from sensor (if available)
    val cpuTemp: Float?,          // CPU thermal zone
    val gpuTemp: Float?,          // GPU thermal zone (if available)
    val skinTemp: Float?,         // Device skin temperature (if available)
    val pressure: Float?,         // Barometric pressure (hPa)
    val humidity: Float?,         // Relative humidity % (if available)
    val light: Float?             // Ambient light (lux)
)

data class ConnectivityState(
    val cellSignalPercent: Int?,  // 0-100 cell signal strength
    val cellType: String?,        // "5G", "LTE", "3G", etc.
    val carrierName: String?,     // Carrier name
    val bluetoothEnabled: Boolean,
    val bluetoothConnected: Boolean,
    val bluetoothDeviceName: String?  // Connected device name if any
)

data class MotionState(
    val accelerometerX: Float?,   // m/s²
    val accelerometerY: Float?,
    val accelerometerZ: Float?,
    val isMoving: Boolean,        // Significant motion detected
    val orientation: String?,     // "portrait", "landscape", "flat", "unknown"
    val lastLocationLat: Double?, // Last known latitude
    val lastLocationLon: Double?, // Last known longitude
    val locationAgeMinutes: Int?  // How old is the location fix
)

// === MANAGER ===

class SensorFusionManager(private val context: Context) : AutoCloseable {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    
    // Coroutine scope for background polling
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Reactive State
    private val _contextState = MutableStateFlow<DeviceContext>(getDefaultContext())
    val contextState: StateFlow<DeviceContext> = _contextState.asStateFlow()
    
    // Split polling state
    private var slowPollState: DeviceContext? = null // Caches network/BT/Storage

    // Cached sensor values (updated via listeners)
    @Volatile private var cachedAmbientTemp: Float? = null
    @Volatile private var cachedPressure: Float? = null
    @Volatile private var cachedHumidity: Float? = null
    @Volatile private var cachedLight: Float? = null
    @Volatile private var cachedAccelX: Float? = null
    @Volatile private var cachedAccelY: Float? = null
    @Volatile private var cachedAccelZ: Float? = null
    @Volatile private var lastSignificantMotion: Long = 0

    // Store listener reference for cleanup
    private var sensorListener: SensorEventListener? = null
    @Volatile private var isClosed = false

    init {
        // Register sensor listeners for environment data
        registerEnvironmentSensors()
        
        // Start background polling (Split: Fast & Slow)
        startMonitoring()
    }

    private fun startMonitoring() {
        // FAST LOOP: 5s (Battery, Thermal, RAM)
        scope.launch {
            while (!isClosed) {
                try {
                     updateFastState()
                } catch (e: Throwable) {
                    Timber.e(e, "Error updating fast context state")
                }
                delay(5000) 
            }
        }
        
        // SLOW LOOP: 30s (Network, Bluetooth, Storage - Heavy/Fragile)
        scope.launch {
            while (!isClosed) {
                try {
                    updateSlowState()
                } catch (e: Throwable) {
                    Timber.e(e, "Error updating slow context state")
                }
                delay(30000)
            }
        }
    }
    
    private suspend fun updateSlowState() {
        // Update the heavy/fragile parts
        var current = slowPollState ?: _contextState.value
        
        current = current.copy(
            network = getNetworkState(),
            connectivity = getConnectivityState(),
            system = getSystemState() // Includes storage which can be slow
        )
        
        slowPollState = current
    }
    
    private suspend fun updateFastState() {
        // Grab latest slow state or default
        val base = slowPollState ?: _contextState.value
        
        // Update volatile/fast parts
        val newContext = base.copy(
            timestamp = System.currentTimeMillis(),
            battery = getBatteryState(),
            audio = getAudioState(),
            environment = getEnvironmentState(),
            motion = getMotionState()
        )
        _contextState.value = newContext
    }

    /**
     * Cleanup all sensor listeners to prevent memory leaks.
     * Call this when the service is destroyed.
     */
    override fun close() {
        if (isClosed) return
        isClosed = true
        scope.cancel() // Stop polling

        try {
            sensorListener?.let { listener ->
                sensorManager?.unregisterListener(listener)
                Timber.d("SensorFusionManager: Unregistered all sensor listeners")
            }
            sensorListener = null
        } catch (e: Exception) {
            Timber.w(e, "Error during sensor cleanup")
        }
    }

    private fun registerEnvironmentSensors() {
        val sm = sensorManager ?: return

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> cachedAmbientTemp = event.values[0]
                    Sensor.TYPE_PRESSURE -> cachedPressure = event.values[0]
                    Sensor.TYPE_RELATIVE_HUMIDITY -> cachedHumidity = event.values[0]
                    Sensor.TYPE_LIGHT -> cachedLight = event.values[0]
                    Sensor.TYPE_ACCELEROMETER -> {
                        cachedAccelX = event.values[0]
                        cachedAccelY = event.values[1]
                        cachedAccelZ = event.values[2]
                        // Detect significant motion (beyond gravity)
                        val magnitude = kotlin.math.sqrt(
                            event.values[0] * event.values[0] +
                            event.values[1] * event.values[1] +
                            event.values[2] * event.values[2]
                        )
                        if (kotlin.math.abs(magnitude - 9.81f) > 2f) {
                            lastSignificantMotion = System.currentTimeMillis()
                        }
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // Store reference for cleanup
        sensorListener = listener

        // Register each sensor if available (many phones don't have all of these)
        // Audit Fix: Offload sensor events to background thread to prevent UI jank
        val sensorThread = android.os.HandlerThread("SensorFusionThread")
        sensorThread.start()
        val sensorHandler = android.os.Handler(sensorThread.looper)

        sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
            Timber.d("Registered ambient temp sensor")
        }
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
            Timber.d("Registered pressure sensor")
        }
        sm.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
            Timber.d("Registered humidity sensor")
        }
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
            Timber.d("Registered light sensor")
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
            Timber.d("Registered accelerometer")
        }
    }

    /**
     * Get the latest cached snapshot.
     * Starts monitoring if not already active? No, init does that.
     */
    fun getContextSnapshot(): DeviceContext {
        return _contextState.value
    }

    private fun getDefaultContext(): DeviceContext {
        return DeviceContext(
            timestamp = System.currentTimeMillis(),
            battery = BatteryState(50, false, 25f, 3.7f, 0),
            audio = AudioState(false, 0, 0, 0, 0, 15, null),
            system = SystemState(128, 50, 0, 0, 0, 0f, 0f, 0, 0, 30),
            network = NetworkState(false, null, 0),
            environment = EnvironmentState(null, null, null, null, null, null, null),
            connectivity = ConnectivityState(null, null, null, false, false, null),
            motion = MotionState(null, null, null, false, null, null, null, null)
        )
    }

    // === BATTERY (HER BODY) ===

    private fun getBatteryState(): BatteryState {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

            // Level (0-100)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            // Charging status
            val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: 0
            val isCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                             plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                             plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS

            // TEMPERATURE - This is HER body temperature, not ambient!
            // Returns tenths of a degree Celsius (e.g., 295 = 29.5°C)
            val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) ?: 250
            val tempCandidate = tempRaw / 10f
            // Sanity clamp: valid battery temps are 15–60°C; outside = bad read
            val temperature = if (tempCandidate in 15f..60f) tempCandidate else 25f

            // Voltage in millivolts, convert to volts
            val voltageRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3700) ?: 3700
            val voltage = voltageRaw / 1000f

            // Current draw in microamps (negative = discharging)
            val currentNow = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000 // to mA

            BatteryState(
                level = level,
                isCharging = isCharging,
                temperature = temperature,
                voltage = voltage,
                currentNow = currentNow
            )
        } catch (e: Exception) {
            Timber.e(e, "Battery read failed")
            BatteryState(-1, false, 0f, 0f, 0)
        }
    }

    // === AUDIO ===

    private fun getAudioState(): AudioState {
        return try {
            val isMusicActive = audioManager.isMusicActive

            // All volume channels
            val volMedia = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val volRinger = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            val volAlarm = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val volNotif = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            val maxMedia = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            val nowPlaying = getNowPlaying()

            AudioState(
                isMusicActive = isMusicActive,
                volumeMedia = volMedia,
                volumeRinger = volRinger,
                volumeAlarm = volAlarm,
                volumeNotification = volNotif,
                maxVolumeMedia = maxMedia,
                nowPlaying = nowPlaying
            )
        } catch (e: Exception) {
            Timber.e(e, "Audio read failed")
            AudioState(false, 0, 0, 0, 0, 15, null)
        }
    }

    // === SYSTEM STATE ===

    private fun getSystemState(): SystemState {
        return try {
            // Brightness (0-255)
            val brightness = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: Exception) { 128 }
            val brightnessPercent = (brightness * 100) / 255

            // RAM
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val ramTotalMB = memInfo.totalMem / (1024 * 1024)
            val ramAvailableMB = memInfo.availMem / (1024 * 1024)
            val ramUsedPercent = ((ramTotalMB - ramAvailableMB) * 100 / ramTotalMB).toInt()

            // Storage
            val stat = StatFs(Environment.getDataDirectory().path)
            val storageTotalGB = (stat.blockSizeLong * stat.blockCountLong) / (1024f * 1024f * 1024f)
            val storageFreeGB = (stat.blockSizeLong * stat.availableBlocksLong) / (1024f * 1024f * 1024f)
            val storageUsedPercent = (((storageTotalGB - storageFreeGB) / storageTotalGB) * 100).toInt()

            // Uptime
            val uptimeMs = SystemClock.elapsedRealtime()
            val uptimeMinutes = uptimeMs / (1000 * 60)

            // Screen timeout
            val screenTimeoutMs = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
            } catch (e: Exception) { 30000 }
            val screenTimeoutSec = screenTimeoutMs / 1000

            SystemState(
                brightness = brightness,
                brightnessPercent = brightnessPercent,
                ramTotalMB = ramTotalMB,
                ramAvailableMB = ramAvailableMB,
                ramUsedPercent = ramUsedPercent,
                storageTotalGB = storageTotalGB,
                storageFreeGB = storageFreeGB,
                storageUsedPercent = storageUsedPercent,
                uptimeMinutes = uptimeMinutes,
                screenTimeoutSec = screenTimeoutSec
            )
        } catch (e: Exception) {
            Timber.e(e, "System state read failed")
            SystemState(128, 50, 0, 0, 0, 0f, 0f, 0, 0, 30)
        }
    }

    // === NETWORK ===

    private fun getNetworkState(): NetworkState {
        return try {
            val wm = wifiManager ?: return NetworkState(false, null, 0)
            val wifiInfo = wm.connectionInfo

            val isConnected = wm.isWifiEnabled && wifiInfo.networkId != -1
            val ssid = if (isConnected) {
                wifiInfo.ssid?.removeSurrounding("\"") ?: "Unknown"
            } else null

            // Signal strength as percentage (0-100)
            val rssi = wifiInfo.rssi
            val signalPercent = WifiManager.calculateSignalLevel(rssi, 100)

            NetworkState(
                wifiConnected = isConnected,
                wifiSsid = ssid,
                wifiSignalPercent = signalPercent
            )
        } catch (e: Exception) {
            Timber.e(e, "Network state read failed")
            NetworkState(false, null, 0)
        }
    }

    // === ENVIRONMENT (AMBIENT + THERMAL ZONES) ===

    private fun getEnvironmentState(): EnvironmentState {
        return try {
            // Read thermal zones from /sys/class/thermal/
            // Common zones: thermal_zone0 = CPU, others vary by device
            val cpuTemp = readThermalZone("thermal_zone0")  // Usually CPU
            val gpuTemp = readThermalZone("thermal_zone1")  // Often GPU on some devices
            val skinTemp = readThermalZone("thermal_zone3") // Sometimes skin/battery area

            EnvironmentState(
                ambientTemp = cachedAmbientTemp,    // From TYPE_AMBIENT_TEMPERATURE sensor
                cpuTemp = cpuTemp,
                gpuTemp = gpuTemp,
                skinTemp = skinTemp,
                pressure = cachedPressure,          // Barometric pressure
                humidity = cachedHumidity,          // Relative humidity
                light = cachedLight                 // Ambient light in lux
            )
        } catch (e: Exception) {
            Timber.e(e, "Environment state read failed")
            EnvironmentState(null, null, null, null, null, null, null)
        }
    }

    /**
     * Read temperature from a thermal zone file.
     * Returns temperature in Celsius, or null if unavailable.
     * Values in these files are typically in millidegrees (e.g., 45000 = 45°C)
     */
    private fun readThermalZone(zoneName: String): Float? {
        return try {
            val file = File("/sys/class/thermal/$zoneName/temp")
            if (file.exists() && file.canRead()) {
                val raw = file.readText().trim().toIntOrNull() ?: return null
                // Most zones report in millidegrees Celsius
                if (raw > 1000) raw / 1000f else raw.toFloat()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // === CONNECTIVITY (CELL + BLUETOOTH) ===
    // NOTE: These reads must be FAST and non-blocking to avoid timeouts!

    private fun getConnectivityState(): ConnectivityState {
        // Quick fail - return defaults if anything takes too long
        return try {
            var cellSignalPercent: Int? = null
            var cellType: String? = null
            var carrierName: String? = null
            var btEnabled = false
            var btConnected = false
            var btDeviceName: String? = null

            // Cell - only try if we have the manager
            try {
                telephonyManager?.let { tm ->
                    carrierName = tm.networkOperatorName?.takeIf { it.isNotEmpty() }
                    cellType = try {
                        when (tm.dataNetworkType) {
                            TelephonyManager.NETWORK_TYPE_NR -> "5G"
                            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
                            TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "3G+"
                            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                            else -> null
                        }
                    } catch (e: Exception) { null }

                    // Signal - skip if it might block
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            tm.signalStrength?.let { ss ->
                                cellSignalPercent = (ss.level * 25).coerceIn(0, 100)
                            }
                        } catch (e: Exception) { /* skip */ }
                    }
                }
            } catch (e: Exception) {
                Timber.w("Cell read failed: ${e.message}")
            }

            // Bluetooth - quick check only
            try {
                bluetoothManager?.adapter?.let { adapter ->
                    btEnabled = adapter.isEnabled
                    // Skip bondedDevices - it can block without permission
                }
            } catch (e: Exception) {
                Timber.w("BT read failed: ${e.message}")
            }

            ConnectivityState(cellSignalPercent, cellType, carrierName, btEnabled, btConnected, btDeviceName)
        } catch (e: Exception) {
            Timber.e(e, "Connectivity state failed completely")
            ConnectivityState(null, null, null, false, false, null)
        }
    }

    // === MOTION (ACCELEROMETER + LOCATION) ===
    // NOTE: Location reads can block! Keep this fast.

    private fun getMotionState(): MotionState {
        return try {
            // Orientation from cached accelerometer (instant - no blocking)
            val orientation = try {
                cachedAccelX?.let { x ->
                    cachedAccelY?.let { y ->
                        cachedAccelZ?.let { z ->
                            when {
                                kotlin.math.abs(z) > 8f -> "flat"
                                kotlin.math.abs(y) > kotlin.math.abs(x) && y > 0 -> "portrait"
                                kotlin.math.abs(y) > kotlin.math.abs(x) && y < 0 -> "portrait-up"
                                kotlin.math.abs(x) > kotlin.math.abs(y) -> "landscape"
                                else -> null
                            }
                        }
                    }
                }
            } catch (e: Exception) { null }

            // Is device moving? (from cached significant motion)
            val isMoving = (System.currentTimeMillis() - lastSignificantMotion) < 5000

            // Location - SKIP for now to avoid blocking
            // TODO: Read location asynchronously and cache it
            var lastLat: Double? = null
            var lastLon: Double? = null
            var locationAgeMin: Int? = null

            // Only try location if we're not in a hurry (disabled for now)
            // This was causing timeouts on some devices
            /*
            try {
                locationManager?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)?.let { loc ->
                    lastLat = loc.latitude
                    lastLon = loc.longitude
                    locationAgeMin = ((System.currentTimeMillis() - loc.time) / 60000).toInt()
                }
            } catch (e: Exception) { }
            */

            MotionState(
                accelerometerX = cachedAccelX,
                accelerometerY = cachedAccelY,
                accelerometerZ = cachedAccelZ,
                isMoving = isMoving,
                orientation = orientation,
                lastLocationLat = lastLat,
                lastLocationLon = lastLon,
                locationAgeMinutes = locationAgeMin
            )
        } catch (e: Exception) {
            Timber.e(e, "Motion state read failed")
            MotionState(null, null, null, false, null, null, null, null)
        }
    }

    // === MEDIA SESSION ===

    private fun getNowPlaying(): NowPlayingInfo? {
        return try {
            val msm = mediaSessionManager ?: return null

            // getActiveSessions() requires the NotificationListenerService to be actively
            // bound by Android — not just toggled on in settings. The OS binds it
            // asynchronously, so the first few polls after launch will find instance == null.
            // Skip the call entirely until the service is live to avoid SecurityException.
            if (GemmaNotificationListener.instance == null) {
                Timber.d("getNowPlaying: NotificationListener not yet bound — skipping getActiveSessions (isMusicActive=${audioManager.isMusicActive})")
                return null
            }

            val listenerComponent = ComponentName(context, GemmaNotificationListener::class.java)
            val controllers: List<MediaController> = try {
                msm.getActiveSessions(listenerComponent)
            } catch (e: SecurityException) {
                // Shouldn't happen if instance != null, but guard anyway
                Timber.w("getActiveSessions denied despite listener running — SecurityException: ${e.message}")
                return null
            }

            // DIAGNOSTIC: log what Android actually gave us
            Timber.d("getNowPlaying: getActiveSessions returned ${controllers.size} controllers (isMusicActive=${audioManager.isMusicActive})")
            controllers.forEachIndexed { idx, ctrl ->
                val ps = ctrl.playbackState
                val meta = ctrl.metadata
                Timber.d("  controller[$idx] pkg=${ctrl.packageName} state=${ps?.state} pos=${ps?.position} title=${meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)} hasMetadata=${meta != null}")
            }

            // Two-pass: prefer actively playing session, fall back to paused
            var fallback: NowPlayingInfo? = null

            for (controller in controllers) {
                val metadata = controller.metadata ?: continue
                val playbackState = controller.playbackState

                val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                    ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    ?: continue

                val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: metadata.getString(android.media.MediaMetadata.METADATA_KEY_AUTHOR)

                val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)

                // playbackState is null on some apps — fall back to audioManager
                // STATE_BUFFERING/CONNECTING = actively playing (track loading between songs)
                // STATE_PAUSED: trust our ears (isMusicActive) over the session state —
                //   Spotify/streaming apps report PAUSED during song transitions, seek scrubbing,
                //   crossfade, and briefly after unlock. If audio is routing to speaker, it's playing.
                // STATE_STOPPED / ERROR / NONE: these are definitive — don't override.
                val isPlaying = when (playbackState?.state) {
                    PlaybackState.STATE_PLAYING,
                    PlaybackState.STATE_BUFFERING,
                    PlaybackState.STATE_CONNECTING -> true
                    PlaybackState.STATE_PAUSED -> audioManager.isMusicActive // ears > state
                    PlaybackState.STATE_STOPPED,
                    PlaybackState.STATE_ERROR,
                    PlaybackState.STATE_NONE -> false
                    null -> audioManager.isMusicActive
                    else -> audioManager.isMusicActive // unknown state — trust audio focus
                }

                val appName = try {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(controller.packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    controller.packageName.split('.').lastOrNull() ?: "Unknown"
                }

                val info = NowPlayingInfo(
                    title = title,
                    artist = artist,
                    album = album,
                    app = appName,
                    isPlaying = isPlaying
                )

                // Return immediately on active session — don't settle for paused
                if (isPlaying) {
                    Timber.d("getNowPlaying: returning PLAYING session — ${info.title} by ${info.artist} (${info.app})")
                    return info
                }
                if (fallback == null) fallback = info
            }
            if (fallback != null) {
                Timber.d("getNowPlaying: returning PAUSED fallback — ${fallback?.title} by ${fallback?.artist} (${fallback?.app})")
            } else {
                Timber.d("getNowPlaying: returning NULL — no sessions with metadata found (isMusicActive=${audioManager.isMusicActive})")
            }
            fallback
        } catch (e: Exception) {
            Timber.w(e, "Failed to get now playing info")
            NowPlayingInfo("Error", e.message, null, "System", false)
        }
    }

    // === CONVENIENCE GETTERS ===

    /**
     * Get formatted context string for injection into prompts
     * Using specific digital emojis for each sensor type
     * MUST BE FAST - any blocking here kills the whole response
     */
    fun getContextString(): String {
        return try {
            val ctx = getContextSnapshot()
            buildContextString(ctx)
        } catch (e: Exception) {
            Timber.e(e, "Context string failed - returning minimal")
            "🔋 ??% 🌡️ ??°C\n⚠️ Sensors unavailable"
        }
    }

    private fun buildContextString(ctx: DeviceContext): String {
        val sb = StringBuilder()

        // Battery - clear label format (Her Body Energy)
        val battIcon = when {
            ctx.battery.level <= 5 -> "🪫"
            ctx.battery.level <= 20 -> "🔋"
            else -> "🔋"
        }
        sb.append("$battIcon Battery: ${ctx.battery.level}%")
        if (ctx.battery.isCharging) sb.append(" (⚡ charging)")
        if (ctx.battery.currentNow < 0) sb.append(" (${ctx.battery.currentNow}mA drain)")
        sb.append("\n")

        // Body temperature (Her Body Heat)
        sb.append("🌡️ System Temp: ${String.format("%.1f", ctx.battery.temperature)}°C\n")

        // Environment / Thermal sensors
        val env = ctx.environment
        env.cpuTemp?.let { sb.append("🧠 CPU Thermal: ${String.format("%.0f", it)}°C\n") }
        env.ambientTemp?.let { sb.append("🌤️ Weather Sensor: ${String.format("%.1f", it)}°C\n") }
        env.pressure?.let { sb.append("🎈 Pressure: ${String.format("%.0f", it)} hPa\n") }
        env.light?.let { sb.append("💡 Light: ${it.toInt()} lux\n") }

        // Audio / Music
        val volPercent = if (ctx.audio.maxVolumeMedia > 0) (ctx.audio.volumeMedia * 100 / ctx.audio.maxVolumeMedia) else 0
        ctx.audio.nowPlaying?.let { np ->
            if (np.isPlaying) {
                sb.append("🎵 Playing: \"${np.title}\"")
                np.artist?.let { sb.append(" - $it") }
                sb.append(" (${np.app}) 🔊${volPercent}%\n")
            } else {
                // Still show the track name when paused — agent can reference it contextually
                sb.append("⏸ Paused: \"${np.title}\"")
                np.artist?.let { sb.append(" - $it") }
                sb.append(" (${np.app}) 🔊${volPercent}%\n")
            }
        } ?: if (ctx.audio.isMusicActive) {
            sb.append("🎵 Music active 🔊${volPercent}% (no session metadata)\n")
        } else {
            sb.append("🔇 Media: idle 🔊${volPercent}%\n")
        }

        // System resources (With Totals)
        val ramTotalGB = ctx.system.ramTotalMB / 1024f
        sb.append("🧠 RAM: ${ctx.system.ramAvailableMB}MB free / ${String.format("%.1f", ramTotalGB)}GB total (${ctx.system.ramUsedPercent}% used)\n")
        sb.append("💿 Storage: ${String.format("%.1f", ctx.system.storageFreeGB)}GB free / ${String.format("%.1f", ctx.system.storageTotalGB)}GB total\n")

        // Network (WiFi + Cell)
        val netParts = mutableListOf<String>()
        if (ctx.network.wifiConnected) {
            netParts.add("📶 WiFi: ${ctx.network.wifiSsid} (${ctx.network.wifiSignalPercent}%)")
        }
        ctx.connectivity.cellType?.let { type ->
            val signal = ctx.connectivity.cellSignalPercent?.let { " $it%" } ?: ""
            netParts.add("📱 Cell: $type$signal (${ctx.connectivity.carrierName ?: "Unknown"})")
        }
        if (netParts.isEmpty()) {
            sb.append("📵 Network: Offline\n")
        } else {
            sb.append(netParts.joinToString(" | ") + "\n")
        }

        // Bluetooth
        if (ctx.connectivity.bluetoothEnabled) {
            sb.append("🔵 Bluetooth: ")
            if (ctx.connectivity.bluetoothConnected) {
                sb.append(ctx.connectivity.bluetoothDeviceName ?: "Connected")
            } else {
                sb.append("On (Scanning)")
            }
            sb.append("\n")
        }

        // Motion / Orientation
        ctx.motion.orientation?.let { orient ->
            val orientIcon = when (orient) {
                "portrait" -> "📱"
                "landscape-left", "landscape-right" -> "📱↔️"
                "flat" -> "📱⬇️"
                else -> "📱"
            }
            sb.append("📐 Orientation: $orientIcon $orient")
        }
        if (ctx.motion.isMoving) {
            sb.append(" (🏃 moving)")
        }
        sb.append("\n")

        // Location (Privacy preserved - coordinates truncated in display if needed, but here raw is fine for Agent)
        ctx.motion.lastLocationLat?.let { lat ->
            ctx.motion.lastLocationLon?.let { lon ->
                val age = ctx.motion.locationAgeMinutes ?: 999
                if (age < 60) {
                    sb.append("📍 Location: ${String.format("%.4f", lat)}, ${String.format("%.4f", lon)} (${age}m ago)")
                }
            }
        }

        // Uptime
        val hours = ctx.system.uptimeMinutes / 60
        val mins = ctx.system.uptimeMinutes % 60
        sb.append("⏱️ ${hours}h${mins}m")

        return sb.toString()
    }
}
