package com.ghost.api.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: ConversationTurn): Long

    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTurns(sessionId: String, limit: Int = 20): List<ConversationTurn>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getAllRecentTurns(limit: Int = 50): List<ConversationTurn>

    @Query("""
        SELECT c.* FROM conversations c
        JOIN conversations_fts fts ON c.id = fts.rowid
        WHERE conversations_fts MATCH :keyword
        ORDER BY c.timestamp DESC
    """)
    suspend fun searchByKeyword(keyword: String): List<ConversationTurn>

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface SemanticFactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFact(fact: SemanticFact): Long

    @Query("SELECT * FROM semantic_facts WHERE factType = :type ORDER BY extractedAt DESC")
    suspend fun getFactsByType(type: String): List<SemanticFact>

    @Query("SELECT * FROM semantic_facts WHERE subject LIKE '%' || :query || '%' OR object_ LIKE '%' || :query || '%' ORDER BY confidence DESC")
    suspend fun searchFacts(query: String): List<SemanticFact>

    @Query("SELECT * FROM semantic_facts ORDER BY extractedAt DESC LIMIT :limit")
    suspend fun getRecentFacts(limit: Int = 100): List<SemanticFact>
}

@Dao
interface AgentStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setState(state: AgentState): Long

    @Query("SELECT * FROM agent_state WHERE `key` = :key")
    suspend fun getState(key: String): AgentState?

    @Query("SELECT * FROM agent_state ORDER BY updatedAt DESC")
    suspend fun getAllStates(): List<AgentState>
}

@Dao
interface DiaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: DiaryEntry): Long

    @Query("SELECT * FROM diary_entries ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEntries(limit: Int = 50): List<DiaryEntry>

    @Query("SELECT * FROM diary_entries WHERE eventType = :type ORDER BY timestamp DESC")
    suspend fun getEntriesByType(type: String): List<DiaryEntry>

    @Query("DELETE FROM diary_entries")
    suspend fun deleteAll()
}
