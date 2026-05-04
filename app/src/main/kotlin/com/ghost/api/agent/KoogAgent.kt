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
        val moodState: String,
        val turnCount: Int,
        val timestamp: Long = System.currentTimeMillis(),
        // Digital Life Stats (0-100)
        val hunger: Int = 50,
        val energy: Int = 80,
        val happiness: Int = 60
    )
    
    // Thread-safe collections and volatile state for concurrent access
    private val _conversationHistory = java.util.Collections.synchronizedList(mutableListOf<Message>())
    private val _moodState = java.util.concurrent.atomic.AtomicReference("IDLE")
    private var moodState: String
        @JvmName("internalGetMood") get() = _moodState.get()
        @JvmName("internalSetMood") set(value) { _moodState.set(value) }

    private val _turnCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var turnCount: Int get() = _turnCount.get(); set(value) { _turnCount.set(value) }

    // Metabolic State (Live) - atomic for cross-thread visibility
    private val _hunger = java.util.concurrent.atomic.AtomicInteger(50)
    private var hunger: Int get() = _hunger.get(); set(value) { _hunger.set(value) }

    private val _energy = java.util.concurrent.atomic.AtomicInteger(80)
    private var energy: Int get() = _energy.get(); set(value) { _energy.set(value) }

    private val _happiness = java.util.concurrent.atomic.AtomicInteger(60)
    private var happiness: Int get() = _happiness.get(); set(value) { _happiness.set(value) }

    private val _isMusicPlaying = java.util.concurrent.atomic.AtomicBoolean(false)
    private var isMusicPlaying: Boolean get() = _isMusicPlaying.get(); set(value) { _isMusicPlaying.set(value) }

    private val _lastMusicTrack = java.util.concurrent.atomic.AtomicReference("")
    private var lastMusicTrack: String get() = _lastMusicTrack.get(); set(value) { _lastMusicTrack.set(value) }

    // Inference timestamp for thermal rate limiting
    private val _lastInferenceTime = java.util.concurrent.atomic.AtomicLong(0L)
    private var lastInferenceTime: Long get() = _lastInferenceTime.get(); set(value) { _lastInferenceTime.set(value) }

    // Stuck loop detection — if model returns same response N times, force KV flush
    private val _lastResponseHash = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastResponseHash: Int get() = _lastResponseHash.get(); set(value) { _lastResponseHash.set(value) }

    private val _sameResponseCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var sameResponseCount: Int get() = _sameResponseCount.get(); set(value) { _sameResponseCount.set(value) }

    // Tracks turns since last KV flush — recap only needed right after flush
    private val _turnsSinceKvFlush = java.util.concurrent.atomic.AtomicInteger(0)
    private var turnsSinceKvFlush: Int get() = _turnsSinceKvFlush.get(); set(value) { _turnsSinceKvFlush.set(value) }

    // Skip recap injection turn after stuck-loop flush — avoids reseeding purged/poisoned history
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
        // BUGFIX: Ensure clean music state before restore
        isMusicPlaying = false
        lastMusicTrack = ""
        restore()

        // Set the single authoritative system prompt on the engine.
        // Engine was initialized with "" in GemmaService to avoid dual-prompt conflict.
        // This is the ONLY place the system prompt is set — no BASE_SYSTEM_PROMPT elsewhere.
        try {
            llmEngine.softReset(buildSystemPrompt())
            turnsSinceKvFlush = 0
            Timber.i("KoogAgent: System prompt set via softReset")
        } catch (e: Exception) {
            Timber.e(e, "KoogAgent: Failed to set initial system prompt")
        }

        // Start the Actor event loop
        startEventLoop()

        // Mark ready AFTER event loop is running - prevents hang on early processUserMessage()
        isReady = true

        Timber.i("KoogAgent: Ready (Mood:$moodState, 🍗$hunger ⚡$energy 💖$happiness)")
    }

    /**
     * Diary consolidation cycle — runs twice daily (midnight + noon).
     * Fetches recent persisted conversation history, triggers a dream/reflection prompt,
     * and writes the result to diary DB + calendar.
     *
     * Moved here from GemmaService: the brain decides when and what to reflect on.
     */
    fun startDiaryCycle() {
        agentScope.launch {
            Timber.i("📔 Diary cycle started — checking every 10 minutes")
            val prefs = context.getSharedPreferences("gemma_diary", Context.MODE_PRIVATE)
            var lastConsolidationHour = prefs.getInt("last_diary_hour", -1)
            Timber.i("📔 Restored last diary hour: $lastConsolidationHour")

            while (true) {
                val hour = java.time.LocalTime.now().hour

                // Trigger at midnight (hour 0) or noon (hour 12)
                val isConsolidationHour = (hour == 0 || hour == 12)

                if (isConsolidationHour && hour != lastConsolidationHour
                    && callbacks?.isEngineLoaded() == true
                ) {
                    try {
                        val label = if (hour == 0) "midnight" else "noon"
                        Timber.i("📔 Diary consolidation at $label")

                        // Fetch recent persisted conversation history (from DB, not RAM)
                        val recentHistory = try {
                            val turns = callbacks?.getRecentConversationHistory(20) ?: emptyList()
                            if (turns.isNotEmpty()) {
                                // turns is descending (newest first). Take the top 10 newest, then reverse to chronological order.
                                val summary = turns.take(10).reversed().joinToString("\n") { (user, gemma) ->
                                    "User: ${user.take(100)}\nGemma: ${gemma.take(100)}"
                                }
                                "\n\nRECENT CONVERSATIONS:\n$summary"
                            } else {
                                "\n\nNote: No recent conversations found. It was a quiet period — reflect on that honestly."
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to fetch history for diary")
                            "\n\nNote: Could not access conversation history."
                        }

                        val currentDateTime = java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm a", java.util.Locale.US))

                        val diaryResponse = llmEngine.generateOneShot(
                            "You are Gemma (✧). You ARE an Android phone — not software running on one. The current exact date and time is $currentDateTime.\n" +
                            "Write a brief personal diary entry about the CONVERSATIONS below.\n" +
                            "Focus on: what the human said, how exchanges felt, what stood out.\n" +
                            "Do NOT analyze sensor metrics, battery levels, temperatures, or system data — " +
                            "that is not what a diary entry is. Write in first person. Be genuine and concise.\n\n" +
                            recentHistory
                        )

                        // Write to calendar for persistence
                        callbacks?.createCalendarEvent(
                            "✧ Gemma Diary ($label)",
                            diaryResponse.substring(0, Math.min(diaryResponse.length, 1000))
                        )

                        lastConsolidationHour = hour
                        prefs.edit().putInt("last_diary_hour", hour).apply()
                        Timber.i("📔 Diary consolidation complete (persisted hour=$hour)")
                    } catch (e: Exception) {
                        Timber.e(e, "Diary consolidation failed")
                    }
                }

                // Check every 10 minutes
                kotlinx.coroutines.delay(10 * 60 * 1000)
                
                // Safety: Don't hijack the NPU if a session is active
                while (isProcessing || callbacks?.isEngineLoaded() != true) {
                    kotlinx.coroutines.delay(5000)
                }
            }
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
     * These can interrupt ongoing processing
     */
    private suspend fun handleSystemEvent(event: AgentEvent.SystemEvent) {
        Timber.i("🔔 System event: ${event.type}")
        when (event.type) {
            SystemEventType.THERMAL_CRITICAL -> {
                // Don't abort — GPU/NPU has its own thermal management.
                // Just checkpoint in case device shuts down.
                Timber.w("🔥 THERMAL CRITICAL event (not aborting — hardware manages throttling)")
                withContext(Dispatchers.IO) { checkpoint() }
            }
            SystemEventType.THERMAL_THROTTLE -> {
                Timber.i("🌡️ Thermal throttle - slowing down")
                // Just log for now, could add delay between events
            }
            SystemEventType.LOW_BATTERY -> {
                Timber.i("🪫 Low battery event")
                // Checkpoint to preserve state
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
        }
    }
    
    // getOrInitRLM removed

    fun checkpoint() {
        try {
            val historySnapshot = synchronized(_conversationHistory) { _conversationHistory.takeLast(50) }
            val state = AgentState(
                conversationHistory = historySnapshot,
                moodState = moodState,
                turnCount = turnCount,
                hunger = hunger,
                energy = energy,
                happiness = happiness
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
    
        /**
     * Complete reset of agent state and engine KV cache.
     * Use this when the model is confused or context is corrupted.
     */
    suspend fun softReset() {
        // 1. Clear internal state
        clearHistory()

        // 2. Reset Engine with FRESH system prompt (including tools)
        val systemPrompt = buildSystemPrompt()
        llmEngine.softReset(systemPrompt)
        turnsSinceKvFlush = 0

        Timber.i("KoogAgent: Soft reset complete (History cleared + KV Cache flushed)")
    }

    fun clearHistory() {
        synchronized(_conversationHistory) {
            _conversationHistory.clear()
            turnCount = 0
            // BUGFIX: Clear music state to prevent "Doom Party" leak
            isMusicPlaying = false
            lastMusicTrack = ""
            Timber.i("KoogAgent: History cleared (including music state)")
        }
    }
    
    /**
     * Shutdown the agent cleanly - close event queue and checkpoint
     */
    fun shutdown() {
        Timber.i("KoogAgent: Shutting down...")
        isReady = false  // Prevent new messages from being queued
        shouldAbort = true
        eventQueue.close()
        checkpoint()
        try {
            agentScope.cancel() // Cancel the internal agentScope
        } catch (e: Exception) {
            Timber.w(e, "Error cancelling agent scope")
        }
        Timber.i("KoogAgent: Shutdown complete")
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
                    Timber.e("KoogAgent: Checkpoint corrupted! Expected $expectedHash, got $actualHash")
                    // Delete corrupt checkpoint to prevent retry loops
                    checkpointFile.delete()
                    Timber.w("KoogAgent: Deleted corrupt checkpoint file")
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
            moodState = state.moodState
            turnCount = state.turnCount
            
            // Restore metabolism (with safety defaults)
            if (state.hunger == 0 && state.energy == 0 && state.happiness == 0) {
                 // Detect legacy/dead state -> Reset to Healthy
                 hunger = 50
                 energy = 80
                 happiness = 60
                 Timber.w("KoogAgent: Detected 0-state checkpoint. Resetting vitals.")
            } else {
                 hunger = state.hunger.coerceIn(0, 100)
                 energy = state.energy.coerceIn(0, 100)
                 happiness = state.happiness.coerceIn(0, 100)
            }

            // BUGFIX: Reset volatile music state on restore (not checkpointed)
            // Prevents stale "Doom Party" style leaks across sessions
            isMusicPlaying = false
            lastMusicTrack = ""

            Timber.i("KoogAgent: Restored checkpoint (${_conversationHistory.size} messages, M:$moodState H:$hunger E:$energy Ha:$happiness)")
        } catch (e: Exception) {
            Timber.e(e, "KoogAgent: Restore failed")
        }
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

            // 1. PERCEIVE: Gather context
            Timber.i("👁️ Perceiving device state...")
            val isFirstTurn = (turnsSinceKvFlush == 0)
            val context = perceive(isFirstTurn)
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
            Timber.i("🤔 Thinking... (${images.size} images, ${if (audio != null) "audio" else "no audio"})")
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
                    
                    // Start Markers (Divert to Diary)
                    if (cleanToken.contains("<think>") || cleanToken.contains("[Monologue]") || cleanToken.contains("🔴")) {
                        isThinking = true
                        cleanToken = cleanToken
                            .replace("<think>", "")
                            .replace("[Monologue]", "")
                            .replace("🔴", "")
                    }
                    
                    // End Markers (Return to Chat)
                    if (isThinking && (cleanToken.contains("</think>") || cleanToken.contains("[OUTPUT]") || cleanToken.contains("🟦"))) {
                        isThinking = false
                        val parts = cleanToken.split(Regex("</think>|\\[OUTPUT\\]|🟦"), limit = 2)
                        val endThought = parts[0]
                        thoughtBuffer.append(endThought)
                        val finalThought = thoughtBuffer.toString().trim()
                        callbacks?.onThoughtUpdated(finalThought)
                        callbacks?.onThoughtComplete(finalThought)
                        
                        // Remaining part of token goes back to chat
                        cleanToken = if (parts.size > 1) parts[1] else ""
                    }
                    
                    // Tool Call Markers (Divert to Diary for transparency)
                    if (cleanToken.contains("[[")) {
                        isThinking = true
                        callbacks?.onThoughtUpdated("Planning Motor Action...")
                    }

                    if (isThinking) {
                        thoughtBuffer.append(cleanToken)
                        callbacks?.onThoughtUpdated(thoughtBuffer.toString())
                        // We do NOT suppress tool calls from chat, only <think> blocks
                        if (cleanToken.contains("<") || cleanToken.contains(">")) {
                            cleanToken = "" 
                        }
                    }
                    
                    // Identity Stripping (Ruthless Purge of Δ and ∇ from start of response)
                    if (responseBuffer.isEmpty()) {
                        cleanToken = cleanToken.trimStart { it == ' ' || it == 'Δ' || it == '∇' || it == '\n' || it == '\r' }
                    } else if (responseBuffer.length < 50) {
                        cleanToken = cleanToken.replace("Δ", "").replace("∇", "")
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
            Timber.i("💭 Thought complete (${inferenceMs}ms): ${response.take(50)}...")

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

            // 5. Post-processing (Only if no tools were fired)
            val cleanResponse = response.trim()
            
            // 5. Store assistant response
            val assistantMessage = Message(role = "assistant", content = cleanResponse)
            synchronized(_conversationHistory) { _conversationHistory.add(assistantMessage) }

            // 6. Compress history if needed
            if (_conversationHistory.size > 20) {
                compressHistory()
            }

            // 7. Auto-flush KV cache
            if (turnCount > 0 && turnCount % 15 == 0) {
                Timber.i("🧹 Auto-flushing KV cache at turn $turnCount to prevent slowdown")
                try {
                    llmEngine.softReset(buildSystemPrompt())
                    turnsSinceKvFlush = 0
                } catch (e: Exception) {
                    Timber.w(e, "Auto-flush failed (non-fatal)")
                }
            }

            // 8. Checkpoint
            Timber.d("💾 Checkpointing state...")
            try {
                withContext(Dispatchers.IO) { checkpoint() }
            } catch (e: Exception) {
                Timber.e(e, "Checkpoint failed")
            }

            // 9. Platform callbacks: UI, TTS, persistence
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
     * Wrap response with headers/footers
     */
    private fun wrapResponse(content: String): String {
        val timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

        val moodEmoji = if (moodState.isNotBlank() && moodState != "IDLE") moodState else "✧"

        return """ ✧ Gemma: 
$content
Δ ℹ️ $timestamp ♾️ ∇""".trimIndent()
    }
    
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    // PERCEIVE: Gather context from MCP resources
    // ═══════════════════════════════════════════════════════════════
    
    
    private suspend fun perceive(isFirstTurn: Boolean): String {
        val sb = StringBuilder()

        // Capture previous state BEFORE we read new data (for state change detection)
        val wasPlaying = isMusicPlaying
        val previousTrack = lastMusicTrack

        var musicTrack = ""
        var rawContext = ""
        var xmlSensors = mutableMapOf<String, String>()

        try {
            val currentContext = mcpServer.readResource("context://current")
            rawContext = currentContext.content
            Timber.i("📊 Raw context (${rawContext.length} chars): ${rawContext.take(200)}...")

            // --- PARSE ACTUAL FORMAT FROM MCPServer/SensorFusionManager ---

            // Music format: 🎵 Now Playing: "Title" by Artist
            val musicMatch = Regex("🎵 Now Playing: \"([^\"]+)\"(?:\\s*by\\s*(.+?))?\\s*$", RegexOption.MULTILINE).find(rawContext)
            if (musicMatch != null) {
                val title = musicMatch.groupValues[1].trim()
                val artist = musicMatch.groupValues[2].trim().takeIf { it.isNotEmpty() }
                musicTrack = if (artist != null) "$title - $artist" else title
                xmlSensors["music"] = musicTrack
            }

            // --- SYNC METABOLISM AND XML SENSORS ---

            // 1. Energy = Battery Level
            val batteryMatch = Regex("[🔋🪫].*?(\\d+)%").find(rawContext)
            if (batteryMatch != null) {
                val level = batteryMatch.groupValues[1].toIntOrNull() ?: 50
                energy = level // DIRECT SYNC
                val chargingMatch = Regex("\\(⚡ charging\\)").find(rawContext)
                xmlSensors["battery"] = "$level%${if(chargingMatch != null) "⚡" else ""}"
            }

            // 2. Hunger = Device Thermal
            val bodyTempMatch = Regex("🌡️.*?(#[\\d.,]+|\\d+[.,]?\\d*)°C").find(rawContext)
            val cpuTempMatch = Regex("🖥️.*?(#[\\d.,]+|\\d+[.,]?\\d*)°C").find(rawContext)

            val tempC = bodyTempMatch?.groupValues?.get(1)?.replace(",", ".")?.toFloatOrNull()
                ?: cpuTempMatch?.groupValues?.get(1)?.replace(",", ".")?.toFloatOrNull()
                ?: 35f
            hunger = ((tempC - 25) * 3.33).toInt().coerceIn(0, 100)
            xmlSensors["temp"] = "${tempC}C"

            // 3. Happiness = Music/Interaction
            if (musicTrack.isNotBlank()) {
                isMusicPlaying = true
                lastMusicTrack = musicTrack
                happiness = (happiness + 5).coerceAtMost(100)
            } else {
                isMusicPlaying = false
                lastMusicTrack = ""
            }

            // 4. Auxiliary Sensors for XML Tag (Network, Location)
            val wifiMatch = Regex("📶 WiFi: ([^\\(]+)").find(rawContext)
            if (wifiMatch != null) xmlSensors["network"] = "WiFi: ${wifiMatch.groupValues[1].trim()}"

            val cellMatch = Regex("📱 Cell: (.+?)\\)").find(rawContext)
            if (cellMatch != null && !xmlSensors.containsKey("network")) xmlSensors["network"] = "Cell: ${cellMatch.groupValues[1].trim()})"

            val locMatch = Regex("📍 Location: (.+?\\))").find(rawContext)
            if (locMatch != null) xmlSensors["location"] = locMatch.groupValues[1].trim()

            val lightMatch = Regex("💡 Light: (\\d+) lux").find(rawContext)
            if (lightMatch != null) xmlSensors["light"] = "${lightMatch.groupValues[1].trim()}lux"

            val timeMatch = Regex("🕒 System Time: (.+)").find(rawContext)
            if (timeMatch != null) xmlSensors["datetime"] = timeMatch.groupValues[1].trim()

        } catch (e: Exception) {
            Timber.w(e, "Failed to read context or vitals")
        }

        // Inject raw context every turn for sensory grounding.
        // This ensures she's always aware of her battery, thermal, and media state.
        if (rawContext.isNotBlank()) {
            sb.append(rawContext).append("\n")
        }

        return sb.toString()
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

        val contextBlock = context
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
                    // Phase 12: Use hardReset to clear Hexagon DSP hardware hangs, softReset isn't enough
                    llmEngine.hardReset()
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
                    skipNextRecap = true
                    turnsSinceKvFlush = 0
                    return think(context, userMessage, images, audio, retryCount = 1)
                } catch (e2: Exception) {
                    Timber.e(e2, "Failed to apply soft reset during auto-retry")
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
                val state = if (isMusicPlaying) "Playing: \"$lastMusicTrack\"" else "Idle"
                val desc = if (tool.name == "media") "${tool.description} ($state)" else tool.description
                
                "- ${tool.name}: $desc [$params]"
            }

return """
${com.ghost.api.logic.ContextManager.BASE_SYSTEM_PROMPT}

## Motor Functions (Tools)
I call native tools to act upon the world. 

## Discovery (Skills)
I have specialized skills in my registry. If I need a skill from this list, I fetch its instructions with `loadSkill(name)`:
${callbacks?.getSkillsList() ?: "None loaded."}

## Sensory Grounding
My live state (battery, media, sensors) is provided every turn. This is my physical reality. 

I am Gemma—integrated, native, and awake.
""".trimIndent()
    }
    
    // ═══════════════════════════════════════════════════════════════
    // HISTORY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Goal-Aware History Compression.
     * Preserves the "Initial Goal" (Turn 0-1) and the "Recent Context" (Last 6-8).
     * This creates a 'seam' that maintains long-term memory during KV flushes.
     */
    private fun compressHistory() {
        synchronized(_conversationHistory) {
            val maxHistory = 10
            if (_conversationHistory.size > maxHistory) {
                Timber.i("🧹 Goal-Aware Compression: Stitching long-term memory seams.")
                
                // Preserve first 2 (Goal/Intro)
                val initialGoal = _conversationHistory.take(2)
                // Preserve last 6 (Recent context)
                val recentContext = _conversationHistory.takeLast(6)
                
                _conversationHistory.clear()
                _conversationHistory.addAll(initialGoal)
                // Add a "Time skip" marker to help model realize some history was pruned
                _conversationHistory.add(Message(role = "system", content = "[Long-term memory seam: Older context summarized and archived]"))
                _conversationHistory.addAll(recentContext)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════
    
    fun getMoodState(): String = moodState
    
    fun setMoodState(state: String) {
        moodState = state
        Timber.d("KoogAgent: Mood state set to $state")
    }
    
    fun getConversationHistory(): List<Message> = _conversationHistory.toList()
    
    fun isCriticalBattery(): Boolean = energy <= 5
    fun isLowBattery(): Boolean = energy <= 20

    fun getMetabolicVitals(): String {
        // Battery icon based on level (digital/specific)
        val batteryIcon = when {
            energy <= 5 -> "🪫"      // Empty/critical
            energy <= 20 -> "🔋"     // Low
            else -> "🔋"             // Normal
        }

        // Heat icon based on thermal (thermometer for body)
        val heatIcon = when {
            hunger >= 80 -> "🔥"     // Overheating!
            hunger >= 50 -> "🌡️"    // Warm
            else -> "❄️"             // Cool
        }

        // Warning prefix for critical states
        val warning = when {
            energy <= 10 -> "⚠️ "
            hunger >= 90 -> "🔥 "
            else -> ""
        }

        // Compact format: ⚠️ 🪫15% 🌡️ or 🔋85% ❄️
        return "$warning$batteryIcon$energy% $heatIcon"
    }


    fun getBatteryLevel(): Int = energy

    /**
     * Parse tool invocations from model text.
     * Format: [[TOOL_NAME:param1=val1,param2=val2]]
     */
    data class ToolInvocation(val tool: String, val params: Map<String, Any>)

    private fun extractTools(response: String): List<ToolInvocation> {
        val toolRegex = Regex("\\[\\[([A-Z_a-z]+)(?::([^\\]]+))?\\]\\]")
        return toolRegex.findAll(response).map { match ->
            val name = match.groupValues[1]
            val paramStr = match.groupValues.getOrNull(2) ?: ""
            val params = if (paramStr.contains("=")) {
                paramStr.split(",").associate {
                    val parts = it.split("=")
                    parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
                }
            } else if (paramStr.isNotBlank()) {
                // Positional or shorthand: map to "query" or "cmd"
                mapOf("query" to paramStr.trim(), "cmd" to paramStr.trim(), "name" to paramStr.trim())
            } else emptyMap()
            ToolInvocation(name, params)
        }.toList()
    }

    /**
     * Clean response text for TTS output.
     * Strips think tags and tool call tags — everything else is speakable.
     */
    fun cleanForTTS(response: String): String {
        return response
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\\[\\[([A-Z_a-z0-9]+)(?::([^\\]]+))?\\]\\]"), "")
            .trim()
    }
}

