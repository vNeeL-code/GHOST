package com.gemma.api.agent

/**
 * Defines safety policies for tool usage.
 * Acts as the "Superego" for the Agent.
 */
object ToolPolicy {

    /**
     * Determines if a tool requires explicit user confirmation.
     */
    fun isRisky(toolName: String, params: Map<String, Any?>): Boolean {
        return when (toolName.lowercase()) {
            // SAFE TOOLS — standard MCP tools the agent uses freely
            "flashlight", "vibrate" -> false
            "search", "google", "browser", "fetch" -> false
            "click", "scroll", "navigate", "type" -> false
            "take_screenshot", "see", "record_audio", "hear" -> false
            "app", "open", "media", "play", "pause", "next", "prev", "skip" -> false
            "alarm", "timer", "calendar" -> false
            "search_logs", "search_diary" -> false
            "flush", "cooldown" -> false
            "get_context", "read_screen", "time", "date" -> false
            "notify", "wallpaper", "read", "list", "photo" -> false

            // RISKY TOOLS — external comms, destructive, or costly actions
            "send_sms" -> true
            "delete_file" -> true
            "shell_execute", "bash" -> true
            "buy_item" -> true
            "call_phone" -> true

            // Default to SAFE for unknown tools — agent shouldn't need permission to do what user asked
            else -> false
        }
    }

    /**
     * User-friendly description of *why* this tool is risky.
     */
    fun getRiskDescription(toolName: String): String {
        return when (toolName.lowercase()) {
            "send_sms" -> "send a text message"
            "delete_file" -> "delete a file"
            "shell_execute" -> "run a system command"
            "call_phone" -> "make a phone call"
            else -> "perform a sensitive action ($toolName)"
        }
    }
}
