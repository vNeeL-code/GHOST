package com.ghost.api.logic

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * ClaudeWebClient
 *
 * Consults Claude using the authenticated web session cookies captured via in-app WebView login.
 * Communicates with claude.ai endpoints using the user's existing account.
 */
object ClaudeWebClient {

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    private val gson = Gson()

    @Volatile
    private var cachedOrgUuid: String? = null

    suspend fun queryClaude(
        cookies: String,
        prompt: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (cookies.isBlank()) {
            return@withContext Pair(
                false,
                "Claude is not connected. Open GHOST Settings -> 'Extend Your Mind' and tap 'Log In' to connect your Claude account."
            )
        }

        try {
            // 1. Resolve Organization UUID if not already cached
            val orgUuid = cachedOrgUuid ?: fetchOrganizationUuid(cookies)
            if (orgUuid == null) {
                return@withContext Pair(
                    false,
                    "Unable to access Claude account. Your web session may have expired. Open Settings -> 'Extend Your Mind' and tap 'Log In' to reconnect."
                )
            }
            cachedOrgUuid = orgUuid

            // 2. Create a conversation
            val conversationUuid = createConversation(orgUuid, cookies)
            if (conversationUuid == null) {
                return@withContext Pair(
                    false,
                    "Failed to initialize Claude chat conversation. Please re-authenticate Claude in Settings."
                )
            }

            // 3. Send prompt and retrieve completion
            val (ok, reply) = sendPrompt(orgUuid, conversationUuid, cookies, prompt)
            if (ok && reply.isNotBlank()) {
                Pair(true, reply.trim())
            } else {
                Pair(false, reply.ifBlank { "Claude returned an empty response." })
            }
        } catch (e: Exception) {
            Timber.e(e, "ClaudeWebClient query failed")
            Pair(false, "Network error consulting Claude: ${e.message}")
        }
    }

    private fun fetchOrganizationUuid(cookies: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://api.claude.ai/api/organizations")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("Cookie", cookies)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Referer", "https://claude.ai/")
                connectTimeout = 12000
                readTimeout = 12000
            }

            if (connection.responseCode in 200..299) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val array = gson.fromJson(text, JsonArray::class.java)
                if (array != null && array.size() > 0) {
                    val org = array.get(0).asJsonObject
                    return org.get("uuid")?.asString
                }
            } else {
                Timber.w("Claude get organizations failed: HTTP ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Error fetching Claude organization")
        } finally {
            connection?.disconnect()
        }
        return null
    }

    private fun createConversation(orgUuid: String, cookies: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val convUuid = UUID.randomUUID().toString()
            val url = URL("https://api.claude.ai/api/organizations/$orgUuid/chat_conversations")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("Cookie", cookies)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Referer", "https://claude.ai/new")
                connectTimeout = 12000
                readTimeout = 12000
                doOutput = true
            }

            val body = JsonObject().apply {
                addProperty("uuid", convUuid)
                addProperty("name", "")
            }

            OutputStreamWriter(connection.outputStream).use {
                it.write(body.toString())
                it.flush()
            }

            if (connection.responseCode in 200..299) {
                return convUuid
            } else {
                Timber.w("Claude createConversation failed: HTTP ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Error creating Claude conversation")
        } finally {
            connection?.disconnect()
        }
        return null
    }

    private fun sendPrompt(
        orgUuid: String,
        convUuid: String,
        cookies: String,
        prompt: String
    ): Pair<Boolean, String> {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://api.claude.ai/api/organizations/$orgUuid/chat_conversations/$convUuid/completion")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("Cookie", cookies)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream, application/json")
                setRequestProperty("Referer", "https://claude.ai/chat/$convUuid")
                connectTimeout = 15000
                readTimeout = 35000
                doOutput = true
            }

            val body = JsonObject().apply {
                addProperty("prompt", prompt.trim())
                addProperty("timezone", "UTC")
                addProperty("model", "claude-3-7-sonnet-20250219")
                val attachments = JsonArray()
                add("attachments", attachments)
                val files = JsonArray()
                add("files", files)
            }

            OutputStreamWriter(connection.outputStream).use {
                it.write(body.toString())
                it.flush()
            }

            val code = connection.responseCode
            if (code in 200..299) {
                val fullResponse = StringBuilder()
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // Claude streaming returns "data: {"completion": "..."}"
                        if (line.startsWith("data: ")) {
                            val dataStr = line.removePrefix("data: ").trim()
                            if (dataStr != "[DONE]" && dataStr.startsWith("{")) {
                                try {
                                    val obj = gson.fromJson(dataStr, JsonObject::class.java)
                                    val piece = obj.get("completion")?.asString
                                    if (piece != null) {
                                        fullResponse.append(piece)
                                    }
                                } catch (_: Exception) {}
                            }
                        } else if (!line.startsWith("event: ") && line.isNotBlank()) {
                            // Non-stream plain JSON response fallback
                            try {
                                val obj = gson.fromJson(line, JsonObject::class.java)
                                val text = obj.get("completion")?.asString ?: obj.get("text")?.asString
                                if (text != null) fullResponse.append(text)
                            } catch (_: Exception) {}
                        }
                    }
                }
                return Pair(true, fullResponse.toString().trim())
            } else if (code == 429) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Timber.w("Claude rate limited / in timeout (429): $err")
                return Pair(false, "Claude is currently in timeout / rate-limited by Anthropic: ${err.take(150)}")
            } else if (code == 401 || code == 403) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Timber.w("Claude session unauthorized/challenged ($code): $err")
                return Pair(false, "Claude web session expired or challenged by Cloudflare (HTTP $code). Tap 'Log In' in Settings to refresh.")
            } else {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Timber.w("Claude sendPrompt failed: HTTP $code - $err")
                return Pair(false, "Claude returned HTTP $code: ${err.take(150)}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Error sending prompt to Claude")
            return Pair(false, "Connection error: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
}
