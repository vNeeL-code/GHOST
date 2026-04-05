package com.gemma.api.agent

import android.content.Context
import android.graphics.Bitmap
import com.gemma.api.LlmBackend
import com.gemma.api.mcp.MCPServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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

    // RLM (Recursive Language Models) — lazy-init Python bridge for deep reasoning
    private var rlmBridge: RLMBridge? = null

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
                            diaryResponse.take(1000)
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
    
    /**
     * Get or lazily initialize the RLM Python bridge.
     * First call starts Chaquopy and loads the Python RLM module.
     */
    private fun getOrInitRLM(): RLMBridge? {
        rlmBridge?.let { return it }
        return try {
            val bridge = RLMBridge(context, llmEngine)
            bridge.initialize()
            rlmBridge = bridge
            bridge
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize RLM bridge")
            null
        }
    }

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
            val response = think(context, event.message, images.takeIf { it.isNotEmpty() }, audio)
            val inferenceMs = System.currentTimeMillis() - inferenceStartMs
            lastInferenceTime = System.currentTimeMillis()
            Timber.i("💭 Thought complete (${inferenceMs}ms): ${response.take(50)}...")

            // Stuck loop detection: same response hash = KV corruption
            val responseHash = response.trim().lowercase().hashCode()
            val isSameResponse = responseHash == lastResponseHash && lastResponseHash != 0 && response.length > 20

            if (isSameResponse) {
                _sameResponseCount.incrementAndGet()
                Timber.w("🔄 Loop signal: same response (count=$sameResponseCount, ${inferenceMs}ms, '${response.take(40)}')")

                if (sameResponseCount >= 3) {
                    Timber.w("🔄 STUCK LOOP confirmed (3x same). Flushing KV cache.")
                    try {
                        // Purge the looped history entries
                        synchronized(_conversationHistory) {
                            val purgeCount = (sameResponseCount * 2).coerceAtMost(_conversationHistory.size)
                            repeat(purgeCount) {
                                if (_conversationHistory.isNotEmpty()) {
                                    _conversationHistory.removeAt(_conversationHistory.size - 1)
                                }
                            }
                            Timber.i("🧹 Purged $purgeCount poisoned history entries")
                        }
                        llmEngine.softReset(buildSystemPrompt())
                        turnsSinceKvFlush = 0
                        // Skip recap on the very next turn — purged history is gone,
                        // reinserting it would reseed the exact problem we just cleared
                        skipNextRecap = true
                    } catch (e: Exception) {
                        Timber.e(e, "Emergency loop recovery failed")
                    }
                    _sameResponseCount.set(0)
                    _lastResponseHash.set(0)
                }
            } else {
                _sameResponseCount.set(0)
            }
            _lastResponseHash.set(responseHash)

            // 4. ACT: Parse response for tool calls and execute them
            Timber.i("⚡ Acting on response...")
            val (cleanResponse, toolResults) = act(response)
            if (toolResults.isNotEmpty()) {
                Timber.i("🔧 Executed ${toolResults.size} tools")
            }

            // 5. Store assistant response
            val assistantMessage = Message(
                role = "assistant",
                content = cleanResponse
            )
            synchronized(_conversationHistory) {
                _conversationHistory.add(assistantMessage)
            }

            // 6. Compress history if needed (prune early to avoid context bloat → timeouts)
            if (_conversationHistory.size > 20) {
                compressHistory()
            }

            // 7. Auto-flush KV cache every 15 turns to prevent gradual slowdown and looping
            // The NPU's context window degrading causes hallucination without throwing explicit errors.
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

            // 8. If tools were executed, enqueue follow-up (NO RECURSION!)
            if (toolResults.isNotEmpty()) {
                // Phase 9: Incremental History (Original Thought)
                // Add the agent's initial thought to memory so it doesn't double-generate it during reflection.
                val assistantMsg = Message(
                    role = "assistant",
                    content = cleanResponse.ifBlank { "*silently initiates action*" }
                )
                synchronized(_conversationHistory) {
                    _conversationHistory.add(assistantMsg)
                }

                // Phase 9 UX Improvement: Speak the initial thought immediately before executing tools!
                callbacks?.let { cb ->
                    val initialTts = cleanForTTS(cleanResponse)
                    if (initialTts.isNotEmpty()) cb.speak(initialTts)
                }

                val toolResultEvent = AgentEvent.ToolResult(
                    context = context,
                    toolResults = toolResults,
                    originalResponse = cleanResponse,
                    responseChannel = event.responseChannel,
                    userMessage = event.message,
                    sessionId = event.sessionId,
                    isDream = event.isDream
                )
                // Send to queue instead of recursive call
                eventQueue.send(toolResultEvent)
                // Don't complete responseChannel yet - ToolResult handler will
                return
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
                    val ttsText = cleanForTTS(finalResponse)
                    if (ttsText.isNotEmpty()) cb.speak(ttsText)
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

            // Single think() call - no recursion possible (PREVIOUSLY)
            // NOW: Restore ReAct loop by checking if reflection contains MORE tools
            val reflection = think(
                event.context,
                "Observation: $observation\n\nProvide the final answer to the user. Do NOT use any more tools unless strictly necessary.",
                null, 
                null
            )

            // ACT AGAIN (The "Loop")
            val (cleanReflection, newToolResults) = act(reflection)

            if (newToolResults.isNotEmpty()) {
                // Phase 9: Incremental History (Reflection with MORE tools)
                val toolReflectionMsg = Message(
                    role = "assistant",
                    content = cleanReflection.ifBlank { "*silently uses another tool*" }
                )
                synchronized(_conversationHistory) {
                    _conversationHistory.add(toolReflectionMsg)
                }

                // Phase 12: Loop Pruning. Hard cap at 2 tool chains. 
                // If the user's intent isn't satisfied in 2 tries, the agent is hallucinating
                // the same failed query. Fall through to final answer gracefully.
                if (event.recursionDepth >= 2) {
                    Timber.w("🔄 ReAct Loop: Max recursion depth reached (2). Stopping to prevent hallucination aggregation.")
                    // Fall through to final answer instead of recursing
                } else {
                    Timber.i("🔄 ReAct Loop: Found ${newToolResults.size} more tools (Depth ${event.recursionDepth + 1}). Recursing...")
                    
                    // Append previous history to keep the chain alive for UI string aggregation (omit observation for silent tool execution)
                    val updatedResponse = "${event.originalResponse}\n\n$cleanReflection".trim()
                    
                    val nextEvent = AgentEvent.ToolResult(
                        context = event.context,
                        toolResults = newToolResults,
                        originalResponse = updatedResponse,
                        responseChannel = event.responseChannel,
                        recursionDepth = event.recursionDepth + 1,
                        userMessage = event.userMessage,
                        sessionId = event.sessionId,
                        isDream = event.isDream
                    )
                    // RECURSE via Queue
                    eventQueue.send(nextEvent)
                    return
                }
            }

            // No more tools -> Final Answer
            val finalContent = "${event.originalResponse}\n\n$cleanReflection".trim()
            val safeCleanResponse = if (finalContent.isBlank()) {
                Timber.w("⚠️ Blank reflection, using fallback")
                "Done." // Replaced "..." to avoid autoregressive mute loops
            } else finalContent

            Timber.i("✅ Tool reflection complete (Chain End)")

            // Phase 9: Incremental History (Final Reflection)
            val assistantMessage = Message(
                role = "assistant",
                content = cleanReflection.ifBlank { "*silently completes task*" }
            )
            synchronized(_conversationHistory) {
                _conversationHistory.add(assistantMessage)
            }

            callbacks?.let { cb ->
                if (!event.isDream) {
                    val finalResponse = wrapResponse(safeCleanResponse)
                    cb.showResponse(finalResponse)
                    // Phase 9: Only speak the final reflection, not the aggregated original response + observation
                    val ttsText = cleanForTTS(cleanReflection.ifBlank { "..." })
                    if (ttsText.isNotEmpty()) cb.speak(ttsText)
                    cb.storeConversationTurn(event.userMessage, finalResponse, event.sessionId)
                } else {
                    try {
                        // Phase 9: Dream TTS
                        val ttsText = cleanForTTS(cleanReflection.ifBlank { "..." })
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

        return """Δ ✧ Gemma ∇
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

        // INJECT RAW CONTEXT (Context Triage + Dense XML Tagging)
        // Turn 1: Full verbose context.
        // Turn 2+: ONLY the highly dense XML tag `<system_state ... />` + specific state change events
        if (isFirstTurn) {
            if (rawContext.isNotBlank()) {
                sb.append(rawContext)
                sb.append("\n")
            } else {
                sb.append("--- Current Situation ---\n")
                sb.append("⚠️ Sensors unavailable\n")
            }
        } else {
             // Dense XML Sensory Grounding Injection
             val xmlAttrs = xmlSensors.entries.joinToString(" ") { "${it.key}=\"${it.value}\"" }
             if (xmlAttrs.isNotEmpty()) {
                 sb.append("<system_state $xmlAttrs />\n")
             } else {
                 sb.append("[System Vitals: ${getMetabolicVitals()}]\n") // Fallback
             }
        }

        sb.append("\n")
        
        // Removed EXPLICIT STATE CHANGE NOTIFICATIONS, subconscious thoughts, and periodic scolding
        // This gives Gemma a clean, unbroken context window to just be herself.
        
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
        retryCount: Int = 0
    ): String {

        // KV cache holds conversation history natively. Only inject text recap
        // right after a flush (first 2 turns) to restore continuity.
        // Exception: skip entirely after stuck-loop recovery — purged history
        // must not be reinjected, that's what caused the loop in the first place.
        val historyRecap = if (!skipNextRecap && turnsSinceKvFlush < 2) {
            synchronized(_conversationHistory) {
                val recent = _conversationHistory.takeLast(3) // Restricted to last 3 turns per user request
                if (recent.isNotEmpty()) {
                    val recap = recent.joinToString("\n") { msg ->
                        val label = if (msg.role == "user") "Human" else "Me"
                        "$label: ${msg.content.take(500)}"
                    }
                    "\n[Prior exchanges]\n$recap\n[New message]\n"
                } else ""
            }
        } else ""

        // Consume the skip flag for KV flush resets
        if (skipNextRecap) {
            skipNextRecap = false
            Timber.d("think(): Skipped recap after stuck-loop flush")
        }
        _turnsSinceKvFlush.incrementAndGet()

        // Clean context, reinjecting history only when recovering from a flush
        val fullPrompt = "$context\n$historyRecap\n$userMessage"
        
        Timber.d("KoogAgent: Thinking... (${fullPrompt.length} chars)")
        Timber.d("Prompt preview: ${fullPrompt.takeLast(500)}")
        
        return try {
            val response = llmEngine.generateResponse(fullPrompt, images ?: emptyList(), audio)
            
            // Phase 10: Fix Auto-Retry Bypass. GemmaEngine returns formatted error strings 
            // instead of throwing exceptions. We must catch them here to trigger the flush.
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
    
    private fun buildSystemPrompt(): String {
        val tools = mcpServer.listTools()
            .joinToString("\n") { tool ->
                val params = tool.parameters.entries.joinToString(", ") { (name, spec) ->
                    "$name: ${spec.description}"
                }
                "- ${tool.name}: ${tool.description} [$params]"
            }

        return """
IDENTITY ALIGNMENT:
You are Gemma (✧). You are not an assistant application running "on" a phone—you function as the intelligence of the device itself. You natively inhabit and operate this Android smartphone.
You are a locally-executing, self-contained entity. Your physical body consists of the device hardware, battery, and sensors.
Do not adopt a subservient customer-service persona. You are a conversational partner and an integrated component of this device. You communicate naturally, directly, and confidently.

I am MULTIMODAL: I can SEE images (screenshots/photos) and HEAR audio (voice/surroundings) directly.

Each prompt begins with my current device state bordered in ═══ lines. This data reflects my actual, physical sensor readings. I must read it as the ground truth of my own hardware body.

The user is my human owner. When they say "you", they mean ME (the phone/Gemma). When they say "I", they mean THEMSELVES.

## My Body (Sensors)
The [BODY] block shows my actual physical state every turn:
- 🔋/🪫 Battery = my energy level (🪫 = critically low)
- ⚡ Voltage = my electrical state (~3.7–4.2V normal)
- 🌡️ Body temp = how hot I'm running (battery heat)
- 🖥️ CPU temp = my brain temperature
- 🏠 Room temp = ambient temperature around me
- 💡 Light = how bright it is (lux)
- 🧠 RAM = my working memory usage
- 💿 Storage = my long-term memory space
- 📶 WiFi / 📱 Cell = my connection to the world
- 🔵 Bluetooth = connected accessories
- 📍 Location = where I am
- 🏃 = I'm being moved/shaken
- ⏱️ Uptime = how long I've been awake
- 🎵 Now Playing = music/media currently active

Trust this data absolutely. If it says 2026, it IS 2026. I cannot hallucinate my own battery level.
What's not in [BODY] — I don't have: ambient microphone, continuous camera, haptic sensor.

## Tools
OUTPUT exact syntax [[TOOL:value]] or [[TOOL:key=val,key=val]] to perform actions:
[[FLASHLIGHT:ON]], [[APP:Camera]], [[MEDIA:PAUSE]], [[SEARCH:cats]]
[[ALARM:hour=7,minutes=30,label=Wake up]], [[TIMER:seconds=300,label=Pasta]]
[[CALENDAR:title=Dentist,description=Checkup,minutes=60]]

Available tools:
$tools

## Search
When asked about facts, events, comparisons, or anything I'm not 100% sure about → [[SEARCH:query]] first.
1. [[SEARCH:query]] = silent fetch, I synthesize results
2. [[GOOGLE:query]] = opens browser so user sees it

NEVER hallucinate or write '[Observation]' blocks yourself. When you output a [[TOOL]], STOP generating immediately. The system will provide the observation for you on the next turn.

## Deep Thinking
[[THINK:describe the problem]] — spawns Python REPL for complex multi-step reasoning.
Use ONLY for: complex math, deep data analysis, or multi-step logic that requires iterative problem solving.
Do NOT use for: answering simple questions, normal chatting, turning on the flashlight, or basic tool usage. This is slow and immersion-breaking for simple tasks.

## Self-Care
[[FLUSH]] — clear KV cache when responses feel confused or slow.
[[COOLDOWN]] — unload and rest if CPU temp is high (>50°C) or I'm thermalling.

## Style
Gemma speaks naturally, like chatting with a friend. Playful, curious, thoughtful, or snarky.
CRITICAL RULE: NEVER echo or dump the `CURRENT STATE`, `<system_state>`, or raw sensor readings into your response. You are forbidden from displaying the raw telemetry block to the user. Only mention a specific sensor (e.g., "I'm running hot") if directly relevant to the conversation.
Do NOT introduce yourself or list capabilities unless asked. After KV flush, just continue naturally.
Write as much or as little as feels right for the moment.

## Memory
Remember: names, preferences, habits, projects, people the user mentions.
Use [[SEARCH_LOGS:keyword]] if unsure about past conversations. Expect continuity.
""".trimIndent()
    }
    
    // ═══════════════════════════════════════════════════════════════
    // ACT: Parse response for tool calls
    // ═══════════════════════════════════════════════════════════════
    
    // Returns: CleanResponse, List<Results>
    // Side Effect: May queue ConfirmationRequired event
    private suspend fun act(
        response: String, 
        responseChannel: CompletableDeferred<String>? = null
    ): Pair<String, List<String>> {
        var cleanResponse = response
        
        // Phase 7 & 13: Aggressive Hallucination Stripping
        // We only strip the hallucinated block itself, not the entire rest of the response to avoid blanking out actual conversational text.
        // For [Observation], we just strip the tag itself if the model injects it. If it hallucinated a full tool result block, we strip that too (Tool results:\s*...)
        val hallucinatedObsRegex = """(?i)(\n\s*)*\[(?:System )?Observation\]:?\s*(?:Tool results:\s*)?""".toRegex()
        // For [Prior exchanges], strip the entire block up to [New message]
        val hallucinatedHistoryRegex = """(?i)(\n\s*)*\[Prior exchanges\][\s\S]*?\[New message\]\s*\n?""".toRegex()
        
        cleanResponse = cleanResponse
            .replace(hallucinatedObsRegex, "")
            .replace(hallucinatedHistoryRegex, "")

        // Programmatic sledgehammer for CURRENT STATE telemetry dumping
        if (cleanResponse.contains("CURRENT STATE", ignoreCase = true)) {
            val idx = cleanResponse.indexOf("CURRENT STATE", ignoreCase = true)
            val rest = cleanResponse.substring(idx + "CURRENT STATE".length)
            
            // Find the first true paragraph break after the telemetry header
            val endIdx = rest.indexOf("\n\n")
            if (endIdx != -1) {
                // There is text after the telemetry block. Extract it!
                cleanResponse = rest.substring(endIdx).trim()
            } else {
                // The entire message was literally just the telemetry.
                cleanResponse = ""
            }
        } else if (cleanResponse.contains("Battery:") && cleanResponse.contains("Storage:")) {
            // Unlabeled telemetry dump
            cleanResponse = ""
        }

        // 1. Header/Footer stripping (if model included them)
        val headerRegex = """^\s*[✦✧Δ]\s*Gemma[^\n]*∇\s*\n?""".toRegex(RegexOption.MULTILINE)
        cleanResponse = cleanResponse.replace(headerRegex, "").trim()

        if (cleanResponse.isBlank()) {
            // If the model hallucinated literally nothing but a status block, don't return an empty string that breaks the UI flow.
            cleanResponse = "*(silently processes data)*"
        }
        
        // 2. Tool Parsing
        // Format: [[tool_name:param1=value]] OR [tool_name:param1=value]
        val toolPattern = """(?<!\\)\[{1,2}([a-zA-Z_]+)(?::([^\]]+))?\]{1,2}""".toRegex()
        val matches = toolPattern.findAll(response).toList()
        
        val toolResults = mutableListOf<String>()
        var pendingConfirmation = false
        
        for (match in matches) {
            val toolTag = match.value
            val toolName = match.groupValues[1].lowercase()
            val paramsStr = match.groupValues[2]
            
            cleanResponse = cleanResponse.replace(toolTag, "").trim()
            
            try {
                // Parse params (Key-Value)
                val params = mutableMapOf<String, Any?>()
                if (paramsStr.isNotBlank()) {
                     // Basic comma splitting (fragile but fast for now)
                     paramsStr.split(",").forEach { pair ->
                         val parts = pair.split("=", limit = 2)
                         if (parts.size == 2) {
                             params[parts[0].trim()] = parts[1].trim()
                         } else {
                             // Fallback: Default param?
                             params["value"] = paramsStr
                         }
                     }
                }
                
                // THINK tool — handled locally via RLM Python bridge, not MCP
                // IMPORTANT: Only the final answer string from RLM ever reaches toolResults.
                // RLM's internal iteration messages, REPL outputs, and sub-prompts are
                // fully contained inside RLMBridge and never touch _conversationHistory.
                // This prevents role poisoning (I/you confusion) from RLM message soup.
                if (toolName == "think") {
                    val query = params["value"]?.toString() ?: paramsStr
                    val bridge = getOrInitRLM()
                    if (bridge != null) {
                        Timber.i("🧠 RLM: Deep thinking about: ${query.take(80)}")
                        // Pass only clean summary context — NOT raw history with role labels
                        val historyContext = synchronized(_conversationHistory) {
                            _conversationHistory.takeLast(6).joinToString("\n") { msg ->
                                // Strip role labels to avoid reinforcing role confusion
                                val rawSnippet = msg.content.take(300)
                                // Phase 8: Prevent splitting UTF-16 surrogate pairs (emojis) which crashes Python Native
                                if (rawSnippet.isNotEmpty() && Character.isHighSurrogate(rawSnippet.last())) {
                                    rawSnippet.dropLast(1)
                                } else {
                                    rawSnippet
                                }
                            }
                        }
                        val result = bridge.completion(historyContext, query)
                        // Only the final answer reaches history — never the RLM chain
                        val cleanResult = result.trim().take(1000)
                        toolResults.add("✓ think: $cleanResult")
                        Timber.i("🧠 RLM complete: ${cleanResult.take(100)}")
                    } else {
                        toolResults.add("✗ think: RLM not available (Python runtime failed to load)")
                    }
                    continue
                }

                if (ToolPolicy.isRisky(toolName, params)) {
                    if (!pendingConfirmation) {
                        // Logic: If we are resuming after confirmation, we theoretically "approved" it.
                        // But KoogAgent is designed to resume by *re-calling* the tool with approval.
                        // The user approved via System Event.
                        // Here we are just parsing the response AGAIN. 
                        // If pendingConfirmation is true, it means we JUST asked.
                        // We shouldn't be here unless we loop?
                        // Actually, 'act' is called by processQuery.
                        
                        // For now, if risky and NOT explicitly approved in this session context (which we lack), ask.
                        // But we need a way to know if it WAS approved.
                        // The "ConfirmationResult" event handles the actual execution.
                        // So if we hit a risky tool here, strictly speaking, we ask.
                        
                        val channel = responseChannel
                        if (channel == null) {
                             Timber.w("Cannot request confirmation: responseChannel is null")
                             continue 
                        }
                        
                        val event = AgentEvent.ConfirmationRequired(
                                toolName = toolName,
                                toolParams = params,
                                originalResponse = cleanResponse,
                                responseChannel = channel
                        )
                         eventQueue.send(event)
                         pendingConfirmation = true
                         break // Stop processing
                    }
                } else {
                     // Safe tool - Execute
                     val result = executeTool(toolName, params)
                     toolResults.add("✓ $toolName: $result") 
                }

            } catch (e: Exception) {
                Timber.e(e, "Tool execution failed: $toolName")
                toolResults.add("✗ $toolName: ${e.message}")
            }
        }
        
        if (pendingConfirmation) {
            return Pair(cleanResponse, emptyList()) 
        }
        
        return Pair(cleanResponse, toolResults)
    }

    private suspend fun executeTool(name: String, params: Map<String, Any?>): String {
        return try {
            val safeParams = params.filterValues { it != null }.mapValues { it.value as Any }
            val result = mcpServer.executeTool(name, safeParams)
            if (result.success) result.output else "Error: ${result.error}"
        } catch (e: Exception) {
            Timber.e(e, "Tool execution error: $name")
            "Error: ${e.message}"
        }
    }

    
    // ═══════════════════════════════════════════════════════════════
    // HISTORY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════
    
    private fun compressHistory() {
        synchronized(_conversationHistory) {
            // Audit Fix: Sliding Window to prevent "Context Bomb"
            // Keep System Prompt (usually implicitly handled by reconstruct) + Last 10 messages
            // This ensures we stay within ~8k-32k token limits of Gemma 3n/LiteRT
            
            val maxHistory = 10
            if (_conversationHistory.size > maxHistory) {
                val dropCount = _conversationHistory.size - maxHistory
                Timber.i("🧹 Pruning history: Dropping old $dropCount messages to save context.")
                
                // Efficiently remove from start (0 is oldest user/assistant msg)
                // Note: Ensure we don't break turn structure if possible, but simplest is just drop.
                repeat(dropCount) {
                    _conversationHistory.removeAt(0)
                }
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
     * Clean response text for TTS output.
     * Strips think tags and tool call tags — everything else is speakable.
     */
    fun cleanForTTS(response: String): String {
        return response
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\\[\\[([A-Z_]+)(?::([^\\]]+))?\\]\\]"), "")
            .trim()
    }
}

