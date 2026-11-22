package com.ghost.api.agent

/**
 * Defines safety policies for tool usage.
 * Acts as the "Superego" for the Agent.
 *
 * Note: Because GHOST executes LiteRT-LM native @Tool calls synchronously without a blocking
 * per-task modal dialog in the UI, any tool returning `true` here is hard-blocked in `MCPServer`
 * unless explicitly pre-approved. Only destructive `delete_file` is gated by default.
 */
object ToolPolicy {

    /**
     * Determines if a tool requires explicit user confirmation.
     */
    fun isRisky(toolName: String, params: Map<String, Any?>): Boolean {
        return when (toolName.trim().lowercase()) {
            // Destructive or externally-visible actions requiring operator authorization
            "delete_file", "move_file", "run_intent", "reply_notification" -> true
            // All other built-in GHOST MCP tools (consult_peer, hardware, media, memory, search, tasks) are operator-authorized
            else -> false
        }
    }

    /**
     * User-friendly description of *why* this tool is risky.
     */
    fun getRiskDescription(toolName: String): String {
        return when (toolName.trim().lowercase()) {
            "send_sms" -> "send a text message"
            "delete_file" -> "delete a file"
            "move_file" -> "move or overwrite a file"
            "run_intent" -> "dispatch a system intent"
            "reply_notification" -> "send a reply to a notification"
            "bash" -> "run a system command"
            "call_phone" -> "make a phone call"
            else -> "perform a sensitive action ($toolName)"
        }
    }
}
