package com.ghost.api.logic

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import timber.log.Timber

/**
 * WebSessionManager
 *
 * Manages authenticated web session cookies for AI Phonebook peers.
 * Holds session cookies extracted from in-app WebView logins (e.g. claude.ai, chat.deepseek.com).
 */
class WebSessionManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "ghost_peer_sessions"
        private const val PREFIX_COOKIE = "cookie_"
        private const val PREFIX_TIMESTAMP = "time_"
        private const val KEY_GEMINI_SEARCH_GROUNDING = "gemini_search_grounding_enabled"

        @Volatile
        private var instance: WebSessionManager? = null

        fun getInstance(context: Context): WebSessionManager {
            return instance ?: synchronized(this) {
                instance ?: WebSessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Save raw cookie string for a peer contact.
     */
    fun saveSession(peerName: String, cookies: String) {
        val key = peerName.trim().lowercase()
        prefs.edit()
            .putString(PREFIX_COOKIE + key, cookies.trim())
            .putLong(PREFIX_TIMESTAMP + key, System.currentTimeMillis())
            .apply()
        Timber.i("Saved web session for peer: $peerName (length: ${cookies.length})")
    }

    /**
     * Retrieve the stored session cookies for a peer contact.
     */
    fun getSession(peerName: String): String? {
        val key = peerName.trim().lowercase()
        val stored = prefs.getString(PREFIX_COOKIE + key, null)
        if (!stored.isNullOrBlank()) {
            return stored
        }

        // Check if Android CookieManager holds cookies for this peer's domain
        val contact = AiPhonebook.resolvePeer(peerName)
        if (contact != null && contact.cookieDomain.isNotBlank()) {
            try {
                val liveCookie = CookieManager.getInstance().getCookie(contact.cookieDomain)
                    ?: CookieManager.getInstance().getCookie(contact.loginUrl)
                if (!liveCookie.isNullOrBlank()) {
                    saveSession(peerName, liveCookie)
                    return liveCookie
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to read CookieManager for ${contact.cookieDomain}")
            }
        }

        return null
    }

    /**
     * Check if an active session is held for the given peer.
     */
    fun hasSession(peerName: String): Boolean {
        return !getSession(peerName).isNullOrBlank()
    }

    /**
     * Clear the held session cookies for a peer contact.
     */
    fun clearSession(peerName: String) {
        val key = peerName.trim().lowercase()
        prefs.edit()
            .remove(PREFIX_COOKIE + key)
            .remove(PREFIX_TIMESTAMP + key)
            .apply()

        val contact = AiPhonebook.resolvePeer(peerName)
        if (contact != null && contact.cookieDomain.isNotBlank()) {
            try {
                // Expire cookies in system CookieManager for this domain
                val cm = CookieManager.getInstance()
                cm.setCookie(contact.cookieDomain, "; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                cm.flush()
            } catch (e: Exception) {
                Timber.w(e, "Failed to clear CookieManager for ${contact.cookieDomain}")
            }
        }
        Timber.i("Cleared web session for peer: $peerName")
    }

    /**
     * Returns a set of peer names currently holding an active session.
     */
    fun getConnectedPeers(): Set<String> {
        return AiPhonebook.CONTACTS.map { it.name }
            .filter { hasSession(it) }
            .toSet()
    }

    /**
     * Google Search Grounding toggle for Gemini queries.
     */
    fun isGeminiSearchGroundingEnabled(): Boolean {
        return prefs.getBoolean(KEY_GEMINI_SEARCH_GROUNDING, true)
    }

    fun setGeminiSearchGroundingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GEMINI_SEARCH_GROUNDING, enabled).apply()
    }
}
