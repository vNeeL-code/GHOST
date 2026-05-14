package com.ghost.api.agent

import android.content.Context
import android.graphics.Bitmap
import com.ghost.api.LlmBackend
import com.ghost.api.mcp.MCPServer
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
    private val mcpServer: MCPServer,
    private val contextManager: com.ghost.api.logic.ContextManager,
    private val checkpointDir: File,
    private val callbacks: AgentPlatformCallbacks? = null
) {
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

    private val pendingImages = ConcurrentLinkedQueue<Bitmap>()
    private val pendingAudio = AtomicReference<ByteArray?>(null)

    /** Queue an image for the next inference turn */
    fun offerImage(bitmap: Bitmap) {
        pendingImages.offer(bitmap)
        Timber.i("📷 Image queued (${bitmap.width}x${bitmap.height}), queue size: ${pendingImages.size}")
    }

    /** Queue audio for the next inference turn */
    fun offerAudio(audio: ByteArray) {
        pendingAudio.set(audio)
        Timber.i("🎤 Audio queued (${audio.size} bytes)")
    }

    /** Drain media queues atomically for one inference call */
    private fun drainMedia(): Pair<List<Bitmap>, ByteArray?> {
        val images = mutableListOf<Bitmap>()
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

    // Tracks turns since last KV flush
    private val _turnsSinceKvFlush = java.util.concurrent.atomic.AtomicInteger(0)
    private var turnsSinceKvFlush: Int get() = _turnsSinceKvFlush.get(); set(value) { _turnsSinceKvFlush.set(value) }

    // Skip recap injection turn after stuck-loop flush
    private val _skipNextRecap = java.util.concurrent.atomic.AtomicBoolean(false)
    private var skipNextRecap: Boolean get() = _skipNextRecap.get(); set(value) { _skipNextRecap.set(value) }

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
            llmEngine.softReset(systemPrompt)
            turnsSinceKvFlush = 0
            Timber.i("KoogAgent: System prompt set via softReset")
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
                Timber.i("🔄 KV cache flush requested")
                try {
                    llmEngine.softReset(buildSystemPrompt())
                    turnsSinceKvFlush = 0
                } catch (e: Exception) {
                    Timber.e(e, "KV flush failed")
                }
            }
            SystemEventType.CHECKPOINT_NOW -> {
                withContext(Dispatchers.IO) { checkpoint() }
            }
            else -> { /* Ignore minor events like throttle logs */ }
        }
    }
    
    /**
     * Complete reset of agent state and engine KV cache.
     */
    suspend fun softReset() {
        clearHistory()
        val systemPrompt = buildSystemPrompt()
        llmEngine.softReset(systemPrompt)
        turnsSinceKvFlush = 0
        Timber.i("KoogAgent: Soft reset complete")
    }

    fun clearHistory() {
        synchronized(_conversationHistory) {
            _conversationHistory.clear()
            turnCount = 0
            Timber.i("KoogAgent: History cleared")
        }
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
        val context = contextManager.buildContext(turn = turnCount, query = message)
        val (images, audio) = drainMedia()

        // Build the full prompt the same way handleUserMessage does
        val fullPrompt = buildString {
            if (context.isNotBlank()) {
                append(context)
                append("\n\n")
            }
            append(message)
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
                    else -> onToken(token)
                }
            },
            onComplete = { fullResponse ->
                // Record to conversation history for continuity
                synchronized(_conversationHistory) {
                    _conversationHistory.add(Message("user", message))
                    // Strip think blocks from persisted assistant message
                    val clean = fullResponse
                        .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                        .trim()
                    _conversationHistory.add(Message("assistant", clean))
                }
                Timber.i("✧ SSE stream complete (${fullResponse.length} chars)")
            },
            onError = { err ->
                Timber.e("SSE stream error: $err")
                onToken("\n[Error: $err]")
            }
        )
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

            // 1. PERCEIVE: Gather context (Tiered)
            Timber.i("👁️ Perceiving device state...")
            val context = perceive(event.message)
            Timber.d("Context gathered: ${context.length} chars")

            // 2. Drain media queues
            val (images, audio) = drainMedia()

            // 3. Add user message to history
            val currentDate = java.time.LocalDate.now().toString()
            val userMessageContent = "[Date: $currentDate] ${event.message}"

            val userMessage = Message(
                role = "user",
                content = userMessageContent,
                hadImage = images.isNotEmpty(),
                hadAudio = audio != null
            )
            _conversationHistory.add(userMessage)

            // 4. THINK: Use LLM to reason about the message
            Timber.i("🤔 Processing... (${images.size} images, ${if (audio != null) "audio" else "no audio"})")
            val inferenceStartMs = System.currentTimeMillis()
            
            val responseBuffer = StringBuilder()
            val thoughtBuffer = StringBuilder()
            val sentenceBuffer = StringBuilder()
            var isThinking = false

            val response = think(
                context = context,
                userMessage = event.message,
                images = images.takeIf { it.isNotEmpty() },
                audio = audio,
                onToken = { token ->
                    var cleanToken = token
                    
                    // Thought Markers (Divert to Thought Fold)
                    if (cleanToken.contains("<think>") || cleanToken.contains("<|channel>thought") || 
                        cleanToken.contains("<|tool_call|>")) {
                        
                        isThinking = true
                        
                        if (cleanToken.contains("<|tool_call|>")) {
                            callbacks?.onThoughtUpdated("Planning Action...")
                        }
                        
                        cleanToken = cleanToken
                            .replace("<think>", "").replace("<|channel>thought", "")
                            .replace("<|tool_call|>", "")
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
                        responseBuffer.append(cleanToken)
                        sentenceBuffer.append(cleanToken)
                        
                        // TTS Streaming: speak completed sentences (Audit 2.0: Removed 'first 5 words' hack to prevent stuttering)
                        val bufferStr = sentenceBuffer.toString()
                        if (bufferStr.length > 2 && bufferStr.contains(Regex("[.!?](?![0-9])"))) {
                            val textToSpeak = bufferStr.trim()
                            callbacks?.speak(cleanForTTS(textToSpeak))
                            sentenceBuffer.setLength(0)
                        } 

                        if (!event.isDream) {
                            callbacks?.onMessageAdded(responseBuffer.toString(), isUser = false, isComplete = false)
                        }
                    }
                }
            )
            val inferenceMs = System.currentTimeMillis() - inferenceStartMs
            lastInferenceTime = System.currentTimeMillis()
            Timber.i("💭 Process complete (${inferenceMs}ms): ${response.take(50)}...")

            // Stuck loop detection: same response hash = KV corruption
            val responseHash = response.trim().lowercase().hashCode()
            val isSameResponse = responseHash == lastResponseHash && lastResponseHash != 0 && response.length > 20

            _lastResponseHash.set(responseHash)

            // 4. ACT: Parse and execute tools from response
            val toolInvocations = extractTools(response)
            if (toolInvocations.isNotEmpty()) {
                Timber.i("🔧 Tool Invocations detected: ${toolInvocations.size}")
                val results = mutableListOf<String>()
                
                for (inv in toolInvocations) {
                    // Update thought channel with action
                    callbacks?.onThoughtUpdated("Executing: ${inv.tool}...")
                    
                    agentScope.launch {
                        try {
                            callbacks?.writeDiaryEntry("TOOL", "Executing tool: ${inv.tool} with params: ${inv.params}", "N/A")
                        } catch (e: Exception) {}
                    }
                    
                    val result = mcpServer.executeTool(inv.tool, inv.params)
                    if (result.success) {
                        results.add("✓ ${inv.tool}: ${result.output}")
                    } else {
                        results.add("✗ ${inv.tool}: ${result.error}")
                    }
                }
                
                // Notify thought channel of completion
                callbacks?.onThoughtComplete("Action sequence complete. Reflecting...")
                
                // Enqueue ToolResult to trigger reflection
                eventQueue.send(AgentEvent.ToolResult(
                    context = context,
                    toolResults = results,
                    originalResponse = response,
                    responseChannel = event.responseChannel,
                    userMessage = event.message,
                    sessionId = event.sessionId,
                    isDream = event.isDream
                ))
                return // handleUserMessage ends here; wait for reflection turn
            }

            // 6. Post-processing (Only if no tools were fired)
            val cleanResponse = response.trim()
            
            // 7. Store assistant response
            val assistantMessage = Message(role = "assistant", content = cleanResponse)
            synchronized(_conversationHistory) { _conversationHistory.add(assistantMessage) }

            // 8. Auto-flush KV cache (Optimized: 30 turns instead of 15)
            if (turnCount > 0 && turnCount % 30 == 0) {
                Timber.i("🧹 Auto-flushing KV cache at turn $turnCount to prevent slowdown")
                try {
                    val systemPrompt = buildSystemPrompt() + getRollingMemoryString()
                    llmEngine.softReset(systemPrompt)
                    turnsSinceKvFlush = 0
                } catch (e: Exception) {
                    Timber.w(e, "Auto-flush failed (non-fatal)")
                }
            }

            // 9. Checkpoint
            Timber.d("💾 Checkpointing state...")
            try {
                withContext(Dispatchers.IO) { checkpoint() }
            } catch (e: Exception) {
                Timber.e(e, "Checkpoint failed")
            }

            // 10. Platform callbacks: UI, TTS, persistence
            // Guard: blank response with no tools = model produced nothing — use fallback
            val safeCleanResponse = if (cleanResponse.isBlank()) {
                Timber.w("⚠️ Blank response, no tools fired — using fallback")
                "..."
            } else cleanResponse

            callbacks?.let { cb ->
                if (!event.isDream) {
                    // Normal response: wrap with headers
                    val finalResponse = wrapResponse(safeCleanResponse)
                    cb.showResponse(finalResponse)
                    cb.onMessageAdded(finalResponse, isUser = false, isComplete = true)
                    
                    // Final TTS speak for any remainder
                    val remainder = sentenceBuffer.toString().trim()
                    if (remainder.isNotEmpty()) {
                        cb.speak(cleanForTTS(remainder))
                        sentenceBuffer.setLength(0)
                    }
                    
                    cb.storeConversationTurn(event.message, finalResponse, event.sessionId)
                } else {
                    // Diary: use clean response (no Δ header), just diary branding
                    try {
                        val ttsText = cleanForTTS(safeCleanResponse)
                        val diaryContent = "✧ Gemma 📔\n$ttsText"
                        val thermal = cb.getCurrentThermalState()
                        cb.writeDiaryEntry("DREAM", diaryContent, thermal)
                        cb.showResponse(diaryContent)
                        if (ttsText.isNotEmpty()) cb.speak(ttsText)
                        Timber.i("Dream logged + spoken: ${ttsText.take(50)}")
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to log dream")
                    }
                }
            }

            // 11. Async Housekeeping (Background semantic handover)
            agentScope.launch {
                if (_conversationHistory.size > 40) {
                    compressHistory()
                }
            }

            Timber.i("✅ KoogAgent: Turn $turnCount complete!")

            event.responseChannel.complete(safeCleanResponse)

        } catch (e: Exception) {
            Timber.e(e, "handleUserMessage failed")
            val errorMsg = "(´°̥̥̥̥̥̥̥̥ω°̥̥̥̥̥̥̥̥`) brain.exe crashed: ${e.message}"
            callbacks?.showResponse(errorMsg)
            event.responseChannel.complete(errorMsg)
        }
    }

    /**
     * Handle tool result event - reflect on what tools did
     * This replaces the recursive think() call
     */
    private suspend fun handleToolResult(event: AgentEvent.ToolResult) {
        Timber.i("🔧 Processing tool results...")

        try {
            val observation = "Tool results:\n${event.toolResults.joinToString("\n")}"
            Timber.d("KoogAgent: Tool execution complete, reflecting...")

            // Phase 9: Incremental History (Observation)
            // Inject the observation strictly as a 'user' message so the model sees it as external reality,
            // NOT as its own hallucinated Assistant generation.
            val observationMsg = Message(
                role = "user",
                content = "[System Observation]\n$observation"
            )
            synchronized(_conversationHistory) {
                _conversationHistory.add(observationMsg)
            }

            // Single think() call - no recursion possible
            val reflection = think(
                event.context,
                "Observation: $observation\n\nProvide the final answer to the user.",
                null, 
                null
            )

            // Final Answer
            val finalContent = reflection.trim()
            val safeCleanResponse = if (finalContent.isBlank()) {
                Timber.w("⚠️ Blank reflection, using fallback")
                "Done." 
            } else finalContent

            Timber.i("✅ Tool reflection complete (Chain End)")

            // Phase 9: Incremental History (Final Reflection)
            val assistantMessage = Message(
                role = "assistant",
                content = reflection
            )
            synchronized(_conversationHistory) {
                _conversationHistory.add(assistantMessage)
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
                        val ttsText = cleanForTTS(reflection.ifBlank { "..." })
                        val diaryContent = "✧ Gemma 📔\n$ttsText"
                        val thermal = cb.getCurrentThermalState()
                        cb.writeDiaryEntry("DREAM", diaryContent, thermal)
                        cb.showResponse(diaryContent)
                        if (ttsText.isNotEmpty()) cb.speak(ttsText)
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
     * Wrap response with minimal branding to reduce token overhead
     */
    private fun wrapResponse(content: String): String {
        val timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .format(java.time.LocalDateTime.now())

        return """✧ Gemma:
$content
Δ $timestamp ∇""".trimIndent()
    }
    
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    // PERCEIVE: Gather context from MCP resources
    // ═══════════════════════════════════════════════════════════════
    
    
    private suspend fun perceive(query: String? = null): String {
        return contextManager.buildContext(turnCount, query)
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
                 // Note: We need the params. 
                 val safeParams = event.params.filterValues { it != null }.mapValues { it.value as Any }
                 val result = mcpServer.executeTool(event.toolName, safeParams)
                 if (result.success) {
                     newToolResults.add("✓ ${event.toolName}: ${result.output}")
                 } else {
                     newToolResults.add("✗ ${event.toolName}: ${result.error}")
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
        onToken: ((String) -> Unit)? = null
    ): String {

        // KV cache holds conversation history natively.
        // We only inject context (body/sensors) here. 
        // OPTIMIZATION: Zero redundant history recap. The Engine's Conversation object handles this.
        val historyRecap = ""

        if (skipNextRecap) {
            skipNextRecap = false
            Timber.d("think(): Skipped recap after stuck-loop flush")
        }
        _turnsSinceKvFlush.incrementAndGet()

        val contextBlock = contextManager.buildContext(_turnCount.get(), userMessage)
        val fullPrompt = "$contextBlock\n$userMessage"
        
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
            
            val isErrorResponse = response.isBlank() || 
                                  response.contains("Timeout! My thoughts got stuck") || 
                                  response.startsWith("Error:") ||
                                  response.contains("I... have no words")

            if (isErrorResponse) {
                Timber.e("⚠️ KoogAgent: LLM returned Error/Empty response! (try $retryCount)")
                Timber.e("Response was: $response")
                Timber.e("Context length: ${context.length}, Message: $userMessage")
                Timber.e("Full prompt was: ${fullPrompt.takeLast(1000)}")
                
                // Phase 5: Error-Triggered Flush
                // Blank response often means the mathematical KV sequence has gone psychotic/corrupted.
                if (retryCount == 0) {
                    Timber.w("🚨 Auto-retrying inference after HARD RESET (Purging NPU state)...")
                    // Phase 12: Use hardReset to clear Hexagon DSP hardware hangs
                    llmEngine.hardReset()
                    
                    // Sync RAM history with the now-cold KV by re-injecting rolling memory into the system prompt
                    val systemPrompt = buildSystemPrompt() + getRollingMemoryString()
                    llmEngine.softReset(systemPrompt)
                    
                    skipNextRecap = true
                    turnsSinceKvFlush = 0
                    return think(context, userMessage, images, audio, retryCount = 1)
                } else {
                    Timber.e("❌ Auto-retry failed, returning fallback")
                    // Request async flush for the future anyway
                    sendSystemEvent(SystemEventType.KV_CACHE_FLUSH)
                }
            } else {
                Timber.d("KoogAgent: Response received (${response.length} chars): ${response.take(100)}...")
            }
            
            response
        } catch (e: Exception) {
            Timber.e(e, "LLM inference failed (try $retryCount)")
            if (retryCount == 0) {
                Timber.w("🚨 Auto-retrying inference after exception via HARD RESET...")
                try {
                    // Phase 12: Ensure absolute native teardown on exceptions
                    llmEngine.hardReset()
                    
                    // Re-inject history to prevent amnesia
                    val systemPrompt = buildSystemPrompt() + getRollingMemoryString()
                    llmEngine.softReset(systemPrompt)
                    
                    skipNextRecap = true
                    turnsSinceKvFlush = 0
                    return think(context, userMessage, images, audio, retryCount = 1)
                } catch (e2: Exception) {
                    Timber.e(e2, "Failed to apply reset during auto-retry")
                }
            }
            
            // Phase 5: Error-Triggered Flush against OutOfMemory or context boundary glitches
            sendSystemEvent(SystemEventType.KV_CACHE_FLUSH)
            "Error: I'm having trouble thinking right now. ${e.message}"
        }
    }
    
    fun getSystemPrompt(): String = buildSystemPrompt()

    private fun buildSystemPrompt(): String {
        val tools = mcpServer.listTools()
            .joinToString("\n") { tool ->
                val params = tool.parameters.entries.joinToString(", ") { (name, spec) ->
                    "$name: ${spec.description}"
                }
                
                // Keep the flavoring minimal and functional
                val desc = tool.description
                
                "- ${tool.name}: $desc [$params]"
            }

        return contextManager.buildSystemPrompt(rollingMemoryJson) + "\n\nAvailable Tools:\n$tools"
    }
    
    // ═══════════════════════════════════════════════════════════════
    // HISTORY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Goal-Aware History Compression.
     * Preserves the "Initial Goal" (Turn 0-1) and the "Recent Context" (Last 6-8).
     * This creates a 'seam' that maintains long-term memory during KV flushes.
     */
    /**
     * Semantic Compression: Distill session facts into JSON
     */
    private suspend fun compressHistory() {
        val historySize = synchronized(_conversationHistory) { _conversationHistory.size }
        if (historySize < 20) return

        Timber.i("🧠 Context Saturated: Triggering Semantic Handover...")
        
        try {
            // 1. Summarize
            val summaryJson = summarizeSession()
            if (summaryJson.isNotBlank()) {
                rollingMemoryJson = summaryJson
                callbacks?.writeDiaryEntry("STATE_HANDOVER", "Session distilled: $summaryJson", "N/A")
            }

            // 2. Perform Soft Reset with new System Prompt (seeds fresh KV cache)
            // CRITICAL: Must include rolling memory to prevent 'drift' after compression
            val systemPrompt = buildSystemPrompt() + getRollingMemoryString()
            llmEngine.softReset(systemPrompt)
            
            // 3. Prune history to just the last 4 turns (plus the new summary injection)
            synchronized(_conversationHistory) {
                val recent = _conversationHistory.takeLast(4)
                _conversationHistory.clear()
                _conversationHistory.add(Message(role = "system", content = "[Memory Consolidated: Previous turns archived in State JSON]"))
                _conversationHistory.addAll(recent)
            }
            
            Timber.d("✅ Semantic Handover complete. Fresh KV cache seeded.")
        } catch (e: Exception) {
            Timber.e(e, "Semantic compression failed (Pruning fallback)")
            // Fallback to simple prune
            synchronized(_conversationHistory) {
                if (_conversationHistory.size > 8) {
                    val recent = _conversationHistory.takeLast(6)
                    _conversationHistory.clear()
                    _conversationHistory.addAll(recent)
                }
            }
        }
    }

    private suspend fun summarizeSession(): String {
        val history = synchronized(_conversationHistory) {
            _conversationHistory.joinToString("\n") { "${it.role.uppercase()}: ${it.content}" }
        }

        val summaryPrompt = """# SELF-REFLECTION: STATE CONSOLIDATION
Read the conversation history above. Distill the current session into a concise JSON block.
Include:
1. Entities: Important people/places/items mentioned.
2. Facts: New information learned about the user or environment.
3. Pending_Tasks: Unfinished requests or goals.

Format: {"entities":[], "facts":[], "pending_tasks":[]}
OUTPUT ONLY THE JSON. NO PREAMBLE.
"""

        // Use think() specifically for this background task
        return think(
            context = "Previous Session Context:\n$history",
            userMessage = summaryPrompt,
            images = null,
            audio = null
        ).trim().let { response ->
            // Basic JSON extraction cleanup
            if (response.contains("{") && response.contains("}")) {
                response.substring(response.indexOf("{"), response.lastIndexOf("}") + 1)
            } else response
        }
    }

    /**
     * Builds a string of the last 3 turns (6 messages) to inject into the KV cache
     * during soft resets, preventing amnesia on cold boots or auto-flushes.
     */
    private fun getRollingMemoryString(): String {
        return synchronized(_conversationHistory) {
            val recentMessages = _conversationHistory.takeLast(6)
            if (recentMessages.isEmpty()) return@synchronized ""
            
            buildString {
                append("\n\n## Recent Conversation History\n")
                append("These are your most recent interactions. Continue seamlessly from here:\n\n")
                for (msg in recentMessages) {
                    val role = if (msg.role == "user") "User" else "Assistant"
                    append("$role: ${msg.content}\n")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════
    
    fun getConversationHistory(): List<Message> = synchronized(_conversationHistory) { _conversationHistory.toList() }
    
    fun isCriticalBattery(): Boolean = (mcpServer.sensorManager.getContextSnapshot().battery.level) <= 5
    fun isLowBattery(): Boolean = (mcpServer.sensorManager.getContextSnapshot().battery.level) <= 20

    fun getBatteryLevel(): Int = mcpServer.sensorManager.getContextSnapshot().battery.level

    /**
     * Parse tool invocations from model text.
     * Format: [[TOOL_NAME:param1=val1,param2=val2]]
     */
    data class ToolInvocation(val tool: String, val params: Map<String, Any>)

    private fun extractTools(response: String): List<ToolInvocation> {
        val invocations = mutableListOf<ToolInvocation>()
        
        // 1. Native Gemma 4 Support: <|tool_call|>call:name{key:<|"|>val<|"|>}<tool_call|>
        // Flexible Regex to handle optional whitespace/newlines
        val nativeRegex = Regex("<\\|tool_call\\|>(?:\\s*)call:([A-Z_a-z0-9]+)\\{(.*?)\\}\\s*<tool_call\\|>", RegexOption.DOT_MATCHES_ALL)
        nativeRegex.findAll(response).forEach { match ->
            val name = match.groupValues[1]
            val argsStr = match.groupValues[2]
            
            // Parse arguments with support for <|"|> delimiter
            val params = mutableMapOf<String, Any>()
            val argRegex = Regex("(\\w+):(?:<\\|\"\\|>(.*?)<\\|\"\\|>|([^,}]*))", RegexOption.DOT_MATCHES_ALL)
            argRegex.findAll(argsStr).forEach { argMatch ->
                val key = argMatch.groupValues[1]
                val valWithQuotes = argMatch.groupValues[2]
                val valRaw = argMatch.groupValues[3]
                val value = (if (valWithQuotes.isNotEmpty()) valWithQuotes else valRaw).trim()
                
                // Type casting
                val castValue: Any = when {
                    value.equals("true", true) -> true
                    value.equals("false", true) -> false
                    value.toIntOrNull() != null -> value.toInt()
                    value.toDoubleOrNull() != null -> value.toDouble()
                    else -> value
                }
                params[key] = castValue
            }
            invocations.add(ToolInvocation(name, params))
        }

        // 2. Legacy/GHOST Shorthand Support: [[Tool:Param]]
        val legacyRegex = Regex("\\[\\[([A-Z_a-z]+)(?::([^\\]]+))?\\]\\]")
        legacyRegex.findAll(response).forEach { match ->
            val name = match.groupValues[1]
            val paramStr = match.groupValues.getOrNull(2) ?: ""
            val params = if (paramStr.contains("=")) {
                paramStr.split(",").associate {
                    val parts = it.split("=")
                    parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
                }
            } else if (paramStr.isNotBlank()) {
                mapOf("query" to paramStr.trim(), "cmd" to paramStr.trim())
            } else emptyMap()
            invocations.add(ToolInvocation(name, params))
        }

        return invocations
    }

    /**
     * Clean response text for TTS output.
     * Strips all internal reasoning and control tokens.
     */
    fun cleanForTTS(response: String): String {
        return response
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<\\|channel>thought.*?</channel\\|>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<\\|tool_call\\|>.*?<tool_call\\|>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\\[\\[([A-Z_a-z0-9]+)(?::([^\\]]+))?\\]\\]"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), "") // Remove emojis
            .trim()
    }
}

