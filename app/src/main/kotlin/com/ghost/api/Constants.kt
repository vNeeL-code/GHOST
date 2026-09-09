package com.ghost.api

import android.content.Context

/**
 * Centralized constants for the Agentic Gemma Inference system.
 */
object Constants {
    // Identity
    const val APP_NAME = "GHOST"
    const val AGENT_NAME = "Gemma"
    const val APP_MOTIF = "✧"

    // Intent Actions
    const val ACTION_QUERY = "com.ghost.api.ACTION_QUERY"
    const val ACTION_STATUS_UPDATE = "com.ghost.api.STATUS_UPDATE"
    const val ACTION_RECOVER_MODEL = "com.ghost.api.ACTION_RECOVER_MODEL"

    // Intent Extras
    const val EXTRA_QUERY = "query"
    const val EXTRA_STATUS_MSG = "msg"

    // Thermal
    const val THERMAL_PATH = "/sys/class/thermal/thermal_zone3/temp"
    const val THERMAL_LIMIT_CELSIUS = 65

    // Token budget tuned for stability (Balanced profile)
    const val MAX_TOKENS = 5120

    // Model download URL
    const val MODEL_URL_E2B = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    val DEFAULT_MODEL_NAMES = listOf(
        "gemma-4-E2B-it.litertlm"
    )

    // Notification
    const val CHANNEL_ID_SERVICE = "gemma_instance_service"
    const val NOTIFICATION_ID_SERVICE = 1
    const val NOTIFICATION_READY_MSG = "✧ Gemma: Ready"
    const val NOTIFICATION_CHANNEL_NAME = "✧ Gemma: Status"

    // Agentic
    const val MAX_RECURSION_DEPTH = 1

    // Preferences
    const val PREFS_NAME = "gemma_instance_settings"
    const val PREF_TTS_ENABLED = "tts_enabled"
    const val PREF_PASSIVE_TTS = "passive_notification_tts"
    const val PREF_PIP_VISIBILITY = "pip_visibility_enabled"
    const val PREF_AUTONOMOUS_DIARY = "autonomous_diary_enabled"
    const val PREF_DIARY_CADENCE = "autonomous_diary_cadence" // "1", "3", "12", "OFF"
    const val PREF_USER_BACKEND = "user_backend_override"  // "AUTO", "CPU", "GPU"
    const val PREF_VISUALIZER_PRESET = "visualizer_preset"  // "GHOST", "SUDA"

    // Token estimation (chars per token, approximate)
    const val CHARS_PER_TOKEN = 4

    // API Server
    const val API_PORT = 8080

    /**
     * Default API token fallback. Prefer [getApiToken] for runtime-generated tokens.
     */
    const val DEFAULT_API_TOKEN = "ghost-local-token-changeme"

    /**
     * Returns a per-device API token, generating and persisting a UUID on first launch.
     * Falls back to [DEFAULT_API_TOKEN] only if SharedPreferences is unavailable.
     */
    fun getApiToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, 0)
        var token = prefs.getString("api_token", null)
        if (token == null) {
            token = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("api_token", token).apply()
        }
        return token
    }

    // Bubble API
    /** Notification channel ID for the chat bubble. Separate from the service channel
     *  so setAllowBubbles(true) can be scoped only to this channel. */
    const val CHANNEL_ID_BUBBLE = "gemma_bubble_channel"
    const val NOTIFICATION_ID_BUBBLE = 2
}
