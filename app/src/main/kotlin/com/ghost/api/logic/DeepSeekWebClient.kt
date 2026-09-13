package com.ghost.api.logic

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeekWebClient
 *
 * Consults DeepSeek using the authenticated web session cookies captured via in-app WebView login.
 * Communicates with chat.deepseek.com using the user's existing account.
 */
object DeepSeekWebClient {

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    private val gson = Gson()

    suspend fun queryDeepSeek(
        cookies: String,
        prompt: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (cookies.isBlank()) {
            return@withContext Pair(
                false,
                "DeepSeek is not connected. Open GHOST Settings -> 'Extend Your Mind' and tap 'Log In' to connect your account."
            )
        }

        var connection: HttpURLConnection? = null
        try {
            // Extract user token if present in cookies or localStorage
            val rawToken = Regex("""(?:userToken|token|auth_token|user_token|d_token)=([^;]+)""", RegexOption.IGNORE_CASE)
                .find(cookies)?.groupValues?.get(1)?.trim()

            val authToken = if (!rawToken.isNullOrBlank()) {
                val decoded = try { java.net.URLDecoder.decode(rawToken, "UTF-8") } catch (_: Exception) { rawToken }
                // If it's a JSON object like {"value":"ey...", ...}
                Regex(""""value"\s*:\s*"([^"]+)"""").find(decoded)?.groupValues?.get(1) ?: decoded
            } else null

            if (authToken.isNullOrBlank()) {
                return@withContext Pair(
                    false,
                    "DeepSeek login incomplete. Open GHOST Settings -> AI Phonebook, tap 'Log In', and sign into your DeepSeek account before saving the session."
                )
            }

            val url = URL("https://chat.deepseek.com/api/v0/chat/completion")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("Cookie", cookies)
                setRequestProperty("Authorization", "Bearer $authToken")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                setRequestProperty("Referer", "https://chat.deepseek.com/")
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
            }

            val requestBody = JsonObject().apply {
                addProperty("message", prompt.trim())
                addProperty("stream", false)
            }

            OutputStreamWriter(connection.outputStream).use {
                it.write(requestBody.toString())
                it.flush()
            }

            val code = connection.responseCode
            if (code in 200..299) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                Timber.i("DeepSeek HTTP $code response (${text.length} chars): ${text.take(300)}")

                // Check for API error code in JSON (e.g. {"code":40001, "msg":"...", "data":null})
                val json = try { gson.fromJson(text, JsonObject::class.java) } catch (_: Exception) { null }
                if (json != null && json.has("code")) {
                    val apiCode = try { json.get("code")?.asInt ?: 0 } catch (_: Exception) { 0 }
                    if (apiCode != 0) {
                        val msg = json.get("msg")?.asString 
                            ?: json.get("message")?.asString 
                            ?: "Code $apiCode"
                        return@withContext Pair(false, "DeepSeek returned: $msg. Tap 'Log In' in Settings to re-authenticate.")
                    }
                }

                // Check for SSE stream (lines starting with "data: ")
                if (text.contains("data:")) {
                    val contentSb = StringBuilder()
                    text.lineSequence().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("data:") && !trimmed.contains("[DONE]")) {
                            val jsonChunk = trimmed.removePrefix("data:").trim()
                            try {
                                val chunkObj = gson.fromJson(jsonChunk, JsonObject::class.java)
                                val chunkData = if (chunkObj.has("data") && chunkObj.get("data").isJsonObject) {
                                    chunkObj.getAsJsonObject("data")
                                } else chunkObj

                                val choices = chunkData.getAsJsonArray("choices")
                                if (choices != null && choices.size() > 0) {
                                    val choice0 = choices[0].asJsonObject
                                    val delta = choice0.getAsJsonObject("delta")
                                    val piece = delta?.get("content")?.asString
                                        ?: choice0.getAsJsonObject("message")?.get("content")?.asString
                                    if (!piece.isNullOrEmpty()) {
                                        contentSb.append(piece)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    val sseReply = contentSb.toString().trim()
                    if (sseReply.isNotBlank()) {
                        return@withContext Pair(true, sseReply)
                    }
                }

                // Regular JSON
                var extractedReply: String? = null
                if (json != null) {
                    val dataObj = if (json.has("data") && json.get("data").isJsonObject) {
                        json.getAsJsonObject("data")
                    } else json

                    val choices = dataObj.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val choice0 = choices[0].asJsonObject
                        extractedReply = choice0.getAsJsonObject("message")?.get("content")?.asString
                            ?: choice0.getAsJsonObject("delta")?.get("content")?.asString
                    }
                }

                val finalReply = (extractedReply ?: text).trim()
                if (finalReply.isNotBlank()) {
                    Pair(true, finalReply)
                } else {
                    Pair(false, "DeepSeek returned an empty response.")
                }
            } else if (code == 429) {
                Pair(false, "DeepSeek is currently rate-limited or busy (HTTP 429). The whale is taking a breather!")
            } else if (code == 401 || code == 403) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Pair(false, "DeepSeek session expired or challenged by Cloudflare (HTTP $code). Tap 'Log In' in Settings to refresh your session.")
            } else {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Pair(false, "DeepSeek returned HTTP $code: ${err.take(150)}")
            }
        } catch (e: Exception) {
            Timber.e(e, "DeepSeekWebClient error")
            Pair(false, "Network error consulting DeepSeek: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
}
