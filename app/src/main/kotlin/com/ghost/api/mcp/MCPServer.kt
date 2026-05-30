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
import com.ghost.api.skills.SkillManager
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MCP (Model Context Protocol) Server
 */
class MCPServer(
    private val context: Context,
    private val hardwareTools: HardwareToolSet,
    private val networkTools: NetworkToolSet,
    private val systemTools: SystemToolSet,
    private val audioRecorder: AudioRecorder,
    val sensorManager: SensorFusionManager,
    private val memoryManager: MemoryManager,
    private val skillManager: SkillManager
) {
    
    data class ToolDefinition(val name: String, val description: String, val parameters: Map<String, ParameterSpec>)
    data class ParameterSpec(val type: String, val description: String, val required: Boolean = true, val enum: List<String>? = null)
    data class ToolResult(val success: Boolean, val output: String, val error: String? = null)
    
    private val toolRegistry = mapOf(
        // Hardware
        "flashlight"      to ToolDefinition("flashlight", "Toggle the device flashlight", mapOf("state" to ParameterSpec("string", "ON or OFF"))),
        // System / Apps
        "app"             to ToolDefinition("app", "Launch an installed app by name", mapOf("name" to ParameterSpec("string", "App name"))),
        "media"           to ToolDefinition("media", "Control media playback", mapOf("action" to ParameterSpec("string", "PLAY, PAUSE, NEXT, or PREV"))),
        "alarm"           to ToolDefinition("alarm", "Set an alarm", mapOf(
            "hour"    to ParameterSpec("integer", "Hour (0-23)"),
            "minutes" to ParameterSpec("integer", "Minute (0-59)"),
            "label"   to ParameterSpec("string", "Label", required = false)
        )),
        "timer"           to ToolDefinition("timer", "Set a countdown timer", mapOf(
            "seconds" to ParameterSpec("integer", "Duration in seconds"),
            "label"   to ParameterSpec("string", "Label", required = false)
        )),
        "calendar"        to ToolDefinition("calendar", "Create a calendar event", mapOf(
            "title"       to ParameterSpec("string", "Event title"),
            "description" to ParameterSpec("string", "Event description", required = false),
            "minutes"     to ParameterSpec("integer", "Duration in minutes", required = false)
        )),
        "read_calendar"   to ToolDefinition("read_calendar", "Read upcoming calendar events", mapOf(
            "days" to ParameterSpec("integer", "Days ahead to look", required = false)
        )),
        // Screen / Accessibility
        "click"           to ToolDefinition("click", "Click a UI element by visible text", mapOf("target" to ParameterSpec("string", "Text of element to click"))),
        "scroll"          to ToolDefinition("scroll", "Scroll the screen", mapOf("direction" to ParameterSpec("string", "UP, DOWN, LEFT, or RIGHT"))),
        "navigate"        to ToolDefinition("navigate", "Android system navigation", mapOf("action" to ParameterSpec("string", "BACK, HOME, RECENTS, or NOTIFICATIONS"))),
        "take_screenshot" to ToolDefinition("take_screenshot", "Capture the current screen", emptyMap()),
        // Memory
        "remember"        to ToolDefinition("remember", "Store a memory", mapOf(
            "title"   to ParameterSpec("string", "Memory title"),
            "content" to ParameterSpec("string", "Memory content")
        )),
        "recall"          to ToolDefinition("recall", "Search stored memories", mapOf("query" to ParameterSpec("string", "Search query"))),
        // Shell
        "bash"            to ToolDefinition("bash", "Run a shell command (Termux pipe)", mapOf("command" to ParameterSpec("string", "Shell command"))),
        // Network
        "web_search"      to ToolDefinition("web_search", "Web search", mapOf("query" to ParameterSpec("string", "Search query"))),
        // Skills
        "loadSkill"       to ToolDefinition("loadSkill", "Load skill instructions by name", mapOf("name" to ParameterSpec("string", "Skill name")))
    )

    suspend fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        return try {
            when (name) {
                // Hardware
                "flashlight" -> {
                    val state = params["state"]?.toString()?.uppercase() ?: "OFF"
                    hardwareTools.flashlight(state)
                    ToolResult(true, "Flashlight $state")
                }
                // System / Apps
                "app" -> {
                    val appName = params["name"]?.toString() ?: ""
                    val res = systemTools.app(appName)
                    ToolResult(res["result"] == "success", res["message"] ?: res["output"] ?: "")
                }
                "media" -> {
                    val action = params["action"]?.toString() ?: "PAUSE"
                    val res = systemTools.media(action)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                "alarm" -> {
                    val hour    = params["hour"]?.toString()?.toIntOrNull() ?: 8
                    val minutes = params["minutes"]?.toString()?.toIntOrNull() ?: 0
                    val label   = params["label"]?.toString() ?: ""
                    val res = systemTools.alarm(hour, minutes, label)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                "timer" -> {
                    val seconds = params["seconds"]?.toString()?.toIntOrNull() ?: 60
                    val label   = params["label"]?.toString() ?: ""
                    val res = systemTools.timer(seconds, label)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                "calendar" -> {
                    val title   = params["title"]?.toString() ?: ""
                    val desc    = params["description"]?.toString() ?: ""
                    val minutes = params["minutes"]?.toString()?.toIntOrNull() ?: 30
                    val res = systemTools.calendar(title, desc, minutes)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                "read_calendar" -> {
                    val days = params["days"]?.toString()?.toIntOrNull() ?: 7
                    val res = systemTools.read_calendar(days)
                    ToolResult(res["result"] == "success", res["events"] ?: "")
                }
                // Screen / Accessibility
                "click" -> {
                    val target = params["target"]?.toString() ?: ""
                    val res = systemTools.click(target)
                    ToolResult(res["result"] == "success", "Clicked: $target")
                }
                "scroll" -> {
                    val direction = params["direction"]?.toString() ?: "DOWN"
                    val res = systemTools.scroll(direction)
                    ToolResult(res["result"] == "success", "Scrolled: $direction")
                }
                "navigate" -> {
                    val action = params["action"]?.toString() ?: "BACK"
                    val res = systemTools.navigate(action)
                    ToolResult(res["result"] == "success", "Navigate: $action")
                }
                "take_screenshot" -> {
                    val res = systemTools.take_screenshot()
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                // Memory
                "remember" -> {
                    val title   = params["title"]?.toString() ?: ""
                    val content = params["content"]?.toString() ?: ""
                    val res = systemTools.remember(title, content)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                "recall" -> {
                    val query = params["query"]?.toString() ?: ""
                    val res = systemTools.recall(query)
                    ToolResult(res["result"] == "success", res["memories"] ?: "")
                }
                // Shell (Termux pipe)
                "bash" -> {
                    val command = params["command"]?.toString() ?: ""
                    val res = systemTools.bash(command)
                    ToolResult(res["result"] == "success", res["output"] ?: res["message"] ?: "")
                }
                // Network
                "web_search" -> {
                    val query = params["query"]?.toString() ?: ""
                    val res = networkTools.web_search(query, 3)
                    ToolResult(res["result"] == "success", res["content"] ?: "")
                }
                // Skills
                "loadSkill" -> {
                    val skillName = params["name"]?.toString() ?: ""
                    val inst = skillManager.getSkillInstructions(skillName)
                    if (inst != null) ToolResult(true, inst) else ToolResult(false, "", "Skill not found: $skillName")
                }
                else -> {
                    Timber.w("MCPServer: Unknown tool requested: $name")
                    ToolResult(false, "", "Unknown tool: $name")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "MCPServer: Tool '$name' threw")
            ToolResult(false, "", e.message)
        }
    }
}
