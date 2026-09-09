package com.ghost.api

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GemmaEngine - LiteRT-LM based multimodal inference engine
 */
class GemmaEngine(private val context: Context) : LlmBackend {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val sessionMutex = Mutex()

    @Volatile private var isResetting = false
    private var toolSets: List<ToolSet> = emptyList()
    private val isBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    
    // Sampler config for inference
    private var samplerConfig: SamplerConfig = SamplerConfig(
        topK = 40,
        topP = 0.95,
        temperature = 0.8
    )

    override var activeBackend: String? = null
        private set

    private var lastModelPath: String = ""
    private var lastSystemPrompt: String = ""
    private var lastVisionEnabled: Boolean = true
    private var lastAudioEnabled: Boolean = true

    @OptIn(ExperimentalApi::class)
    suspend fun initialize(
        modelPath: String,
        systemPrompt: String,
        enableVision: Boolean = true,
        enableAudio: Boolean = true,
        toolSets: List<ToolSet> = emptyList(),
        forcedBackend: String? = null
    ): String? {
        // Cache these for potential hard resets
        this.lastModelPath = modelPath
        this.lastSystemPrompt = systemPrompt
        this.lastVisionEnabled = enableVision
        this.lastAudioEnabled = enableAudio
        this.toolSets = toolSets

        return sessionMutex.withLock {
            initializeInternal(modelPath, systemPrompt, enableVision, enableAudio, toolSets, forcedBackend)
        }
    }

    /**
     * Core initialization logic — must be called while sessionMutex is already held.
     * Extracted to avoid deadlock when called from hardReset() which also holds the mutex.
     */
    @OptIn(ExperimentalApi::class)
    private fun initializeInternal(
        modelPath: String,
        systemPrompt: String,
        enableVision: Boolean,
        enableAudio: Boolean,
        toolSets: List<ToolSet>,
        forcedBackend: String? = null
    ): String? {
        return runCatching {
            Timber.i("Initializing Gemma Engine...")
            Engine.setNativeMinLogSeverity(LogSeverity.INFO)

            val sharedGpuBackend = Backend.GPU()
            
            val backendsToTry = when (forcedBackend?.uppercase()) {
                "CPU" -> listOf("CPU" to Backend.CPU())
                "GPU" -> listOf("GPU" to sharedGpuBackend)
                else -> listOf("GPU" to sharedGpuBackend, "CPU" to Backend.CPU())
            }
            
            var lastError: Exception? = null
            for ((backendName, preferredBackend) in backendsToTry) {
                val isGpu = backendName == "GPU"
                val visionBackend = if (enableVision) {
                    if (isGpu) sharedGpuBackend else Backend.CPU()
                } else null

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = preferredBackend,  // Main inference backend
                    visionBackend = visionBackend,  // Match CPU/GPU mode cleanly
                    audioBackend = if (enableAudio) Backend.CPU() else null,    // must be CPU for Gemma
                    maxNumTokens = Constants.MAX_TOKENS,
                    cacheDir = context.codeCacheDir.absolutePath  // Private internal storage (safe from Samsung Knox SELinux sandbox blocks)
                )

                try {
                    val newEngine = Engine(engineConfig)
                    newEngine.initialize()

                    val conversationConfig = ConversationConfig(
                        samplerConfig = samplerConfig,
                        systemInstruction = if (systemPrompt.isNotBlank()) Contents.of(systemPrompt) else null,
                        tools = toolSets.map { tool(it) }
                    )

                    val newConversation = newEngine.createConversation(conversationConfig)

                    engine?.close()
                    conversation?.close()
                    engine = newEngine
                    conversation = newConversation

                    activeBackend = "LiteRT-LM ($backendName)"
                    Timber.i("GemmaEngine initialized successfully on $backendName")
                    return null // Success!
                } catch (e: Exception) {
                    Timber.w(e, "Native Engine Initialization Failed for $backendName")
                    lastError = e
                    // Continue to the next backend in the loop
                }
            }
            
            // If we exhausted all backends
            val errorMsg = lastError?.message ?: "Unknown fatal error"
            Timber.e("All backends failed to initialize. Last error: $errorMsg")
            return errorMsg
        }.getOrElse { it.message ?: "Unknown fatal error" }
    }

    override suspend fun generateResponse(
        prompt: String,
        images: List<Bitmap>,
        audioData: ByteArray?
    ): String {
        var finalResponse = ""
        var error: String? = null
        streamResponse(
            prompt = prompt,
            images = images,
            audioData = audioData,
            onToken = {},
            onComplete = { finalResponse = it },
            onError = { error = it }
        )
        error?.let { throw Exception(it) }
        return finalResponse
    }

    override suspend fun streamResponse(
        prompt: String,
        images: List<Bitmap>,
        audioData: ByteArray?,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val startTime = System.currentTimeMillis()
        val deferred = CompletableDeferred<Unit>()
        
        // Use a lock ONLY to start the stream and verify state
        sessionMutex.withLock {
            if (isBusy.getAndSet(true)) {
                onError("Engine is currently busy with another inference.")
                return@withLock
            }
            if (conversation == null) {
                isBusy.set(false)
                onError("Engine not initialized")
                return@withLock
            }
        }

        try {
            val contents = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val list = mutableListOf<Content>()
                
                for (image in images) {
                    list.add(Content.ImageBytes(image.toJpegByteArray()))
                }
                audioData?.let {
                    list.add(Content.AudioBytes(it))
                }
                // add the text after image and audio for the accurate last token
                if (prompt.isNotBlank()) {
                    list.add(Content.Text(prompt))
                }
                list
            }

            var fullResponse = ""
            conversation?.sendMessageAsync(
                Contents.of(contents),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val token = message.toString()
                        
                        if (fullResponse.isEmpty()) {
                            Timber.i("⏱️ First token received after ${System.currentTimeMillis() - startTime}ms")
                        }
                        fullResponse += token
                        onToken(token)
                    }
                    override fun onDone() {
                        try {
                            onComplete(truncateRepetition(fullResponse))
                        } finally {
                            isBusy.set(false)
                            deferred.complete(Unit)
                        }
                    }
                    override fun onError(throwable: Throwable) {
                        try {
                            onError(throwable.message ?: "Stream error")
                        } finally {
                            isBusy.set(false)
                            deferred.complete(Unit)
                        }
                    }
                }
            )
            
            // Wait for completion outside the mutex - using await() instead of polling
            kotlinx.coroutines.withTimeout(240000) {
                deferred.await()
            }
        } finally {
            isBusy.set(false)
            if (!deferred.isCompleted) deferred.complete(Unit)
        }
    }



    override suspend fun softReset(systemPrompt: String, newToolSets: List<ToolSet>?, initialMessages: List<com.google.ai.edge.litertlm.Message>?) {
        lastSystemPrompt = systemPrompt
        if (newToolSets != null) {
            this.toolSets = newToolSets
        }
        sessionMutex.withLock {
            val eng = engine ?: return@withLock
            try {
                conversation?.close()
                val config = ConversationConfig(
                    samplerConfig = samplerConfig,
                    systemInstruction = if (systemPrompt.isNotBlank()) Contents.of(systemPrompt) else null,
                    tools = toolSets.map { tool(it) },
                    initialMessages = initialMessages ?: emptyList()
                )
                conversation = eng.createConversation(config)
                Timber.i("Soft reset complete.")
            } catch (e: Exception) {
                Timber.e(e, "Soft reset failed")
            }
        }
    }

    override suspend fun hardReset() {
        if (lastModelPath.isBlank()) return
        sessionMutex.withLock {
            try {
                conversation?.close()
                engine?.close()
                conversation = null
                engine = null
                initializeInternal(lastModelPath, lastSystemPrompt, lastVisionEnabled, lastAudioEnabled, toolSets)
            } catch (e: Exception) {
                Timber.e(e, "Hard reset failure")
            }
        }
    }

    override suspend fun generateOneShot(prompt: String, systemPrompt: String?, temperature: Double?): String {
        return sessionMutex.withLock {
            // Guard: wait briefly if a streaming inference is in flight
            var retries = 0
            while (isBusy.get() && retries < 15) {
                kotlinx.coroutines.delay(200)
                retries++
            }
            if (isBusy.getAndSet(true)) {
                return@withLock "Error: Engine busy with active stream"
            }

            try {
                val eng = engine ?: return@withLock "Error: Engine not initialized"
                suspendCancellableCoroutine { continuation ->
                    try {
                        val temp = temperature ?: 0.1
                        val config = ConversationConfig(
                            samplerConfig = SamplerConfig(topK = if (temp < 0.3) 1 else 40, topP = if (temp < 0.3) 0.1 else 0.95, temperature = temp),
                            systemInstruction = Contents.of(systemPrompt ?: "You are a concise observer.")
                        )
                        val tempConv = eng.createConversation(config)
                        val responseBuilder = StringBuilder()
                        tempConv.sendMessageAsync(
                            Contents.of(listOf(Content.Text(prompt))),
                            object : MessageCallback {
                                override fun onMessage(message: Message) { responseBuilder.append(message.toString()) }
                                override fun onDone() {
                                    val resp = responseBuilder.toString()
                                    tempConv.close()
                                    continuation.resume(resp)
                                }
                                override fun onError(throwable: Throwable) {
                                    tempConv.close()
                                    continuation.resumeWithException(throwable)
                                }
                            }
                        )
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            } finally {
                isBusy.set(false)
            }
        }
    }

    private fun truncateRepetition(response: String): String {
        if (response.length < 100) return response
        
        // 1. Sentence-level repetition
        val sentences = response.split(Regex("""(?<=[.!?])\s+""")).filter { it.length >= 15 }
        if (sentences.size >= 10) {
            val seen = java.util.HashSet<String>()
            for ((i, sentence) in sentences.withIndex()) {
                val normalized = sentence.trim().lowercase()
                if (!seen.add(normalized) && i > 5) {
                    return sentences.take(i).joinToString(" ") + "\n\n(...loop detected)"
                }
            }
        }
        
        // 2. Word-level repetition ("beambeambeambeam")
        val wordPattern = Regex("""(.{3,15}?)\1{10,}""") // A 3-15 char string repeated 10+ times
        val match = wordPattern.find(response)
        if (match != null) {
            return response.substring(0, match.range.first) + "\n\n(...loop detected)"
        }
        
        return response
    }

    override suspend fun cleanup() {
        // Mark busy upfront to prevent new inferences from queueing during teardown
        isBusy.set(true)
        val acquired = kotlinx.coroutines.withTimeoutOrNull(5000) {
            sessionMutex.lock()
            true
        } ?: false

        try {
            if (!acquired) {
                Timber.w("sessionMutex lock timed out during cleanup, proceeding with safe close")
            }
            conversation?.close()
            engine?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing engine/conversation")
        } finally {
            conversation = null
            engine = null
            if (acquired) sessionMutex.unlock()
            isBusy.set(false)
        }
    }

    private fun Bitmap.toJpegByteArray(): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        // Rigid 1024 max dimension to prevent MAX_TOKENS OOM during native injection
        val maxDim = 1024
        val scaledBitmap = if (this.width > maxDim || this.height > maxDim) {
            val ratio = Math.min(maxDim.toFloat() / this.width, maxDim.toFloat() / this.height)
            Bitmap.createScaledBitmap(this, (this.width * ratio).toInt(), (this.height * ratio).toInt(), true)
        } else {
            this
        }
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        if (scaledBitmap != this) scaledBitmap.recycle()
        return stream.toByteArray()
    }
}
