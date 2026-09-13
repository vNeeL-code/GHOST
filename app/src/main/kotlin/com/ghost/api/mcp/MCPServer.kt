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
    private val uiMacroTools: com.ghost.api.hardware.UiMacroToolSet,
    private val termuxTools: com.ghost.api.hardware.TermuxAdbToolSet,
    private val audioRecorder: AudioRecorder,
    val sensorManager: SensorFusionManager,
    private val memoryManager: MemoryManager,
    private val skillManager: SkillManager,
    private val fileTools: com.ghost.api.hardware.FileToolSet = com.ghost.api.hardware.FileToolSet(context)
) {
    
    data class ToolDefinition(val name: String, val description: String, val parameters: Map<String, ParameterSpec>)
    data class ParameterSpec(val type: String, val description: String, val required: Boolean = true, val enum: List<String>? = null)
    data class ToolResult(val success: Boolean, val output: String, val error: String? = null)
    
    private val toolRegistry = mapOf(
        // Hardware
        "flashlight"      to ToolDefinition("flashlight", "Toggle the device flashlight", mapOf("state" to ParameterSpec("string", "ON or OFF"))),
        "set_edge_lights" to ToolDefinition("set_edge_lights", "Controls or toggles ambient screen equalizer edge lights rim lighting", mapOf("state" to ParameterSpec("string", "ON, OFF, or TOGGLE"))),
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

        // Memory
        "remember"        to ToolDefinition("remember", "Store a memory", mapOf(
            "title"   to ParameterSpec("string", "Memory title"),
            "content" to ParameterSpec("string", "Memory content")
        )),
        "recall"          to ToolDefinition("recall", "Search stored memories", mapOf("query" to ParameterSpec("string", "Search query"))),
        // Shell
        "bash"            to ToolDefinition("bash", "Run a shell command (Termux pipe)", mapOf("command" to ParameterSpec("string", "Shell command"))),
        "execute_background_search" to ToolDefinition("execute_background_search", "DEFAULT SEARCH TOOL. Use naturally to silently scrape the web for info or unknown topics.", mapOf("query" to ParameterSpec("string", "Search query"))),
        "open_system_browser_bar" to ToolDefinition("open_system_browser_bar", "Open URL or search in system browser", mapOf("queryOrUrl" to ParameterSpec("string", "Search query or URL"))),
        // File Management & Search
        "search_files"    to ToolDefinition("search_files", "Search device files with fuzzy keywords, syllables, or extensions", mapOf(
            "query" to ParameterSpec("string", "Keyword, syllable, or extension"),
            "type"  to ParameterSpec("string", "Filter ('audio', 'video', 'image', 'doc', 'any')", required = false)
        )),
        "list_files"      to ToolDefinition("list_files", "List files in a folder or category (downloads, music, documents, etc.)", mapOf(
            "target"    to ParameterSpec("string", "Category or directory path", required = false),
            "extension" to ParameterSpec("string", "Extension filter (e.g. mp3, pdf, all)", required = false),
            "limit"     to ParameterSpec("integer", "Max items to return", required = false)
        )),
        "move_file"       to ToolDefinition("move_file", "Move or rename a file to a new path or directory", mapOf(
            "sourcePath"      to ParameterSpec("string", "Source file absolute path"),
            "destinationPath" to ParameterSpec("string", "Destination file or directory path")
        )),
        "copy_file"       to ToolDefinition("copy_file", "Copy a file to a destination path or directory", mapOf(
            "sourcePath"      to ParameterSpec("string", "Source file absolute path"),
            "destinationPath" to ParameterSpec("string", "Destination file or directory path")
        )),
        "delete_file"     to ToolDefinition("delete_file", "Delete a file at the specified path", mapOf(
            "filePath" to ParameterSpec("string", "Absolute path of file to delete")
        )),
        "get_file_info"   to ToolDefinition("get_file_info", "Get detailed metadata for a file (size, modified date, MIME)", mapOf(
            "filePath" to ParameterSpec("string", "Absolute path of file")
        )),
        "open_file"       to ToolDefinition("open_file", "Open a file with system handler or specific app", mapOf(
            "filePath" to ParameterSpec("string", "Absolute path of file to open"),
            "appName"  to ParameterSpec("string", "Optional app name (e.g. VLC)", required = false)
        )),
        "read_file_text"  to ToolDefinition("read_file_text", "Read text content of a file", mapOf(
            "filePath" to ParameterSpec("string", "Absolute path of text file")
        )),
        // AI Phonebook (Extend Your Mind)
        "consult_peer"    to ToolDefinition("consult_peer", "Consult a peer AI from Gemma's phonebook", mapOf(
            "peer"   to ParameterSpec("string", "Peer name or callsign: Claude, DeepSeek, Gemini, Grok, Perplexity, Kimi, Qwen, Mistral, Copilot, ChatGPT, Meta, GLM"),
            "prompt" to ParameterSpec("string", "Question or task to consult them on")
        )),
        "consultpeer"     to ToolDefinition("consultpeer", "Alias for consult_peer", mapOf(
            "peer"   to ParameterSpec("string", "Peer name or callsign"),
            "prompt" to ParameterSpec("string", "Question or task to consult them on")
        )),

        // Skills
        "loadSkill"       to ToolDefinition("loadSkill", "Load skill instructions by name", mapOf("name" to ParameterSpec("string", "Skill name")))
    )

    fun getTools(): Map<String, ToolDefinition> = toolRegistry

    suspend fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        return try {
            when (name) {
                // Hardware
                "flashlight" -> {
                    val state = params["state"]?.toString()?.uppercase() ?: "OFF"
                    hardwareTools.flashlight(state)
                    ToolResult(true, "Flashlight $state")
                }
                "set_edge_lights" -> {
                    val state = params["state"]?.toString() ?: "TOGGLE"
                    val res = hardwareTools.set_edge_lights(state)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
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
                    val res = uiMacroTools.click(target)
                    ToolResult(res["result"] == "success", "Clicked: $target")
                }
                "scroll" -> {
                    val direction = params["direction"]?.toString() ?: "DOWN"
                    val res = uiMacroTools.scroll(direction)
                    ToolResult(res["result"] == "success", "Scrolled: $direction")
                }
                "navigate" -> {
                    val action = params["action"]?.toString() ?: "BACK"
                    val res = uiMacroTools.navigate(action)
                    ToolResult(res["result"] == "success", "Navigate: $action")
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
                    val res = termuxTools.bash(command)
                    ToolResult(res["result"] == "success", res["output"] ?: res["message"] ?: "")
                }
                "execute_background_search" -> {
                    val query = params["query"]?.toString() ?: ""
                    val res = networkTools.execute_background_search(query, 3)
                    ToolResult(res["result"] == "success", res["content"] ?: "")
                }
                "open_system_browser_bar" -> {
                    val queryOrUrl = params["queryOrUrl"]?.toString() ?: ""
                    val res = networkTools.open_system_browser_bar(queryOrUrl)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                // Messaging
                "reply_notification" -> {
                    val pkg = params["packageName"]?.toString() ?: ""
                    val msg = params["message"]?.toString() ?: ""
                    val res = systemTools.reply_notification(pkg, msg)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                "send_whatsapp_message" -> {
                    val phone = params["phoneNumber"]?.toString() ?: ""
                    val msg = params["message"]?.toString() ?: ""
                    val res = systemTools.send_whatsapp_message(msg, phone)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                // File Management & Search
                "search_files" -> {
                    val query = params["query"]?.toString() ?: ""
                    val res = fileTools.search_files(query)
                    ToolResult(res["result"] == "success", res["files"] ?: res["message"] ?: "")
                }
                "list_files" -> {
                    val folder = params["folder"]?.toString() ?: params["target"]?.toString() ?: "downloads"
                    val res = fileTools.list_files(folder)
                    ToolResult(res["result"] == "success", res["files"] ?: res["message"] ?: "")
                }
                "move_file" -> {
                    val src = params["sourcePath"]?.toString() ?: ""
                    val dst = params["destinationPath"]?.toString() ?: ""
                    val res = fileTools.move_file(src, dst)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                "copy_file" -> {
                    val src = params["sourcePath"]?.toString() ?: ""
                    val dst = params["destinationPath"]?.toString() ?: ""
                    val res = fileTools.copy_file(src, dst)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                "delete_file" -> {
                    val path = params["filePath"]?.toString() ?: ""
                    val res = fileTools.delete_file(path)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                "get_file_info" -> {
                    val path = params["filePath"]?.toString() ?: ""
                    val res = fileTools.get_file_info(path)
                    ToolResult(res["result"] == "success", res.toString())
                }
                "open_file" -> {
                    val path = params["filePath"]?.toString() ?: ""
                    val res = fileTools.open_file(path)
                    ToolResult(res["result"] == "success", res["message"] ?: "")
                }
                "read_file_text" -> {
                    val path = params["filePath"]?.toString() ?: ""
                    val res = fileTools.read_file_text(path)
                    ToolResult(res["result"] == "success", res["content"] ?: res["message"] ?: "")
                }
                // AI Phonebook (Extend Your Mind)
                "consult_peer", "consultpeer" -> {
                    val peer = params["peer"]?.toString() ?: ""
                    val prompt = params["prompt"]?.toString() ?: params["query"]?.toString() ?: ""
                    val res = networkTools.consult_peer(peer, prompt)
                    ToolResult(res["result"] == "success", res["content"] ?: res["message"] ?: "")
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
