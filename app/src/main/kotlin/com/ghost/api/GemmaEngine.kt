package com.ghost.api

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Channel
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
import kotlinx.coroutines.withTimeout
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
    fun initialize(
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

        return runBlocking {
            sessionMutex.withLock {
                runCatching {
                    Timber.i("Initializing Gemma Engine...")
                    Engine.setNativeMinLogSeverity(LogSeverity.INFO)

                    val backendsToTry = when (forcedBackend?.uppercase()) {
                        "CPU" -> listOf("CPU" to Backend.CPU())
                        "GPU" -> listOf("GPU" to Backend.GPU())
                        else -> listOf("GPU" to Backend.GPU(), "CPU" to Backend.CPU())
                    }
                    var lastError: String? = null

                    for ((backendName, backend) in backendsToTry) {
                        try {
                            val engineConfig = EngineConfig(
                                modelPath = modelPath,
                                backend = backend,
                                visionBackend = if (enableVision && backendName == "GPU") Backend.GPU() else null,
                                audioBackend = if (enableAudio) Backend.CPU() else null,
                                maxNumTokens = Constants.MAX_TOKENS,
                                cacheDir = context.cacheDir.absolutePath
                            )

                            val newEngine = Engine(engineConfig)
                            newEngine.initialize()

                            val conversationConfig = ConversationConfig(
                                samplerConfig = samplerConfig,
                                systemInstruction = if (systemPrompt.isNotBlank()) Contents.of(systemPrompt) else null,
                                channels = listOf(Channel(channelName = "thought", start = "<think>", end = "</think>")),
                                tools = toolSets.map { tool(it) }
                            )

                            val newConversation = newEngine.createConversation(conversationConfig)

                            engine?.close()
                            conversation?.close()
                            engine = newEngine
                            conversation = newConversation

                            activeBackend = "LiteRT-LM ($backendName)"
                            return@runCatching null
                        } catch (e: Exception) {
                            lastError = e.message
                            Timber.w("$backendName backend failed: $lastError")
                        }
                    }
                    lastError ?: "All backends failed"
                }.getOrElse { it.message ?: "Unknown fatal error" }
            }
        }
    }

    override suspend fun generateResponse(
        prompt: String,
        images: List<Bitmap>,
        audioData: ByteArray?
    ): String {
        return suspendCancellableCoroutine { continuation ->
            streamResponse(
                prompt = prompt,
                images = images,
                audioData = audioData,
                onToken = {},
                onComplete = { continuation.resume(it) },
                onError = { continuation.resumeWithException(Exception(it)) }
            )
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
        runBlocking {
            sessionMutex.withLock {
                val conv = conversation
                if (conv == null) {
                    onError("Engine not initialized")
                    return@withLock
                }

                val deferred = CompletableDeferred<Unit>()
                try {
                    val contents = mutableListOf<Content>()
                    for (image in images) {
                        contents.add(Content.ImageBytes(image.toPngByteArray()))
                    }
                    audioData?.let {
                        contents.add(Content.AudioBytes(it))
                    }
                    if (prompt.isNotBlank()) {
                        contents.add(Content.Text(prompt))
                    }

                    var fullResponse = ""
                    conv.sendMessageAsync(
                        Contents.of(contents),
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                val token = message.toString()
                                fullResponse += token
                                onToken(token)
                            }
                            override fun onDone() {
                                try {
                                    onComplete(truncateRepetition(decodeHexTokens(fullResponse)))
                                } finally {
                                    deferred.complete(Unit)
                                }
                            }
                            override fun onError(throwable: Throwable) {
                                try {
                                    onError(throwable.message ?: "Stream error")
                                } finally {
                                    deferred.complete(Unit)
                                }
                            }
                        }
                    )
                    // Hold the mutex until the streaming is finished
                    // Added a 2-minute safety timeout to prevent deadlocks if native hangs
                    withTimeout(120000) {
                        deferred.await()
                    }
                } catch (e: Exception) {
                    onError(e.message ?: "Unknown failure")
                    deferred.complete(Unit)
                }
            }
        }
    }

    override fun softReset(systemPrompt: String) {
        lastSystemPrompt = systemPrompt
        runBlocking {
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
    }

    override fun hardReset() {
        if (lastModelPath.isBlank()) return
        runBlocking {
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
                                continuation.resume(decodeHexTokens(resp))
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
        if (sentences.size < 6) return response
        val seen = mutableMapOf<String, Int>()
        var cutIndex = -1
        for ((i, sentence) in sentences.withIndex()) {
            val normalized = sentence.trim().lowercase()
            val count = (seen[normalized] ?: 0) + 1
            seen[normalized] = count
            if (count >= 4 && cutIndex == -1) cutIndex = i
        }
        return if (cutIndex > 0) sentences.take(cutIndex).joinToString(" ") + "\n\n(...loop detected)" else response
    }

    private fun decodeHexTokens(response: String): String {
        val regex = """(<0x[0-9A-Fa-f]{2}>)+""".toRegex()
        return regex.replace(response) { match ->
            try {
                val bytes = match.value.split("<0x").filter { it.isNotBlank() }.map { it.replace(">", "").toInt(16).toByte() }.toByteArray()
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) { match.value }
        }
    }

    override fun cleanup() {
        runBlocking {
            sessionMutex.withLock {
                conversation?.close()
                engine?.close()
            }
        }
    }

    private fun Bitmap.toPngByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        this.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
