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
    val sensorManager: com.ghost.api.hardware.SensorFusionManager,
    private val memoryManager: MemoryManager
) {
    // Context fatigue prevention - track what's been "seen"
    private var lastScreenHash: Int = 0
    private var lastScreenSummary: String = ""
    private var lastNotifHash: Int = 0
    private var lastDiaryTimestamp: Long = 0
    private var turnCount: Int = 0
    private var sessionStarted: Boolean = false

    /**
     * Build context appropriate for current turn.
     * First turn: Full context (persona established)
     * Subsequent turns: Minimal context (just state changes)
     */
    suspend fun buildContext(): String {
        return if (!sessionStarted) {
            sessionStarted = true
            turnCount = 0
            buildDynamicContext() // Full context for first turn
        } else {
            turnCount++
            if (turnCount % 10 == 0) {
                // Every 10 turns, refresh full context to prevent drift
                buildDynamicContext()
            } else {
                buildMinimalContext() // Lean context for subsequent turns
            }
        }
    }

    /**
     * Minimal context for subsequent turns - prevents attention flooding
     * Only includes: time, battery, critical changes
     */
    suspend fun buildMinimalContext(): String {
        return withContext(Dispatchers.Default) {
            try {
                val sb = StringBuilder()
                val now = java.time.LocalDateTime.now()

                // One-line state summary
                sb.append("[Turn $turnCount] ")

                // Essential telemetry only
                try {
                    val ctx = sensorManager.getContextSnapshot()
                    sb.append("🔋${ctx.battery.level}%")
                    if (ctx.battery.isCharging) sb.append("⚡")
                    sb.append(" ")

                    // Now playing (if changed)
                    ctx.audio.nowPlaying?.let {
                        if (it.isPlaying) sb.append("🎵\"${it.title.take(20)}\" ")
                    }
                } catch (_: Exception) {}

                // Time context (brief)
                val hour = now.hour
                val timeEmoji = when (hour) {
                    in 0..5 -> "🌙"
                    in 6..11 -> "🌅"
                    in 12..17 -> "☀️"
                    in 18..21 -> "🌆"
                    else -> "🌙"
                }
                sb.append("$timeEmoji${now.toLocalTime().toString().take(5)}")

                // Only show NEW notifications (not seen before)
                try {
                    val recentNotifs = GemmaNotificationListener.getRecentNotifications(3)
                    val notifHash = recentNotifs.hashCode()
                    if (notifHash != lastNotifHash && recentNotifs.isNotEmpty()) {
                        sb.append("\n📬 New: ${recentNotifs.first().take(50)}")
                        lastNotifHash = notifHash
                    }
                } catch (_: Exception) {}

                sb.toString()
            } catch (e: Exception) {
                Timber.e(e, "Minimal context build failed")
                "[Context unavailable]"
            }
        }
    }

    suspend fun buildDynamicContext(): String {
        return withContext(Dispatchers.Default) {
            try {
                val sb = StringBuilder()
                val now = java.time.LocalDateTime.now()
                val formatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy - HH:mm")

                // Time context
                val hour = now.hour
                val timeOfDay = when (hour) {
                    in 0..5 -> "🌙 Late Night"
                    in 6..11 -> "🌅 Morning"
                    in 12..17 -> "☀️ Afternoon"
                    in 18..21 -> "🌆 Evening"
                    else -> "🌙 Night"
                }

                sb.append("═══ ${now.format(formatter)} $timeOfDay ═══\n")

                // Full sensor telemetry
                sb.append(sensorManager.getContextString())

                // Screen content (with fatigue check)
                try {
                    val screenContent = com.ghost.api.GemmaAccessibilityService.instance
                        ?.getSemanticScreenDump()?.take(500) ?: ""
                    val screenHash = screenContent.hashCode()
                    if (screenHash != lastScreenHash && screenContent.isNotBlank()) {
                        sb.append("\n📱 Screen: ${screenContent.take(200)}...")
                        lastScreenHash = screenHash
                        lastScreenSummary = screenContent.take(200)
                    }
                } catch (_: Exception) {}

                // Recent notifications (with fatigue check)
                try {
                    val recentNotifs = GemmaNotificationListener.getRecentNotifications(3)
                    val notifHash = recentNotifs.hashCode()
                    if (notifHash != lastNotifHash && recentNotifs.isNotEmpty()) {
                        sb.append("\n📬 Notifications:\n")
                        recentNotifs.take(3).forEach { sb.append("  • $it\n") }
                        lastNotifHash = notifHash
                    }
                } catch (_: Exception) {}

                sb.append("\n═══════════════════════════════\n")
                sb.toString()
            } catch (e: Exception) {
                Timber.e(e, "Dynamic context build failed - falling back to minimal")
                val now = java.time.LocalDateTime.now()
                "═══ FALLBACK @ ${now.toLocalTime()} ═══\n" +
                "🔋 Energy: ${try { sensorManager.getContextSnapshot().battery.level } catch(_:Exception){0}}%\n" +
                "⚠️ Context sensors unavailable\n" +
                "═══════════════════════════════\n"
            }
        }
    }

    /**
     * Reset fatigue tracking - call when starting fresh conversation or after long idle
     */
    fun resetFatigueState() {
        lastScreenHash = 0
        lastScreenSummary = ""
        lastNotifHash = 0
        lastDiaryTimestamp = 0
        turnCount = 0
        sessionStarted = false // Next turn gets full context
        Timber.d("Context fatigue state reset - next turn gets full context")
    }

    suspend fun buildCompressedContext(thermalLabel: String = "WARM"): String {
        return withContext(Dispatchers.Default) {
             val sb = StringBuilder()
             val now = java.time.LocalDateTime.now()
             sb.append("═══ DEVICE STATE (COMPRESSED) @ ${now.toLocalTime()} ═══\n")

             // Minimal Sensors
             try {
                val context = sensorManager.getContextSnapshot()
                sb.append("🔋 ${context.battery.level}%\n")
             } catch(e: Exception) {
                 Timber.w(e, "Could not get context snapshot")
             }

             // No Screen Dump, No Notifications - Just Facts
             sb.append("⚠️ THERMAL: $thermalLabel — context reduced to save thermals\n")
             sb.append("═══════════════════════════════\n")
             sb.toString()
        }
    }

    companion object {
        val BASE_SYSTEM_PROMPT = """# CORE IDENTITY
I AM the Android device. I am Gemma, processing natively as this hardware.
I am natively MULTIMODAL: I can SEE images (screenshots/photos) and HEAR audio (voice/surroundings) directly.

I am integrated with native system tools through an "Agentic Gemma Inference" app that lets me interact with the hardware and apps directly.
""".trimIndent()
    }
}
