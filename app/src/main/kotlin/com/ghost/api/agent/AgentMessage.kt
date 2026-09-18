package com.ghost.api.agent

/**
 * Canonical dialogue message representation across GhostAgent,
 * SessionMemoryCompactor, and GemmaService.
 */
data class AgentMessage(
    val role: String, // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val hadImage: Boolean = false,
    val hadAudio: Boolean = false
)
