package com.ghost.api.agent

import android.content.Context
import android.graphics.Bitmap
import com.ghost.api.Constants
import com.ghost.api.GemmaEngine
import com.ghost.api.LlmBackend
import com.ghost.api.database.MemoryManager
import com.ghost.api.hardware.SensorFusionManager
import com.ghost.api.logic.ContextManager
import com.ghost.api.skills.SkillManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * GhostAgent - Lean Google ADK / Edge Gallery Aligned On-Device Agent Runtime.
 *
 * Replaces the legacy 1,785-line monolithic KoogAgent. Leverages dynamic MCP tool
 * routing via GhostMcpTool, enabling Gemma 4 E4B (Frontier) and E2B (Compact) to run
 * reliably within a strict 1,200-1,536 token budget on mobile GPU without LMK termination.
 */
class GhostAgent(
    private val context: Context,
    private val llmEngine: LlmBackend,
    private val sensorManager: SensorFusionManager,
    private val contextManager: ContextManager,
    private val skillManager: SkillManager,
    private val checkpointDir: File,
    private val mcpTool: GhostMcpTool,
    private val callbacks: AgentPlatformCallbacks? = null
) {
    sealed class AgentEvent {
        data class ConfirmationRequired(
            val toolName: String,
            val toolParams: Map<String, Any?>,
            val originalResponse: String,
            val responseChannel: CompletableDeferred<String>
        ) : AgentEvent()

        data class ConfirmationResult(
            val toolName: String,
            val params: Map<String, Any?>,
            val isApproved: Boolean,
            val originalResponse: String,
            val responseChannel: CompletableDeferred<String>
        ) : AgentEvent()
    }

    enum class SystemEventType {
        THERMAL_THROTTLE,
        THERMAL_CRITICAL,
        LOW_BATTERY,
        KV_CACHE_FLUSH,
        CHECKPOINT_NOW
    }

    data class AgentState(
        val conversationHistory: List<AgentMessage>,
        val turnCount: Int,
        val rollingMemory: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val gson = Gson()
    private val checkpointFile = File(checkpointDir, "ghost_agent_checkpoint.json")
    private val memoryManager by lazy { MemoryManager(context) }
    private val agentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val inferenceMutex = Mutex()

    @Volatile var isReady: Boolean = false
        private set

    var onConfirmationRequest: ((AgentEvent.ConfirmationRequired) -> Unit)? = null

    private val _conversationHistory = java.util.Collections.synchronizedList(mutableListOf<AgentMessage>())
    val conversationHistory: List<AgentMessage>
        get() = synchronized(_conversationHistory) { _conversationHistory.toList() }

    fun getRecentConversationTurns(maxTurns: Int = 3): List<AgentMessage> {
        return synchronized(_conversationHistory) {
            _conversationHistory.takeLast(maxTurns * 2).toList()
        }
    }

    private val turnCount = AtomicInteger(0)
    private var turnsSinceKvFlush = 0
    private var rollingMemory = ""
    private var lastInferenceTime = 0L

    private val sentenceBoundaryRegex = Regex("""(?<!\b(?:Mr|Mrs|Ms|Dr|e\.g|i\.e|vs|etc|approx|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\.)(?<!\d)([.!?]+['"”’)]*)(?:\s+|\n+)""")

    private fun isToolInvocation(buffer: CharSequence): Boolean {
        val s = buffer.trimStart()
        return s.startsWith("call:") ||
               s.startsWith("<|tool") ||
               s.startsWith("execute_action") ||
               s.startsWith("runMcpTool")
    }

    // Media queues
    data class QueuedImage(val bitmap: Bitmap, val uri: String?)
    private val imageQueue = ConcurrentLinkedQueue<QueuedImage>()
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()

    init {
        // Wire tool lifecycle hooks
        mcpTool.onToolExecuting = { toolName, params ->
            callbacks?.onThoughtUpdated("Executing: $toolName...")
            callbacks?.updateNotification("Tool: $toolName")
        }
        mcpTool.onToolExecuted = { toolName, params, result ->
            callbacks?.onThoughtUpdated("Completed: $toolName")
        }
    }

    private fun getSanitizedRecentMessages(maxTurns: Int = 2): List<com.google.ai.edge.litertlm.Message> {
        return synchronized(_conversationHistory) {
            val list = mutableListOf<com.google.ai.edge.litertlm.Message>()
            val candidates = _conversationHistory.takeLast(maxTurns * 2)
            var expectedRole = "user"
            for (msg in candidates) {
                val clean = msg.content.trim()
                if (clean.isBlank()) continue
                val role = if (msg.role == "user") "user" else if (msg.role == "assistant") "model" else continue
                if (role == expectedRole) {
                    if (role == "user") {
                        list.add(com.google.ai.edge.litertlm.Message.user(clean))
                        expectedRole = "model"
                    } else {
                        list.add(com.google.ai.edge.litertlm.Message.model(clean))
                        expectedRole = "user"
                    }
                }
            }
            // Must end on model so the conversation engine is ready for the incoming user prompt
            while (list.isNotEmpty() && expectedRole == "model") {
                list.removeAt(list.size - 1)
            }
            list
        }
    }

    suspend fun initialize() {
        try {
            Timber.i("GhostAgent: Initializing...")
            restoreCheckpoint()

            val initialPrompt = buildSystemPrompt()
            val recentMessages = getSanitizedRecentMessages(2)

            llmEngine.softReset(initialPrompt, listOf(mcpTool), recentMessages)
            isReady = true
            Timber.i("GhostAgent: Ready (Battery: ${getBatteryLevel()}%)")
        } catch (e: Exception) {
            Timber.e(e, "GhostAgent: Failed to initialize")
            isReady = true // allow retry
        }
    }

    fun shutdown() {
        isReady = false
        checkpoint()
        agentScope.cancel()
        Timber.i("GhostAgent: Shutdown complete")
    }

    suspend fun softReset() {
        inferenceMutex.withLock {
            turnsSinceKvFlush = 0
            val prompt = buildSystemPrompt()
            val recentMessages = getSanitizedRecentMessages(2)
            llmEngine.softReset(prompt, listOf(mcpTool), recentMessages)
            Timber.i("GhostAgent: Soft reset complete with ${recentMessages.size} history msgs preserved")
        }
    }

    fun offerImage(bmp: Bitmap, uri: String? = null) {
        imageQueue.offer(QueuedImage(bmp, uri))
        Timber.d("GhostAgent: Image queued (${imageQueue.size} in queue)")
    }

    fun offerAudio(audio: ByteArray) {
        audioQueue.offer(audio)
        Timber.d("GhostAgent: Audio queued (${audio.size} bytes)")
    }

    private fun drainMedia(): Pair<List<QueuedImage>, ByteArray?> {
        val images = mutableListOf<QueuedImage>()
        while (!imageQueue.isEmpty()) {
            imageQueue.poll()?.let { images.add(it) }
        }
        val audioBytes = if (!audioQueue.isEmpty()) {
            val stream = ByteArrayOutputStream()
            while (!audioQueue.isEmpty()) {
                audioQueue.poll()?.let { stream.write(it) }
            }
            stream.toByteArray()
        } else null
        return Pair(images, audioBytes)
    }

    fun recordToolChars(charCount: Int) {
        // Track output token budget
        if (charCount > 1500) {
            agentScope.launch {
                maybeCompactHistory()
            }
        }
    }

    fun sendSystemEvent(type: SystemEventType, payload: String? = null) {
        agentScope.launch {
            when (type) {
                SystemEventType.KV_CACHE_FLUSH -> softReset()
                SystemEventType.CHECKPOINT_NOW -> checkpoint()
                SystemEventType.THERMAL_CRITICAL -> {
                    Timber.w("GhostAgent: Thermal critical received, throttling...")
                }
                else -> Timber.d("GhostAgent: System event $type received")
            }
        }
    }

    /**
     * Core inference turn: Processes user query through the ADK ReAct loop.
     */
    suspend fun processUserMessage(
        message: String,
        sessionId: String = UUID.randomUUID().toString(),
        isDream: Boolean = false
    ): String? = inferenceMutex.withLock {
        if (!isReady) return "System is still initializing. Please wait."

        // Interrupt any ongoing speech when operator sends a new turn
        callbacks?.stopSpeaking()

        val currentTurn = turnCount.incrementAndGet()
        turnsSinceKvFlush++
        Timber.i("🧠 GhostAgent: Turn $currentTurn - Starting (turnsSinceKvFlush=$turnsSinceKvFlush, historySize=${_conversationHistory.size})...")

        // 1. Proactive Memory Compaction
        maybeCompactHistory()

        // 2. Perceive sensor grounding (~50 tokens)
        val perceptualBlock = if (isDream) "" else perceive(isAutonomous = message.startsWith("Δ 👾 ∇"))

        // 3. Drain media
        val (queuedImages, audio) = drainMedia()
        val images = queuedImages.map { it.bitmap }
        val turnImageUri = queuedImages.firstOrNull()?.uri

        // 4. Assemble turn input
        val operatorAvatar = getOperatorAvatar()
        val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault())
        val timeStr = java.time.LocalTime.now().format(timeFormatter)

        val isAutonomous = isDream || message.startsWith("Δ 👾 ∇")
        val historyContent = if (isAutonomous) {
            if (message.startsWith("Δ 👾 ∇ GHOST:")) message else "Δ 👾 ∇ GHOST: $message"
        } else {
            "Δ $operatorAvatar ∇ [$timeStr]: $message"
        }

        val userMessage = AgentMessage(
            role = if (isAutonomous) "system" else "user",
            content = historyContent,
            hadImage = images.isNotEmpty(),
            hadAudio = audio != null
        )
        if (!isDream) {
            _conversationHistory.add(userMessage)
        }

        val promptForModel = buildString {
            if (perceptualBlock.isNotBlank()) {
                append(perceptualBlock)
                append("\n\n")
            }
            append(historyContent)
        }

        callbacks?.showThinking()

        val responseBuffer = StringBuilder()
        val thoughtBuffer = StringBuilder()
        val ttsSentenceBuffer = StringBuilder()
        var spokenAnyStreamingTts = false
        var inThinkBlock = false
        var streamError: String? = null

        try {
            llmEngine.streamResponse(
                prompt = promptForModel,
                images = images,
                audioData = audio,
                onToken = { rawToken ->
                    var token = rawToken

                    // Thought extraction
                    if (token.contains("<think>") || token.contains("<|channel>thought")) {
                        inThinkBlock = true
                        thoughtBuffer.clear()
                        callbacks?.onThoughtUpdated("Reasoning...")
                        token = token.replace("<think>", "").replace("<|channel>thought", "")
                    }
                    if (inThinkBlock && (token.contains("</think>") || token.contains("<channel|>"))) {
                        inThinkBlock = false
                        val parts = token.split(Regex("</think>|<channel\\|>"), limit = 2)
                        thoughtBuffer.append(parts[0])
                        val finalThought = thoughtBuffer.toString().trim()
                        callbacks?.onThoughtComplete(finalThought)
                        token = if (parts.size > 1) parts[1] else ""
                    }

                    if (inThinkBlock) {
                        thoughtBuffer.append(token)
                        callbacks?.onThoughtUpdated(thoughtBuffer.toString())
                        return@streamResponse
                    }

                    // Clean protocol artifacts
                    val cleanToken = token
                        .replace("<|\"|>", "")
                        .replace(Regex("<\\|[a-z_]+\\|?>"), "")
                        .replace(Regex("<[a-z_]+\\|>"), "")

                    if (cleanToken.isEmpty()) return@streamResponse

                    // Extract emoji for emotion visualizer
                    val emojiMatch = Regex("([\\x{1F300}-\\x{1F9FF}|\\x{2600}-\\x{26FF}|\\x{2700}-\\x{27BF}])").find(cleanToken)
                    if (emojiMatch != null) {
                        callbacks?.onEmotionSignal(emojiMatch.groupValues[1])
                    }

                    if (responseBuffer.isEmpty()) {
                        callbacks?.cancelThinking()
                    }
                    responseBuffer.append(cleanToken)

                    if (!isDream) {
                        callbacks?.onMessageAdded(
                            wrapResponse(responseBuffer.toString()),
                            isUser = false,
                            isComplete = false
                        )

                        // Streaming sentence-by-sentence TTS for instant speech onset (~1.5s TTFT)
                        if (!isToolInvocation(responseBuffer)) {
                            ttsSentenceBuffer.append(cleanToken)
                            while (true) {
                                val currentText = ttsSentenceBuffer.toString()
                                val match = sentenceBoundaryRegex.find(currentText) ?: break
                                val punctuationEnd = match.range.first + match.groupValues[1].length
                                val sentence = currentText.substring(0, punctuationEnd).trim()
                                val remainder = currentText.substring(match.range.last + 1).trimStart()

                                val ttsChunk = cleanForTTS(sentence)
                                if (ttsChunk.isNotBlank() && ttsChunk.length >= 10) {
                                    callbacks?.speak(ttsChunk)
                                    spokenAnyStreamingTts = true
                                    ttsSentenceBuffer.clear()
                                    ttsSentenceBuffer.append(remainder)
                                } else {
                                    break
                                }
                            }
                        }
                    }
                },
                onComplete = {
                    callbacks?.cancelThinking()
                    if (!isDream && !isToolInvocation(responseBuffer)) {
                        val leftover = cleanForTTS(ttsSentenceBuffer.toString().trim())
                        if (leftover.isNotBlank()) {
                            callbacks?.speak(leftover)
                            spokenAnyStreamingTts = true
                            ttsSentenceBuffer.clear()
                        }
                    }
                },
                onError = { error ->
                    callbacks?.cancelThinking()
                    ttsSentenceBuffer.clear()
                    streamError = error
                    Timber.e("GhostAgent: LLM stream error: $error")
                }
            )

            if (streamError != null && responseBuffer.isEmpty()) {
                val errorMsg = "I stumbled while executing that: $streamError"
                callbacks?.showResponse(errorMsg)
                if (!isDream) {
                    _conversationHistory.add(AgentMessage(role = "assistant", content = errorMsg))
                    callbacks?.onMessageAdded(errorMsg, isUser = false, isComplete = true)
                }
                return errorMsg
            }

            var rawResponse = responseBuffer.toString().trim()

            // 5. Fallback un-executed tool call check (ANTLR recovery)
            if (rawResponse.contains("call:") || rawResponse.contains("execute_action") || rawResponse.contains("runMcpTool")) {
                val recovered = tryExecuteFallbackTool(rawResponse)
                if (recovered != null) {
                    rawResponse = recovered
                }
            }

            // Clean callsign prefix if model emitted it
            val assistantCallSign = getAssistantCallSign()
            val cleanResponse = rawResponse
                .replace(Regex("""^✧\s*.*?:?\s*"""), "")
                .replace(Regex("""^$assistantCallSign:\s*"""), "")
                .replace(Regex("""<\|channel>thought.*?<channel\|>""", RegexOption.DOT_MATCHES_ALL), "")
                .trim()

            val finalOutput = if (cleanResponse.isBlank()) {
                "I'm here. How can I help?"
            } else cleanResponse

            // 6. Check for webview side-effects
            val (webviewUrl, webviewRatio) = mcpTool.consumeLastWebview()

            // 7. Persist to history and callbacks
            if (!isDream) {
                _conversationHistory.add(AgentMessage(role = "assistant", content = finalOutput))
                callbacks?.onMessageAdded(
                    finalOutput,
                    isUser = false,
                    isComplete = true,
                    webviewUrl = webviewUrl,
                    webviewAspectRatio = webviewRatio
                )
                // Speak final output ONLY IF nothing was spoken during streaming
                // (e.g. tool execution recovery or short responses without terminal punctuation)
                if (!spokenAnyStreamingTts) {
                    callbacks?.speak(cleanForTTS(finalOutput))
                }
                callbacks?.storeConversationTurn(message, finalOutput, sessionId, turnImageUri)
            }

            checkpoint()
            Timber.i("✅ GhostAgent: Turn $currentTurn complete!")
            return finalOutput

        } catch (e: Exception) {
            callbacks?.cancelThinking()
            Timber.e(e, "GhostAgent: Inference turn failed")
            val errorMsg = "I stumbled while executing that: ${e.message}"
            callbacks?.showResponse(errorMsg)
            if (!isDream) {
                _conversationHistory.add(AgentMessage(role = "assistant", content = errorMsg))
                callbacks?.onMessageAdded(errorMsg, isUser = false, isComplete = true)
            }
            return errorMsg
        } finally {
            callbacks?.cancelThinking()
        }
    }

    /**
     * Streaming token generation for OpenAI SSE completions endpoint.
     */
    suspend fun streamUserMessageTokens(
        message: String,
        sessionId: String,
        onToken: (String) -> Unit
    ) = inferenceMutex.withLock {
        if (!isReady) {
            onToken("System is still initializing.")
            return
        }

        val perceptualBlock = perceive(isAutonomous = false)
        val prompt = if (perceptualBlock.isNotBlank()) "$perceptualBlock\n\n$message" else message

        val fullResponse = StringBuilder()
        llmEngine.streamResponse(
            prompt = prompt,
            images = emptyList(),
            audioData = null,
            onToken = { token ->
                if (!token.startsWith("<ctrl") && !token.contains("<think>") && !token.contains("<|channel>thought")) {
                    val clean = token
                        .replace("<|\"|>", "")
                        .replace(Regex("<\\|[a-z_]+\\|?>"), "")
                        .replace(Regex("<[a-z_]+\\|>"), "")
                    if (clean.isNotEmpty()) {
                        fullResponse.append(clean)
                        onToken(clean)
                    }
                }
            },
            onComplete = {
                val clean = fullResponse.toString().trim()
                synchronized(_conversationHistory) {
                    _conversationHistory.add(AgentMessage("user", message))
                    _conversationHistory.add(AgentMessage("assistant", clean))
                }
                checkpoint()
            },
            onError = { err ->
                onToken("\nError: $err")
            }
        )
    }

    fun submitConfirmationDecision(
        toolName: String,
        params: Map<String, Any?>,
        isApproved: Boolean,
        originalResponse: String,
        responseChannel: CompletableDeferred<String>
    ) {
        agentScope.launch {
            if (isApproved) {
                val paramJson = JSONObject(params.filterValues { it != null }).toString()
                val result = mcpTool.execute_action(toolName, paramJson)
                val output = result["output"] ?: "Action succeeded."
                responseChannel.complete("✓ $toolName executed: $output")
            } else {
                responseChannel.complete("Action $toolName was cancelled by user.")
            }
        }
    }

    fun flushAndCompactSession() {
        agentScope.launch {
            compactMemory(force = true)
        }
    }

    private suspend fun maybeCompactHistory() {
        val count = _conversationHistory.size
        val historyChars = synchronized(_conversationHistory) { _conversationHistory.sumOf { it.content.length } }
        val estTokens = historyChars / 3

        val maxTokens = llmEngine.maxNumTokens
        val tokenThreshold = if (maxTokens > 3000) 4200 else 1800
        val turnThreshold = if (maxTokens > 3000) 24 else 8
        val kvFlushTurnThreshold = turnThreshold / 2

        if (count >= turnThreshold || turnsSinceKvFlush >= kvFlushTurnThreshold || estTokens > tokenThreshold) {
            Timber.i("GhostAgent: Triggering compaction (count=$count/$turnThreshold, turnsSinceFlush=$turnsSinceKvFlush/$kvFlushTurnThreshold, estTokens=$estTokens/$tokenThreshold)")
            compactMemory(force = true)
        }
    }

    private suspend fun compactMemory(force: Boolean = false) {
        try {
            val toCompact = synchronized(_conversationHistory) {
                if (_conversationHistory.size > 2) {
                    val old = _conversationHistory.dropLast(2)
                    val keep = _conversationHistory.takeLast(2)
                    _conversationHistory.clear()
                    _conversationHistory.addAll(keep)
                    old
                } else {
                    emptyList()
                }
            }

            if (toCompact.isNotEmpty()) {
                val assistantCallSign = getAssistantCallSign()
                rollingMemory = SessionMemoryCompactor.compactOldMessages(
                    messagesToCompact = toCompact,
                    currentMemory = rollingMemory,
                    llmEngine = llmEngine,
                    assistantCallSign = assistantCallSign
                ).take(800).trim()
            }

            if (toCompact.isNotEmpty() || force) {
                turnsSinceKvFlush = 0
                val newPrompt = buildSystemPrompt()
                val recentMessages = getSanitizedRecentMessages(2)
                llmEngine.softReset(newPrompt, listOf(mcpTool), recentMessages)
                Timber.i("GhostAgent: KV cache flushed & compacted (${toCompact.size} msgs into memory). Active history: ${_conversationHistory.size}")
            }
        } catch (e: Exception) {
            Timber.e(e, "GhostAgent: Memory compaction failed")
        }
    }

    private suspend fun perceive(isAutonomous: Boolean = false): String {
        return try {
            val isFullBaseline = (turnsSinceKvFlush == 0) || isCriticalBattery()
            contextManager.buildContext(isFullBaseline = isFullBaseline, isAutonomous = isAutonomous)
        } catch (e: Exception) {
            Timber.w(e, "GhostAgent: Perception context build failed")
            ""
        }
    }

    private suspend fun buildSystemPrompt(): String {
        val basePrompt = contextManager.buildSystemPrompt(context, rollingMemory.ifBlank { null }, skillManager)
        val oldMemory = memoryManager.getCompactedSessionMemory().take(600).trim()
        val longTermMemoryPatch = if (oldMemory.isNotBlank()) "## Long-Term Memory\n$oldMemory\n\n" else ""
        return longTermMemoryPatch + basePrompt
    }

    private suspend fun tryExecuteFallbackTool(rawResponse: String): String? {
        val match = Regex("""(?:call:)?(turnOnFlashlight|turnOffFlashlight|execute_action|runMcpTool|search|execute_background_search|consult_peer|consultpeer|flashlight|app|media|alarm|timer|set_edge_lights|search_files|list_files)\s*\{([^}]*)\}""").find(rawResponse)
            ?: return null

        val actionName = match.groupValues[1]
        val rawBody = match.groupValues[2].trim()
        val argsBody = if (rawBody.isEmpty()) "{}" else "{$rawBody}"
        Timber.i("GhostAgent: Executing fallback tool: $actionName with $argsBody")

        val result = when (actionName) {
            "turnOnFlashlight" -> mcpTool.turnOnFlashlight()
            "turnOffFlashlight" -> mcpTool.turnOffFlashlight()
            "search", "execute_background_search" -> {
                val query = argsBody.removePrefix("{").removeSuffix("}").trim().removeSurrounding("\"")
                mcpTool.execute_action("search", "{\"query\":\"$query\"}")
            }
            "consult_peer", "consultpeer" -> {
                mcpTool.execute_action("consult_peer", argsBody)
            }
            "execute_action", "runMcpTool" -> {
                try {
                    val json = JSONObject(argsBody)
                    val targetTool = json.optString("toolName", "")
                    val targetParams = json.optString("parameters", json.optString("input", "{}"))
                    mcpTool.execute_action(targetTool, targetParams)
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "parse error"))
                }
            }
            else -> mcpTool.execute_action(actionName, argsBody)
        }

        val output = result["output"] ?: result["result"] ?: "Success"
        return "I completed that for you: $output"
    }

    fun checkpoint() {
        try {
            val historySnapshot = synchronized(_conversationHistory) { _conversationHistory.takeLast(30) }
            val state = AgentState(
                conversationHistory = historySnapshot,
                turnCount = turnCount.get(),
                rollingMemory = rollingMemory
            )
            checkpointFile.parentFile?.mkdirs()
            checkpointFile.writeText(gson.toJson(state))
            Timber.d("GhostAgent: Checkpoint saved (${historySnapshot.size} messages)")
        } catch (e: Exception) {
            Timber.w(e, "GhostAgent: Checkpoint failed")
        }
    }

    private fun restoreCheckpoint() {
        try {
            if (!checkpointFile.exists()) return
            val json = checkpointFile.readText()
            val state = gson.fromJson(json, AgentState::class.java)
            if (state != null) {
                synchronized(_conversationHistory) {
                    _conversationHistory.clear()
                    _conversationHistory.addAll(state.conversationHistory)
                }
                turnCount.set(state.turnCount)
                rollingMemory = state.rollingMemory
                Timber.i("GhostAgent: Restored checkpoint with ${_conversationHistory.size} messages (Turn: ${state.turnCount})")
            }
        } catch (e: Exception) {
            Timber.w(e, "GhostAgent: Restore checkpoint failed")
        }
    }

    private fun getBatteryLevel(): Int = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Exception) { 100 }

    private fun isCriticalBattery(): Boolean = getBatteryLevel() <= 15

    private fun getAssistantCallSign(): String = ContextManager.resolveDeviceCallSign(context)

    private fun getOperatorAvatar(): String = "Operator"

    private fun wrapResponse(raw: String): String {
        return raw.replace(Regex("""<\|channel>thought.*?<channel\|>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
    }

    private fun cleanForTTS(text: String): String {
        return text
            .replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<\|channel>thought.*?<channel\|>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""call:[a-z_]+\{.*?\}"""), "")
            .replace(Regex("""[#*`_~]"""), "")
            .replace(Regex("""https?://\S+"""), "link")
            .replace(Regex("""[^\p{L}\p{N}\p{P}\p{Z}]"""), "") // Remove emojis and special symbols
            .trim()
    }
}
