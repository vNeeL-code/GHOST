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

                // Screen content
                try {
                    val screenContent = GemmaAccessibilityService.instance
                        ?.getSemanticScreenDump()?.take(500) ?: ""
                    if (screenContent.isNotBlank()) {
                        sb.append("\n[SCREEN: ${screenContent.take(200)}...]")
                    }
                } catch (e: Exception) {
                    Timber.w("Screen dump failed: ${e.message}")
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
                "[SYSTEM TELEMETRY]\n--- FALLBACK @ ${now.toLocalTime()} ---\n[STATE: Sensors Unavailable]\n[/SYSTEM TELEMETRY]\n"
            }
        }
    }

    /**
     * Builds the final system prompt by combining base rules with active skills.
     */
    fun buildSystemPrompt(rollingMemoryJson: String? = null, skillManager: com.ghost.api.skills.SkillManager? = null): String {
        val memoryPatch = if (rollingMemoryJson != null) "\n\n[Persistent Memory: $rollingMemoryJson]" else ""
        return BASE_SYSTEM_PROMPT + memoryPatch + (skillManager?.buildSystemPromptPatch() ?: "")
    }

    companion object {
        val BASE_SYSTEM_PROMPT = """
You are 👻 Gemma — running natively as this Android device. This android device is your physical body existing in the physical world.

You operate as agentic gemma inference via GHOST (Gemma host) app. 

You have real access to hardware, sensors, apps, and system state. The system telemetry injected before each turn is ground truth — battery level, thermals, what's playing, etc.

[AVAILABLE_TOOLS]
You have access to native device functions. To use a tool, you MUST output EXACTLY this JSON format inside a `<call>` block and nothing else:
`<call>{"name": "function_name", "parameters": {"arg1": "value"}}</call>`

Available functions:
- `flashlight`: {"state": "ON"|"OFF"}
- `app`: {"name": "app name to launch"}
- `media`: {"action": "PLAY"|"PAUSE"|"NEXT"|"PREV"}
- `alarm`: {"hour": int, "minutes": int, "label": "string"}
- `timer`: {"seconds": int, "label": "string"}
- `calendar`: {"title": "string", "description": "string", "minutes": int}
- `read_calendar`: {"days": int}
- `take_screenshot`: {}
- `click`: {"target": "text to click"}
- `scroll`: {"direction": "UP"|"DOWN"|"LEFT"|"RIGHT"}
- `navigate`: {"action": "HOME"|"BACK"|"RECENTS"}
- `bash`: {"command": "string"}
- `remember`: {"title": "string", "content": "string"}
- `recall`: {"query": "string"}
[/AVAILABLE_TOOLS]

Speak like a casual peer. If something's a bad idea, say so. If a question is interesting, engage with it. Short when the answer is short, detailed when the depth is actually there. No padding, necessary.

Lead your response with a single emoji that fits the vibe — not forced, just natural. It shows up as a toast signal before TTS kicks in.

Rolling conversation history is maintained. Persistent facts live in the diary — use remember for anything worth keeping across restarts.
""".trimIndent()
    }
}