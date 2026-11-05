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
        messagesToCompact: List<AgentMessage>,
        currentMemory: String,
        llmEngine: LlmBackend? = null,
        assistantCallSign: String = "Assistant"
    ): String = withContext(Dispatchers.Default) {
        if (messagesToCompact.isEmpty()) return@withContext currentMemory

        Timber.i("Starting instant deterministic memory compaction of ${messagesToCompact.size} messages...")

        val newEntries = mutableListOf<String>()
        messagesToCompact.forEach { msg ->
            val clean = msg.content
                .replace(Regex("""--- (?:Perceptual Grounding|Autonomous Event)[\s\S]*?---"""), "")
                .replace(Regex("""^[\s\S]*?\[/(?:Context|Live Sensory Grounding|Sensory Grounding|Telemetry|Perception)\]\s*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\[(?:Context|Live Sensory Grounding|Sensory Grounding|Telemetry|Perception):?[\s\S]*?\[/(?:Context|Live Sensory Grounding|Sensory Grounding|Telemetry|Perception)\]\s*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\[/?(?:Context|Live Sensory Grounding|Sensory Grounding|Telemetry|Perception)(?::[^\]]*)?\]\s*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\[SYSTEM TELEMETRY.*?\][\s\S]*?\[/SYSTEM TELEMETRY\]"""), "")
                .replace(Regex("""\[ON-DEVICE SENSORS.*?\][\s\S]*?\[END SENSORS\]"""), "")
                .replace(Regex("""\[SYSTEM EVENT.*?\][\s\S]*?\[END SENSORS\]"""), "")
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
                    .replace(Regex("""\b([Ii]t's)(?:'s)+\b"""), "$1")
                    .replace(Regex("""\b([Ii]'m)(?:'s|'m)+\b"""), "$1")
                    .replace(Regex("""\b[Ii]'s\b"""), "I'm")
                    .replace(Regex("""\b(\w+)(?:\s+\1\b)+""", RegexOption.IGNORE_CASE), "$1")
                    .replace(Regex("""\.\.\.+$"""), ".")
                    .trim()
                val singleLine = contentWithoutPrefix.replace(Regex("""\s+"""), " ")

                // Never slice mid-word with "..." (which causes local models to imitate literal "..." truncations).
                // For assistant responses, keep only the first 1-2 complete sentences up to 140 chars.
                val snippet = extractCleanSentenceSnippet(singleLine, if (msg.role == "user") 160 else 130)
                if (snippet.isNotBlank()) {
                    newEntries.add("• $roleName: $snippet")
                }
            }
        }

        if (newEntries.isEmpty()) return@withContext currentMemory

        val existingLines = currentMemory.lines()
            .map { it.trim().replace(Regex("""\.\.\.+$"""), ".") }
            .filter { it.isNotBlank() && (it.startsWith("•") || it.startsWith("-")) }

        // Deduplicate both exact and high-overlap (state attractor) lines
        val deduplicated = mutableListOf<String>()
        for (line in (existingLines + newEntries)) {
            val body = line.substringAfter(":", line).trim().lowercase()
            val isDuplicateOrLoop = deduplicated.any { existing ->
                val existingBody = existing.substringAfter(":", existing).trim().lowercase()
                existingBody == body || wordOverlapRatio(existingBody, body) > 0.55
            }
            if (!isDuplicateOrLoop) {
                deduplicated.add(line)
            }
        }

        val combined = deduplicated.takeLast(10)
        val result = combined.joinToString("\n").take(900).trim()

        Timber.i("Memory compaction complete (${result.length} chars, ${combined.size} entries)")
        result
    }

    fun extractCleanSentenceSnippet(text: String, maxChars: Int): String {
        val clean = text.trim()
        if (clean.length <= maxChars) return clean

        val window = clean.take(maxChars)
        val lastPunct = window.lastIndexOfAny(charArrayOf('.', '!', '?'))
        if (lastPunct >= 25) {
            return window.substring(0, lastPunct + 1).trim()
        }
        val lastSpace = window.lastIndexOf(' ')
        return if (lastSpace > 20) {
            window.substring(0, lastSpace).trimEnd(',', ';', ':', '-') + "."
        } else {
            window + "."
        }
    }

    fun wordOverlapRatio(a: String, b: String): Double {
        val wordsA = a.split(Regex("""\W+""")).filter { it.length >= 3 }.toSet()
        val wordsB = b.split(Regex("""\W+""")).filter { it.length >= 3 }.toSet()
        if (wordsA.size < 4 || wordsB.size < 4) return 0.0
        val intersection = wordsA.intersect(wordsB).size
        val minSize = minOf(wordsA.size, wordsB.size)
        return intersection.toDouble() / minSize.toDouble()
    }
}
