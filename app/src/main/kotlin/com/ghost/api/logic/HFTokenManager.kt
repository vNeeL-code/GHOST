package com.ghost.api.logic

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages the HuggingFace Access Token for gated model downloads.
 * Persists to SharedPreferences securely (TODO: move to Keystore).
 */
class HFTokenManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ghost_auth_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_HF_TOKEN = "hf_access_token"
        private const val KEY_OPENROUTER_KEY = "openrouter_api_key"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    }

    fun setToken(token: String) {
        prefs.edit().putString(KEY_HF_TOKEN, token).apply()
    }

    fun getToken(): String? {
        return prefs.getString(KEY_HF_TOKEN, null)
    }

    fun hasToken(): Boolean = !getToken().isNullOrBlank()

    fun setGeminiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
    }

    fun getGeminiKey(): String? {
        return prefs.getString(KEY_GEMINI_API_KEY, null)
    }

    fun hasGeminiKey(): Boolean = !getGeminiKey().isNullOrBlank()

    fun setOpenRouterKey(key: String) {
        prefs.edit().putString(KEY_OPENROUTER_KEY, key.trim()).apply()
    }

    fun getOpenRouterKey(): String? {
        return prefs.getString(KEY_OPENROUTER_KEY, null)
    }

    fun hasOpenRouterKey(): Boolean = !getOpenRouterKey().isNullOrBlank()
}

/**
 * Manages configuration and feature preferences for AI Phonebook peers,
 * including Google Search Grounding for Gemini.
 */
class WebSessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "ghost_peer_sessions"
        private const val KEY_GEMINI_SEARCH_GROUNDING = "gemini_search_grounding_enabled"

        @Volatile
        private var instance: WebSessionManager? = null

        fun getInstance(context: Context): WebSessionManager {
            return instance ?: synchronized(this) {
                instance ?: WebSessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun isGeminiSearchGroundingEnabled(): Boolean {
        return prefs.getBoolean(KEY_GEMINI_SEARCH_GROUNDING, true)
    }

    fun setGeminiSearchGroundingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GEMINI_SEARCH_GROUNDING, enabled).apply()
    }
}

