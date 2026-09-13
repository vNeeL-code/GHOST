package com.ghost.api.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ghost.api.logic.PeerContact
import timber.log.Timber

/**
 * PeerLoginSheet
 *
 * In-app WebView authentication dialog for AI Phonebook contacts ("Holding Cookie").
 * Allows the user to sign into their existing accounts (Claude, DeepSeek, Perplexity, etc.)
 * and securely extracts and holds the session cookies for Gemma's peer consultations.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PeerLoginSheet(
    contact: PeerContact,
    onDismiss: () -> Unit,
    onSessionSaved: (cookies: String) -> Unit
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("Loading...") }
    var currentUrl by remember { mutableStateOf(contact.loginUrl) }
    var hasDetectedSession by remember { mutableStateOf(false) }

    val accentColor = Color(0xFF8BB4F6)
    val successColor = Color(0xFF4CAF50)
    val surfaceBg = Color(0xFF141418)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6000000))
                .padding(top = 28.dp, bottom = 16.dp, start = 12.dp, end = 12.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp)),
                color = surfaceBg
            ) {
                Column(modifier = Modifier.fillMaxSize()) {

                    // Header Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A22))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color(0x1AFFFFFF), CircleShape)
                            ) {
                                Text(text = "✕", color = Color.White, fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "${contact.callsign} Login",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (hasDetectedSession) "● Session Detected!" else contact.organization,
                                    color = if (hasDetectedSession) successColor else Color(0x99FFFFFF),
                                    fontSize = 11.sp,
                                    fontWeight = if (hasDetectedSession) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }

                        // "Hold Cookie / Save Session" Action Button
                        Button(
                            onClick = {
                                val cm = CookieManager.getInstance()
                                cm.flush()
                                val domainUrl = if (contact.cookieDomain.startsWith("http")) contact.cookieDomain else "https://${contact.cookieDomain}"
                                val cookieCandidates = listOfNotNull(
                                    cm.getCookie(domainUrl),
                                    cm.getCookie(contact.loginUrl),
                                    cm.getCookie(currentUrl)
                                )
                                // Merge distinct cookie key-value pairs
                                val cookieMap = mutableMapOf<String, String>()
                                cookieCandidates.forEach { cookieStr ->
                                    cookieStr.split(";").forEach { part ->
                                        val kv = part.trim().split("=", limit = 2)
                                        if (kv.size == 2 && kv[0].isNotBlank()) {
                                            cookieMap[kv[0].trim()] = kv[1].trim()
                                        }
                                    }
                                }
                                val webCookies = cookieMap.map { "${it.key}=${it.value}" }.joinToString("; ")

                                webViewRef?.evaluateJavascript(
                                    """
                                    (function() {
                                        try {
                                            var items = [];
                                            for (var i = 0; i < localStorage.length; i++) {
                                                var k = localStorage.key(i);
                                                var v = localStorage.getItem(k);
                                                if (v && (k.toLowerCase().includes('token') || k.toLowerCase().includes('auth') || k.toLowerCase().includes('user') || k.toLowerCase().includes('session') || k.toLowerCase().includes('key'))) {
                                                    items.push(k + '=' + encodeURIComponent(v));
                                                }
                                            }
                                            return items.join('; ');
                                        } catch (e) {
                                            return '';
                                        }
                                    })()
                                    """.trimIndent()
                                ) { storageResult ->
                                    val storageStr = storageResult?.trim('"', ' ', '\\') ?: ""
                                    val unescapedStorage = storageStr
                                        .replace("\\\"", "\"")
                                        .replace("\\\\", "\\")
                                    val combinedSession = if (unescapedStorage.isNotBlank()) {
                                        if (webCookies.isNotBlank()) "$webCookies; $unescapedStorage" else unescapedStorage
                                    } else {
                                        webCookies
                                    }

                                    if (combinedSession.isNotBlank()) {
                                        onSessionSaved(combinedSession)
                                        Toast.makeText(
                                            context,
                                            "${contact.callsign} connected! (Holding Session)",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onDismiss()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "No session cookies found. Please log in first.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasDetectedSession) successColor else accentColor
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (hasDetectedSession) "✓ Save Session" else "Hold Cookie",
                                color = Color.Black,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Progress / Info Banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (hasDetectedSession) Color(0x334CAF50) else Color(0x1A8BB4F6))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (hasDetectedSession) {
                                "✨ Active session detected! Tap '✓ Save Session' above to connect ${contact.callsign}."
                            } else {
                                "Sign into your account below. Once you reach your chat screen, tap 'Save Session' above."
                            },
                            fontSize = 11.sp,
                            color = if (hasDetectedSession) successColor else Color(0xFFD0E0FF)
                        )
                    }

                    // In-App Browser (AndroidView hosting WebView)
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewRef = this
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    setSupportZoom(true)
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    userAgentString =
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                                }

                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)

                                webChromeClient = object : WebChromeClient() {
                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        if (!title.isNullOrBlank()) pageTitle = title
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        if (url != null) currentUrl = url
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        if (url != null) {
                                            currentUrl = url
                                            val domainUrl = if (contact.cookieDomain.startsWith("http")) contact.cookieDomain else "https://${contact.cookieDomain}"
                                            val cookies = cm.getCookie(domainUrl) ?: cm.getCookie(url) ?: ""
                                            view?.evaluateJavascript(
                                                """
                                                (function() {
                                                    try {
                                                        for (var i = 0; i < localStorage.length; i++) {
                                                            var k = localStorage.key(i).toLowerCase();
                                                            var v = localStorage.getItem(k);
                                                            if (v && v.length > 20 && (k.includes('usertoken') || k.includes('auth_token') || k.includes('session_key') || k.includes('account'))) {
                                                                return true;
                                                            }
                                                        }
                                                        return false;
                                                    } catch (e) { return false; }
                                                })()
                                                """.trimIndent()
                                            ) { hasStorageAuth ->
                                                val storageAuthed = hasStorageAuth?.trim() == "true"
                                                val isLoginPath = url.contains("/login") || url.contains("/sign_in") ||
                                                        url.contains("/auth") || url.contains("/signup") || url.contains("/tos")
                                                val hasChatPath = url.contains("/chat") || url.contains("/c/") ||
                                                        url.contains("/new") || url.contains("/conversation")

                                                val isAuthed = (storageAuthed ||
                                                        cookies.contains("sessionKey") ||
                                                        cookies.contains("lastActiveOrg") ||
                                                        cookies.contains("userToken") ||
                                                        cookies.contains("__Secure-next-auth.session-token") ||
                                                        cookies.contains("auth_token=") ||
                                                        hasChatPath) && !isLoginPath

                                                if (isAuthed) {
                                                    hasDetectedSession = true
                                                }
                                            }
                                        }
                                    }
                                }

                                loadUrl(contact.loginUrl)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }
}
