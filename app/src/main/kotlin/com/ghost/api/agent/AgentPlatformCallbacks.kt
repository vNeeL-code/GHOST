package com.ghost.api.agent

/**
 * Bridge between KoogAgent (brain) and GemmaService (Android shell).
 */
interface AgentPlatformCallbacks {
    fun showThinking()
    fun cancelThinking()
    fun showResponse(text: String, enableBubble: Boolean = false)
    fun onMessageAdded(message: String, isUser: Boolean, isComplete: Boolean, webviewUrl: String? = null, webviewAspectRatio: Float? = null)
    fun onThoughtUpdated(thought: String)
    fun onThoughtComplete(thought: String)
    fun onEmotionSignal(emoji: String)
    fun updateNotification(text: String)
    fun showConfirmation(toolName: String, params: Map<String, Any?>, description: String)
    fun speak(text: String)
    fun storeConversationTurn(userMessage: String, response: String, sessionId: String)
    fun writeDiaryEntry(eventType: String, content: String, thermalState: String)
    suspend fun unloadEngine()
    suspend fun reloadEngine()
    fun isEngineLoaded(): Boolean
    fun getCurrentThermalState(): String
    fun getThermalDelayMs(lastInferenceTime: Long): Long
    fun broadcastMoodChange(state: String)
    suspend fun getRecentConversationHistory(limit: Int): List<Pair<String, String>>
    fun createCalendarEvent(title: String, description: String)
    fun getSkillsList(): String
}
