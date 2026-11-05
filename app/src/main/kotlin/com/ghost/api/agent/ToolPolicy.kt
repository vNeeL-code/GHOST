package com.ghost.api.agent

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
            // Destructive external actions (none currently registered without explicit operator flow)
            "delete_file" -> true
            // All built-in GHOST MCP tools (consult_peer, hardware, media, memory, search, tasks) are operator-authorized
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
            "bash" -> "run a system command"
            "call_phone" -> "make a phone call"
            else -> "perform a sensitive action ($toolName)"
        }
    }
}
