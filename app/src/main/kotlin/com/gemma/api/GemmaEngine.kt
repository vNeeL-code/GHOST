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
        val conv = conversation ?: return "Error: Model not loaded"

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
                                    continuation.resume("Error: NPU stall (driver hang).")
                                }
                                this.cancel()
                            }
                        }
                    }
                    timer.scheduleAtFixedRate(stallTask, checkInterval, checkInterval)

                    // 3. Send Message
                    conv.sendMessageAsync(message, object : MessageCallback {
                        override fun onMessage(msg: Message) {
                            lastTokenTime = System.currentTimeMillis() // Heartbeat
                            response.append(msg.toString())
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
            "Error: Timeout (NPU stall or long processing). Try again."
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

    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(shorts)
        return buffer.array()
    }

    fun softReset(systemPrompt: String) {
        val oldConv = conversation
        conversation = engine?.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
                systemMessage = Message.of(listOf(Content.Text(systemPrompt))),
                tools = listOf()
            )
        )
        oldConv?.close() // Release old KV cache
        Timber.i("KV Cache Flushed (Soft Reset)")
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
