package com.ghost.api.agent

import android.content.Context
import android.graphics.Bitmap
import com.ghost.api.Constants
import com.ghost.api.GemmaEngine
import com.ghost.api.LlmBackend
import com.ghost.api.database.MemoryManager
import com.ghost.api.hardware.SensorFusionManager
import com.ghost.api.logic.ContextManager
import com.ghost.api.logic.IntentBagger
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
import java.util.Locale
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

    // Sentence or major clause boundary for low-latency streaming TTS dispatch
    private val sentenceBoundaryRegex = Regex("""(?<!\b(?:Mr|Mrs|Ms|Dr|e\.g|i\.e|vs|etc|approx|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\.)(?<!\d)([.!?]+['"”’)]*|[,:;\n—–-]+)(?:\s+|\n+)""")

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

    @Volatile private var lastExecutedToolName: String? = null
    @Volatile private var lastExecutedToolResult: String? = null
    @Volatile private var toolExecutedInCurrentTurn = false
    @Volatile private var toolSpokeFlavorTts = false
    @Volatile private var lastToolFlavorPhrase: String? = null

    init {
        // Wire tool lifecycle hooks
        mcpTool.onToolExecuting = { toolName, params ->
            val userTitle = Constants.getUserTitle(context)
            val hudText = when (toolName.lowercase()) {
                "app", "open_app" -> {
                    val appName = try { JSONObject(params).optString("name", "") } catch (e: Exception) { "" }
                    if (appName.isNotBlank()) com.ghost.api.logic.ActionFlavorTexts.appLaunchHud(appName, userTitle) else "Executing: $toolName..."
                }
                "flashlight", "toggle_torch" -> {
                    val isTorchOn = params.contains("ON", ignoreCase = true) || params.contains("true", ignoreCase = true)
                    com.ghost.api.logic.ActionFlavorTexts.torchHud(isTorchOn)
                }
                "alarm", "set_alarm" -> {
                    val h = try { JSONObject(params).optInt("hour", -1) } catch (e: Exception) { -1 }
                    val m = try { JSONObject(params).optInt("minutes", JSONObject(params).optInt("minute", 0)) } catch (e: Exception) { 0 }
                    if (h >= 0) com.ghost.api.logic.ActionFlavorTexts.alarmHud("$h:${m.toString().padStart(2, '0')}") else "Executing: $toolName..."
                }
                "volume" -> {
                    val isMute = params.contains("mute", ignoreCase = true)
                    val level = Regex("""(?:level|volume)=(\d+)""").find(params)?.groupValues?.get(1)
                    com.ghost.api.logic.ActionFlavorTexts.volumeHud(level, isMute)
                }
                "music", "media" -> {
                    val action = Regex("""(?:action=)?(play|pause|next|skip|prev|stop)""").find(params.lowercase())?.groupValues?.get(1) ?: "action"
                    com.ghost.api.logic.ActionFlavorTexts.musicHud(action)
                }
                "status" -> com.ghost.api.logic.ActionFlavorTexts.statusHud(userTitle)
                "execute_command" -> {
                    val cmdLower = params.lowercase()
                    when {
                        cmdLower.contains("volume") -> {
                            val isMute = params.contains("mute", ignoreCase = true)
                            val level = Regex("""(?:level|volume)=(\d+)""").find(params)?.groupValues?.get(1)
                            com.ghost.api.logic.ActionFlavorTexts.volumeHud(level, isMute)
                        }
                        cmdLower.contains("status") -> com.ghost.api.logic.ActionFlavorTexts.statusHud(userTitle)
                        cmdLower.contains("music") || cmdLower.contains("media") -> {
                            val action = Regex("""(?:action=)?(play|pause|next|skip|prev|stop)""").find(cmdLower)?.groupValues?.get(1) ?: "action"
                            com.ghost.api.logic.ActionFlavorTexts.musicHud(action)
                        }
                        else -> "Executing: $toolName..."
                    }
                }
                else -> "Executing: $toolName..."
            }
            callbacks?.onThoughtUpdated(hudText)
            callbacks?.updateNotification("Tool: $toolName")
        }
        mcpTool.onToolExecuted = { toolName, params, result ->
            callbacks?.onThoughtUpdated("Completed: $toolName")
            lastExecutedToolName = toolName
            lastExecutedToolResult = result
            toolExecutedInCurrentTurn = true

            val userTitle = Constants.getUserTitle(context)

            // Trigger wire-speed flavor TTS for screen exit or void hardware toggles
            when (toolName.lowercase()) {
                "app", "open_app" -> {
                    val appName = try { JSONObject(params).optString("name", "") } catch (e: Exception) { "" }
                    if (appName.isNotBlank()) {
                        val phrase = com.ghost.api.logic.ActionFlavorTexts.appLaunchTts(appName, userTitle)
                        lastToolFlavorPhrase = phrase
                        callbacks?.speak(phrase)
                        toolSpokeFlavorTts = true
                    }
                }
                "flashlight", "toggle_torch" -> {
                    val isTorchOn = params.contains("ON", ignoreCase = true) || params.contains("true", ignoreCase = true)
                    val phrase = com.ghost.api.logic.ActionFlavorTexts.torchTts(isTorchOn)
                    lastToolFlavorPhrase = phrase
                    callbacks?.speak(phrase)
                    toolSpokeFlavorTts = true
                }
                "volume" -> {
                    val isMute = params.contains("mute", ignoreCase = true)
                    val level = Regex("""(?:level|volume)=(\d+)""").find(params)?.groupValues?.get(1)
                    val phrase = com.ghost.api.logic.ActionFlavorTexts.volumeTts(level, isMute)
                    lastToolFlavorPhrase = phrase
                    callbacks?.speak(phrase)
                    toolSpokeFlavorTts = true
                }
                "music", "media" -> {
                    val action = Regex("""(?:action=)?(play|pause|next|skip|prev|stop)""").find(params.lowercase())?.groupValues?.get(1) ?: "play"
                    val phrase = com.ghost.api.logic.ActionFlavorTexts.musicTts(action)
                    lastToolFlavorPhrase = phrase
                    callbacks?.speak(phrase)
                    toolSpokeFlavorTts = true
                }
                "status" -> {
                    val phrase = com.ghost.api.logic.ActionFlavorTexts.statusTts(userTitle)
                    lastToolFlavorPhrase = phrase
                    callbacks?.speak(phrase)
                    toolSpokeFlavorTts = true
                }
                "alarm", "set_alarm" -> {
                    val h = try { JSONObject(params).optInt("hour", -1) } catch (e: Exception) { -1 }
                    val m = try { JSONObject(params).optInt("minutes", JSONObject(params).optInt("minute", 0)) } catch (e: Exception) { 0 }
                    val timeStr = if (h >= 0) "$h:${m.toString().padStart(2, '0')}" else "target time"
                    val phrase = com.ghost.api.logic.ActionFlavorTexts.alarmTts(timeStr)
                    lastToolFlavorPhrase = phrase
                    callbacks?.speak(phrase)
                    toolSpokeFlavorTts = true
                }
                "execute_command" -> {
                    val cmdLower = params.lowercase()
                    when {
                        cmdLower.contains("volume") -> {
                            val isMute = params.contains("mute", ignoreCase = true)
                            val level = Regex("""(?:level|volume)=(\d+)""").find(params)?.groupValues?.get(1)
                            val phrase = com.ghost.api.logic.ActionFlavorTexts.volumeTts(level, isMute)
                            lastToolFlavorPhrase = phrase
                            callbacks?.speak(phrase)
                            toolSpokeFlavorTts = true
                        }
                        cmdLower.contains("status") -> {
                            val phrase = com.ghost.api.logic.ActionFlavorTexts.statusTts(userTitle)
                            lastToolFlavorPhrase = phrase
                            callbacks?.speak(phrase)
                            toolSpokeFlavorTts = true
                        }
                        cmdLower.contains("music") || cmdLower.contains("media") -> {
                            val action = Regex("""(?:action=)?(play|pause|next|skip|prev|stop)""").find(cmdLower)?.groupValues?.get(1) ?: "play"
                            val phrase = com.ghost.api.logic.ActionFlavorTexts.musicTts(action)
                            lastToolFlavorPhrase = phrase
                            callbacks?.speak(phrase)
                            toolSpokeFlavorTts = true
                        }
                    }
                }
            }
        }
    }

    private fun getSanitizedRecentMessages(maxTurns: Int = 2): List<com.google.ai.edge.litertlm.Message> {
        return synchronized(_conversationHistory) {
            val list = mutableListOf<com.google.ai.edge.litertlm.Message>()
            val candidates = _conversationHistory.takeLast(maxTurns * 2)
            var expectedRole = "user"
            for (msg in candidates) {
                val rawContent = msg.content.trim()
                val clean = if (msg.role == "assistant") sanitizeAgentOutput(rawContent) else rawContent
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
            llmEngine.softReset(initialPrompt, listOf(mcpTool))
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
            llmEngine.softReset(prompt, listOf(mcpTool))
            Timber.i("GhostAgent: Soft reset complete")
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

        lastExecutedToolName = null
        lastExecutedToolResult = null
        toolExecutedInCurrentTurn = false
        toolSpokeFlavorTts = false

        val currentTurn = turnCount.incrementAndGet()
        turnsSinceKvFlush++
        Timber.i("🧠 GhostAgent: Turn $currentTurn - Starting (turnsSinceKvFlush=$turnsSinceKvFlush, historySize=${_conversationHistory.size})...")

        val turnStartTime = System.currentTimeMillis()

        // 1. Proactive Memory Compaction
        maybeCompactHistory()

        // 2. Perceive sensor grounding (~50 tokens)
        val perceptualBlock = if (isDream) "" else perceive(isAutonomous = message.startsWith("Δ 👾 ∇"))

        // 3. Drain media
        val (queuedImages, audio) = drainMedia()
        val images = queuedImages.map { it.bitmap }
        val turnImageUri = queuedImages.mapNotNull { it.uri }.joinToString("|").takeIf { it.isNotBlank() }

        // 4. Assemble turn input
        val operatorAvatar = getOperatorAvatar()
        val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault())
        val timeStr = java.time.LocalTime.now().format(timeFormatter)

        val isAutonomous = isDream || message.startsWith("Δ 👾 ∇")
        val imageTag = if (images.isNotEmpty()) {
            val countStr = if (images.size > 1) "${images.size} images" else "image"
            " [Attached: $countStr]"
        } else ""
        val historyContent = if (isAutonomous) {
            if (message.startsWith("Δ 👾 ∇ GHOST:")) message else "Δ 👾 ∇ GHOST: $message"
        } else {
            "Δ $operatorAvatar ∇ [$timeStr]$imageTag: ${if (message.isBlank()) "[shared image]" else message}"
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

        val baggedIntents = if (!isAutonomous && !isDream) {
            try {
                IntentBagger.bagIntents(context, message)
            } catch (e: Exception) {
                Timber.w(e, "IntentBagger failed to evaluate intents")
                emptyList()
            }
        } else emptyList()

        // FAST-PATH: Wire-speed direct dispatch for 100% confidence app launches and web links (<2ms)
        val wireSpeedIntent = baggedIntents.firstOrNull { it.isWireSpeedDirect }
        if (wireSpeedIntent != null) {
            val appTarget = wireSpeedIntent.appLabel
            val urlTarget = wireSpeedIntent.browserUrl
            if (!appTarget.isNullOrBlank()) {
                val userTitle = Constants.getUserTitle(context)
                val ttsPhrase = com.ghost.api.logic.ActionFlavorTexts.appLaunchTts(appTarget, userTitle)
                val hudChip = com.ghost.api.logic.ActionFlavorTexts.appLaunchHud(appTarget, userTitle)
                callbacks?.speak(ttsPhrase)
                callbacks?.onThoughtUpdated(hudChip)
                callbacks?.updateNotification("App: $appTarget")
                mcpTool.open_app(appTarget)

                val durationMs = System.currentTimeMillis() - turnStartTime
                val finalOutput = hudChip
                _conversationHistory.add(AgentMessage(role = "assistant", content = finalOutput))
                callbacks?.onMessageAdded(finalOutput, isUser = false, isComplete = true)
                callbacks?.storeConversationTurn(
                    userMessage = message,
                    response = finalOutput,
                    sessionId = sessionId,
                    imageUri = turnImageUri,
                    userTimestamp = turnStartTime,
                    durationMs = durationMs
                )
                checkpoint()
                Timber.i("⚡ GhostAgent: Wire-speed app launch '$appTarget' dispatched in ${durationMs}ms")
                return finalOutput
            } else if (!urlTarget.isNullOrBlank()) {
                val ttsPhrase = com.ghost.api.logic.ActionFlavorTexts.browserLaunchTts("Browser")
                val hudChip = com.ghost.api.logic.ActionFlavorTexts.browserLaunchHud("Browser")
                callbacks?.speak(ttsPhrase)
                callbacks?.onThoughtUpdated(hudChip)
                callbacks?.updateNotification("Browser: $urlTarget")
                mcpTool.execute_action("open_system_browser_bar", "{\"queryOrUrl\":\"$urlTarget\"}")

                val durationMs = System.currentTimeMillis() - turnStartTime
                val finalOutput = hudChip
                _conversationHistory.add(AgentMessage(role = "assistant", content = finalOutput))
                callbacks?.onMessageAdded(finalOutput, isUser = false, isComplete = true)
                callbacks?.storeConversationTurn(
                    userMessage = message,
                    response = finalOutput,
                    sessionId = sessionId,
                    imageUri = turnImageUri,
                    userTimestamp = turnStartTime,
                    durationMs = durationMs
                )
                checkpoint()
                Timber.i("⚡ GhostAgent: Wire-speed URL '$urlTarget' dispatched in ${durationMs}ms")
                return finalOutput
            }
        }

        val intentHints = if (baggedIntents.isNotEmpty()) {
            IntentBagger.formatPromptEnvelope(baggedIntents)
        } else ""

        val promptForModel = buildString {
            if (perceptualBlock.isNotBlank()) {
                append(perceptualBlock)
                append("\n\n")
            }
            if (intentHints.isNotBlank()) {
                append(intentHints)
                append("\n\n")
            }
            append(historyContent)
        }

        // Hard token safety guard: Estimate sequence tokens before passing to native engine
        val maxTokens = llmEngine.maxNumTokens
        val historyChars = synchronized(_conversationHistory) { _conversationHistory.sumOf { it.content.length } }
        val historyTokens = historyChars / 3
        val pastVisionTokens = synchronized(_conversationHistory) { _conversationHistory.count { it.hadImage } * 512 }
        val currentVisionTokens = images.size * 512
        val promptTokens = promptForModel.length / 3
        val estimatedTotal = 1200 + historyTokens + promptTokens + pastVisionTokens + currentVisionTokens // 1200 tokens baseline for system prompt + MCP tool schemas

        if (estimatedTotal > maxTokens - 400 && turnsSinceKvFlush > 0) {
            Timber.w("GhostAgent: Estimated tokens ($estimatedTotal) near ceiling ($maxTokens). Forcing KV cache flush & compaction.")
            compactMemory(force = true)
        }

        val promptTruncationLimit = ((maxTokens - 600) * 3).coerceIn(3500, 24000)
        val boundedPrompt = if (promptForModel.length > promptTruncationLimit) {
            Timber.w("GhostAgent: Truncating overly long prompt (${promptForModel.length} chars) to $promptTruncationLimit chars")
            promptForModel.take(promptTruncationLimit)
        } else {
            promptForModel
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
                prompt = boundedPrompt,
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
                            val trimmedBuf = responseBuffer.trimStart()
                            val inContextBlock = (trimmedBuf.startsWith("[Context", ignoreCase = true) ||
                                    trimmedBuf.startsWith("[Sensory", ignoreCase = true) ||
                                    trimmedBuf.startsWith("[Telemetry", ignoreCase = true) ||
                                    trimmedBuf.startsWith("[Perception", ignoreCase = true) ||
                                    trimmedBuf.startsWith("[Intent", ignoreCase = true)) &&
                                    !trimmedBuf.contains("[/", ignoreCase = true)

                            if (inContextBlock) {
                                ttsSentenceBuffer.clear()
                            } else {
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

            var rawResponse = responseBuffer.toString().trim()

            // 5. Fallback un-executed tool call check (ANTLR recovery)
            val hasToolCallText = rawResponse.contains("call:") ||
                rawResponse.contains("execute_command") ||
                rawResponse.contains("execute_action") ||
                rawResponse.contains("runMcpTool") ||
                rawResponse.contains("set_alarm(") || rawResponse.contains("set_alarm{") ||
                rawResponse.contains("alarm(") || rawResponse.contains("alarm{") ||
                rawResponse.contains("timer(") || rawResponse.contains("timer{") ||
                rawResponse.contains("open_app(") || rawResponse.contains("open_app{") ||
                rawResponse.contains("toggle_torch(") || rawResponse.contains("toggle_torch{") ||
                rawResponse.contains("media(") || rawResponse.contains("media{") ||
                rawResponse.contains("flashlight(") || rawResponse.contains("flashlight{")
            if (hasToolCallText) {
                val recovered = tryExecuteFallbackTool(rawResponse)
                if (recovered != null) {
                    rawResponse = recovered
                    toolExecutedInCurrentTurn = true
                    lastExecutedToolName = "fallback"
                    lastExecutedToolResult = recovered
                }
            }

            var cleanResponse = sanitizeAgentOutput(rawResponse)

            // 5B. Intent Bagger Safety Net:
            // If the model did NOT execute a tool via LiteRT-LM reflection or fallback,
            // but the incoming user message matched an actionable hardware/system intent
            // (or the model claimed in its text response that it completed it),
            // auto-execute the intent directly so the physical Android action never silently drops!
            if (!toolExecutedInCurrentTurn && !isAutonomous && !isDream && baggedIntents.isNotEmpty()) {
                val lowerMsg = message.lowercase(Locale.ROOT)
                val lowerResp = cleanResponse.lowercase(Locale.ROOT)

                // 1. Alarm safety net
                val alarmIntent = baggedIntents.firstOrNull { it.tool == "alarm" }
                if (alarmIntent != null) {
                    val userWantedAlarm = lowerMsg.contains("alarm") || lowerMsg.contains("wake")
                    val modelClaimsAlarm = lowerResp.contains("alarm") || lowerResp.contains("set") || lowerResp.contains("done")
                    if (userWantedAlarm || modelClaimsAlarm) {
                        Timber.i("GhostAgent: Safety net firing bagged alarm: ${alarmIntent.paramsJson}")
                        val res = mcpTool.execute_action("alarm", alarmIntent.paramsJson)
                        toolExecutedInCurrentTurn = true
                        lastExecutedToolName = "alarm"
                        lastExecutedToolResult = res["output"] ?: res["result"] ?: "Alarm scheduled"
                    }
                }

                // 2. Timer safety net
                if (!toolExecutedInCurrentTurn) {
                    val timerIntent = baggedIntents.firstOrNull { it.tool == "timer" }
                    if (timerIntent != null) {
                        val userWantedTimer = lowerMsg.contains("timer") || lowerMsg.contains("countdown")
                        val modelClaimsTimer = lowerResp.contains("timer") || lowerResp.contains("set") || lowerResp.contains("done")
                        if (userWantedTimer || modelClaimsTimer) {
                            Timber.i("GhostAgent: Safety net firing bagged timer: ${timerIntent.paramsJson}")
                            val res = mcpTool.execute_action("timer", timerIntent.paramsJson)
                            toolExecutedInCurrentTurn = true
                            lastExecutedToolName = "timer"
                            lastExecutedToolResult = res["output"] ?: res["result"] ?: "Timer scheduled"
                        }
                    }
                }

                // 3. App Launch safety net
                if (!toolExecutedInCurrentTurn) {
                    val appIntent = baggedIntents.firstOrNull { it.tool == "app" || it.tool == "open_app" }
                    if (appIntent != null) {
                        val userWantedApp = lowerMsg.startsWith("open ") || lowerMsg.startsWith("launch ") ||
                                            lowerMsg.contains("open the ") || lowerMsg.contains("launch the ") ||
                                            lowerMsg.endsWith(" dammit") || lowerMsg.endsWith(" please")
                        if (userWantedApp) {
                            Timber.i("GhostAgent: Safety net firing bagged app: ${appIntent.paramsJson}")
                            val res = mcpTool.execute_action("app", appIntent.paramsJson)
                            toolExecutedInCurrentTurn = true
                            lastExecutedToolName = "app"
                            lastExecutedToolResult = res["output"] ?: res["result"] ?: "App launched"
                        }
                    }
                }

                // 4. Media transport safety net
                if (!toolExecutedInCurrentTurn) {
                    val mediaIntent = baggedIntents.firstOrNull { it.tool == "media" || it.tool == "music" }
                    if (mediaIntent != null) {
                        val userWantedMedia = lowerMsg.contains("pause") || lowerMsg.contains("play") ||
                                              lowerMsg.contains("skip") || lowerMsg.contains("next") ||
                                              lowerMsg.contains("previous")
                        if (userWantedMedia) {
                            Timber.i("GhostAgent: Safety net firing bagged media: ${mediaIntent.paramsJson}")
                            val res = mcpTool.execute_command("music", mediaIntent.paramsJson)
                            toolExecutedInCurrentTurn = true
                            lastExecutedToolName = "music"
                            lastExecutedToolResult = res["output"] ?: res["result"] ?: "Media dispatched"
                        }
                    }
                }

                // 5. Flashlight safety net
                if (!toolExecutedInCurrentTurn) {
                    val flashIntent = baggedIntents.firstOrNull { it.tool == "flashlight" }
                    if (flashIntent != null) {
                        val userWantedFlash = lowerMsg.contains("flashlight") || lowerMsg.contains("torch")
                        if (userWantedFlash) {
                            Timber.i("GhostAgent: Safety net firing bagged flashlight: ${flashIntent.paramsJson}")
                            val res = mcpTool.execute_action("flashlight", flashIntent.paramsJson)
                            toolExecutedInCurrentTurn = true
                            lastExecutedToolName = "flashlight"
                            lastExecutedToolResult = res["output"] ?: res["result"] ?: "Flashlight toggled"
                        }
                    }
                }

                // 6. Volume safety net
                if (!toolExecutedInCurrentTurn) {
                    val volumeIntent = baggedIntents.firstOrNull { it.tool == "volume" }
                    if (volumeIntent != null) {
                        val userWantedVol = lowerMsg.contains("volume") || lowerMsg.contains("mute") || lowerMsg.contains("sound")
                        if (userWantedVol) {
                            Timber.i("GhostAgent: Safety net firing bagged volume: ${volumeIntent.paramsJson}")
                            val res = mcpTool.execute_command("volume", volumeIntent.paramsJson)
                            toolExecutedInCurrentTurn = true
                            lastExecutedToolName = "volume"
                            lastExecutedToolResult = res["output"] ?: res["result"] ?: "Volume adjusted"
                        }
                    }
                }

                // 7. Status safety net
                if (!toolExecutedInCurrentTurn) {
                    val statusIntent = baggedIntents.firstOrNull { it.tool == "status" }
                    if (statusIntent != null) {
                        val userWantedStatus = lowerMsg.contains("status") || lowerMsg.contains("battery")
                        if (userWantedStatus) {
                            Timber.i("GhostAgent: Safety net firing bagged status")
                            val res = mcpTool.execute_command("status", "")
                            toolExecutedInCurrentTurn = true
                            lastExecutedToolName = "status"
                            lastExecutedToolResult = res["output"] ?: res["result"] ?: "Status checked"
                        }
                    }
                }
            }

            // 5C. Tool Continuation Pass:
            // When LiteRT-LM executes a tool in sendMessageAsync, C++ appends tool results to context
            // but does not resume token streaming. If a tool was executed and the model emitted only a stub (<25 chars),
            // run a continuation pass or adopt the tool result!
            if (toolExecutedInCurrentTurn && (cleanResponse.length < 25 || cleanResponse == "I'")) {
                Timber.i("GhostAgent: Tool was executed ($lastExecutedToolName). Running continuation decode pass...")
                val continuationBuffer = StringBuilder()
                try {
                    llmEngine.streamResponse(
                        prompt = "Synthesize the findings from the tool result above concisely for the ${Constants.getUserTitle(context)}.",
                        images = emptyList(),
                        audioData = null,
                        onToken = { contToken ->
                            val cleanCont = contToken
                                .replace("<|\"|>", "")
                                .replace(Regex("<\\|[a-z_]+\\|?>"), "")
                                .replace(Regex("<[a-z_]+\\|>"), "")
                            if (cleanCont.isNotEmpty()) {
                                continuationBuffer.append(cleanCont)
                                callbacks?.onMessageAdded(
                                    wrapResponse(continuationBuffer.toString()),
                                    isUser = false,
                                    isComplete = false
                                )
                            }
                        },
                        onComplete = {
                            Timber.i("GhostAgent: Tool continuation decode completed (${continuationBuffer.length} chars)")
                        },
                        onError = { err ->
                            Timber.w("GhostAgent: Continuation decode error: $err")
                        }
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Continuation decode exception")
                }

                val contResult = continuationBuffer.toString().trim()
                if (contResult.isNotBlank() && contResult.length >= 10) {
                    rawResponse = contResult
                } else if (!lastExecutedToolResult.isNullOrBlank()) {
                    rawResponse = lastExecutedToolResult!!
                }

                cleanResponse = sanitizeAgentOutput(rawResponse)
            }

            val isStub = cleanResponse.length < 5 || cleanResponse == "I'" || cleanResponse.endsWith("]:")
            if (streamError != null || (isStub && !toolExecutedInCurrentTurn)) {
                Timber.w("GhostAgent: Stream error or stub detected (error=$streamError, stub=$isStub, response='$cleanResponse'). Attempting single-shot auto-recovery retry...")

                // 1. Force a clean conversation reset immediately
                try {
                    compactMemory(force = true)
                } catch (e: Exception) {
                    Timber.w(e, "Soft reset during recovery retry failed")
                }

                // 2. Retry the inference once on the fresh clean conversation
                val retryBuffer = StringBuilder()
                var retryError: String? = null
                try {
                    llmEngine.streamResponse(
                        prompt = boundedPrompt,
                        images = images,
                        audioData = audio,
                        onToken = { contToken ->
                            val cleanCont = contToken
                                .replace("<|\"|>", "")
                                .replace(Regex("<\\|[a-z_]+\\|?>"), "")
                                .replace(Regex("<[a-z_]+\\|>"), "")
                            if (cleanCont.isNotEmpty()) {
                                retryBuffer.append(cleanCont)
                                callbacks?.onMessageAdded(
                                    wrapResponse(retryBuffer.toString()),
                                    isUser = false,
                                    isComplete = false
                                )
                            }
                        },
                        onComplete = {
                            Timber.i("GhostAgent: Auto-recovery retry decode completed (${retryBuffer.length} chars)")
                        },
                        onError = { err ->
                            retryError = err
                            Timber.w("GhostAgent: Auto-recovery retry error: $err")
                        }
                    )
                } catch (e: Exception) {
                    retryError = e.message
                    Timber.w(e, "Auto-recovery retry exception")
                }

                val retryClean = sanitizeAgentOutput(retryBuffer.toString())
                val retryStub = retryClean.length < 5 || retryClean == "I'" || retryClean.endsWith("]:")

                if (retryError == null && !retryStub) {
                    Timber.i("✅ GhostAgent: Auto-recovery retry SUCCEEDED! User prompt preserved.")
                    cleanResponse = retryClean
                } else if (toolExecutedInCurrentTurn && !lastExecutedToolResult.isNullOrBlank()) {
                    cleanResponse = lastExecutedToolResult!!
                } else {
                    val errorMsg = if (retryError != null || streamError != null) {
                        "Processing interrupted. Substrate reset completed."
                    } else {
                        "Processing cycle completed. What would you like to check?"
                    }
                    callbacks?.showResponse(errorMsg)
                    if (!isDream) {
                        _conversationHistory.add(AgentMessage(role = "assistant", content = errorMsg))
                        callbacks?.onMessageAdded(errorMsg, isUser = false, isComplete = true)
                    }
                    return errorMsg
                }
            }

            val finalOutput = if (cleanResponse.isBlank()) {
                if (toolSpokeFlavorTts && !lastToolFlavorPhrase.isNullOrBlank()) {
                    lastToolFlavorPhrase!!
                } else {
                    "Processing complete."
                }
            } else {
                if (toolSpokeFlavorTts && !lastToolFlavorPhrase.isNullOrBlank() && !cleanResponse.contains(lastToolFlavorPhrase!!, ignoreCase = true)) {
                    "${lastToolFlavorPhrase!!}\n$cleanResponse"
                } else {
                    cleanResponse
                }
            }

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
                // Speak final output ONLY IF nothing was spoken during streaming AND tool didn't already speak flavor TTS
                // (e.g. tool execution recovery or short responses without terminal punctuation)
                if (!spokenAnyStreamingTts && !toolSpokeFlavorTts) {
                    callbacks?.speak(cleanForTTS(finalOutput))
                }
                val durationMs = System.currentTimeMillis() - turnStartTime
                callbacks?.storeConversationTurn(
                    userMessage = message,
                    response = finalOutput,
                    sessionId = sessionId,
                    imageUri = turnImageUri,
                    userTimestamp = turnStartTime,
                    durationMs = durationMs
                )
            }

            checkpoint()
            Timber.i("✅ GhostAgent: Turn $currentTurn complete!")
            return finalOutput

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                Timber.i("GhostAgent: Inference turn cancelled")
                throw e
            }
            callbacks?.cancelThinking()
            Timber.e(e, "GhostAgent: Inference turn failed")
            val errorMsg = "I stumbled while executing that: ${e.message}"
            callbacks?.showResponse(errorMsg)
            if (!isDream) {
                _conversationHistory.add(AgentMessage(role = "assistant", content = errorMsg))
                callbacks?.onMessageAdded(errorMsg, isUser = false, isComplete = true)
            }
            if (e.message?.contains("token", ignoreCase = true) == true || e.message?.contains("Status Code: 3", ignoreCase = true) == true) {
                agentScope.launch {
                    try { softReset() } catch (_: Exception) {}
                }
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
                val clean = sanitizeAgentOutput(fullResponse.toString())
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
        val visionTokens = synchronized(_conversationHistory) { _conversationHistory.count { it.hadImage } * 512 }
        val estTokens = (historyChars / 3) + visionTokens

        val maxTokens = llmEngine.maxNumTokens
        val tokenThreshold = if (maxTokens > 6000) 4200 else if (maxTokens > 3000) 2400 else 1200
        val turnThreshold = if (maxTokens > 6000) 24 else if (maxTokens > 3000) 16 else 8

        if (count >= turnThreshold || turnsSinceKvFlush >= turnThreshold || estTokens > tokenThreshold) {
            Timber.i("GhostAgent: Triggering compaction (count=$count/$turnThreshold, turnsSinceFlush=$turnsSinceKvFlush/$turnThreshold, estTokens=$estTokens/$tokenThreshold)")
            compactMemory(force = true)
        }
    }

    private suspend fun compactMemory(force: Boolean = false) {
        try {
            val toCompact = synchronized(_conversationHistory) {
                if (_conversationHistory.size > 8) {
                    val old = _conversationHistory.dropLast(8)
                    val keep = _conversationHistory.takeLast(8)
                    _conversationHistory.clear()
                    _conversationHistory.addAll(keep)
                    old
                } else if (force && _conversationHistory.size > 4) {
                    val old = _conversationHistory.dropLast(4)
                    val keep = _conversationHistory.takeLast(4)
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
                llmEngine.softReset(newPrompt, listOf(mcpTool))
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
        val recentDialogue = synchronized(_conversationHistory) {
            val completedMessages = _conversationHistory.filterIndexed { index, msg ->
                !(index == _conversationHistory.lastIndex && msg.role == "user")
            }
            if (completedMessages.isEmpty()) ""
            else {
                val assistantCallSign = getAssistantCallSign()
                val lines = completedMessages.takeLast(6).mapNotNull { msg ->
                    val clean = if (msg.role == "assistant") sanitizeAgentOutput(msg.content) else msg.content.trim()
                    if (clean.isBlank()) return@mapNotNull null
                    val roleLabel = if (msg.role == "user") "Operator" else assistantCallSign
                    val contentWithoutPrefix = clean
                        .removePrefix("Δ 👾 ∇ GHOST:")
                        .replace(Regex("""^Δ\s*.*?\s*∇(\s*\[.*?\])?:\s*"""), "")
                        .replace(Regex("""^✧\s*.*?:"""), "")
                        .trim()
                    val singleLine = contentWithoutPrefix.replace(Regex("""\s+"""), " ")
                    val snippet = if (singleLine.length > 200) singleLine.take(197) + "..." else singleLine
                    "• $roleLabel: $snippet"
                }
                if (lines.isNotEmpty()) {
                    "\n\n## Recent Conversation Context\n" + lines.joinToString("\n")
                } else ""
            }
        }

        val basePrompt = contextManager.buildSystemPrompt(context, rollingMemory.ifBlank { null }, skillManager)
        val oldMemory = memoryManager.getCompactedSessionMemory().take(600).trim()
        val longTermMemoryPatch = if (oldMemory.isNotBlank()) "## Long-Term Memory\n$oldMemory\n\n" else ""
        return longTermMemoryPatch + basePrompt + recentDialogue
    }

    private suspend fun tryExecuteFallbackTool(rawResponse: String): String? {
        val pattern = """(?:call:)?(turnOnFlashlight|turnOffFlashlight|toggle_torch|toggleTorch|set_alarm|setAlarm|execute_command|executeCommand|execute_action|runMcpTool|search|execute_background_search|consult_peer|consultpeer|flashlight|open_app|openApp|launch_app|app|media|alarm|timer|set_edge_lights|search_files|list_files)\s*(?:\{([^}]*)\}|\(([^)]*)\))"""
        val match = Regex(pattern).find(rawResponse) ?: return null

        val actionName = match.groupValues[1]
        val rawBody = (match.groupValues[2].ifEmpty { match.groupValues[3] }).trim()
        val argsBody = if (rawBody.isEmpty()) "{}" else if (rawBody.startsWith("{") && rawBody.endsWith("}")) rawBody else "{$rawBody}"
        Timber.i("GhostAgent: Executing fallback tool: $actionName with rawBody: '$rawBody'")

        val result = when (actionName) {
            "turnOnFlashlight" -> mcpTool.turnOnFlashlight()
            "turnOffFlashlight" -> mcpTool.turnOffFlashlight()
            "toggle_torch", "toggleTorch" -> {
                val enabled = rawBody.contains("true", ignoreCase = true) || rawBody.contains("on", ignoreCase = true)
                mcpTool.toggle_torch(enabled)
            }
            "open_app", "openApp", "launch_app", "app" -> {
                val appName = rawBody.removePrefix("{").removeSuffix("}")
                    .replace(Regex("""^"name"\s*[:=]\s*"""), "")
                    .replace(Regex("""^name\s*[:=]\s*"""), "")
                    .trim().removeSurrounding("\"")
                mcpTool.open_app(appName)
            }
            "media" -> {
                val action = rawBody.removePrefix("{").removeSuffix("}")
                    .replace(Regex("""^"action"\s*[:=]\s*"""), "")
                    .replace(Regex("""^action\s*[:=]\s*"""), "")
                    .trim().removeSurrounding("\"")
                mcpTool.media(action)
            }
            "search", "execute_background_search" -> {
                val query = rawBody.removePrefix("{").removeSuffix("}")
                    .replace(Regex("""^"query"\s*[:=]\s*"""), "")
                    .replace(Regex("""^query\s*[:=]\s*"""), "")
                    .trim().removeSurrounding("\"")
                mcpTool.execute_action("search", "{\"query\":\"$query\"}")
            }
            "consult_peer", "consultpeer" -> {
                mcpTool.execute_action("consult_peer", argsBody)
            }
            "set_alarm", "setAlarm" -> {
                val hourMatch = Regex("""(?i)\bhour\s*[:=]\s*["']?(\d+)["']?""").find(rawBody)
                val minMatch = Regex("""(?i)\bmin(?:ute)?s?\s*[:=]\s*["']?(\d+)["']?""").find(rawBody)
                if (hourMatch != null) {
                    val h = hourMatch.groupValues[1].toInt()
                    val m = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    mcpTool.set_alarm(h, m)
                } else {
                    val parsed = com.ghost.api.logic.TimePreprocessor.parseTime(rawBody)
                    if (parsed != null) {
                        mcpTool.set_alarm(parsed.hour24, parsed.minute)
                    } else {
                        mcpTool.execute_command("alarm", argsBody)
                    }
                }
            }
            "alarm" -> {
                val hourMatch = Regex("""(?i)\bhour\s*[:=]\s*["']?(\d+)["']?""").find(rawBody)
                val minMatch = Regex("""(?i)\bmin(?:ute)?s?\s*[:=]\s*["']?(\d+)["']?""").find(rawBody)
                val labelMatch = Regex("""(?i)\blabel\s*[:=]\s*["']([^"']+)["']""").find(rawBody)

                if (hourMatch != null) {
                    val h = hourMatch.groupValues[1].toInt()
                    val m = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val l = labelMatch?.groupValues?.get(1)?.trim() ?: ""
                    mcpTool.alarm(h, m, l)
                } else {
                    val positionalMatch = Regex("""^\s*(\d{1,2})\s*(?:,\s*(\d{1,2}))?\s*(?:,\s*["']?([^"']*)["']?)?\s*$""").find(rawBody)
                    if (positionalMatch != null) {
                        val h = positionalMatch.groupValues[1].toInt()
                        val m = positionalMatch.groupValues[2].toIntOrNull() ?: 0
                        val l = positionalMatch.groupValues[3].trim()
                        mcpTool.alarm(h, m, l)
                    } else {
                        val parsed = com.ghost.api.logic.TimePreprocessor.parseTime(rawBody)
                        if (parsed != null) {
                            mcpTool.alarm(parsed.hour24, parsed.minute, parsed.label)
                        } else {
                            mcpTool.execute_action("alarm", argsBody)
                        }
                    }
                }
            }
            "timer" -> {
                val secMatch = Regex("""(?i)\b(?:second|sec|duration)s?\s*[:=]\s*["']?(\d+)["']?""").find(rawBody)
                val labelMatch = Regex("""(?i)\blabel\s*[:=]\s*["']([^"']+)["']""").find(rawBody)
                if (secMatch != null) {
                    val s = secMatch.groupValues[1].toInt()
                    val l = labelMatch?.groupValues?.get(1)?.trim() ?: ""
                    mcpTool.timer(s, l)
                } else {
                    val positionalMatch = Regex("""^\s*(\d+)\s*(?:,\s*["']?([^"']*)["']?)?\s*$""").find(rawBody)
                    if (positionalMatch != null) {
                        val s = positionalMatch.groupValues[1].toInt()
                        val l = positionalMatch.groupValues[2].trim()
                        mcpTool.timer(s, l)
                    } else {
                        mcpTool.execute_action("timer", argsBody)
                    }
                }
            }
            "execute_command", "executeCommand" -> {
                val cmd = rawBody.substringBefore(",").substringBefore(" ").removePrefix("\"").removeSuffix("\"").trim()
                val args = rawBody.substringAfter(",", "").ifBlank { rawBody.substringAfter(" ", "") }.trim()
                mcpTool.execute_command(cmd, args)
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
        val userTitle = Constants.getUserTitle(context)
        return when (actionName) {
            "open_app", "openApp", "launch_app", "app" -> {
                val appName = rawBody.removePrefix("{").removeSuffix("}")
                    .replace(Regex("""^"name"\s*[:=]\s*"""), "")
                    .replace(Regex("""^name\s*[:=]\s*"""), "")
                    .trim().removeSurrounding("\"")
                com.ghost.api.logic.ActionFlavorTexts.appLaunchTts(if (appName.isNotBlank()) appName else "App", userTitle)
            }
            "toggle_torch", "toggleTorch", "turnOnFlashlight", "turnOffFlashlight", "flashlight" -> {
                val enabled = rawBody.contains("true", ignoreCase = true) || rawBody.contains("on", ignoreCase = true) || actionName == "turnOnFlashlight"
                com.ghost.api.logic.ActionFlavorTexts.torchTts(enabled)
            }
            "volume" -> {
                val isMute = rawBody.contains("mute", ignoreCase = true)
                val level = Regex("""(?:level|volume)=(\d+)""").find(rawBody)?.groupValues?.get(1)
                com.ghost.api.logic.ActionFlavorTexts.volumeTts(level, isMute)
            }
            "status" -> com.ghost.api.logic.ActionFlavorTexts.statusTts(userTitle)
            "music", "media" -> {
                val action = Regex("""(?:action=)?(play|pause|next|skip|prev|stop)""").find(rawBody.lowercase())?.groupValues?.get(1) ?: "play"
                com.ghost.api.logic.ActionFlavorTexts.musicTts(action)
            }
            "set_alarm", "alarm" -> {
                com.ghost.api.logic.ActionFlavorTexts.alarmTts("target time")
            }
            "execute_command", "executeCommand" -> {
                val cmdLower = rawBody.lowercase()
                when {
                    cmdLower.contains("volume") -> {
                        val isMute = cmdLower.contains("mute")
                        val level = Regex("""(?:level|volume)=(\d+)""").find(cmdLower)?.groupValues?.get(1)
                        com.ghost.api.logic.ActionFlavorTexts.volumeTts(level, isMute)
                    }
                    cmdLower.contains("status") -> com.ghost.api.logic.ActionFlavorTexts.statusTts(userTitle)
                    cmdLower.contains("music") || cmdLower.contains("media") -> {
                        val action = Regex("""(?:action=)?(play|pause|next|skip|prev|stop)""").find(cmdLower)?.groupValues?.get(1) ?: "play"
                        com.ghost.api.logic.ActionFlavorTexts.musicTts(action)
                    }
                    else -> "I completed that for you: $output"
                }
            }
            else -> "I completed that for you: $output"
        }
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
                val sanitizedHistory = state.conversationHistory.mapNotNull { msg ->
                    val clean = if (msg.role == "assistant") sanitizeAgentOutput(msg.content) else msg.content.trim()
                    if (clean.isBlank()) null else msg.copy(content = clean)
                }
                synchronized(_conversationHistory) {
                    _conversationHistory.clear()
                    _conversationHistory.addAll(sanitizedHistory)
                }
                turnCount.set(state.turnCount)
                rollingMemory = sanitizeAgentOutput(state.rollingMemory)
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

    fun sanitizeAgentOutput(raw: String): String {
        var text = raw
        // 1. Strip thought channels (completed or trailing)
        text = text
            .replace(Regex("""<\|channel>thought.*?<channel\|>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<\|channel>thought.*""", RegexOption.DOT_MATCHES_ALL), "")

        // 2. Strip any sensory telemetry preamble ending in closing tags [/Context], [/Sensory Grounding], [/Telemetry], [/Perception], [/Intent Hints]
        text = text.replace(Regex("""^[\s\S]*?\[/(?:Context|Live Sensory Grounding|Sensory Grounding|Telemetry|Perception|Intent Hints)\]\s*""", RegexOption.IGNORE_CASE), "")

        // 3. Strip any full [Context: ...] ... [/Context] blocks anywhere in text
        text = text.replace(Regex("""\[(?:Context|Live Sensory Grounding|Sensory Grounding|Telemetry|Perception|Intent Hints):?[\s\S]*?\[/(?:Context|Live Sensory Grounding|Sensory Grounding|Telemetry|Perception|Intent Hints)\]\s*""", RegexOption.IGNORE_CASE), "")

        // 4. Strip any standalone opening or closing context/telemetry/perception tags
        text = text.replace(Regex("""\[/?(?:Context|Live Sensory Grounding|Sensory Grounding|Telemetry|Perception|Intent Hints|Internal hardware perception|Internal perception)(?::[^\]]*)?\]\s*""", RegexOption.IGNORE_CASE), "")

        // 5. Clean callsign prefix and echoed prompt header if model emitted it
        val assistantCallSign = getAssistantCallSign()
        text = text
            .replace(Regex("""^(?:\[.*?\]|[\w\s:·-]*?\]:?)\s*"""), "") // Strips echoed timestamp/prefix e.g. "pm]:", "[9:31 pm]:", "]:"
            .replace(Regex("""^Δ\s*.*?\s*∇\s*"""), "")
            .replace(Regex("""^✧\s*.*?:?\s*"""), "")
            .replace(Regex("""^${Regex.escape(assistantCallSign)}:\s*"""), "")

        // 6. Clean stuttering time tokens (e.g. "8:0000 PM" -> "8:00 PM", "2:000" -> "2:00")
        text = com.ghost.api.logic.TimePreprocessor.sanitizeTimeTokens(text)

        return text.trim()
    }

    private fun wrapResponse(raw: String): String {
        val trimmed = raw.trimStart()
        if (trimmed.startsWith("[Context", ignoreCase = true) ||
            trimmed.startsWith("[Sensory", ignoreCase = true) ||
            trimmed.startsWith("[Telemetry", ignoreCase = true) ||
            trimmed.startsWith("[Perception", ignoreCase = true) ||
            trimmed.startsWith("[Intent Hints", ignoreCase = true)) {
            val hasClosing = trimmed.contains("[/Context]", ignoreCase = true) ||
                             trimmed.contains("[/Sensory", ignoreCase = true) ||
                             trimmed.contains("[/Telemetry", ignoreCase = true) ||
                             trimmed.contains("[/Perception", ignoreCase = true) ||
                             trimmed.contains("[/Intent Hints", ignoreCase = true)
            if (!hasClosing) {
                return ""
            }
        }
        return sanitizeAgentOutput(raw)
    }

    private fun cleanForTTS(text: String): String {
        return sanitizeAgentOutput(text)
            .replace(Regex("""call:[a-z_]+\{.*?\}"""), "")
            .replace(Regex("""[#*`_~]"""), "")
            .replace(Regex("""https?://\S+"""), "link")
            .replace(Regex("""[^\p{L}\p{N}\p{P}\p{Z}]"""), "") // Remove emojis and special symbols
            .trim()
    }

    fun appendAssistantContext(text: String) {
        val clean = sanitizeAgentOutput(text)
        if (clean.isNotBlank()) {
            synchronized(_conversationHistory) {
                _conversationHistory.add(AgentMessage(role = "assistant", content = clean))
            }
            checkpoint()
        }
    }
}
