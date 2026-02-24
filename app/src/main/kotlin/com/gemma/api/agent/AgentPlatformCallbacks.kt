package com.gemma.api.agent

/**
 * Bridge between KoogAgent (brain) and GemmaService (Android shell).
 *
 * KoogAgent calls these to interact with the platform without knowing
 * about Android lifecycle, notifications, TTS, or database internals.
 */
interface AgentPlatformCallbacks {

    // === UI ===
    fun showThinking()
    fun showResponse(text: String)
    fun updateNotification(text: String)
    fun showConfirmation(toolName: String, params: Map<String, Any?>, description: String)

    // === TTS ===
    fun speak(text: String)

    // === Persistence ===
    fun storeConversationTurn(userMessage: String, response: String, sessionId: String)
    fun writeDiaryEntry(eventType: String, content: String, thermalState: String)

    // === Engine Lifecycle ===
    fun unloadEngine()
    suspend fun reloadEngine()
    fun isEngineLoaded(): Boolean

    // === Thermal ===
    fun getCurrentThermalState(): String
    fun getThermalDelayMs(lastInferenceTime: Long): Long

    // === Mood (MacroDroid integration) ===
    fun broadcastMoodChange(state: String)

    // === Diary / History ===
    /** Get recent persisted conversation history (from DB) for diary grounding */
    suspend fun getRecentConversationHistory(limit: Int): List<Pair<String, String>>

    /** Write a calendar event (title, description) at current time */
    fun createCalendarEvent(title: String, description: String)
}
