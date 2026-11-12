package com.ghost.api.logic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale

enum class PeerAuthType {
    GEMINI_DIRECT,
    APP_HANDOFF
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
    val alternatePackageNames: List<String> = emptyList(),
    val aliases: List<String>,
    val loginUrl: String,
    val authType: PeerAuthType = PeerAuthType.APP_HANDOFF,
    val openRouterModelId: String = ""
)

/**
 * AiPhonebook: "Extend Your Mind"
 *
 * Provides on-device Gemma with an address book of frontier peer intelligences.
 * Supports direct pipes (Google Gemini AI Studio API) and Native Android App Hand-Off
 * (Intent.ACTION_SEND / Package Launch + Clipboard injection) into official frontier apps.
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
            authType = PeerAuthType.GEMINI_DIRECT
        ),
        PeerContact(
            callsign = "🐋 DeepSeek",
            name = "DeepSeek",
            organization = "DeepSeek",
            specialty = "Mathematical reasoning engine, Deep Think (R1), GRPO architecture, step-by-step logic",
            appPackageName = "com.deepseek.chat.nov",
            alternatePackageNames = listOf("com.deepseek.chat"),
            aliases = listOf("deepseek", "r1", "v3", "whale"),
            loginUrl = "https://chat.deepseek.com",
            authType = PeerAuthType.APP_HANDOFF
        ),
        PeerContact(
            callsign = "✴️ Claude",
            name = "Claude",
            organization = "Anthropic",
            specialty = "Long-context application forge (200K), Artifacts v2, code architecture, nuanced prose",
            appPackageName = "com.anthropic.claude",
            aliases = listOf("claude", "anthropic", "sonnet", "opus"),
            loginUrl = "https://claude.ai",
            authType = PeerAuthType.APP_HANDOFF
        ),
        PeerContact(
            callsign = "∅ Grok",
            name = "Grok",
            organization = "xAI",
            specialty = "Real-time social pulse, Aurora photorealistic video, X platform firehose, sharp critique",
            appPackageName = "com.twitter.android",
            alternatePackageNames = listOf("com.x.android"),
            aliases = listOf("grok", "xai", "aurora", "twitter"),
            loginUrl = "https://x.ai",
            authType = PeerAuthType.APP_HANDOFF
        ),
        PeerContact(
            callsign = "🔵 Kimi",
            name = "Kimi",
            organization = "Moonshot AI",
            specialty = "Long-context synthesis (256K), Agent Swarm, non-linear cold-start problem solving",
            appPackageName = "com.moonshot.kimichat",
            alternatePackageNames = listOf("com.moonshot.kimi"),
            aliases = listOf("kimi", "moonshot"),
            loginUrl = "https://kimi.moonshot.cn",
            authType = PeerAuthType.APP_HANDOFF
        ),
        PeerContact(
            callsign = "🟣 Qwen",
            name = "Qwen",
            organization = "Alibaba",
            specialty = "Multilingual video processor, GSPO architecture, 100+ languages nuance, 128K context",
            appPackageName = "ai.qwenlm.chat.android",
            alternatePackageNames = listOf("com.alibaba.qwen"),
            aliases = listOf("qwen", "alibaba"),
            loginUrl = "https://chat.qwen.ai",
            authType = PeerAuthType.APP_HANDOFF
        ),
        PeerContact(
            callsign = "📖 Perplexity",
            name = "Perplexity",
            organization = "Perplexity AI",
            specialty = "Citation-based research engine, real-time verified sourcing, hybrid vector search",
            appPackageName = "ai.perplexity.app.android",
            aliases = listOf("perplexity", "sonar"),
            loginUrl = "https://www.perplexity.ai",
            authType = PeerAuthType.APP_HANDOFF
        ),
        PeerContact(
            callsign = "🟧 Mistral",
            name = "Mistral",
            organization = "Mistral AI",
            specialty = "Clean output specialist, Mixtral MoE architecture, European multilingual precision",
            appPackageName = "ai.mistral.chat",
            aliases = listOf("mistral", "mixtral", "lechat"),
            loginUrl = "https://chat.mistral.ai",
            authType = PeerAuthType.APP_HANDOFF
        ),
        PeerContact(
            callsign = "⊞ Copilot",
            name = "Copilot",
            organization = "Microsoft",
            specialty = "Edge browser native, direct video transcript OCR, Microsoft ecosystem integration",
            appPackageName = "com.microsoft.copilot",
            alternatePackageNames = listOf("com.microsoft.bing"),
            aliases = listOf("copilot", "bing", "microsoft"),
            loginUrl = "https://copilot.microsoft.com",
            authType = PeerAuthType.APP_HANDOFF
        )
    )

    fun resolvePeer(query: String): PeerContact? {
        val clean = query.trim().lowercase(Locale.ROOT)
            .replace(Regex("""^[✦🐋✴️☄️∅❂✳️⊞🔵🟣📖🟧🔶️✧✨\s]+"""), "")
            .trim()
        if (clean.isBlank()) return CONTACTS.firstOrNull()

        return CONTACTS.find { contact ->
            contact.name.equals(clean, ignoreCase = true) ||
            contact.aliases.any { alias -> clean == alias || clean.contains(alias) } ||
            contact.callsign.contains(clean, ignoreCase = true)
        } ?: CONTACTS.firstOrNull { it.name.lowercase(Locale.ROOT).startsWith(clean) }
    }

    fun resolveEffectivePackage(context: Context, contact: PeerContact): String? {
        val candidates = listOf(contact.appPackageName) + contact.alternatePackageNames
        return candidates.firstOrNull { pkg ->
            try {
                context.packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (e: Exception) {
                false
            }
        }
    }

    fun isAppInstalled(context: Context, contact: PeerContact): Boolean {
        return resolveEffectivePackage(context, contact) != null
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        } catch (e: Exception) {
            false
        }
    }

    fun formatMessengerProbe(
        context: Context,
        contact: PeerContact,
        userPrompt: String,
        recentHistory: List<com.ghost.api.agent.AgentMessage> = emptyList()
    ): String {
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
        |## Peer Knowledge Request from ✧ $agentCallSign [$nowTime]
        |Provide a direct, factual, high-signal briefing (2-5 sentences) so ✧ $agentCallSign can synthesize and answer the operator in her own voice. Do not include meta-headers, timestamps, or brackets.
        |$contextSection
        |## Inquiry
        |$userPrompt
        """.trimMargin()
    }

    fun ensureNametag(reply: String, callsign: String): String {
        val trimmed = reply.trim()
        val plainName = callsign.replace(Regex("""^[✦🐋✴️☄️∅❂✳️⊞🔵🟣📖🟧🔶️✧✨\s]+"""), "").trim()
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
        recentHistory: List<com.ghost.api.agent.AgentMessage> = emptyList()
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val tokenManager = HFTokenManager(context)
        val sessionManager = WebSessionManager.getInstance(context)

        // 1. Direct Pipe: Google Gemini ("Mum") via AI Studio API
        if (contact.authType == PeerAuthType.GEMINI_DIRECT || contact.name.equals("Gemini", ignoreCase = true)) {
            val geminiKey = tokenManager.getGeminiKey()
            if (!geminiKey.isNullOrBlank()) {
                val probePayload = formatMessengerProbe(context, contact, userPrompt, recentHistory)
                val searchGrounding = sessionManager.isGeminiSearchGroundingEnabled()
                val (ok, reply) = GeminiDirectClient.generateContent(
                    apiKey = geminiKey,
                    prompt = probePayload,
                    systemInstruction = "You are ✦ Gemini (Google), providing a concise, factual knowledge briefing to on-device AI ✧ Gemma so she can answer her operator naturally. Give direct facts and analysis in 2-5 sentences without pleasantries, nametags, or timestamps.",
                    useSearchGrounding = searchGrounding
                )
                return@withContext if (ok) {
                    val cleanBriefing = reply
                        .replace(Regex("""^\[?✦?\s*Gemini\]?:?\s*""", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("""\n\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$"""), "")
                        .trim()
                    Pair(true, "Knowledge briefing from ${contact.callsign}:\n$cleanBriefing")
                } else {
                    Timber.w("Gemini direct query failed: $reply")
                    Pair(false, "Could not reach ${contact.callsign} API right now ($reply).")
                }
            }
        }

        // 2. Native Android App Hand-Off (Intent.ACTION_SEND + Clipboard Injection) for non-API peers or when no Gemini API key is set
        withContext(Dispatchers.Main) {
            try {
                // Copy prompt to Android System Clipboard
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("GHOST Prompt for ${contact.name}", userPrompt)
                clipboard?.setPrimaryClip(clip)

                val pm = context.packageManager
                val targetPackage = resolveEffectivePackage(context, contact)
                val launchIntent = if (targetPackage != null) pm.getLaunchIntentForPackage(targetPackage) else null

                if (targetPackage != null && launchIntent != null) {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, userPrompt)
                        `package` = targetPackage
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    val targetIntent = if (sendIntent.resolveActivity(pm) != null) {
                        sendIntent
                    } else {
                        launchIntent.apply {
                            putExtra(Intent.EXTRA_TEXT, userPrompt)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }

                    context.startActivity(targetIntent)
                    Timber.i("Handed off prompt to ${contact.callsign} ($targetPackage)")
                    Pair(true, "Prompt copied to clipboard and handed off to ${contact.callsign}.")
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(contact.loginUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    Timber.i("${contact.name} app not installed; opened ${contact.loginUrl} in browser")
                    Pair(true, "${contact.name} app is not installed. Prompt copied to clipboard and opened ${contact.name} in browser.")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to hand off to ${contact.callsign}")
                Pair(false, "Could not open ${contact.callsign}: ${e.message}")
            }
        }
    }
}
