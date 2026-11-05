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

/**
 * GeminiDirectClient
 *
 * Direct, zero-latency HTTP connection to Google Gemini 2.0 Flash via Google AI Studio.
 * Serves as Gemma's primary frontier anchor ("Mum") with built-in Google Search Grounding.
 */
object GeminiDirectClient {

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    private const val FALLBACK_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"
    private val gson = Gson()

    /**
     * Send a generation prompt to Gemini with optional Google Search grounding.
     */
    suspend fun generateContent(
        apiKey: String,
        prompt: String,
        systemInstruction: String? = null,
        useSearchGrounding: Boolean = false
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Pair(
                false,
                "Google Gemini API key is not configured. Add your free Google AI Studio key in GHOST Settings -> 'Extend Your Mind'."
            )
        }

        // If search grounding is requested, try with grounding first, then fallback to standard generation if grounding errors/quotas out
        val groundingModes = if (useSearchGrounding) listOf(true, false) else listOf(false)
        val urlsToTry = listOf(BASE_URL, FALLBACK_URL)
        var lastError = "Failed to reach Gemini"

        for (groundingMode in groundingModes) {
            for (targetUrl in urlsToTry) {
                var connection: HttpURLConnection? = null
                try {
                    val endpoint = URL("$targetUrl?key=${apiKey.trim()}")
                    connection = (endpoint.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        connectTimeout = 15000
                        readTimeout = 25000
                        doOutput = true
                    }

                    val requestJson = JsonObject()

                    // Optional System Instruction
                    if (!systemInstruction.isNullOrBlank()) {
                        val sysObj = JsonObject().apply {
                            val parts = JsonArray().apply {
                                add(JsonObject().apply { addProperty("text", systemInstruction.trim()) })
                            }
                            add("parts", parts)
                        }
                        requestJson.add("system_instruction", sysObj)
                    }

                    // User Content
                    val contentsArray = JsonArray().apply {
                        val userContent = JsonObject().apply {
                            addProperty("role", "user")
                            val parts = JsonArray().apply {
                                add(JsonObject().apply { addProperty("text", prompt.trim()) })
                            }
                            add("parts", parts)
                        }
                        add(userContent)
                    }
                    requestJson.add("contents", contentsArray)

                    // Google Search Grounding
                    if (groundingMode) {
                        val toolsArray = JsonArray().apply {
                            val searchTool = JsonObject().apply {
                                add("google_search", JsonObject())
                            }
                            add(searchTool)
                        }
                        requestJson.add("tools", toolsArray)
                    }

                    // Generation config
                    val genConfig = JsonObject().apply {
                        addProperty("temperature", 0.7)
                        addProperty("maxOutputTokens", 1000)
                    }
                    requestJson.add("generationConfig", genConfig)

                    // Send payload
                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(requestJson.toString())
                        writer.flush()
                    }

                    val responseCode = connection.responseCode
                    if (responseCode in 200..299) {
                        val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val root = gson.fromJson(responseText, JsonObject::class.java)

                        val candidates = root?.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray
                        if (candidates != null && candidates.size() > 0) {
                            val candidate = candidates.get(0).takeIf { it.isJsonObject }?.asJsonObject
                            val content = candidate?.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
                            val parts = content?.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray
                            val replyBuilder = StringBuilder()

                            parts?.forEach { partElement ->
                                if (partElement.isJsonObject) {
                                    val textEl = partElement.asJsonObject.get("text")
                                    val text = if (textEl != null && !textEl.isJsonNull) textEl.asString else null
                                    if (!text.isNullOrBlank()) {
                                        replyBuilder.append(text)
                                    }
                                }
                            }

                            // Append citations if search grounding was used and sources are present
                            val grounding = candidate?.get("groundingMetadata")?.takeIf { it.isJsonObject }?.asJsonObject
                            val chunks = grounding?.get("groundingChunks")?.takeIf { it.isJsonArray }?.asJsonArray
                            if (chunks != null && chunks.size() > 0) {
                                val sources = mutableListOf<String>()
                                for (i in 0 until minOf(chunks.size(), 3)) {
                                    val chunkObj = chunks.get(i).takeIf { it.isJsonObject }?.asJsonObject
                                    val web = chunkObj?.get("web")?.takeIf { it.isJsonObject }?.asJsonObject
                                    val title = web?.get("title")?.takeIf { !it.isJsonNull }?.asString
                                    val uri = web?.get("uri")?.takeIf { !it.isJsonNull }?.asString
                                    if (!uri.isNullOrBlank()) {
                                        sources.add(if (!title.isNullOrBlank()) "$title: $uri" else uri)
                                    }
                                }
                                if (sources.isNotEmpty()) {
                                    replyBuilder.append("\n\nSources:\n")
                                    sources.forEach { replyBuilder.append("- $it\n") }
                                }
                            }

                            val finalReply = replyBuilder.toString().trim()
                            if (finalReply.isNotBlank()) {
                                return@withContext Pair(true, finalReply)
                            }
                            lastError = "Gemini returned an empty response."
                        } else {
                            lastError = "Gemini did not return any candidate response."
                        }
                    } else {
                        val errorStream = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                        Timber.w("Gemini API error (grounding=$groundingMode, url=$targetUrl): HTTP $responseCode - $errorStream")
                        lastError = "Gemini API error ($responseCode): ${errorStream.take(200)}"
                    }
                } catch (e: Exception) {
                    Timber.e(e, "GeminiDirectClient generateContent failed on $targetUrl (grounding=$groundingMode)")
                    lastError = "Network error consulting Gemini: ${e.message}"
                } finally {
                    connection?.disconnect()
                }
            }
        }
        Pair(false, lastError)
    }

    /**
     * Specialized method for live background web search synthesis.
     * Uses Gemini 2.0 Flash + Google Search Grounding to return real-time web knowledge.
     */
    suspend fun searchGround(apiKey: String, query: String): String? {
        val (success, reply) = generateContent(
            apiKey = apiKey,
            prompt = "Search the web and provide current, concise, factual findings for: $query",
            systemInstruction = "You are a real-time web search synthesizer for ✧ Gemma. Research the user query using Google Search. Return concise, factual, well-synthesized points with source URLs.",
            useSearchGrounding = true
        )
        return if (success) reply else null
    }
}
