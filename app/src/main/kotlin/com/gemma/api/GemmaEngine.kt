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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GemmaEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val conversationLock = Object()  // Synchronize conversation access
    @Volatile private var isResetting = false  // Flag to block queries during reset

    var activeBackend: Backend? = null
        private set

    fun initialize(
        modelPath: String,
        systemPrompt: String,
        enableVision: Boolean = true,
        enableAudio: Boolean = true
    ): String? {
        return runCatching {
            Timber.i("Initializing Gemma (Omnimodal)...")
            Timber.i("Model: $modelPath")

            // Smart Backend Selection with Fallback
            // Priority: Preferred (NPU) -> GPU -> CPU
            val backendsToTry = mutableListOf<Backend>()
            
            when (Constants.PREFERRED_BACKEND.uppercase()) {
                "NPU" -> {
                    backendsToTry.add(Backend.NPU)
                    backendsToTry.add(Backend.GPU) // First fallback
                    backendsToTry.add(Backend.CPU) // Ultimate safety
                }
                "GPU" -> {
                    backendsToTry.add(Backend.GPU)
                    backendsToTry.add(Backend.CPU)
                }
                else -> backendsToTry.add(Backend.CPU)
            }

            var lastError: Throwable? = null

            for (backend in backendsToTry) {
                try {
                    Timber.i(">> Attempting Backend: $backend")
                    
                    val engineConfig = EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        // Context 32k is standard for Gemma 3n
                        maxNumTokens = 32768,
                        
                        // Multimodal logic:
                        // Vision is heavy, usually needs GPU. Disable on CPU to save RAM?
                        // Audio is usually CPU bound in LiteRT currently.
                        visionBackend = if (enableVision && backend != Backend.CPU) Backend.GPU else null,
                        audioBackend = if (enableAudio) Backend.CPU else null,
                        
                        cacheDir = if (modelPath.startsWith("/data/local/tmp")) {
                            context.getExternalFilesDir(null)?.absolutePath
                        } else null
                    )

                    Timber.i("Creating Engine ($backend)...")
                    engine = Engine(engineConfig).apply { initialize() }
                    
                    Timber.i("✓ Success! Running on $backend")
                    activeBackend = backend

                    conversation = engine!!.createConversation(
                        ConversationConfig(
                            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
                            systemMessage = Message.of(listOf(Content.Text(systemPrompt))),
                            tools = listOf()
                        )
                    )
                    
                    Timber.i("✓ Conversation created. Ready.")
                    return null // Success (no error string)
                    
                } catch (e: Exception) {
                    Timber.w("!! Backend $backend failed: ${e.message}")
                    lastError = e
                    // Continue to next backend...
                }
            }
            
            // If loop finishes, all failed
            throw lastError ?: Exception("All backends failed initialization")
            
        }.getOrElse { e ->
            Timber.e(e, "Fatal: Could not initialize Gemma")
            e.message ?: "Unknown fatal error"
        }
    }

    suspend fun generateResponse(
        prompt: String,
        images: List<Bitmap> = emptyList(),
        audioData: ShortArray? = null
    ): String {
        // Wait if reset is in progress (max 500ms)
        var waitCount = 0
        while (isResetting && waitCount < 5) {
            kotlinx.coroutines.delay(100)
            waitCount++
        }

        val conv = synchronized(conversationLock) {
            conversation
        } ?: return "(；￣Д￣) I'm not loaded yet... give me a sec"

        return try {
            // Extended timeout for multimodal
            val timeout = if (images.isNotEmpty() || audioData != null) 120_000L else 90_000L
            
            withTimeout(timeout) {
                suspendCancellableCoroutine { continuation ->
                    // 1. Prepare Content
                    val contentList = prepareContent(prompt, images, audioData)
                    val message = Message.of(contentList)
                    val response = StringBuilder()
                    
                    var lastTokenTime = System.currentTimeMillis()
                    val checkInterval = 5000L // Check every 5s
                    
                    // 2. Setup NPU Stall Detection (Heartbeat)
                    val timer = java.util.Timer()
                    val stallTask = object : java.util.TimerTask() {
                        override fun run() {
                            val timeSinceToken = System.currentTimeMillis() - lastTokenTime
                            if (timeSinceToken > 30000) { // 30s silence = DEAD
                                Timber.e("NPU STALL DETECTED - Force cancelling")
                                if (continuation.isActive) {
                                    continuation.resume("┻━┻︵ \\(✦□✦)/ ︵ ┻━┻ NPU stall! My brain froze...")
                                }
                                this.cancel()
                            }
                        }
                    }
                    timer.scheduleAtFixedRate(stallTask, checkInterval, checkInterval)

                    // 3. Send Message with repetition detection
                    var repetitionCount = 0
                    var lastChunk = ""
                    val maxResponseLength = 4000  // Cap response length

                    conv.sendMessageAsync(message, object : MessageCallback {
                        override fun onMessage(msg: Message) {
                            lastTokenTime = System.currentTimeMillis() // Heartbeat
                            val chunk = msg.toString()

                            // Repetition detection: same chunk 5+ times = loop
                            if (chunk == lastChunk && chunk.length > 2) {
                                repetitionCount++
                                if (repetitionCount >= 5) {
                                    Timber.w("(╯°□°)╯ REPETITION LOOP DETECTED - aborting")
                                    timer.cancel()
                                    val output = response.toString() + "\n\n(；′⌒`) ...I got stuck in a loop, sorry!"
                                    if (continuation.isActive) continuation.resume(output)
                                    return
                                }
                            } else {
                                repetitionCount = 0
                                lastChunk = chunk
                            }

                            response.append(chunk)

                            // Length cap to prevent runaway generation
                            if (response.length > maxResponseLength) {
                                Timber.w("Response length exceeded $maxResponseLength - truncating")
                                timer.cancel()
                                if (continuation.isActive) continuation.resume(response.toString())
                                return
                            }
                        }

                        override fun onDone() {
                            timer.cancel()
                            val output = response.toString()
                            if (output.isBlank()) {
                                Timber.w("Generation done but EMPTY output (Immediate EOS?)")
                            } else {
                                Timber.d("Generation done: ${output.take(50)}...")
                            }
                            if (continuation.isActive) continuation.resume(output)
                        }

                        override fun onError(throwable: Throwable) {
                            timer.cancel()
                            Timber.e(throwable, "Generation error")
                            if (continuation.isActive) {
                                if (throwable is CancellationException) {
                                    continuation.cancel(throwable)
                                } else if (throwable is TimeoutCancellationException) {
                                    continuation.resume("Error: Processing timeout")
                                } else {
                                    continuation.resumeWithException(throwable)
                                }
                            }
                        }
                    })
                    
                    // Cleanup timer on cancellation
                    continuation.invokeOnCancellation { 
                        timer.cancel() 
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Critical: Mark engine as suspect so next call triggers soft reset
            Timber.w("Timeout detected - marking engine suspect")
            conversation?.close() // Force KV cache clear
            "(┛✧Д✧))┛彡┻━┻ Timeout! My thoughts got stuck... try again?"
        } catch (e: Exception) {
            Timber.e(e, "Generate failed")
            "Error: ${e.message}"
        }
    }

    fun streamResponse(
        prompt: String,
        images: List<Bitmap> = emptyList(),
        audioData: ShortArray? = null,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val conv = conversation
        if (conv == null) {
            onError("Not loaded")
            return
        }

        try {
            val contentList = prepareContent(prompt, images, audioData)
            val responseAccumulator = StringBuilder()
            val message = Message.of(contentList)

            conv.sendMessageAsync(message, object : MessageCallback {
                override fun onMessage(msg: Message) {
                    val token = msg.toString()
                    synchronized(responseAccumulator) {
                        responseAccumulator.append(token)
                    }
                    onToken(token)
                }

                override fun onDone() {
                    Timber.d("Stream complete")
                    onComplete(responseAccumulator.toString())
                }

                override fun onError(throwable: Throwable) {
                    Timber.e(throwable, "Stream error")
                    if (throwable !is CancellationException) {
                        onError(throwable.message ?: "Unknown error")
                    }
                }
            })
        } catch (e: Exception) {
            Timber.e(e, "Stream failed")
            onError(e.message ?: "Unknown error")
        }
    }
    
    private fun prepareContent(prompt: String, images: List<Bitmap>, audioData: ShortArray?): List<Content> {
        val contentList = mutableListOf<Content>()
        images.forEach { image ->
            runCatching {
                val scaledImage = scaleImage(image, 768)
                val pngBytes = scaledImage.toPngByteArray()
                contentList.add(Content.ImageBytes(pngBytes))
                Timber.d("Added image: ${scaledImage.width}x${scaledImage.height} (${pngBytes.size} bytes)")
            }.onFailure { Timber.w(it, "Failed to add image") }
        }

        audioData?.takeIf { it.isNotEmpty() }?.let {
            runCatching {
                val audioBytes = shortArrayToByteArray(it)
                contentList.add(Content.AudioBytes(audioBytes))
                Timber.d("Added audio: ${it.size} samples (${audioBytes.size} bytes)")
            }.onFailure { Timber.w(it, "Failed to add audio") }
        }

        contentList.add(Content.Text(prompt))
        return contentList
    }

    private fun scaleImage(bitmap: Bitmap, maxSize: Int): Bitmap {
        if (bitmap.width <= maxSize && bitmap.height <= maxSize) return bitmap
        val ratio = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    }

    private fun Bitmap.toPngByteArray(): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    }

    /**
     * Convert ShortArray to raw PCM bytes (little-endian, 16-bit)
     * LiteRT-LM expects raw PCM bytes at 16kHz mono - same as gallery app
     * No WAV header needed - the library handles preprocessing internally
     */
    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in shorts) {
            buffer.putShort(sample)
        }
        return buffer.array()
    }

    fun softReset(systemPrompt: String) {
        isResetting = true
        try {
            // Create fresh conversation FIRST (before closing old one)
            val newConv = engine?.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
                    systemMessage = Message.of(listOf(Content.Text(systemPrompt))),
                    tools = listOf()
                )
            )

            if (newConv != null) {
                // Atomically swap: set new, then close old
                val oldConv = synchronized(conversationLock) {
                    val old = conversation
                    conversation = newConv
                    old
                }

                // Close old AFTER new is active (prevents "not alive" error)
                try {
                    oldConv?.close()
                } catch (e: Exception) {
                    Timber.w("Old conversation close failed (non-fatal): ${e.message}")
                }

                Timber.i("KV Cache Flushed (Soft Reset)")
            } else {
                Timber.e("Failed to create new conversation - keeping old one")
            }
        } catch (e: Exception) {
            Timber.e(e, "Soft reset failed - will retry on next turn")
        } finally {
            isResetting = false
        }
    }

    fun cleanup() {
        runCatching {
            conversation?.close()
            engine?.close()
        }.onFailure { Timber.e(it, "Cleanup error") }
        conversation = null
        engine = null
        Timber.i("Cleanup done")
    }
}
