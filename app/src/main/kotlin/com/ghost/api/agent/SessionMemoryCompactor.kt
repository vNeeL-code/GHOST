package com.ghost.api.agent

import com.ghost.api.LlmBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object SessionMemoryCompactor {

    suspend fun compactOldMessages(
        messagesToCompact: List<KoogAgent.Message>,
        currentMemory: String,
        llmEngine: LlmBackend
    ): String = withContext(Dispatchers.IO) {
        if (messagesToCompact.isEmpty()) return@withContext currentMemory

        Timber.i("Starting synchronous memory compaction of ${messagesToCompact.size} messages...")
        
        val transcript = buildString {
            messagesToCompact.forEach { msg ->
                val roleName = if (msg.role == "user") "User" else "Assistant"
                append("$roleName: ${msg.content.take(1000)}\n")
            }
        }.take(3500)

        val systemPrompt = """
            You are a backend memory summarizer for an AI system.
            Your task is to compress the provided dialogue into a dense, long-term memory representation.
            Preserve any established facts, user preferences, tool states, or critical narrative context.
            Do not include pleasantries. Keep the summary dense, focused, and concise (strictly under 1,200 characters / ~250 words).
            
            Current Existing Memory:
            ${if (currentMemory.isBlank()) "None." else currentMemory.take(1200)}
            
            Dialogue to append/merge:
            $transcript
            
            Output ONLY the updated, comprehensive Session Memory block.
        """.trimIndent()

        try {
            // Generate summary without streaming
            val newMemory = llmEngine.generateOneShot(systemPrompt)
            Timber.i("Memory compaction complete. Compressed length: ${newMemory.length}")
            newMemory.take(1500).trim()
        } catch (e: Exception) {
            Timber.e(e, "Failed to compact session memory")
            currentMemory.take(1500) // Fallback to old memory on failure
        }
    }
}
