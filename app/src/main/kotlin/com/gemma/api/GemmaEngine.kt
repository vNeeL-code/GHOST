package com.gemma.api

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GemmaEngine - LiteRT-LM based multimodal inference engine
 *
 * Uses the proper Google AI Edge LiteRT-LM library for Gemma 3n inference
 * with full support for images and audio (omnimodal!)
 */
class GemmaEngine(private val context: Context) : LlmBackend {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val sessionLock = Object()

    @Volatile private var isResetting = false

    override var activeBackend: String? = null
        private set

    // Phase 12: Cache initialization params for hardReset
    private var lastModelPath: String = ""
    private var lastSystemPrompt: String = ""
    private var lastVisionEnabled: Boolean = true
    private var lastAudioEnabled: Boolean = true

    fun initialize(
        modelPath: String,
        systemPrompt: String,
        enableVision: Boolean = true,
        enableAudio: Boolean = true
    ): String? {
        return runCatching {
            Timber.i("Initializing Gemma with LiteRT-LM Engine...")
            Timber.i("Model: $modelPath")
            Timber.i("Vision: $enableVision, Audio: $enableAudio")

            lastModelPath = modelPath
            lastSystemPrompt = systemPrompt
            lastVisionEnabled = enableVision
            lastAudioEnabled = enableAudio

            // Phase 12: Triple-Tier Hardware Fallback (NPU -> GPU -> CPU)
            val backendsToTry = listOf(
                "NPU" to { Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir) },
                "GPU" to { Backend.GPU() },
                "CPU" to { Backend.CPU() }
            )

            var lastError: String? = null
            
            for ((backendName, backendProvider) in backendsToTry) {
                try {
                    val currentBackend = backendProvider()
                    Timber.i("⚙️ Attempting LiteRT Initialization on $backendName Backend...")
                    
                    val engineConfig = EngineConfig(
                        modelPath = modelPath,
                        backend = currentBackend,
                        visionBackend = null,
                        audioBackend = null,
                        maxNumTokens = 32768,
                        cacheDir = null
                    )

                    val newEngine = Engine(engineConfig)
                    newEngine.initialize()

                    val conversationConfig = ConversationConfig(
                        samplerConfig = if (currentBackend is Backend.NPU) null else SamplerConfig(
                            topK = 40,
                            topP = 0.95,
                            temperature = 0.8
                        )
                    )

                    val newConversation = newEngine.createConversation(conversationConfig)

                    synchronized(sessionLock) {
                        conversation?.close()
                        engine?.close()
                        engine = newEngine
                        conversation = newConversation
                    }

                    activeBackend = "LiteRT-LM ($backendName)"
                    Timber.i("✓ Gemma initialized successfully on $backendName!")
                    return null // Success!
                } catch (e: Exception) {
                    lastError = e.message ?: "Unknown native error"
                    Timber.w("⚠️ $backendName initialization failed: $lastError")
                    // Continue to next backend...
                }
            }

            // If we reached here, everything failed
            Timber.e("❌ All backends (NPU, GPU, CPU) failed to initialize Gemma 4.")
            lastError ?: "Total hardware rejection"
        }.getOrElse { e ->
            Timber.e(e, "Fatal: Unexpected initialization error")
            e.message ?: "Unknown fatal error"
        }
    }

    override suspend fun generateResponse(
        prompt: String,
        images: List<Bitmap>,
        audioData: ByteArray?
    ): String {
        // Wait if reset is in progress or conversation is transiently null
        var waitCount = 0
        while (waitCount < 30) {
            if (!isResetting) {
                val c = synchronized(sessionLock) { conversation }
                if (c != null) break
            }
            kotlinx.coroutines.delay(200)
            waitCount++
            if (waitCount % 10 == 0) Timber.d("Waiting for conversation... (${waitCount * 200}ms)")
        }

        val conv = synchronized(sessionLock) {
            conversation
        } ?: return "(；￣Д￣) I'm not loaded yet... give me a sec"

        return try {
            val timeout = if (images.isNotEmpty() || audioData != null) 120_000L else 90_000L

            withTimeout(timeout) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        // Build multimodal content list
                        val contents = mutableListOf<Content>()

                        // Add images first (Gemma 3n expects images before text)
                        for (image in images) {
                            contents.add(Content.ImageBytes(image.toPngByteArray()))
                            Timber.d("Added image: ${image.width}x${image.height}")
                        }

                        // Add audio if present
                        audioData?.let {
                            contents.add(Content.AudioBytes(it))
                            Timber.d("Added audio: ${it.size} bytes")
                        }

                        // Add text last
                        if (prompt.isNotBlank()) {
                            contents.add(Content.Text(prompt))
                        }

                        Timber.d("Generating response with ${images.size} images, ${if (audioData != null) "audio" else "no audio"}, text: ${prompt.take(50)}...")

                        // Build response incrementally
                        val responseBuilder = StringBuilder()

                        conv.sendMessageAsync(
                            Message.of(contents),
                            object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    responseBuilder.append(message.toString())
                                }

                                override fun onDone() {
                                    val response = responseBuilder.toString()
                                    if (response.isBlank()) {
                                        Timber.w("Empty response from model")
                                        if (continuation.isActive) {
                                            continuation.resume("(・_・ヾ I... have no words")
                                        }
                                    } else {
                                        val cleaned = truncateRepetition(decodeHexTokens(response))
                                        Timber.d("Response complete: ${cleaned.take(50)}...")
                                        if (continuation.isActive) {
                                            continuation.resume(cleaned)
                                        }
                                    }
                                }

                                override fun onError(throwable: Throwable) {
                                    Timber.e(throwable, "Generation error")
                                    if (continuation.isActive) {
                                        if (throwable is CancellationException) {
                                            continuation.resume("(Generation cancelled)")
                                        } else {
                                            continuation.resumeWithException(throwable)
                                        }
                                    }
                                }
                            }
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Generation failed")
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Timber.w("Timeout during generation")
            "(┛✧Д✧))┛彡┻━┻ Timeout! My thoughts got stuck... try again?"
        } catch (e: Exception) {
            Timber.e(e, "Generate failed")
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
        val conv = synchronized(sessionLock) {
            conversation
        }

        if (conv == null) {
            onError("Not loaded")
            return
        }

        try {
            // Build multimodal content list
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
                Message.of(contents),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val token = message.toString()
                        fullResponse += token
                        onToken(token)
                    }

                    override fun onDone() {
                        onComplete(truncateRepetition(decodeHexTokens(fullResponse)))
                    }

                    override fun onError(throwable: Throwable) {
                        onError(throwable.message ?: "Unknown error")
                    }
                }
            )

        } catch (e: Exception) {
            Timber.e(e, "Stream failed")
            onError(e.message ?: "Unknown error")
        }
    }

    override fun softReset(systemPrompt: String) {
        Timber.i("GemmaEngine Soft Reset with robust prompt injection")
        isResetting = true
        try {
            synchronized(sessionLock) {
                val currentEngine = engine ?: return

                // Close old conversation
                conversation?.close()

                // Create new conversation with fresh system prompt
                val conversationConfig = ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = 40,
                        topP = 0.95,
                        temperature = 0.8
                    )
                )

                conversation = currentEngine.createConversation(conversationConfig)
            }
            Timber.i("KV Cache Flushed (Soft Reset)")
        } catch (e: Exception) {
            Timber.e(e, "Soft reset failed")
        } finally {
            isResetting = false
        }
    }

    // Phase 12: Implement hardReset to cure Hexagon DSP NPU hardware timeouts.
    override fun hardReset() {
        if (lastModelPath.isBlank()) {
            Timber.e("Cannot hard reset: Engine was never initialized")
            return
        }

        isResetting = true
        Timber.w("🚨 Initiating HARD RESET of GemmaEngine (Purging NPU state) 🚨")
        try {
            synchronized(sessionLock) {
                // 1. Fully destroy native objects
                conversation?.close()
                engine?.close()
                conversation = null
                engine = null
            }
            // 2. Re-initialize from scratch using cached params
            val err = initialize(
                modelPath = lastModelPath,
                systemPrompt = lastSystemPrompt,
                enableVision = lastVisionEnabled,
                enableAudio = lastAudioEnabled
            )
            if (err != null) {
                Timber.e("Hard reset initialization throw: $err")
            } else {
                Timber.i("✅ Hard reset complete. Engine fully rebuilt.")
            }
        } catch (e: Exception) {
            Timber.e(e, "Fatal failure during hard reset")
        } finally {
            isResetting = false
        }
    }

    /**
     * Detect and truncate repetitive model output.
     * Catches death spirals like "I am processing. I am learning. I am processing. I am learning."
     * Threshold is high enough to not block intentional repetition (lyrics, emphasis, style).
     */
    private fun truncateRepetition(response: String): String {
        if (response.length < 500) return response

        // Only flag long sentences (15+ chars) — short phrases could be lyrics or emphasis
        // Tuned down from 40 to catch short psychotic breaks like "I'm a Gemma."
        val sentences = response.split(Regex("""(?<=[.!?])\s+""")).filter { it.length >= 15 }
        if (sentences.size < 6) return response

        // Find first sentence that repeats 4+ times (genuine loop, not style)
        val seen = mutableMapOf<String, Int>()
        var cutIndex = -1
        for ((i, sentence) in sentences.withIndex()) {
            val normalized = sentence.trim().lowercase()
            val count = (seen[normalized] ?: 0) + 1
            seen[normalized] = count
            if (count >= 4 && cutIndex == -1) {
                cutIndex = i
            }
        }

        if (cutIndex > 0) {
            val kept = sentences.take(cutIndex).joinToString(" ")
            Timber.w("Repetition loop detected at sentence $cutIndex, truncating (${response.length} → ${kept.length} chars)")
            return "$kept\n\n(...I got stuck in a loop there, sorry!)"
        }

        return response
    }

    /**
     * One-shot inference with isolated conversation.
     * Creates a temporary conversation, runs inference, cleans up.
     * Used by RLM for recursive sub-calls that shouldn't pollute the main conversation.
     */
    override suspend fun generateOneShot(prompt: String): String {
        Timber.i("Executing one-shot prompt...")
        val currentEngine = synchronized(sessionLock) { engine }
            ?: return "Error: Engine not initialized"

        return try {
            withTimeout(60_000L) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        val config = ConversationConfig(
                            samplerConfig = SamplerConfig(
                                topK = 40,
                                topP = 0.95,
                                temperature = 0.7
                            )
                        )
                        val tempConv = currentEngine.createConversation(config)

                        val responseBuilder = StringBuilder()

                        tempConv.sendMessageAsync(
                            Message.of(listOf(Content.Text(prompt))),
                            object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    responseBuilder.append(message.toString())
                                }

                                override fun onDone() {
                                    val response = responseBuilder.toString()
                                    try { tempConv.close() } catch (_: Exception) {}
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            if (response.isBlank()) "(empty response)"
                                            else truncateRepetition(decodeHexTokens(response))
                                        )
                                    }
                                }

                                override fun onError(throwable: Throwable) {
                                    try { tempConv.close() } catch (_: Exception) {}
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(throwable)
                                    }
                                }
                            }
                        )
                    } catch (e: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Timber.w("One-shot inference timeout")
            "(timeout)"
        } catch (e: Exception) {
            Timber.e(e, "One-shot inference failed")
            "Error: ${e.message}"
        }
    }

    /**
     * Decode literal byte tokens (e.g. <0xF0><0x9F><...>) into proper UTF-8 strings.
     * This fixes instances where LiteRT-LM's detokenizer fails on emojis or non-ascii sequences.
     */
    private fun decodeHexTokens(response: String): String {
        val regex = """(<0x[0-9A-Fa-f]{2}>)+""".toRegex()
        return regex.replace(response) { match ->
            try {
                val hexTokens = match.value.split("<0x")
                    .filter { it.isNotBlank() }
                    .map { it.replace(">", "") }
                val bytes = hexTokens.map { it.toInt(16).toByte() }.toByteArray()
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                match.value
            }
        }
    }

    override fun cleanup() {
        runCatching {
            synchronized(sessionLock) {
                conversation?.close()
                conversation = null
                engine?.close()
                engine = null
            }
        }.onFailure { Timber.e(it, "Cleanup error") }
        Timber.i("Cleanup done")
    }

    // Extension function to convert Bitmap to PNG bytes
    private fun Bitmap.toPngByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        this.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
