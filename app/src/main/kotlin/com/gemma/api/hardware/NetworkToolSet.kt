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
        val url = "https://www.google.com/search?q=${Uri.encode(query)}"
        
        // 1. Try launching directly if we have Overlay Permission (Background Launch allowed)
        if (android.provider.Settings.canDrawOverlays(context)) {
            try {
                Timber.i("Launching Google Search (Direct): $query")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return mapOf("success" to true, "action" to "opened_browser_direct", "query" to query)
            } catch (e: Exception) {
                Timber.w(e, "Direct launch failed, trying notification fallback")
            }
        } else {
            Timber.w("No Overlay Permission - using Notification Fallback")
        }

        // 2. Fallback: Send a high-priority notification with PendingIntent
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 
                query.hashCode(), 
                intent, 
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
            val channelId = "gemma_actions"
            
            // Ensure channel exists
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (notificationManager.getNotificationChannel(channelId) == null) {
                    val channel = android.app.NotificationChannel(
                        channelId,
                        "Gemma Actions",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                    notificationManager.createNotificationChannel(channel)
                }
            }

            val builder = android.app.Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Search Ready: $query")
                .setContentText("Tap to open results in browser")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOngoing(false) // Dismissable
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Foreground service logic might require this if we were strictly binding, but simple notification is fine
            }

            notificationManager.notify(url.hashCode(), builder.build())
            
            return mapOf("success" to true, "action" to "posted_notification", "query" to query, "note" to "User must tap notification (missing permissions)")
        } catch (e: Exception) {
            Timber.e(e, "Notification fallback failed")
            return mapOf("success" to false, "error" to (e.message ?: "Unknown error"))
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
     * Uses Google search with fallback to DuckDuckGo
     */
    suspend fun fetchSearchResults(query: String, maxResults: Int = 5): String = withContext(Dispatchers.IO) {
        // Try Google first, fall back to DuckDuckGo
        val googleResult = try { fetchGoogleResults(query, maxResults) } catch (_: Exception) { null }
        if (googleResult != null) return@withContext googleResult

        val ddgResult = try { fetchDuckDuckGoResults(query, maxResults) } catch (_: Exception) { null }
        if (ddgResult != null) return@withContext ddgResult

        "Search failed for '$query' — both Google and DuckDuckGo unavailable."
    }

    /**
     * Scrape Google search results (no API key needed)
     */
    private fun fetchGoogleResults(query: String, maxResults: Int): String? {
        Timber.i("Fetching Google results for: $query")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.google.com/search?q=$encoded&num=$maxResults&hl=en")

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        // Mobile UA gets simpler HTML from Google
        connection.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.instanceFollowRedirects = true

        val responseCode = connection.responseCode
        if (responseCode != 200) {
            Timber.w("Google search HTTP $responseCode")
            return null
        }

        val html = connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        // Parse Google results — extract text from result divs
        val results = parseGoogleResults(html, maxResults, query)
        if (results.isEmpty()) return null

        val sb = StringBuilder("GOOGLE RESULTS for '$query':\n\n")
        results.forEachIndexed { index, result ->
            sb.append("${index + 1}. ${result.title}\n")
            if (result.snippet.isNotBlank()) sb.append("   ${result.snippet}\n")
            if (result.url.isNotBlank()) sb.append("   ${result.url}\n")
            sb.append("\n")
        }
        sb.append("---\nSynthesize these results to answer the user's question.")
        return sb.toString()
    }

    /**
     * Fallback: DuckDuckGo HTML scraping
     */
    private fun fetchDuckDuckGoResults(query: String, maxResults: Int): String? {
        Timber.i("Fetching DuckDuckGo results for: $query")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://html.duckduckgo.com/html/?q=$encoded")

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Gemma/1.0")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        val responseCode = connection.responseCode
        if (responseCode != 200) return null

        val html = connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        val results = parseDuckDuckGoResults(html, maxResults)
        if (results.isEmpty()) return null

        val sb = StringBuilder("SEARCH RESULTS for '$query':\n\n")
        results.forEachIndexed { index, result ->
            sb.append("${index + 1}. ${result.title}\n")
            sb.append("   ${result.snippet}\n")
            sb.append("   URL: ${result.url}\n\n")
        }
        sb.append("---\nSynthesize these results to answer the user's question.")
        return sb.toString()
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

    /**
     * Parse Google mobile search HTML.
     * Google's mobile HTML is simpler — results are in divs with data-hveid.
     * We extract text content from each result block.
     */
    private fun parseGoogleResults(html: String, maxResults: Int, query: String = ""): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // Strategy: Find <a> tags with /url?q= (Google's redirect links) as anchors for result blocks
        // Then extract nearby text as title/snippet
        val linkPattern = Regex("""<a[^>]*href="/url\?q=([^&"]+)[^"]*"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        for (match in linkPattern.findAll(html)) {
            if (results.size >= maxResults) break

            val rawUrl = try {
                java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
            } catch (_: Exception) { match.groupValues[1] }

            // Skip Google's own links (accounts, support, etc.)
            if (rawUrl.contains("google.com/") && !rawUrl.contains("google.com/amp")) continue
            if (rawUrl.contains("accounts.google")) continue

            // Extract title from link text (strip HTML tags)
            val title = match.groupValues[2]
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .trim()

            if (title.isBlank() || title.length < 3) continue

            // Look for snippet text after this link — grab next text block
            val afterLink = html.substring(
                (match.range.last + 1).coerceAtMost(html.length),
                (match.range.last + 800).coerceAtMost(html.length)
            )
            // Find first substantial text block (not in a tag)
            val snippetMatch = Regex("""<(?:span|div)[^>]*>([^<]{20,})</(?:span|div)>""")
                .find(afterLink)
            val snippet = snippetMatch?.groupValues?.get(1)
                ?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")
                ?.trim() ?: ""

            results.add(SearchResult(title, snippet, rawUrl))
        }

        // Fallback: brute-force text extraction if structured parsing got nothing
        if (results.isEmpty()) {
            Timber.w("Google structured parsing failed, trying text extraction")
            val text = html
                .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
                .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(3000)

            if (text.length > 100) {
                results.add(SearchResult("Google Search: $query", text.take(500), ""))
            }
        }

        return results
    }

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
