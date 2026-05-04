package com.ghost.api

import android.graphics.Bitmap

interface LlmBackend {
    val activeBackend: String?
    suspend fun softReset(systemPrompt: String)
    suspend fun hardReset()
    suspend fun cleanup()
    
    suspend fun generateOneShot(prompt: String): String
    
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
