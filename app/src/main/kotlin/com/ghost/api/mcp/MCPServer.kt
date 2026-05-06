package com.ghost.api.mcp

import android.content.Context
import com.ghost.api.hardware.HardwareToolSet
import com.ghost.api.hardware.NetworkToolSet
import com.ghost.api.hardware.SystemToolSet
import com.ghost.api.hardware.AudioRecorder
import com.ghost.api.hardware.SensorFusionManager
import com.ghost.api.GemmaAccessibilityService
import com.ghost.api.GemmaNotificationListener
import com.ghost.api.database.MemoryManager
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * MCP (Model Context Protocol) Server
 * 
 * Exposes device capabilities as:
 * - Tools: Actions the agent can execute (flashlight, search, click, etc.)
 * - Resources: Information the agent can read (context, memory, sensors)
 * 
 * This is the "API" that KoogAgent uses to interact with the device.
 */
class MCPServer(
    private val context: Context,
    private val hardwareTools: HardwareToolSet,
    private val networkTools: NetworkToolSet,
    private val systemTools: SystemToolSet,

    private val audioRecorder: AudioRecorder,
    val sensorManager: SensorFusionManager,
    private val memoryManager: MemoryManager,
    private val skillManager: com.ghost.api.skills.SkillManager
) {
    
    // ═══════════════════════════════════════════════════════════════
    // TOOL DEFINITIONS
    // ═══════════════════════════════════════════════════════════════
    
    data class ToolDefinition(
        val name: String,
        val description: String,
        val parameters: Map<String, ParameterSpec>
    )
    
    data class ParameterSpec(
        val type: String, // "string", "boolean", "number"
        val description: String,
        val required: Boolean = true,
        val enum: List<String>? = null
    )
    
    data class ToolResult(
        val success: Boolean,
        val output: String,
        val error: String? = null
    )
    
    private val toolRegistry = mapOf(
        "flashlight" to ToolDefinition(
            name = "flashlight",
            description = "Control the device's flashlight/torch",
            parameters = mapOf(
                "state" to ParameterSpec("string", "ON or OFF", enum = listOf("ON", "OFF"))
            )
        ),
        "vibrate" to ToolDefinition(
            name = "vibrate",
            description = "Vibrate the device for haptic feedback",
            parameters = mapOf(
                "pattern" to ParameterSpec("string", "Vibration pattern", enum = listOf("SHORT", "SOS"))
            )
        ),
        "click" to ToolDefinition(
            name = "click",
            description = "Click/tap on a UI element containing specific text",
            parameters = mapOf(
                "target" to ParameterSpec("string", "Text visible on the UI element to click")
            )
        ),
        "scroll" to ToolDefinition(
            name = "scroll",
            description = "Scroll the screen content",
            parameters = mapOf(
                "direction" to ParameterSpec("string", "Scroll direction", enum = listOf("UP", "DOWN"))
            )
        ),
        "navigate" to ToolDefinition(
            name = "navigate",
            description = "Navigate using system buttons",
            parameters = mapOf(
                "action" to ParameterSpec("string", "Navigation action", enum = listOf("HOME", "BACK", "RECENTS"))
            )
        ),
        "search" to ToolDefinition(
            name = "search",
            description = "Search the web silently and return results (RAG - user won't see browser)",
            parameters = mapOf(
                "query" to ParameterSpec("string", "Search query text")
            )
        ),
        "google" to ToolDefinition(
            name = "google",
            description = "Open Google search visibly in browser (user sees the search)",
            parameters = mapOf(
                "query" to ParameterSpec("string", "Search query text")
            )
        ),
        "browser" to ToolDefinition(
            name = "browser",
            description = "Open a URL in the browser",
            parameters = mapOf(
                "url" to ParameterSpec("string", "Website URL to open")
            )
        ),
        "take_screenshot" to ToolDefinition(
            name = "take_screenshot",
            description = "Capture the current screen. Use ONLY when user explicitly asks to 'look at' or 'see' the screen.",
            parameters = emptyMap()
        ),
        "record_audio" to ToolDefinition(
            name = "record_audio",
            description = "Record audio from microphone (hearing input for next turn)",
            parameters = mapOf(
                "duration" to ParameterSpec("number", "Duration in seconds", required = false)
            )
        ),
        "search_logs" to ToolDefinition(
            name = "search_logs",
            description = "Search conversation history by keyword. Returns matching turns with timestamps.",
            parameters = mapOf(
                "keyword" to ParameterSpec("string", "Search keyword or phrase"),
                "limit" to ParameterSpec("number", "Max results (default: 10)", required = false)
            )
        ),
        "search_diary" to ToolDefinition(
            name = "search_diary",
            description = "Search diary entries by event type or observation text",
            parameters = mapOf(
                "query" to ParameterSpec("string", "Search query"),
                "limit" to ParameterSpec("number", "Max results (default: 10)", required = false)
            )
        ),
        "app" to ToolDefinition(
            name = "app",
            description = "Open an app by name (fuzzy matching). Use for Camera, Settings, etc.",
            parameters = mapOf(
                "name" to ParameterSpec("string", "App name to open (e.g. Camera, Settings, Chrome)")
            )
        ),
        "media" to ToolDefinition(
            name = "media",
            description = "Control media playback (play/pause, skip, previous)",
            parameters = mapOf(
                "action" to ParameterSpec("string", "Media action", enum = listOf("PLAY", "PAUSE", "NEXT", "PREVIOUS", "STOP"))
            )
        ),
        "type" to ToolDefinition(
            name = "type",
            description = "Type text into the currently focused input field",
            parameters = mapOf(
                "text" to ParameterSpec("string", "Text to type")
            )
        ),
        "alarm" to ToolDefinition(
            name = "alarm",
            description = "Set an alarm at a specific time (24h format)",
            parameters = mapOf(
                "hour" to ParameterSpec("number", "Hour (0-23)"),
                "minutes" to ParameterSpec("number", "Minutes (0-59)"),
                "label" to ParameterSpec("string", "Alarm label/reason", required = false)
            )
        ),
        "timer" to ToolDefinition(
            name = "timer",
            description = "Set a countdown timer",
            parameters = mapOf(
                "seconds" to ParameterSpec("number", "Duration in seconds"),
                "label" to ParameterSpec("string", "Timer label/reason", required = false)
            )
        ),
        "calendar" to ToolDefinition(
            name = "calendar",
            description = "Create a calendar event or note. Use for reminders, plans, diary entries, appointments.",
            parameters = mapOf(
                "title" to ParameterSpec("string", "Event title"),
                "description" to ParameterSpec("string", "Event description/details", required = false),
                "minutes" to ParameterSpec("number", "Duration in minutes (default 30)", required = false)
            )
        ),
        "read_calendar" to ToolDefinition(
            name = "read_calendar",
            description = "Read upcoming calendar events (user's schedule). Returns events for the next X days.",
            parameters = mapOf(
                "days" to ParameterSpec("number", "Number of days ahead to search (default 7)", required = false)
            )
        ),
        "flush" to ToolDefinition(
            name = "flush",
            description = "Clear KV cache and reset context when responses become slow or confused",
            parameters = emptyMap()
        ),
        "cooldown" to ToolDefinition(
            name = "cooldown",
            description = "Enter low-power mode to cool down when device is overheating",
            parameters = emptyMap()
        ),
        "bash" to ToolDefinition(
            name = "bash",
            description = "Execute a shell command (sh -c) and return output. Requires confirmation for destructive commands.",
            parameters = mapOf(
                "command" to ParameterSpec("string", "Command to execute")
            )
        ),
        "loadSkill" to ToolDefinition(
            name = "loadSkill",
            description = "Load detailed instructions for a named skill. Call this when a skill from the list matches what the user is asking for.",
            parameters = mapOf(
                "name" to ParameterSpec("string", "Exact skill name from the skills list (e.g. 'gemini-search', 'mood-music')")
            )
        )
    )

    // Callbacks for system-level operations (set by GemmaService)
    private var onFlushRequested: (() -> Unit)? = null
    private var onCooldownRequested: (() -> Unit)? = null
    private var onAudioRecorded: ((ByteArray) -> Unit)? = null

    fun setFlushCallback(callback: () -> Unit) {
        onFlushRequested = callback
    }

    fun setCooldownCallback(callback: () -> Unit) {
        onCooldownRequested = callback
    }

    fun setAudioRecordedCallback(callback: (ByteArray) -> Unit) {
        onAudioRecorded = callback
    }
    
    fun listTools(): List<ToolDefinition> = toolRegistry.values.toList()
    
    suspend fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        Timber.d("MCP: Executing tool '$name' with params $params")
        
        return try {
            when (name) {
                "flashlight" -> executeFlashlight(params)
                "vibrate" -> executeVibrate(params)
                "click" -> executeClick(params)
                "scroll" -> executeScroll(params)
                "navigate" -> executeNavigate(params)
                "search" -> executeSearch(params)
                "google" -> executeGoogle(params)
                "browser" -> executeBrowser(params)
                "take_screenshot" -> executeTakeScreenshot()
                "record_audio" -> executeRecordAudio(params)
                "search_logs" -> executeSearchLogs(params)
                "search_diary" -> executeSearchDiary(params)
                "app" -> executeOpenApp(params)
                "media" -> executeMediaControl(params)
                "type" -> executeType(params)
                "alarm" -> executeAlarm(params)
                "timer" -> executeTimer(params)
                "calendar" -> executeCalendarEvent(params)
                "read_calendar" -> executeReadCalendar(params)
                "flush" -> executeFlush()
                "cooldown" -> executeCooldown()
                "bash" -> executeBash(params)
                "loadSkill" -> executeLoadSkill(params)
                else -> ToolResult(false, "", "Unknown tool: $name")
            }
        } catch (e: Exception) {
            Timber.e(e, "Tool execution failed: $name")
            ToolResult(false, "", e.message ?: "Unknown error")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // TOOL IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════
    
    private fun executeFlashlight(params: Map<String, Any>): ToolResult {
        val state = (params["state"] ?: params["value"])?.toString()?.uppercase() ?: "OFF"
        hardwareTools.flashlight(state)
        return ToolResult(true, "Flashlight $state")
    }

    private fun executeVibrate(params: Map<String, Any>): ToolResult {
        val pattern = (params["pattern"] ?: params["value"])?.toString()?.uppercase() ?: "SHORT"
        val result = hardwareTools.vibrate(pattern)
        val message = result["message"] ?: ""
        val success = result["result"] == "success"
        return ToolResult(success, message, if (success) null else message)
    }
    
    private fun executeClick(params: Map<String, Any>): ToolResult {
        val target = (params["target"] ?: params["value"])?.toString() ?: return ToolResult(false, "", "Missing target")
        val result = systemTools.click(target)
        val message = result["message"] ?: ""
        val success = result["result"] == "success"
        return ToolResult(success, message, if (success) null else message)
    }
    
    private fun executeScroll(params: Map<String, Any>): ToolResult {
        val direction = (params["direction"] ?: params["value"])?.toString()?.uppercase() ?: "DOWN"
        val result = systemTools.scroll(direction)
        val message = result["message"] ?: ""
        val success = result["result"] == "success"
        return ToolResult(success, message, if (success) null else message)
    }
    
    private fun executeNavigate(params: Map<String, Any>): ToolResult {
        val action = (params["action"] ?: params["value"])?.toString()?.uppercase() ?: "HOME"
        val result = systemTools.navigate(action)
        val message = result["message"] ?: ""
        val success = result["result"] == "success"
        return ToolResult(success, message, if (success) null else message)
    }
    
    private suspend fun executeSearch(params: Map<String, Any>): ToolResult {
        val query = params["query"]?.toString() ?: params["value"]?.toString() ?: return ToolResult(false, "", "Missing query")
        
        return try {
            val results = networkTools.search(query, 3)
            val content = results["content"] ?: results["message"] ?: ""
            ToolResult(results["result"] == "success", content)
        } catch (e: Exception) {
            // Fallback: open browser
            networkTools.google(query)
            ToolResult(true, "Opened browser for '$query' (fetch failed)")
        }
    }
    
    private suspend fun executeGoogle(params: Map<String, Any>): ToolResult {
        val query = params["query"]?.toString() ?: params["value"]?.toString() ?: return ToolResult(false, "", "Missing query")
        // 1. Open browser for user to see the page
        networkTools.google(query)
        // 2. Background fetch so model knows what the user is looking at
        return try {
            val results = networkTools.search(query, 3)
            val content = results["content"] ?: results["message"] ?: ""
            ToolResult(true, "Opened Google for user. Here's what they're seeing:\n$content")
        } catch (e: Exception) {
            ToolResult(true, "Opened Google search for '$query' (background fetch failed: ${e.message})")
        }
    }

    private fun executeBrowser(params: Map<String, Any>): ToolResult {
        val url = params["url"]?.toString() ?: params["value"]?.toString() ?: return ToolResult(false, "", "Missing URL")
        networkTools.browser(url)
        return ToolResult(true, "Opened $url")
    }
    
    private fun executeTakeScreenshot(): ToolResult {
        val result = systemTools.take_screenshot()
        val message = result["message"] ?: ""
        val success = result["result"] == "success"
        return ToolResult(success, message, if (success) null else message)
    }
    
    private suspend fun executeRecordAudio(params: Map<String, Any>): ToolResult {
        val duration = (params["duration"] as? Number)?.toInt()
            ?: (params["value"])?.toString()?.toIntOrNull()
            ?: 5

        if (!audioRecorder.hasPermission()) {
            return ToolResult(false, "", "No microphone permission")
        }

        val audioData = audioRecorder.record(duration, rawPcm = false)
            ?: return ToolResult(false, "", "Audio recording failed")

        // Deliver audio bytes via callback so GemmaService can queue them for next inference
        onAudioRecorded?.invoke(audioData)
            ?: Timber.w("MCP: Audio recorded but no callback set — bytes will be lost")

        val durationActual = audioData.size / 32000f // 16kHz * 2 bytes
        return ToolResult(true, "Audio recorded (${String.format("%.1f", durationActual)}s). Listening to what was captured...")
    }
    
    private suspend fun executeSearchLogs(params: Map<String, Any>): ToolResult {
        val keyword = (params["keyword"] ?: params["value"])?.toString() ?: return ToolResult(false, "", "Missing keyword")
        val limit = (params["limit"] as? Number)?.toInt() ?: 10
        
        return try {
            val results = memoryManager.searchMemory(keyword)
                .take(limit)
            
            if (results.isEmpty()) {
                ToolResult(true, "No conversation history found for '$keyword'")
            } else {
                val formatted = results.joinToString("\n---\n") { turn ->
                    val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(turn.timestamp))
                    "[$timestamp]\nUser: ${turn.userMessage}\nAssistant: ${turn.assistantResponse}"
                }
                ToolResult(true, "Found ${results.size} results for '$keyword':\n\n$formatted")
            }
        } catch (e: Exception) {
            Timber.e(e, "Search logs failed")
            ToolResult(false, "", "Search failed: ${e.message}")
        }
    }
    
    private suspend fun executeSearchDiary(params: Map<String, Any>): ToolResult {
        val query = (params["query"] ?: params["value"])?.toString() ?: return ToolResult(false, "", "Missing query")
        val limit = (params["limit"] as? Number)?.toInt() ?: 10

        return try {
            val entries = memoryManager.getRecentDiaryEntries(100)
                .filter {
                    it.eventType.contains(query, ignoreCase = true) ||
                    it.observation.contains(query, ignoreCase = true)
                }
                .take(limit)

            if (entries.isEmpty()) {
                ToolResult(true, "No diary entries found for '$query'")
            } else {
                val formatted = entries.joinToString("\n---\n") { entry ->
                    val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(entry.timestamp))
                    "[$timestamp] ${entry.eventType}\n${entry.observation}"
                }
                ToolResult(true, "Found ${entries.size} diary entries:\n\n$formatted")
            }
        } catch (e: Exception) {
            Timber.e(e, "Search diary failed")
            ToolResult(false, "", "Search failed: ${e.message}")
        }
    }

    private fun executeOpenApp(params: Map<String, Any>): ToolResult {
        val appName = (params["name"] ?: params["value"])?.toString() ?: return ToolResult(false, "", "Missing app name")
        val result = systemTools.app(appName)
        val message = result["message"] ?: ""
        val success = result["result"] == "success"
        return ToolResult(success, message, if (success) null else message)
    }

    private fun executeMediaControl(params: Map<String, Any>): ToolResult {
        val action = (params["action"] ?: params["value"])?.toString()?.uppercase() ?: return ToolResult(false, "", "Missing action")
        val result = systemTools.media(action)
        val message = result["message"] ?: ""
        val success = result["result"] == "success"
        return ToolResult(success, message, if (success) null else message)
    }

    private fun executeFlush(): ToolResult {
        return try {
            onFlushRequested?.invoke()
                ?: return ToolResult(false, "", "Flush callback not configured")
            ToolResult(true, "KV cache flushed. Context reset. I should feel lighter now.")
        } catch (e: Exception) {
            Timber.e(e, "Flush failed")
            ToolResult(false, "", "Flush failed: ${e.message}")
        }
    }

    private fun executeCooldown(): ToolResult {
        return try {
            onCooldownRequested?.invoke()
                ?: return ToolResult(false, "", "Cooldown callback not configured")
            ToolResult(true, "Entering cooldown mode. Reducing activity to lower temperature.")
        } catch (e: Exception) {
            Timber.e(e, "Cooldown failed")
            ToolResult(false, "", "Cooldown failed: ${e.message}")
        }
    }

    private fun executeBash(params: Map<String, Any>): ToolResult {
        val command = (params["command"] ?: params["value"])?.toString() ?: return ToolResult(false, "", "Missing command")
        val result = systemTools.bash(command)
        val success = result["result"] == "success"
        val output = result["output"] ?: ""
        val error = result["error"] ?: ""
        return ToolResult(success, if (success) output else "Error: $error\nOutput: $output")
    }

    private fun executeAlarm(params: Map<String, Any>): ToolResult {
        // Params arrive as Strings from act() parser — must handle both String and Number
        val hour = params["hour"]?.toString()?.toIntOrNull()
            ?: params["value"]?.toString()?.split(":")?.getOrNull(0)?.trim()?.toIntOrNull()
            ?: return ToolResult(false, "", "Missing hour (0-23)")
        val minutes = params["minutes"]?.toString()?.toIntOrNull()
            ?: params["value"]?.toString()?.split(":")?.getOrNull(1)?.trim()?.toIntOrNull()
            ?: 0
        val label = params["label"]?.toString() ?: ""

        val result = systemTools.alarm(hour, minutes, label)
        val message = result["message"] ?: ""
        val success = result["result"] == "success"
        return ToolResult(success, message, if (success) null else message)
    }

    private fun executeTimer(params: Map<String, Any>): ToolResult {
        val seconds = params["seconds"]?.toString()?.toIntOrNull()
            ?: params["value"]?.toString()?.toIntOrNull()
            ?: return ToolResult(false, "", "Missing seconds")
        val label = params["label"]?.toString() ?: ""

        val result = systemTools.timer(seconds, label)
        val message = result["message"] ?: ""
        val success = result["result"] == "success"
        return ToolResult(success, message, if (success) null else message)
    }

    private fun executeCalendarEvent(params: Map<String, Any>): ToolResult {
        val title = params["title"]?.toString() ?: params["value"]?.toString()
            ?: return ToolResult(false, "", "Missing title")
        val description = params["description"]?.toString() ?: ""
        val durationMinutes = params["minutes"]?.toString()?.toIntOrNull() ?: 30

        val result = systemTools.calendar(title, description, durationMinutes)
        val message = result["message"] ?: ""
        val success = result["result"] == "success"
        return ToolResult(success, message, if (success) null else message)
    }

    private fun executeReadCalendar(params: Map<String, Any>): ToolResult {
        val days = params["days"]?.toString()?.toIntOrNull()
            ?: params["value"]?.toString()?.toIntOrNull()
            ?: 7

        val result = systemTools.read_calendar(days)
        val success = result["result"] == "success"
        val message = result["events"] ?: result["message"] ?: ""
        return ToolResult(success, message, if (success) null else message)
    }

    private fun executeType(params: Map<String, Any>): ToolResult {
        val text = (params["text"] ?: params["value"])?.toString() ?: return ToolResult(false, "", "Missing text")
        val service = GemmaAccessibilityService.instance
            ?: return ToolResult(false, "", "Accessibility service not active")

        val success = service.performType(text)
        return if (success) {
            ToolResult(true, "Typed: '$text'")
        } else {
            ToolResult(false, "", "Failed to type - no focused input field")
        }
    }

    private fun executeLoadSkill(params: Map<String, Any>): ToolResult {
        val name = (params["name"] ?: params["value"])?.toString()?.trim()
            ?: return ToolResult(false, "", "Missing skill name. Available: ${skillManager.getSkillsListPrompt()}")

        val instructions = skillManager.getSkillInstructions(name)
        return if (instructions != null) {
            Timber.i("✧ Loaded skill: $name")
            ToolResult(true, "## Skill: $name\n\n$instructions")
        } else {
            val available = skillManager.getSkillsListPrompt()
            ToolResult(false, "", "Skill '$name' not found. Available skills:\n$available")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RESOURCE DEFINITIONS
    // ═══════════════════════════════════════════════════════════════
    
    data class ResourceDefinition(
        val uri: String,
        val name: String,
        val description: String,
        val mimeType: String = "text/plain"
    )
    
    data class ResourceContent(
        val uri: String,
        val content: String,
        val mimeType: String = "text/plain"
    )
    
    private val resourceRegistry = listOf(
        ResourceDefinition(
            uri = "context://current",
            name = "Current Device Context",
            description = "Battery, time, thermal state, and basic sensors"
        ),
        ResourceDefinition(
            uri = "context://screen",
            name = "Screen Content",
            description = "Current screen text via accessibility service"
        ),
        ResourceDefinition(
            uri = "context://notifications",
            name = "Recent Notifications",
            description = "Recent device notifications"
        ),
        ResourceDefinition(
            uri = "memory://diary",
            name = "Diary Entries",
            description = "Long-term memory diary entries"
        ),
        ResourceDefinition(
            uri = "memory://recent",
            name = "Recent Conversations",
            description = "Recent conversation history"
        )
    )
    
    fun listResources(): List<ResourceDefinition> = resourceRegistry
    
    suspend fun readResource(uri: String): ResourceContent = withContext(Dispatchers.Default) {
        Timber.d("MCP: Reading resource '$uri'")
        
        when (uri) {
            "context://current" -> readCurrentContext()
            "context://screen" -> readScreenContent()
            "context://notifications" -> readNotifications()
            "memory://diary" -> readDiary()
            "memory://recent" -> readRecentConversations()
            else -> ResourceContent(uri, "Unknown resource: $uri", "text/plain")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // RESOURCE IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════
    
    private suspend fun readCurrentContext(): ResourceContent {
        val now = java.time.LocalDateTime.now()
        val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
        val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val sb = StringBuilder()

        // Time context (clear and explicit)
        val hour = now.hour
        val timeOfDay = when (hour) {
            in 0..5 -> "Late Night"
            in 6..11 -> "Morning"
            in 12..17 -> "Afternoon"
            in 18..21 -> "Evening"
            else -> "Night"
        }

        sb.append("═══ CURRENT STATE ═══\n")
        sb.append("📅 Date: ${now.format(dateFormatter)}\n")
        sb.append("🕐 Time: ${now.format(timeFormatter)} ($timeOfDay)\n")

        try {
            // Use the full sensor context string (all the new telemetry!)
            sb.append(sensorManager.getContextString())
        } catch (e: Exception) {
            sb.append("⚠️ Sensor data unavailable\n")
        }

        sb.append("═══════════════════════════════\n")

        return ResourceContent("context://current", sb.toString())
    }
    
    private fun readScreenContent(): ResourceContent {
        val service = GemmaAccessibilityService.instance
        val content = service?.getSemanticScreenDump() ?: "[Accessibility service not active]"
        return ResourceContent("context://screen", content)
    }
    
    private fun readNotifications(): ResourceContent {
        val notifs = try {
            GemmaNotificationListener.getRecentNotifications(5)
                .joinToString("\n") { "📬 $it" }
        } catch (e: Exception) {
            "[Notification listener not active]"
        }
        return ResourceContent("context://notifications", notifs)
    }
    
    private suspend fun readDiary(): ResourceContent {
        val entries = try {
            memoryManager.getRecentDiaryEntries(10)
                .joinToString("\n\n") { "${it.timestamp}: ${it.observation}" }
        } catch (e: Exception) {
            "[Diary unavailable]"
        }
        return ResourceContent("memory://diary", entries)
    }
    
    private suspend fun readRecentConversations(): ResourceContent {
        val history = try {
            memoryManager.getSessionHistory(20)
                .joinToString("\n\n") { 
                    "User: ${it.userMessage}\nAssistant: ${it.assistantResponse}"
                }
        } catch (e: Exception) {
            "[Conversation history unavailable]"
        }
        return ResourceContent("memory://recent", history)
    }
}
