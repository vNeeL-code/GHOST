package com.ghost.api

import android.graphics.Bitmap

interface LlmBackend {
    val activeBackend: String?
    val maxNumTokens: Int
    suspend fun softReset(systemPrompt: String, newToolSets: List<com.google.ai.edge.litertlm.ToolSet>? = null, initialMessages: List<com.google.ai.edge.litertlm.Message>? = null)
    suspend fun hardReset()
    suspend fun cleanup()
    
    suspend fun generateOneShot(prompt: String, systemPrompt: String? = null, temperature: Double? = null): String
    
    suspend fun generateResponse(
        prompt: String,
        images: List<Bitmap> = emptyList(),
        audioData: ByteArray? = null
    ): String
    
    suspend fun streamResponse(
        prompt: String,
        images: List<Bitmap> = emptyList(),
        audioData: ByteArray? = null,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    )
}
