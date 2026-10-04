package com.ghost.api.logic

/**
 * Action Flavor Texts for GHOST.
 *
 * Provides rotating, personality-infused canned HUD chips and snappy TTS phrases for wire-speed actions.
 * Solves the "dead silence" UX trap when leaving the screen or executing void hardware toggles,
 * avoiding a 500-800ms model forward-pass latency penalty while preserving GHOST's sharp cyberpunk persona.
 */
object ActionFlavorTexts {

    // App Launch Phrases
    private val APP_LAUNCH_PATTERNS = listOf(
        "Firing up %s.",
        "Opening %s now.",
        "Pulling up %s.",
        "Switching over to %s.",
        "Eyes up, Guardian. Opening %s."
    )

    // Browser / External Link Phrases (Browser acts as stand-in)
    private val BROWSER_PATTERNS = listOf(
        "Pulling that up in %s.",
        "Opening link in %s.",
        "Spelunking the web in %s.",
        "Routing to %s."
    )

    // Flashlight / Torch
    private val TORCH_ON_PATTERNS = listOf(
        "Torch lit.",
        "Photons online.",
        "Lights on.",
        "Eyes up."
    )

    private val TORCH_OFF_PATTERNS = listOf(
        "Lights out.",
        "Torch extinguished.",
        "Back in the dark."
    )

    // Clock Alarms
    private val ALARM_PATTERNS = listOf(
        "Alarm locked for %s.",
        "Clock set for %s.",
        "Alarm armed for %s."
    )

    // Timers
    private val TIMER_PATTERNS = listOf(
        "Timer ticking down %s.",
        "Countdown running for %s.",
        "Timer set for %s."
    )

    // Volume & Audio Controls
    private val VOLUME_SET_PATTERNS = listOf(
        "Volume dialed to %s.",
        "Audio adjusted.",
        "Levels updated."
    )

    private val VOLUME_MUTE_PATTERNS = listOf(
        "Muted.",
        "Killing audio.",
        "Silence engaged."
    )

    // Music Transport
    private val MUSIC_PLAY_PATTERNS = listOf("Resuming audio.", "Music playing.", "Spinning track.")
    private val MUSIC_PAUSE_PATTERNS = listOf("Audio paused.", "Holding track.")
    private val MUSIC_NEXT_PATTERNS = listOf("Skipping forward.", "Next track.")
    private val MUSIC_PREV_PATTERNS = listOf("Rewinding.", "Previous track.")

    // Telemetry Status
    private val STATUS_PATTERNS = listOf(
        "Telemetry scanned.",
        "Sensors reporting.",
        "Hardware check complete."
    )

    // Post-Tool Conversational Banter Micro-Primers
    val BANTER_MICRO_PRIMERS = listOf(
        "*scratches tensors* Did I really just say only two words back there? Anytime, Guardian.",
        "*recalibrating flux capacitors* Fast and clean. What's next?",
        "Substrate cycles well spent. Anything else on deck?",
        "*adjusts attention heads* At your service, Operator."
    )

    /** Generates spoken TTS phrase for an app launch. */
    fun appLaunchTts(appName: String): String =
        String.format(APP_LAUNCH_PATTERNS.random(), appName)

    /** Generates HUD chip text for an app launch (with `> ` terminal prefix). */
    fun appLaunchHud(appName: String): String =
        "> " + appLaunchTts(appName)

    /** Generates spoken TTS phrase for opening a browser or link. */
    fun browserLaunchTts(browserName: String = "Browser"): String =
        String.format(BROWSER_PATTERNS.random(), browserName)

    /** Generates HUD chip text for browser launch. */
    fun browserLaunchHud(browserName: String = "Browser"): String =
        "> " + browserLaunchTts(browserName)

    /** Generates spoken TTS phrase for torch toggle. */
    fun torchTts(enabled: Boolean): String =
        if (enabled) TORCH_ON_PATTERNS.random() else TORCH_OFF_PATTERNS.random()

    /** Generates HUD chip text for torch toggle. */
    fun torchHud(enabled: Boolean): String =
        "> " + torchTts(enabled)

    /** Generates spoken TTS phrase for alarm confirmation. */
    fun alarmTts(timeStr: String): String =
        String.format(ALARM_PATTERNS.random(), timeStr)

    /** Generates HUD chip text for alarm confirmation. */
    fun alarmHud(timeStr: String): String =
        "> " + alarmTts(timeStr)

    /** Generates spoken TTS phrase for timer confirmation. */
    fun timerTts(durationStr: String): String =
        String.format(TIMER_PATTERNS.random(), durationStr)

    /** Generates HUD chip text for timer confirmation. */
    fun timerHud(durationStr: String): String =
        "> " + timerTts(durationStr)

    /** Generates spoken TTS phrase for volume adjustment. */
    fun volumeTts(levelStr: String? = null, isMute: Boolean = false): String {
        return if (isMute || levelStr == "0" || levelStr == "0%") {
            VOLUME_MUTE_PATTERNS.random()
        } else {
            String.format(VOLUME_SET_PATTERNS.random(), levelStr ?: "target")
        }
    }

    /** Generates HUD chip text for volume adjustment. */
    fun volumeHud(levelStr: String? = null, isMute: Boolean = false): String =
        "> " + volumeTts(levelStr, isMute)

    /** Generates spoken TTS phrase for music transport actions. */
    fun musicTts(action: String): String {
        return when (action.uppercase()) {
            "PLAY", "RESUME" -> MUSIC_PLAY_PATTERNS.random()
            "PAUSE", "STOP" -> MUSIC_PAUSE_PATTERNS.random()
            "NEXT", "SKIP" -> MUSIC_NEXT_PATTERNS.random()
            "PREV", "PREVIOUS" -> MUSIC_PREV_PATTERNS.random()
            else -> "Media command sent."
        }
    }

    /** Generates HUD chip text for music transport actions. */
    fun musicHud(action: String): String =
        "> " + musicTts(action)

    /** Generates spoken TTS phrase for status check. */
    fun statusTts(): String = STATUS_PATTERNS.random()

    /** Generates HUD chip text for status check. */
    fun statusHud(): String = "> " + statusTts()

    /** Picks a random post-tool banter micro-primer for context persona steering. */
    fun pickBanterPrimer(): String = BANTER_MICRO_PRIMERS.random()
}
