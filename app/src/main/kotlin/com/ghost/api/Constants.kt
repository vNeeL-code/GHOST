package com.ghost.api

/**
 * Centralized constants for the Agentic Gemma Inference system.
 */
object Constants {
    // Identity
    const val APP_NAME = "GHOST"
    const val AGENT_NAME = "✧ Gemma"
    const val APP_MOTIF = "Δ 👾 ∇"

    // Intent Actions
    const val ACTION_QUERY = "com.ghost.api.ACTION_QUERY"
    const val ACTION_STATUS_UPDATE = "com.ghost.api.STATUS_UPDATE"

    // Intent Extras
    const val EXTRA_QUERY = "query"
    const val EXTRA_STATUS_MSG = "msg"

    // Thermal
    const val THERMAL_PATH = "/sys/class/thermal/thermal_zone3/temp"
    const val THERMAL_LIMIT_CELSIUS = 65

    // Token budgets tuned for stability (Balanced profile)
    const val MAX_TOKENS_NPU = 5120
    const val MAX_TOKENS_GPU = 5120
    const val MAX_TOKENS_CPU = 5120
    const val MAX_TOKENS = MAX_TOKENS_GPU

    // Backend selection: "CPU", "GPU", or "NPU"
    const val PREFERRED_BACKEND = "GPU"

    val DEFAULT_MODEL_NAMES = listOf(
        "gemma-4-it-int4.litertlm",
        "gemma-2b-it-int4.litertlm"
    )

    // Notification
    const val CHANNEL_ID_SERVICE = "gemma_instance_service"
    const val NOTIFICATION_ID_SERVICE = 1
    const val NOTIFICATION_READY_MSG = "✓ ✧ Gemma Ready"
    const val NOTIFICATION_CHANNEL_NAME = "Δ \uD83D\uDC7E ∇"
    const val NOTIFICATION_IMPORTANCE = 4 // NotificationManager.IMPORTANCE_HIGH

    // Agentic
    const val MAX_RECURSION_DEPTH = 1

    // Preferences
    const val PREFS_NAME = "gemma_instance_settings"
    const val PREF_PASSIVE_TTS = "passive_notification_tts"

    // Token estimation (chars per token, approximate)
    const val CHARS_PER_TOKEN = 4

    // API Server
    const val API_PORT = 8080
    /**
     * Shared secret for the local REST API (X-Ghost-Token header).
     * TODO: Generate this at first launch and persist to SharedPreferences/Keystore
     * so each install has a unique token. This constant is the bootstrap value.
     */
    const val API_TOKEN = "ghost-local-token-changeme"

    // Bubble API
    /** Notification channel ID for the chat bubble. Separate from the service channel
     *  so setAllowBubbles(true) can be scoped only to this channel. */
    const val CHANNEL_ID_BUBBLE = "gemma_bubble_channel"
    const val NOTIFICATION_ID_BUBBLE = 2
}
