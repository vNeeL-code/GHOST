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

    // Token budgets tuned per model architecture & memory footprint
    const val MAX_TOKENS_E4B = 4096   // 12GB+ RAM profile (Game Space pinned, MTP speculative decoding, ample prompt+tools headroom)
    const val MAX_TOKENS_E2B = 5120   // 8GB RAM profile (1.72GB peak, immune to 2GB SPKL, 4.7k free dialogue runway)
    const val MAX_TOKENS = MAX_TOKENS_E4B // Legacy fallback

    // Memory suspension safety valve thresholds (avoids false-positive unloads at 85-89% idle)
    const val RAM_CRITICAL_UTILIZATION_THRESHOLD = 0.95f // 95% RAM utilization
    const val RAM_CRITICAL_MIN_FREE_BYTES = 500L * 1024 * 1024 // 500 MB free memory floor

    /**
     * Resolves the maximum token context window for a given model.
     */
    fun getMaxTokensForModel(modelNameOrKey: String): Int {
        return if (modelNameOrKey.contains("E2B", ignoreCase = true)) {
            MAX_TOKENS_E2B
        } else {
            MAX_TOKENS_E4B
        }
    }

    /**
     * Automatically resolves the appropriate model core based on physical device RAM.
     * Devices with < 10.5GB RAM (e.g. 8GB Galaxy S21) are locked to E2B (Compact).
     * Devices with >= 10.5GB RAM (e.g. 12GB/16GB REDMAGIC) are assigned E4B (Frontier).
     */
    fun resolveHardwareModelTier(context: Context): String {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val totalRamGb = (memInfo.totalMem.toDouble()) / (1024.0 * 1024.0 * 1024.0)
            if (totalRamGb >= 10.5) "E4B" else "E2B"
        } catch (e: Exception) {
            "E2B" // Safe fallback
        }
    }

    // Model download URLs and repos
    const val MODEL_URL_E2B = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    const val MODEL_URL_E4B = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
    const val MODEL_REPO_E2B = "litert-community/gemma-4-E2B-it-litert-lm"
    const val MODEL_REPO_E4B = "litert-community/gemma-4-E4B-it-litert-lm"
    const val MODEL_NAME_E2B = "gemma-4-E2B-it.litertlm"
    const val MODEL_NAME_E4B = "gemma-4-E4B-it.litertlm"

    val DEFAULT_MODEL_NAMES = listOf(
        MODEL_NAME_E4B,
        MODEL_NAME_E2B
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
    const val PREF_AUTONOMOUS_DIARY = "autonomous_diary_enabled"
    const val PREF_DIARY_CADENCE = "autonomous_diary_cadence" // "1", "3", "12", "OFF"
    const val PREF_USER_BACKEND = "user_backend_override"  // "AUTO", "CPU", "GPU"
    const val PREF_SELECTED_MODEL = "selected_model_core"   // "E4B", "E2B"
    const val PREF_VISUALIZER_PRESET = "visualizer_preset"  // "OPTION_A", "OPTION_B", "OPTION_C", "OPTION_D"
    const val PREF_OPERATOR_AVATAR = "operator_avatar_emoji"
    const val PREF_EDGE_LIGHTS_ENABLED = "edge_lights_enabled"
    const val PREF_EDGE_LIGHT_STYLE = "edge_light_style"
    const val EDGE_STYLE_BARS = "STYLE_BARS"
    const val EDGE_STYLE_BOOM = "STYLE_BOOM"
    const val EDGE_STYLE_WIREFRAME = "STYLE_WIREFRAME"
    const val EDGE_STYLE_HEX = "STYLE_HEX"

    // Token estimation (multimodal tuned for Gemma 4 / LiteRT-LM)
    const val CHARS_PER_TOKEN = 4
    const val TOKENS_PER_IMAGE = 576
    const val AUDIO_TOKENS_PER_SECOND = 25
    const val AUDIO_SAMPLE_RATE = 16000
    const val AUDIO_BYTES_PER_SECOND = 32000 // 16kHz * 2 bytes (16-bit PCM mono)
    const val AUDIO_WAV_HEADER_BYTES = 44

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
