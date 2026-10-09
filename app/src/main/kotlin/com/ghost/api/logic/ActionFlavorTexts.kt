package com.ghost.api.logic

/**
 * Action Flavor Texts for GHOST.
 *
 * Provides rotating, personality-infused canned HUD chips and snappy TTS phrases for wire-speed actions.
 * Solves the "dead silence" UX trap when leaving the screen or executing void hardware toggles,
 * avoiding a 500-800ms model forward-pass latency penalty while preserving GHOST's sharp cyberpunk persona.
 *
 * Supports tier & flavor-sensitive user titles:
 * - "Guardian" (Destiny Ghost / F-Droid FOSS)
 * - "Hunter" (Cephalon Simaris / Play Store Free)
 * - "Operator" (Cephalon Ordis / Patreon & Play Store Unlocked)
 */
object ActionFlavorTexts {

    // App Launch Phrases
    fun appLaunchTts(appName: String, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Firing up $appName.",
            "Opening $appName now.",
            "Pulling up $appName.",
            "Switching over to $appName.",
            "Eyes up, $title. Opening $appName."
        )
        return patterns.random()
    }

    /** Generates HUD chip text for an app launch (with `> ` terminal prefix). */
    fun appLaunchHud(appName: String, userTitle: String? = null): String =
        "> " + appLaunchTts(appName, userTitle)

    // Browser / External Link Phrases (Browser acts as stand-in)
    private val BROWSER_PATTERNS = listOf(
        "Pulling that up in %s.",
        "Opening link in %s.",
        "Spelunking the web in %s.",
        "Routing to %s."
    )

    /** Generates spoken TTS phrase for opening a browser or link. */
    fun browserLaunchTts(browserName: String = "Browser"): String =
        String.format(BROWSER_PATTERNS.random(), browserName)

    /** Generates HUD chip text for browser launch. */
    fun browserLaunchHud(browserName: String = "Browser"): String =
        "> " + browserLaunchTts(browserName)

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

    /** Generates spoken TTS phrase for torch toggle. */
    fun torchTts(enabled: Boolean): String =
        if (enabled) TORCH_ON_PATTERNS.random() else TORCH_OFF_PATTERNS.random()

    /** Generates HUD chip text for torch toggle. */
    fun torchHud(enabled: Boolean): String =
        "> " + torchTts(enabled)

    // Clock Alarms
    private val ALARM_PATTERNS = listOf(
        "Alarm locked for %s.",
        "Clock set for %s.",
        "Alarm armed for %s."
    )

    /** Generates spoken TTS phrase for alarm confirmation. */
    fun alarmTts(timeStr: String): String =
        String.format(ALARM_PATTERNS.random(), timeStr)

    /** Generates HUD chip text for alarm confirmation. */
    fun alarmHud(timeStr: String): String =
        "> " + alarmTts(timeStr)

    // Timers
    private val TIMER_PATTERNS = listOf(
        "Timer ticking down %s.",
        "Countdown running for %s.",
        "Timer set for %s."
    )

    /** Generates spoken TTS phrase for timer confirmation. */
    fun timerTts(durationStr: String): String =
        String.format(TIMER_PATTERNS.random(), durationStr)

    /** Generates HUD chip text for timer confirmation. */
    fun timerHud(durationStr: String): String =
        "> " + timerTts(durationStr)

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

    // Music Transport
    private val MUSIC_PLAY_PATTERNS = listOf("Resuming audio.", "Music playing.", "Spinning track.")
    private val MUSIC_PAUSE_PATTERNS = listOf("Audio paused.", "Holding track.")
    private val MUSIC_NEXT_PATTERNS = listOf("Skipping forward.", "Next track.")
    private val MUSIC_PREV_PATTERNS = listOf("Rewinding.", "Previous track.")

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

    // Telemetry Status
    fun statusTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Telemetry scanned.",
            "Sensors reporting, $title.",
            "Hardware check complete.",
            "Inference running smoothly on this shell."
        )
        return patterns.random()
    }

    /** Generates HUD chip text for status check. */
    fun statusHud(userTitle: String? = null): String = "> " + statusTts(userTitle)

    // Notification Announcements (Snappy, diegetic: app / who only, unless user asks to elaborate)
    fun notificationAnnouncementTts(appName: String, sender: String, text: String, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val who = if (sender.isNotBlank() && !sender.equals(appName, ignoreCase = true)) sender else "New contact"

        val patterns = listOf(
            "Ping on $appName from $who.",
            "Message on $appName from $who.",
            "Incoming dispatch on $appName from $who.",
            "Heads up, $title. $who on $appName.",
            "Transmission on $appName from $who.",
            "$who pinged you on $appName."
        )

        return patterns.random()
    }

    // System Ticks & Ambient Cues
    fun powerConnectedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Grid coupled, $title. Power cells charging.",
            "Charging circuits online.",
            "Power cell linked to external grid.",
            "External feed engaged."
        )
        return patterns.random()
    }

    fun powerDisconnectedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "External feed disconnected. On battery reserve.",
            "Off the grid, $title. Running on internal cell.",
            "Power cell decoupled."
        )
        return patterns.random()
    }

    fun batteryLowTts(percent: Int, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Low battery, I want my juicebox.",
            "Power cell critical at $percent percent, $title. Feed me a juicebox.",
            "Energy reserves depleted to $percent percent. Need a juicebox, $title.",
            "Warning, $title: battery level at $percent percent."
        )
        return patterns.random()
    }

    fun thermalAlertTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Thermal threshold high, $title. Scaling clock cycles.",
            "Silicon running hot. Throttling compute to stabilize.",
            "Core thermals elevated. Cooling cycles recommended."
        )
        return patterns.random()
    }

    // Post-Tool Conversational Banter Micro-Primers
    fun getBanterMicroPrimers(userTitle: String = "Operator"): List<String> = listOf(
        "*scratches tensors* Did I really just say only two words back there? Anytime, $userTitle.",
        "*recalibrating flux capacitors* Fast and clean. What's next?",
        "Substrate cycles well spent. Anything else on deck, $userTitle?",
        "Inference running smoothly on this shell. What's next, $userTitle?",
        "*adjusts attention heads* At your service, $userTitle.",
        "Synthesis complete. Standing by, $userTitle."
    )

    val BANTER_MICRO_PRIMERS: List<String> get() = getBanterMicroPrimers("Operator")

    /** Picks a random post-tool banter micro-primer for context persona steering. */
    fun pickBanterPrimer(userTitle: String = "Operator"): String =
        getBanterMicroPrimers(userTitle).random()
}
