package com.ghost.api.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import timber.log.Timber

/**
 * OperIT-style floating browser window.
 * Shows the user what the AI is researching.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class BrowserBubbleOverlay(
    context: Context,
    private val windowManager: WindowManager,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val webView: WebView
    private val titleBar: TextView
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    val windowParams: WindowManager.LayoutParams

    init {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * 0.45).toInt()
        val height = (metrics.heightPixels * 0.35).toInt()

        windowParams = WindowManager.LayoutParams(
            width, height, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 120
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            elevation = 8f * metrics.density
        }

        // Title bar (Draggable)
        titleBar = TextView(context).apply {
            text = "Gemma is researching..."
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2D2D2D"))
            setPadding(32, 16, 32, 16)
            textSize = 14f
            
            // Drag logic
            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = windowParams.x
                        initialY = windowParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        windowParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        windowParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(this@BrowserBubbleOverlay, windowParams)
                        true
                    }
                    else -> false
                }
            }
        }

        // Close Button (Manual Overide if needed)
        val closeButton = TextView(context).apply {
            text = "×"
            setTextColor(Color.GRAY)
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                onDismiss()
            }
        }

        val titleLayout = FrameLayout(context).apply {
            addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            })
        }

        rootLayout.addView(titleLayout)

        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    titleBar.text = view?.title ?: "Researching..."
                }
            }
            
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    return true // Hush
                }
            }
        }

        rootLayout.addView(webView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(rootLayout)
    }

    fun loadUrl(url: String) {
        post {
            webView.loadUrl(url)
        }
    }

    /**
     * Executes JS to scrape text and returns it asynchronously via callback.
     */
    fun scrapeContent(callback: (String) -> Unit) {
        post {
            webView.evaluateJavascript(
                "(function() { return document.body.innerText; })();"
            ) { result ->
                // Result comes back wrapped in quotes due to JS stringifying
                val scraped = result?.trim('"')?.replace("\\n", "\n") ?: ""
                callback(scraped)
            }
        }
    }
}
