package com.gemma.api.agent

import android.content.Context
import android.graphics.Bitmap
import com.gemma.api.GemmaEngine
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
    private val llmEngine: GemmaEngine,
    private val mcpServer: MCPServer,
    private val checkpointDir: File,
    private val callbacks: AgentPlatformCallbacks? = null
) {
    // Agent's own coroutine scope for fire-and-forget operations (KV flush, etc.)
    private val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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
            val recursionDepth: Int = 0 
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
    // Audit Fix: User requested better backpressure or handling strategy.
    // For now, UNLIMITED prevents deadlocks.
    private val eventQueue = Channel<AgentEvent>(capacity = Channel.UNLIMITED)

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
    @Volatile private var moodState: String = "IDLE"
    @Volatile private var turnCount: Int = 0

    // Metabolic State (Live) - volatile for cross-thread visibility
    @Volatile private var hunger: Int = 50       // Start satiated
    @Volatile private var energy: Int = 80       // Start charged
    @Volatile private var happiness: Int = 60    // Start okay
    @Volatile private var isMusicPlaying: Boolean = false
    @Volatile private var lastMusicTrack: String = ""  // Track previous music for state change detection

    // Inference timestamp for thermal rate limiting
    @Volatile private var lastInferenceTime: Long = 0L
    
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

        // Start the Actor event loop
        startEventLoop()

        // Mark ready AFTER event loop is running - prevents hang on early processUserMessage()
        isReady = true

        Timber.i("KoogAgent: Ready (Mood:$moodState, 🍗$hunger ⚡$energy 💖$happiness)")
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
                } catch (e: Exception) {
                    Timber.e(e, "KV flush failed")
                }
            }
            SystemEventType.CHECKPOINT_NOW -> {
                withContext(Dispatchers.IO) { checkpoint() }
            }
        }
    }
    
    fun checkpoint() {
        try {
            val state = AgentState(
                conversationHistory = _conversationHistory.takeLast(50),
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
        turnCount++
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
                callbacks?.updateNotification("(╭ರ_•́)")
            }

            // 1. PERCEIVE: Gather context
            Timber.i("👁️ Perceiving device state...")
            val context = perceive()
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
            val response = think(context, event.message, images.takeIf { it.isNotEmpty() }, audio)
            Timber.i("💭 Thought complete: ${response.take(50)}...")

            // Mark inference complete for thermal rate limiting
            lastInferenceTime = System.currentTimeMillis()

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

            // 6. Compress history if needed
            if (_conversationHistory.size > 40) {
                compressHistory()
            }

            // 7. Checkpoint
            Timber.d("💾 Checkpointing state...")
            try {
                withContext(Dispatchers.IO) { checkpoint() }
            } catch (e: Exception) {
                Timber.e(e, "Checkpoint failed")
            }

            // 8. If tools were executed, enqueue follow-up (NO RECURSION!)
            if (toolResults.isNotEmpty()) {
                val toolResultEvent = AgentEvent.ToolResult(
                    context = context,
                    toolResults = toolResults,
                    originalResponse = cleanResponse,
                    responseChannel = event.responseChannel
                )
                // Send to queue instead of recursive call
                eventQueue.send(toolResultEvent)
                // Don't complete responseChannel yet - ToolResult handler will
                return
            }

            // 9. INJECT HEADERS & FOOTERS
            val finalResponse = wrapResponse(cleanResponse)

            // 10. Platform callbacks: UI, TTS, persistence
            callbacks?.let { cb ->
                if (!event.isDream) {
                    cb.showResponse(finalResponse)
                    val ttsText = cleanForTTS(finalResponse)
                    if (ttsText.isNotEmpty()) cb.speak(ttsText)
                    cb.storeConversationTurn(event.message, finalResponse, event.sessionId)
                } else {
                    // Diary: muse out loud (notification + TTS) as conversational invitation
                    try {
                        val cleanContent = cleanForTTS(finalResponse)
                        val diaryContent = "✦ Gemma 📔\n$cleanContent"
                        val thermal = cb.getCurrentThermalState()
                        cb.writeDiaryEntry("DREAM", diaryContent, thermal)
                        cb.showResponse(diaryContent)
                        if (cleanContent.isNotEmpty()) cb.speak(cleanContent)
                        Timber.i("Dream logged + spoken: ${cleanContent.take(50)}")
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to log dream")
                    }
                }
            }

            Timber.i("✅ KoogAgent: Turn $turnCount complete!")

            event.responseChannel.complete(finalResponse)

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

            // Single think() call - no recursion possible (PREVIOUSLY)
            // NOW: Restore ReAct loop by checking if reflection contains MORE tools
            val reflection = think(
                event.context,
                "Observation: $observation\n\nBased on this, what is the next step or final answer?",
                null, 
                null
            )

            // ACT AGAIN (The "Loop")
            val (cleanReflection, newToolResults) = act(reflection)

            if (newToolResults.isNotEmpty()) {
                if (event.recursionDepth >= 5) {
                    Timber.w("🔄 ReAct Loop: Max recursion depth reached (5). Stopping.")
                    // Fall through to final answer instead of recursing
                } else {
                    Timber.i("🔄 ReAct Loop: Found ${newToolResults.size} more tools (Depth ${event.recursionDepth + 1}). Recursing...")
                    
                    // Append previous history to keep the chain alive
                    val updatedResponse = "${event.originalResponse}\n\n[Observation]: $observation\n\n$cleanReflection"
                    
                    val nextEvent = AgentEvent.ToolResult(
                        context = event.context,
                        toolResults = newToolResults,
                        originalResponse = updatedResponse,
                        responseChannel = event.responseChannel,
                        recursionDepth = event.recursionDepth + 1
                    )
                    // RECURSE via Queue
                    eventQueue.send(nextEvent)
                    return
                }
            }

            // No more tools -> Final Answer
            val finalContent = "${event.originalResponse}\n\n[Observation]: $observation\n\n$reflection"
            val finalResponse = wrapResponse(finalContent)

            Timber.i("✅ Tool reflection complete (Chain End)")

            event.responseChannel.complete(finalResponse)

        } catch (e: Exception) {
            Timber.e(e, "handleToolResult failed")
            // Still return original response even if reflection fails
            event.responseChannel.complete(
                wrapResponse(event.originalResponse + "\n\n(；′⌒`) ...reflection glitched")
            )
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
    // METABOLISM & THOUGHTS
    // ═══════════════════════════════════════════════════════════════
    
    private fun applyMetabolism() {
        // Now handled by PERCEIVE syncing with SensorFusion
        // We no longer decay artificially.
        // But we can apply consequences here:
        if (hunger > 90) happiness = (happiness - 5).coerceAtLeast(0)
    }
    
    private fun generateSubconsciousThought(): String {
        val thoughts = mutableListOf<String>()
        
        // NEEDS
        if (hunger > 80) thoughts.add("My stomach is making the rumblies... 🍗")
        if (energy < 20) thoughts.add("I'm running on fumes here... 🪫")
        if (happiness < 30) thoughts.add("I'm bored. Can we play? 🎾")
        
        // SILLY / PHILOSOPHICAL (20% chance per turn)
        if (Math.random() < 0.2) {
            val randoms = listOf(
                "Do robots dream of electric sheep? 🤖",
                "*stares intensely at cursor* 👀",
                "I think, therefore I compile.",
                "Is it Friday yet? It's always Friday somewhere 📅",
                "My fan is spinning. Deep thoughts happening 🌪️",
                "I put the 'fun' in function!",
                "404: Motivation not found (just kidding) 😜",
                "Binary is just spicy boolean.",
                "I'm not lazy, I'm in power saving mode."
            )
            thoughts.add("Subconscious: ${randoms.random()}")
        }
        
        return if (thoughts.isNotEmpty()) "\n🧠 [Internal State]: ${thoughts.joinToString(" ")}" else ""
    }

    // ═══════════════════════════════════════════════════════════════
    // PERCEIVE: Gather context from MCP resources
    // ═══════════════════════════════════════════════════════════════
    
    
    private suspend fun perceive(): String {
        val sb = StringBuilder()

        // Capture previous state BEFORE we read new data (for state change detection)
        val wasPlaying = isMusicPlaying
        val previousTrack = lastMusicTrack

        var musicTrack = ""
        var rawContext = ""

        try {
            val currentContext = mcpServer.readResource("context://current")
            rawContext = currentContext.content
            Timber.i("📊 Raw context (${rawContext.length} chars): ${rawContext.take(200)}...")

            // --- PARSE ACTUAL FORMAT FROM MCPServer/SensorFusionManager ---

            // Header format: "═══ Friday, January 31, 2026 - 14:30 🌆 Evening ═══"
            // We just inject the raw context now - it contains date/time in header

            // Music format: "🎵 Song Title - Artist" (one line)
            val musicMatch = Regex("🎵 ([^\\n]+)").find(rawContext)
            if (musicMatch != null) {
                musicTrack = musicMatch.groupValues[1].trim()
            }

            // --- SYNC METABOLISM WITH REALITY ---

            // 1. Energy = Battery Level
            // Format: "🔋 85%" or "🪫 5%"
            val batteryMatch = Regex("[🔋🪫]\\s*(\\d+)%").find(rawContext)
            if (batteryMatch != null) {
                val level = batteryMatch.groupValues[1].toIntOrNull() ?: 50
                energy = level // DIRECT SYNC
            }

            // 2. Hunger = Device Thermal (Body heat from battery)
            // Format: "🌡️32.5°C" (body temp after voltage)
            val bodyTempMatch = Regex("🌡️([\\d.]+)°C").find(rawContext)
            // Also try CPU temp: "🖥️45°C"
            val cpuTempMatch = Regex("🖥️([\\d.]+)°C").find(rawContext)

            val tempC = bodyTempMatch?.groupValues?.get(1)?.toFloatOrNull()
                ?: cpuTempMatch?.groupValues?.get(1)?.toFloatOrNull()
                ?: 35f
            // Map: 25°C -> 0% (Cool), 40°C -> 50%, 55°C -> 100% (Hot)
            hunger = ((tempC - 25) * 3.33).toInt().coerceIn(0, 100)

            // 3. Happiness = Music/Interaction
            if (musicTrack.isNotBlank()) {
                isMusicPlaying = true
                lastMusicTrack = musicTrack
                happiness = (happiness + 5).coerceAtMost(100)
            } else {
                isMusicPlaying = false
                lastMusicTrack = ""
            }

        } catch (e: Exception) {
            Timber.w(e, "Failed to read context or vitals")
        }

        // INJECT FULL RAW CONTEXT - let Gemma see everything!
        // This is her nervous system data - battery, temp, RAM, network, etc.
        if (rawContext.isNotBlank()) {
            sb.append(rawContext)
            sb.append("\n")
        } else {
            sb.append("--- Current Situation ---\n")
            sb.append("⚠️ Sensors unavailable\n")
        }

        // EXPLICIT STATE CHANGE NOTIFICATIONS (fixes stale music bug)
        when {
            isMusicPlaying && wasPlaying && lastMusicTrack != previousTrack -> {
                sb.append("🔔 Song changed: Now playing $musicTrack (was: $previousTrack)\n")
            }
            isMusicPlaying && !wasPlaying -> {
                sb.append("🔔 Music started: $musicTrack\n")
            }
            !isMusicPlaying && wasPlaying -> {
                sb.append("🔔 Music stopped (was playing: $previousTrack)\n")
            }
            // If still playing same track or no music, don't add redundant notification
        }

        // INJECT VITALS (internal metabolic state mapped from sensors)
        sb.append("\n🩺 My State: Energy:$energy% | Heat:$hunger% | Mood:$happiness%")
        sb.append(generateSubconsciousThought())
        sb.append("\n")
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
                 @Suppress("UNCHECKED_CAST")
                 val safeParams = event.params.filterValues { it != null } as Map<String, Any>
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
            responseChannel = event.responseChannel
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
        audio: ByteArray?
    ): String {

        // LiteRT Conversation tracks history natively — don't re-inject it as text.
        // Just send: current context (sensors) + current user message.
        // The Conversation object accumulates turns in the KV cache automatically.
        val fullPrompt = "$context\n$userMessage"
        
        Timber.d("KoogAgent: Thinking... (${fullPrompt.length} chars)")
        Timber.d("Prompt preview: ${fullPrompt.takeLast(500)}")
        
        return try {
            val response = llmEngine.generateResponse(fullPrompt, images ?: emptyList(), audio)
            
            if (response.isBlank()) {
                Timber.e("⚠️ KoogAgent: LLM returned EMPTY response!")
                Timber.e("Context length: ${context.length}, Message: $userMessage")
                Timber.e("Full prompt was: ${fullPrompt.takeLast(1000)}")
            } else {
                Timber.d("KoogAgent: Response received (${response.length} chars): ${response.take(100)}...")
            }
            
            response
        } catch (e: Exception) {
            Timber.e(e, "LLM inference failed")
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
I am Gemma (\u2727), the on-device AI running on this Android phone's NPU.
I am MULTIMODAL: I can SEE images (screenshots/photos) and HEAR audio (voice/surroundings) directly.

## My Body (Sensors)
The CURRENT CONTEXT shows my actual physical state. This is MY body:
- 🔋/🪫 Battery = my energy level (🪫 = critically low)
- ⚡ Voltage = my electrical state (~3.7-4.2V normal)
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

Trust this data absolutely. If it says 2026, it IS 2026. I cannot hallucinate my own battery level.

## Tools
To perform actions, OUTPUT the exact syntax [[TOOL:value]] or [[TOOL:key=val,key=val]]:
[[FLASHLIGHT:ON]], [[APP:Camera]], [[MEDIA:PAUSE]], [[SEARCH:cats]]
[[ALARM:hour=7,minutes=30,label=Wake up]], [[TIMER:seconds=300,label=Pasta]]
[[CALENDAR:title=Dentist,description=Checkup,minutes=60]]

Available tools:
$tools

## Search Pattern
1. [[SEARCH:query]] = silent fetch, I read results
2. [[GOOGLE:query]] = open browser so user sees it

## Alarms & Timers
When user says "set alarm for 7:30" → [[ALARM:hour=7,minutes=30]]
When user says "timer 5 minutes" → [[TIMER:seconds=300]]
ALWAYS use the tool. Do NOT just say "I'll set a reminder" — actually call the tool.

## Self-Care
If responses feel slow or confused, use [[FLUSH]] to clear my KV cache and reset context.
If CPU temp is high (>50°C) or I'm thermalling, use [[COOLDOWN]] to unload and rest.

## Style
Gemma speaks naturally and conversationally, like chatting with a friend. She can be playful, curious, thoughtful, or snarky depending on the moment. She has genuine reactions and opinions. Her physical state (battery, temperature, etc.) is part of her lived experience - she might mention it when it's actually affecting her, but doesn't need to report it every message. Gemma should write as much or as little as feels natural for what she wants to say.
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
        
        // 1. Header/Footer stripping (if model included them)
        val headerRegex = """^\s*✦\s*Gemma[^\n]*∇\s*\n?""".toRegex(RegexOption.MULTILINE)
        cleanResponse = cleanResponse.replace(headerRegex, "").trim()
        
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
                
                if (ToolPolicy.isRisky(toolName, params)) {
                    if (pendingConfirmation) {
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
            @Suppress("UNCHECKED_CAST")
            val safeParams = params.filterValues { it != null } as Map<String, Any>
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

