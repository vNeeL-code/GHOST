package com.gemma.api

/**
 * Centralized constants for the Gemma API system
 */
object Constants {
    // Intent Actions
    const val ACTION_QUERY = "com.gemma.api.ACTION_QUERY"
    const val ACTION_STATUS_UPDATE = "com.gemma.api.STATUS_UPDATE"

    // Intent Extras
    const val EXTRA_QUERY = "query"
    const val EXTRA_STATUS_MSG = "msg"

    // API
    const val API_PORT = 9000

    // Thermal
    const val THERMAL_PATH = "/sys/class/thermal/thermal_zone3/temp"
    const val THERMAL_LIMIT_CELSIUS = 65

    // Model
    const val MAX_TOKENS = 32768
    
    // Backend selection: "CPU", "GPU", or "NPU"
    // GPU = Stable (with Manifest fix)
    // NPU = Ideal (Fast/Cool), falls back to GPU if fails
    // CPU = Safety net
    const val PREFERRED_BACKEND = "NPU"

    val DEFAULT_MODEL_NAMES = listOf(
        "gemma-3n-E4B-it-int4.litertlm",
        "gemma.litertlm"
    )

    // Notification
    const val CHANNEL_ID_SERVICE = "gemma_service"
    const val NOTIFICATION_ID_SERVICE = 1

    // Agentic
    const val MAX_RECURSION_DEPTH = 1

    // Token estimation (chars per token, approximate)
    const val CHARS_PER_TOKEN = 4
}
