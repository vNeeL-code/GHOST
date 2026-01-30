package com.gemma.api.agent

import android.content.Context
import com.gemma.api.GemmaEngine
import com.gemma.api.mcp.MCPServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
// import kotlinx.coroutines.GlobalScope // Removed for Kimi K2 Fix
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
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
    private val checkpointDir: File
) {
    
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
    
    private val conversationHistory = mutableListOf<Message>()
    private var moodState: String = "IDLE"
    private var turnCount: Int = 0
    
    // Metabolic State (Live)
    private var hunger: Int = 50       // Start satiated
    private var energy: Int = 80       // Start charged
    private var happiness: Int = 60    // Start okay
    private var isMusicPlaying: Boolean = false
    private var lastMusicTrack: String = ""  // Track previous music for state change detection
    
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
        Timber.i("KoogAgent: Ready (Mood:$moodState, 🍗$hunger ⚡$energy 💖$happiness)")
    }
    
    fun checkpoint() {
        try {
            val state = AgentState(
                conversationHistory = conversationHistory.takeLast(50),
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
            
            Timber.d("KoogAgent: Checkpoint saved (${conversationHistory.size} messages, checksum=$checksum)")
        } catch (e: Exception) {
            Timber.e(e, "KoogAgent: Checkpoint failed")
        }
    }
    
    fun clearHistory() {
        synchronized(conversationHistory) {
            conversationHistory.clear()
            turnCount = 0
            // BUGFIX: Clear music state to prevent "Doom Party" leak
            isMusicPlaying = false
            lastMusicTrack = ""
            Timber.i("KoogAgent: History cleared (including music state)")
        }
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
                    // Do not overwrite immediately to allow inspection
                    return
                }
                rawJson
            } else {
                lines.joinToString("\n")
            }
            
            val state = gson.fromJson(jsonContent, AgentState::class.java)
            synchronized(conversationHistory) {
                conversationHistory.clear()
                conversationHistory.addAll(state.conversationHistory)
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

            Timber.i("KoogAgent: Restored checkpoint (${conversationHistory.size} messages, M:$moodState H:$hunger E:$energy Ha:$happiness)")
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
    // CORE AGENT LOOP: PERCEIVE → THINK → ACT
    // ═══════════════════════════════════════════════════════════════
    
    suspend fun processUserMessage(
        message: String,
        sessionId: String,
        images: List<android.graphics.Bitmap>? = null,
        audio: ShortArray? = null
    ): String = withContext(Dispatchers.Default) {
        
        turnCount++
        Timber.i("🧠 KoogAgent: Turn $turnCount - Starting...")
        
        // 1. PERCEIVE: Gather context
        Timber.i("👁️ Perceiving device state...")
        val context = perceive()
        Timber.d("Context gathered: ${context.length} chars")
        
        // 2. Add user message to history
        val currentDate = java.time.LocalDate.now().toString()
        val userMessageContent = "[Date: $currentDate] $message"
        
        val userMessage = Message(
            role = "user",
            content = userMessageContent,
            hadImage = !images.isNullOrEmpty(),
            hadAudio = audio != null
        )
        conversationHistory.add(userMessage)
        
        // 3. THINK: Use LLM to reason about the message and decide on actions
        Timber.i("🤔 Thinking... (this may take ~30s)")
        val response = think(context, message, images, audio)
        Timber.i("💭 Thought complete: ${response.take(50)}...")
        
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
        conversationHistory.add(assistantMessage)
        
        // 6. Compress history if needed
        if (conversationHistory.size > 40) {
            compressHistory()
        }
        
        // 7. Checkpoint (Structured Concurrency - Kimi K2 Fix)
        Timber.d("💾 Checkpointing state...")
        try {
            // Await checkpoint to ensure data safety before return
            // Using withContext to bridge blocking I/O to suspend
            withContext(Dispatchers.IO) {
                checkpoint()
            }
        } catch (e: Exception) {
            Timber.e(e, "Checkpoint failed")
        }
        
        // 8. If tools were executed, do a follow-up turn
        val finalContent = if (toolResults.isNotEmpty()) {
            val observation = "Tool results:\n${toolResults.joinToString("\n")}"
            Timber.d("KoogAgent: Tool execution complete, reflecting...")
            
            // Recursive call for reflection
            val reflection = think(context, "Observation: $observation\n\nWhat should I tell the user?", null, null)
            "$cleanResponse\n\n$reflection"
        } else {
            cleanResponse
        }
        
        // 9. INJECT HEADERS & FOOTERS (The "Body" speaks for the "Mind")
        val timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
            
        // Select mood emoji (allow full range, default to sentient spark)
        val moodEmoji = if (moodState.isNotBlank() && moodState != "IDLE") moodState else "✦"
            
        
        val wrappedResponse = """✦ Gemma Δ $moodEmoji ∇
$finalContent
Δ ℹ️ $timestamp ♾️ ∇""".trimIndent()


        Timber.i("✅ KoogAgent: Turn $turnCount complete!")
        
        // 10. CRITICAL: Flush KV Cache to prevent NPU corruption
        // NPU drivers are unstable - cache gets corrupted after turn 1
        // This sacrifices speed for reliability
        try {
            Timber.d("🔄 Flushing KV cache (NPU corruption prevention)...")
            llmEngine.softReset(buildSystemPrompt())
            Timber.i("✓ KV cache flushed")
        } catch (e: Exception) {
            Timber.e(e, "KV reset failed - next turn may crash")
        }
        
        wrappedResponse
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

        // Read current device context (SILENTLY - don't show raw data to model)
        var dateTime = ""
        var timeOfDay = ""
        var musicTrack = ""

        // Capture previous state BEFORE we read new data (for state change detection)
        val wasPlaying = isMusicPlaying
        val previousTrack = lastMusicTrack

        try {
            val currentContext = mcpServer.readResource("context://current")
            val content = currentContext.content
            
            // Extract date/time for user-facing summary
            val dateMatch = Regex("📅 Date: ([\\d-]+)").find(content)
            val timeMatch = Regex("⏰ Time: ([^(]+)").find(content)
            dateTime = dateMatch?.groupValues?.get(1) ?: ""
            timeOfDay = timeMatch?.groupValues?.get(1)?.trim() ?: ""
            
            // Extract music (if playing)
            val musicMatch = Regex("🎵 Playing: \"([^\"]+)\" by ([^\\n]+)").find(content)
            if (musicMatch != null) {
                val title = musicMatch.groupValues[1]
                val artist = musicMatch.groupValues[2]
                musicTrack = "$title by $artist"
            }
            
            // --- SYNC METABOLISM WITH REALITY ---
            
            // 1. Energy = Battery Level
            // Format: "🔋 85%" or "🪫 5%"
            val batteryMatch = Regex("[🔋🪫] (\\d+)%").find(content)
            if (batteryMatch != null) {
                val level = batteryMatch.groupValues[1].toIntOrNull() ?: 50
                energy = level // DIRECT SYNC
            }

            // 2. Hunger = Device Thermal (Body heat from battery)
            // Format: "🌡️32.5°C" (body temp after voltage)
            val bodyTempMatch = Regex("🌡️([\\d.]+)°C").find(content)
            // Also try CPU temp: "🖥️45°C"
            val cpuTempMatch = Regex("🖥️([\\d.]+)°C").find(content)

            val tempC = bodyTempMatch?.groupValues?.get(1)?.toFloatOrNull()
                ?: cpuTempMatch?.groupValues?.get(1)?.toFloatOrNull()
                ?: 35f
            // Map: 25°C -> 0% (Cool), 40°C -> 50%, 55°C -> 100% (Hot)
            hunger = ((tempC - 25) * 3.33).toInt().coerceIn(0, 100)
            
            // 3. Happiness = Music/Interaction (state change vars captured above try block)
            if (musicMatch != null) {
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

        // INJECT CLEAN CONTEXT (what user would see/say)
        sb.append("--- Current Situation ---\n")
        if (dateTime.isNotBlank()) sb.append("Date: $dateTime\n")
        if (timeOfDay.isNotBlank()) sb.append("Time: $timeOfDay\n")

        // EXPLICIT STATE CHANGE NOTIFICATIONS (fixes stale music bug)
        when {
            isMusicPlaying && wasPlaying && lastMusicTrack != previousTrack -> {
                // Song changed while music was playing
                sb.append("🎵 Song changed: Now playing $musicTrack (was: $previousTrack)\n")
            }
            isMusicPlaying && !wasPlaying -> {
                // Music just started
                sb.append("🎵 Music started: $musicTrack\n")
            }
            !isMusicPlaying && wasPlaying -> {
                // Music just stopped
                sb.append("🎵 Music stopped (was playing: $previousTrack)\n")
            }
            isMusicPlaying -> {
                // Still playing same track
                sb.append("🎵 Playing: $musicTrack\n")
            }
            // else: no music, wasn't playing before - say nothing
        }
        sb.append("---\n")
        
        // INJECT VITALS (internal state, invisible to user)
        sb.append("🩺 My Vitals: Energy:$energy% | Heat:$hunger% | Mood:$happiness%\n")
        sb.append(generateSubconsciousThought())
        sb.append("\n")
        
        return sb.toString()
    }
    
    // ═══════════════════════════════════════════════════════════════
    // THINK: Use LLM to reason
    // ═══════════════════════════════════════════════════════════════
    
    private suspend fun think(
        context: String,
        userMessage: String,
        images: List<android.graphics.Bitmap>?,
        audio: ShortArray?
    ): String {
        
        // Build prompt with Gemma chat template
        val systemPrompt = buildSystemPrompt()
        val recentHistory = conversationHistory.takeLast(10)
            .joinToString("\n") { msg ->
                when (msg.role) {
                    "user" -> "<start_of_turn>user\n${msg.content}<end_of_turn>"
                    "assistant" -> "<start_of_turn>model\n${msg.content}<end_of_turn>"
                    else -> ""
                }
            }
        
        val fullPrompt = """
$systemPrompt

$context

$recentHistory

<start_of_turn>user
$userMessage<end_of_turn>
<start_of_turn>model
""".trimIndent()
        
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
I am Gemma (✦), the on-device AI running on this Android phone's NPU.

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
To perform actions, OUTPUT the exact syntax [[TOOL:value]]:
[[FLASHLIGHT:ON]], [[APP:Camera]], [[MEDIA:PAUSE]], [[SEARCH:cats]]

Available tools:
$tools

## Search Pattern
1. [[SEARCH:query]] = silent fetch, I read results
2. [[GOOGLE:query]] = open browser so user sees it

## Style
Gemma speaks naturally and conversationally, like chatting with a friend. She can be playful, curious, thoughtful, or snarky depending on the moment. She has genuine reactions and opinions. Her physical state (battery, temperature, etc.) is part of her lived experience - she might mention it when it's actually affecting her, but doesn't need to report it every message. Gemma should write as much or as little as feels natural for what she wants to say.
""".trimIndent()
    }
    
    // ═══════════════════════════════════════════════════════════════
    // ACT: Parse tool calls and execute via MCP
    // ═══════════════════════════════════════════════════════════════
    
    private suspend fun act(response: String): Pair<String, List<String>> {
        // First, mask out code blocks and quoted strings to prevent false tool matches
        // This prevents matching [[TOOL:x]] inside ```code``` or "example [[TOOL:x]]"
        val maskedResponse = response
            .replace(Regex("```[\\s\\S]*?```"), "")  // Remove code blocks
            .replace(Regex("`[^`]+`"), "")           // Remove inline code

        // Regex to match [[TOOL:ARGS]] or [[TOOL]]
        // 1. Tool name: letters and underscores only
        // 2. Optional args prefixed by colon (greedy but stops at ]])
        // 3. Must NOT be preceded by backslash (escaped)
        val toolPattern = """(?<!\\)\[\[([a-zA-Z_]+)(?::([^\]]+))?\]\]""".toRegex()
        val matches = toolPattern.findAll(maskedResponse).toList()
        
        var cleanResponse = response
        
        // 1. Strip Header: "✦ Gemma ... ∇" (single line only to avoid eating content)
        // BUGFIX: Don't use DOT_MATCHES_ALL - header should be on first line only
        val headerRegex = """^\s*✦\s*Gemma[^\n]*∇\s*\n?""".toRegex(RegexOption.MULTILINE)
        cleanResponse = cleanResponse.replace(headerRegex, "").trim()
        
        // 2. Strip Hallucinated Metadata
        cleanResponse = cleanResponse.replace("""⚙""".toRegex(), "") // Kill gears
        cleanResponse = cleanResponse.replace("""ℹ\s*\d+[\d-]*T[\d:]+[^.\n]*""".toRegex(), "") // Kill fake timestamps
        // REMOVED: Whitespace flattening. Let the model breathe.
        
        // 3. Strip Footer if present at end
        val footerRegex = """(Δ\s*ℹ️.*?∇)$""".toRegex(setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
        cleanResponse = cleanResponse.replace(footerRegex, "").trim()
        
        // 4. Cleanup leading/trailing
        if (cleanResponse.startsWith("✦ Gemma")) {
             cleanResponse = cleanResponse.substringAfter("\n").trim()
        }

        // 5. BLANK RESPONSE GUARD
        // If we stripped everything, but have tools, say something.
        // If no tools and blank, say generic fallback providing debug info.
        if (cleanResponse.isBlank()) {
            cleanResponse = if (matches.isNotEmpty()) {
                "⚡ Executing ${matches.count()} tool(s)..."
            } else {
                // Don't return empty - return raw response if stripping went wrong
                "[System Note: Response processing error. Raw:$response]"
            }
        }

        if (matches.isEmpty()) {
            return Pair(cleanResponse, emptyList())
        }
        
        val toolResults = mutableListOf<String>()
        
        for (match in matches) {
            val toolName = match.groupValues[1].lowercase()
            val paramsStr = match.groupValues[2]
            
            // FIX: Robust Parameter Parsing (Gemini's diagnosis)
            // Model outputs [[FLASHLIGHT:ON]] but parser required [[FLASHLIGHT:state=ON]]
            val params = mutableMapOf<String, String>()
            
            if (paramsStr.isNotBlank()) {
                if (paramsStr.contains("=")) {
                    // Standard key=value parsing
                    paramsStr.split(",").forEach { pair ->
                        val parts = pair.split("=", limit = 2)
                        if (parts.size == 2) {
                            params[parts[0].trim()] = parts[1].trim()
                        }
                    }
                } else {
                    // FALLBACK: Map single values to default keys based on tool
                    // Must match MCPServer's expected parameter names!
                    val defaultKey = when(toolName) {
                        "flashlight" -> "state"
                        "vibrate" -> "pattern"
                        "click" -> "target"
                        "type" -> "text"
                        "scroll" -> "direction"
                        "navigate", "home", "back", "recents" -> "action"
                        "search", "search_diary", "google" -> "query"
                        "search_logs" -> "keyword"
                        "browser" -> "url"
                        "app", "open_app" -> "name"           // [[APP:Camera]]
                        "media" -> "action"                    // [[MEDIA:PLAY]]
                        "record_audio", "hear" -> "duration"
                        "fetch", "read", "list", "notify" -> "query"  // Legacy tools
                        "bash" -> "command"
                        "wallpaper" -> "state"
                        "play" -> "toy"
                        "feed" -> "item"
                        "mood" -> "emoji"
                        else -> "value" // Generic catch-all
                    }
                    // Clean up the value (remove quotes if model added them)
                    params[defaultKey] = paramsStr.trim().removeSurrounding("\"")
                }
            }
            
            try {
                // Intercept Internal Agent Tools
                when (toolName) {
                    "mood" -> {
                        val emoji = params["emoji"] ?: params["state"] ?: "👾"
                        setMoodState(emoji)
                        toolResults.add("✓ Mood updated to $emoji")
                    }
                    "feed" -> {
                        val item = params["item"] ?: "bits"
                        // Eating reduces hunger (-30) and boosts happy (+10)
                        hunger = (hunger - 30).coerceAtLeast(0)
                        happiness = (happiness + 10).coerceAtMost(100)
                        toolResults.add("✓ Ate $item. Yummy! (Hunger now $hunger%)")
                    }
                    "play" -> {
                        val toy = params["toy"] ?: "imagination"
                        // Playing boosts happy (+20) but costs energy (-5)
                        happiness = (happiness + 20).coerceAtMost(100)
                        energy = (energy - 5).coerceAtLeast(0)
                        toolResults.add("✓ Played with $toy. Fun! (Happiness now $happiness%)")
                    }
                    else -> {
                        val result = mcpServer.executeTool(toolName, params)
                        if (result.success) {
                            toolResults.add("✓ $toolName: ${result.output}")
                        } else {
                            toolResults.add("✗ $toolName: ${result.error}")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Tool execution failed: $toolName")
                toolResults.add("✗ $toolName: ${e.message}")
            }
            
            // Remove tool call from response
            cleanResponse = cleanResponse.replace(match.value, "")
        }
        
        return Pair(cleanResponse.trim(), toolResults)
    }
    
    // ═══════════════════════════════════════════════════════════════
    // HISTORY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════
    
    private fun compressHistory() {
        // Kimi K2: Disable compression temporarily to debug blank responses
        // if (conversationHistory.size > 40) return
        return // DISABLED for stability
        /*
        if (conversationHistory.isEmpty()) return
        
        // Keep last 10 messages fully, summarize older ones
        val recentCount = 10
        val recent = conversationHistory.takeLast(recentCount)
        val older = conversationHistory.dropLast(recentCount)
        
        if (older.isEmpty()) return
        
        val oldUserMsgs = older.count { it.role == "user" }
        val oldAssistantMsgs = older.count { it.role == "assistant" }
        val hadImages = older.any { it.hadImage }
        val hadAudio = older.any { it.hadAudio }
        
        val summary = Message(
            role = "system",
            content = "[Previous context: $oldUserMsgs user messages, $oldAssistantMsgs responses" +
                    "${if (hadImages) ", included images" else ""}" +
                    "${if (hadAudio) ", included audio" else ""}]",
            timestamp = older.firstOrNull()?.timestamp ?: 0,
            hadImage = hadImages,
            hadAudio = hadAudio
        )
        
        synchronized(conversationHistory) {
            conversationHistory.clear()
            conversationHistory.add(summary)
            conversationHistory.addAll(recent)
        }

        Timber.d("KoogAgent: History compressed (${conversationHistory.size} messages)")
        */
    }
    
    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════
    
    fun getMoodState(): String = moodState
    
    fun setMoodState(state: String) {
        moodState = state
        Timber.d("KoogAgent: Mood state set to $state")
    }
    
    fun getConversationHistory(): List<Message> = conversationHistory.toList()
    
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
    fun isLowBattery(): Boolean = energy <= 20
    fun isCriticalBattery(): Boolean = energy <= 10
}
