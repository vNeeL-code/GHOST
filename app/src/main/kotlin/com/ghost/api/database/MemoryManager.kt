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
    private val memoryEmbeddingDao = db.memoryEmbeddingDao()

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

    val externalDiaryJsonlFile: java.io.File by lazy {
        try {
            val docs = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            )
            val ghostDir = java.io.File(docs, "GHOST")
            if (!ghostDir.exists()) ghostDir.mkdirs()
            java.io.File(ghostDir, "diary_history.jsonl")
        } catch (e: Exception) {
            val appExternal = context.getExternalFilesDir(null) ?: context.filesDir
            val ghostDir = java.io.File(appExternal, "GHOST")
            if (!ghostDir.exists()) ghostDir.mkdirs()
            java.io.File(ghostDir, "diary_history.jsonl")
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
            com.ghost.api.ui.GhostHudDispatcher.bark(
                context = context,
                glyph = "⚠️ Memory log write failed",
                debounceKey = "memory_log_write_fail",
                debounceMs = 60_000L,
                durationMs = 3000L
            )
        }
    }

    fun appendDiaryJsonl(entry: DiaryEntry) {
        try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val json = org.json.JSONObject().apply {
                put("id", entry.id)
                put("timestamp", entry.timestamp)
                put("iso", sdf.format(java.util.Date(entry.timestamp)))
                put("eventType", entry.eventType)
                put("contextData", entry.contextData)
                put("observation", entry.observation)
            }
            synchronized(this) {
                externalDiaryJsonlFile.appendText(json.toString() + "\n", Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to append to external diary_history.jsonl")
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

    private suspend fun indexEmbedding(sourceType: String, sourceId: Long, text: String) {
        if (text.isBlank()) return
        try {
            val backend = com.ghost.api.logic.EmbeddingSidecar.activeBackend
            val vec = backend.embedText(text)
            val blob = com.ghost.api.logic.EmbeddingSidecar.packFloatArray(vec)
            memoryEmbeddingDao.insertEmbedding(
                MemoryEmbedding(
                    sourceType = sourceType,
                    sourceId = sourceId,
                    textPreview = text.take(280),
                    modelTag = backend.modelTag,
                    dims = backend.dimensions,
                    vectorBlob = blob
                )
            )
            memoryEmbeddingDao.trimOldEmbeddings(keepCount = 600)
        } catch (e: Exception) {
            Timber.w(e, "MemoryManager: Non-fatal embedding index error for $sourceType")
        }
    }

    /**
     * Cosine-similarity semantic search over stored 256-dim embeddings (scoped to the active modelTag).
     * Works for FACT, DIARY, TURN, IMAGE (16-24GB multimodal), and WIKI records.
     */
    suspend fun searchSemanticVectors(
        query: String,
        sourceType: String? = null,
        topK: Int = 8,
        minScore: Float = 0.22f
    ): List<Pair<MemoryEmbedding, Float>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val backend = com.ghost.api.logic.EmbeddingSidecar.activeBackend
            val queryVec = backend.embedText(query)
            val candidates = if (sourceType != null) {
                memoryEmbeddingDao.getByTypeForModel(sourceType, backend.modelTag, limit = 300)
            } else {
                memoryEmbeddingDao.getRecentForModel(backend.modelTag, limit = 500)
            }
            candidates.mapNotNull { row ->
                val vec = com.ghost.api.logic.EmbeddingSidecar.unpackFloatArray(row.vectorBlob)
                val score = com.ghost.api.logic.EmbeddingSidecar.cosineSimilarity(queryVec, vec)
                if (score >= minScore) row to score else null
            }
                .sortedByDescending { it.second }
                .take(topK)
        } catch (e: Exception) {
            Timber.w(e, "MemoryManager: Semantic vector search failed")
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
        val factId = semanticFactDao.insertFact(fact)
        semanticFactDao.trimOldFacts(keepCount = 150)
        indexEmbedding("FACT", factId, "$title: $content")
        appendToMemoryLog("FACT", "$title: $content")
        maybeBackupDatabase(force = true)
    }

    /**
     * Hybrid FTS + Semantic Vector recall:
     * Runs Room FTS/LIKE first, then supplements with cosine-matched semantic vectors so
     * paraphrased queries still find the right memory even when exact keywords miss.
     */
    suspend fun searchSemanticFacts(query: String): List<SemanticFact> = withContext(Dispatchers.IO) {
        val ftsResults = try {
            semanticFactDao.searchByKeyword(query)
        } catch (e: Exception) {
            semanticFactDao.searchFacts(query)
        }
        val vectorHits = searchSemanticVectors(query, sourceType = "FACT", topK = 6, minScore = 0.22f)
        if (vectorHits.isEmpty()) return@withContext ftsResults

        val existingIds = ftsResults.map { it.id }.toHashSet()
        val recentFactsById = semanticFactDao.getRecentFacts(150).associateBy { it.id }
        val merged = ftsResults.toMutableList()
        for ((emb, score) in vectorHits) {
            if (emb.sourceId !in existingIds) {
                val matchedFact = recentFactsById[emb.sourceId]
                if (matchedFact != null) {
                    merged.add(matchedFact.copy(confidence = score))
                    existingIds.add(matchedFact.id)
                }
            }
        }
        merged
    }

    suspend fun getRecentDiaryEntries(limit: Int = 50): List<DiaryEntry> = withContext(Dispatchers.IO) {
        diaryDao.getRecentEntries(limit)
    }

    suspend fun addDiaryEntry(entry: DiaryEntry) = withContext(Dispatchers.IO) {
        val entryId = diaryDao.insertEntry(entry)
        diaryDao.trimOldEntries(keepCount = 25)
        indexEmbedding("DIARY", entryId, "${entry.eventType}: ${entry.observation}")
        appendToMemoryLog(entry.eventType, entry.observation)
        appendDiaryJsonl(entry)
        maybeBackupDatabase(force = true)
    }

    suspend fun writeDiaryEntry(eventType: String, observation: String, contextData: String) = withContext(Dispatchers.IO) {
        val entry = DiaryEntry(
            timestamp = System.currentTimeMillis(),
            eventType = eventType,
            observation = observation,
            contextData = contextData
        )
        val entryId = diaryDao.insertEntry(entry)
        diaryDao.trimOldEntries(keepCount = 25)
        indexEmbedding("DIARY", entryId, "$eventType: $observation")
        appendToMemoryLog(eventType, observation)
        appendDiaryJsonl(entry)
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
            memoryEmbeddingDao.trimOldEmbeddings(keepCount = 600)

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
        memoryEmbeddingDao.deleteAll()
    }

    fun close() {
        if (db.isOpen) {
            db.close()
        }
    }
}
