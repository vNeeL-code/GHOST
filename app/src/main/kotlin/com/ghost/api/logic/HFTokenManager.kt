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
    }

    fun setToken(token: String) {
        prefs.edit().putString(KEY_HF_TOKEN, token).apply()
    }

    fun getToken(): String? {
        return prefs.getString(KEY_HF_TOKEN, null)
    }

    fun hasToken(): Boolean = !getToken().isNullOrBlank()

    fun setOpenRouterKey(key: String) {
        prefs.edit().putString(KEY_OPENROUTER_KEY, key.trim()).apply()
    }

    fun getOpenRouterKey(): String? {
        return prefs.getString(KEY_OPENROUTER_KEY, null)
    }

    fun hasOpenRouterKey(): Boolean = !getOpenRouterKey().isNullOrBlank()
}
