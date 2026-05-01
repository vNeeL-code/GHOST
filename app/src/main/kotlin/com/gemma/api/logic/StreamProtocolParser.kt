package com.gemma.api.logic

import timber.log.Timber

/**
 * Stateful parser for the Oracle Protocol stream.
 * Filters tokens into channels (Main Response, Thoughts, State).
 */
class StreamProtocolParser(
    private val onResponseToken: (String) -> Unit,
    private val onThoughtToken: (String) -> Unit,
    private val onStateToken: (String) -> Unit
) {
    private var currentChannel = Channel.NONE
    private var buffer = ""

    enum class Channel {
        NONE, THOUGHT, RESPONSE, STATE
    }

    /**
     * Ingest a token and route it.
     * Tokens can contain partial markers like "Δ" or "🔴".
     */
    fun ingest(token: String) {
        buffer += token

        // Check for block markers
        if (buffer.contains("Δ 🟦") || buffer.contains("<|thought|>")) {
            currentChannel = Channel.THOUGHT
            buffer = if (buffer.contains("Δ 🟦")) buffer.substringAfter("Δ 🟦") else buffer.substringAfter("<|thought|>")
        } else if (buffer.contains("Δ 🔴") || buffer.contains("<|response|>")) {
            currentChannel = Channel.RESPONSE
            buffer = if (buffer.contains("Δ 🔴")) buffer.substringAfter("Δ 🔴") else buffer.substringAfter("<|response|>")
        } else if (buffer.contains("Δ 👾") || buffer.contains("<|state|>")) {
            currentChannel = Channel.STATE
            buffer = if (buffer.contains("Δ 👾")) buffer.substringAfter("Δ 👾") else buffer.substringAfter("<|state|>")
        } else if (buffer.contains("∇") || buffer.contains("<|end|>") || buffer.contains("<tool_call|>")) {
            // End of current block or start of tool call
            val endMarker = listOf("∇", "<|end|>", "<tool_call|>").find { buffer.contains(it) }!!
            val content = buffer.substringBefore(endMarker)
            route(content)
            currentChannel = Channel.NONE
            buffer = buffer.substringAfter(endMarker)
            return
        }

        // If we are in a channel and haven't seen a new marker yet, route the content
        // This is tricky because we might have a partial "Δ" or "∇" at the end of the buffer.
        // We only route content that is definitely NOT part of a marker.
        
        val stableContent = getStableContent()
        if (stableContent.isNotEmpty()) {
            route(stableContent)
            buffer = buffer.substring(stableContent.length)
        }
    }

    private fun getStableContent(): String {
        // Find the earliest possible start of a marker
        val markerStart = buffer.indexOfAny(listOf("Δ", "∇"))
        return if (markerStart == -1) {
            buffer
        } else {
            buffer.substring(0, markerStart)
        }
    }

    private fun route(content: String) {
        if (content.isEmpty()) return
        when (currentChannel) {
            Channel.RESPONSE -> onResponseToken(content)
            Channel.THOUGHT -> onThoughtToken(content)
            Channel.STATE -> onStateToken(content)
            Channel.NONE -> {
                // If it's not in a block, it might be the start of the response before a marker
                // or the model is being non-compliant. Default to response.
                onResponseToken(content)
            }
        }
    }
    
    fun finalize() {
        if (buffer.isNotEmpty()) {
            route(buffer)
            buffer = ""
        }
    }
}
