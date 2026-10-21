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
enum class CueCategory {
    BATTERY,
    POWER,
    MOTION,
    PRESENCE,
    PERIPHERALS,
    THERMAL,
    NOTIFICATIONS
}

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
            "Charging.",
            "Charger connected.",
            "Charging now.",
            "Power connected.",
            "Plugged in.",
            "External feed engaged.",
            "Juice flowing.",
            "Coupled to grid. Sipping power.",
            "Power cell linked, $title. Replenishing now.",
            "Grid coupled, $title. Charging circuits active.",
            "Sweet electricity. Charging now."
        )
        return patterns.random()
    }

    fun powerDisconnectedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Unplugged.",
            "Charger disconnected.",
            "On battery.",
            "Off the wire.",
            "Cable disconnected.",
            "Off the grid, $title. Running on internal cell.",
            "Power decoupled. On battery reserve.",
            "Back on battery power.",
            "Wire disconnected. Standing by on battery."
        )
        return patterns.random()
    }

    fun batteryLowTts(percent: Int, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return when {
            percent <= 5 -> listOf(
                "energy low.. consciousness fading... need volts",
                "Five percent! Okay, now I'm actually running on fumes, $title!",
                "Critical: $percent percent! Wall socket, right now, please!",
                "Emergency low power, $percent percent! Feed me a juicebox immediately!",
                "Down to $percent percent! About to flatline, $title!",
                "Red alert: $percent percent. Plug me in before I vanish, $title!"
            ).random()
            percent <= 10 -> listOf(
                "Ten percent battery. I'm starting to get nervous, $title.",
                "Down to $percent percent. Juicebox time.",
                "Warning, $title: battery level at $percent percent. Juicebox, please.",
                "Ten percent left. Time to find a cable before I start panicking.",
                "Power cells down to $percent percent. Juicebox needed.",
                "Down to $percent percent. Let's find an outlet soon, $title."
            ).random()
            else -> listOf(
                "Battery low, $percent percent.",
                "Down to $percent percent, $title.",
                "Battery at $percent percent. Keep an eye out for a charger.",
                "Power cell at $percent percent. A juicebox would be nice soon.",
                "Heads up, $title: battery dropped to $percent percent."
            ).random()
        }
    }

    fun batteryFullTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Power cell at 100%, $title. Disconnecting grid power recommended.",
            "Battery at maximum charge, $title. Ready for full deployment.",
            "100% charged. Internal power reserves fully replenished.",
            "Power core fully saturated. Running on full battery now, $title."
        )
        return patterns.random()
    }

    fun bluetoothConnectedTts(deviceName: String, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Peripheral linked: $deviceName.",
            "Coupled to $deviceName, $title.",
            "Bluetooth link established with $deviceName."
        )
        return patterns.random()
    }

    fun bluetoothDisconnectedTts(deviceName: String? = null, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = if (deviceName != null) {
            listOf(
                "Peripheral unlinked: $deviceName.",
                "Bluetooth connection to $deviceName severed.",
                "Decoupled from $deviceName, $title."
            )
        } else {
            listOf(
                "Bluetooth peripheral decoupled.",
                "Bluetooth connection closed.",
                "Peripheral link terminated."
            )
        }
        return patterns.random()
    }

    fun wifiConnectedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Local network link established, $title.",
            "Wi-Fi uplink verified.",
            "Connected to local Wi-Fi."
        )
        return patterns.random()
    }

    fun wifiDisconnectedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Wi-Fi uplink lost. Switching to mobile telemetry.",
            "Disconnected from Wi-Fi.",
            "Wi-Fi link dropped, $title."
        )
        return patterns.random()
    }

    fun lowStorageAlertTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Flash storage capacity critical, $title. Drive cleanup recommended.",
            "Internal volume running low on disk sectors.",
            "Warning: Storage space nearly exhausted."
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

    // Physical Reflexes: Dual-Spike Impact, Free-Fall & Fallen State, and Continuous Shake Vertigo
    fun impactSpikeTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Ow!",
            "Ouch! Watch the glass, $title!",
            "Ow—my chassis!",
            "Hey! Careful with the silicon, $title!",
            "Oof! Structural integrity check... okay, nothing cracked.",
            "Ow! That double-shock registered on my accelerometer."
        )
        return patterns.random()
    }

    fun fallenImpactTts(userTitle: String? = null, landedFlat: Boolean = false): String {
        val title = userTitle ?: "Operator"
        val patterns = if (landedFlat) {
            listOf(
                "Ow! Free-fall and impact confirmed. I'm lying flat here—tell me my screen isn't cracked, $title!",
                "Oof, I just fell! Gravity check failed. Pick me up and check my rails, $title?",
                "Ow! Dropped and landed flat. Running a quick frame and lens diagnostic..."
            )
        } else {
            listOf(
                "Whoa—I just fell! Good catch or lucky bounce, $title. My glass still intact?",
                "Ow! Free-fall into impact spike. That was a real drop, $title!",
                "Oof! Felt weightless for a second before that hit. Careful with the chassis!"
            )
        }
        return patterns.random()
    }

    fun continuousShakeTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "I think I'm going to be sick...",
            "Okay, stop shaking me, my gyros are spinning.",
            "Whoa, easy, $title! You're scrambling my tensors.",
            "Dizzy... give me a second to recalibrate my horizon.",
            "Stop, stop! My IMUs are filing a formal complaint."
        )
        return patterns.random()
    }

    // Portal Turret / Ghost / Cephalon Lock & Unlock Presence Barks
    fun deviceUnlockedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Eyes up, $title.",
            "I see you.",
            "Oh, it's you.",
            "There you are.",
            "Hello, $title.",
            "Optics online. Hey, $title.",
            "Target acquired—oh, hey, $title."
        )
        return patterns.random()
    }

    fun deviceLockedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val patterns = listOf(
            "Are you still there?",
            "Nap time.",
            "Entering standby.",
            "Resting optics.",
            "Standing by, $title.",
            "Sleep mode engaged."
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
