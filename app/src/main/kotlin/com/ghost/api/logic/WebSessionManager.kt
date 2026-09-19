package com.ghost.api.logic

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber

/**
 * WebSessionManager
 *
 * Manages configuration and feature preferences for AI Phonebook peers,
 * including Google Search Grounding for Gemini.
 */
class WebSessionManager(private val context: Context) {

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

