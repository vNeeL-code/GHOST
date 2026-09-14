package com.ghost.api.hardware

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Network Tools - Web search and fetch
 */
class NetworkToolSet(private val context: Context) : ToolSet {

    @Tool(description = "Activated ONLY when the user explicitly dictates a navigational command or an explicit 'Google this' action (e.g. 'Go to miniclip', 'Open Wikipedia', 'Google Y for me'). Treats the input like a physical desktop URL bar.")
    fun open_system_browser_bar(
        @ToolParam(description = "The exact search query or URL to navigate to") queryOrUrl: String
    ): Map<String, String> {
        val parsedUri = if (android.util.Patterns.WEB_URL.matcher(queryOrUrl).matches()) {
            val validUrl = if (queryOrUrl.startsWith("http")) queryOrUrl else "https://$queryOrUrl"
            Uri.parse(validUrl)
        } else {
            Uri.parse("https://www.google.com/search?q=${Uri.encode(queryOrUrl)}")
        }
        val intent = Intent(Intent.ACTION_VIEW, parsedUri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return mapOf("result" to "success", "message" to "Handed off to system browser for: $queryOrUrl")
    }

    @Tool(description = "DEFAULT SEARCH TOOL. Use this naturally whenever you need to search the web, find fresh info, or lack knowledge about a topic.")
    fun execute_background_search(
        @ToolParam(description = "Search query") query: String, 
        @ToolParam(description = "Max results to return (default: 3, max: 4)") maxResults: Int = 3
    ): Map<String, String> = runBlocking(Dispatchers.IO) {
        val cleanQuery = sanitizeToolString(query, 120)
        if (cleanQuery.isBlank()) {
            return@runBlocking mapOf("result" to "error", "message" to "Search query was empty.")
        }
        Timber.i("Performing silent search for: $cleanQuery")
        com.ghost.api.GemmaService.instance?.showWorkSignal("SEARCHING")
        try {
            val tokenManager = com.ghost.api.logic.HFTokenManager(context)
            val sessionManager = com.ghost.api.logic.WebSessionManager.getInstance(context)
            val geminiKey = tokenManager.getGeminiKey()

            var searchResult: String? = null

            // Direct pipe to Mum (✦ Gemini) with Google Search Grounding
            if (!geminiKey.isNullOrBlank() && sessionManager.isGeminiSearchGroundingEnabled()) {
                try {
                    searchResult = com.ghost.api.logic.GeminiDirectClient.searchGround(geminiKey, cleanQuery)
                    if (!searchResult.isNullOrBlank()) {
                        Timber.i("Google Search Grounding via Gemini succeeded for: $cleanQuery")
                    }
                } catch (e: Exception) {
                    Timber.w("Gemini search grounding failed, falling back to DuckDuckGo: ${e.message}")
                }
            }

            // Fallback to DuckDuckGo Lite if Gemini search grounding is inactive or failed
            if (searchResult.isNullOrBlank()) {
                searchResult = try { 
                    fetchDuckDuckGoLite(cleanQuery, maxResults.coerceIn(1, 4)) 
                } catch (e: Exception) { 
                    Timber.w("DuckDuckGo search failed: ${e.message}")
                    null 
                }
            }
            
            if (searchResult != null && searchResult.isNotBlank()) {
                val cleanResult = sanitizeToolString(searchResult, 1400)
                com.ghost.api.GemmaService.instance?.recordToolOutput(cleanResult.length)
                return@runBlocking mapOf("result" to "success", "content" to cleanResult)
            }

            mapOf("result" to "error", "message" to "No search results found for '$cleanQuery'.")
        } finally {
            com.ghost.api.GemmaService.instance?.showWorkSignal("SYNTHESIZING")
        }
    }

    private fun fetchDuckDuckGoLite(query: String, maxResults: Int): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://lite.duckduckgo.com/lite/")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        connection.connectTimeout = 8000
        connection.readTimeout = 8000

        try {
            connection.outputStream.use { it.write("q=$encoded".toByteArray()) }

            if (connection.responseCode != 200) return null
            val html = connection.inputStream.bufferedReader().use { it.readText() }

            val results = parseDuckDuckGoLiteResults(html, maxResults)
            if (results.isEmpty()) return null

            val sb = StringBuilder("SEARCH RESULTS for '$query':\n\n")
            results.forEachIndexed { index, result ->
                val title = sanitizeToolString(result.title, 80)
                val snippet = sanitizeToolString(result.snippet, 180)
                val cleanUrl = sanitizeToolString(result.url, 120)
                sb.append("${index + 1}. $title\n   $snippet\n   URL: $cleanUrl\n\n")
            }
            sb.append("---\nSynthesize these results to answer.")
            return sanitizeToolString(sb.toString(), 1400)
        } finally {
            connection.disconnect()
        }
    }

    @Tool(description = "Fetches plaintext content from a webpage URL")
    fun fetchWebpage(
        @ToolParam(description = "The URL string") urlString: String, 
        @ToolParam(description = "Maximum characters to return (default: 1500, max: 2000)") maxChars: Int = 1500
    ): Map<String, String> = runBlocking(Dispatchers.IO) {
        val safeMaxChars = maxChars.coerceIn(200, 2000)
        com.ghost.api.GemmaService.instance?.showWorkSignal("FETCHING")
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Gemma/1.0")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            try {
                if (connection.responseCode != 200) {
                    return@runBlocking mapOf("result" to "error", "message" to "HTTP ${connection.responseCode}")
                }

                val html = connection.inputStream.bufferedReader().use { it.readText() }

                val text = html
                    .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
                    .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
                    .replace(Regex("<[^>]+>"), " ")

                val cleanText = sanitizeToolString(text, safeMaxChars)
                com.ghost.api.GemmaService.instance?.recordToolOutput(cleanText.length)
                mapOf("result" to "success", "content" to cleanText)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message?.take(80) ?: "failed"))
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    @Tool(description = "Consults a peer AI from Gemma's phonebook (Gemini, DeepSeek) for frontier reasoning, coding, math, or Google search grounding")
    fun consult_peer(
        @ToolParam(description = "Peer name or callsign: Gemini, DeepSeek") peer: String,
        @ToolParam(description = "The prompt or question to ask the peer") prompt: String
    ): Map<String, String> = runBlocking(Dispatchers.IO) {
        com.ghost.api.GemmaService.instance?.showWorkSignal("PHONEBOOK", 2500)
        com.ghost.api.audio.SystemVisualizer.setActivePeer(peer)
        try {
            val contact = com.ghost.api.logic.AiPhonebook.resolvePeer(peer)
                ?: return@runBlocking mapOf(
                    "result" to "error",
                    "message" to "Peer '$peer' not found in AI Phonebook. Available: Gemini, DeepSeek."
                )

            val (success, reply) = com.ghost.api.logic.AiPhonebook.queryPeer(context, contact, prompt)
            val cleanReply = sanitizeToolString(reply, 1800)
            if (success) {
                com.ghost.api.GemmaService.instance?.recordToolOutput(cleanReply.length)
                mapOf("result" to "success", "peer" to contact.callsign, "content" to cleanReply)
            } else {
                mapOf("result" to "error", "peer" to contact.callsign, "message" to cleanReply)
            }
        } catch (e: Exception) {
            Timber.e(e, "consult_peer failed")
            mapOf("result" to "error", "message" to "Failed to consult peer '$peer': ${e.message}")
        } finally {
            com.ghost.api.audio.SystemVisualizer.setActivePeer(null)
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    @Tool(description = "Alias for consult_peer. Consults a peer AI from Gemma's phonebook.")
    fun consultpeer(
        @ToolParam(description = "Peer name or callsign: Gemini, DeepSeek") peer: String,
        @ToolParam(description = "The prompt or question to ask the peer") prompt: String
    ): Map<String, String> = consult_peer(peer, prompt)

    /**
     * Defensive sanitizer for all tool return strings passed into LiteRT-LM C++ JNI bridge.
     * Prevents SIGSEGV SEGV_ACCERR buffer overruns, unescaped HTML entities, and control token poisoning.
     */
    private fun sanitizeToolString(input: String, maxChars: Int): String {
        return input
            // HTML entity unescaping
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            // Strip LiteRT-LM & Gemma special control tokens that could corrupt native tokenizer state
            .replace(Regex("<\\|[a-zA-Z0-9_]+\\|?>"), "")
            .replace(Regex("<[a-zA-Z0-9_]+\\|>"), "")
            .replace(Regex("<start_of_turn>|<end_of_turn>|<thought>|</thought>|<call:[^>]+>|</call>"), "")
            // Strip null bytes and non-printable control characters (keep \n, \r, \t)
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFD]"), "")
            // Collapse whitespace runs
            .replace(Regex("[ \\t]+"), " ")
            .trim()
            .take(maxChars)
    }

    private data class SearchResult(val title: String, val snippet: String, val url: String)

    private fun parseDuckDuckGoLiteResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        // Parse the table structure of lite.duckduckgo.com using more robust regex
        val titlePattern = Regex("""<a[^>]*?href=["']([^"']+)["'][^>]*?class=["']result-link["'][^>]*?>([\s\S]*?)</a>""")
        val snippetPattern = Regex("""class=["']result-snippet["'][^>]*?>([\s\S]*?)</td>""")

        val titles = titlePattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()

        for (i in 0 until minOf(titles.size, snippets.size, maxResults)) {
            val url = titles[i].groupValues[1]
            val rawTitle = titles[i].groupValues[2]
            val title = rawTitle.replace(Regex("<[^>]+>"), "").trim()
            val rawSnippet = snippets[i].groupValues[1]
            val snippet = rawSnippet.replace(Regex("<[^>]+>"), "").trim()
            results.add(SearchResult(title, snippet, url))
        }

        return results
    }
}
