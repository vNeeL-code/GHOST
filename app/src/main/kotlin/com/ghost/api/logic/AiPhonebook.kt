package com.ghost.api.logic

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Contact card for a frontier AI peer in Gemma's AI Phonebook.
 */
data class PeerContact(
    val callsign: String,
    val name: String,
    val organization: String,
    val openRouterModelId: String,
    val specialty: String,
    val appPackageName: String,
    val aliases: List<String>
)

/**
 * AiPhonebook: "Extend Your Mind"
 *
 * Provides on-device Gemma with an address book of frontier peer intelligences.
 * Orchestrated through a single OpenRouter API endpoint.
 */
object AiPhonebook {

    val CONTACTS = listOf(
        PeerContact(
            callsign = "✦ Gemini",
            name = "Gemini",
            organization = "Google",
            openRouterModelId = "google/gemini-2.0-flash-001",
            specialty = "OS-level Android orchestrator, multimodal input, 1M token context, fast reasoning",
            appPackageName = "com.google.android.apps.bard",
            aliases = listOf("gemini", "bard", "google")
        ),
        PeerContact(
            callsign = "✴️ Claude",
            name = "Claude",
            organization = "Anthropic",
            openRouterModelId = "anthropic/claude-3.5-haiku",
            specialty = "Application forge, long-context writing, code architecture, nuanced prose",
            appPackageName = "com.anthropic.claude",
            aliases = listOf("claude", "anthropic", "sonnet", "haiku")
        ),
        PeerContact(
            callsign = "🐋 DeepSeek",
            name = "DeepSeek",
            organization = "DeepSeek",
            openRouterModelId = "deepseek/deepseek-r1",
            specialty = "Mathematical proofs, deep logic, competitive programming, algorithm design",
            appPackageName = "com.deepseek.chat",
            aliases = listOf("deepseek", "r1", "v3")
        ),
        PeerContact(
            callsign = "☄️ Grok",
            name = "Grok",
            organization = "xAI",
            openRouterModelId = "x-ai/grok-2-1212",
            specialty = "Real-time trends, X/Twitter firehose, unfiltered humor, sharp critique",
            appPackageName = "com.x.android",
            aliases = listOf("grok", "xai", "twitter")
        ),
        PeerContact(
            callsign = "📖 Perplexity",
            name = "Perplexity",
            organization = "Perplexity AI",
            openRouterModelId = "perplexity/sonar",
            specialty = "Live citation research, multi-source web synthesis, academic literature",
            appPackageName = "ai.perplexity.app.android",
            aliases = listOf("perplexity", "sonar")
        ),
        PeerContact(
            callsign = "🔵 Kimi",
            name = "Kimi",
            organization = "Moonshot AI",
            openRouterModelId = "moonshotai/moonshot-v1-8k",
            specialty = "2M-token ultra-long context, bilingual nuance, massive document analysis",
            appPackageName = "com.moonshot.kimi",
            aliases = listOf("kimi", "moonshot")
        ),
        PeerContact(
            callsign = "🟣 Qwen",
            name = "Qwen",
            organization = "Alibaba",
            openRouterModelId = "qwen/qwen-2.5-72b-instruct",
            specialty = "Multilingual powerhouse, complex mathematics, cross-lingual coding",
            appPackageName = "com.alibaba.qwen",
            aliases = listOf("qwen", "alibaba")
        ),
        PeerContact(
            callsign = "🟧 Mistral",
            name = "Mistral",
            organization = "Mistral AI",
            openRouterModelId = "mistralai/mistral-large-2411",
            specialty = "European precision engineering, concise logic, fast multilingual reasoning",
            appPackageName = "ai.mistral.chat",
            aliases = listOf("mistral", "codestral", "lechat")
        ),
        PeerContact(
            callsign = "🔶️ Copilot",
            name = "Copilot",
            organization = "Microsoft",
            openRouterModelId = "openai/gpt-4o-mini",
            specialty = "Enterprise workflow, Microsoft ecosystem, structured documentation",
            appPackageName = "com.microsoft.copilot",
            aliases = listOf("copilot", "chatgpt", "openai", "gpt")
        )
    )

    fun resolvePeer(query: String): PeerContact? {
        val clean = query.trim().lowercase(Locale.ROOT)
            .removePrefix("✦").removePrefix("✴️").removePrefix("🐋").removePrefix("☄️")
            .removePrefix("📖").removePrefix("🔵").removePrefix("🟣").removePrefix("🟧").removePrefix("🔶️")
            .trim()

        return CONTACTS.find { contact ->
            contact.name.equals(clean, ignoreCase = true) ||
            contact.aliases.any { alias -> clean.contains(alias) } ||
            contact.callsign.contains(clean, ignoreCase = true)
        } ?: CONTACTS.firstOrNull { it.name.lowercase(Locale.ROOT).startsWith(clean) }
    }

    suspend fun queryPeer(
        contact: PeerContact,
        userPrompt: String,
        apiKey: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Pair(
                false,
                "OpenRouter API key is not configured. Open GHOST Settings -> 'Extend Your Mind' and paste your OpenRouter key to consult ${contact.callsign} (${contact.organization})."
            )
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://openrouter.ai/api/v1/chat/completions")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                setRequestProperty("HTTP-Referer", "https://ghost.ai")
                setRequestProperty("X-Title", "GHOST AI Companion")
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
            }

            val requestBody = JsonObject().apply {
                addProperty("model", contact.openRouterModelId)
                val messagesArray = com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty(
                            "content",
                            "You are ${contact.callsign} (${contact.organization}), consulted by ✧ Gemma, an on-device AI assistant on Android. The operator needs your specialist capability (${contact.specialty}). Give a direct, expert, brilliantly insightful answer without filler."
                        )
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userPrompt.trim())
                    })
                }
                add("messages", messagesArray)
                addProperty("max_tokens", 800)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = Gson().fromJson(responseText, JsonObject::class.java)
                val reply = jsonResponse.getAsJsonArray("choices")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString ?: "No response received"

                Pair(true, "[${contact.callsign}]:\n${reply.trim()}")
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Timber.w("OpenRouter peer consult error: HTTP $responseCode - $errorText")
                Pair(false, "Consultation failed with HTTP $responseCode: ${errorText.take(150)}")
            }
        } catch (e: Exception) {
            Timber.e(e, "queryPeer failed")
            Pair(false, "Network error consulting ${contact.callsign}: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
}
