package com.ghost.api.database

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class MemoryManager(private val context: Context) {

    private val db = MemoryDatabase.getDatabase(context)
    private val conversationDao = db.conversationDao()
    private val diaryDao = db.diaryDao()
    private val semanticFactDao = db.semanticFactDao()

    private val sessionMemoryFile = java.io.File(context.filesDir, "session_memory.txt")
    @Volatile private var lastExternalBackupTime = 0L

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

    private fun maybeBackupDatabase(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastExternalBackupTime > 5 * 60 * 1000L) {
            lastExternalBackupTime = now
            MemoryDatabase.backupDatabaseToExternal(context)
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
            Timber.w(e, "Failed to append to external memory_log.txt")
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
        conversationDao.trimOldTurns(keepCount = 200)
        appendToMemoryLog("CHAT", "User: ${turn.userMessage} ↔ Gemma: ${turn.assistantResponse}")
        maybeBackupDatabase(force = false)
    }

    suspend fun getFormattedHistory(limit: Int = 10): String = withContext(Dispatchers.IO) {
        val turns = conversationDao.getAllRecentTurns(limit)
        turns.reversed().joinToString("\n") { turn ->
            val u = if (turn.userMessage.isNotBlank()) "User: ${turn.userMessage}" else ""
            val a = if (turn.assistantResponse.isNotBlank()) "Assistant: ${turn.assistantResponse}" else ""
            listOf(u, a).filter { it.isNotBlank() }.joinToString("\n")
        }
    }

    suspend fun getSessionHistory(limit: Int = 50): List<ConversationTurn> = withContext(Dispatchers.IO) {
        conversationDao.getAllRecentTurns(limit)
    }

    suspend fun searchMemory(query: String): List<ConversationTurn> = withContext(Dispatchers.IO) {
        try {
            conversationDao.searchByKeyword(query)
        } catch (e: Exception) {
            Timber.e(e, "Search failed")
            emptyList()
        }
    }

    suspend fun storeSemanticFact(title: String, content: String, factType: String = "factoid") = withContext(Dispatchers.IO) {
        val fact = SemanticFact(
            extractedAt = System.currentTimeMillis(),
            factType = factType,
            subject = title,
            predicate = "is",
            object_ = content,
            confidence = 1.0f,
            sourceConversationId = -1L
        )
        semanticFactDao.insertFact(fact)
        semanticFactDao.trimOldFacts(keepCount = 150)
        appendToMemoryLog("FACT", "$title: $content")
        maybeBackupDatabase(force = true)
    }

    suspend fun searchSemanticFacts(query: String): List<SemanticFact> = withContext(Dispatchers.IO) {
        try {
            semanticFactDao.searchByKeyword(query)
        } catch (e: Exception) {
            try {
                semanticFactDao.searchFacts(query)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun getRecentDiaryEntries(limit: Int = 50): List<DiaryEntry> = withContext(Dispatchers.IO) {
        diaryDao.getRecentEntries(limit)
    }

    suspend fun addDiaryEntry(entry: DiaryEntry) = withContext(Dispatchers.IO) {
        diaryDao.insertEntry(entry)
        diaryDao.trimOldEntries(keepCount = 25)
        appendToMemoryLog(entry.eventType, entry.observation)
        maybeBackupDatabase(force = true)
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
        maybeBackupDatabase(force = true)
    }

    /**
     * Cleans up duplicate diary entries that were historically written into semantic_facts,
     * prunes old rows across tables, and caps external memory_log.txt size.
     */
    suspend fun runDatabaseCleanup() = withContext(Dispatchers.IO) {
        try {
            val purged = semanticFactDao.purgeLegacyDuplicateDiaryFacts()
            if (purged > 0) {
                Timber.i("🧹 MemoryManager: Purged $purged duplicate diary entries from semantic_facts")
            }
            conversationDao.trimOldTurns(keepCount = 200)
            semanticFactDao.trimOldFacts(keepCount = 150)
            diaryDao.trimOldEntries(keepCount = 25)

            if (externalLogFile.exists() && externalLogFile.length() > 256 * 1024L) {
                synchronized(this@MemoryManager) {
                    val kept = externalLogFile.readLines(Charsets.UTF_8).takeLast(400)
                    externalLogFile.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Database cleanup pass encountered non-fatal error")
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        conversationDao.deleteAll()
        diaryDao.deleteAll()
        semanticFactDao.deleteAll()
    }

    fun close() {
        if (db.isOpen) {
            db.close()
        }
    }
}
