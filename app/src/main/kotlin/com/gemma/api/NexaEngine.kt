package com.gemma.api

import android.content.Context
import android.graphics.Bitmap
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.ModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * NexaEngine - GGUF based inference engine (NPU/GPU/CPU)
 *
 * Utilizes the Nexa SDK to run open weights natively (.gguf, .nexa)
 */
class NexaEngine(private val context: Context) : LlmBackend {

    private var wrapper: LlmWrapper? = null
    private val sessionLock = Object()
    
    @Volatile private var isResetting = false

    override var activeBackend: String? = null
        private set

    private var lastModelPath: String = ""
    private var lastSystemPrompt: String = ""

    private val engineScope = CoroutineScope(Dispatchers.IO + Job())

    fun initialize(
        modelPath: String,
        systemPrompt: String,
        enableVision: Boolean = false,
        enableAudio: Boolean = false // Nexa LLMs don't natively do audio yet
    ): String? {
        return runCatching {
            Timber.i("Initializing NexaEngine with GGUF Support...")
            Timber.i("Model: $modelPath")
            
            lastModelPath = modelPath
            lastSystemPrompt = systemPrompt

            // Initialize global SDK
            NexaSdk.getInstance().init(context)

            // Setup the Wrapper builder
            val createInput = LlmCreateInput(
                model_name = "local_gguf",
                model_path = modelPath,
                config = ModelConfig(
                    max_tokens = 4096, 
                    enable_thinking = false
                ),
                plugin_id = "cpu" 
            )

            // Depending on the version, the builder returns a Result
            // We use standard construction
            runCatching {
                wrapper = kotlinx.coroutines.runBlocking {
                    LlmWrapper.builder()
                        .llmCreateInput(createInput)
                        .build()
                        .getOrThrow()
                }
            }.getOrThrow()

            // System prompt is usually appended to History or Chat template, 
            // but for raw completion inference, we prepend it manually on turn 1.

            activeBackend = "Nexa SDK (GGUF Engine)"
            Timber.i("✓ NexaEngine initialized successfully!")
            null
        }.getOrElse { e ->
            Timber.e(e, "Fatal: Could not initialize NexaEngine")
            e.message ?: "Unknown fatal error"
        }
    }

    override suspend fun generateResponse(
        prompt: String,
        images: List<Bitmap>,
        audioData: ByteArray?
    ): String {
        val currentWrapper = synchronized(sessionLock) { wrapper } 
            ?: return "(；￣Д￣) I'm not loaded yet... give me a sec"

        return try {
            withTimeout(90_000L) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        val fullPrompt = if (lastSystemPrompt.isNotBlank()) {
                            "$lastSystemPrompt\n\nUser: $prompt\nAssistant: "
                        } else {
                            prompt
                        }

                        val genConfig = GenerationConfig()
                        
                        engineScope.launch {
                            val responseBuilder = StringBuilder()
                            currentWrapper.generateStreamFlow(fullPrompt, genConfig)
                                .collect { result ->
                                    when (result) {
                                        is LlmStreamResult.Token -> {
                                            responseBuilder.append(result.text)
                                        }
                                        is LlmStreamResult.Completed -> {
                                            if (continuation.isActive) {
                                                continuation.resume(responseBuilder.toString())
                                            }
                                        }
                                        is LlmStreamResult.Error -> {
                                            if (continuation.isActive) {
                                                continuation.resumeWithException(result.throwable ?: Exception("Unknown error"))
                                            }
                                        }
                                    }
                                }
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Timber.w("Timeout during GGUF generation")
            "(┛✧Д✧))┛彡┻━┻ Timeout! NPU got stuck..."
        } catch (e: Exception) {
            Timber.e(e, "GGUF Generate failed")
            "Error: ${e.message}"
        }
    }

    override fun streamResponse(
        prompt: String,
        images: List<Bitmap>,
        audioData: ByteArray?,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val currentWrapper = synchronized(sessionLock) { wrapper }
        if (currentWrapper == null) {
            onError("Not loaded")
            return
        }

        val fullPrompt = if (lastSystemPrompt.isNotBlank()) {
            "$lastSystemPrompt\n\nUser: $prompt\nAssistant: "
        } else {
            prompt
        }

        engineScope.launch {
            try {
                val responseBuilder = StringBuilder()
                currentWrapper.generateStreamFlow(fullPrompt, GenerationConfig())
                    .collect { result ->
                        when (result) {
                            is LlmStreamResult.Token -> {
                                responseBuilder.append(result.text)
                                onToken(result.text)
                            }
                            is LlmStreamResult.Completed -> {
                                onComplete(responseBuilder.toString())
                            }
                            is LlmStreamResult.Error -> {
                                onError(result.throwable?.message ?: "Unknown error")
                            }
                        }
                    }
            } catch (e: Exception) {
                Timber.e(e, "GGUF Stream failed")
                onError(e.message ?: "Unknown error")
            }
        }
    }

    override fun softReset(systemPrompt: String) {
        Timber.i("NexaEngine Soft Reset")
        runCatching {
            synchronized(sessionLock) {
                engineScope.launch { wrapper?.reset() }
            }
        }
    }

    override fun hardReset() {
        if (lastModelPath.isBlank()) return
        isResetting = true
        Timber.w("🚨 Initiating HARD RESET of NexaEngine 🚨")
        try {
            cleanup()
            initialize(lastModelPath, lastSystemPrompt)
            Timber.i("✅ Hard reset complete. Engine fully rebuilt.")
        } catch (e: Exception) {
            Timber.e(e, "Fatal failure during hard reset")
        } finally {
            isResetting = false
        }
    }

    override suspend fun generateOneShot(prompt: String): String {
        return generateResponse(prompt)
    }

    override fun cleanup() {
        runCatching {
            synchronized(sessionLock) {
                engineScope.launch { wrapper?.stopStream() }
                // Assuming Nexa wrapper has a close/destroy method if needed
                // wrapper?.close()
                wrapper = null
            }
        }.onFailure { Timber.e(it, "Cleanup error") }
        Timber.i("Nexa Cleanup done")
    }
}
