package com.ghost.api.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Conversation turn storage
 * Stores every user message and assistant response
 */
@Entity(tableName = "conversations")
data class ConversationTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val userMessage: String,
    val assistantResponse: String,
    val tokensUsed: Int,
    val sessionId: String,  // For grouping related conversations
    val tokenHash: String = "", // Detect content changes without re-tokenizing
    val imageUri: String? = null,
    val webviewUrl: String? = null,
    val webviewAspectRatio: Float? = null
)

@androidx.room.Fts4(contentEntity = ConversationTurn::class)
@Entity(tableName = "conversations_fts")
data class ConversationTurnFts(
    val userMessage: String,
    val assistantResponse: String
)

/**
 * Semantic facts extracted from conversations
 * E.g., "user likes purple", "user's name is Neil", etc.
 */
@Entity(tableName = "semantic_facts")
data class SemanticFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val extractedAt: Long,
    val factType: String,  // "preference", "relationship", "event", "skill", "goal"
    val subject: String,
    val predicate: String,
    val object_: String,
    val confidence: Float,
    val sourceConversationId: Long
)

@androidx.room.Fts4(contentEntity = SemanticFact::class)
@Entity(tableName = "semantic_facts_fts")
data class SemanticFactFts(
    val subject: String,
    val predicate: String,
    val object_: String
)

/**
 * Agent state tracking (metadata, not roleplay)
 * E.g., battery level observations, system status annotations
 */
@Entity(tableName = "agent_state")
data class AgentState(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long
)

/**
 * Agent diary entries (background observations)
 * Gemma's first-person journal of device context
 */
@Entity(tableName = "diary_entries")
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val eventType: String,  // "battery_low", "app_installed", "photo_taken", etc.
    val observation: String,  // Factual observation, no roleplay
    val contextData: String  // JSON metadata
)

/**
 * Semantic vector sidecar storage (Matryoshka 256-dim float32 blobs).
 * Scoped by [modelTag] so vectors from different embedding checkpoints are never compared.
 * Supports FACT, DIARY, TURN, IMAGE (16-24GB multimodal), and WIKI entries.
 */
@Entity(
    tableName = "memory_embeddings",
    indices = [androidx.room.Index(value = ["sourceType", "modelTag"])]
)
data class MemoryEmbedding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: String,   // "FACT", "DIARY", "TURN", "IMAGE", "WIKI"
    val sourceId: Long,
    val textPreview: String,
    val modelTag: String,     // e.g. "hash-256", "embeddinggemma-270m-256", "embeddinggemma-mm-256"
    val dims: Int,
    val vectorBlob: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryEmbedding) return false
        return id == other.id && sourceType == other.sourceType && sourceId == other.sourceId &&
            modelTag == other.modelTag && dims == other.dims && vectorBlob.contentEquals(other.vectorBlob)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sourceType.hashCode()
        result = 31 * result + sourceId.hashCode()
        result = 31 * result + modelTag.hashCode()
        result = 31 * result + vectorBlob.contentHashCode()
        return result
    }
}
