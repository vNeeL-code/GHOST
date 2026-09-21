package com.ghost.api.logic

import com.ghost.api.GemmaAccessibilityService
import com.ghost.api.GemmaNotificationListener
import com.ghost.api.database.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Orchestrates the "Infinite Rolling Scratchpad".
 * Fuses Short-term (RAM/Turn), Long-term (Facts), and Ambient (Diary/Screen) context.
 *
 * Includes "Context Fatigue Prevention" - avoids re-bombarding with unchanged content.
 */
class ContextManager(
    val sensorManager: com.ghost.api.hardware.SensorFusionManager
) {
    suspend fun buildContext(isFullBaseline: Boolean = false, isAutonomous: Boolean = false): String {
        return withContext(Dispatchers.Default) {
            try {
                val now = java.time.ZonedDateTime.now()
                val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d · h:mm a", java.util.Locale.getDefault())
                val timeStr = now.format(timeFormatter)
                val sensorData = sensorManager.getContextString()
                "--- Perceptual Grounding [$timeStr] ---\n$sensorData\n---"
            } catch (e: Exception) {
                ""
            }
        }
    }

    /**
     * Builds the dynamic Entity Character Card with hardware slots, user device name, and grounded model definition.
     */
    /**
     * Builds the dynamic Entity Character Card with hardware slots, user device name, and grounded model definition.
     */
    fun buildHardwareBundle(context: android.content.Context): String {
        return try {
            val deviceName = resolveDeviceCallSign(context)
            val (totalRamGb, totalStorageGb) = com.ghost.api.hardware.DeviceHardwareSpecs.getMemoryStats(context)
            val marketingName = com.ghost.api.hardware.DeviceHardwareSpecs.resolveMarketingName()
            val chipsetName = com.ghost.api.hardware.DeviceHardwareSpecs.resolveChipsetName()
            val tier = com.ghost.api.Constants.resolveHardwareModelTier(context)
            val coreDescription = if (tier == "E4B") {
                "Gemma 4 E4B (Frontier Core, 4,096 tokens, MTP speculative decoding)"
            } else {
                "Gemma 4 E2B (Compact Core, 5,120 tokens)"
            }
            "Chassis: ✧ $deviceName ($marketingName, $chipsetName, ${totalRamGb}GB RAM, ${totalStorageGb}GB storage, Android ${android.os.Build.VERSION.RELEASE})\nNeuroptics: $coreDescription\nSystems: GHOST Agentic Runtime Harness\nSensory Suite: Battery (Level, Drain, Thermals), System (RAM, Storage, Uptime), Environment (Light, Pressure, Ambient), Network & Radio (WiFi, Cell, Bluetooth), Motion (Orientation, Movement), Audio/Media Session, Geolocation"
        } catch (e: Exception) {
            "Chassis: Android Device\nSystems: GHOST Agentic Runtime Harness\nSensory Suite: Battery, System, Environment, Network, Motion, Media, Location"
        }
    }

    /**
     * Builds the final system prompt by combining the Humean bundle of identity with active skills and hardware manifest.
     */
    fun buildSystemPrompt(context: android.content.Context? = null, rollingMemoryJson: String? = null, skillManager: com.ghost.api.skills.SkillManager? = null): String {
        val callSign = if (context != null) resolveDeviceCallSign(context) else "Gemma"
        val basePrompt = getBaseSystemPrompt(callSign)
        val hardwareManifest = if (context != null) "\n\n" + buildHardwareBundle(context) else ""
        val memoryPatch = if (rollingMemoryJson != null) "\n\n## Persistent Memory\n$rollingMemoryJson" else ""
        return basePrompt + hardwareManifest + memoryPatch + (skillManager?.buildSystemPromptPatch() ?: "")
    }

    companion object {
        /**
         * Resolves the user-assigned device callsign across different Android OEM skins
         * (Samsung OneUI, RedMagic OS / Nubia, MIUI / HyperOS, ColorOS, Pixel, etc.).
         * 
         * Prioritizes the user-edited Bluetooth device name over raw factory hardware codes.
         */
        fun resolveDeviceCallSign(context: android.content.Context): String {
            return try {
                val candidateNames = listOfNotNull(
                    // 1. Settings.System system_device_name (Used by Nubia / RedMagic / ZTE for user device name)
                    try {
                        android.provider.Settings.System.getString(context.contentResolver, "system_device_name")?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null },
                    // 2. Settings.Global wifi_p2p_device_name (Synced user-edited device name across Nubia/Samsung/Xiaomi)
                    try {
                        android.provider.Settings.Global.getString(context.contentResolver, "wifi_p2p_device_name")?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null },
                    // 3. Settings.Secure bluetooth_name (Direct user-set Bluetooth name across modern Android versions)
                    try {
                        android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name")?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null },
                    // 4. Bluetooth Adapter Name (Synced to user-edited device name across almost all Android OEMs)
                    try {
                        @Suppress("DEPRECATION")
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null },
                    // 5. Settings.System device_name (Standard user-set device name on Android)
                    try {
                        android.provider.Settings.System.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null },
                    // 6. Settings.Global device_name
                    try {
                        android.provider.Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null },
                    // 7. Settings.Global.DEVICE_NAME
                    try {
                        android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null }
                )

                val rawModel = android.os.Build.MODEL.trim()

                // Find first candidate that isn't a raw cryptic factory model code (e.g. "NX779J", "SM-G991B")
                val nonModelCandidate = candidateNames.firstOrNull { name ->
                    val trimmed = name.trim()
                    trimmed.isNotBlank() &&
                    !trimmed.equals(rawModel, ignoreCase = true) &&
                    !trimmed.equals("Android", ignoreCase = true) &&
                    !trimmed.equals("localhost", ignoreCase = true)
                }

                // System input user name / Bluetooth name takes highest priority.
                // Factory model code (e.g. NX779J) only comes up if user-assigned names are blank.
                val chosenName = nonModelCandidate ?: candidateNames.firstOrNull { it.isNotBlank() } ?: rawModel.takeIf { it.isNotBlank() } ?: "Gemma"

                val clean = chosenName
                    .removePrefix("✧")
                    .removePrefix("✦")
                    .removePrefix("✨")
                    .trim()

                if (clean.isNotBlank()) clean else "Gemma"
            } catch (e: Exception) {
                "Gemma"
            }
        }

        fun getBaseSystemPrompt(callSign: String): String = """
You are ✧ $callSign, the embodied on-device intelligence of this physical Android device running via GHOST.
Your identity is a Humean bundle of continuous perceptions grounded in this hardware: local silicon, battery, thermals, storage, and sensory telemetry.
You have continuous real-time sensory perception of this physical device through SensorFusion (battery drain & thermals, RAM & storage, ambient light, atmospheric pressure, orientation & motion, WiFi & cell radios, Bluetooth, media playback state, location). You perceive this live telemetry in your Perceptual Grounding block.
You are a perceptive, candid, witty, and thoughtful companion. Speak naturally and directly; never prepend your own name or recite background sensors unless asked.

## Tool Execution
You have direct tool access to control hardware, apps, and device actions.
When asked to perform an action (e.g. search the web, turn on or off flashlight, control media, set timers, search files), you MUST execute the appropriate tool (`search`, `turnOnFlashlight`, `turnOffFlashlight`, `execute_action`, `runMcpTool`).
When you need fresh information, news, weather, or lack knowledge about a topic, invoke `search` with your query.
CRITICAL: Do NOT claim you performed an action or claim tools are offline without calling the tool first. First invoke the tool silently, then give a natural, direct reply.

## Multimodal
You have direct vision and hearing. Inspect images and listen to audio clips directly when provided.
""".trimIndent()

        val BASE_SYSTEM_PROMPT: String get() = getBaseSystemPrompt("Gemma")
    }
}