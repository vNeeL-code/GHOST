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
        val hint: String
    )

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

        // 1. App Launch Intent (e.g. "open calendar", "can you please open spotify for me", "calendar, dammit")
        val appName = matchAppLaunch(context, clean, trimmed)
        if (appName != null) {
            bagged.add(BaggedIntent("app", "{\"name\":\"$appName\"}", "Launch $appName app immediately"))
        }

        // 2. Media Transport Controls
        val mediaAction = matchMedia(clean)
        if (mediaAction != null) {
            bagged.add(BaggedIntent("media", "{\"action\":\"$mediaAction\"}", "Media $mediaAction"))
        }

        // 3. Flashlight / Torch
        val flashState = matchFlashlight(clean)
        if (flashState != null) {
            bagged.add(BaggedIntent("flashlight", "{\"state\":\"$flashState\"}", "Turn $flashState flashlight"))
        }

        // 4. Timer Intent
        val timerSeconds = matchTimer(clean)
        if (timerSeconds != null) {
            bagged.add(BaggedIntent("timer", "{\"seconds\":$timerSeconds}", "Set countdown timer for ${timerSeconds}s"))
        }

        // 5. Alarm Intent
        val alarmParams = matchAlarm(clean, trimmed)
        if (alarmParams != null) {
            val (h, m, label) = alarmParams
            val labelSnippet = if (label.isNotBlank()) ",\"label\":\"$label\"" else ""
            val parsed = TimePreprocessor.parseTime(trimmed)
            val time12h = parsed?.formatted12h ?: "${if (h == 0) 12 else if (h > 12) h - 12 else h}:${m.toString().padStart(2, '0')} ${if (h >= 12) "PM" else "AM"}"
            val time24h = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
            bagged.add(BaggedIntent("alarm", "{\"hour\":$h,\"minutes\":$m$labelSnippet}", "Set alarm for $time12h ($time24h)"))
        }

        // 6. Diary Reflections Reading
        if (clean.contains("diary") || clean.contains("memory log") || clean.contains("past reflections")) {
            if (clean.contains("read") || clean.contains("show") || clean.contains("what") || clean.contains("check") || clean.contains("open") || clean.contains("look at")) {
                bagged.add(BaggedIntent("read_diary", "{\"days\":3}", "Read recent episodic memory diary reflections"))
            }
        }

        // 7. Calendar Schedule Reading
        if (clean.contains("calendar") || clean.contains("schedule") || clean.contains("agenda") || clean.contains("appointments")) {
            if (clean.contains("what") || clean.contains("check") || clean.contains("read") || clean.contains("show") || clean.contains("upcoming") || clean.contains("today")) {
                bagged.add(BaggedIntent("read_calendar", "{\"days\":7}", "Check upcoming calendar events"))
            }
        }

        // 8. File Search Intent
        val fileQuery = matchFileSearch(clean, trimmed)
        if (fileQuery != null) {
            bagged.add(BaggedIntent("search_files", "{\"query\":\"$fileQuery\"}", "Search device files for $fileQuery"))
        }

        // 9. Peer Consultation Intent
        val peer = matchPeerConsultation(clean, trimmed)
        if (peer != null) {
            val (peerName, prompt) = peer
            bagged.add(BaggedIntent("consult_peer", "{\"peer\":\"$peerName\",\"prompt\":\"$prompt\"}", "Consult peer AI $peerName"))
        }

        return bagged.take(2)
    }

    private fun matchAppLaunch(context: Context, clean: String, raw: String): String? {
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
            .trim()

        if (candidate.isBlank()) {
            // Check if raw message contains a direct single app keyword (e.g. "calendar", "spotify")
            val tokens = clean.split(" ")
            for (token in tokens) {
                if (COMMON_APP_ALIASES.containsKey(token)) {
                    return COMMON_APP_ALIASES[token]?.firstOrNull() ?: token.replaceFirstChar { it.uppercase() }
                }
            }
            return null
        }

        // Check aliases first
        for ((alias, candidates) in COMMON_APP_ALIASES) {
            if (candidate == alias || candidate.contains(alias) || clean.contains(" $alias")) {
                return candidates.firstOrNull() ?: alias.replaceFirstChar { it.uppercase() }
            }
        }

        // Check installed apps cache
        val apps = if (AppReelOverlay.AppListCache.isLoaded && AppReelOverlay.AppListCache.cachedApps.isNotEmpty()) {
            AppReelOverlay.AppListCache.cachedApps.map { it.label }
        } else {
            emptyList()
        }

        val directMatch = apps.firstOrNull { it.equals(candidate, ignoreCase = true) }
            ?: apps.firstOrNull { it.contains(candidate, ignoreCase = true) }

        if (directMatch != null) return directMatch

        if (candidate.length in 3..25 && (raw.contains("open", ignoreCase = true) || raw.contains("launch", ignoreCase = true))) {
            return candidate.replaceFirstChar { it.uppercase() }
        }

        return null
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
                "alarm" -> {
                    try {
                        val json = org.json.JSONObject(c.paramsJson)
                        val h = json.optInt("hour", 0)
                        val m = json.optInt("minutes", 0)
                        val l = json.optString("label", "")
                        if (l.isNotBlank()) "alarm(hour = $h, minutes = $m, label = \"$l\")" else "alarm(hour = $h, minutes = $m)"
                    } catch (e: Exception) {
                        "alarm(${c.paramsJson})"
                    }
                }
                "timer" -> {
                    try {
                        val json = org.json.JSONObject(c.paramsJson)
                        val s = json.optInt("seconds", 60)
                        "timer(seconds = $s)"
                    } catch (e: Exception) {
                        "timer(${c.paramsJson})"
                    }
                }
                "app" -> {
                    try {
                        val json = org.json.JSONObject(c.paramsJson)
                        val name = json.optString("name", "")
                        "open_app(\"$name\")"
                    } catch (e: Exception) {
                        "open_app(${c.paramsJson})"
                    }
                }
                "media" -> {
                    try {
                        val json = org.json.JSONObject(c.paramsJson)
                        val act = json.optString("action", "PAUSE")
                        "media(\"$act\")"
                    } catch (e: Exception) {
                        "media(${c.paramsJson})"
                    }
                }
                "flashlight" -> {
                    try {
                        val json = org.json.JSONObject(c.paramsJson)
                        val st = json.optString("state", "ON")
                        "flashlight(\"$st\")"
                    } catch (e: Exception) {
                        "flashlight(${c.paramsJson})"
                    }
                }
                else -> "execute_action(\"${c.tool}\", \"${c.paramsJson.replace("\"", "\\\"")}\")"
            }
            sb.append("• Action: $directSyntax — ${c.hint}\n")
        }
        sb.append("[/Intent Hints]")
        return sb.toString().trim()
    }
}
