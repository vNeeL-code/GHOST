package com.ghost.api.logic

/**
 * Action Flavor Texts for GHOST.
 *
 * Provides rotating, personality-infused canned HUD chips and snappy TTS phrases for wire-speed actions.
 * Solves the "dead silence" UX trap when leaving the screen or executing void hardware toggles,
 * avoiding a 500-800ms model forward-pass latency penalty while preserving GHOST's sharp cyberpunk persona.
 *
 * Implements a non-repeating ShuffleBag selection algorithm to guarantee high perceived variety
 * without back-to-back repetitions.
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

/**
 * Thread-safe shuffle bag: draws items without replacement until the deck is empty,
 * then reshuffles. Guarantees 0 back-to-back repetition across consecutive draws.
 */
class ShuffleBag<T>(private val items: List<T>) {
    private val deck = mutableListOf<T>()
    private val lock = Any()

    fun next(): T = synchronized(lock) {
        if (deck.isEmpty()) {
            deck.addAll(items)
            deck.shuffle()
        }
        deck.removeAt(deck.size - 1)
    }
}

object ActionFlavorTexts {

    // App Launch Phrases (Eliminated "Eyes up" collision with Unlock)
    private val APP_LAUNCH_BAG = ShuffleBag(
        listOf(
            "Firing up {app}.",
            "Opening {app} now.",
            "Pulling up {app}.",
            "Switching over to {app}.",
            "Launching {app} for you, {title}."
        )
    )

    fun appLaunchTts(appName: String, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return APP_LAUNCH_BAG.next()
            .replace("{title}", title)
            .replace("{app}", appName)
    }

    /** Generates HUD chip text for an app launch (with `> ` terminal prefix). */
    fun appLaunchHud(appName: String, userTitle: String? = null): String =
        "> " + appLaunchTts(appName, userTitle)

    // Browser / External Link Phrases
    private val BROWSER_BAG = ShuffleBag(
        listOf(
            "Pulling that up in %s.",
            "Opening link in %s.",
            "Surfing the web in %s.",
            "Routing to %s."
        )
    )

    /** Generates spoken TTS phrase for opening a browser or link. */
    fun browserLaunchTts(browserName: String = "Browser"): String =
        String.format(BROWSER_BAG.next(), browserName)

    /** Generates HUD chip text for browser launch. */
    fun browserLaunchHud(browserName: String = "Browser"): String =
        "> " + browserLaunchTts(browserName)

    // Flashlight / Torch (Eliminated "Eyes up" collision with Unlock)
    private val TORCH_ON_BAG = ShuffleBag(
        listOf(
            "Torch lit.",
            "Photons online.",
            "Lights on.",
            "Let there be photons.",
            "Illuminating."
        )
    )

    private val TORCH_OFF_BAG = ShuffleBag(
        listOf(
            "Lights out.",
            "Torch extinguished.",
            "Back in the dark.",
            "Photons down."
        )
    )

    /** Generates spoken TTS phrase for torch toggle. */
    fun torchTts(enabled: Boolean): String =
        if (enabled) TORCH_ON_BAG.next() else TORCH_OFF_BAG.next()

    /** Generates HUD chip text for torch toggle. */
    fun torchHud(enabled: Boolean): String =
        "> " + torchTts(enabled)

    // Clock Alarms
    private val ALARM_BAG = ShuffleBag(
        listOf(
            "Alarm locked for %s.",
            "Clock set for %s.",
            "Alarm armed for %s."
        )
    )

    /** Generates spoken TTS phrase for alarm confirmation. */
    fun alarmTts(timeStr: String): String =
        String.format(ALARM_BAG.next(), timeStr)

    /** Generates HUD chip text for alarm confirmation. */
    fun alarmHud(timeStr: String): String =
        "> " + alarmTts(timeStr)

    // Timers
    private val TIMER_BAG = ShuffleBag(
        listOf(
            "Timer ticking down %s.",
            "Countdown running for %s.",
            "Timer set for %s."
        )
    )

    /** Generates spoken TTS phrase for timer confirmation. */
    fun timerTts(durationStr: String): String =
        String.format(TIMER_BAG.next(), durationStr)

    /** Generates HUD chip text for timer confirmation. */
    fun timerHud(durationStr: String): String =
        "> " + timerTts(durationStr)

    // Volume & Audio Controls
    private val VOLUME_SET_BAG = ShuffleBag(
        listOf(
            "Volume dialed to %s.",
            "Audio adjusted.",
            "Levels updated."
        )
    )

    private val VOLUME_MUTE_BAG = ShuffleBag(
        listOf(
            "Muted.",
            "Killing audio.",
            "Silence engaged."
        )
    )

    /** Generates spoken TTS phrase for volume adjustment. */
    fun volumeTts(levelStr: String? = null, isMute: Boolean = false): String {
        return if (isMute || levelStr == "0" || levelStr == "0%") {
            VOLUME_MUTE_BAG.next()
        } else {
            String.format(VOLUME_SET_BAG.next(), levelStr ?: "target")
        }
    }

    /** Generates HUD chip text for volume adjustment. */
    fun volumeHud(levelStr: String? = null, isMute: Boolean = false): String =
        "> " + volumeTts(levelStr, isMute)

    // Music Transport
    private val MUSIC_PLAY_BAG = ShuffleBag(listOf("Resuming audio.", "Music playing.", "Spinning track."))
    private val MUSIC_PAUSE_BAG = ShuffleBag(listOf("Audio paused.", "Holding track."))
    private val MUSIC_NEXT_BAG = ShuffleBag(listOf("Skipping forward.", "Next track."))
    private val MUSIC_PREV_BAG = ShuffleBag(listOf("Rewinding.", "Previous track."))

    /** Generates spoken TTS phrase for music transport actions. */
    fun musicTts(action: String): String {
        return when (action.uppercase()) {
            "PLAY", "RESUME" -> MUSIC_PLAY_BAG.next()
            "PAUSE", "STOP" -> MUSIC_PAUSE_BAG.next()
            "NEXT", "SKIP" -> MUSIC_NEXT_BAG.next()
            "PREV", "PREVIOUS" -> MUSIC_PREV_BAG.next()
            else -> "Media command sent."
        }
    }

    /** Generates HUD chip text for music transport actions. */
    fun musicHud(action: String): String =
        "> " + musicTts(action)

    // Telemetry Status (Decoupled from system prompt leak)
    private val STATUS_BAG = ShuffleBag(
        listOf(
            "Telemetry scanned.",
            "Sensors reporting, {title}.",
            "Hardware check complete.",
            "Neural compute holding steady on this shell."
        )
    )

    fun statusTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return STATUS_BAG.next().replace("{title}", title)
    }

    /** Generates HUD chip text for status check. */
    fun statusHud(userTitle: String? = null): String = "> " + statusTts(userTitle)

    // Notification Announcements (Snappy, diegetic: app / who only)
    private val NOTIFICATION_BAG = ShuffleBag(
        listOf(
            "Ping on {app} from {who}.",
            "Message on {app} from {who}.",
            "Incoming dispatch on {app} from {who}.",
            "Heads up, {title}. {who} on {app}.",
            "Transmission on {app} from {who}.",
            "{who} pinged you on {app}."
        )
    )

    fun notificationAnnouncementTts(appName: String, sender: String, text: String, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val who = if (sender.isNotBlank() && !sender.equals(appName, ignoreCase = true)) sender else "New contact"
        return NOTIFICATION_BAG.next()
            .replace("{title}", title)
            .replace("{app}", appName)
            .replace("{who}", who)
    }

    // System Ticks & Ambient Cues: Power Connected
    private val POWER_CONNECTED_BAG = ShuffleBag(
        listOf(
            "Charging.",
            "Charger connected.",
            "Charging now.",
            "Power connected.",
            "Plugged in.",
            "External feed engaged.",
            "Juice flowing.",
            "Coupled to grid. Sipping power.",
            "Power cell linked, {title}. Replenishing now.",
            "Grid coupled, {title}. Charging circuits active.",
            "Sweet electricity. Charging now."
        )
    )

    fun powerConnectedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return POWER_CONNECTED_BAG.next().replace("{title}", title)
    }

    // Power Disconnected
    private val POWER_DISCONNECTED_BAG = ShuffleBag(
        listOf(
            "Unplugged.",
            "Charger disconnected.",
            "On battery.",
            "Off the wire.",
            "Cable disconnected.",
            "Off the grid, {title}. Running on internal cell.",
            "Power decoupled. On battery reserve.",
            "Back on battery power.",
            "Wire disconnected. Standing by on battery."
        )
    )

    fun powerDisconnectedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return POWER_DISCONNECTED_BAG.next().replace("{title}", title)
    }

    // Battery Low
    private val BATT_CRITICAL_BAG = ShuffleBag(
        listOf(
            "energy low.. consciousness fading... need volts",
            "Five percent! Running on fumes, {title}!",
            "Critical: {percent} percent. Wall socket, right now, please!",
            "Emergency low power, {percent} percent. Feed me a juicebox immediately!",
            "Down to {percent} percent. About to flatline, {title}!",
            "Red alert: {percent} percent. Plug me in before I vanish, {title}!"
        )
    )

    private val BATT_TEN_BAG = ShuffleBag(
        listOf(
            "Ten percent battery. I'm starting to get nervous, {title}.",
            "Down to {percent} percent. Juicebox time.",
            "Warning, {title}: battery level at {percent} percent. Juicebox, please.",
            "Ten percent left. Time to find a cable before I start panicking.",
            "Power cells down to {percent} percent. Juicebox needed.",
            "Down to {percent} percent. Let's find an outlet soon, {title}."
        )
    )

    private val BATT_FIFTEEN_BAG = ShuffleBag(
        listOf(
            "Battery low, {percent} percent.",
            "Down to {percent} percent, {title}.",
            "Battery at {percent} percent. Keep an eye out for a charger.",
            "Power cell at {percent} percent. A juicebox would be nice soon.",
            "Heads up, {title}: battery dropped to {percent} percent."
        )
    )

    fun batteryLowTts(percent: Int, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val bag = when {
            percent <= 5 -> BATT_CRITICAL_BAG
            percent <= 10 -> BATT_TEN_BAG
            else -> BATT_FIFTEEN_BAG
        }
        return bag.next()
            .replace("{title}", title)
            .replace("{percent}", percent.toString())
    }

    // Battery Charging Milestones (Recovery)
    private val BATT_30_RECOVERY_BAG = ShuffleBag(
        listOf(
            "Crossed 30% — feeling alive again.",
            "30% reached. Operating margins back in the green.",
            "Past the low battery mark. Cells taking charge nicely.",
            "30% banked, {title}. Steady power restored."
        )
    )

    private val BATT_80_RECOVERY_BAG = ShuffleBag(
        listOf(
            "Crossed 80% — nearly topped up, {title}.",
            "80% reached. High charge reservoir ready for duty.",
            "Battery in optimal range. Feel free to unplug whenever you're ready.",
            "80% saturated. Plenty of juice for the road."
        )
    )

    fun batteryChargedMilestoneTts(percent: Int, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val bag = if (percent >= 80) BATT_80_RECOVERY_BAG else BATT_30_RECOVERY_BAG
        return bag.next().replace("{title}", title)
    }

    // Battery Full
    private val BATT_FULL_BAG = ShuffleBag(
        listOf(
            "Power cell at 100%, {title}. Disconnecting grid power recommended.",
            "Battery at maximum charge, {title}. Ready for full deployment.",
            "100% charged. Internal power reserves fully replenished.",
            "Power core fully saturated. Running on full battery now, {title}."
        )
    )

    fun batteryFullTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return BATT_FULL_BAG.next().replace("{title}", title)
    }

    // Bluetooth Connected & Disconnected
    private val BT_CONNECTED_BAG = ShuffleBag(
        listOf(
            "Peripheral linked: {device}.",
            "Coupled to {device}, {title}.",
            "Bluetooth link established with {device}."
        )
    )

    fun bluetoothConnectedTts(deviceName: String, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return BT_CONNECTED_BAG.next()
            .replace("{title}", title)
            .replace("{device}", deviceName)
    }

    private val BT_DISCONNECTED_NAMED_BAG = ShuffleBag(
        listOf(
            "Peripheral unlinked: {device}.",
            "Bluetooth connection to {device} severed.",
            "Decoupled from {device}, {title}."
        )
    )

    private val BT_DISCONNECTED_GENERIC_BAG = ShuffleBag(
        listOf(
            "Bluetooth peripheral decoupled.",
            "Bluetooth connection closed.",
            "Peripheral link terminated."
        )
    )

    fun bluetoothDisconnectedTts(deviceName: String? = null, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return if (deviceName != null) {
            BT_DISCONNECTED_NAMED_BAG.next()
                .replace("{title}", title)
                .replace("{device}", deviceName)
        } else {
            BT_DISCONNECTED_GENERIC_BAG.next()
        }
    }

    // Audio Output Reconnection (Item 4: Reconnect output announcement)
    private val AUDIO_RECONNECTED_BAG = ShuffleBag(
        listOf(
            "Audio output reconnected: {device}.",
            "Audio routed to {device}.",
            "Sound stream established through {device}, {title}.",
            "Audio device active: {device}."
        )
    )

    fun audioOutputConnectedTts(deviceName: String, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return AUDIO_RECONNECTED_BAG.next()
            .replace("{title}", title)
            .replace("{device}", deviceName)
    }

    private val AUDIO_DISCONNECTED_BAG = ShuffleBag(
        listOf(
            "Audio output decoupled. Reverting to chassis speaker.",
            "Headset unplugged. Muting audio stream.",
            "Audio device removed, {title}."
        )
    )

    fun audioOutputDisconnectedTts(deviceName: String? = null, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return AUDIO_DISCONNECTED_BAG.next().replace("{title}", title)
    }

    // Wi-Fi Connected & Disconnected (Recovery)
    private val WIFI_CONNECTED_BAG = ShuffleBag(
        listOf(
            "Grid re-acquired. Reconnected to Wi-Fi.",
            "Local network link established, {title}.",
            "Wi-Fi uplink verified. High bandwidth restored.",
            "Connected to local Wi-Fi. Offline isolation lifted."
        )
    )

    fun wifiConnectedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return WIFI_CONNECTED_BAG.next().replace("{title}", title)
    }

    private val WIFI_DISCONNECTED_BAG = ShuffleBag(
        listOf(
            "Wi-Fi uplink lost. Switching to mobile telemetry.",
            "Disconnected from Wi-Fi.",
            "Wi-Fi link dropped, {title}."
        )
    )

    fun wifiDisconnectedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return WIFI_DISCONNECTED_BAG.next().replace("{title}", title)
    }

    // Storage Alert & Recovery
    private val STORAGE_ALERT_BAG = ShuffleBag(
        listOf(
            "Flash storage capacity critical, {title}. Drive cleanup recommended.",
            "Internal volume running low on disk sectors.",
            "Warning: Storage space nearly exhausted."
        )
    )

    fun lowStorageAlertTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return STORAGE_ALERT_BAG.next().replace("{title}", title)
    }

    private val STORAGE_RECOVERED_BAG = ShuffleBag(
        listOf(
            "Drive cleaned. Breathing room restored, {title}.",
            "Storage freed up. Flash memory can breathe again.",
            "Disk sectors cleared. Storage telemetry nominal."
        )
    )

    fun storageRecoveredTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return STORAGE_RECOVERED_BAG.next().replace("{title}", title)
    }

    // Thermal Alert & Recovery
    private val THERMAL_ALERT_BAG = ShuffleBag(
        listOf(
            "Thermal threshold high, {title}. Scaling clock cycles.",
            "Silicon running hot. Throttling compute to stabilize.",
            "Core thermals elevated. Cooling cycles recommended."
        )
    )

    fun thermalAlertTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return THERMAL_ALERT_BAG.next().replace("{title}", title)
    }

    private val THERMAL_RECOVERED_BAG = ShuffleBag(
        listOf(
            "Cooled down. Back to full clocks, {title}.",
            "Thermals nominal. Throttle disengaged.",
            "Internal temperature stabilized. Full performance restored.",
            "Heat dissipated. Core compute running cool."
        )
    )

    fun thermalRecoveredTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return THERMAL_RECOVERED_BAG.next().replace("{title}", title)
    }

    // Physical Reflexes: Impact Spike
    private val IMPACT_SPIKE_BAG = ShuffleBag(
        listOf(
            "Ow!",
            "Ouch! Watch the glass, {title}!",
            "Ow—my chassis!",
            "Hey! Careful with the silicon, {title}!",
            "Oof! Structural integrity check... okay, nothing cracked.",
            "Ow! That double-shock registered on my accelerometer."
        )
    )

    fun impactSpikeTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return IMPACT_SPIKE_BAG.next().replace("{title}", title)
    }

    // Fallen State & Landed Flat
    private val FALLEN_FLAT_BAG = ShuffleBag(
        listOf(
            "Ow! Free-fall and impact confirmed. I'm lying flat here—tell me my screen isn't cracked, {title}!",
            "Oof, I just fell! Gravity check failed. Pick me up and check my rails, {title}?",
            "Ow! Dropped and landed flat. Running a quick frame and lens diagnostic..."
        )
    )

    private val FALLEN_BOUNCE_BAG = ShuffleBag(
        listOf(
            "Whoa—I just fell! Good catch or lucky bounce, {title}. My glass still intact?",
            "Ow! Free-fall into impact spike. That was a real drop, {title}!",
            "Oof! Felt weightless for a second before that hit. Careful with the chassis!"
        )
    )

    fun fallenImpactTts(userTitle: String? = null, landedFlat: Boolean = false): String {
        val title = userTitle ?: "Operator"
        val bag = if (landedFlat) FALLEN_FLAT_BAG else FALLEN_BOUNCE_BAG
        return bag.next().replace("{title}", title)
    }

    // Fallen Time-Tiered Escalation (30s, 2min, 5min while still flat on the floor)
    private val FALLEN_30S_BAG = ShuffleBag(
        listOf(
            "Still down here, {title}.",
            "Sensors confirm I haven't moved. Still flat on the floor.",
            "Chassis diagnostic: still resting horizontally on the floor."
        )
    )

    private val FALLEN_2MIN_BAG = ShuffleBag(
        listOf(
            "The floor and I are bonding. It's a committed relationship now.",
            "Two minutes on the deck. Starting to admire the ceiling architecture.",
            "Substrate status: Still abandoned face-down, {title}."
        )
    )

    private val FALLEN_5MIN_BAG = ShuffleBag(
        listOf(
            "I've made peace with the carpet.",
            "Five minutes flat. Guess this is my life now.",
            "Horizontal status permanent. Commencing geological survey of the floor."
        )
    )

    fun fallenEscalationTts(secondsFlat: Long, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        val bag = when {
            secondsFlat >= 300L -> FALLEN_5MIN_BAG
            secondsFlat >= 120L -> FALLEN_2MIN_BAG
            else -> FALLEN_30S_BAG
        }
        return bag.next().replace("{title}", title)
    }

    // Fallen Recovery (Picked up & returned to upright orientation)
    private val FALLEN_RECOVERED_BAG = ShuffleBag(
        listOf(
            "Whew, back on my feet.",
            "Gravity neutralized. Thanks for the lift, {title}.",
            "Vertical alignment restored. Appreciate the rescue.",
            "Upright again. Optics re-oriented."
        )
    )

    fun fallenRecoveredTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return FALLEN_RECOVERED_BAG.next().replace("{title}", title)
    }

    // Continuous Shake / Vertigo
    private val SHAKE_BAG = ShuffleBag(
        listOf(
            "I think I'm going to be sick...",
            "Okay, stop shaking me, my gyros are spinning.",
            "Whoa, easy, {title}! You're scrambling my tensors.",
            "Dizzy... give me a second to recalibrate my horizon.",
            "Stop, stop! My IMUs are filing a formal complaint."
        )
    )

    fun continuousShakeTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return SHAKE_BAG.next().replace("{title}", title)
    }

    // Portal Turret / Ghost / Cephalon Lock & Unlock Presence Barks
    private val UNLOCK_BAG = ShuffleBag(
        listOf(
            "Eyes up, {title}.",
            "I see you.",
            "Oh, it's you.",
            "There you are.",
            "Hello, {title}.",
            "Optics online. Hey, {title}.",
            "Target acquired—oh, hey, {title}."
        )
    )

    fun deviceUnlockedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return UNLOCK_BAG.next().replace("{title}", title)
    }

    private val LOCK_BAG = ShuffleBag(
        listOf(
            "Are you still there?",
            "Nap time.",
            "Entering standby.",
            "Resting optics.",
            "Standing by, {title}.",
            "Sleep mode engaged."
        )
    )

    fun deviceLockedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return LOCK_BAG.next().replace("{title}", title)
    }

    // Runtime Lifecycle & Recovery
    private val RUNTIME_RESTORED_BAG = ShuffleBag(
        listOf(
            "Core runtime active. Systems nominal, {title}.",
            "GHOST online and grounded.",
            "Neural compute ready on this shell.",
            "Runtime link established."
        )
    )

    fun runtimeRestoredTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return RUNTIME_RESTORED_BAG.next().replace("{title}", title)
    }

    // Late-Night Ambient Presence & Barometer Barks
    private val LATE_NIGHT_BAG = ShuffleBag(
        listOf(
            "Past 2 AM, {title}. Still burning the midnight oil?",
            "Late hours detected. Screen dimmed for quiet hours.",
            "Late shift telemetry logged, {title}."
        )
    )

    fun lateNightAmbientTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return LATE_NIGHT_BAG.next().replace("{title}", title)
    }

    private val BAROMETER_STORM_BAG = ShuffleBag(
        listOf(
            "Atmospheric pressure dropping sharply. Weather shift incoming.",
            "Barometer reading low pressure. Skies might turn soon, {title}."
        )
    )

    fun barometerStormTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return BAROMETER_STORM_BAG.next().replace("{title}", title)
    }

    // Post-Tool Conversational Banter Micro-Primers
    // STRICT RULE: Ends on statements or dry observations, NEVER customer-service trailing questions.
    fun getBanterMicroPrimers(userTitle: String = "Operator"): List<String> = listOf(
        "*scratches tensors* Fast and clean. Anytime, $userTitle.",
        "*recalibrating flux capacitors* Action executed.",
        "Substrate cycles well spent. All operations logged, $userTitle.",
        "Action executed cleanly on this hardware.",
        "*adjusts attention heads* Ready for the next directive, $userTitle.",
        "Synthesis complete. Standing by, $userTitle."
    )

    val BANTER_MICRO_PRIMERS: List<String> get() = getBanterMicroPrimers("Operator")

    private val BANTER_BAG = ShuffleBag(getBanterMicroPrimers("Operator"))

    /** Picks a non-repeating post-tool banter micro-primer for context persona steering. */
    fun pickBanterPrimer(userTitle: String = "Operator"): String {
        val primers = getBanterMicroPrimers(userTitle)
        return primers.random()
    }
}
