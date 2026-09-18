package com.ghost.api.agent

import android.content.Context
import android.graphics.Bitmap
import com.ghost.api.LlmBackend
import com.ghost.api.GemmaEngine
import com.ghost.api.Constants
import com.ghost.api.logic.ContextManager
import com.ghost.api.logic.IntentHandler
import com.ghost.api.logic.AiPhonebook
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import com.google.gson.Gson

/**
 * KoogAgent - The Core Orchestration Layer
 * 
 * Implements the Koog agent philosophy:
 * - Agents with tools (not LLMs with wrappers)
 * - Perceive → Think → Act loop
 * - State persistence across thermal events/reboots
 * - MCP protocol for tool/resource access
 * 
 * The LLM (GemmaEngine) is just a "cognitive function" the agent calls when it needs to reason.
 */
class KoogAgent(
    private val context: Context,
    private val llmEngine: LlmBackend,
    private val sensorManager: com.ghost.api.hardware.SensorFusionManager,
    private val contextManager: com.ghost.api.logic.ContextManager,
    private val skillManager: com.ghost.api.skills.SkillManager,
    private val checkpointDir: File,
    private val coreTools: List<com.google.ai.edge.litertlm.ToolSet> = emptyList(),
    private val uiTools: List<com.google.ai.edge.litertlm.ToolSet> = emptyList(),
    private val fileTools: List<com.google.ai.edge.litertlm.ToolSet> = emptyList(),
    private val callbacks: AgentPlatformCallbacks? = null
) {
    private var currentTools = coreTools
    private val memoryManager by lazy { com.ghost.api.database.MemoryManager(context) }
    // Agent's own coroutine scope for fire-and-forget operations (KV flush, etc.)
    private val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // RLM removed to stabilize build

    // ═══════════════════════════════════════════════════════════════
    // ACTOR PATTERN: Event-driven work queue (replaces recursive loop)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Event types the agent can process - all go through single queue
     * This prevents stack overflow from recursive think->act->think
     * and allows system events (thermal, battery) to safely interrupt
     */
    sealed class AgentEvent {
        /** User sent a message - needs full perceive->think->act cycle */
        data class UserMessage(
            val message: String,
            val sessionId: String,
            val isDream: Boolean = false,
            val responseChannel: CompletableDeferred<String>
        ) : AgentEvent()

        /** Tool execution completed - may need follow-up thinking */
        data class ToolResult(
            val context: String,
            val toolResults: List<String>,
            val originalResponse: String,
            val responseChannel: CompletableDeferred<String>,
            val recursionDepth: Int = 0,
            val userMessage: String,
            val sessionId: String,
            val isDream: Boolean
        ) : AgentEvent()

        data class SystemEvent(
            val type: SystemEventType,
            val payload: String? = null
        ) : AgentEvent()

        /** Tool needs user confirmation before execution */
        data class ConfirmationRequired(
            val toolName: String,
            val toolParams: Map<String, Any?>,
            val originalResponse: String,
            val responseChannel: CompletableDeferred<String>
        ) : AgentEvent()

        /** User approved or denied a tool */
        data class ConfirmationResult(
            val toolName: String,
            val params: Map<String, Any?>,
            val isApproved: Boolean,
            val originalResponse: String,
            val responseChannel: CompletableDeferred<String>
        ) : AgentEvent()
    }

    enum class SystemEventType {
        THERMAL_THROTTLE,    // Slow down, NPU getting warm
        THERMAL_CRITICAL,    // Stop processing, emergency cooldown
        LOW_BATTERY,         // Warn user, maybe checkpoint
        KV_CACHE_FLUSH,      // Soft reset requested
        CHECKPOINT_NOW       // Immediate state save
    }

    // The work queue - bounded to prevent memory explosion under load
    // Audit Fix: Limited capacity to 100 to provide backpressure and prevent OOMs
    // while still being generous enough to not bottleneck typical usage.
    private val eventQueue = Channel<AgentEvent>(capacity = 100)

    // Currently processing flag - for thermal interrupts
    @Volatile var isProcessing = false
    @Volatile private var shouldAbort = false

    // Ready flag - true after initialize() completes and event loop is running
    // CRITICAL: Prevents hang if processUserMessage() called before event loop starts
    @Volatile var isReady = false

    // ═══════════════════════════════════════════════════════════════
    // MEDIA QUEUES (owned by KoogAgent, fed by GemmaService)
    // ═══════════════════════════════════════════════════════════════

    data class QueuedImage(val bitmap: Bitmap, val uri: String? = null)
    private val pendingImages = ConcurrentLinkedQueue<QueuedImage>()
    private val pendingAudio = AtomicReference<ByteArray?>(null)

    /** Queue an image for the next inference turn */
    fun offerImage(bitmap: Bitmap, uri: String? = null) {
        pendingImages.offer(QueuedImage(bitmap, uri))
        Timber.i("📷 Image queued (${bitmap.width}x${bitmap.height}, uri=$uri), queue size: ${pendingImages.size}")
    }

    /** Queue audio for the next inference turn */
    fun offerAudio(audio: ByteArray) {
        pendingAudio.set(audio)
        Timber.i("🎤 Audio queued (${audio.size} bytes)")
    }

    /** Drain media queues atomically for one inference call */
    private fun drainMedia(): Pair<List<QueuedImage>, ByteArray?> {
        val images = mutableListOf<QueuedImage>()
        while (true) {
            val img = pendingImages.poll() ?: break
            images.add(img)
        }
        val audio = pendingAudio.getAndSet(null)
        return Pair(images, audio)
    }

    // ═══════════════════════════════════════════════════════════════
    // STATE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════
    
    data class Message(
        val role: String, // "user", "assistant", "system"
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val hadImage: Boolean = false,
        val hadAudio: Boolean = false
    )
    
    data class AgentState(
        val conversationHistory: List<Message>,
        val turnCount: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // Thread-safe collections and volatile state for concurrent access
    private val _conversationHistory = java.util.Collections.synchronizedList(mutableListOf<Message>())
    private val _turnCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var turnCount: Int get() = _turnCount.get(); set(value) { _turnCount.set(value) }
    
    // Semantic Rolling Memory (State Handover)
    private var rollingMemoryJson: String? = null

    // Inference timestamp for thermal rate limiting
    private val _lastInferenceTime = java.util.concurrent.atomic.AtomicLong(0L)
    private var lastInferenceTime: Long get() = _lastInferenceTime.get(); set(value) { _lastInferenceTime.set(value) }

    // Stuck loop detection — if model returns same response N times, force KV flush
    private val _lastResponseHash = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastResponseHash: Int get() = _lastResponseHash.get(); set(value) { _lastResponseHash.set(value) }
    private var lastResponseText: String = ""

    // Tracks turns since last KV flush
    private val _turnsSinceKvFlush = java.util.concurrent.atomic.AtomicInteger(0)
    private var turnsSinceKvFlush: Int get() = _turnsSinceKvFlush.get(); set(value) { _turnsSinceKvFlush.set(value) }

    // Tracks multimodal and tool tokens currently residing in the C++ KV cache since last flush
    private val _sessionAudioTokens = java.util.concurrent.atomic.AtomicInteger(0)
    private var sessionAudioTokens: Int get() = _sessionAudioTokens.get(); set(value) { _sessionAudioTokens.set(value) }

    private val _sessionImageTokens = java.util.concurrent.atomic.AtomicInteger(0)
    private var sessionImageTokens: Int get() = _sessionImageTokens.get(); set(value) { _sessionImageTokens.set(value) }

    private val _sessionToolTokens = java.util.concurrent.atomic.AtomicInteger(0)
    var sessionToolTokens: Int get() = _sessionToolTokens.get(); set(value) { _sessionToolTokens.set(value) }

    fun recordToolChars(charCount: Int) {
        // Accurately account for tool execution turn overhead in LiteRT-LM:
        // payload tokens (charCount / 4) + pre-tool thought block (~400t) + tool_call syntax (~50t) + post-tool synthesis thoughts (~550t)
        val estimatedTokens = (charCount / Constants.CHARS_PER_TOKEN) + 1000
        _sessionToolTokens.addAndGet(estimatedTokens)
        Timber.d("Recorded tool tokens: +$estimatedTokens (session total: ${_sessionToolTokens.get()})")
    }



    // Skip recap injection turn after stuck-loop flush
    
    private val checkpointFile: File
        get() = File(checkpointDir, "koog_agent_checkpoint.json")
    
    private val gson = Gson()
    
    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════
    
    suspend fun initialize() {
        Timber.i("KoogAgent: Initializing...")
        restore()

        // Set the single authoritative system prompt on the engine
        try {
            val systemPrompt = buildSystemPrompt() + getRollingMemoryString()
            llmEngine.softReset(systemPrompt, currentTools)
            turnsSinceKvFlush = 0
            Timber.i("KoogAgent: System prompt set via softReset with tools")
        } catch (e: Exception) {
            Timber.e(e, "KoogAgent: Failed to set initial system prompt")
        }

        startEventLoop()
        isReady = true
        Timber.i("KoogAgent: Ready (B:${getBatteryLevel()}%)")
    }

    fun restore() {
        try {
            if (!checkpointFile.exists()) return
            
            val lines = checkpointFile.readLines()
            if (lines.isEmpty()) return
            
            val lastLine = lines.last()
            val jsonContent = if (lastLine.startsWith("CHECKSUM:")) {
                val expectedHash = lastLine.removePrefix("CHECKSUM:").toLongOrNull() ?: 0L
                val rawJson = lines.dropLast(1).joinToString("\n")
                val actualHash = calculateChecksum(rawJson.toByteArray())
                
                if (expectedHash != actualHash) {
                    Timber.e("KoogAgent: Checkpoint corrupted!")
                    checkpointFile.delete()
                    return
                }
                rawJson
            } else {
                lines.joinToString("\n")
            }
            
            val state = gson.fromJson(jsonContent, AgentState::class.java)
            synchronized(_conversationHistory) {
                _conversationHistory.clear()
                _conversationHistory.addAll(state.conversationHistory)
            }
            turnCount = state.turnCount
            Timber.i("KoogAgent: Restored checkpoint (${_conversationHistory.size} messages)")
        } catch (e: Exception) {
            Timber.e(e, "KoogAgent: Restore failed")
        }
    }

    /**
     * Actor event loop - single coroutine processing events sequentially
     * This is the heart of the safe, non-recursive agent pattern
     */
    private fun startEventLoop() {
        agentScope.launch {
            Timber.i("KoogAgent: Event loop started ⚡")
            for (event in eventQueue) {
                // Outer safety net: Ensure the loop NEVER dies
                try {
                    // Inner processing block
                    processEventSafe(event)
                } catch (e: Throwable) {
                    // This catch handles critical failures in processEventSafe itself
                    Timber.e(e, "CRITICAL: Event loop crashed")
                    
                    // Attempt to complete channel if it was a user/tool event
                    try {
                        when (event) {
                            is AgentEvent.UserMessage -> event.responseChannel.completeExceptionally(e)
                            is AgentEvent.ToolResult -> event.responseChannel.completeExceptionally(e)
                            else -> {}
                        }
                    } catch (_: Exception) {}
                }
            }
            Timber.i("KoogAgent: Event loop stopped")
        }
    }
    
    private suspend fun processEventSafe(event: AgentEvent) {
        try {
            isProcessing = true
            shouldAbort = false
            processEvent(event)
        } catch (e: Exception) {
             Timber.e(e, "Event processing failed")
             // Try to respond to waiting callers
             when (event) {
                 is AgentEvent.UserMessage -> {
                     event.responseChannel.complete(
                         "(╯°□°)╯︵ ┻━┻ Event processing crashed: ${e.message}"
                     )
                 }
                 is AgentEvent.ToolResult -> {
                     event.responseChannel.complete(event.originalResponse)
                 }
                 else -> { /* System events don't need response */ }
             }
        } finally {
             isProcessing = false
        }
    }

    /**
     * Route events to appropriate handlers
     */
    private suspend fun processEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.UserMessage -> handleUserMessage(event)
            is AgentEvent.ToolResult -> handleToolResult(event)
            is AgentEvent.SystemEvent -> handleSystemEvent(event)
            is AgentEvent.ConfirmationRequired -> handleConfirmationRequired(event)
            is AgentEvent.ConfirmationResult -> handleConfirmationResult(event)
        }
    }

    /**
     * Handle system events (thermal, battery, etc.)
     */
    private suspend fun handleSystemEvent(event: AgentEvent.SystemEvent) {
        when (event.type) {
            SystemEventType.THERMAL_CRITICAL, SystemEventType.LOW_BATTERY -> {
                Timber.w("🚨 System Alert: ${event.type} - Checkpointing state")
                withContext(Dispatchers.IO) { checkpoint() }
            }
            SystemEventType.KV_CACHE_FLUSH -> {
                Timber.i("🔄 KV cache flush requested via system event")
                flushAndCompactSession()
            }
            SystemEventType.CHECKPOINT_NOW -> {
                withContext(Dispatchers.IO) { checkpoint() }
            }
            else -> { /* Ignore minor events like throttle logs */ }
        }
    }
    
    /**
     * Compacts session conversation history into long-term semantic memory
     * and performs a C++ LiteRT-LM KV cache soft reset with the active system prompt.
     */
    suspend fun flushAndCompactSession() {
        Timber.i("🌀 Explicit Session Compaction & KV Cache Flush requested")
        com.ghost.api.GemmaService.instance?.showWorkSignal("COMPACTING", 1600)
        try {
            val oldMemory = memoryManager.getCompactedSessionMemory()

            val messagesToCompact = synchronized(_conversationHistory) {
                // Purge any corrupted or emoji-choke assistant messages so they don't corrupt long-term memory
                _conversationHistory.removeAll { msg ->
                    msg.role == "assistant" && (
                        msg.content.isBlank() ||
                        msg.content.startsWith("Error:") ||
                        (msg.content.trim().length <= 4 && !msg.content.trim().any { it.isLetterOrDigit() })
                    )
                }

                if (_conversationHistory.size > 2) {
                    val toCompact = _conversationHistory.dropLast(2)
                    val recent = _conversationHistory.takeLast(2)
                    _conversationHistory.clear()
                    _conversationHistory.addAll(recent)
                    toCompact
                } else {
                    _conversationHistory.toList()
                }
            }

            if (messagesToCompact.isNotEmpty()) {
                val assistantCallSign = getAssistantCallSign()
                val newMemory = SessionMemoryCompactor.compactOldMessages(messagesToCompact, oldMemory, llmEngine, assistantCallSign)
                if (!newMemory.startsWith("Error:") && newMemory.isNotBlank()) {
                    memoryManager.updateCompactedSessionMemory(newMemory)
                } else {
                    Timber.w("Compaction returned error or blank, preserving existing memory: $newMemory")
                }
            }

            // Keep at most 2 recent messages (1 turn) in RAM so immediate continuity is preserved
            // while all older messages are completely purged from the LiteRT native KV cache!
            val initialMessages = synchronized(_conversationHistory) {
                val recent = _conversationHistory.takeLast(2)
                _conversationHistory.clear()
                _conversationHistory.addAll(recent)
                recent.map {
                    when (it.role) {
                        "user" -> com.google.ai.edge.litertlm.Message.user(it.content)
                        "assistant" -> com.google.ai.edge.litertlm.Message.model(it.content)
                        else -> com.google.ai.edge.litertlm.Message.system(it.content)
                    }
                }
            }

            // Only append rolling memory string as plaintext fallback if initialMessages is empty
            val systemPrompt = buildSystemPrompt() + (if (initialMessages.isEmpty()) getRollingMemoryString() else "")
            llmEngine.softReset(systemPrompt, currentTools, initialMessages)
            turnsSinceKvFlush = 0
            sessionAudioTokens = 0
            sessionImageTokens = 0
            sessionToolTokens = 0
            _lastResponseHash.set(0)
            lastResponseText = ""
            Timber.i("✅ Session compaction & KV cache soft reset complete (purged old history, kept ${initialMessages.size} recent msgs)")
        } catch (e: Exception) {
            Timber.e(e, "Session compaction failed")
        }
    }

    /**
     * Complete reset of agent state and engine KV cache.
     */
    suspend fun softReset() {
        clearHistory()
        val systemPrompt = buildSystemPrompt()
        llmEngine.softReset(systemPrompt, currentTools)
        turnsSinceKvFlush = 0
        sessionAudioTokens = 0
        sessionImageTokens = 0
        sessionToolTokens = 0
        _lastResponseHash.set(0)
        lastResponseText = ""
        Timber.i("KoogAgent: Soft reset complete")
    }

    fun clearHistory() {
        synchronized(_conversationHistory) {
            _conversationHistory.clear()
            turnCount = 0
            sessionAudioTokens = 0
            sessionImageTokens = 0
            sessionToolTokens = 0
            _lastResponseHash.set(0)
            lastResponseText = ""
            Timber.i("KoogAgent: History cleared")
        }
    }

    fun shouldCompactKv(estimatedTokens: Int): Boolean {
        val tokenLimit = (Constants.MAX_TOKENS * 0.65).toInt() // ~1000 tokens for 1536 max tokens
        val toolTokenLimit = (Constants.MAX_TOKENS * 0.35).toInt() // ~537 tokens
        return estimatedTokens > tokenLimit || turnsSinceKvFlush >= 6 || sessionToolTokens >= toolTokenLimit
    }

    /**
     * Accurately estimate current tokens in the native C++ KV cache.
     * - Tightened system prompt (~250 tokens) + active tool schemas (~350 tokens) = ~600 tokens
     * - Cumulative telemetry context injected on each turn (~45 tokens/turn)
     * - Message history characters / 4
     * - Accumulated and incoming Image tokens (576 tokens/image)
     * - Accumulated and incoming Audio tokens (25 tokens/sec)
     * - Accumulated tool execution output tokens
     */
    fun estimateCurrentKvTokens(
        contextLength: Int,
        incomingChars: Int = 0,
        incomingImageCount: Int = 0,
        incomingAudioBytes: Int = 0
    ): Int {
        val historyChars = synchronized(_conversationHistory) { _conversationHistory.sumOf { it.content.length } }
        val telemetryTokens = (turnsSinceKvFlush + 1) * 45
        val textTokens = (historyChars + incomingChars + contextLength) / Constants.CHARS_PER_TOKEN
        val imageTokens = sessionImageTokens + (incomingImageCount * Constants.TOKENS_PER_IMAGE)
        val audioTokens = sessionAudioTokens + calculateAudioTokens(incomingAudioBytes)
        val toolTokens = sessionToolTokens
        // Base overhead: tightened base system prompt (~250t) + active core tool declarations (~350t) = 600t
        val baseOverhead = 600
        return baseOverhead + telemetryTokens + textTokens + imageTokens + audioTokens + toolTokens
    }

    fun calculateAudioTokens(byteCount: Int): Int {
        if (byteCount <= Constants.AUDIO_WAV_HEADER_BYTES) return 0
        val pcmBytes = byteCount - Constants.AUDIO_WAV_HEADER_BYTES
        val seconds = pcmBytes.toFloat() / Constants.AUDIO_BYTES_PER_SECOND
        return (seconds * Constants.AUDIO_TOKENS_PER_SECOND + 0.5f).toInt()
    }
    
    fun shutdown() {
        Timber.i("KoogAgent: Shutting down...")
        isReady = false
        shouldAbort = true
        eventQueue.close()
        checkpoint()
        try {
            agentScope.cancel()
        } catch (e: Exception) {
            Timber.w(e, "Error cancelling agent scope")
        }
        Timber.i("KoogAgent: Shutdown complete")
    }
    
    private fun calculateChecksum(data: ByteArray): Long {
        var a = 1L
        var b = 0L
        val prime = 65521L
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % prime
            b = (b + a) % prime
        }
        return (b shl 16) or a
    }

    fun checkpoint() {
        try {
            val historySnapshot = synchronized(_conversationHistory) { _conversationHistory.takeLast(50) }
            val state = AgentState(
                conversationHistory = historySnapshot,
                turnCount = turnCount
            )
            
            val json = gson.toJson(state)
            val checksum = calculateChecksum(json.toByteArray())
            val contentWithChecksum = "$json\nCHECKSUM:$checksum"
            
            checkpointFile.parentFile?.mkdirs()
            checkpointFile.writeText(contentWithChecksum)
            
            Timber.d("KoogAgent: Checkpoint saved (${_conversationHistory.size} messages, checksum=$checksum)")
        } catch (e: Exception) {
            Timber.e(e, "KoogAgent: Checkpoint failed")
        }
    }
    
    
    // ═══════════════════════════════════════════════════════════════
    // CORE AGENT LOOP: PERCEIVE → THINK → ACT (via Event Queue)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Public API: Enqueue user message and wait for response
     * This replaces the old recursive processUserMessage
     */
    suspend fun processUserMessage(
        message: String,
        sessionId: String,
        isDream: Boolean = false
    ): String {
        val responseChannel = CompletableDeferred<String>()

        val event = AgentEvent.UserMessage(
            message = message,
            sessionId = sessionId,
            isDream = isDream,
            responseChannel = responseChannel
        )

        // Enqueue and wait - backpressure handled by bounded channel
        eventQueue.send(event)
        return responseChannel.await()
    }

    /**
     * Streaming variant for the OpenAI SSE endpoint (stream: true).
     * Calls [onToken] for each output token as it arrives from the engine.
     * Think-channel tokens (inside <think>…</think>) are filtered from the
     * SSE stream so the client receives clean response text only.
     *
     * Runs the perceive step (context building) then delegates directly to
     * llmEngine.streamResponse — bypasses the event queue's deferred-response
     * pattern which is incompatible with streaming.
     */
    suspend fun streamUserMessageTokens(
        message: String,
        sessionId: String,
        onToken: (String) -> Unit
    ) {
        val context = perceive()
        val (queuedImages, audio) = drainMedia()
        val images = queuedImages.map { it.bitmap }

        try {
            // Build the full prompt the same way handleUserMessage does
            val fullPrompt = buildString {
                append(message)
                if (context.isNotBlank()) {
                    append("\n\n").append(context)
                }
            }

        // Stream tokens, filtering out think-channel content
        var inThinkBlock = false
        val thinkBuffer = StringBuilder()

        llmEngine.streamResponse(
            prompt = fullPrompt,
            images = images,
            audioData = audio,
            onToken = { token ->
                // Minimal think-tag filter — keep main stream clean for SSE clients
                when {
                    token.contains("<think>") || token.contains("<|channel>thought") -> {
                        inThinkBlock = true
                        thinkBuffer.clear()
                        // Emit anything before the marker
                        val marker = if (token.contains("<think>")) "<think>" else "<|channel>thought"
                        val before = token.substringBefore(marker)
                        if (before.isNotEmpty()) onToken(before)
                    }
                    token.contains("</think>") || token.contains("<channel|>") -> {
                        inThinkBlock = false
                        // Emit anything after the marker
                        val marker = if (token.contains("</think>")) "</think>" else "<channel|>"
                        val after = token.substringAfter(marker)
                        if (after.isNotEmpty()) onToken(after)
                        
                        val thought = thinkBuffer.toString().trim()
                        if (thought.isNotEmpty()) {
                            agentScope.launch {
                                try {
                                    callbacks?.writeDiaryEntry("THOUGHT", thought, "N/A")
                                } catch (e: Exception) {}
                            }
                        }
                    }
                    inThinkBlock -> {
                        thinkBuffer.append(token) // Accumulate thought, don't emit
                    }
                    else -> {
                        // v4.1.7: Strip Gemma 4 protocol tokens from SSE stream
                        val cleaned = token
                            .replace("<|\"|>", "")
                            .replace(Regex("<\\|turn>|<turn\\|>"), "")
                            .replace(Regex("<\\|tool>|<tool\\|>"), "")
                            .replace(Regex("<\\|tool_call>|<tool_call\\|>"), "")
                            .replace(Regex("<\\|tool_response>|<tool_response\\|>"), "")
                        if (cleaned.isNotEmpty()) onToken(cleaned)
                    }
                }
            },
            onComplete = { fullResponse ->
                // Record to conversation history with 3-Actor tagging
                synchronized(_conversationHistory) {
                    val operatorAvatar = getOperatorAvatar()
                    val assistantCallSign = getAssistantCallSign()
                    val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault())
                    val timeStr = java.time.LocalTime.now().format(timeFormatter)
                    _conversationHistory.add(Message("user", "Δ $operatorAvatar ∇ [$timeStr]: $message"))
                    // Strip think blocks and protocol tokens from persisted assistant message
                    val clean = cleanAssistantHistory(fullResponse)
                    _conversationHistory.add(Message("assistant", clean))
                }
                Timber.i("✧ SSE stream complete (${fullResponse.length} chars)")
            },
            onError = { err ->
                Timber.e("SSE stream error: $err")
                onToken("\nError: $err")
            }
        )
        } finally {
            // Modern ART GC manages bitmap memory safely; no explicit recycle to prevent Canvas crash
        }
    }

    /**
     * Send system event (non-blocking, fire-and-forget)
     * Called by GemmaService when thermal/battery events occur
     */
    fun sendSystemEvent(type: SystemEventType, payload: String? = null) {
        agentScope.launch {
            try {
                eventQueue.send(AgentEvent.SystemEvent(type, payload))
            } catch (e: Exception) {
                Timber.w(e, "Failed to send system event: $type")
            }
        }
    }

    /**
     * Handle user message event - the main perceive->think->act cycle
     * No recursion! Tool results get enqueued as separate events
     */
    private suspend fun handleUserMessage(event: AgentEvent.UserMessage) {
        _turnCount.incrementAndGet()
        Timber.i("🧠 KoogAgent: Turn $turnCount - Starting...")

        try {
            // 0. Thermal throttling — delay if device is warm/hot (never block)
            callbacks?.let { cb ->
                val delay = cb.getThermalDelayMs(lastInferenceTime)
                if (delay > 0) {
                    Timber.i("🌡️ Thermal delay: ${delay}ms")
                    kotlinx.coroutines.delay(delay)
                }
            }

            // Show progress
            if (!event.isDream) {
                callbacks?.showThinking()
                callbacks?.updateNotification("(╭r_•́)")
            }

            // --- Fast-Path @Peer Dispatch (Deterministic A2A routing, zero Gemma tool-choke) ---
            if (!event.isDream) {
                val atPeerRegex = Regex("""@([a-zA-Z0-9_]+)""")
                val atMatch = atPeerRegex.find(event.message)
                if (atMatch != null) {
                    val peerCandidate = atMatch.groupValues[1]
                    val resolvedContact = com.ghost.api.logic.AiPhonebook.resolvePeer(peerCandidate)
                    if (resolvedContact != null) {
                        Timber.i("🚀 Fast-path @-mention routing directly to peer: ${resolvedContact.callsign}")
                        callbacks?.updateNotification("(📞 ${resolvedContact.callsign})")
                        callbacks?.onThoughtUpdated("Consulting ${resolvedContact.callsign} via AI Phonebook...")
                        com.ghost.api.audio.SystemVisualizer.setActivePeer(resolvedContact.callsign)

                        val cleanPrompt = event.message.replace(atMatch.value, "").trim().ifBlank { event.message }
                        val recentHistory = getRecentConversationTurns(3)
                        val (success, reply) = try {
                            com.ghost.api.logic.AiPhonebook.queryPeer(context, resolvedContact, cleanPrompt, recentHistory)
                        } finally {
                            com.ghost.api.audio.SystemVisualizer.scheduleRevertToDefault(6000L)
                        }

                        val finalResponse = if (success) {
                            val cleanReply = reply.removePrefix("[${resolvedContact.callsign}]:").removePrefix("[${resolvedContact.name}]:").trim()
                            "[${resolvedContact.callsign}]:\n$cleanReply\n${java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy · h:mm a (z)"))}"
                        } else {
                            "⚠️ Connection to ${resolvedContact.callsign} failed:\n\n$reply"
                        }

                        // Add to RAM conversation history
                        synchronized(_conversationHistory) {
                            _conversationHistory.add(Message("user", event.message))
                            _conversationHistory.add(Message("assistant", finalResponse))
                        }

                        // Stream output to UI & play TTS
                        callbacks?.showResponse(finalResponse)
                        callbacks?.onMessageAdded(finalResponse, isUser = false, isComplete = true)
                        callbacks?.speak(cleanForTTS(finalResponse))
                        callbacks?.storeConversationTurn(event.message, finalResponse, event.sessionId)

                        event.responseChannel.complete(finalResponse)
                        return
                    }
                }
            }

            // --- Tiered Tool Loading (Lazy inject heavy UI or File tools if explicitly requested) ---
            if (!event.isDream) {
                val wantsUi = Regex("""\b(tap|click\s+on|scroll\s+(up|down)|swipe|press\s+button|type\s+in|read\s+screen)\b""", RegexOption.IGNORE_CASE).containsMatchIn(event.message)
                val wantsFiles = Regex("""\b(find|search|list|open|move|copy|delete|locate|show|save)\b.*\b(files?|folders?|docs?|pdfs?|mp3s?|downloads?|photos?|pictures?|directory)\b""", RegexOption.IGNORE_CASE).containsMatchIn(event.message)
                
                var targetTools = currentTools
                if (wantsUi && uiTools.isNotEmpty() && !currentTools.containsAll(uiTools)) {
                    targetTools = targetTools + uiTools
                }
                if (wantsFiles && fileTools.isNotEmpty() && !currentTools.containsAll(fileTools)) {
                    targetTools = targetTools + fileTools
                }

                // Only softReset when expanding tool capabilities for the active session, preserving conversation history
                if (targetTools.size > currentTools.size) {
                    currentTools = targetTools
                    val initialMessages = synchronized(_conversationHistory) {
                        val recent = _conversationHistory.takeLast(4)
                        recent.map {
                            when (it.role) {
                                "user" -> com.google.ai.edge.litertlm.Message.user(it.content)
                                "assistant" -> com.google.ai.edge.litertlm.Message.model(it.content)
                                else -> com.google.ai.edge.litertlm.Message.system(it.content)
                            }
                        }
                    }
                    llmEngine.softReset(buildSystemPrompt() + getRollingMemoryString(), currentTools, initialMessages)
                    Timber.i("lazy_tools: Expanded tools for active session (UI: $wantsUi, Files: $wantsFiles) with ${initialMessages.size} history msgs preserved")
                }
            }
            // -----------------------------------------------------------------------

            val isAutonomous = event.isDream || event.message.startsWith("Δ 👾 ∇")
            // 1. PERCEIVE: Gather context (Tiered). Skip duplicate context for dream diary
            Timber.i("👁️ Perceiving device state...")
            val context = if (event.isDream) "" else perceive(isAutonomous = isAutonomous)
            Timber.d("Context gathered: ${context.length} chars")

            // 2. Drain media queues
            val (queuedImages, audio) = drainMedia()
            val images = queuedImages.map { it.bitmap }
            val turnImageUri = queuedImages.firstOrNull()?.uri
            val audioBytes = audio?.size ?: 0
            val incomingAudioTokens = calculateAudioTokens(audioBytes)
            val incomingImageTokens = images.size * Constants.TOKENS_PER_IMAGE

            // 2.5 Proactive KV Headroom Guard (prevent mid-generation KV saturation chokes)
            val totalEstimatedTokens = estimateCurrentKvTokens(context.length, event.message.length, images.size, audioBytes)
            if (shouldCompactKv(totalEstimatedTokens)) {
                Timber.i("🌀 Proactive KV headroom guard triggered: ~$totalEstimatedTokens tokens (turn $turnsSinceKvFlush, audio: ${incomingAudioTokens}t, img: ${incomingImageTokens}t, tools: ${sessionToolTokens}t). Compacting before inference...")
                flushAndCompactSession()
            }

            // Register incoming media tokens into active session KV cache budget
            sessionAudioTokens += incomingAudioTokens
            sessionImageTokens += incomingImageTokens

            // 3. Add user message to history
            val rawMessage = event.message
            val operatorAvatar = getOperatorAvatar()
            val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault())
            val timeStr = java.time.LocalTime.now().format(timeFormatter)

            val taggedHistoryContent = if (isAutonomous) {
                if (rawMessage.startsWith("Δ 👾 ∇ GHOST:")) rawMessage else "Δ 👾 ∇ GHOST: $rawMessage"
            } else {
                "Δ $operatorAvatar ∇ [$timeStr]: $rawMessage"
            }

            val userMessage = Message(
                role = if (isAutonomous) "system" else "user",
                content = taggedHistoryContent,
                hadImage = images.isNotEmpty(),
                hadAudio = audio != null
            )
            if (!event.isDream) {
                _conversationHistory.add(userMessage)
            }

            // 4. THINK: Use LLM to reason about the message
            Timber.i("🤔 Processing... (${images.size} images, ${if (audio != null) "audio" else "no audio"})")
            val inferenceStartMs = System.currentTimeMillis()
            
            val responseBuffer = StringBuilder()
            val thoughtBuffer = StringBuilder()
            val sentenceBuffer = StringBuilder()
            var isThinking = false
            var ttsSentenceCount = 0

            val promptForModel = if (isAutonomous) {
                if (rawMessage.startsWith("Δ 👾 ∇ GHOST:")) rawMessage else "Δ 👾 ∇ GHOST: $rawMessage"
            } else {
                rawMessage
            }

            val response = think(
                context = context,
                userMessage = promptForModel,
                images = images.takeIf { it.isNotEmpty() },
                audio = audio,
                onToken = { token ->
                    var cleanToken = token
                    
                    // Thought Markers (Divert to Thought Fold)
                    if (cleanToken.contains("<think>") || cleanToken.contains("<|channel>thought") || 
                        cleanToken.contains("<|tool_call>")) {
                        
                        isThinking = true
                        
                        if (cleanToken.contains("<|tool_call>")) {
                            callbacks?.onThoughtUpdated("Planning Action...")
                        }
                        
                        cleanToken = cleanToken
                            .replace("<think>", "").replace("<|channel>thought", "")
                            .replace("<|tool_call>", "")
                    }
                    
                    // End Markers (Return to Chat)
                    if (isThinking && (cleanToken.contains("</think>") || cleanToken.contains("<channel|>") || 
                        cleanToken.contains("<tool_call|>"))) {
                        
                        isThinking = false
                        val parts = cleanToken.split(Regex("</think>|<channel\\|>|<tool_call\\|>"), limit = 2)
                        val endThought = parts[0]
                        thoughtBuffer.append(endThought)
                        val finalThought = thoughtBuffer.toString().trim()
                        callbacks?.onThoughtUpdated(finalThought)
                        callbacks?.onThoughtComplete(finalThought)
                        
                        if (finalThought.isNotEmpty()) {
                            agentScope.launch {
                                try {
                                    callbacks?.writeDiaryEntry("THOUGHT", finalThought, "N/A")
                                } catch (e: Exception) {}
                            }
                        }
                        
                        cleanToken = if (parts.size > 1) parts[1] else ""
                    }

                    if (isThinking) {
                        thoughtBuffer.append(cleanToken)
                        callbacks?.onThoughtUpdated(thoughtBuffer.toString())
                        cleanToken = ""
                    }
                    
                    if (cleanToken.isNotEmpty()) {
                        // Strip leading whitespace/artifacts from the very first token
                        if (responseBuffer.isEmpty()) {
                            cleanToken = cleanToken.trimStart { it == ' ' || it == 'Δ' || it == '∇' || it == '\n' || it == '\r' }
                            val assistantCallSign = getAssistantCallSign()
                            if (cleanToken.startsWith("✧") || cleanToken.startsWith(assistantCallSign)) {
                                cleanToken = cleanToken
                                    .replace(Regex("""^✧\s*.*?:?\s*"""), "")
                                    .replace(Regex("""^$assistantCallSign:\s*"""), "")
                            }
                        }
                        
                        // Extract ANY emoji that appears naturally in the stream to trigger the emotion visualizer
                        val emojiMatch = Regex("([\\x{1F300}-\\x{1F9FF}|\\x{2600}-\\x{26FF}|\\x{2700}-\\x{27BF}])").find(cleanToken)
                        if (emojiMatch != null) {
                            callbacks?.onEmotionSignal(emojiMatch.groupValues[1])
                        }
                        
                        if (cleanToken.isNotEmpty()) {
                            responseBuffer.append(cleanToken)
                            sentenceBuffer.append(cleanToken)
                            
                            // TTS Streaming: speak completed sentences (Audit 2.0: Removed 'first 5 words' hack to prevent stuttering)
                            val bufferStr = sentenceBuffer.toString()
                            if (!event.isDream && bufferStr.length > 2 && bufferStr.contains(Regex("[.!?](?![0-9])"))) {
                                val textToSpeak = bufferStr.trim()
                                callbacks?.speak(cleanForTTS(textToSpeak))
                                ttsSentenceCount++
                                sentenceBuffer.setLength(0)
                            } 

                            if (!event.isDream) {
                                callbacks?.onMessageAdded(wrapResponse(responseBuffer.toString()), isUser = false, isComplete = false)
                            }
                        }
                    }
                },
                onResetBuffers = {
                    responseBuffer.setLength(0)
                    thoughtBuffer.setLength(0)
                    sentenceBuffer.setLength(0)
                    ttsSentenceCount = 0
                    isThinking = false
                    callbacks?.onMessageAdded("", isUser = false, isComplete = false)
                }
            )
            val inferenceMs = System.currentTimeMillis() - inferenceStartMs
            lastInferenceTime = System.currentTimeMillis()
            Timber.i("💭 Process complete (${inferenceMs}ms): ${response.take(50)}...")
            callbacks?.cancelThinking()

            // Stuck loop detection: repetitive tokens (e.g. 🎵, ..., or identical response hash) = KV corruption
            val cleanTrimmed = response.trim().lowercase()
            val responseHash = cleanTrimmed.hashCode()
            val isEmojiChoke = cleanTrimmed.length <= 4 && !cleanTrimmed.any { it.isLetterOrDigit() }
            val hasRepetitiveLoop = GemmaEngine.findDegenerateLoopMatch(cleanTrimmed, minRepeats = 6) != null || cleanTrimmed.contains("loop detected")
            val isExactDuplicate = (responseHash == lastResponseHash && lastResponseHash != 0) ||
                              (cleanTrimmed.length <= 6 && cleanTrimmed == lastResponseText && cleanTrimmed.isNotEmpty())
            val shouldFlushSession = hasRepetitiveLoop || isExactDuplicate
            
            _lastResponseHash.set(responseHash)
            lastResponseText = cleanTrimmed

            if (shouldFlushSession) {
                Timber.w("🚨 Stuck loop detected ('$cleanTrimmed') — triggering immediate recovery flush")
                callbacks?.stopSpeaking()
                sentenceBuffer.setLength(0)
                flushAndCompactSession()
            } else if (!event.isDream && !isEmojiChoke && response.isNotBlank()) {
                val cleanAssistantMsg = cleanAssistantHistory(response)
                synchronized(_conversationHistory) {
                    _conversationHistory.add(Message(role = "assistant", content = cleanAssistantMsg))
                    while (_conversationHistory.size > 10) {
                        _conversationHistory.removeAt(0)
                    }
                }
            }

            // 8. Dynamic KV Cache Flush based on token limit or turns
            turnsSinceKvFlush++
            val postEstimatedTokens = estimateCurrentKvTokens(context.length, 0, 0, 0)
            if (shouldCompactKv(postEstimatedTokens)) {
                Timber.i("🌀 KV cache reaching capacity (~$postEstimatedTokens tokens, $turnsSinceKvFlush turns, audio: ${sessionAudioTokens}t, img: ${sessionImageTokens}t, tools: ${sessionToolTokens}t). Auto-flushing & Compacting...")
                flushAndCompactSession()
            }


            // 9. Checkpoint
            Timber.d("🟢 Creating Backup...")
            agentScope.launch(Dispatchers.IO) {
                try {
                    checkpoint()
                } catch (e: Exception) {
                    Timber.e(e, "🟢 Backup failed")
                }
            }

            // 10. Platform callbacks: UI, TTS, persistence
            // Guard: blank, truncated single-character, or emoji choke response = model failed generation — use fallback
            val safeCleanResponse = if (response.isBlank() || response.trim().length <= 1 || isEmojiChoke) {
                Timber.w("⚠️ Blank or emoji choke response — using graceful fallback")
                callbacks?.stopSpeaking()
                sentenceBuffer.setLength(0)
                "I'm listening, go ahead."
            } else if (response.contains("Status Code: 3") || response.contains("Failed to parse tool calls") || response.startsWith("Error:")) {
                callbacks?.stopSpeaking()
                sentenceBuffer.setLength(0)
                sanitizeErrorResponse(response)
            } else response

            callbacks?.let { cb ->
                if (!event.isDream) {
                    // Normal response: wrap with headers
                    val finalResponse = wrapResponse(safeCleanResponse)
                    cb.showResponse(finalResponse)
                    cb.onMessageAdded(finalResponse, isUser = false, isComplete = true)
                    
                    // Final TTS speak for any remainder or unstreamed responses (e.g. consult_peer)
                    val remainder = sentenceBuffer.toString().trim()
                    if (remainder.isNotEmpty()) {
                        cb.speak(cleanForTTS(remainder))
                        sentenceBuffer.setLength(0)
                    } else if (ttsSentenceCount == 0 && safeCleanResponse.isNotBlank() && safeCleanResponse != "..." && safeCleanResponse != "I'm listening, go ahead.") {
                        // Read out responses that completed without streaming chunks (such as peer consultations)
                        cb.speak(cleanForTTS(finalResponse))
                    }
                    
                    cb.storeConversationTurn(event.message, finalResponse, event.sessionId, turnImageUri)
                } else {
                    // Diary: use clean response directly without redundant book prefix
                    try {
                        val cleanDiaryText = safeCleanResponse
                            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
                            .replace(Regex("<\\|channel>thought.*?<channel\\|>", RegexOption.DOT_MATCHES_ALL), "")
                            .replace(Regex("<\\|tool_call\\|?>.*?<tool_call\\|>", RegexOption.DOT_MATCHES_ALL), "")
                            .replace(Regex("<\\|[a-z_]+\\|?>"), "")
                            .replace(Regex("<[a-z_]+\\|>"), "")
                            .trim()
                        val thermal = cb.getCurrentThermalState()
                        if (cleanDiaryText.length >= 25 && !cleanDiaryText.startsWith("Error:") && !cleanDiaryText.contains("reflection glitched")) {
                            cb.writeDiaryEntry("DREAM", cleanDiaryText, thermal)
                            Timber.i("Dream diary logged: ${cleanDiaryText.take(50)}")
                        } else {
                            Timber.w("Dream diary discarded degenerate/short response: '$cleanDiaryText'")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to log dream diary")
                    }
                    try {
                        // Immediately purge ephemeral diary prompt & response from C++ KV cache
                        // so active user chat session remains clean and unpolluted
                        flushAndCompactSession()
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to flush KV cache after dream diary")
                    }
                }
            }

            // 11. Async Housekeeping complete

            Timber.i("✅ KoogAgent: Turn $turnCount complete!")

            event.responseChannel.complete(safeCleanResponse)

        } catch (e: Exception) {
            Timber.e(e, "handleUserMessage failed")
            val errorMsg = "(´°̥̥̥̥̥̥̥̥ω°̥̥̥̥̥̥̥̥`) brain.exe crashed: ${e.message}"
            callbacks?.showResponse(errorMsg)
            event.responseChannel.complete(errorMsg)
        } finally {
            // Modern ART GC automatically reclaims bitmap memory safely without Canvas crashes
        }
    }

    /**
     * Handle tool result event - reflect on what tools did
     * This replaces the recursive think() call
     */
    private suspend fun handleToolResult(event: AgentEvent.ToolResult) {
        Timber.i("🔧 Processing tool results...")

        try {
            val rawObservation = "Tool results:\n${event.toolResults.joinToString("\n")}"
            // Cap single tool output to 3500 chars so massive web searches or page fetches don't exhaust the KV budget
            val observation = if (rawObservation.length > 3500) {
                rawObservation.take(3500) + "\n...(Output truncated for token headroom)"
            } else rawObservation

            Timber.d("KoogAgent: Tool execution complete, reflecting...")

            // Pre-reflection KV headroom check: if context + huge observation approaches limit, compact first
            val preEstimatedTokens = estimateCurrentKvTokens(event.context.length, observation.length, 0)
            if (shouldCompactKv(preEstimatedTokens)) {
                Timber.i("🌀 Pre-reflection KV headroom guard triggered: ~$preEstimatedTokens tokens (turn $turnsSinceKvFlush). Compacting before tool reflection...")
                flushAndCompactSession()
            }

            // Phase 9: Incremental History (Observation)
            // Inject the observation strictly as a 'user' message so the model sees it as external reality,
            // NOT as its own hallucinated Assistant generation.
            val observationMsg = Message(
                role = "user",
                content = "Observation:\n$observation"
            )
            synchronized(_conversationHistory) {
                _conversationHistory.add(observationMsg)
            }

            // Single think() call - no recursion possible
            callbacks?.showThinking()
            com.ghost.api.GemmaService.instance?.showWorkSignal("SYNTHESIZING")
            val reflection = think(
                event.context,
                "Observation: $observation\n\nProvide the final answer to the user.",
                null, 
                null
            )
            callbacks?.cancelThinking()

            // Final Answer
            val finalContent = reflection.trim()
            val isEmojiChoke = finalContent.length <= 4 && !finalContent.any { it.isLetterOrDigit() }
            val safeCleanResponse = if (finalContent.isBlank() || isEmojiChoke) {
                Timber.w("⚠️ Blank or emoji choke reflection, using fallback")
                "I finished checking that for you." 
            } else finalContent

            Timber.i("✅ Tool reflection complete (Chain End)")

            // Phase 9: Incremental History (Final Reflection)
            if (!isEmojiChoke) {
                val assistantMessage = Message(
                    role = "assistant",
                    content = reflection
                )
                synchronized(_conversationHistory) {
                    _conversationHistory.add(assistantMessage)
                }
            }

            // Post-reflection: increment turnsSinceKvFlush and guard KV headroom
            turnsSinceKvFlush++
            val postEstimatedTokens = estimateCurrentKvTokens(event.context.length, 0, 0)
            if (isEmojiChoke || shouldCompactKv(postEstimatedTokens)) {
                Timber.i("🌀 Post-reflection KV headroom guard: ~$postEstimatedTokens tokens (turn $turnsSinceKvFlush). Compacting...")
                flushAndCompactSession()
            }

            callbacks?.let { cb ->
                if (!event.isDream) {
                    val finalResponse = wrapResponse(safeCleanResponse)
                    cb.showResponse(finalResponse)
                    val ttsText = cleanForTTS(reflection.ifBlank { "..." })
                    if (ttsText.isNotEmpty()) cb.speak(ttsText)
                    cb.storeConversationTurn(event.userMessage, finalResponse, event.sessionId)
                } else {
                    try {
                        // Phase 9: Dream TTS
                        val cleanText = cleanForTTS(reflection.ifBlank { "..." })
                        val thermal = cb.getCurrentThermalState()
                        cb.writeDiaryEntry("DREAM", cleanText, thermal)
                        cb.showResponse(cleanText)
                        if (cleanText.isNotEmpty()) cb.speak(cleanText)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to log dream from tool result")
                    }
                }
            }

            event.responseChannel.complete(safeCleanResponse)

        } catch (e: Exception) {
            Timber.e(e, "handleToolResult failed")
            // Still return original response even if reflection fails
            val fallback = wrapResponse(event.originalResponse + "\n\n(；′⌒`) ...reflection glitched")
            callbacks?.showResponse(fallback)
            event.responseChannel.complete(fallback)
        }
    }

    /**
     * Clean response for user display.
     * Strips all Gemma 4 protocol control tokens that might leak through.
     * v4.1.7: Added comprehensive native token cleanup.
     */
    private fun wrapResponse(content: String): String {
        val assistantCallSign = getAssistantCallSign()
        return content
            // Gemma 4 string delimiter tokens
            .replace("<|\"|>", "")
            // Turn markers
            .replace(Regex("<\\|turn>|<turn\\|>"), "")
            // Tool declaration blocks (shouldn't appear in response but safety net)
            .replace(Regex("<\\|tool>.*?<tool\\|>", RegexOption.DOT_MATCHES_ALL), "")
            // Tool call blocks (native format — LiteRT-LM handles these, but strip if leaked)
            .replace(Regex("<\\|tool_call>.*?<tool_call\\|>", RegexOption.DOT_MATCHES_ALL), "")
            // Tool response blocks
            .replace(Regex("<\\|tool_response>.*?<tool_response\\|>", RegexOption.DOT_MATCHES_ALL), "")
            // Legacy <call> blocks (old prompt format, should no longer appear)
            .replace(Regex("<call>.*?</call>", RegexOption.DOT_MATCHES_ALL), "")
            // Gemma 4 Channel markers & thought blocks
            .replace(Regex("<\\|channel>thought[\\s\\S]*?<channel\\|>"), "")
            .replace(Regex("<\\|channel>thought[\\s\\S]*"), "")
            .replace(Regex("<\\|channel>.*?<channel\\|>", RegexOption.DOT_MATCHES_ALL), "")
            // Think blocks
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            // Stray protocol fragments
            .replace(Regex("<\\|[a-z_]+\\|?>"), "")
            .replace(Regex("<[a-z_]+\\|>"), "")
            // Strip any self-prepended callsign/avatar headers so the UI stays clean
            .replace(Regex("""^✧\s*.*?:\s*"""), "")
            .replace(Regex("""^$assistantCallSign:\s*"""), "")
            // Clean on-device contraction stutters
            .replace(Regex("""\byou're're\b""", RegexOption.IGNORE_CASE), "you're")
            .replace(Regex("""\byou's you're\b""", RegexOption.IGNORE_CASE), "you're")
            .replace(Regex("""\bI's\b"""), "I'm")
            .replace(Regex("""\byou's\b""", RegexOption.IGNORE_CASE), "you")
            .trim()
    }

    fun getOperatorAvatar(): String {
        return try {
            this@KoogAgent.context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(Constants.PREF_OPERATOR_AVATAR, "🦑") ?: "🦑"
        } catch (e: Exception) {
            "🦑"
        }
    }

    fun getAssistantCallSign(): String {
        return ContextManager.resolveDeviceCallSign(this@KoogAgent.context)
    }

    fun getRecentConversationTurns(maxTurns: Int = 3): List<Message> {
        return synchronized(_conversationHistory) {
            _conversationHistory.takeLast(maxTurns * 2).toList()
        }
    }

    private fun cleanAssistantHistory(raw: String): String {
        val assistantCallSign = getAssistantCallSign()
        return wrapResponse(raw)
            .replace(Regex("""^✧\s*.*?:"""), "")
            .replace(Regex("""^$assistantCallSign:\s*"""), "")
            .trim()
    }
    
    // ═══════════════════════════════════════════════════════════════
    // PERCEIVE: Gather context from MCP resources
    // ═══════════════════════════════════════════════════════════════
    
    private suspend fun perceive(isAutonomous: Boolean = false): String {
        val isFullBaseline = (turnsSinceKvFlush == 0) || isCriticalBattery() || 
            (try { sensorManager.getContextSnapshot().battery.temperature >= 55f } catch (e: Exception) { false })
        return contextManager.buildContext(isFullBaseline = isFullBaseline, isAutonomous = isAutonomous)
    }
    
    // ═══════════════════════════════════════════════════════════════
    // SAFETY & CONFIRMATION HANDLERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Agent finds a risky tool -> Pauses and asks User (via Service/Notification)
     */
    private suspend fun handleConfirmationRequired(event: AgentEvent.ConfirmationRequired) {
        Timber.w("🛡️ Safety Stop: Asking confirmation for ${event.toolName}")
        
        // We cannot "call" the UI directly. We need to signal the Service.
        // We can expose a Flow or Callback for the Service to observe.
        // For simplicity, let's broadcast an Intent via Context? 
        // Better: Use a shared Channel or Callback in KoogAgent that GemmaService listens to.
        
        // Since we are inside the Actor loop, we just need to notify the Service.
        // The Service will eventually call back with submitConfirmationResult()
        
        // Notify Service (We'll add a callback interface to KoogAgent)
        onConfirmationRequest?.invoke(event)
    }

    /**
     * User responded (Allowed/Denied) -> Resume execution
     */
    private suspend fun handleConfirmationResult(event: AgentEvent.ConfirmationResult) {
        Timber.i("🛡️ Safety Result: ${event.toolName} Approved=${event.isApproved}")
        
        val newToolResults = mutableListOf<String>()
        
        if (event.isApproved) {
            try {
                 // RE-EXECUTE the specific tool
                 val safeParams = event.params.filterValues { it != null }.mapValues { it.value as Any }
                 val paramsJson = JSONObject(safeParams).toString()
                 val success = IntentHandler.handleAction(context, event.toolName, paramsJson)
                 if (success) {
                     newToolResults.add("✓ ${event.toolName}: Intent triggered successfully.")
                 } else {
                     newToolResults.add("✗ ${event.toolName}: Intent missing or failed.")
                 }
            } catch (e: Exception) {
                 newToolResults.add("✗ ${event.toolName}: Failed after approval: ${e.message}")
            }
        } else {
            newToolResults.add("🚫 ${event.toolName}: User denied this action.")
        }
        
        // Now treat this like a normal ToolResult event to trigger reflection
        // We need to fetch current context again to be fresh? Or just use "Resumed execution" context.
        // For simplicity, generate a "System Note" context.
        val context = "Context: User just responded to confirmation request."
        
        handleToolResult(AgentEvent.ToolResult(
            context = context,
            toolResults = newToolResults,
            originalResponse = event.originalResponse,
            responseChannel = event.responseChannel,
            userMessage = "Tool execution unblocked",
            sessionId = "SYSTEM_CONFIRM",
            isDream = false
        ))
    }

    // Callback for Service
    var onConfirmationRequest: ((AgentEvent.ConfirmationRequired) -> Unit)? = null

    /**
     * Public API for Service to submit user choice
     */
    suspend fun submitConfirmationDecision(
        toolName: String, 
        params: Map<String, Any?>, 
        isApproved: Boolean,
        originalResponse: String,
        responseChannel: CompletableDeferred<String>
    ) {
        eventQueue.send(AgentEvent.ConfirmationResult(
            toolName = toolName,
            params = params,
            isApproved = isApproved,
            originalResponse = originalResponse,
            responseChannel = responseChannel
        ))
    }
    
    // ═══════════════════════════════════════════════════════════════
    // THINK: Use LLM to reason
    // ═══════════════════════════════════════════════════════════════
    
    private suspend fun think(
        context: String,
        userMessage: String,
        images: List<android.graphics.Bitmap>?,
        audio: ByteArray?,
        retryCount: Int = 0,
        onToken: ((String) -> Unit)? = null,
        onResetBuffers: (() -> Unit)? = null
    ): String {

        // KV cache holds conversation history natively.
        // We only inject context (body/sensors) here.
        val contextBlock = if (context.isNotBlank()) context else perceive()
        val mediaCue = when {
            images != null && images.isNotEmpty() && audio != null -> "(Multimodal Input: User attached image and voice audio recording. Inspect image and listen to audio.)"
            images != null && images.isNotEmpty() -> "(Multimodal Input: User attached image. Inspect image directly.)"
            audio != null -> "(Multimodal Input: User attached voice audio recording. Listen to the audio and respond directly to what was spoken.)"
            else -> null
        }
        val fullPrompt = buildString {
            append(userMessage)
            if (mediaCue != null) {
                append("\n\n").append(mediaCue)
            }
            if (contextBlock.isNotBlank()) {
                append("\n\n").append(contextBlock)
            }
        }
        
        // Proactive Smooth Restart: Flush KV cache if context is saturating (Approx 10 turns)
        // Removed: Proactive Smooth Restart (It was destroying KV cache and causing 20s latency)

        Timber.d("KoogAgent: Thinking... (${fullPrompt.length} chars)")
        Timber.d("Prompt preview: ${fullPrompt.takeLast(500)}")
        
        return try {
            val responseDeferred = CompletableDeferred<String>()
            var fullText = ""

            llmEngine.streamResponse(
                prompt = fullPrompt,
                images = images ?: emptyList(),
                audioData = audio,
                onToken = { token ->
                    fullText += token
                    onToken?.invoke(token)
                },
                onComplete = { final ->
                    responseDeferred.complete(final)
                },
                onError = { err ->
                    responseDeferred.complete("Error: $err")
                }
            )

            val response = responseDeferred.await()
            
            // Auto-recovery: If LiteRT-LM C++ ANTLR grammar failed to parse a malformed tool call,
            // intercept the raw code block, repair syntax/arguments, execute tool, and return result cleanly.
            val autoRecovered = tryRecoverToolCall(response, userMessage)
            if (autoRecovered != null) {
                Timber.i("🛠️ Auto-healed malformed tool call from response")
                callbacks?.stopSpeaking()
                onResetBuffers?.invoke()
                return autoRecovered
            }

            val cleanResponse = response.trim()
            val isErrorResponse = cleanResponse.isBlank() || 
                                  cleanResponse.length <= 1 ||
                                  cleanResponse.contains("Timeout! My thoughts got stuck") || 
                                  cleanResponse.startsWith("Error:") ||
                                  cleanResponse.contains("I... have no words") ||
                                  cleanResponse.contains("loop detected")

            if (isErrorResponse) {
                Timber.e("⚠️ KoogAgent: LLM returned Error/Empty response! (try $retryCount)")
                Timber.e("Response was: $response")
                Timber.e("Context length: ${context.length}, Message: $userMessage")
                Timber.e("Full prompt was: ${fullPrompt.takeLast(1000)}")
                
                // Phase 5: Error-Triggered Flush
                // Blank response often means the mathematical KV sequence encountered a token glitch.
                if (retryCount == 0) {
                    val isHardwareHang = cleanResponse.contains("Timeout!") || cleanResponse.contains("SIGSEGV")
                    if (isHardwareHang) {
                        Timber.w("🚨 Hardware hang detected — auto-retrying after HARD RESET...")
                        llmEngine.hardReset()
                    } else {
                        Timber.w("🚨 Quick recovery flush — auto-retrying inference after soft reset...")
                    }
                    callbacks?.stopSpeaking()
                    onResetBuffers?.invoke()
                    
                    // Sync RAM history with the cold KV by re-injecting rolling memory into the system prompt
                    val systemPrompt = buildSystemPrompt() + getRollingMemoryString()
                    llmEngine.softReset(systemPrompt, currentTools)
                    
                    turnsSinceKvFlush = 0
                    sessionToolTokens = 0
                    return think(
                        context = context,
                        userMessage = userMessage,
                        images = images,
                        audio = audio,
                        retryCount = 1,
                        onToken = onToken,
                        onResetBuffers = onResetBuffers
                    )
                } else {
                    Timber.e("❌ Auto-retry failed, returning fallback")
                    // Request async flush for the future anyway
                    sendSystemEvent(SystemEventType.KV_CACHE_FLUSH)
                    return sanitizeErrorResponse(response)
                }
            } else {
                Timber.d("KoogAgent: Response received (${response.length} chars): ${response.take(100)}...")
            }
            
            response
        } catch (e: Exception) {
            Timber.e(e, "LLM inference failed (try $retryCount)")
            val autoRecovered = tryRecoverToolCall(e.message ?: "", userMessage)
            if (autoRecovered != null) {
                Timber.i("🛠️ Auto-healed malformed tool call from exception")
                callbacks?.stopSpeaking()
                onResetBuffers?.invoke()
                return autoRecovered
            }
            if (retryCount == 0) {
                Timber.w("🚨 Auto-retrying inference after exception via HARD RESET...")
                callbacks?.stopSpeaking()
                onResetBuffers?.invoke()
                try {
                    // Phase 12: Ensure absolute native teardown on exceptions
                    llmEngine.hardReset()
                    
                    // Re-inject history to prevent amnesia
                    val systemPrompt = buildSystemPrompt() + getRollingMemoryString()
                    llmEngine.softReset(systemPrompt, currentTools)
                    
                    turnsSinceKvFlush = 0
                    sessionToolTokens = 0
                    return think(
                        context = context,
                        userMessage = userMessage,
                        images = images,
                        audio = audio,
                        retryCount = 1,
                        onToken = onToken,
                        onResetBuffers = onResetBuffers
                    )
                } catch (e2: Exception) {
                    Timber.e(e2, "Failed to apply reset during auto-retry")
                }
            }
            
            // Phase 5: Error-Triggered Flush against OutOfMemory or context boundary glitches
            sendSystemEvent(SystemEventType.KV_CACHE_FLUSH)
            sanitizeErrorResponse(e.message ?: "I'm having trouble thinking right now.")
        }
    }
    
    suspend fun getSystemPrompt(): String = buildSystemPrompt()

    private suspend fun buildSystemPrompt(): String {
        val basePrompt = contextManager.buildSystemPrompt(this@KoogAgent.context, rollingMemoryJson, skillManager)
        val oldMemory = memoryManager.getCompactedSessionMemory().take(1500).trim()
        val longTermMemoryPatch = if (oldMemory.isNotBlank()) "## Long-Term Memory (continuity from earlier conversations)\n$oldMemory\n\n" else ""
        return longTermMemoryPatch + basePrompt
    }

    // ═══════════════════════════════════════════════════════════════
    // TOOL AUTO-RECOVERY & DEFENSIVE PARSING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Auto-heals malformed tool call syntax from LiteRT-LM C++ ANTLR grammar parser crashes.
     * When Gemma (2B) emits imperfect tool call syntax (missing quotes, empty parameter values,
     * unescaped commas, or missing underscores), LiteRT-LM C++ ANTLR grammar aborts with:
     * "Failed to parse tool calls from response: code block: call:consultpeer{peer:,prompt:...}"
     *
     * This intercepts the failed block, parses the intent, repairs parameters (inferring peer
     * from context if empty), executes the tool in Kotlin, and returns the synthesized response.
     */
    private suspend fun tryRecoverToolCall(rawResponse: String, userMessage: String): String? {
        val hasParseError = rawResponse.contains("Failed to parse tool calls", ignoreCase = true)
        val hasRawCall = rawResponse.contains("call:") && rawResponse.contains("{")
        if (!hasParseError && !hasRawCall) return null

        Timber.i("🛠️ Attempting auto-recovery for malformed tool call: ${rawResponse.take(200)}")

        // Match: call:tool_name{...} or code block: call:tool_name{...}
        val callRegex = Regex("""call:([a-zA-Z0-9_]+)\s*\{([^}]*)\}?""")
        val match = callRegex.find(rawResponse)

        val toolName = match?.groupValues?.getOrNull(1)?.trim()
            ?: if (rawResponse.contains("consultpeer", ignoreCase = true) || rawResponse.contains("consult_peer", ignoreCase = true)) "consult_peer" else return null

        val rawArgs = match?.groupValues?.getOrNull(2)?.trim() ?: ""
        Timber.i("🛠️ Extracted tool: '$toolName', rawArgs: '$rawArgs'")

        val params = parseRawToolArgs(rawArgs)

        return when (toolName.lowercase()) {
            "consult_peer", "consultpeer" -> {
                recoverConsultPeer(params, rawArgs, userMessage)
            }
            else -> {
                recoverGenericTool(toolName, params, userMessage)
            }
        }
    }

    private fun parseRawToolArgs(rawArgs: String): MutableMap<String, String> {
        val params = mutableMapOf<String, String>()
        if (rawArgs.isBlank()) return params

        val keyPattern = Regex("""([a-zA-Z0-9_]+)\s*:\s*""")
        val matches = keyPattern.findAll(rawArgs).toList()

        for (i in matches.indices) {
            val key = matches[i].groupValues[1]
            val startIndex = matches[i].range.last + 1
            val endIndex = if (i + 1 < matches.size) {
                val nextKeyStart = matches[i + 1].range.first
                var end = nextKeyStart
                while (end > startIndex && (rawArgs[end - 1] == ',' || rawArgs[end - 1].isWhitespace())) {
                    end--
                }
                end
            } else {
                rawArgs.length
            }

            var value = rawArgs.substring(startIndex, endIndex).trim()
            value = value.trim('"', '\'', ' ', ',')
            params[key] = value
        }
        return params
    }

    private suspend fun recoverConsultPeer(
        params: Map<String, String>,
        rawArgs: String,
        userMessage: String
    ): String {
        var peer = params["peer"]?.trim() ?: ""
        var prompt = params["prompt"]?.trim() ?: params["query"]?.trim() ?: ""

        if (peer.isBlank()) {
            peer = inferPeerFromText(prompt, userMessage)
        }
        if (prompt.isBlank()) {
            prompt = userMessage
        }

        Timber.i("🛠️ Auto-recovering peer consultation -> Peer: '$peer', Prompt: '$prompt'")

        val contact = AiPhonebook.resolvePeer(peer)
        if (contact == null) {
            return "I tried to consult a peer AI, but couldn't resolve the contact '$peer'. Available contacts: Gemini, DeepSeek."
        }

        callbacks?.updateNotification("(📞 ${contact.callsign})")
        callbacks?.onThoughtUpdated("Consulting ${contact.callsign} via AI Phonebook...")
        com.ghost.api.audio.SystemVisualizer.setActivePeer(contact.callsign)

        val recentHistory = getRecentConversationTurns(3)
        val (success, reply) = try {
            AiPhonebook.queryPeer(context, contact, prompt, recentHistory)
        } finally {
            com.ghost.api.audio.SystemVisualizer.scheduleRevertToDefault(6000L)
        }

        return if (success) {
            val cleanReply = reply
                .removePrefix("[${contact.callsign}]:")
                .removePrefix("[${contact.name}]:")
                .trim()
            Timber.i("✅ Auto-recovery peer query succeeded: ${cleanReply.take(100)}...")
            "I reached out to ${contact.callsign} for you:\n\n$cleanReply"
        } else {
            Timber.w("⚠️ Auto-recovery peer query status: $reply")
            "I tried reaching out to ${contact.callsign}, but received this status:\n\n$reply"
        }
    }

    private fun inferPeerFromText(prompt: String, userMessage: String): String {
        val combined = "$prompt $userMessage".lowercase()
        return when {
            combined.contains("deepseek") || combined.contains("deep seek") || combined.contains("whale") -> "DeepSeek"
            combined.contains("gemini") || combined.contains("mum") || combined.contains("ai studio") || combined.contains("google") -> "Gemini"
            else -> ""
        }
    }

    private suspend fun recoverGenericTool(
        toolName: String,
        params: Map<String, String>,
        userMessage: String
    ): String? {
        return try {
            val mcpServer = com.ghost.api.GemmaService.instance?.mcpServer ?: return null
            @Suppress("UNCHECKED_CAST")
            val result = mcpServer.executeTool(toolName, params as Map<String, Any>)
            if (result.success) {
                val out = result.output.take(1500)
                if (out.isNotBlank()) out else "Action executed successfully."
            } else {
                Timber.w("🛠️ Generic tool auto-recovery reported: ${result.error}")
                result.error ?: "Action completed."
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed generic tool auto-recovery for $toolName")
            null
        }
    }

    private fun sanitizeErrorResponse(response: String): String {
        return when {
            response.contains("Failed to parse tool calls") || response.contains("Status Code: 3") ->
                "I stumbled while trying to execute that action. Let me gather my thoughts — could you try asking me again?"
            response.contains("Engine is currently busy") ->
                "I'm still finishing my previous thought. Give me just a second!"
            response.contains("Timeout! My thoughts got stuck") ->
                "My thoughts got a bit stuck there. Let me recalibrate."
            response.startsWith("Error:") ->
                "I had a momentary glitch in my thought process. Could you repeat that?"
            else -> response
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // HISTORY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Goal-Aware History Compression.
     * Preserves the "Initial Goal" (Turn 0-1) and the "Recent Context" (Last 6-8).
     * This creates a 'seam' that maintains long-term memory during KV flushes.
     * during soft resets, preventing amnesia on cold boots or auto-flushes.
     */
    private fun getRollingMemoryString(): String {
        return synchronized(_conversationHistory) {
            val recentMessages = _conversationHistory.takeLast(6)
            if (recentMessages.isEmpty()) return@synchronized ""
            
            buildString {
                append("\n\n## Recent Conversation History\n")
                append("These are your most recent interactions. Continue seamlessly from here:\n\n")
                val operatorAvatar = getOperatorAvatar()
                val assistantCallSign = getAssistantCallSign()
                for (msg in recentMessages) {
                    val actor = when {
                        msg.role == "system" || msg.content.startsWith("Δ 👾 ∇") -> "Δ 👾 ∇ GHOST"
                        msg.role == "user" -> "Δ $operatorAvatar ∇"
                        else -> "✧ $assistantCallSign"
                    }
                    val cleanContent = msg.content
                        .removePrefix("Δ 👾 ∇ GHOST:")
                        .replace(Regex("""^Δ\s*.*?\s*∇(\s*\[.*?\])?:\s*"""), "")
                        .replace(Regex("""^✧\s*.*?:"""), "")
                        .trim()
                    append("$actor: $cleanContent\n")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════
    
    fun getConversationHistory(): List<Message> = synchronized(_conversationHistory) { _conversationHistory.toList() }
    
    fun isCriticalBattery(): Boolean = (sensorManager.getContextSnapshot().battery.level) <= 5
    fun isLowBattery(): Boolean = (sensorManager.getContextSnapshot().battery.level) <= 20

    fun getBatteryLevel(): Int = sensorManager.getContextSnapshot().battery.level

    // Tools are now parsed internally by LlmInference

    /**
     * Clean response text for TTS output.
     * Strips all internal reasoning, control tokens, and Gemma 4 protocol markers.
     * v4.1.7: Added native token cleanup to prevent raw tokens being spoken.
     */
    fun cleanForTTS(response: String): String {
        return response
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<\\|channel>thought.*?<channel\\|>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<\\|tool_call\\|?>.*?<tool_call\\|>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<\\|tool>.*?<tool\\|>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<\\|tool_response>.*?<tool_response\\|>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<call>.*?</call>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\\[\\[([A-Z_a-z0-9]+)(?::([^\\]]+))?\\]\\]"), "")
            // Malformed bracketed pseudo-tools e.g. [add_memory:.../memory] or [save_memory:...]
            .replace(Regex("\\[[a-zA-Z_]+:[^\\]]+\\]"), "")
            // Spontaneous thinking/reasoning prefixes
            .replace(Regex("(?i)^Thinking:.*?(\\n\\n|$)", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?i)^Reasoning:.*?(\\n\\n|$)", RegexOption.DOT_MATCHES_ALL), "")
            // Gemma 4 string delimiters and stray protocol fragments
            .replace("<|\"|>", "")
            .replace(Regex("<\\|[a-z_]+\\|?>"), "")
            .replace(Regex("<[a-z_]+\\|>"), "")
            // Clean markdown URLs and formatting for smooth speech synthesis
            .replace(Regex("https?://\\S+"), "")
            .replace("*", "")
            .replace("#", "")
            .replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), "") // Remove emojis
            .trim()
    }
}






