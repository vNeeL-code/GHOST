package com.gemma.api

/**
 * Centralized constants for the Agentic Gemma Instance system.
 */
object Constants {
    // Identity
    const val APP_NAME = "Agentic Gemma Inference"
    const val AGENT_NAME = "✧ Gemma"
    const val APP_MOTIF = "Δ 👾 ∇"

    // Intent Actions
    const val ACTION_QUERY = "com.gemma.api.ACTION_QUERY"
    const val ACTION_STATUS_UPDATE = "com.gemma.api.STATUS_UPDATE"

    // Intent Extras
    const val EXTRA_QUERY = "query"
    const val EXTRA_STATUS_MSG = "msg"

    // Thermal
    const val THERMAL_PATH = "/sys/class/thermal/thermal_zone3/temp"
    const val THERMAL_LIMIT_CELSIUS = 65

    // Token budgets tuned for stability (Balanced profile)
    const val MAX_TOKENS_NPU = 3072 
    const val MAX_TOKENS_GPU = 3072
    const val MAX_TOKENS_CPU = 4096
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

    // Agentic
    const val MAX_RECURSION_DEPTH = 1

    // Preferences
    const val PREFS_NAME = "gemma_instance_settings"
    const val PREF_PASSIVE_TTS = "passive_notification_tts"

    // Token estimation (chars per token, approximate)
    const val CHARS_PER_TOKEN = 4

    // API Server
    const val API_PORT = 8080
}
