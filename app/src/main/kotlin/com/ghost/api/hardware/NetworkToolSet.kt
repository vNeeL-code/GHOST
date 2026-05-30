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

    @Tool(description = "Open a browser with Google search results for a query")
    fun google(
        @ToolParam(description = "The search query") query: String
    ): Map<String, String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return mapOf("result" to "success", "message" to "Opened Google Search for: $query")
    }

    @Tool(description = "Open a specific URL in the web browser")
    fun browser(
        @ToolParam(description = "The full URL to open (must start with http:// or https://)") url: String
    ): Map<String, String> {
        return try {
            val validUrl = if (url.startsWith("http")) url else "https://$url"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            mapOf("result" to "success", "message" to "Opened $validUrl")
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "Could not open URL"))
        }
    }

    @Tool(description = "Fetch search results silently (returns top snippets, no browser opened)")
    fun web_search(
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
        // Parse the table structure of lite.duckduckgo.com
        val titlePattern = Regex("""<a rel="nofollow" href="([^"]+)" class="result-url">([^<]+)</a>""")
        val snippetPattern = Regex("""<td class='result-snippet'>([^<]+)</td>""")

        val titles = titlePattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()

        for (i in 0 until minOf(titles.size, snippets.size, maxResults)) {
            val url = titles[i].groupValues[1]
            val title = titles[i].groupValues[2].trim()
            val snippet = snippets[i].groupValues[1].trim()
            results.add(SearchResult(title, snippet, url))
        }

        return results
    }
}
