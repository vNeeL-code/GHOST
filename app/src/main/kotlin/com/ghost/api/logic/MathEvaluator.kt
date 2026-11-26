package com.ghost.api.logic

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Zero-dependency deterministic arithmetic evaluator so compact on-device LLMs (E2B/E4B)
 * never hallucinate basic math, percentages, restaurant tips, or bill splits.
 */
object MathEvaluator {

    fun evaluate(rawExpression: String): Result<String> {
        return try {
            val normalized = preprocess(rawExpression)
            if (normalized.isBlank()) {
                return Result.failure(IllegalArgumentException("Empty math expression"))
            }
            val parser = Parser(normalized)
            val value = parser.parseExpression()
            parser.skipWhitespace()
            if (parser.pos < normalized.length) {
                return Result.failure(IllegalArgumentException("Unexpected character '${normalized[parser.pos]}'"))
            }
            if (value.isNaN() || value.isInfinite()) {
                return Result.failure(ArithmeticException("Result is undefined (division by zero or overflow)"))
            }
            Result.success(formatNumber(value))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun preprocess(raw: String): String {
        var s = raw.trim().lowercase(Locale.ROOT)
            .replace(",", "")
            .replace("$", "")
            .replace("£", "")
            .replace("€", "")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("−", "-")
            .replace("π", "pi")

        // Natural-language percentage helpers:
        // "18% tip on 73.50" or "18% of 73.50" -> "(18/100)*73.50"
        s = Regex("""(\d+(?:\.\d+)?)\s*%\s*(?:tip\s+on|of|on)\s*(\d+(?:\.\d+)?)""")
            .replace(s) { m -> "((${m.groupValues[1]}/100.0)*${m.groupValues[2]})" }

        // "73.50 + 18%" -> "(73.50*(1+18/100.0))"
        s = Regex("""(\d+(?:\.\d+)?)\s*\+\s*(\d+(?:\.\d+)?)\s*%""")
            .replace(s) { m -> "(${m.groupValues[1]}*(1+(${m.groupValues[2]}/100.0)))" }

        // "73.50 - 20%" -> "(73.50*(1-20/100.0))"
        s = Regex("""(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)\s*%""")
            .replace(s) { m -> "(${m.groupValues[1]}*(1-(${m.groupValues[2]}/100.0)))" }

        // Standalone "18%" -> "(18/100.0)"
        s = Regex("""(\d+(?:\.\d+)?)\s*%""")
            .replace(s) { m -> "(${m.groupValues[1]}/100.0)" }

        // Word operators
        s = s.replace(Regex("""\bplus\b"""), "+")
            .replace(Regex("""\bminus\b"""), "-")
            .replace(Regex("""\btimes\b"""), "*")
            .replace(Regex("""\bdivided by\b"""), "/")
            .replace(Regex("""\bover\b"""), "/")
            .replace(Regex("""\bpi\b"""), Math.PI.toString())

        return s.trim()
    }

    private fun formatNumber(value: Double): String {
        val bd = BigDecimal.valueOf(value)
            .setScale(6, RoundingMode.HALF_UP)
            .stripTrailingZeros()
        return bd.toPlainString()
    }

    private class Parser(private val s: String) {
        var pos: Int = 0

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun eat(ch: Char): Boolean {
            skipWhitespace()
            if (pos < s.length && s[pos] == ch) {
                pos++
                return true
            }
            return false
        }

        fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                x = when {
                    eat('+') -> x + parseTerm()
                    eat('-') -> x - parseTerm()
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parsePower()
            while (true) {
                x = when {
                    eat('*') -> x * parsePower()
                    eat('/') -> {
                        val d = parsePower()
                        if (d == 0.0) throw ArithmeticException("Division by zero")
                        x / d
                    }
                    else -> return x
                }
            }
        }

        private fun parsePower(): Double {
            val base = parseFactor()
            return if (eat('^')) base.pow(parsePower()) else base
        }

        private fun parseFactor(): Double {
            if (eat('+')) return parseFactor()
            if (eat('-')) return -parseFactor()
            skipWhitespace()

            if (eat('(')) {
                val x = parseExpression()
                if (!eat(')')) throw IllegalArgumentException("Missing closing parenthesis")
                return x
            }

            if (s.startsWith("sqrt", pos)) {
                pos += 4
                val v = parseFactor()
                if (v < 0.0) throw ArithmeticException("Square root of negative number")
                return sqrt(v)
            }

            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (pos == start) {
                throw IllegalArgumentException("Expected number at position $pos")
            }
            return s.substring(start, pos).toDouble()
        }
    }
}
