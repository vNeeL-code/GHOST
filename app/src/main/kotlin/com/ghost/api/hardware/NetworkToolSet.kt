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
        @ToolParam(description = "Max results to return") maxResults: Int = 5
    ): Map<String, String> = runBlocking(Dispatchers.IO) {
        Timber.i("Performing silent search for: $query")

        val ddgResult = try { fetchDuckDuckGoLite(query, maxResults) } catch (e: Exception) { 
            Timber.w("DuckDuckGo search failed: ${e.message}")
            null 
        }
        
        if (ddgResult != null) {
            return@runBlocking mapOf("result" to "success", "content" to ddgResult)
        }

        mapOf("result" to "error", "message" to "Search failed for '$query'.")
    }

    private fun fetchDuckDuckGoLite(query: String, maxResults: Int): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://lite.duckduckgo.com/lite/")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        connection.outputStream.write("q=$encoded".toByteArray())

        if (connection.responseCode != 200) return null
        val html = connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        val results = parseDuckDuckGoLiteResults(html, maxResults)
        if (results.isEmpty()) return null

        val sb = StringBuilder("SEARCH RESULTS for '$query':\n\n")
        results.forEachIndexed { index, result ->
            sb.append("${index + 1}. ${result.title}\n   ${result.snippet}\n   URL: ${result.url}\n\n")
        }
        sb.append("---\nSynthesize these results to answer.")
        return sb.toString()
    }

    @Tool(description = "Fetches plaintext content from a webpage URL")
    fun fetchWebpage(
        @ToolParam(description = "The URL string") urlString: String, 
        @ToolParam(description = "Maximum characters to return") maxChars: Int = 10000
    ): Map<String, String> = runBlocking(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Gemma/1.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode != 200) {
                return@runBlocking mapOf("result" to "error", "message" to "HTTP ${connection.responseCode}")
            }

            val html = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val text = html
                .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(maxChars)

            mapOf("result" to "success", "content" to text)
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "failed"))
        }
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
