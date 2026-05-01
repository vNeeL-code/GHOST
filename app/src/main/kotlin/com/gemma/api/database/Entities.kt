package com.gemma.api.database

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
    val tokenHash: String = "" // Detect content changes without re-tokenizing
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
