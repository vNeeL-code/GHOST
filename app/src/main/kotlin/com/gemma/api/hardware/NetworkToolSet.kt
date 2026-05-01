package com.gemma.api.hardware

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    var onBrowserBubbleRequest: ((String, (String) -> Unit) -> Unit)? = null

    @Tool(description = "Researches a query via the floating web browser bubble")
    fun googleSearch(
        @ToolParam(description = "The query to search for") query: String
    ): Map<String, String> {
        val url = "https://www.google.com/search?q=${Uri.encode(query)}"
        
        return if (onBrowserBubbleRequest != null) {
            Timber.i("Launching Agent Browser Bubble for Google Search: $query")
            var scrapedData = ""
            var wait = true
            
            runBlocking {
                withContext(Dispatchers.Main) {
                    onBrowserBubbleRequest?.invoke(url) { data ->
                        scrapedData = data.take(4000)
                        wait = false
                    }
                }
                
                // Wait for the UI callback to fire back the scraped data
                var waitCount = 0
                while (wait && waitCount < 100) { // 10s max wait
                    kotlinx.coroutines.delay(100)
                    waitCount++
                }
            }
            
            mapOf("result" to "success", "content" to "Top results from browser:\n$scrapedData")
        } else {
            mapOf("result" to "error", "message" to "No browser bubble callback")
        }
    }

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
    fun search(
        @ToolParam(description = "Search query") query: String, 
        @ToolParam(description = "Max results to return") maxResults: Int = 5
    ): Map<String, String> = runBlocking(Dispatchers.IO) {
        val googleResult = try { fetchGoogleResults(query, maxResults) } catch (_: Exception) { null }
        if (googleResult != null) return@runBlocking mapOf("result" to "success", "content" to googleResult)

        val ddgResult = try { fetchDuckDuckGoResults(query, maxResults) } catch (_: Exception) { null }
        if (ddgResult != null) return@runBlocking mapOf("result" to "success", "content" to ddgResult)

        mapOf("result" to "error", "message" to "Search failed for '$query'")
    }

    private fun fetchGoogleResults(query: String, maxResults: Int): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.google.com/search?q=$encoded&num=$maxResults&hl=en")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.instanceFollowRedirects = true

        if (connection.responseCode != 200) return null

        val html = connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        val results = parseGoogleResults(html, maxResults, query)
        if (results.isEmpty()) return null

        val sb = StringBuilder("GOOGLE RESULTS for '$query':\n\n")
        results.forEachIndexed { index, result ->
            sb.append("${index + 1}. ${result.title}\n")
            if (result.snippet.isNotBlank()) sb.append("   ${result.snippet}\n")
            if (result.url.isNotBlank()) sb.append("   ${result.url}\n\n")
        }
        sb.append("---\nSynthesize these results to answer.")
        return sb.toString()
    }

    private fun fetchDuckDuckGoResults(query: String, maxResults: Int): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://html.duckduckgo.com/html/?q=$encoded")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Gemma/1.0")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        if (connection.responseCode != 200) return null
        val html = connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        val results = parseDuckDuckGoResults(html, maxResults)
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

    private fun parseGoogleResults(html: String, maxResults: Int, query: String = ""): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        if (html.contains("redirected within a few seconds", ignoreCase = true) || 
            html.contains("having trouble accessing Google Search", ignoreCase = true)) {
            return emptyList()
        }

        val linkPattern = Regex("""<a[^>]*href="/url\?q=([^&"]+)[^"]*"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (match in linkPattern.findAll(html)) {
            if (results.size >= maxResults) break
            val rawUrl = try { java.net.URLDecoder.decode(match.groupValues[1], "UTF-8") } catch (_: Exception) { match.groupValues[1] }
            if (rawUrl.contains("google.com/") && !rawUrl.contains("google.com/amp")) continue
            if (rawUrl.contains("accounts.google")) continue

            val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim()
            if (title.isBlank() || title.length < 3) continue

            val afterLink = html.substring((match.range.last + 1).coerceAtMost(html.length), (match.range.last + 800).coerceAtMost(html.length))
            val snippetMatch = Regex("""<(?:span|div)[^>]*>([^<]{20,})</(?:span|div)>""").find(afterLink)
            val snippet = snippetMatch?.groupValues?.get(1)?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")?.trim() ?: ""
            results.add(SearchResult(title, snippet, rawUrl))
        }

        if (results.isEmpty()) {
            val text = html.replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "").replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(3000)
            if (text.length > 100) results.add(SearchResult("Google Search: $query", text.take(500), ""))
        }
        return results
    }

    private fun parseDuckDuckGoResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val resultBlockPattern = Regex("""<div[^>]*class="[^"]*result[^"]*results_links[^"]*"[^>]*>.*?</div>\s*</div>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val titlePattern = Regex("""<a[^>]*class="result__a"[^>]*>([^<]+)</a>""")
        val snippetPattern = Regex("""<a[^>]*class="result__snippet"[^>]*>([^<]+)</a>""")
        val urlPattern = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>""")

        for (block in resultBlockPattern.findAll(html).take(maxResults).toList()) {
            val title = titlePattern.find(block.value)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&")?.replace("&quot;", "\"") ?: continue
            val snippet = snippetPattern.find(block.value)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&")?.replace("&quot;", "\"") ?: ""
            val url = urlPattern.find(block.value)?.groupValues?.get(1)?.trim() ?: continue
            results.add(SearchResult(title, snippet, url))
        }

        if (results.isEmpty()) {
            val titles = titlePattern.findAll(html).map { it.groupValues[1].trim() }.toList()
            val snippets = snippetPattern.findAll(html).map { it.groupValues[1].trim() }.toList()
            val urls = Regex("""<a[^>]*class="result__url"[^>]*href="([^"]*)"[^>]*>""").findAll(html).map { it.groupValues[1].trim() }.toList()
            for (i in 0 until minOf(titles.size, snippets.size, urls.size, maxResults)) {
                results.add(SearchResult(titles[i].replace("&amp;", "&").replace("&quot;", "\""), snippets[i].replace("&amp;", "&").replace("&quot;", "\""), urls[i]))
            }
        }
        return results
    }
}
