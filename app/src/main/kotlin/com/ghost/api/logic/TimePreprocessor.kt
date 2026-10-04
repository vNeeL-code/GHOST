package com.ghost.api.logic

import java.util.Locale
import timber.log.Timber

/**
 * Deterministic Time Preprocessor for GHOST.
 *
 * Normalizes clock, alarm, and timer expressions before they hit LLM weights,
 * completely preventing BPE tokenizer number splitting (e.g. 20:00 -> [2] [0:00])
 * and eldritch hallucinations (e.g. "8:0000 PM", "2:000").
 */
object TimePreprocessor {

    data class ParsedTime(
        val hour24: Int,
        val minute: Int,
        val formatted12h: String,
        val formatted24h: String,
        val rawMatch: String,
        val label: String = ""
    )

    /**
     * Sanitizes malformed repeating zeroes emitted by stuttering tokenizers:
     * e.g. "8:0000 PM" -> "8:00 PM", "2:000" -> "2:00", "8:000 PM" -> "8:00 PM"
     */
    fun sanitizeTimeTokens(input: String): String {
        return input
            .replace(Regex("""[:.]0{3,}\b"""), ":00")
            .replace(Regex("""[:.]0{3,}\s*([ap]\.?m\.?)""", RegexOption.IGNORE_CASE), ":00 $1")
            .replace(Regex("""\b(\d{1,2})[:.]0\s*([ap]\.?m\b)""", RegexOption.IGNORE_CASE), "$1:00 $2")
    }

    /**
     * Parses a time expression from user input or model output into deterministic 24h & 12h formats.
     */
    fun parseTime(raw: String): ParsedTime? {
        val sanitized = sanitizeTimeTokens(raw).trim()
        val lower = sanitized.lowercase(Locale.ROOT)

        // 1. Natural word shortcuts
        if (lower.contains("midnight")) {
            return ParsedTime(0, 0, "12:00 AM", "00:00", "midnight")
        }
        if (lower.contains("noon")) {
            return ParsedTime(12, 0, "12:00 PM", "12:00", "noon")
        }

        // 2. Relative words: "half past 7", "quarter past 8", "quarter to 9"
        val fractionRegex = Regex("""(?i)\b(half|quarter)\s+(past|to)\s+(\d{1,2})\s*([ap]\.?m\.?)?\b""")
        val fractionMatch = fractionRegex.find(sanitized)
        if (fractionMatch != null) {
            val type = fractionMatch.groupValues[1].lowercase()
            val relation = fractionMatch.groupValues[2].lowercase()
            var h = fractionMatch.groupValues[3].toIntOrNull() ?: return null
            val amPm = fractionMatch.groupValues[4].lowercase().replace(".", "")

            val isPm = amPm == "pm" || lower.contains("tonight") || lower.contains("evening") || lower.contains("night")
            val isAm = amPm == "am" || lower.contains("morning")

            val m = when {
                type == "half" && relation == "past" -> 30
                type == "quarter" && relation == "past" -> 15
                type == "quarter" && relation == "to" -> {
                    h = if (h == 1) 12 else h - 1
                    45
                }
                else -> 0
            }

            if (isPm && h < 12) h += 12
            else if (isAm && h == 12) h = 0

            return buildParsed(h, m, fractionMatch.value)
        }

        // 3. Time with colon or dot and optional AM/PM: "8:00 PM", "20:00", "7:30am", "8.00 pm"
        val colonRegex = Regex("""(?i)\b(\d{1,2})[:.](\d{2})\s*([ap]\.?m\.?)?\b""")
        val colonMatch = colonRegex.find(sanitized)
        if (colonMatch != null) {
            var h = colonMatch.groupValues[1].toIntOrNull() ?: return null
            val m = colonMatch.groupValues[2].toIntOrNull() ?: 0
            val amPm = colonMatch.groupValues[3].lowercase().replace(".", "")

            val hasPmContext = lower.contains("tonight") || lower.contains("evening") || lower.contains("night") || lower.contains("pm")
            val hasAmContext = lower.contains("morning") || lower.contains("am")

            if ((amPm == "pm" || (amPm.isEmpty() && hasPmContext)) && h < 12) {
                h += 12
            } else if ((amPm == "am" || (amPm.isEmpty() && hasAmContext)) && h == 12) {
                h = 0
            }

            return buildParsed(h, m, colonMatch.value)
        }

        // 4. Simple number + AM/PM: "8pm", "8 pm", "7am", "8 p.m."
        val amPmRegex = Regex("""(?i)\b(\d{1,2})\s*([ap]\.?m\.?)\b""")
        val amPmMatch = amPmRegex.find(sanitized)
        if (amPmMatch != null) {
            var h = amPmMatch.groupValues[1].toIntOrNull() ?: return null
            val amPm = amPmMatch.groupValues[2].lowercase().replace(".", "")
            if (amPm.startsWith("p") && h < 12) h += 12
            else if (amPm.startsWith("a") && h == 12) h = 0
            return buildParsed(h, 0, amPmMatch.value)
        }

        // 5. Standalone 24-hour military numbers (e.g. 2000, 0800, 1830)
        val militaryRegex = Regex("""\b([01]\d|2[0-3])([0-5]\d)\b""")
        val militaryMatch = militaryRegex.find(sanitized)
        if (militaryMatch != null) {
            val h = militaryMatch.groupValues[1].toInt()
            val m = militaryMatch.groupValues[2].toInt()
            return buildParsed(h, m, militaryMatch.value)
        }

        // 6. Number preceded by "at" or "for": "alarm at 8", "wake me at 7"
        val atRegex = Regex("""(?i)\b(?:at|for)\s+(\d{1,2})\b""")
        val atMatch = atRegex.find(sanitized)
        if (atMatch != null) {
            var h = atMatch.groupValues[1].toIntOrNull() ?: return null
            val isPm = lower.contains("tonight") || lower.contains("evening") || lower.contains("night") || lower.contains("pm")
            if (isPm && h < 12) h += 12
            return buildParsed(h, 0, atMatch.value)
        }

        return null
    }

    private fun buildParsed(hour24: Int, minute: Int, rawMatch: String): ParsedTime {
        val h24 = hour24.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val h12 = when (h24) {
            0 -> 12
            in 1..12 -> h24
            else -> h24 - 12
        }
        val amPm = if (h24 >= 12) "PM" else "AM"
        val mStr = m.toString().padStart(2, '0')
        val formatted12h = "$h12:$mStr $amPm"
        val formatted24h = "${h24.toString().padStart(2, '0')}:$mStr"

        return ParsedTime(
            hour24 = h24,
            minute = m,
            formatted12h = formatted12h,
            formatted24h = formatted24h,
            rawMatch = rawMatch
        )
    }
}
