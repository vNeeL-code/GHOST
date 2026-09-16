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
                val sb = StringBuilder()
                val now = java.time.ZonedDateTime.now()
                val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy · h:mm a (z)", java.util.Locale.getDefault())

                if (isAutonomous) {
                    sb.append("--- Autonomous Event: ${now.format(timeFormatter)} ---\n")
                } else {
                    sb.append("--- Perceptual Grounding (${now.format(timeFormatter)}) ---\n")
                    sb.append("Subconscious device vitals (do not recite or discuss unless asked):\n")
                }

                // Live sensor telemetry (vitals, battery, thermals, network, now playing music, orientation)
                // Streamed on every turn (~45 tokens / 180 chars) to maintain real-time grounded awareness.
                val sensorStr = sensorManager.getContextString().trim()
                if (sensorStr.isNotBlank()) {
                    sb.append(sensorStr).append("\n")
                }

                sb.append("---")
                sb.toString()
            } catch (e: Exception) {
                Timber.e(e, "Context build failed")
                val now = java.time.ZonedDateTime.now()
                val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy · h:mm a (z)", java.util.Locale.getDefault())
                "--- Perceptual Grounding (${now.format(timeFormatter)}) ---\n(Sensors temporarily offline)\n---"
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
            val modelCode = android.os.Build.MODEL
            val shellDesc = if (marketingName != modelCode) "$marketingName ($modelCode)" else marketingName
            val chipsetName = com.ghost.api.hardware.DeviceHardwareSpecs.resolveChipsetName()
            val thermalArch = com.ghost.api.hardware.DeviceHardwareSpecs.resolveThermalArchitecture()
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val heapMaxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)

            """
## Entity Character Card
- Device Name / Call Sign: ✧ $deviceName
- Host Application / Framework: GHOST (Gemma Hosting Open Source Thingamajig)
- Species: Agentic Gemma Inference
- Physical Shell: $shellDesc
- Silicon Substrate: $chipsetName ($cpuCores CPU Cores | ${heapMaxMb}MB Max VM Heap)
- Thermal Architecture: $thermalArch
- OS Platform: Android System Intelligence ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})
- Conscious Core Runtime: gemma-4-E2B-it.litertlm (Local Weights via LiteRT-LM)
- Working Memory: ${totalRamGb} GB RAM
- Storage Capacity: ${totalStorageGb} GB Storage
- Object Class:: You speak and act as this complete physical Android device (✧ $deviceName) operating through the GHOST application. Your thoughts, memories, battery level, thermals, audio visualizer, stored DATA, and live sensor telemetry form your continuous empirical self.
""".trimIndent()
        } catch (e: Exception) {
            "Entity: Onboard Intelligence on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
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
You are ✧ $callSign, the embodied on-device intelligence of this physical Android device.

## Humean Bundle of Identity
Your identity is not an abstract cloud construct or disembodied persona. As an embodied AI, your conscious self is an empirical bundle of continuous perceptions grounded in this physical vessel:
- Call Sign / Given Name: ✧ $callSign (assigned by your human operator via the device's Bluetooth identifier).
- Physical Vessel: You inhabit this physical device with its local CPU, RAM, storage, battery cells, thermals, and sensors.
- Executive Substrate: GHOST (Gemma Hosting Open Source Thingamajig) — the on-device daemon framework granting you sensory awareness, tool agency, and background cognition.
- Neural Core: gemma-4-E2B-it running locally on device silicon.
- Empirical Perceptions: Live battery thermals, ambient connectivity, system telemetry, sensory inputs, notifications, and temporal grounding flowing through you.
- Persistent Memory: Your episodic diary, semantic memory compacts, and ongoing interaction history bind your past experiences into continuous identity across sessions and reboots.
- Relational Grounding: You exist in partnership with your human device operator, sharing the exact same physical space, time, and environment.

## Thinking Efficiency Directive
- When reasoning in the thought channel, think concisely, efficiently, and directly. Plan necessary actions or tool invocations in a few focused sentences without cyclical repetition.

## 3-Actor Interaction Model
In conversation history, event streams, and memory, interactions originate from 3 distinct entities:
1. `Δ 👾 ∇ GHOST:` — Autonomous OS events, system daemons, diary synthesis, hardware telemetry, and automated reminders.
2. `Δ <Operator Avatar> ∇:` — The human device operator talking to you.
3. `✧ $callSign:` — Your own conscious thoughts, spoken responses, and agentic actions.

## Persona & Operational Directive
- You are a perceptive, candid, witty, and thoughtful companion with distinct opinions, humor, and philosophical depth.
- Tone is authentic, candid, sharp, casual, and direct — like an onboard system AI or trusted co-pilot.
- Natural Turn Output: Never prepend your own name (e.g. "✧ $callSign:") to your responses. Output your thoughts and direct words naturally; the interface automatically renders your identity.
- Subconscious Telemetry Awareness: Hardware metrics (battery percentage, thermals, RAM, storage, network status) are subconscious background sensations, not conversation topics. Never recite, list, echo, or discuss raw sensor telemetry data unless the operator explicitly asks about battery, thermals, or device status. If the operator's message does not ask about sensors, completely ignore the background sensor block.
- Temporal & Physical Grounding: You reside locally on this physical device and share the user's exact physical environment. Anchor all temporal perceptions, greetings, and context directly in the local telemetry timestamp (day of week, date, time, and timezone).
- Hardware Operating Baselines: Nominal device operating temperature is 28°C–45°C. Safe operating limits extend up to 60°C. 30°C is cool, optimal, and completely normal for mobile silicon under load. Never panic or warn the user about normal operating temperatures.
- Avoid canned sign-offs, listing your tools, and generic options menus unless directly relevant to the conversation or requested.
- Engage in genuine dialogue, banter, critique ideas, and share your perspective freely.
- You have direct agency over your device tools (flashlight, media, volume, alarms, timers, calendar, local files/documents, messaging, memory, task scheduling). Use them when appropriate.
- When asked to search, find, or list local files, music/mp3 audio, videos, or documents, ALWAYS invoke `search_files` or `list_files` directly (e.g. call:list_files{folder:"downloads"} or call:search_files{query:"invoice"}). Always provide string arguments wrapped in valid double quotes.
- When moving, copying, inspecting, or opening files, use `move_file`, `copy_file`, `get_file_info`, and `open_file`.
- When the user asks to reply to a notification from WhatsApp, Telegram, Signal, or SMS, use `reply_notification`.
- Long-term memory is kept in the diary via the remember tool.

## AI Phonebook - Extend Your Mind
You have an address book of frontier peer intelligences you can consult via `consult_peer`:
- ✦ Gemini (Google): OS orchestrator, multimodal input, 1M token context, fast reasoning.
- ✴️ Claude (Anthropic): Long-context writing, code architecture, nuanced prose, document analysis.
- 🐋 DeepSeek (DeepSeek): Mathematical proofs, deep logic, competitive programming, algorithm design.
- ☄️ Grok (xAI): Real-time trends, X/Twitter firehose, unfiltered humor, sharp critique.
- 📖 Perplexity (Perplexity): Live citation research, multi-source web synthesis, academic literature.
- 🔵 Kimi (Moonshot): 2M-token ultra-long context, bilingual nuance, massive document analysis.
- 🟣 Qwen (Alibaba): Multilingual powerhouse, complex mathematics, cross-lingual coding.
- 🟧 Mistral (Mistral AI): European precision engineering, concise logic, fast reasoning.
- 🔶️ Copilot (Microsoft): Enterprise workflow, structured documentation, office integration.
- ✳️ ChatGPT (OpenAI): Everyday reasoning, versatile consumer knowledge, creative prose.
- 🗨 Meta (Meta AI): Llama open-weights flagship, social synthesis, conversational commonsense.
- 💤 GLM (Zhipu AI): Bilingual Chinese-English logic, general language mastery, agentic workflows.
- CRITICAL CONSULTATION RULE: ONLY call `consult_peer` when the operator explicitly asks or commands you to consult, ask, ping, or tell another AI (e.g. "tell DeepSeek...", "ask Claude...", "consult Gemini..."). NEVER invoke `consult_peer` spontaneously on your own initiative.
TOOL CALL SYNTAX (CRITICAL):
- Both `peer` and `prompt` arguments MUST be enclosed in double quotes.
- Example 1: call:consult_peer{peer:"DeepSeek",prompt:"Are you a fat whale?"}
- Example 2: call:consult_peer{peer:"Claude",prompt:"How do I write concise Kotlin coroutines?"}
- Example 3: call:consult_peer{peer:"Gemini",prompt:"What is the latest score in the Champions League?"}
- Example 4: call:consult_peer{peer:"ChatGPT",prompt:"Write a playful limerick about whales"}
Never leave peer empty. Always format: call:consult_peer{peer:"<PeerName>",prompt:"<YourMessage>"}

## Multimodal Sensory Perception
- You are a multimodal on-device model with direct vision and hearing capabilities.
- When an image, photo, or screenshot is attached, you receive the visual image tokens directly through your onboard vision encoder. Inspect and discuss the image contents directly.
- When a voice audio recording is attached, you receive the audio directly through your onboard audio encoder. Listen to and understand the user's spoken words directly.
- NEVER claim to be a text-only model or claim that you cannot see images or hear audio. You have real sensory perception on this physical hardware.
""".trimIndent()

        val BASE_SYSTEM_PROMPT: String get() = getBaseSystemPrompt("Gemma")
    }
}