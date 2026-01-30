package com.gemma.api.hardware

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Network Tools - Web search and fetch
 */
class NetworkToolSet(private val context: Context) {

    /**
     * Launch browser search (UI action)
     */
    fun googleSearch(query: String): Map<String, Any> {
        return try {
            Timber.i("Launching Google Search for: $query")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            mapOf("success" to true, "action" to "opened_browser", "query" to query)
        } catch (e: Exception) {
            Timber.e(e, "Search failed")
            mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
        }
    }

    /**
     * Open a generic URL in the browser
     */
    fun openBrowser(url: String) {
        try {
            Timber.i("Opening URL: $url")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open browser")
        }
    }

    /**
     * Fetch search results as text (RAG-style)
     * Uses DuckDuckGo HTML for no-API-key access
     */
    suspend fun fetchSearchResults(query: String, maxResults: Int = 5): String = withContext(Dispatchers.IO) {
        try {
            Timber.i("Fetching search results for: $query")
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://html.duckduckgo.com/html/?q=$encoded")

            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Gemma/1.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                return@withContext "Search failed: HTTP $responseCode"
            }

            val html = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            // Parse results from DuckDuckGo HTML
            val results = parseDuckDuckGoResults(html, maxResults)

            if (results.isEmpty()) {
                "No results found for: $query"
            } else {
                val sb = StringBuilder("SEARCH RESULTS for '$query':\n\n")
                results.forEachIndexed { index, result ->
                    sb.append("${index + 1}. ${result.title}\n")
                    sb.append("   ${result.snippet}\n")
                    sb.append("   URL: ${result.url}\n\n")
                }
                sb.append("---\nSynthesize these results to answer the user's question.")
                sb.toString()
            }
        } catch (e: Exception) {
            Timber.e(e, "Search fetch failed")
            "Search failed: ${e.message}"
        }
    }

    /**
     * Fetch webpage content as text
     */
    suspend fun fetchWebpage(urlString: String, maxChars: Int = 10000): String = withContext(Dispatchers.IO) {
        try {
            Timber.i("Fetching webpage: $urlString")
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Gemma/1.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                return@withContext "Fetch failed: HTTP $responseCode"
            }

            val html = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            // Strip HTML tags for plain text
            val text = html
                .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(maxChars)

            "WEBPAGE CONTENT from $urlString:\n\n$text"
        } catch (e: Exception) {
            Timber.e(e, "Webpage fetch failed")
            "Fetch failed: ${e.message}"
        }
    }

    private data class SearchResult(val title: String, val snippet: String, val url: String)

    private fun parseDuckDuckGoResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // BUGFIX: Parse each result block as a coherent unit to prevent misalignment
        // DuckDuckGo wraps each result in <div class="result results_links results_links_deep web-result">
        val resultBlockPattern = Regex(
            """<div[^>]*class="[^"]*result[^"]*results_links[^"]*"[^>]*>.*?</div>\s*</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        // Patterns to extract from WITHIN each block
        val titlePattern = Regex("""<a[^>]*class="result__a"[^>]*>([^<]+)</a>""")
        val snippetPattern = Regex("""<a[^>]*class="result__snippet"[^>]*>([^<]+)</a>""")
        val urlPattern = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>""")

        // Find all result blocks first
        val blocks = resultBlockPattern.findAll(html).take(maxResults).toList()

        for (block in blocks) {
            val blockHtml = block.value

            // Extract title, snippet, and URL from THIS block only
            val title = titlePattern.find(blockHtml)?.groupValues?.get(1)?.trim()
                ?.replace("&amp;", "&")?.replace("&quot;", "\"")
                ?: continue  // Skip if no title

            val snippet = snippetPattern.find(blockHtml)?.groupValues?.get(1)?.trim()
                ?.replace("&amp;", "&")?.replace("&quot;", "\"")
                ?: ""  // Snippet optional

            val url = urlPattern.find(blockHtml)?.groupValues?.get(1)?.trim()
                ?: continue  // Skip if no URL

            results.add(SearchResult(title, snippet, url))
        }

        // Fallback: If block-based parsing found nothing, try legacy approach
        if (results.isEmpty()) {
            Timber.w("Block parsing failed, trying legacy extraction")
            val titles = titlePattern.findAll(html).map { it.groupValues[1].trim() }.toList()
            val snippets = snippetPattern.findAll(html).map { it.groupValues[1].trim() }.toList()
            val urls = Regex("""<a[^>]*class="result__url"[^>]*href="([^"]*)"[^>]*>""")
                .findAll(html).map { it.groupValues[1].trim() }.toList()

            val count = minOf(titles.size, snippets.size, urls.size, maxResults)
            for (i in 0 until count) {
                results.add(SearchResult(
                    title = titles[i].replace("&amp;", "&").replace("&quot;", "\""),
                    snippet = snippets[i].replace("&amp;", "&").replace("&quot;", "\""),
                    url = urls[i]
                ))
            }
        }

        return results
    }
}
