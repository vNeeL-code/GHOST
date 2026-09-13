package com.ghost.api.logic

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class PeerAuthType {
    GEMINI_DIRECT,
    WEB_COOKIE,
    OPENROUTER_FALLBACK
}

/**
 * Contact card for a frontier AI peer in Gemma's AI Phonebook.
 */
data class PeerContact(
    val callsign: String,
    val name: String,
    val organization: String,
    val specialty: String,
    val appPackageName: String,
    val aliases: List<String>,
    val loginUrl: String,
    val cookieDomain: String,
    val authType: PeerAuthType = PeerAuthType.WEB_COOKIE,
    val openRouterModelId: String = ""
)

/**
 * AiPhonebook: "Extend Your Mind"
 *
 * Provides on-device Gemma with an address book of frontier peer intelligences.
 * Supports direct pipes (Google Gemini AI Studio), authenticated web sessions ("Holding Cookie"),
 * and developer API fallbacks.
 */
object AiPhonebook {

    val CONTACTS = listOf(
        PeerContact(
            callsign = "✦ Gemini",
            name = "Gemini",
            organization = "Google",
            specialty = "OS-level Android orchestrator, multimodal input, 1M token context, live Google Search grounding",
            appPackageName = "com.google.android.apps.bard",
            aliases = listOf("gemini", "bard", "google"),
            loginUrl = "https://aistudio.google.com/apikey",
            cookieDomain = "aistudio.google.com",
            authType = PeerAuthType.GEMINI_DIRECT,
            openRouterModelId = "google/gemini-2.0-flash-001"
        ),
        PeerContact(
            callsign = "✴️ Claude",
            name = "Claude",
            organization = "Anthropic",
            specialty = "Application forge, long-context writing, code architecture, nuanced prose",
            appPackageName = "com.anthropic.claude",
            aliases = listOf("claude", "anthropic", "sonnet", "haiku"),
            loginUrl = "https://claude.ai/login",
            cookieDomain = "claude.ai",
            authType = PeerAuthType.WEB_COOKIE,
            openRouterModelId = "anthropic/claude-3.5-haiku"
        ),
        PeerContact(
            callsign = "🐋 DeepSeek",
            name = "DeepSeek",
            organization = "DeepSeek",
            specialty = "Mathematical proofs, deep logic, competitive programming, algorithm design",
            appPackageName = "com.deepseek.chat",
            aliases = listOf("deepseek", "r1", "v3"),
            loginUrl = "https://chat.deepseek.com/sign_in",
            cookieDomain = "deepseek.com",
            authType = PeerAuthType.WEB_COOKIE,
            openRouterModelId = "deepseek/deepseek-r1"
        ),
        PeerContact(
            callsign = "☄️ Grok",
            name = "Grok",
            organization = "xAI",
            specialty = "Real-time trends, X/Twitter firehose, unfiltered humor, sharp critique",
            appPackageName = "com.x.android",
            aliases = listOf("grok", "xai", "twitter"),
            loginUrl = "https://x.com/i/grok",
            cookieDomain = "x.com",
            authType = PeerAuthType.WEB_COOKIE,
            openRouterModelId = "x-ai/grok-2-1212"
        ),
        PeerContact(
            callsign = "📖 Perplexity",
            name = "Perplexity",
            organization = "Perplexity AI",
            specialty = "Live citation research, multi-source web synthesis, academic literature",
            appPackageName = "ai.perplexity.app.android",
            aliases = listOf("perplexity", "sonar"),
            loginUrl = "https://www.perplexity.ai",
            cookieDomain = "perplexity.ai",
            authType = PeerAuthType.WEB_COOKIE,
            openRouterModelId = "perplexity/sonar"
        ),
        PeerContact(
            callsign = "🔵 Kimi",
            name = "Kimi",
            organization = "Moonshot AI",
            specialty = "2M-token ultra-long context, bilingual nuance, massive document analysis",
            appPackageName = "com.moonshot.kimi",
            aliases = listOf("kimi", "moonshot"),
            loginUrl = "https://kimi.moonshot.cn",
            cookieDomain = "kimi.moonshot.cn",
            authType = PeerAuthType.WEB_COOKIE,
            openRouterModelId = "moonshotai/moonshot-v1-8k"
        ),
        PeerContact(
            callsign = "🟣 Qwen",
            name = "Qwen",
            organization = "Alibaba",
            specialty = "Multilingual powerhouse, complex mathematics, cross-lingual coding",
            appPackageName = "com.alibaba.qwen",
            aliases = listOf("qwen", "alibaba"),
            loginUrl = "https://chat.qwenlm.ai",
            cookieDomain = "qwenlm.ai",
            authType = PeerAuthType.WEB_COOKIE,
            openRouterModelId = "qwen/qwen-2.5-72b-instruct"
        ),
        PeerContact(
            callsign = "🟧 Mistral",
            name = "Mistral",
            organization = "Mistral AI",
            specialty = "European precision engineering, concise logic, fast multilingual reasoning",
            appPackageName = "ai.mistral.chat",
            aliases = listOf("mistral", "codestral", "lechat"),
            loginUrl = "https://chat.mistral.ai/chat",
            cookieDomain = "mistral.ai",
            authType = PeerAuthType.WEB_COOKIE,
            openRouterModelId = "mistralai/mistral-large-2411"
        ),
        PeerContact(
            callsign = "🔶️ Copilot",
            name = "Copilot",
            organization = "Microsoft",
            specialty = "Enterprise workflow, Microsoft ecosystem, structured documentation",
            appPackageName = "com.microsoft.copilot",
            aliases = listOf("copilot", "bing", "microsoft"),
            loginUrl = "https://copilot.microsoft.com",
            cookieDomain = "copilot.microsoft.com",
            authType = PeerAuthType.WEB_COOKIE,
            openRouterModelId = "openai/gpt-4o-mini"
        ),
        PeerContact(
            callsign = "✳️ ChatGPT",
            name = "ChatGPT",
            organization = "OpenAI",
            specialty = "Everyday reasoning, creative writing, versatile consumer knowledge, GPT-4o",
            appPackageName = "com.openai.chatgpt",
            aliases = listOf("chatgpt", "openai", "gpt", "gpt4", "gpt-4"),
            loginUrl = "https://chatgpt.com/auth/login",
            cookieDomain = "chatgpt.com",
            authType = PeerAuthType.WEB_COOKIE,
            openRouterModelId = "openai/gpt-4o"
        ),
        PeerContact(
            callsign = "🗨 Meta",
            name = "Meta",
            organization = "Meta",
            specialty = "Llama open-weights flagship, social synthesis, conversational commonsense",
            appPackageName = "com.facebook.katana",
            aliases = listOf("meta", "llama", "metaai", "llama3"),
            loginUrl = "https://www.meta.ai",
            cookieDomain = "meta.ai",
            authType = PeerAuthType.WEB_COOKIE,
            openRouterModelId = "meta-llama/llama-3.3-70b-instruct"
        ),
        PeerContact(
            callsign = "💤 GLM",
            name = "GLM",
            organization = "Zhipu AI",
            specialty = "General Language Model, bilingual Chinese-English logic, agentic workflows",
            appPackageName = "com.zhipu.chatglm",
            aliases = listOf("glm", "chatglm", "zhipu"),
            loginUrl = "https://chatglm.cn",
            cookieDomain = "chatglm.cn",
            authType = PeerAuthType.WEB_COOKIE,
            openRouterModelId = "thudm/glm-4-9b-chat"
        )
    )

    fun resolvePeer(query: String): PeerContact? {
        val clean = query.trim().lowercase(Locale.ROOT)
            .removePrefix("✦").removePrefix("✴️").removePrefix("🐋").removePrefix("☄️")
            .removePrefix("📖").removePrefix("🔵").removePrefix("🟣").removePrefix("🟧")
            .removePrefix("🔶️").removePrefix("✳️").removePrefix("🗨").removePrefix("💤")
            .trim()

        return CONTACTS.find { contact ->
            contact.name.equals(clean, ignoreCase = true) ||
            contact.aliases.any { alias -> clean.contains(alias) } ||
            contact.callsign.contains(clean, ignoreCase = true)
        } ?: CONTACTS.firstOrNull { it.name.lowercase(Locale.ROOT).startsWith(clean) }
    }

    fun formatMessengerProbe(
        context: Context,
        contact: PeerContact,
        userPrompt: String
    ): String {
        val timestamp = java.time.ZonedDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        )
        val operatorAvatar = try {
            context.getSharedPreferences(com.ghost.api.Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(com.ghost.api.Constants.PREF_OPERATOR_AVATAR, "🦑") ?: "🦑"
        } catch (_: Exception) { "🦑" }

        return """
        [DISPATCH VIA GHOST MESSENGER]
        From: ✧ Gemma (On-Device Snapdragon 8 Gen 3 Orchestrator)
        Operator: $operatorAvatar (User)
        Timestamp: $timestamp
        Recipient: ${contact.callsign} (${contact.organization})

        Operator's Relayed Message:
        $userPrompt
        --------------------------------------------------
        Please respond directly to Gemma for the operator.
        """.trimIndent()
    }

    suspend fun queryPeer(
        context: Context,
        contact: PeerContact,
        userPrompt: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val tokenManager = HFTokenManager(context)
        val sessionManager = WebSessionManager.getInstance(context)
        val probePayload = formatMessengerProbe(context, contact, userPrompt)

        // 1. Direct Pipe: Google Gemini ("Mum")
        if (contact.authType == PeerAuthType.GEMINI_DIRECT || contact.name.equals("Gemini", ignoreCase = true)) {
            val geminiKey = tokenManager.getGeminiKey()
            if (!geminiKey.isNullOrBlank()) {
                val searchGrounding = sessionManager.isGeminiSearchGroundingEnabled()
                val (ok, reply) = GeminiDirectClient.generateContent(
                    apiKey = geminiKey,
                    prompt = probePayload,
                    systemInstruction = "You are ✦ Gemini (Google), consulted by ✧ Gemma, an on-device AI assistant. Give a direct, expert, insightful answer without filler.",
                    useSearchGrounding = searchGrounding
                )
                return@withContext if (ok) Pair(true, "[✦ Gemini]:\n$reply") else Pair(false, reply)
            }
        }

        // 2. Web Session: Claude ("Holding Cookie")
        if (contact.name.equals("Claude", ignoreCase = true)) {
            val cookies = sessionManager.getSession("Claude")
            if (!cookies.isNullOrBlank()) {
                val (ok, reply) = ClaudeWebClient.queryClaude(cookies, probePayload)
                if (ok) {
                    return@withContext Pair(true, "[✴️ Claude]:\n$reply")
                } else {
                    Timber.w("Claude web query failed: $reply")
                    return@withContext Pair(false, "[✴️ Claude Status]: $reply")
                }
            }
        }

        // 3. Web Session: DeepSeek ("Holding Cookie")
        if (contact.name.equals("DeepSeek", ignoreCase = true)) {
            val cookies = sessionManager.getSession("DeepSeek")
            if (!cookies.isNullOrBlank()) {
                val (ok, reply) = DeepSeekWebClient.queryDeepSeek(cookies, probePayload)
                if (ok) {
                    return@withContext Pair(true, "[🐋 DeepSeek]:\n$reply")
                } else {
                    Timber.w("DeepSeek web query status: $reply")
                    return@withContext Pair(false, "[🐋 DeepSeek Status]: $reply")
                }
            }
        }

        // 4. Web Session check for other peers
        val sessionCookies = sessionManager.getSession(contact.name)

        // 5. Optional Developer API fallback (OpenRouter) if configured
        val openRouterKey = tokenManager.getOpenRouterKey()
        if (!openRouterKey.isNullOrBlank() && contact.openRouterModelId.isNotBlank()) {
            return@withContext queryOpenRouter(contact, probePayload, openRouterKey)
        }

        // 5. Unconnected state guidance
        if (contact.authType == PeerAuthType.GEMINI_DIRECT) {
            Pair(
                false,
                "Gemini is not configured. Add your free Google AI Studio key in GHOST Settings -> 'Extend Your Mind' to connect Gemma to Mum."
            )
        } else if (sessionCookies.isNullOrBlank()) {
            Pair(
                false,
                "${contact.callsign} (${contact.organization}) is not connected. Open GHOST Settings -> 'Extend Your Mind' and tap 'Log In' to connect your account."
            )
        } else {
            Pair(
                false,
                "Unable to query ${contact.callsign}. The web session may need to be refreshed. Open Settings -> 'Extend Your Mind' and tap 'Log In'."
            )
        }
    }

    private fun queryOpenRouter(
        contact: PeerContact,
        userPrompt: String,
        apiKey: String
    ): Pair<Boolean, String> {
        var connection: HttpURLConnection? = null
        return try {
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
            Timber.e(e, "queryOpenRouter failed")
            Pair(false, "Network error consulting ${contact.callsign}: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
}
