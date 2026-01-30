
package com.gemma.api.database

import android.content.Context

class MemoryManager(context: Context) {

    private val db = OracleDatabase.getDatabase(context)
    private val conversationDao = db.conversationDao()
    private val diaryDao = db.diaryDao()

    suspend fun storeTurn(turn: ConversationTurn) {
        conversationDao.insertTurn(turn)
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
    
    suspend fun getSessionHistory(limit: Int = 50): List<ConversationTurn> {
        return conversationDao.getAllRecentTurns(limit)
    }
    
    suspend fun searchMemory(query: String): List<ConversationTurn> {
        return try {
            // Using LIKE search (FTS disabled temporarily)
            conversationDao.searchByKeyword(query)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Search failed")
            emptyList()
        }
    }

    suspend fun rebuildSearchIndex() {
        // FTS disabled temporarily
        timber.log.Timber.d("FTS rebuild skipped - FTS disabled")
    }
    
    suspend fun getRecentDiaryEntries(limit: Int = 50): List<DiaryEntry> {
        return diaryDao.getRecentEntries(limit)
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
}
