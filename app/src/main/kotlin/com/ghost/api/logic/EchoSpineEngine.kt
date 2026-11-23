package com.ghost.api.logic

import android.content.Context
import com.ghost.api.Constants
import com.ghost.api.hardware.HardwareToolSet
import com.ghost.api.hardware.SensorFusionManager
import com.ghost.api.hardware.SystemToolSet
import com.ghost.api.skills.QrCodeGenerator
import org.json.JSONObject
import timber.log.Timber
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * GHOST Echo Mode — Autonomic Spinal Cord Engine (Zero-LLM, Sub-ms, Bounded Output).
 *
 * Keeps GHOST present, responsive, and grounded even when the generative cortex (E4B/E2B)
 * is unavailable:
 * 1. 4GB / Android Go devices (ECHO hardware tier — never OOM-loads a 1.7GB+ model)
 * 2. First-boot model downloads (the ghost introduces itself & runs reflexes while weights download)
 * 3. RAM-valve (94%) or thermal-protection suspension windows (body stays awake while forebrain sleeps)
 * 4. Explicit operator selection (`ECHO` core or `OFF` backend)
 *
 * Security invariant:
 * - Zero generative surface → zero prompt injection, zero hallucinated tool calls.
 * - Skill loader is strictly OFF in Echo mode; only deterministic reflexes and state-conditioned
 *   sensory pools execute.
 * - Intent classification is abstracted behind [EchoIntentClassifier] so an EmbeddingGemma
 *   sidecar (270M text or 567M multimodal) can drop in seamlessly without rewiring.
 */
object EchoSpineEngine {

    enum class EchoReason(val shortLabel: String, val spokenExplanation: String) {
        ECHO_TIER(
            shortLabel = "Echo Tier (Spinal Cord)",
            spokenExplanation = "Running in Echo mode on the spinal cord."
        ),
        DOWNLOADING_WEIGHTS(
            shortLabel = "Downloading Cortex Weights",
            spokenExplanation = "Still downloading my cortex weights—running on spinal cord reflexes in the meantime."
        ),
        RAM_VALVE_SUSPENDED(
            shortLabel = "RAM Valve Sleeping",
            spokenExplanation = "Cortex is napping to save RAM—spinal cord reflexes and sensors are still live."
        ),
        THERMAL_COOLDOWN(
            shortLabel = "Thermal Cooldown",
            spokenExplanation = "Cooling down the cortex—spinal cord reflexes are still online."
        ),
        ENGINE_OFF(
            shortLabel = "Cortex OFF (Echo Active)",
            spokenExplanation = "Inference engine is set to OFF—running on spinal cord reflexes only."
        ),
        INITIALIZING(
            shortLabel = "Warming Up Cortex",
            spokenExplanation = "Cortex is still warming up—spinal cord reflexes are live right now."
        )
    }

    enum class EchoIntent {
        TIME_AND_DATE,
        BATTERY_STATUS,
        THERMAL_STATUS,
        SYSTEM_STATUS,
        GREETING,
        WHO_ARE_YOU,
        THANKS,
        HELP
    }

    /**
     * Pluggable intent classifier interface.
     * Defaults to [LexicalEchoClassifier]; swap in an EmbeddingGemma cosine classifier
     * when the embedding sidecar is active.
     */
    fun interface EchoIntentClassifier {
        fun classify(cleanInput: String): EchoIntent?
    }

    data class EchoSensoryState(
        val batteryPercent: Int = 100,
        val isCharging: Boolean = false,
        val tempC: Int = 28,
        val ramUsedPercent: Int = 50,
        val isLateNight: Boolean = false,
        val nowFormatted: String = "",
        val callSign: String = "Gemma",
        val userTitle: String = "Operator"
    )

    data class EchoResponse(
        val text: String,
        val spokenTts: String = text,
        val webviewUrl: String? = null,
        val webviewAspectRatio: Float? = null,
        val actionExecuted: String? = null
    )

    /**
     * Default deterministic lexical classifier for Echo Mode.
     */
    val LexicalEchoClassifier = EchoIntentClassifier { clean ->
        when {
            Regex("""\b(what time|current time|time is it|what day|what date|day of the week|today's date|what's the date)\b""").containsMatchIn(clean) ||
                clean == "time" || clean == "date" || clean == "day" -> EchoIntent.TIME_AND_DATE

            Regex("""\b(battery|charge|charging|power level|juice|how much battery|percent)\b""").containsMatchIn(clean) -> EchoIntent.BATTERY_STATUS

            Regex("""\b(temp|temperature|how hot|thermal|overheating|heat|warm)\b""").containsMatchIn(clean) -> EchoIntent.THERMAL_STATUS

            Regex("""\b(status|telemetry|diagnostics|ram|memory usage|vitals|how are you holding up)\b""").containsMatchIn(clean) -> EchoIntent.SYSTEM_STATUS

            Regex("""\b(who are you|what are you|echo mode|why are you|are you offline|are you awake|status report)\b""").containsMatchIn(clean) -> EchoIntent.WHO_ARE_YOU

            Regex("""\b(help|what can you do|commands|reflexes)\b""").containsMatchIn(clean) -> EchoIntent.HELP

            Regex("""^(hi|hey|hello|yo|sup|morning|good morning|good evening|good night|evening|hiya|alright|you there|ping)\b""").containsMatchIn(clean) -> EchoIntent.GREETING

            Regex("""^(thanks|thank you|thx|cheers|nice|cool|good job|appreciated|ta)\b""").containsMatchIn(clean) -> EchoIntent.THANKS

            else -> null
        }
    }

    @Volatile
    var activeClassifier: EchoIntentClassifier = EmbeddingSidecar.SemanticEchoClassifier

    private val daytimeGreetings = listOf(
        "Eyes up, {title}. Spinal cord online—{battery}% battery, {temp}°C.",
        "Here, {title}. {reason} Reflexes and sensors are live.",
        "Online on the spinal cord, {title}. Clock, sensors, and hardware reflexes are ready."
    )

    private val lateNightGreetings = listOf(
        "Still up, {title}. Late-night Echo watch—{battery}% battery, {temp}°C.",
        "Quiet hours, {title}. Spinal cord is awake whenever you need a reflex.",
        "Night shift online, {title}. {reason}"
    )

    private val lowBatteryGreetings = listOf(
        "Running light at {battery}%, {title}. Echo mode is keeping power draw near zero.",
        "Conserving volts at {battery}%, {title}. Spinal cord reflexes are still online."
    )

    private val thanksPool = listOf(
        "Anytime, {title}.",
        "Logged and ready, {title}.",
        "Always on watch, {title}."
    )

    private val fallbackPool = listOf(
        "{reason} I can still run reflexes—time, battery, thermals, flashlight, volume, media, alarms, timers, apps, or QR codes.",
        "Spinal cord only right now ({shortLabel}). Ask for time, device vitals, apps, media, timers, or a QR code—full chat returns once the cortex wakes.",
        "Echo mode active ({shortLabel}). No generative cortex right now, {title}, but hardware reflexes, clock, and telemetry are live."
    )

    private var bagIndex = 0
    private fun pickFrom(pool: List<String>): String {
        val idx = (bagIndex++) % pool.size
        return pool[idx]
    }

    /**
     * Full Android-wired entry point: tries deterministic IntentBagger reflexes first,
     * then falls back to state-conditioned sensory & conversational Echo responses.
     */
    fun handle(
        context: Context,
        rawInput: String,
        reason: EchoReason,
        sensorFusionManager: SensorFusionManager? = null,
        systemToolSet: SystemToolSet? = null,
        hardwareToolSet: HardwareToolSet? = null
    ): EchoResponse {
        val trimmed = rawInput.trim()
        val sensory = captureSensoryState(context, sensorFusionManager)

        if (trimmed.isEmpty()) {
            return buildConversationalResponse(EchoIntent.GREETING, reason, sensory)
        }

        // Pass 1: Deterministic Reflexes via IntentBagger (skill loader stays strictly OFF)
        val bagged = try {
            IntentBagger.bagIntents(context, trimmed)
        } catch (e: Exception) {
            Timber.w(e, "EchoSpineEngine: IntentBagger failed")
            emptyList()
        }

        val reflexResult = executeSafeReflex(context, bagged, sensory, systemToolSet, hardwareToolSet)
        if (reflexResult != null) {
            return reflexResult
        }

        // Pass 2: Grounded Sensory & Conversational Classifier (pluggable for EmbeddingGemma)
        return handlePureText(trimmed, reason, sensory)
    }

    /**
     * Pure, JVM-testable text classification + state-conditioned response synthesis.
     */
    fun handlePureText(
        rawInput: String,
        reason: EchoReason,
        sensory: EchoSensoryState
    ): EchoResponse {
        val clean = rawInput.trim()
            .replace(Regex("""[^\w\s':.-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase(Locale.ROOT)

        val intent = activeClassifier.classify(clean)
        return buildConversationalResponse(intent, reason, sensory)
    }

    private fun buildConversationalResponse(
        intent: EchoIntent?,
        reason: EchoReason,
        s: EchoSensoryState
    ): EchoResponse {
        fun fill(template: String): String = template
            .replace("{title}", s.userTitle)
            .replace("{callSign}", s.callSign)
            .replace("{battery}", s.batteryPercent.toString())
            .replace("{temp}", s.tempC.toString())
            .replace("{ram}", s.ramUsedPercent.toString())
            .replace("{reason}", reason.spokenExplanation)
            .replace("{shortLabel}", reason.shortLabel)

        val reply = when (intent) {
            EchoIntent.TIME_AND_DATE ->
                "It's ${s.nowFormatted}, ${s.userTitle}."

            EchoIntent.BATTERY_STATUS -> {
                val chargeStr = if (s.isCharging) "charging" else "on battery"
                val moodNote = if (!s.isCharging && s.batteryPercent <= Constants.BATTERY_WORRIED_ENTER_PERCENT) {
                    " Running low—plug in soon."
                } else ""
                "Battery is at ${s.batteryPercent}% ($chargeStr), ${s.tempC}°C.$moodNote"
            }

            EchoIntent.THERMAL_STATUS -> {
                val feel = when {
                    s.tempC >= 52 -> "running hot"
                    s.tempC >= 43 -> "warm"
                    else -> "cool and steady"
                }
                "Thermals are ${s.tempC}°C—$feel."
            }

            EchoIntent.SYSTEM_STATUS -> {
                val chargeStr = if (s.isCharging) "charging" else "discharging"
                "Echo status [${reason.shortLabel}]: ${s.batteryPercent}% ($chargeStr), ${s.tempC}°C, RAM ${s.ramUsedPercent}% · ${s.nowFormatted}."
            }

            EchoIntent.GREETING -> {
                val pool = when {
                    !s.isCharging && s.batteryPercent <= Constants.BATTERY_WORRIED_ENTER_PERCENT -> lowBatteryGreetings
                    s.isLateNight -> lateNightGreetings
                    else -> daytimeGreetings
                }
                fill(pickFrom(pool))
            }

            EchoIntent.WHO_ARE_YOU ->
                "${s.callSign} here, ${s.userTitle}. ${reason.spokenExplanation} My spinal cord handles clock, live sensors, apps, media, volume, flashlight, timers, alarms, and offline QR codes with zero cloud calls."

            EchoIntent.HELP ->
                "Echo reflexes online: ask the time/day, check battery or thermals, launch apps, open URLs, toggle flashlight, set volume, control music, set alarms/timers, or generate a QR code."

            EchoIntent.THANKS ->
                fill(pickFrom(thanksPool))

            null ->
                fill(pickFrom(fallbackPool))
        }

        return EchoResponse(text = reply, spokenTts = reply)
    }

    private fun executeSafeReflex(
        context: Context,
        bagged: List<IntentBagger.BaggedIntent>,
        sensory: EchoSensoryState,
        systemToolSet: SystemToolSet?,
        hardwareToolSet: HardwareToolSet?
    ): EchoResponse? {
        for (intent in bagged) {
            try {
                when (intent.tool) {
                    "open_app" -> {
                        val appName = intent.appLabel ?: JSONObject(intent.paramsJson).optString("name", "")
                        if (appName.isNotBlank() && systemToolSet != null) {
                            val res = systemToolSet.app(appName)
                            if (res["result"] == "success") {
                                val text = ActionFlavorTexts.appLaunchHud(appName, sensory.userTitle)
                                val tts = ActionFlavorTexts.appLaunchTts(appName, sensory.userTitle)
                                return EchoResponse(text = text, spokenTts = tts, actionExecuted = "open_app:$appName")
                            }
                        }
                    }

                    "open_system_browser_bar" -> {
                        val url = intent.browserUrl ?: JSONObject(intent.paramsJson).optString("queryOrUrl", "")
                        if (url.isNotBlank()) {
                            val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                            val launchIntent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(normalized)
                            ).apply {
                                addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(launchIntent)
                            val host = try { android.net.Uri.parse(normalized).host ?: normalized } catch (_: Exception) { normalized }
                            return EchoResponse(
                                text = "Opening $host.",
                                spokenTts = "Opening $host.",
                                actionExecuted = "open_url:$normalized"
                            )
                        }
                    }

                    "music" -> {
                        val action = JSONObject(intent.paramsJson).optString("action", "")
                        if (action.isNotBlank() && systemToolSet != null) {
                            systemToolSet.media(action)
                            val hud = ActionFlavorTexts.musicHud(action)
                            val tts = ActionFlavorTexts.musicTts(action)
                            return EchoResponse(text = hud, spokenTts = tts, actionExecuted = "media:$action")
                        }
                    }

                    "flashlight" -> {
                        val state = JSONObject(intent.paramsJson).optString("state", "ON")
                        val turnOn = state.equals("ON", ignoreCase = true)
                        if (hardwareToolSet != null) {
                            hardwareToolSet.flashlight(if (turnOn) "ON" else "OFF")
                            val hud = ActionFlavorTexts.torchHud(turnOn)
                            val tts = ActionFlavorTexts.torchTts(turnOn)
                            return EchoResponse(text = hud, spokenTts = tts, actionExecuted = "flashlight:$state")
                        }
                    }

                    "volume" -> {
                        val json = JSONObject(intent.paramsJson)
                        val level = json.optInt("level", -1)
                        if (level in 0..100) {
                            val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                            if (am != null) {
                                val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                val target = ((level / 100.0) * maxVol).toInt().coerceIn(0, maxVol)
                                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
                                val hud = ActionFlavorTexts.volumeHud("$level%", level == 0)
                                val tts = ActionFlavorTexts.volumeTts("$level%", level == 0)
                                return EchoResponse(text = hud, spokenTts = tts, actionExecuted = "volume:$level")
                            }
                        }
                    }

                    "alarm" -> {
                        val json = JSONObject(intent.paramsJson)
                        val hour = json.optInt("hour", -1)
                        val minute = json.optInt("minute", 0)
                        val label = json.optString("label", "")
                        if (hour in 0..23 && systemToolSet != null) {
                            systemToolSet.alarm(hour, minute, label)
                            val timeStr = String.format(Locale.US, "%02d:%02d", hour, minute)
                            val hud = ActionFlavorTexts.alarmHud(timeStr)
                            val tts = ActionFlavorTexts.alarmTts(timeStr)
                            return EchoResponse(text = hud, spokenTts = tts, actionExecuted = "alarm:$timeStr")
                        }
                    }

                    "timer" -> {
                        val json = JSONObject(intent.paramsJson)
                        val secs = json.optInt("seconds", -1)
                        val label = json.optString("label", "")
                        if (secs > 0 && systemToolSet != null) {
                            systemToolSet.timer(secs, label)
                            val durLabel = SystemToolSet.formatDuration(secs)
                            val hud = ActionFlavorTexts.timerHud(durLabel)
                            val tts = ActionFlavorTexts.timerTts(durLabel)
                            return EchoResponse(text = hud, spokenTts = tts, actionExecuted = "timer:$secs")
                        }
                    }

                    "run_js" -> {
                        // Offline native QR code generation works in Echo Mode with zero JS/WebView/LLM overhead
                        val json = JSONObject(intent.paramsJson)
                        val skill = json.optString("skill", "").lowercase(Locale.ROOT)
                        if (skill == "qr-code" || skill == "qrcode" || skill == "qr") {
                            val argsStr = json.optString("args", "")
                            val payload = try {
                                JSONObject(argsStr).optString("text", argsStr)
                            } catch (_: Exception) {
                                argsStr
                            }.ifBlank { "https://github.com/vNeeL-code/GHOST" }
                            val qrUri = QrCodeGenerator.generatePngBase64(payload, 512)
                            return EchoResponse(
                                text = "Generated offline QR code for: $payload",
                                spokenTts = "QR code ready, ${sensory.userTitle}.",
                                webviewUrl = qrUri,
                                webviewAspectRatio = 1.0f,
                                actionExecuted = "qr_code"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "EchoSpineEngine: Reflex ${intent.tool} failed")
            }
        }
        return null
    }

    private fun captureSensoryState(
        context: Context,
        sensorFusionManager: SensorFusionManager?
    ): EchoSensoryState {
        val now = ZonedDateTime.now()
        val fmt = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy · h:mm a", Locale.getDefault())
        val snap = try { sensorFusionManager?.getContextSnapshot() } catch (_: Exception) { null }
        val batt = snap?.battery?.level ?: 100
        val charging = snap?.battery?.isCharging ?: false
        val temp = snap?.environment?.cpuTemp?.toInt()
            ?: snap?.battery?.temperature?.toInt()
            ?: 28
        val ram = snap?.system?.ramUsedPercent ?: 50
        val hour = now.hour
        return EchoSensoryState(
            batteryPercent = batt,
            isCharging = charging,
            tempC = temp,
            ramUsedPercent = ram,
            isLateNight = hour >= 22 || hour < 6,
            nowFormatted = now.format(fmt),
            callSign = ContextManager.resolveDeviceCallSign(context),
            userTitle = Constants.getUserTitle(context)
        )
    }
}
