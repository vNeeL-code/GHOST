package com.ghost.api

import android.content.Context
import android.graphics.Bitmap
import android.os.Vibrator
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runBlocking

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
    
    // Sampler config using Double as expected by the environment
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
            runCatching {
                Timber.i("Initializing Gemma Engine...")
                Engine.setNativeMinLogSeverity(LogSeverity.INFO)

                val npuBackend = Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
                val sharedGpuBackend = Backend.GPU()
                
                val backendsToTry = when (forcedBackend?.uppercase()) {
                    "CPU" -> listOf("CPU" to Backend.CPU())
                    "GPU" -> listOf("GPU" to sharedGpuBackend)
                    "NPU" -> listOf("NPU" to npuBackend)
                    else -> listOf("NPU" to npuBackend, "GPU" to sharedGpuBackend, "CPU" to Backend.CPU())
                }
                
                var lastError: Exception? = null
                for ((backendName, preferredBackend) in backendsToTry) {
                    val engineConfig = EngineConfig(
                        modelPath = modelPath,
                        backend = preferredBackend,  // Main inference backend
                        visionBackend = if (enableVision) sharedGpuBackend else null,  // reuse same GPU instance to prevent OOM
                        audioBackend = if (enableAudio) Backend.CPU() else null,    // must be CPU for Gemma 3n
                        maxNumTokens = Constants.MAX_TOKENS,
                        cacheDir = context.getExternalFilesDir(null)?.absolutePath
                    )

                    try {
                        val newEngine = Engine(engineConfig)
                        newEngine.initialize()

                        val conversationConfig = ConversationConfig(
                            samplerConfig = if (preferredBackend is Backend.NPU) null else samplerConfig,
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
            isAborted = false 
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
                        // Debug log to see if 'message' has channel info
                        Timber.v("DEBUG: onMessage token='$token'") 
                        
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
            kotlinx.coroutines.withTimeout(120000) {
                deferred.await()
            }
        } finally {
            // Audit 3.1: Guaranteed Busy Release
            isBusy.set(false)
            if (!deferred.isCompleted) deferred.complete(Unit)
        }
    }

    private var isAborted = false

    override suspend fun softReset(systemPrompt: String) {
        lastSystemPrompt = systemPrompt
        sessionMutex.withLock {
            val eng = engine ?: return@withLock
            try {
                conversation?.close()
                val config = ConversationConfig(
                    samplerConfig = samplerConfig,
                    systemInstruction = if (systemPrompt.isNotBlank()) Contents.of(systemPrompt) else null,
                    tools = toolSets.map { tool(it) }
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
                initialize(lastModelPath, lastSystemPrompt, lastVisionEnabled, lastAudioEnabled, toolSets)
            } catch (e: Exception) {
                Timber.e(e, "Hard reset failure")
            }
        }
    }

    override suspend fun generateOneShot(prompt: String): String {
        return sessionMutex.withLock {
            val eng = engine ?: return@withLock "Error: Engine not initialized"
            suspendCancellableCoroutine { continuation ->
                try {
                    val config = ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 1, topP = 0.1, temperature = 0.1),
                        systemInstruction = Contents.of("You are a concise observer.")
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
        }
    }

    private fun truncateRepetition(response: String): String {
        if (response.length < 500) return response
        val sentences = response.split(Regex("""(?<=[.!?])\s+""")).filter { it.length >= 15 }
        if (sentences.size < 10) return response
        val seen = HashSet<String>()
        var cutIndex = -1
        for ((i, sentence) in sentences.withIndex()) {
            val normalized = sentence.trim().lowercase()
            if (!seen.add(normalized) && i > 5) {
                 // Second occurrence of same sentence after some variety
                 cutIndex = i
                 break
            }
        }
        return if (cutIndex > 0) sentences.take(cutIndex).joinToString(" ") + "\n\n(...loop detected)" else response
    }

    // Audit 2.0: decodeHexTokens removed.

    override suspend fun cleanup() {
        isAborted = true
        // Try to acquire lock, but don't block forever if inference is stuck
        val acquired = kotlinx.coroutines.withTimeoutOrNull(2000) {
            sessionMutex.lock()
            true
        } ?: false

        try {
            conversation?.close()
            engine?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing engine/conversation")
        } finally {
            conversation = null
            engine = null
            if (acquired) sessionMutex.unlock()
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
