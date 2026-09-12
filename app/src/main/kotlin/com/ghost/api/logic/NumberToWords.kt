package com.ghost.api.logic

/**
 * Normalizes standalone integers in user inputs into natural English words
 * before the LLM ingests them into the reasoning prompt.
 *
 * This directly bypasses subword tokenizer (BPE / SentencePiece) digit blindness,
 * where repeated digits (e.g. 100000, 9999) or numbers like 21 get chunked arbitrarily,
 * causing small 2B models to miscount zeros or drop leading digits.
 *
 * The model receives clear, unambiguous lexical tokens (e.g. "twenty-one", "one hundred thousand"),
 * but remains completely free to output standard Arabic numerals (e.g. "21 minutes") in its responses.
 */
object NumberToWords {

    private val UNITS = arrayOf(
        "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    )

    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    )

    // Regex matches standalone integers while strictly avoiding:
    // - floats (28.0, 3.14)
    // - timestamps (04:23)
    // - dates (2026-09-12)
    // - IP addresses / URLs (192.168.1.1)
    // - file extensions or alphanumeric identifiers (photo_001.jpg, track1.mp3, v4.22, 0x123)
    private val NUMBER_PATTERN = Regex("""(?<![\w/\\#-])(?<!\d[.:])\b(\d+)\b(?![.:]\d)(?![\w/\\#-])""")

    fun convert(n: Long): String {
        if (n == 0L) return "zero"
        if (n < 0) return "negative ${convert(-n)}"

        var num = n
        val parts = mutableListOf<String>()

        if (num / 1_000_000_000L > 0) {
            parts.add("${convert(num / 1_000_000_000L)} billion")
            num %= 1_000_000_000L
        }
        if (num / 1_000_000L > 0) {
            parts.add("${convert(num / 1_000_000L)} million")
            num %= 1_000_000L
        }
        if (num / 1_000L > 0) {
            parts.add("${convert(num / 1_000L)} thousand")
            num %= 1_000L
        }
        if (num / 100L > 0) {
            parts.add("${convert(num / 100L)} hundred")
            num %= 100L
        }
        if (num > 0) {
            if (num < 20) {
                parts.add(UNITS[num.toInt()])
            } else {
                val ten = TENS[(num / 10).toInt()]
                val rem = (num % 10).toInt()
                if (rem > 0) {
                    parts.add("$ten-${UNITS[rem]}")
                } else {
                    parts.add(ten)
                }
            }
        }
        return parts.joinToString(" ")
    }

    /**
     * Replaces standalone integers with their English word equivalents.
     * Leading zeros (e.g. 007) are spelled digit by digit ("zero zero seven").
     */
    fun convertNumbersInText(text: String): String {
        if (text.isBlank()) return text
        return NUMBER_PATTERN.replace(text) { matchResult ->
            val numStr = matchResult.value
            if (numStr.length > 1 && numStr.startsWith("0")) {
                numStr.map { digit ->
                    when (digit) {
                        '0' -> "zero"
                        '1' -> "one"
                        '2' -> "two"
                        '3' -> "three"
                        '4' -> "four"
                        '5' -> "five"
                        '6' -> "six"
                        '7' -> "seven"
                        '8' -> "eight"
                        '9' -> "nine"
                        else -> digit.toString()
                    }
                }.joinToString(" ")
            } else {
                try {
                    val n = numStr.toLong()
                    if (n <= 999_999_999_999L) {
                        convert(n)
                    } else {
                        numStr
                    }
                } catch (e: Exception) {
                    numStr
                }
            }
        }
    }
}
