package com.ghost.api.agent

import com.ghost.api.LlmBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object SessionMemoryCompactor {

    /**
     * Compacts older dialogue turns into dense, long-term session memory.
     * Uses zero-latency deterministic extraction so it never contends for
     * or locks the on-device LiteRT-LM inference engine.
     */
    suspend fun compactOldMessages(
        messagesToCompact: List<KoogAgent.Message>,
        currentMemory: String,
        llmEngine: LlmBackend? = null,
        assistantCallSign: String = "Assistant"
    ): String = withContext(Dispatchers.Default) {
        if (messagesToCompact.isEmpty()) return@withContext currentMemory

        Timber.i("Starting instant deterministic memory compaction of ${messagesToCompact.size} messages...")

        val newEntries = mutableListOf<String>()
        messagesToCompact.forEach { msg ->
            val clean = msg.content
                .replace(Regex("""\[SYSTEM TELEMETRY.*?\][\s\S]*?\[/SYSTEM TELEMETRY\]"""), "")
                .replace(Regex("""Δ 👾 ∇ GHOST TELEMETRY.*?\[/Δ 👾 ∇ GHOST TELEMETRY\]"""), "")
                .replace(Regex("""<think>[\s\S]*?</think>"""), "")
                .replace(Regex("""<\|channel>thought[\s\S]*?<channel\|>"""), "")
                .replace(Regex("""<\|channel>thought[\s\S]*"""), "")
                .replace(Regex("""<\|tool_call>[\s\S]*?<tool_call\|>"""), "")
                .replace(Regex("""<\|tool_response>[\s\S]*?<tool_response\|>"""), "")
                .replace(Regex("""<\|tool>[\s\S]*?<tool\|>"""), "")
                .replace("<|\"|>", "")
                .replace(Regex("""\[Date:\s*[\d-]+\]\s*"""), "")
                .trim()

            if (clean.isNotBlank() && clean.length > 2) {
                val roleName = when {
                    msg.role == "system" || clean.startsWith("Δ 👾 ∇") -> "GHOST System"
                    msg.role == "user" -> "Operator"
                    else -> assistantCallSign
                }
                val contentWithoutPrefix = clean
                    .removePrefix("Δ 👾 ∇ GHOST:")
                    .replace(Regex("""^Δ\s*.*?\s*∇(\s*\[.*?\])?:\s*"""), "")
                    .replace(Regex("""^✧\s*.*?:"""), "")
                    .trim()
                val singleLine = contentWithoutPrefix.replace(Regex("""\s+"""), " ")
                val snippet = if (singleLine.length > 180) singleLine.take(177) + "..." else singleLine
                newEntries.add("• $roleName: $snippet")
            }
        }

        if (newEntries.isEmpty()) return@withContext currentMemory

        val existingLines = currentMemory.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && (it.startsWith("•") || it.startsWith("-")) }

        val combined = (existingLines + newEntries).distinct().takeLast(14)
        val result = combined.joinToString("\n").take(1200).trim()

        Timber.i("Memory compaction complete (${result.length} chars, ${combined.size} entries)")
        result
    }
}
