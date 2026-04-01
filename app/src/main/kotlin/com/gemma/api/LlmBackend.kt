package com.gemma.api

import android.graphics.Bitmap

interface LlmBackend {
    val activeBackend: String?
    fun softReset(systemPrompt: String)
    fun hardReset()
    fun cleanup()
    
    suspend fun generateOneShot(prompt: String): String
    
    suspend fun generateResponse(
        prompt: String,
        images: List<Bitmap> = emptyList(),
        audioData: ByteArray? = null
    ): String
    
    fun streamResponse(
        prompt: String,
        images: List<Bitmap> = emptyList(),
        audioData: ByteArray? = null,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    )
}
