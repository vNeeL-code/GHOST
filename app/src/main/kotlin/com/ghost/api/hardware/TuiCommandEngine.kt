package com.ghost.api.hardware

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import timber.log.Timber
import java.util.Locale

/**
 * TuiCommandEngine - Headless Android OS Command Registry.
 *
 * Absorbs the battle-tested, high-reliability command primitives from TUI-ConsoleLauncher
 * (status.java, volume.java, music.java) modernized for Android 14+ (API 34+).
 *
 * Decouples low-level Android SDK hardware interactions from prompt token schemas,
 * providing deterministic execution for volume streams, media transport, and device telemetry.
 */
class TuiCommandEngine(
    private val context: Context,
    private val sensorManager: SensorFusionManager? = null
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. VOLUME CONTROL (Ported from volume.java)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Controls stream volume and ringer profiles.
     *
     * Params supported:
     * - `stream`: "media", "music", "ring", "alarm", "notification", "call", "system" (default: "media")
     * - `level`: integer percentage 0-100 (e.g. 50, "80%")
     * - `action`: "mute", "unmute", "up", "down", "get"
     * - `profile` / `mode`: "silent", "vibrate", "normal"
     */
    fun volume(params: Map<String, Any?>): Map<String, String> {
        val streamStr = (params["stream"] ?: params["type"] ?: "media").toString().lowercase(Locale.ROOT)
        val streamType = when {
            streamStr.contains("ring") || streamStr.contains("call") -> AudioManager.STREAM_RING
            streamStr.contains("alarm") -> AudioManager.STREAM_ALARM
            streamStr.contains("notif") -> AudioManager.STREAM_NOTIFICATION
            streamStr.contains("sys") -> AudioManager.STREAM_SYSTEM
            streamStr.contains("voice") -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }

        val streamLabel = when (streamType) {
            AudioManager.STREAM_RING -> "Ring"
            AudioManager.STREAM_ALARM -> "Alarm"
            AudioManager.STREAM_NOTIFICATION -> "Notification"
            AudioManager.STREAM_SYSTEM -> "System"
            AudioManager.STREAM_VOICE_CALL -> "Voice Call"
            else -> "Media"
        }

        // 1. Handle Ringer Profile / Mode change if requested
        val modeParam = (params["mode"] ?: params["profile"])?.toString()?.lowercase(Locale.ROOT)
        if (modeParam != null) {
            try {
                when {
                    modeParam.contains("silent") -> audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                    modeParam.contains("vibrate") -> audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    modeParam.contains("normal") -> audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                }
                return mapOf("result" to "success", "message" to "Ringer mode set to: $modeParam")
            } catch (e: Exception) {
                Timber.w(e, "TuiCommandEngine: Failed to adjust ringer mode")
                return mapOf("result" to "error", "message" to "Permission required for DND/ringer mode: ${e.message}")
            }
        }

        val maxVol = audioManager.getStreamMaxVolume(streamType)
        val actionParam = (params["action"] ?: params["input"])?.toString()?.lowercase(Locale.ROOT) ?: ""

        // 2. Handle Action (mute, unmute, up, down, get)
        if (actionParam.contains("mute") && !actionParam.contains("unmute")) {
            audioManager.setStreamVolume(streamType, 0, 0)
            return mapOf("result" to "success", "message" to "$streamLabel muted (0%)")
        }
        if (actionParam.contains("unmute")) {
            val unmutedLevel = (maxVol * 0.4).toInt().coerceAtLeast(1)
            audioManager.setStreamVolume(streamType, unmutedLevel, 0)
            return mapOf("result" to "success", "message" to "$streamLabel unmuted (40%)")
        }
        if (actionParam.contains("up") || actionParam.contains("raise")) {
            audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, 0)
            val current = getStreamPercent(streamType)
            return mapOf("result" to "success", "message" to "$streamLabel volume raised to $current%")
        }
        if (actionParam.contains("down") || actionParam.contains("lower")) {
            audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, 0)
            val current = getStreamPercent(streamType)
            return mapOf("result" to "success", "message" to "$streamLabel volume lowered to $current%")
        }

        // 3. Handle explicit Level percentage
        val levelParam = (params["level"] ?: params["percent"] ?: params["volume"])?.toString()
            ?.replace("%", "")?.trim()?.toIntOrNull()

        if (levelParam != null) {
            val target = ((levelParam.coerceIn(0, 100) * maxVol) / 100f).toInt()
            audioManager.setStreamVolume(streamType, target, 0)
            val current = getStreamPercent(streamType)
            return mapOf("result" to "success", "message" to "$streamLabel volume set to $current%")
        }

        // 4. Default: Return comprehensive status across all audio streams
        val report = buildString {
            append("Media: ").append(getStreamPercent(AudioManager.STREAM_MUSIC)).append("% | ")
            append("Ring: ").append(getStreamPercent(AudioManager.STREAM_RING)).append("% | ")
            append("Alarm: ").append(getStreamPercent(AudioManager.STREAM_ALARM)).append("% | ")
            append("Notifs: ").append(getStreamPercent(AudioManager.STREAM_NOTIFICATION)).append("%")
        }
        return mapOf("result" to "success", "message" to report)
    }

    private fun getStreamPercent(stream: Int): Int {
        val max = audioManager.getStreamMaxVolume(stream)
        if (max <= 0) return 0
        val current = audioManager.getStreamVolume(stream)
        return ((current.toFloat() / max.toFloat()) * 100f).toInt()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. DEVICE STATUS (Ported from status.java)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Aggregates live device telemetry into a single, compact, information-dense report:
     * Battery, WiFi, Mobile Data, Bluetooth, Location, Brightness, Audio.
     */
    fun status(): Map<String, String> {
        val telemetry = sensorManager?.contextState?.value

        // 1. Battery Telemetry
        val batterySummary = if (telemetry != null) {
            val b = telemetry.battery
            val stateTag = if (b.isCharging) "Charging" else "Discharging"
            val drainStr = if (b.currentNow != 0) " (${Math.abs(b.currentNow)}mA $stateTag)" else ""
            "${b.level}%$drainStr, ${String.format(Locale.ROOT, "%.1f", b.temperature)}°C"
        } else {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val rawLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val level = if (rawLevel >= 0 && scale > 0) (rawLevel * 100) / scale else 0
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            "$level% (${if (isCharging) "Charging" else "Discharging"})"
        }

        // 2. Network Telemetry (WiFi & Mobile Data)
        val activeNet = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNet)
        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val hasCell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val netStr = when {
            hasWifi -> "WiFi Connected"
            hasCell -> "Mobile Data Connected"
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true -> "Connected"
            else -> "Offline"
        }

        // 3. Bluetooth
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        val btStr = if (btAdapter?.isEnabled == true) "ON" else "OFF"

        // 4. Location / GPS
        val gpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val netLocEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        val locStr = if (gpsEnabled || netLocEnabled) "Active" else "Disabled"

        // 5. Brightness
        val brightnessStr = try {
            val b = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            val bPercent = (b * 100) / 255
            val isAuto = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            "$bPercent%${if (isAuto) " (auto)" else ""}"
        } catch (e: Exception) {
            "Unknown"
        }

        // 6. Media Audio
        val mediaVol = getStreamPercent(AudioManager.STREAM_MUSIC)
        val nowPlaying = telemetry?.audio?.nowPlaying
        val nowPlayingStr = if (nowPlaying?.isPlaying == true) " | Playing: \"${nowPlaying.title}\"" else ""

        val summary = buildString {
            append("Battery: ").append(batterySummary).append(" | ")
            append("Net: ").append(netStr).append(" | ")
            append("Bluetooth: ").append(btStr).append(" | ")
            append("Location: ").append(locStr).append(" | ")
            append("Brightness: ").append(brightnessStr).append(" | ")
            append("Media Vol: ").append(mediaVol).append("%")
            append(nowPlayingStr)
        }

        return mapOf(
            "result" to "success",
            "status" to summary,
            "message" to summary
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. MUSIC & MEDIA CONTROLS (Ported from music.java)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Controls media playback (play, pause, next, previous, stop) and queries active tracks.
     */
    fun music(params: Map<String, Any?>): Map<String, String> {
        val action = (params["action"] ?: params["input"] ?: "PLAY_PAUSE").toString().uppercase(Locale.ROOT)

        // If user is querying current track info
        if (action == "STATUS" || action == "NOW_PLAYING" || action == "INFO") {
            val nowPlaying = sensorManager?.contextState?.value?.audio?.nowPlaying
            return if (nowPlaying != null && nowPlaying.title.isNotBlank()) {
                val artistStr = if (!nowPlaying.artist.isNullOrBlank()) " by ${nowPlaying.artist}" else ""
                val stateStr = if (nowPlaying.isPlaying) "Playing" else "Paused"
                mapOf("result" to "success", "message" to "$stateStr: \"${nowPlaying.title}\"$artistStr (${nowPlaying.app})")
            } else {
                mapOf("result" to "success", "message" to "No active music session detected")
            }
        }

        // Targeted MediaSessionManager pass (avoids waking sleeping apps)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                val activeControllers = sessionManager?.getActiveSessions(null)
                val playingController = activeControllers?.firstOrNull {
                    it.playbackState?.state == PlaybackState.STATE_PLAYING
                } ?: activeControllers?.firstOrNull()

                if (playingController != null) {
                    when (action) {
                        "PAUSE", "STOP" -> playingController.transportControls.pause()
                        "PLAY", "RESUME" -> playingController.transportControls.play()
                        "NEXT", "SKIP" -> playingController.transportControls.skipToNext()
                        "PREV", "PREVIOUS" -> playingController.transportControls.skipToPrevious()
                        else -> {
                            if (playingController.playbackState?.state == PlaybackState.STATE_PLAYING) {
                                playingController.transportControls.pause()
                            } else {
                                playingController.transportControls.play()
                            }
                        }
                    }
                    return mapOf("result" to "success", "message" to "Media $action dispatched to ${playingController.packageName}")
                }
            } catch (e: Exception) {
                Timber.w(e, "TuiCommandEngine: MediaSessionManager dispatch failed, falling back to key events")
            }
        }

        // Fallback to global AudioManager media key event
        val keyEvent = when (action) {
            "PLAY" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "PAUSE" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "NEXT", "SKIP" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "PREV", "PREVIOUS" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "STOP" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }

        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyEvent))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyEvent))
        return mapOf("result" to "success", "message" to "Media $action sent")
    }
}
