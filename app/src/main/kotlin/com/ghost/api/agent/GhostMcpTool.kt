package com.ghost.api.agent

import android.content.Context
import com.ghost.api.logic.IntentHandler
import com.ghost.api.logic.JsExecutionBridge
import com.ghost.api.mcp.MCPServer
import com.ghost.api.skills.SkillManager
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import timber.log.Timber

/**
 * GhostMcpTool - Google ADK / Edge Gallery aligned Dynamic MCP Toolset.
 * Exposes a lean set of meta-tools (execute_action, runMcpTool, load_skill, run_js, run_intent)
 * to LiteRT-LM's C++ ANTLR grammar, dropping tool schema overhead from ~3,800 tokens to ~80 tokens.
 */
class GhostMcpTool(
    private val context: Context,
    private val mcpServer: MCPServer,
    private val skillManager: SkillManager,
    var onToolExecuting: ((toolName: String, params: String) -> Unit)? = null,
    var onToolExecuted: ((toolName: String, params: String, result: String) -> Unit)? = null
) : ToolSet {

    @Volatile var lastWebviewUrl: String? = null
    @Volatile var lastWebviewAspectRatio: Float? = null

    fun consumeLastWebview(): Pair<String?, Float?> {
        val result = Pair(lastWebviewUrl, lastWebviewAspectRatio)
        lastWebviewUrl = null
        lastWebviewAspectRatio = null
        return result
    }

    /** Turns on flashlight. Matches Google Edge Gallery MobileActionsTools. */
    @Tool(description = "Turns the flashlight on")
    fun turnOnFlashlight(): Map<String, String> {
        Timber.i("GhostMcpTool: turnOnFlashlight invoked")
        return executeMcpAction("flashlight", "{\"state\":\"ON\"}")
    }

    /** Turns off flashlight. Matches Google Edge Gallery MobileActionsTools. */
    @Tool(description = "Turns the flashlight off")
    fun turnOffFlashlight(): Map<String, String> {
        Timber.i("GhostMcpTool: turnOffFlashlight invoked")
        return executeMcpAction("flashlight", "{\"state\":\"OFF\"}")
    }

    @Tool(description = "Controls device flashlight (ON or OFF)")
    fun flashlight(
        @ToolParam(description = "State: ON or OFF") state: String
    ): Map<String, String> {
        Timber.i("GhostMcpTool: flashlight invoked with state=$state")
        return executeMcpAction("flashlight", "{\"state\":\"$state\"}")
    }

    @Tool(description = "DEFAULT SEARCH TOOL. Silently searches the web for fresh info, news, weather, or unknown topics.")
    fun search(
        @ToolParam(description = "Search query") query: String
    ): Map<String, String> {
        Timber.i("GhostMcpTool: search invoked with query='$query'")
        return executeMcpAction("search", "{\"query\":\"$query\"}")
    }

    @Tool(description = "Alias for search tool. Silently searches the web for information.")
    fun execute_background_search(
        @ToolParam(description = "Search query") query: String
    ): Map<String, String> {
        Timber.i("GhostMcpTool: execute_background_search invoked with query='$query'")
        return executeMcpAction("search", "{\"query\":\"$query\"}")
    }

    @Tool(description = "Consults a peer AI from Gemma's phonebook (Claude, DeepSeek, Gemini, Grok, Perplexity, Mistral, Copilot). Hands off prompt to official app or direct API. ONLY call when operator explicitly asks.")
    fun consult_peer(
        @ToolParam(description = "Peer name: Claude, DeepSeek, Gemini, Grok, Perplexity, Mistral, Copilot") peer: String,
        @ToolParam(description = "The prompt or question to ask the peer") prompt: String
    ): Map<String, String> {
        Timber.i("GhostMcpTool: consult_peer invoked for peer='$peer'")
        return executeMcpAction("consult_peer", "{\"peer\":\"$peer\",\"prompt\":\"$prompt\"}")
    }

    @Tool(description = "Execute an on-device action or MCP tool by name with parameters (JSON format).")
    fun execute_action(
        @ToolParam(description = "The exact name of the tool/action to execute (e.g. 'search', 'flashlight', 'set_edge_lights', 'app', 'media', 'alarm', 'timer', 'calendar', 'read_calendar', 'read_diary', 'remember', 'recall', 'search_files', 'list_files', 'open_file', 'click', 'scroll', 'navigate', 'consult_peer').") toolName: String,
        @ToolParam(description = "JSON object string with parameters (e.g. '{\"query\":\"London weather\"}', '{\"state\":\"ON\"}', '{\"name\":\"YouTube\"}').") parameters: String
    ): Map<String, String> {
        return executeMcpAction(toolName, parameters)
    }

    @Tool(description = "Run an MCP tool by name with parameters.")
    fun runMcpTool(
        @ToolParam(description = "The name of the tool to run.") toolName: String,
        @ToolParam(description = "The parameters passed to tool as input JSON string.") input: String
    ): Map<String, String> {
        return executeMcpAction(toolName, input)
    }

    @Tool(description = "Loads the detailed instructions and capabilities for a specific skill.")
    fun load_skill(
        @ToolParam(description = "The unique name of the skill to load (e.g., 'weather', 'calculator').") name: String
    ): Map<String, String> {
        Timber.i("GhostMcpTool: load_skill called for '$name'")
        val rawInstructions = skillManager.getSkillInstructions(name)
        return if (rawInstructions != null) {
            val instructions = rawInstructions.take(1400)
            com.ghost.api.GemmaService.instance?.recordToolOutput(instructions.length)
            Timber.i("Skill loaded: $name (${instructions.length} chars)")
            mapOf("result" to "success", "instructions" to instructions)
        } else {
            Timber.w("Skill not found: $name")
            mapOf("result" to "error", "message" to "Skill '$name' not found in registry.")
        }
    }

    @Tool(description = "Runs a JS script from a skill to perform complex calculations, fetch data, or return interactive UIs.")
    fun run_js(
        @ToolParam(description = "The name of the skill") skillName: String,
        @ToolParam(description = "The script name to run. Use 'index.html' if not provided by user") scriptName: String,
        @ToolParam(description = "The data to pass to the script as a JSON string. Use empty string if not provided.") data: String
    ): Map<String, String> {
        return runBlocking(Dispatchers.IO) {
            Timber.i("GhostMcpTool: run_js called for skill '$skillName', script '$scriptName'")
            val skill = skillManager.getSkill(skillName.trim())
            if (skill == null) {
                Timber.w("Skill not found: $skillName")
                return@runBlocking mapOf("error" to "Skill '$skillName' not found")
            }

            val scriptPath = if (scriptName.isNotBlank() && scriptName != "null") scriptName else "index.html"
            val url = "file://${skill.path}/scripts/$scriptPath"
            val secret = ""

            val resultJsonString = JsExecutionBridge.executeJs(context, url, data, secret)

            try {
                val json = JSONObject(resultJsonString)
                if (json.has("error")) {
                    return@runBlocking mapOf("error" to json.getString("error"))
                }
                if (json.has("webview")) {
                    val webviewObj = json.getJSONObject("webview")
                    val webviewUrl = webviewObj.optString("url", "")
                    val absoluteWebviewUrl = if (webviewUrl.startsWith("http")) webviewUrl
                                             else "file://${skill.path}/assets/$webviewUrl"
                    Timber.i("GhostMcpTool: Sending Webview request to UI: $absoluteWebviewUrl")
                    lastWebviewUrl = absoluteWebviewUrl
                    lastWebviewAspectRatio = webviewObj.optDouble("aspectRatio", 1.333).toFloat()
                    return@runBlocking mapOf("result" to json.optString("result", "Interactive view loaded."))
                }
                return@runBlocking mapOf("result" to json.optString("result", "Success"))
            } catch (e: Exception) {
                return@runBlocking mapOf("result" to resultJsonString)
            }
        }
    }

    @Tool(description = "Run an Android intent to interact with the OS.")
    fun run_intent(
        @ToolParam(description = "The intent to run") intent: String,
        @ToolParam(description = "A JSON string containing the parameter values required for the intent.") parameters: String
    ): Map<String, String> {
        Timber.i("GhostMcpTool: run_intent called for intent '$intent'")
        val success = IntentHandler.handleAction(context, intent, parameters)
        return if (success) {
            mapOf("action" to intent, "result" to "succeeded")
        } else {
            mapOf("action" to intent, "result" to "failed", "error" to "Intent implementation missing or failed")
        }
    }

    private fun executeMcpAction(toolName: String, rawParams: String): Map<String, String> {
        val cleanTool = toolName.trim()
        val cleanParams = rawParams.trim()
        Timber.i("GhostMcpTool: dispatching '$cleanTool' with '$cleanParams'")
        onToolExecuting?.invoke(cleanTool, cleanParams)

        val paramsMap = parseParams(cleanTool, cleanParams)
        val result = runBlocking(Dispatchers.IO) {
            mcpServer.executeTool(cleanTool, paramsMap)
        }
        val rawOutput = if (result.success) result.output else (result.error ?: "Action failed")
        val outputStr = rawOutput.take(1500)
        com.ghost.api.GemmaService.instance?.recordToolOutput(outputStr.length)
        onToolExecuted?.invoke(cleanTool, cleanParams, outputStr)

        return mapOf(
            "result" to if (result.success) "success" else "error",
            "output" to outputStr
        )
    }

    private fun parseParams(toolName: String, rawParams: String): Map<String, Any> {
        val trimmed = rawParams.trim()
        if (trimmed.isEmpty() || trimmed == "{}" || trimmed == "null") return emptyMap()

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val map = mutableMapOf<String, Any>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = json.get(key)
                }
                return map
            } catch (e: Exception) {
                Timber.w(e, "GhostMcpTool: Failed to parse JSON parameters, using heuristic")
            }
        }

        val plain = trimmed.removeSurrounding("\"")
        return when (toolName.lowercase()) {
            "flashlight", "set_edge_lights" -> mapOf("state" to plain)
            "app" -> mapOf("name" to plain)
            "media", "navigate" -> mapOf("action" to plain)
            "timer" -> mapOf("seconds" to (plain.toIntOrNull() ?: 60))
            "recall", "search", "web_search", "google", "execute_background_search", "search_files" -> mapOf("query" to plain)
            "fetch_webpage", "fetchwebpage" -> mapOf("url" to plain)
            "list_files" -> mapOf("folder" to plain)
            "read_calendar", "read_diary" -> mapOf("days" to (plain.toIntOrNull() ?: 7))
            "read_file_text", "open_file", "delete_file" -> mapOf("filePath" to plain)
            "loadskill", "load_skill" -> mapOf("name" to plain)
            "consult_peer", "consultpeer" -> {
                val peer = plain.substringBefore(" ").trim()
                val prompt = plain.substringAfter(" ").trim()
                mapOf("peer" to peer, "prompt" to prompt)
            }
            else -> mapOf("input" to plain)
        }
    }
}
