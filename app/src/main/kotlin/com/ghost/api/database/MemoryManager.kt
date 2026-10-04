
package com.ghost.api.database

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MemoryManager(private val context: Context) {

    private val db = MemoryDatabase.getDatabase(context)
    private val conversationDao = db.conversationDao()
    private val diaryDao = db.diaryDao()
    private val semanticFactDao = db.semanticFactDao()
    
    private val sessionMemoryFile = java.io.File(context.filesDir, "session_memory.txt")

    val externalLogFile: java.io.File by lazy {
        try {
            val docs = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            )
            val ghostDir = java.io.File(docs, "GHOST")
            if (!ghostDir.exists()) ghostDir.mkdirs()
            java.io.File(ghostDir, "memory_log.txt")
        } catch (e: Exception) {
            val appExternal = context.getExternalFilesDir(null) ?: context.filesDir
            val ghostDir = java.io.File(appExternal, "GHOST")
            if (!ghostDir.exists()) ghostDir.mkdirs()
            java.io.File(ghostDir, "memory_log.txt")
        }
    }

    fun appendToMemoryLog(type: String, content: String) {
        try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val timestamp = sdf.format(java.util.Date())
            val sanitized = content.trim().replace("\r\n", "\n").replace("\n", " ⏎ ")
            val line = "[$timestamp] | $type | $sanitized\n"
            synchronized(this) {
                externalLogFile.appendText(line, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Failed to append to external memory_log.txt")
        }
    }

    suspend fun tailMemoryLog(limitLines: Int = 30): List<String> = withContext(Dispatchers.IO) {
        try {
            if (!externalLogFile.exists()) return@withContext emptyList()
            val lines = externalLogFile.readLines(Charsets.UTF_8)
            lines.takeLast(limitLines)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun grepMemoryLog(query: String, maxResults: Int = 20): List<String> = withContext(Dispatchers.IO) {
        try {
            if (!externalLogFile.exists() || query.isBlank()) return@withContext emptyList()
            val matches = mutableListOf<String>()
            externalLogFile.forEachLine(Charsets.UTF_8) { line ->
                if (line.contains(query, ignoreCase = true)) {
                    matches.add(line)
                    if (matches.size >= maxResults) return@forEachLine
                }
            }
            matches
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getCompactedSessionMemory(): String = withContext(Dispatchers.IO) {
        if (sessionMemoryFile.exists()) {
            sessionMemoryFile.readText()
        } else {
            ""
        }
    }

    suspend fun updateCompactedSessionMemory(newMemory: String) = withContext(Dispatchers.IO) {
        sessionMemoryFile.writeText(newMemory)
    }

    suspend fun clearSessionMemory() = withContext(Dispatchers.IO) {
        if (sessionMemoryFile.exists()) {
            sessionMemoryFile.delete()
        }
    }

    suspend fun storeTurn(turn: ConversationTurn) = withContext(Dispatchers.IO) {
        conversationDao.insertTurn(turn)
        appendToMemoryLog("CHAT", "User: ${turn.userMessage} ↔ Gemma: ${turn.assistantResponse}")
    }

    suspend fun getFormattedHistory(limit: Int = 10): String = withContext(Dispatchers.IO) {
        val turns = conversationDao.getAllRecentTurns(limit)
        turns.reversed().joinToString("\n") { turn ->
            val u = if (turn.userMessage.isNotBlank()) "User: ${turn.userMessage}" else ""
            val a = if (turn.assistantResponse.isNotBlank()) "Assistant: ${turn.assistantResponse}" else ""
            listOf(u, a).filter { it.isNotBlank() }.joinToString("\n")
        }
    }

    suspend fun getCompressedContext(): String = withContext(Dispatchers.IO) {
        val allTurns = conversationDao.getAllRecentTurns(15)
        if (allTurns.isEmpty()) return@withContext "No previous context."

        val recentTurns = allTurns.take(3).reversed()
        val olderTurns = allTurns.drop(3).take(7)

        val sb = StringBuilder()
        
        if (olderTurns.isNotEmpty()) {
            sb.append("[SESSION SUMMARY]\n")
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

        sb.toString()
    }
    
    suspend fun getSessionHistory(limit: Int = 50): List<ConversationTurn> = withContext(Dispatchers.IO) {
        conversationDao.getAllRecentTurns(limit)
    }
    
    suspend fun searchMemory(query: String): List<ConversationTurn> = withContext(Dispatchers.IO) {
        try {
            conversationDao.searchByKeyword(query)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Search failed")
            emptyList()
        }
    }

    suspend fun rebuildSearchIndex() = withContext(Dispatchers.IO) {
        timber.log.Timber.d("FTS index is managed automatically by Room")
    }

    suspend fun storeSemanticFact(title: String, content: String) = withContext(Dispatchers.IO) {
        val fact = SemanticFact(
            extractedAt = System.currentTimeMillis(),
            factType = "factoid",
            subject = title,
            predicate = "is",
            object_ = content,
            confidence = 1.0f,
            sourceConversationId = -1L
        )
        semanticFactDao.insertFact(fact)
        appendToMemoryLog("FACT", "$title: $content")
    }

    suspend fun searchSemanticFacts(query: String): List<SemanticFact> = withContext(Dispatchers.IO) {
        try {
            semanticFactDao.searchByKeyword(query)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Semantic Fact Search failed")
            emptyList()
        }
    }
    
    suspend fun getRecentDiaryEntries(limit: Int = 50): List<DiaryEntry> = withContext(Dispatchers.IO) {
        diaryDao.getRecentEntries(limit)
    }

    suspend fun addDiaryEntry(entry: DiaryEntry) = withContext(Dispatchers.IO) {
        diaryDao.insertEntry(entry)
        diaryDao.trimOldEntries(keepCount = 25)
        appendToMemoryLog(entry.eventType, entry.observation)
    }

    suspend fun writeDiaryEntry(eventType: String, observation: String, contextData: String) = withContext(Dispatchers.IO) {
        val entry = DiaryEntry(
            timestamp = System.currentTimeMillis(),
            eventType = eventType,
            observation = observation,
            contextData = contextData
        )
        diaryDao.insertEntry(entry)
        diaryDao.trimOldEntries(keepCount = 25)
        appendToMemoryLog(eventType, observation)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        conversationDao.deleteAll()
        diaryDao.deleteAll()
    }

    fun close() {
        if (db.isOpen) {
            db.close()
        }
    }
}
