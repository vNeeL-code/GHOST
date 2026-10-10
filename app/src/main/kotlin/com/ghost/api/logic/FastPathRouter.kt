package com.ghost.api.logic

import android.content.Context
import android.content.Intent
import android.net.Uri
import timber.log.Timber
import java.net.URI

enum class InputSource {
    USER_TYPED,
    USER_VOICE,
    OVERLAY,
    API_SERVER,
    NOTIFICATION,
    BACKGROUND_TASK
}

sealed class RoutingResult {
    /**
     * Anchored full match: bypasses LLM completely.
     * Zero model loading, zero tokens, zero GPU thermals.
     */
    data class HandledFastPath(
        val action: String,
        val feedback: String,
        val url: String? = null,
        val execute: ((Context) -> Unit)? = null
    ) : RoutingResult()

    /**
     * Fall-through to LLM (Gemma).
     * If entities or hints were extracted during partial match, [augmentedUserMessage]
     * appends them strictly to the USER MESSAGE TURN (preserving the static system KV cache!).
     */
    data class FallThroughToLlm(
        val augmentedUserMessage: String,
        val extractedEntities: Map<String, String> = emptyMap(),
        val toolHints: List<String> = emptyList()
    ) : RoutingResult()
}

/**
 * 2-Stage Deterministic Fast-Path Router:
 * - Mode A: Full match (anchored) -> Executes directly, bypassing Gemma entirely.
 * - Mode B: Partial match -> Extracts entities & appends tool suggestions to the user message turn.
 * - Security Guard: Only user-initiated inputs (typed, spoken, overlay) can trigger Mode A.
 */
object FastPathRouter {

    private val BARE_URL_REGEX = Regex(
        "^((https?://)?[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(:\\d+)?(/\\S*)?)$",
        RegexOption.IGNORE_CASE
    )

    private val INLINE_URL_REGEX = Regex(
        "(https?://[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(:\\d+)?(/\\S*)?)",
        RegexOption.IGNORE_CASE
    )

    private val VOLUME_PERCENT_REGEX = Regex(
        "^volume\\s+(\\d{1,3})%?$",
        RegexOption.IGNORE_CASE
    )

    private val FLASHLIGHT_REGEX = Regex(
        "^(?:flashlight|torch)\\s+(on|off)$",
        RegexOption.IGNORE_CASE
    )

    fun route(
        input: String,
        source: InputSource,
        isFastPathEnabled: Boolean = true
    ): RoutingResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return RoutingResult.FallThroughToLlm(augmentedUserMessage = input)
        }

        // 1. Settings Kill-Switch Check
        if (!isFastPathEnabled) {
            return RoutingResult.FallThroughToLlm(augmentedUserMessage = input)
        }

        // 2. Security Guard: Only direct user interaction can execute fast-path actions.
        // Notifications, background cycles, and unauthenticated API calls MUST NOT auto-execute.
        val isTrustedUserSource = when (source) {
            InputSource.USER_TYPED,
            InputSource.USER_VOICE,
            InputSource.OVERLAY -> true
            InputSource.API_SERVER,
            InputSource.NOTIFICATION,
            InputSource.BACKGROUND_TASK -> false
        }

        // 3. Mode A (Anchored): Bare URL execution
        if (isTrustedUserSource && BARE_URL_REGEX.matches(trimmed)) {
            val normalizedUrl = if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                "https://$trimmed"
            } else {
                trimmed
            }

            val scheme = extractScheme(normalizedUrl)
            // Strict scheme allowlist: reject intent://, file://, javascript://, content://
            if (scheme == "http" || scheme == "https") {
                Timber.i("⚡ [FastPath] Anchored Bare URL match ($normalizedUrl) from $source. Bypassing LLM.")
                return RoutingResult.HandledFastPath(
                    action = "OPEN_URL",
                    feedback = "Opening link in browser ↗",
                    url = normalizedUrl,
                    execute = { context ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Timber.e(e, "FastPath: Failed to launch browser for $normalizedUrl")
                        }
                    }
                )
            } else {
                Timber.w("⚡ [FastPath] Rejected non-http/https URL scheme: $scheme")
            }
        }

        // 4. Mode A (Anchored): Deterministic hardware actions
        if (isTrustedUserSource) {
            val volMatch = VOLUME_PERCENT_REGEX.find(trimmed)
            if (volMatch != null) {
                val percent = volMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
                if (percent != null) {
                    Timber.i("⚡ [FastPath] Anchored Volume adjustment ($percent%) from $source. Bypassing LLM.")
                    return RoutingResult.HandledFastPath(
                        action = "SET_VOLUME",
                        feedback = "Volume set to $percent%",
                        execute = { context ->
                            try {
                                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                                val max = audioManager?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 15
                                val target = ((percent / 100f) * max).toInt()
                                audioManager?.setStreamVolume(
                                    android.media.AudioManager.STREAM_MUSIC,
                                    target,
                                    android.media.AudioManager.FLAG_SHOW_UI
                                )
                            } catch (e: Exception) {
                                Timber.w(e, "FastPath: Failed to adjust volume")
                            }
                        }
                    )
                }
            }

            val torchMatch = FLASHLIGHT_REGEX.find(trimmed)
            if (torchMatch != null) {
                val state = torchMatch.groupValues[1].lowercase() == "on"
                Timber.i("⚡ [FastPath] Anchored Flashlight toggle ($state) from $source. Bypassing LLM.")
                return RoutingResult.HandledFastPath(
                    action = "TOGGLE_FLASHLIGHT",
                    feedback = if (state) "Flashlight turned on" else "Flashlight turned off",
                    execute = { context ->
                        try {
                            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                            val cameraId = cameraManager?.cameraIdList?.firstOrNull()
                            if (cameraId != null) {
                                cameraManager.setTorchMode(cameraId, state)
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "FastPath: Failed to toggle flashlight")
                        }
                    }
                )
            }
        }

        // 5. Mode B (Partial Match / Unanchored): Entity Extraction & Tool Hinting
        // NEVER auto-launches on partial matches! Injects hint into USER MESSAGE TURN to keep KV cache warm.
        val inlineUrlMatch = INLINE_URL_REGEX.find(trimmed)
        if (inlineUrlMatch != null) {
            val foundUrl = inlineUrlMatch.value
            val scheme = extractScheme(foundUrl)
            if (scheme == "http" || scheme == "https") {
                val hints = listOf("web_fetch", "web_search")
                val hintBlock = "\n\n[Router Context: Detected URL: $foundUrl | Suggested tools: ${hints.joinToString(", ")}]"
                val augmented = trimmed + hintBlock

                Timber.i("⚡ [FastPath] Partial URL match detected ($foundUrl). Injected into user turn to preserve KV cache.")
                return RoutingResult.FallThroughToLlm(
                    augmentedUserMessage = augmented,
                    extractedEntities = mapOf("url" to foundUrl),
                    toolHints = hints
                )
            }
        }

        // Default fall-through: unaugmented input
        return RoutingResult.FallThroughToLlm(augmentedUserMessage = input)
    }

    fun extractScheme(urlStr: String): String? {
        return try {
            val uri = URI(urlStr)
            uri.scheme?.lowercase()
        } catch (e: Exception) {
            null
        }
    }
}
