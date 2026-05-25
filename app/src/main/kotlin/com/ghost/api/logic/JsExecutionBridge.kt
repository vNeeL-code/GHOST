package com.ghost.api.logic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber
import org.json.JSONObject

/**
 * Headless WebView executor that enables JS skills (games, dynamic scripts) to run 
 * and return data to the LLM backend without needing to be visible on screen immediately.
 */
object JsExecutionBridge {

    /**
     * Executes the given JS skill script and waits for the `ai_edge_gallery_get_result` callback.
     * @param context Application context
     * @param url The local or remote URL of the skill's index.html
     * @param data JSON string containing parameters from the model
     * @param secret Optional API key or secret
     * @return The JSON string returned by the script, or an error payload
     */
    suspend fun executeJs(context: Context, url: String, data: String, secret: String = ""): String {
        val deferred = CompletableDeferred<String>()

        Handler(Looper.getMainLooper()).post {
            try {
                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                }

                // Inject our callback interface
                webView.addJavascriptInterface(object : Any() {
                    @JavascriptInterface
                    fun onResultReady(result: String) {
                        Timber.i("JsExecutionBridge: Received result")
                        deferred.complete(result)
                    }
                }, "AiEdgeGallery")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        Timber.i("JsExecutionBridge: Page loaded $loadedUrl")
                        val safeData = JSONObject.quote(data.trim().ifEmpty { "{}" })
                        val safeSecret = JSONObject.quote(secret)
                        
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
                                    var result = await window.ai_edge_gallery_get_result($safeData, $safeSecret);
                                    window.AiEdgeGallery.onResultReady(result);
                                } catch(e) {
                                    window.AiEdgeGallery.onResultReady(JSON.stringify({error: e.toString()}));
                                }
                            })();
                        """.trimIndent()

                        view.evaluateJavascript(script, null)
                    }
                }

                Timber.d("JsExecutionBridge: Loading URL $url")
                webView.loadUrl(url)

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
