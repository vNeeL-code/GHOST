package com.ghost.api.logic

import android.content.Context
import android.content.Intent
import com.ghost.api.hardware.SystemToolSet
import com.ghost.api.ui.AppReelOverlay
import timber.log.Timber
import java.util.Locale

/**
 * Dynamic Intent Bagging & Parallel Intent Suggester for GHOST.
 *
 * Runs in parallel on incoming user messages in < 1ms to pre-match actionable
 * intents against installed applications, media controls, hardware toggles,
 * clocks/alarms/timers, files, diary, and peer consultation.
 *
 * Injects a lean `[Intent Hints]` envelope into prompt context ONLY when
 * actionable intents match, giving Gemma the exact tool syntax immediately
 * without polluting her KV-cache context runway on normal conversational chatter.
 */
object IntentBagger {

    data class BaggedIntent(
        val tool: String,
        val paramsJson: String,
        val hint: String,
        val isWireSpeedDirect: Boolean = false,
        val appLabel: String? = null,
        val browserUrl: String? = null
    )

    data class AppMatch(
        val name: String,
        val isHighConfidence: Boolean
    )

    data class UrlMatch(
        val url: String,
        val isDirect: Boolean
    )

    private val URL_REGEX = Regex("""(?i)\b(?:https?://|www\.)[^\s]+|\b[a-zA-Z0-9-]+\.(?:com|org|net|io|dev|app|ai|me|co|uk|de|ca)(?:/[^\s]*)?""")


    private val MEDIA_VERBS_PAUSE = listOf("pause", "pause music", "stop music", "halt music", "mute music", "stop playing")
    private val MEDIA_VERBS_PLAY = listOf("play", "resume", "unpause", "play music", "start music", "play song", "continue playing")
    private val MEDIA_VERBS_NEXT = listOf("next", "skip", "next song", "next track", "skip track", "skip song")
    private val MEDIA_VERBS_PREV = listOf("prev", "previous", "previous song", "previous track", "last track", "last song")

    private val APP_LAUNCH_PREFIXES = listOf(
        "open the app", "open app", "launch app", "open", "launch", "start",
        "bring up", "go to", "can you please open", "can you open",
        "please open", "switch to", "run"
    )

    private val COMMON_APP_ALIASES = mapOf(
        "calendar" to listOf("Calendar", "Google Calendar"),
        "chrome" to listOf("Chrome", "Browser", "Internet"),
        "browser" to listOf("Chrome", "Browser", "Internet"),
        "spotify" to listOf("Spotify", "Music", "YouTube Music"),
        "music" to listOf("Spotify", "Music", "YouTube Music"),
        "youtube" to listOf("YouTube"),
        "camera" to listOf("Camera"),
        "clock" to listOf("Clock", "DeskClock"),
        "alarm" to listOf("Clock", "DeskClock"),
        "alarms" to listOf("Clock", "DeskClock"),
        "settings" to listOf("Settings"),
        "gallery" to listOf("Photos", "Gallery"),
        "photos" to listOf("Photos", "Gallery"),
        "calculator" to listOf("Calculator"),
        "notes" to listOf("Notes", "Keep", "Google Keep"),
        "keep" to listOf("Google Keep", "Keep"),
        "files" to listOf("Files", "My Files", "File Manager"),
        "maps" to listOf("Maps", "Google Maps"),
        "mail" to listOf("Gmail", "Mail", "Email"),
        "email" to listOf("Gmail", "Mail", "Email"),
        "gmail" to listOf("Gmail"),
        "play store" to listOf("Google Play Store", "Play Store"),
        "whatsapp" to listOf("WhatsApp"),
        "telegram" to listOf("Telegram"),
        "discord" to listOf("Discord"),
        "reddit" to listOf("Reddit"),
        "twitter" to listOf("X", "Twitter"),
        "x" to listOf("X", "Twitter")
    )

    fun bagIntents(context: Context, rawMessage: String): List<BaggedIntent> {
        val trimmed = rawMessage.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("Δ 👾 ∇") || trimmed.startsWith("[Context")) {
            return emptyList()
        }

        val clean = trimmed
            .replace(Regex("""[^\w\s:.]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase(Locale.ROOT)

        val bagged = mutableListOf<BaggedIntent>()

        // 1. Direct Web URL Navigation (Wire-speed exit candidate only if no extra text)
        val urlMatch = matchUrl(clean, trimmed)
        if (urlMatch != null) {
            val urlIntent = BaggedIntent(
                tool = "open_system_browser_bar",
                paramsJson = "{\"queryOrUrl\":\"${urlMatch.url}\"}",
                hint = "Open ${urlMatch.url} in system browser",
                isWireSpeedDirect = urlMatch.isDirect,
                browserUrl = urlMatch.url
            )
            if (urlMatch.isDirect) {
                return listOf(urlIntent)
            } else {
                bagged.add(urlIntent)
            }
        }

        // 2. App Launch Intent (e.g. "open calendar", "can you please open spotify for me")
        val appMatch = matchAppLaunch(context, clean, trimmed)
        if (appMatch != null) {
            val appIntent = BaggedIntent(
                tool = "open_app",
                paramsJson = "{\"name\":\"${appMatch.name}\"}",
                hint = "Launch ${appMatch.name} app immediately",
                isWireSpeedDirect = appMatch.isHighConfidence,
                appLabel = appMatch.name
            )
            if (appMatch.isHighConfidence) {
                return listOf(appIntent)
            } else {
                bagged.add(appIntent)
            }
        }

        // 3. Media Transport Controls
        val mediaAction = matchMedia(clean)
        if (mediaAction != null) {
            bagged.add(BaggedIntent("music", "{\"action\":\"$mediaAction\"}", "Media $mediaAction"))
        }

        // 4. Volume / Sound Controls
        val volumeParams = matchVolume(clean)
        if (volumeParams != null) {
            bagged.add(BaggedIntent("volume", volumeParams, "Adjust or inspect device volume"))
        }

        // 5. Device Status / Telemetry
        if (matchStatus(clean)) {
            bagged.add(BaggedIntent("status", "", "Check device telemetry (battery, network, sensors, audio)"))
        }

        // 6. Flashlight / Torch
        val flashState = matchFlashlight(clean)
        if (flashState != null) {
            bagged.add(BaggedIntent("flashlight", "{\"state\":\"$flashState\"}", "Turn $flashState flashlight"))
        }

        // 7. Timer Intent
        val timerSeconds = matchTimer(clean)
        if (timerSeconds != null) {
            bagged.add(BaggedIntent("timer", "{\"seconds\":$timerSeconds}", "Set countdown timer for ${timerSeconds}s"))
        }

        // 8. Alarm Intent
        val alarmParams = matchAlarm(clean, trimmed)
        if (alarmParams != null) {
            val (h, m, label) = alarmParams
            val labelSnippet = if (label.isNotBlank()) ",\"label\":\"$label\"" else ""
            val parsed = TimePreprocessor.parseTime(trimmed)
            val time12h = parsed?.formatted12h ?: "${if (h == 0) 12 else if (h > 12) h - 12 else h}:${m.toString().padStart(2, '0')} ${if (h >= 12) "PM" else "AM"}"
            val time24h = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
            bagged.add(BaggedIntent("alarm", "{\"hour\":$h,\"minutes\":$m$labelSnippet}", "Set alarm for $time12h ($time24h)"))
        }

        // 9. Diary Reflections Reading
        if (clean.contains("diary") || clean.contains("memory log") || clean.contains("past reflections")) {
            if (clean.contains("read") || clean.contains("show") || clean.contains("what") || clean.contains("check") || clean.contains("open") || clean.contains("look at")) {
                bagged.add(BaggedIntent("read_diary", "{\"days\":3}", "Read recent episodic memory diary reflections"))
            }
        }

        // 10. Calendar Schedule Reading
        if (clean.contains("calendar") || clean.contains("schedule") || clean.contains("agenda") || clean.contains("appointments")) {
            if (clean.contains("what") || clean.contains("check") || clean.contains("read") || clean.contains("show") || clean.contains("upcoming") || clean.contains("today")) {
                bagged.add(BaggedIntent("read_calendar", "{\"days\":7}", "Check upcoming calendar events"))
            }
        }

        // 11. File Search Intent
        val fileQuery = matchFileSearch(clean, trimmed)
        if (fileQuery != null) {
            bagged.add(BaggedIntent("search_files", "{\"query\":\"$fileQuery\"}", "Search device files for $fileQuery"))
        }

        // 12. Peer Consultation Intent
        val peer = matchPeerConsultation(clean, trimmed)
        if (peer != null) {
            val (peerName, prompt) = peer
            bagged.add(BaggedIntent("consult_peer", "{\"peer\":\"$peerName\",\"prompt\":\"$prompt\"}", "Consult peer AI $peerName"))
        }

        return bagged.take(2)
    }

    private fun matchUrl(clean: String, raw: String): UrlMatch? {
        val match = URL_REGEX.find(raw) ?: URL_REGEX.find(clean) ?: return null
        var url = match.value.trim().trimEnd('.', ',', ';')
        if (url.startsWith("www.", ignoreCase = true)) {
            url = "https://$url"
        } else if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "https://$url"
        }

        // Only direct wire-speed exit if the message is strictly a URL or simple navigation command
        var remainder = raw.replace(match.value, "").trim()
        val navPrefixes = listOf("open", "go to", "browse to", "browse", "launch", "visit", "navigate to")
        for (prefix in navPrefixes) {
            if (remainder.equals(prefix, ignoreCase = true)) {
                remainder = ""
                break
            }
        }
        val isDirect = remainder.isEmpty()
        return UrlMatch(url, isDirect)
    }

    private fun matchAppLaunch(context: Context, clean: String, raw: String): AppMatch? {
        // Strip common prefixes
        var candidate = clean
        for (prefix in APP_LAUNCH_PREFIXES) {
            if (candidate.startsWith(prefix)) {
                candidate = candidate.removePrefix(prefix).trim()
                break
            }
        }
        candidate = candidate
            .removePrefix("the ")
            .removeSuffix(" for me")
            .removeSuffix(" app")
            .removeSuffix(" please")
            .removeSuffix(" dammit")
            .trim()

        if (candidate.isBlank()) {
            // Check if raw message contains a direct single app keyword (e.g. "calendar", "spotify")
            val tokens = clean.split(" ")
            for (token in tokens) {
                if (COMMON_APP_ALIASES.containsKey(token)) {
                    val app = COMMON_APP_ALIASES[token]?.firstOrNull() ?: token.replaceFirstChar { it.uppercase() }
                    return AppMatch(app, isHighConfidence = true)
                }
            }
            return null
        }

        // Check aliases first (Exact alias match = high confidence)
        for ((alias, candidates) in COMMON_APP_ALIASES) {
            if (candidate == alias || clean.contains("open $alias") || clean.contains("launch $alias") || clean == alias) {
                val app = candidates.firstOrNull() ?: alias.replaceFirstChar { it.uppercase() }
                return AppMatch(app, isHighConfidence = true)
            }
        }

        // Check installed apps cache
        val apps = if (AppReelOverlay.AppListCache.isLoaded && AppReelOverlay.AppListCache.cachedApps.isNotEmpty()) {
            AppReelOverlay.AppListCache.cachedApps.map { it.label }
        } else {
            emptyList()
        }

        val directMatch = apps.firstOrNull { it.equals(candidate, ignoreCase = true) }
        if (directMatch != null) return AppMatch(directMatch, isHighConfidence = true)

        val partialMatch = apps.firstOrNull { it.contains(candidate, ignoreCase = true) }
        if (partialMatch != null) return AppMatch(partialMatch, isHighConfidence = false)

        if (candidate.length in 3..25 && (raw.contains("open", ignoreCase = true) || raw.contains("launch", ignoreCase = true))) {
            return AppMatch(candidate.replaceFirstChar { it.uppercase() }, isHighConfidence = false)
        }

        return null
    }

    private fun matchVolume(clean: String): String? {
        val hasVolWord = clean.contains("volume") || clean.contains("sound") || clean.contains("audio")
        val isMute = clean.contains("mute") && !clean.contains("unmute")
        val isUnmute = clean.contains("unmute")
        val isUp = clean.contains("louder") || clean.contains("volume up") || clean.contains("turn it up") || clean.contains("raise volume")
        val isDown = clean.contains("quieter") || clean.contains("volume down") || clean.contains("turn it down") || clean.contains("lower volume")
        val isSilent = clean.contains("silent mode") || clean.contains("silence phone")
        val isVibrate = clean.contains("vibrate mode") || clean.contains("vibration mode")

        if (!hasVolWord && !isMute && !isUnmute && !isUp && !isDown && !isSilent && !isVibrate) {
            return null
        }

        val stream = when {
            clean.contains("ring") || clean.contains("call") -> "ring"
            clean.contains("alarm") -> "alarm"
            clean.contains("notif") -> "notification"
            clean.contains("sys") -> "system"
            else -> "media"
        }

        if (isSilent) return "mode=silent"
        if (isVibrate) return "mode=vibrate"
        if (isMute) return "stream=$stream action=mute"
        if (isUnmute) return "stream=$stream action=unmute"
        if (isUp) return "stream=$stream action=up"
        if (isDown) return "stream=$stream action=down"

        val levelMatch = Regex("""(?:to|at|level)?\s*(\d{1,3})\s*%?""").find(clean)
        if (levelMatch != null && hasVolWord) {
            val lvl = levelMatch.groupValues[1].toIntOrNull()
            if (lvl != null && lvl in 0..100) {
                return "stream=$stream level=$lvl"
            }
        }

        if (clean.contains("status") || clean.contains("check") || clean.contains("what") || clean.contains("get")) {
            return "stream=$stream action=get"
        }

        return "stream=$stream"
    }

    private fun matchStatus(clean: String): Boolean {
        if (clean.contains("battery") || clean.contains("charging") || clean.contains("battery level") || clean.contains("battery life")) {
            return true
        }
        if (clean.contains("device status") || clean.contains("phone status") || clean.contains("system status") || clean.contains("hardware status")) {
            return true
        }
        if ((clean.contains("check") || clean.contains("what") || clean.contains("show")) && (clean.contains("telemetry") || clean.contains("sensors") || clean.contains("status"))) {
            return true
        }
        return false
    }

    private fun matchMedia(clean: String): String? {
        return when {
            MEDIA_VERBS_PAUSE.any { clean.contains(it) } -> "PAUSE"
            MEDIA_VERBS_PLAY.any { clean.contains(it) } -> "PLAY"
            MEDIA_VERBS_NEXT.any { clean.contains(it) } -> "NEXT"
            MEDIA_VERBS_PREV.any { clean.contains(it) } -> "PREV"
            else -> null
        }
    }

    private fun matchFlashlight(clean: String): String? {
        if (!clean.contains("torch") && !clean.contains("flashlight")) return null
        return if (clean.contains("off") || clean.contains("disable") || clean.contains("kill")) "OFF" else "ON"
    }

    private fun matchTimer(clean: String): Int? {
        if (!clean.contains("timer")) return null
        val match = Regex("""(\d+)\s*(sec|second|min|minute|hr|hour)s?""").find(clean) ?: return null
        val num = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2]
        return when {
            unit.startsWith("sec") -> num
            unit.startsWith("min") -> num * 60
            unit.startsWith("hr") || unit.startsWith("hour") -> num * 3600
            else -> num * 60
        }
    }

    private fun matchAlarm(clean: String, raw: String): Triple<Int, Int, String>? {
        if (!clean.contains("alarm") && !clean.contains("wake")) return null
        val parsed = TimePreprocessor.parseTime(raw) ?: TimePreprocessor.parseTime(clean) ?: return null
        val label = if (clean.contains("labeled") || clean.contains("called")) {
            raw.substringAfter("labeled", "").substringAfter("called", "").trim()
        } else ""

        return Triple(parsed.hour24, parsed.minute, label)
    }

    private fun matchFileSearch(clean: String, raw: String): String? {
        if (!clean.contains("search file") && !clean.contains("find file") && !clean.contains("look for file") && !clean.contains("search for file")) {
            return null
        }
        val query = raw.substringAfter("file", "").substringAfter("for", "").trim()
        return query.takeIf { it.isNotBlank() }
    }

    private fun matchPeerConsultation(clean: String, raw: String): Pair<String, String>? {
        val peers = listOf("claude", "gemini", "deepseek", "perplexity", "qwen", "kimi", "mistral", "copilot")
        val foundPeer = peers.firstOrNull { clean.contains("ask $it") || clean.contains("consult $it") } ?: return null
        val prompt = raw.substringAfter(foundPeer, "").trim().trimStart(':', ',', ' ')
        val capitalizedPeer = foundPeer.replaceFirstChar { it.uppercase() }
        return Pair(capitalizedPeer, prompt)
    }

    /**
     * Formats bagged intent hints into a concise context block for prompt injection.
     */
    fun formatPromptEnvelope(candidates: List<BaggedIntent>): String {
        if (candidates.isEmpty()) return ""
        val sb = StringBuilder("[Intent Hints (Operator action candidate — execute immediately if requested)]\n")
        for (c in candidates) {
            val directSyntax = when (c.tool) {
                "alarm", "set_alarm" -> {
                    val h = Regex("""["']?hour["']?\s*[:=]\s*(\d+)""").find(c.paramsJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val m = Regex("""["']?min(?:ute)?s?["']?\s*[:=]\s*(\d+)""").find(c.paramsJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    "set_alarm(hour = $h, minute = $m)"
                }
                "timer" -> {
                    val s = Regex("""["']?seconds?["']?\s*[:=]\s*(\d+)""").find(c.paramsJson)?.groupValues?.get(1)?.toIntOrNull() ?: 60
                    "execute_command(\"timer\", \"seconds=$s\")"
                }
                "app", "open_app" -> {
                    val name = c.appLabel ?: Regex("""["']?name["']?\s*[:=]\s*["']?([^"'{}]+)["']?""").find(c.paramsJson)?.groupValues?.get(1)?.trim() ?: ""
                    "open_app(\"$name\")"
                }
                "media", "music" -> {
                    val act = Regex("""["']?action["']?\s*[:=]\s*["']?(\w+)["']?""").find(c.paramsJson)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: "pause"
                    "execute_command(\"music\", \"action=$act\")"
                }
                "flashlight", "toggle_torch" -> {
                    val isTorchOn = c.paramsJson.contains("ON", ignoreCase = true) || c.paramsJson.contains("true", ignoreCase = true)
                    "toggle_torch(enabled = $isTorchOn)"
                }
                "volume" -> {
                    "execute_command(\"volume\", \"${c.paramsJson}\")"
                }
                "status" -> {
                    "execute_command(\"status\", \"\")"
                }
                "open_system_browser_bar" -> {
                    val url = c.browserUrl ?: ""
                    "execute_command(\"open_system_browser_bar\", \"queryOrUrl=\\\"$url\\\"\")"
                }
                else -> {
                    val cleanParams = c.paramsJson.replace("\"", "\\\"")
                    "execute_command(\"${c.tool}\", \"$cleanParams\")"
                }
            }
            sb.append("• Action: $directSyntax — ${c.hint}\n")
        }
        sb.append("[/Intent Hints]")
        return sb.toString().trim()
    }
}

