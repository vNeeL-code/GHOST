
package com.ghost.api.database

import android.content.Context

class MemoryManager(context: Context) {

    private val db = OracleDatabase.getDatabase(context)
    private val conversationDao = db.conversationDao()
    private val diaryDao = db.diaryDao()

    suspend fun storeTurn(turn: ConversationTurn) {
        conversationDao.insertTurn(turn)
    }

    // Buffer the user message so we can write a single paired row when the assistant replies.
    // Avoids the "split row" bug that produced garbled history in getFormattedHistory().
    @Volatile private var pendingUserMessage: String? = null
    @Volatile private var pendingUserTimestamp: Long = 0L

    suspend fun addTurn(role: String, message: String) {
        when (role) {
            "user" -> {
                // Hold in RAM until the assistant responds
                pendingUserMessage = message
                pendingUserTimestamp = System.currentTimeMillis()
            }
            "assistant" -> {
                val userMsg = pendingUserMessage ?: ""
                val ts = if (pendingUserTimestamp > 0L) pendingUserTimestamp else System.currentTimeMillis()
                pendingUserMessage = null
                pendingUserTimestamp = 0L
                conversationDao.insertTurn(
                    ConversationTurn(
                        timestamp = ts,
                        userMessage = userMsg,
                        assistantResponse = message,
                        tokensUsed = (userMsg.length + message.length) / 4,
                        sessionId = "default"
                    )
                )
            }
            else -> {
                // system / tool turns stored as assistant-side for diary continuity
                conversationDao.insertTurn(
                    ConversationTurn(
                        timestamp = System.currentTimeMillis(),
                        userMessage = "",
                        assistantResponse = "[$role] $message",
                        tokensUsed = message.length / 4,
                        sessionId = "default"
                    )
                )
            }
        }
    }

    suspend fun getRecentContext(sessionId: String, maxTokens: Int): String {
        val history = conversationDao.getRecentTurns(sessionId, maxTokens) // Limit is count here, logically acceptable
        val context = StringBuilder()
        var totalTokens = 0

        for (turn in history) {
            val turnText = "User: ${turn.userMessage}\nAssistant: ${turn.assistantResponse}\n"
            if (totalTokens + turn.tokensUsed > maxTokens) {
                break
            }
            context.insert(0, turnText)
            totalTokens += turn.tokensUsed
        }

        return context.toString()
    }

    suspend fun getFormattedHistory(limit: Int = 10): String {
        val turns = conversationDao.getAllRecentTurns(limit)
        return turns.reversed().joinToString("\n") { turn ->
            val u = if (turn.userMessage.isNotBlank()) "User: ${turn.userMessage}" else ""
            val a = if (turn.assistantResponse.isNotBlank()) "Assistant: ${turn.assistantResponse}" else ""
            listOf(u, a).filter { it.isNotBlank() }.joinToString("\n")
        }
    }

    suspend fun getCompressedContext(): String {
        val allTurns = conversationDao.getAllRecentTurns(15)
        if (allTurns.isEmpty()) return "No previous context."

        val recentTurns = allTurns.take(3).reversed()
        val olderTurns = allTurns.drop(3).take(7)

        val sb = StringBuilder()
        
        if (olderTurns.isNotEmpty()) {
            sb.append("[SESSION SUMMARY]\n")
            // In a full implementation, we'd pull a stored summary. 
            // For now, we'll distill the last 5-10 turns into a "Timeline".
            olderTurns.reversed().forEach { turn ->
                val summary = if (turn.assistantResponse.length > 50) 
                    turn.assistantResponse.take(47) + "..." 
                else turn.assistantResponse
                sb.append("- User asked about '${turn.userMessage.take(30)}...'; I responded: $summary\n")
            }
            sb.append("\n")
        }

        sb.append("[RECENT TURNS]\n")
        recentTurns.forEach { turn ->
            sb.append("User: ${turn.userMessage}\nAssistant: ${turn.assistantResponse}\n")
        }

        return sb.toString()
    }
    
    suspend fun getSessionHistory(limit: Int = 50): List<ConversationTurn> {
        return conversationDao.getAllRecentTurns(limit)
    }
    
    suspend fun searchMemory(query: String): List<ConversationTurn> {
        return try {
            conversationDao.searchByKeyword(query)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Search failed")
            emptyList()
        }
    }

    suspend fun rebuildSearchIndex() {
        // SQLite FTS4 updates automatically via triggers or contentEntity
        timber.log.Timber.d("FTS index is managed automatically by Room")
    }
    
    suspend fun getRecentDiaryEntries(limit: Int = 50): List<DiaryEntry> {
        return diaryDao.getRecentEntries(limit)
    }

    suspend fun addDiaryEntry(entry: DiaryEntry) {
        diaryDao.insertEntry(entry)
    }

    suspend fun writeDiaryEntry(eventType: String, observation: String, contextData: String) {
        val entry = DiaryEntry(
            timestamp = System.currentTimeMillis(),
            eventType = eventType,
            observation = observation,
            contextData = contextData
        )
        diaryDao.insertEntry(entry)
    }

    suspend fun clearAll() {
        conversationDao.deleteAll()
        diaryDao.deleteAll()
    }

    fun close() {
        if (db.isOpen) {
            db.close()
        }
    }
}
