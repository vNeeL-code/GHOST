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
            "Sensors nominal, {title}.",
            "Hardware check complete.",
            "Systems holding steady."
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
            "Heads up, {title}. {who} on {app}.",
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
            "Juice flowing."
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
            "Back on battery."
        )
    )

    fun powerDisconnectedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return POWER_DISCONNECTED_BAG.next().replace("{title}", title)
    }

    // Battery Low
    private val BATT_CRITICAL_BAG = ShuffleBag(
        listOf(
            "Energy low... need volts.",
            "{percent}% battery! Running on fumes!",
            "Critical: {percent}%. Charger, quick!",
            "Down to {percent}%, {title}!"
        )
    )

    private val BATT_TEN_BAG = ShuffleBag(
        listOf(
            "Battery at {percent}%, {title}.",
            "Down to {percent}%. Juicebox time.",
            "{percent}% left. Find a cable soon.",
            "Low battery: {percent}%."
        )
    )

    private val BATT_FIFTEEN_BAG = ShuffleBag(
        listOf(
            "Battery low, {percent}%.",
            "Down to {percent}%, {title}.",
            "Heads up: {percent}% battery."
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
            "Back to 30%.",
            "30% charged, {title}.",
            "Crossed 30% — breathing easy."
        )
    )

    private val BATT_80_RECOVERY_BAG = ShuffleBag(
        listOf(
            "80% charged, {title}.",
            "Crossed 80% — good to go.",
            "80% reached. Ready whenever."
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
            "Battery full, {title}.",
            "100% charged.",
            "Topped up at 100%, {title}.",
            "Full charge reached."
        )
    )

    fun batteryFullTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return BATT_FULL_BAG.next().replace("{title}", title)
    }

    // Bluetooth Connected & Disconnected
    private val BT_CONNECTED_BAG = ShuffleBag(
        listOf(
            "Connected to {device}.",
            "Linked with {device}, {title}.",
            "{device} connected."
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
            "{device} disconnected.",
            "Unlinked from {device}.",
            "Lost link to {device}, {title}."
        )
    )

    private val BT_DISCONNECTED_GENERIC_BAG = ShuffleBag(
        listOf(
            "Bluetooth disconnected.",
            "Peripheral unlinked.",
            "Bluetooth link closed."
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
            "Audio routed to {device}.",
            "{device} connected.",
            "Playing through {device}, {title}."
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
            "Headphones unplugged.",
            "Audio device removed, {title}.",
            "Audio output disconnected."
        )
    )

    fun audioOutputDisconnectedTts(deviceName: String? = null, userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return AUDIO_DISCONNECTED_BAG.next().replace("{title}", title)
    }

    // Wi-Fi Connected & Disconnected (Recovery)
    private val WIFI_CONNECTED_BAG = ShuffleBag(
        listOf(
            "Wi-Fi connected.",
            "Back on Wi-Fi, {title}.",
            "Network link restored."
        )
    )

    fun wifiConnectedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return WIFI_CONNECTED_BAG.next().replace("{title}", title)
    }

    private val WIFI_DISCONNECTED_BAG = ShuffleBag(
        listOf(
            "Wi-Fi disconnected.",
            "Wi-Fi dropped, {title}.",
            "Lost Wi-Fi link."
        )
    )

    fun wifiDisconnectedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return WIFI_DISCONNECTED_BAG.next().replace("{title}", title)
    }

    // Storage Alert & Recovery
    private val STORAGE_ALERT_BAG = ShuffleBag(
        listOf(
            "Storage running low, {title}.",
            "Disk space critical.",
            "Low storage warning."
        )
    )

    fun lowStorageAlertTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return STORAGE_ALERT_BAG.next().replace("{title}", title)
    }

    private val STORAGE_RECOVERED_BAG = ShuffleBag(
        listOf(
            "Storage cleared, {title}.",
            "Disk space recovered.",
            "Storage breathing again."
        )
    )

    fun storageRecoveredTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return STORAGE_RECOVERED_BAG.next().replace("{title}", title)
    }

    // Thermal Alert & Recovery
    private val THERMAL_ALERT_BAG = ShuffleBag(
        listOf(
            "Running hot, {title}.",
            "Thermals high — throttling.",
            "Getting warm in here."
        )
    )

    fun thermalAlertTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return THERMAL_ALERT_BAG.next().replace("{title}", title)
    }

    private val THERMAL_RECOVERED_BAG = ShuffleBag(
        listOf(
            "Cooled down, {title}.",
            "Thermals back to normal.",
            "Running cool again."
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
            "Ouch! Easy on the glass, {title}!",
            "Ow—my frame!",
            "Hey! Careful, {title}!",
            "Oof! Nothing cracked."
        )
    )

    fun impactSpikeTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return IMPACT_SPIKE_BAG.next().replace("{title}", title)
    }

    // Fallen State & Landed Flat
    private val FALLEN_FLAT_BAG = ShuffleBag(
        listOf(
            "Ow! Dropped flat, {title}!",
            "Oof, I fell! Screen okay?",
            "Ow! Landed flat on the deck."
        )
    )

    private val FALLEN_BOUNCE_BAG = ShuffleBag(
        listOf(
            "Whoa—that was a drop, {title}!",
            "Ow! Hard bounce. Glass intact?",
            "Oof! Careful with the drop!"
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
            "Still flat on the floor.",
            "Waiting for a pickup, {title}."
        )
    )

    private val FALLEN_2MIN_BAG = ShuffleBag(
        listOf(
            "Bonding with the floor now.",
            "Two minutes down here, {title}.",
            "Still abandoned face-down, {title}."
        )
    )

    private val FALLEN_5MIN_BAG = ShuffleBag(
        listOf(
            "Made peace with the carpet.",
            "Five minutes flat. Guess I live here.",
            "Still on the floor, {title}."
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
            "Whew, back up.",
            "Thanks for the lift, {title}.",
            "Back on my feet.",
            "Upright again."
        )
    )

    fun fallenRecoveredTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return FALLEN_RECOVERED_BAG.next().replace("{title}", title)
    }

    // Continuous Shake / Vertigo
    private val SHAKE_BAG = ShuffleBag(
        listOf(
            "Getting dizzy...",
            "Stop shaking me, gyros spinning!",
            "Whoa, easy, {title}!",
            "Dizzy... give me a second."
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
            "Hey, {title}."
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
            "Sleep mode."
        )
    )

    fun deviceLockedTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return LOCK_BAG.next().replace("{title}", title)
    }

    // Runtime Lifecycle & Recovery
    private val RUNTIME_RESTORED_BAG = ShuffleBag(
        listOf(
            "Runtime active, {title}.",
            "GHOST online.",
            "Systems ready.",
            "Back online."
        )
    )

    fun runtimeRestoredTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return RUNTIME_RESTORED_BAG.next().replace("{title}", title)
    }

    // Late-Night Ambient Presence & Barometer Barks
    private val LATE_NIGHT_BAG = ShuffleBag(
        listOf(
            "Past 2 AM, {title}.",
            "Late shift, {title}?",
            "Quiet hours active."
        )
    )

    fun lateNightAmbientTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return LATE_NIGHT_BAG.next().replace("{title}", title)
    }

    private val BAROMETER_STORM_BAG = ShuffleBag(
        listOf(
            "Pressure dropping. Weather incoming.",
            "Low pressure on the barometer, {title}."
        )
    )

    fun barometerStormTts(userTitle: String? = null): String {
        val title = userTitle ?: "Operator"
        return BAROMETER_STORM_BAG.next().replace("{title}", title)
    }

    // Post-Tool Conversational Banter Micro-Primers
    // STRICT RULE: Ends on statements or dry observations, NEVER customer-service trailing questions.
    fun getBanterMicroPrimers(userTitle: String = "Operator"): List<String> = listOf(
        "Fast and clean. Anytime, $userTitle.",
        "Action executed.",
        "All set, $userTitle.",
        "Done and logged.",
        "Ready for the next call, $userTitle.",
        "Standing by, $userTitle."
    )

    val BANTER_MICRO_PRIMERS: List<String> get() = getBanterMicroPrimers("Operator")

    private val BANTER_BAG = ShuffleBag(getBanterMicroPrimers("Operator"))

    /** Picks a non-repeating post-tool banter micro-primer for context persona steering. */
    fun pickBanterPrimer(userTitle: String = "Operator"): String {
        val primers = getBanterMicroPrimers(userTitle)
        return primers.random()
    }
}
