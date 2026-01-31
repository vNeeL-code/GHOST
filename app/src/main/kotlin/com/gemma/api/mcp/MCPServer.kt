package com.gemma.api.mcp

import android.content.Context
import com.gemma.api.hardware.HardwareToolSet
import com.gemma.api.hardware.NetworkToolSet
import com.gemma.api.hardware.SystemToolSet
import com.gemma.api.hardware.SensorFusionManager
import com.gemma.api.GemmaAccessibilityService
import com.gemma.api.GemmaNotificationListener
import com.gemma.api.database.MemoryManager
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
    private val sensorManager: SensorFusionManager,
    private val memoryManager: MemoryManager
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
            description = "Capture the current screen (vision input for next turn)",
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
        "flush" to ToolDefinition(
            name = "flush",
            description = "Clear KV cache and reset context when responses become slow or confused",
            parameters = emptyMap()
        ),
        "cooldown" to ToolDefinition(
            name = "cooldown",
            description = "Enter low-power mode to cool down when device is overheating",
            parameters = emptyMap()
        )
    )

    // Callbacks for system-level operations (set by GemmaService)
    private var onFlushRequested: (() -> Unit)? = null
    private var onCooldownRequested: (() -> Unit)? = null

    fun setFlushCallback(callback: () -> Unit) {
        onFlushRequested = callback
    }

    fun setCooldownCallback(callback: () -> Unit) {
        onCooldownRequested = callback
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
                "flush" -> executeFlush()
                "cooldown" -> executeCooldown()
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
        val state = params["state"]?.toString()?.uppercase() == "ON"
        hardwareTools.controlFlashlight(state)
        return ToolResult(true, "Flashlight ${if (state) "ON" else "OFF"}")
    }
    
    private fun executeVibrate(params: Map<String, Any>): ToolResult {
        val pattern = params["pattern"]?.toString()?.uppercase() ?: "SHORT"
        val timing = if (pattern == "SOS") {
            listOf(0L, 200L, 100L, 200L, 100L, 200L, 300L, 500L, 100L, 500L, 100L, 500L, 300L, 200L, 100L, 200L, 100L, 200L)
        } else {
            listOf(0L, 500L)
        }
        hardwareTools.vibrate(timing)
        return ToolResult(true, "Vibrated $pattern pattern")
    }
    
    private fun executeClick(params: Map<String, Any>): ToolResult {
        val target = params["target"]?.toString() ?: return ToolResult(false, "", "Missing target")
        val service = GemmaAccessibilityService.instance
            ?: return ToolResult(false, "", "Accessibility service not active")
        
        val found = service.performClick(target)
        return if (found) {
            ToolResult(true, "Clicked on '$target'")
        } else {
            ToolResult(false, "", "Could not find '$target' on screen")
        }
    }
    
    private fun executeScroll(params: Map<String, Any>): ToolResult {
        val direction = params["direction"]?.toString()?.uppercase() ?: "DOWN"
        val service = GemmaAccessibilityService.instance
            ?: return ToolResult(false, "", "Accessibility service not active")
        
        val success = service.performScroll(direction)
        return if (success) {
            ToolResult(true, "Scrolled $direction")
        } else {
            ToolResult(false, "", "Failed to scroll")
        }
    }
    
    private fun executeNavigate(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString()?.uppercase() ?: "HOME"
        val service = GemmaAccessibilityService.instance
            ?: return ToolResult(false, "", "Accessibility service not active")
        
        service.performGlobal(action)
        return ToolResult(true, "Navigated $action")
    }
    
    private suspend fun executeSearch(params: Map<String, Any>): ToolResult {
        val query = params["query"]?.toString() ?: return ToolResult(false, "", "Missing query")
        
        return try {
            val results = networkTools.fetchSearchResults(query, 3)
            ToolResult(true, "Search results for '$query':\n$results")
        } catch (e: Exception) {
            // Fallback: open browser
            networkTools.googleSearch(query)
            ToolResult(true, "Opened browser for '$query' (fetch failed)")
        }
    }
    
    private fun executeGoogle(params: Map<String, Any>): ToolResult {
        val query = params["query"]?.toString() ?: return ToolResult(false, "", "Missing query")
        networkTools.googleSearch(query)
        return ToolResult(true, "Opened Google search for '$query'")
    }

    private fun executeBrowser(params: Map<String, Any>): ToolResult {
        val url = params["url"]?.toString() ?: return ToolResult(false, "", "Missing URL")
        networkTools.openBrowser(url)
        return ToolResult(true, "Opened $url")
    }
    
    private fun executeTakeScreenshot(): ToolResult {
        // Broadcast request for screenshot to GemmaService
        val intent = android.content.Intent("com.gemma.api.ACTION_REQUEST_SCREENSHOT")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
        return ToolResult(true, "Screenshot requested. Visual input will be available in next turn.")
    }
    
    private fun executeRecordAudio(params: Map<String, Any>): ToolResult {
        val duration = (params["duration"] as? Number)?.toInt() ?: 5
        // Placeholder - actual implementation would trigger audio recording
        return ToolResult(true, "Audio recording ($duration seconds) queued for next turn")
    }
    
    private suspend fun executeSearchLogs(params: Map<String, Any>): ToolResult {
        val keyword = params["keyword"]?.toString() ?: return ToolResult(false, "", "Missing keyword")
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
        val query = params["query"]?.toString() ?: return ToolResult(false, "", "Missing query")
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
        val appName = params["name"]?.toString() ?: return ToolResult(false, "", "Missing app name")
        val result = systemTools.openApp(appName)
        val success = result.startsWith("Launched")
        return ToolResult(success, result, if (success) null else result)
    }

    private fun executeMediaControl(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString()?.uppercase() ?: return ToolResult(false, "", "Missing action")
        val result = systemTools.mediaControl(action)
        val success = result.startsWith("Media Action Sent")
        return ToolResult(success, result, if (success) null else result)
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

    private fun executeType(params: Map<String, Any>): ToolResult {
        val text = params["text"]?.toString() ?: return ToolResult(false, "", "Missing text")
        val service = GemmaAccessibilityService.instance
            ?: return ToolResult(false, "", "Accessibility service not active")

        val success = service.performType(text)
        return if (success) {
            ToolResult(true, "Typed: '$text'")
        } else {
            ToolResult(false, "", "Failed to type - no focused input field")
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
        val formatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy - HH:mm")
        val sb = StringBuilder()

        // Time context (human readable)
        val hour = now.hour
        val timeOfDay = when (hour) {
            in 0..5 -> "🌙 Late Night"
            in 6..11 -> "🌅 Morning"
            in 12..17 -> "☀️ Afternoon"
            in 18..21 -> "🌆 Evening"
            else -> "🌙 Night"
        }

        sb.append("═══ ${now.format(formatter)} $timeOfDay ═══\n")

        try {
            // Use the full sensor context string (all the new telemetry!)
            sb.append(sensorManager.getContextString())
        } catch (e: Exception) {
            sb.append("⚠️ Sensor data unavailable\n")
        }

        sb.append("\n═══════════════════════════════\n")

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
