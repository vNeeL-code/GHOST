package com.ghost.api.logic

import android.util.LruCache

/**
 * In-Memory Token Ledger to avoid "Count of Death" (SQL IO)
 * Uses simple approximation until real BPE vocab is loaded.
 */
class TokenLedger {
    // Cache for exact string matches
    private val cache = LruCache<String, Int>(1000)

    fun getTokenCount(text: String): Int {
        return cache.get(text) ?: estimateTokens(text).also { cache.put(text, it) }
    }

    private fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        // English average: 1 token ~= 4 chars.
        // For code/logs, it might be denser. 
        // Kimi suggested split(" ") lookups, but we'll use a robust heuristic for now.
        // Audit Fix: 3.5 was under-counting. 3.2 is safer (approx 0.31 tokens/char).
        return (text.length / 3.2).toInt().coerceAtLeast(1)
    }
    
    // Singleton for app-wide access
    companion object {
        val instance = TokenLedger()
    }
}
