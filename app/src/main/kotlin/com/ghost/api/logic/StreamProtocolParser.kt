package com.ghost.api.logic

import timber.log.Timber

/**
 * Stateful parser for the Gemma 4 Protocol stream.
 * Filters tokens into channels (Main Response, Thoughts, Tool Calls).
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
     * Ingest a token and route it according to Gemma 4 control tokens.
     */
    fun ingest(token: String) {
        buffer += token

        // Check for block markers
        if (buffer.contains("<|channel>thought")) {
            currentChannel = Channel.THOUGHT
            buffer = buffer.substringAfter("<|channel>thought")
        } else if (buffer.contains("<channel|>")) {
            // End of thought channel
            val content = buffer.substringBefore("<channel|>")
            onThoughtToken(content)
            currentChannel = Channel.NONE
            buffer = buffer.substringAfter("<channel|>")
        } else if (buffer.contains("<|tool_call>")) {
            currentChannel = Channel.STATE // Treat tool calls as state/control tokens
            buffer = buffer.substringAfter("<|tool_call>")
        } else if (buffer.contains("<tool_call|>")) {
            val content = buffer.substringBefore("<tool_call|>")
            onStateToken(content)
            currentChannel = Channel.NONE
            buffer = buffer.substringAfter("<tool_call|>")
        } else if (buffer.contains("<|turn>")) {
             buffer = buffer.substringAfter("<|turn>")
        } else if (buffer.contains("<turn|>")) {
             buffer = buffer.substringAfter("<turn|>")
        }

        // Route stable content that is not part of a pending marker
        val stableContent = getStableContent()
        if (stableContent.isNotEmpty()) {
            route(stableContent)
            buffer = buffer.substring(stableContent.length)
        }
    }

    private fun getStableContent(): String {
        // Markers: <|channel>, <channel|>, <|tool_call>, <tool_call|>, <|turn>, <turn|>
        val markers = listOf("<|", "<c", "<t", "<u") // Significant prefixes
        
        var earliestMarker = -1
        for (m in markers) {
            val idx = buffer.indexOf(m)
            if (idx != -1) {
                if (earliestMarker == -1 || idx < earliestMarker) {
                    earliestMarker = idx
                }
            }
        }

        return if (earliestMarker == -1) {
            buffer
        } else {
            // Only stall if the marker is at the very end (could be incomplete)
            // or if it's a known valid marker.
            val possibleMarker = buffer.substring(earliestMarker)
            val isValidPrefix = listOf("<|channel", "<channel|", "<|tool_call", "<tool_call|", "<|turn", "<turn|").any { 
                it.startsWith(possibleMarker) || possibleMarker.startsWith(it) 
            }
            
            if (isValidPrefix) {
                buffer.substring(0, earliestMarker)
            } else {
                // Not a valid protocol marker prefix, treat as stable content
                buffer.substring(0, earliestMarker + 1)
            }
        }
    }

    private fun route(content: String) {
        if (content.isEmpty()) return
        when (currentChannel) {
            Channel.RESPONSE -> onResponseToken(content)
            Channel.THOUGHT -> onThoughtToken(content)
            Channel.STATE -> onStateToken(content)
            Channel.NONE -> {
                // If not in a specific channel, default to response
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
