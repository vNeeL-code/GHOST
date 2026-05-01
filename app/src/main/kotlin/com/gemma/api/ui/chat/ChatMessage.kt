package com.gemma.api.ui.chat

/**
 * Enhanced ChatMessage with support for event types (e.g. LOGIC_TRACE, DREAM)
 */
data class ChatMessage(
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String? = null
)
