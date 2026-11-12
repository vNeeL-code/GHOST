package com.ghost.api.logic

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Headless WebView executor that enables JS skills (games, dynamic scripts) to run
 * and return data to the LLM backend without needing to be visible on screen immediately.
 *
 * Security hardening (SEC-1):
 * - Disables allowFileAccess, allowContentAccess, allowFileAccessFromFileURLs, and allowUniversalAccessFromFileURLs.
 * - Serves skill assets over https://appassets.androidplatform.net/ via WebViewAssetLoader
 *   instead of raw file:// URLs, preventing cross-file sandbox reads.
 * - Does not pass raw secrets into page context.
 */
object JsExecutionBridge {

    const val ASSET_HOST = "appassets.androidplatform.net"
    private const val ASSET_ORIGIN = "https://$ASSET_HOST"

    fun createAssetLoader(context: Context): WebViewAssetLoader {
        val appCtx = context.applicationContext
        val publicSkillsDir = try {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            File(docs, "GHOST/skills").apply { if (!exists()) mkdirs() }
        } catch (_: Exception) {
            File(appCtx.getExternalFilesDir(null) ?: appCtx.filesDir, "GHOST/skills").apply { if (!exists()) mkdirs() }
        }

        val skillsPathHandler = WebViewAssetLoader.PathHandler { path ->
            try {
                val target = File(publicSkillsDir, path)
                val baseCanonical = publicSkillsDir.canonicalPath
                val targetCanonical = target.canonicalPath
                if (!targetCanonical.startsWith(baseCanonical) || !target.exists() || !target.isFile) {
                    return@PathHandler null
                }
                val mimeType = when {
                    path.endsWith(".html", ignoreCase = true) -> "text/html"
                    path.endsWith(".js", ignoreCase = true) -> "application/javascript"
                    path.endsWith(".css", ignoreCase = true) -> "text/css"
                    path.endsWith(".json", ignoreCase = true) -> "application/json"
                    path.endsWith(".png", ignoreCase = true) -> "image/png"
                    path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                    path.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
                    else -> "application/octet-stream"
                }
                WebResourceResponse(mimeType, "UTF-8", target.inputStream())
            } catch (e: Exception) {
                Timber.w(e, "JsExecutionBridge: skillsPathHandler failed for $path")
                null
            }
        }

        return WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appCtx))
            .addPathHandler("/skills/", skillsPathHandler)
            .addPathHandler("/cache/", WebViewAssetLoader.InternalStoragePathHandler(appCtx, appCtx.cacheDir))
            .build()
    }

    /**
     * Maps legacy `file:///android_asset/...` or local skill/cache `file://` URLs
     * to safe `https://appassets.androidplatform.net/...` URLs served by [WebViewAssetLoader].
     */
    fun toSafeAssetUrl(context: Context, rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("https://") || trimmed.startsWith("http://") || trimmed.startsWith("data:")) {
            return trimmed
        }
        if (trimmed.startsWith("file:///android_asset/")) {
            val rel = trimmed.removePrefix("file:///android_asset/").trimStart('/')
            return "$ASSET_ORIGIN/assets/$rel"
        }
        val cleanPath = trimmed.removePrefix("file://")
        val cachePath = context.applicationContext.cacheDir.absolutePath.replace("\\", "/")
        val normalizedClean = cleanPath.replace("\\", "/")

        if (normalizedClean.startsWith(cachePath)) {
            val rel = normalizedClean.removePrefix(cachePath).trimStart('/')
            return "$ASSET_ORIGIN/cache/$rel"
        }

        val marker = "/GHOST/skills/"
        val idx = normalizedClean.indexOf(marker, ignoreCase = true)
        if (idx >= 0) {
            val rel = normalizedClean.substring(idx + marker.length).trimStart('/')
            return "$ASSET_ORIGIN/skills/$rel"
        }

        return trimmed
    }

    /**
     * Executes the given JS skill script and waits for the `ai_edge_gallery_get_result` callback.
     * @param context Application context
     * @param url The asset-loader or remote URL of the skill's index.html
     * @param data JSON string containing parameters from the model
     * @return The JSON string returned by the script, or an error payload
     */
    suspend fun executeJs(context: Context, url: String, data: String): String {
        val deferred = CompletableDeferred<String>()
        val safeUrl = toSafeAssetUrl(context, url)

        if (safeUrl.startsWith("file://", ignoreCase = true)) {
            Timber.w("JsExecutionBridge: Blocked direct file:// URL outside scoped skill roots: $safeUrl")
            return "{\"error\": \"Direct file:// execution is disabled by security policy.\"}"
        }

        Handler(Looper.getMainLooper()).post {
            try {
                val assetLoader = createAssetLoader(context)
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                }

                // Inject our callback interface
                webView.addJavascriptInterface(object : Any() {
                    @JavascriptInterface
                    fun onResultReady(result: String) {
                        Timber.i("JsExecutionBridge: Received result")
                        deferred.complete(result)
                        // Destroy WebView on success to prevent leak
                        Handler(Looper.getMainLooper()).post { webView.destroy() }
                    }
                }, "AiEdgeGallery")

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(request.url)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldInterceptRequest(
                        view: WebView,
                        url: String
                    ): WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(Uri.parse(url))
                    }

                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        Timber.i("JsExecutionBridge: Page loaded $loadedUrl")
                        val safeData = JSONObject.quote(data.trim().ifEmpty { "{}" })

                        val script = """
                            (async function() {
                                try {
                                    var startTs = Date.now();
                                    while(true) {
                                        if (typeof window.ai_edge_gallery_get_result === 'function') {
                                            break;
                                        }
                                        await new Promise(resolve => setTimeout(resolve, 100));
                                        if (Date.now() - startTs > 10000) {
                                            throw new Error("Timeout waiting for ai_edge_gallery_get_result to be defined");
                                        }
                                    }
                                    var result = await window.ai_edge_gallery_get_result($safeData);
                                    window.AiEdgeGallery.onResultReady(result);
                                } catch(e) {
                                    window.AiEdgeGallery.onResultReady(JSON.stringify({error: e.toString()}));
                                }
                            })();
                        """.trimIndent()

                        view.evaluateJavascript(script, null)
                    }
                }

                Timber.d("JsExecutionBridge: Loading URL $safeUrl")
                webView.loadUrl(safeUrl)

                // Safety timeout
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!deferred.isCompleted) {
                        deferred.complete("{\"error\": \"Skill execution timed out (30s).\"}")
                        webView.destroy()
                    }
                }, 30000)

            } catch (e: Exception) {
                Timber.e(e, "JsExecutionBridge: Execution failed")
                deferred.complete("{\"error\": \"${e.message}\"}")
            }
        }

        return deferred.await()
    }
}
