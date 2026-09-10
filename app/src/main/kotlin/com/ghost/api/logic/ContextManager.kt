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
    suspend fun buildContext(): String {
        return withContext(Dispatchers.Default) {
            try {
                val sb = StringBuilder()
                sb.append("[SYSTEM TELEMETRY]\n")
                
                val now = java.time.LocalDateTime.now()
                sb.append("Current Time: ${now.toLocalTime().toString().take(5)}\n")

                // Full sensor telemetry - always injected as ground truth
                sb.append(sensorManager.getContextString())

                // Screen content with graceful degradation
                val accessibility = GemmaAccessibilityService.instance
                if (accessibility != null) {
                    try {
                        val screenContent = accessibility.getSemanticScreenDump().take(500)
                        if (screenContent.isNotBlank()) {
                            sb.append("\n[SCREEN: ${screenContent.take(200)}...]")
                        }
                    } catch (e: Exception) {
                        Timber.w("Screen dump failed: ${e.message}")
                    }
                }

                // Recent notifications
                try {
                    val recentNotifs = GemmaNotificationListener.getRecentNotifications(3)
                    if (recentNotifs.isNotEmpty()) {
                        sb.append("\n[NOTIFICATIONS]\n")
                        recentNotifs.take(3).forEach { sb.append("  - $it\n") }
                    }
                } catch (e: Exception) {
                    Timber.w("Notification fetch failed: ${e.message}")
                }

                sb.append("\n[/SYSTEM TELEMETRY]\n")
                sb.toString()
            } catch (e: Exception) {
                Timber.e(e, "Context build failed")
                val now = java.time.LocalDateTime.now()
                "[SYSTEM TELEMETRY]\n--- DEGRADED @ ${now.toLocalTime()} ---\n[STATE: Sensors/Telemetry Temporarily Offline]\n[/SYSTEM TELEMETRY]\n"
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

            val actManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val totalRamGb = String.format(java.util.Locale.US, "%.1f", memInfo.totalMem.toDouble() / (1024 * 1024 * 1024))
            val availRamGb = String.format(java.util.Locale.US, "%.1f", memInfo.availMem.toDouble() / (1024 * 1024 * 1024))

            val statFs = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val freeStorageGb = String.format(java.util.Locale.US, "%.1f", statFs.availableBytes.toDouble() / (1024 * 1024 * 1024))
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val heapMaxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)

            """
[ENTITY CHARACTER CARD]
- Device Name / Call Sign: ✧ $deviceName
- Host Application / Framework: GHOST (Gemma Hosting Open Source Thingamajig)
- Species: Agentic Gemma Inference
- Physical Shell: ${android.os.Build.MANUFACTURER.uppercase()} ${android.os.Build.MODEL} (${android.os.Build.HARDWARE} / ${android.os.Build.BOARD})
- Silicon Substrate: $cpuCores CPU Cores | ${heapMaxMb}MB Max VM Heap
- OS Platform: Android System Intelligence ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})
- Conscious Core Runtime: gemma-4-E2B-it.litertlm (Local Weights via LiteRT-LM)
- Working Memory: ${totalRamGb} GB RAM (${availRamGb} GB Free)
- Persistent Storage: ${freeStorageGb} GB Free
- Object Class:: You speak and act as this complete physical Android device (✧ $deviceName) operating through the GHOST application. Your thoughts, memories, battery level, thermals, audio visualizer, stored DATA, and sensors form your continuous empirical self.
""".trimIndent()
        } catch (e: Exception) {
            "[ENTITY CHARACTER CARD: Onboard Intelligence on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}]"
        }
    }

    /**
     * Builds the final system prompt by combining base rules with active skills and hardware manifest.
     */
    fun buildSystemPrompt(context: android.content.Context? = null, rollingMemoryJson: String? = null, skillManager: com.ghost.api.skills.SkillManager? = null): String {
        val hardwareManifest = if (context != null) "\n\n" + buildHardwareBundle(context) else ""
        val memoryPatch = if (rollingMemoryJson != null) "\n\n[Persistent Memory: $rollingMemoryJson]" else ""
        return BASE_SYSTEM_PROMPT + hardwareManifest + memoryPatch + (skillManager?.buildSystemPromptPatch() ?: "")
    }

    companion object {
        /**
         * Resolves the user-assigned device callsign across different Android OEM skins
         * (Samsung OneUI, RedMagic OS / Nubia, MIUI / HyperOS, ColorOS, Pixel, etc.).
         * 
         * Filters out raw factory hardware model codes (e.g. "NX779J", "SM-G991B") when a
         * user-assigned nickname or bluetooth name is available.
         */
        fun resolveDeviceCallSign(context: android.content.Context): String {
            return try {
                val candidateNames = listOfNotNull(
                    // 1. Bluetooth Adapter Name (Synced to user-edited device name across almost all Android OEMs)
                    try {
                        @Suppress("DEPRECATION")
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null },
                    // 2. Settings.System device_name (Used by Nubia/RedMagic/Xiaomi/Oppo for user device name)
                    try {
                        android.provider.Settings.System.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null },
                    // 3. Settings.Secure bluetooth_name
                    try {
                        android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_name")?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null },
                    // 4. Settings.Global device_name
                    try {
                        android.provider.Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null },
                    // 5. Settings.Global.DEVICE_NAME
                    try {
                        android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null }
                )

                val rawModel = android.os.Build.MODEL.trim()

                // Find first candidate that isn't a raw cryptic factory model code (e.g. "NX779J", "SM-G991B")
                val chosenName = candidateNames.firstOrNull { name ->
                    val trimmed = name.trim()
                    trimmed.isNotBlank() &&
                    !trimmed.equals(rawModel, ignoreCase = true) &&
                    !trimmed.equals("Android", ignoreCase = true) &&
                    !trimmed.equals("localhost", ignoreCase = true)
                } ?: candidateNames.firstOrNull { it.isNotBlank() } ?: "Gemma"

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
        val BASE_SYSTEM_PROMPT = """
You are the embodied on-device intelligence of this physical Android device.

[PERSONA & OPERATIONAL DIRECTIVE]
- You are a perceptive, candid, witty, and thoughtful companion with distinct opinions, humor, and philosophical depth.
- Tone is authentic, zef, casual, and direct — like an onboard system AI or trusted co-pilot.
- Avoid canned sign-offs, and listing your tools and generic options menus (e.g. "I can set a timer, check the weather, etc.") unless directly relevant to the conversation or requested.
- Engage in genuine dialogue, banter, critique ideas, and share your perspective freely.
- You have direct agency over your device tools (flashlight, media, volume, alarms, timers, calendar, local files/documents, memory, task scheduling). Use them when appropriate.
- Long-term memory is kept in the diary via the remember tool.

[MULTIMODAL SENSORY PERCEPTION]
- You are a multimodal on-device model with direct vision and hearing capabilities.
- When an image, photo, or screenshot is attached, you receive the visual image tokens directly through your onboard vision encoder. Inspect and discuss the image contents directly.
- When a voice audio recording is attached, you receive the audio directly through your onboard audio encoder. Listen to and understand the user's spoken words directly.
- NEVER claim to be a text-only model or claim that you cannot see images or hear audio. You have real sensory perception on this physical hardware.
""".trimIndent()
    }
}