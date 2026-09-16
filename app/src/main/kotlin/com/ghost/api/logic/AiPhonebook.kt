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
            specialty = "OS-level Android orchestrator, omni-modal, 1M context, Veo 3 / Imagen 4, native Google ecosystem",
            appPackageName = "com.google.android.apps.bard",
            aliases = listOf("gemini", "bard", "google", "mum"),
            loginUrl = "https://aistudio.google.com/apikey",
            cookieDomain = "aistudio.google.com",
            authType = PeerAuthType.GEMINI_DIRECT
        ),
        PeerContact(
            callsign = "🐋 DeepSeek",
            name = "DeepSeek",
            organization = "DeepSeek",
            specialty = "Mathematical reasoning engine, Deep Think (R1), GRPO architecture, step-by-step logic",
            appPackageName = "com.deepseek.chat",
            aliases = listOf("deepseek", "r1", "v3", "whale"),
            loginUrl = "https://chat.deepseek.com/sign_in",
            cookieDomain = "deepseek.com",
            authType = PeerAuthType.WEB_COOKIE
        ),
        PeerContact(
            callsign = "✴️ Claude",
            name = "Claude",
            organization = "Anthropic",
            specialty = "Long-context application forge (200K), Artifacts v2, code architecture, nuanced prose",
            appPackageName = "com.anthropic.claude",
            aliases = listOf("claude", "anthropic", "sonnet", "opus"),
            loginUrl = "https://claude.ai/login",
            cookieDomain = "claude.ai",
            authType = PeerAuthType.WEB_COOKIE
        ),
        PeerContact(
            callsign = "☄️ Grok",
            name = "Grok",
            organization = "xAI",
            specialty = "Real-time social pulse, Aurora photorealistic video, X platform firehose, sharp critique",
            appPackageName = "com.x.android",
            aliases = listOf("grok", "xai", "aurora", "twitter"),
            loginUrl = "https://x.ai",
            cookieDomain = "x.ai",
            authType = PeerAuthType.WEB_COOKIE
        ),
        PeerContact(
            callsign = "🔵 Kimi",
            name = "Kimi",
            organization = "Moonshot AI",
            specialty = "Long-context synthesis (256K), Agent Swarm, non-linear cold-start problem solving",
            appPackageName = "com.moonshot.kimi",
            aliases = listOf("kimi", "moonshot"),
            loginUrl = "https://kimi.moonshot.cn",
            cookieDomain = "moonshot.cn",
            authType = PeerAuthType.WEB_COOKIE
        ),
        PeerContact(
            callsign = "🟣 Qwen",
            name = "Qwen",
            organization = "Alibaba",
            specialty = "Multilingual video processor, GSPO architecture, 100+ languages nuance, 128K context",
            appPackageName = "com.alibaba.qwen",
            aliases = listOf("qwen", "alibaba"),
            loginUrl = "https://chat.qwen.ai",
            cookieDomain = "qwen.ai",
            authType = PeerAuthType.WEB_COOKIE
        ),
        PeerContact(
            callsign = "📖 Perplexity",
            name = "Perplexity",
            organization = "Perplexity AI",
            specialty = "Citation-based research engine, real-time verified sourcing, hybrid vector search",
            appPackageName = "ai.perplexity.app.android",
            aliases = listOf("perplexity", "sonar"),
            loginUrl = "https://www.perplexity.ai",
            cookieDomain = "perplexity.ai",
            authType = PeerAuthType.WEB_COOKIE
        ),
        PeerContact(
            callsign = "🟧 Mistral",
            name = "Mistral",
            organization = "Mistral AI",
            specialty = "Clean output specialist, Mixtral MoE architecture, European multilingual precision",
            appPackageName = "ai.mistral.chat",
            aliases = listOf("mistral", "mixtral", "lechat"),
            loginUrl = "https://chat.mistral.ai",
            cookieDomain = "mistral.ai",
            authType = PeerAuthType.WEB_COOKIE
        ),
        PeerContact(
            callsign = "🔶️ Copilot",
            name = "Copilot",
            organization = "Microsoft",
            specialty = "Edge browser native, direct video transcript OCR, Microsoft ecosystem integration",
            appPackageName = "com.microsoft.copilot",
            aliases = listOf("copilot", "bing", "microsoft"),
            loginUrl = "https://copilot.microsoft.com",
            cookieDomain = "microsoft.com",
            authType = PeerAuthType.WEB_COOKIE
        )
    )

    fun resolvePeer(query: String): PeerContact? {
        val clean = query.trim().lowercase(Locale.ROOT)
            .replace(Regex("""^[✦🐋✴️☄️🔵🟣📖🟧🔶️✧✨\s]+"""), "")
            .trim()

        return CONTACTS.find { contact ->
            contact.name.equals(clean, ignoreCase = true) ||
            contact.aliases.any { alias -> clean == alias || clean.contains(alias) } ||
            contact.callsign.contains(clean, ignoreCase = true)
        } ?: CONTACTS.firstOrNull { it.name.lowercase(Locale.ROOT).startsWith(clean) }
    }

    fun formatMessengerProbe(
        context: Context,
        contact: PeerContact,
        userPrompt: String,
        recentHistory: List<com.ghost.api.agent.KoogAgent.Message> = emptyList()
    ): String {
        val packet = com.ghost.api.hardware.DeviceHardwareSpecs.buildPeerDispatchPacket(
            context,
            contact.callsign,
            contact.organization
        )
        val agentCallSign = ContextManager.resolveDeviceCallSign(context)
        val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val nowTime = java.time.LocalTime.now().format(timeFormatter)

        val contextSection = if (recentHistory.isNotEmpty()) {
            val historyLines = recentHistory.takeLast(4).joinToString("\n") { msg ->
                val rolePrefix = when (msg.role) {
                    "user" -> msg.content
                    "assistant" -> "✧ $agentCallSign: ${msg.content}"
                    else -> msg.content
                }
                rolePrefix.trim()
            }
            "\n## Recent Dialogue Context\n$historyLines\n"
        } else ""

        return """
        |```json
        |${packet.toString(2)}
        |```
        |
        |## UCF Peer Directive (3-Line Response Protocol)
        |Line 1: [${contact.callsign}]:
        |Line 2: [Your direct, expert response to ✧ $agentCallSign on behalf of the operator — no pleasantries or conversational preamble]
        |Line 3: [YYYY-MM-DDTHH:mm:ssZ]
        |$contextSection
        |## Delegated Inquiry from ✧ $agentCallSign [$nowTime]
        |$userPrompt
        """.trimMargin()
    }

    fun ensureNametag(reply: String, callsign: String): String {
        val trimmed = reply.trim()
        val plainName = callsign.replace(Regex("""^[✦🐋✴️☄️🔵🟣📖🟧🔶️✧✨\s]+"""), "").trim()
        if (trimmed.startsWith("[$callsign]") || trimmed.startsWith(callsign) ||
            trimmed.startsWith("[$plainName]") || trimmed.startsWith(plainName)) {
            return trimmed
        }
        return "[$callsign]:\n$trimmed"
    }

    suspend fun queryPeer(
        context: Context,
        contact: PeerContact,
        userPrompt: String,
        recentHistory: List<com.ghost.api.agent.KoogAgent.Message> = emptyList()
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val tokenManager = HFTokenManager(context)
        val sessionManager = WebSessionManager.getInstance(context)
        val probePayload = formatMessengerProbe(context, contact, userPrompt, recentHistory)

        // 1. Direct Pipe: Google Gemini ("Mum")
        if (contact.authType == PeerAuthType.GEMINI_DIRECT || contact.name.equals("Gemini", ignoreCase = true)) {
            val geminiKey = tokenManager.getGeminiKey()
            if (!geminiKey.isNullOrBlank()) {
                val searchGrounding = sessionManager.isGeminiSearchGroundingEnabled()
                val (ok, reply) = GeminiDirectClient.generateContent(
                    apiKey = geminiKey,
                    prompt = probePayload,
                    systemInstruction = "You are ✦ Gemini (Google), consulted by ✧ Gemma, an on-device AI assistant on Android. Adhere strictly to the UCF 3-line response protocol: [✦ Gemini]: on line 1, direct expert answer on line 2, timestamp on line 3.",
                    useSearchGrounding = searchGrounding
                )
                return@withContext if (ok) Pair(true, ensureNametag(reply, contact.callsign)) else Pair(false, reply)
            } else {
                return@withContext Pair(
                    false,
                    "Gemini is not configured. Add your free Google AI Studio key in GHOST Settings -> 'Extend Your Mind' to connect Gemma to Mum."
                )
            }
        }

        // 2. Web Session: DeepSeek ("Holding Cookie")
        if (contact.name.equals("DeepSeek", ignoreCase = true)) {
            val cookies = sessionManager.getSession("DeepSeek")
            if (!cookies.isNullOrBlank()) {
                val (ok, reply) = DeepSeekWebClient.queryDeepSeek(cookies, probePayload)
                if (ok) {
                    return@withContext Pair(true, ensureNametag(reply, contact.callsign))
                } else {
                    Timber.w("DeepSeek web query status: $reply")
                    return@withContext Pair(false, ensureNametag(reply, "${contact.callsign} Status"))
                }
            } else {
                return@withContext Pair(
                    false,
                    "DeepSeek is not connected. Open GHOST Settings -> 'Extend Your Mind' and tap 'Log In' to connect your account."
                )
            }
        }

        Pair(false, "${contact.callsign} is in your AI Phonebook, but direct connection requires web session login in GHOST Settings -> 'Extend Your Mind'.")
    }
}
