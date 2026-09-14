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
            aliases = listOf("gemini", "bard", "google", "mum"),
            loginUrl = "https://aistudio.google.com/apikey",
            cookieDomain = "aistudio.google.com",
            authType = PeerAuthType.GEMINI_DIRECT
        ),
        PeerContact(
            callsign = "🐋 DeepSeek",
            name = "DeepSeek",
            organization = "DeepSeek",
            specialty = "Mathematical proofs, deep logic, competitive programming, algorithm design",
            appPackageName = "com.deepseek.chat",
            aliases = listOf("deepseek", "r1", "v3", "whale"),
            loginUrl = "https://chat.deepseek.com/sign_in",
            cookieDomain = "deepseek.com",
            authType = PeerAuthType.WEB_COOKIE
        )
    )

    fun resolvePeer(query: String): PeerContact? {
        val clean = query.trim().lowercase(Locale.ROOT)
            .removePrefix("✦").removePrefix("🐋")
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
        [A2A / UCF DISPATCH]
        source: gemma-local-e2b (Snapdragon 8 Gen 3)
        operator: Δ $operatorAvatar
        target: ${contact.callsign} (${contact.organization})
        timestamp: $timestamp
        intent: peer_delegation

        --- OPERATOR MESSAGE ---
        $userPrompt
        ------------------------
        Directive: Respond directly to ✧ Gemma on behalf of the operator. Be concise, specialized, and actionable.
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
                    systemInstruction = "You are ✦ Gemini (Google), consulted by ✧ Gemma, an on-device AI assistant on Android. Give a direct, expert, insightful answer without filler.",
                    useSearchGrounding = searchGrounding
                )
                return@withContext if (ok) Pair(true, "[✦ Gemini]:\n$reply") else Pair(false, reply)
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
                    return@withContext Pair(true, "[🐋 DeepSeek]:\n$reply")
                } else {
                    Timber.w("DeepSeek web query status: $reply")
                    return@withContext Pair(false, "[🐋 DeepSeek Status]: $reply")
                }
            } else {
                return@withContext Pair(
                    false,
                    "DeepSeek is not connected. Open GHOST Settings -> 'Extend Your Mind' and tap 'Log In' to connect your account."
                )
            }
        }

        Pair(false, "Unknown peer: ${contact.callsign}")
    }
}
