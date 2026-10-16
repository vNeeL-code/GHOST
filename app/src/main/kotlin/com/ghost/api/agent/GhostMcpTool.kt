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

    /** Turns on flashlight. Helper method. */
    fun turnOnFlashlight(): Map<String, String> {
        Timber.i("GhostMcpTool: turnOnFlashlight invoked")
        return toggle_torch(true)
    }

    /** Turns off flashlight. Helper method. */
    fun turnOffFlashlight(): Map<String, String> {
        Timber.i("GhostMcpTool: turnOffFlashlight invoked")
        return toggle_torch(false)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CORE 6 NATIVE TOOLS (Exposed to LiteRT-LM reflection schema)
    // ─────────────────────────────────────────────────────────────────────────────

    @Tool(description = "Launches an installed Android application by name or label (e.g. 'YouTube Music', 'Calendar', 'Camera', 'Chrome', 'Clock', 'Settings', 'Files').")
    fun open_app(
        @ToolParam(description = "Name or label of the application to launch") name: String
    ): Map<String, String> {
        Timber.i("GhostMcpTool: open_app invoked with name='$name'")
        return executeMcpAction("app", "{\"name\":\"$name\"}")
    }

    @JvmOverloads
    @Tool(description = "Sets an alarm for a specific time via the system Clock app. Hour must strictly be in 24-hour format 0-23 (e.g. 20 for 8 PM, 8 for 8 AM, 0 for midnight).")
    fun set_alarm(
        @ToolParam(description = "Strictly 24-hour format hour (0 to 23, e.g. 20 for 8 PM, 8 for 8 AM)") hour: Int,
        @ToolParam(description = "Minute (0 to 59, default: 0)") minute: Int = 0
    ): Map<String, String> {
        Timber.i("GhostMcpTool: set_alarm invoked with hour=$hour, minute=$minute")
        return executeMcpAction("alarm", "{\"hour\":$hour,\"minutes\":$minute}")
    }

    @Tool(description = "Controls device flashlight (true for ON, false for OFF).")
    fun toggle_torch(
        @ToolParam(description = "true to turn torch ON, false to turn torch OFF") enabled: Boolean
    ): Map<String, String> {
        val state = if (enabled) "ON" else "OFF"
        Timber.i("GhostMcpTool: toggle_torch invoked with enabled=$enabled (state=$state)")
        return executeMcpAction("flashlight", "{\"state\":\"$state\"}")
    }

    @Tool(description = "DEFAULT SEARCH TOOL. Silently searches the web for fresh info, news, weather, or unknown topics.")
    fun search(
        @ToolParam(description = "Search query") query: String
    ): Map<String, String> {
        Timber.i("GhostMcpTool: search invoked with query='$query'")
        return executeMcpAction("search", "{\"query\":\"$query\"}")
    }

    @JvmOverloads
    @Tool(description = "Executes an on-device system or utility command (e.g. 'volume', 'status', 'notifications', 'music', 'calendar', 'timer', 'search_files'). Arguments are passed as key-value pairs (e.g. 'stream=media level=50', 'action=pause', 'seconds=300' for 5m, 'seconds=3600' for 1h) or JSON.")
    fun execute_command(
        @ToolParam(description = "Command name (e.g. 'volume', 'status', 'notifications', 'music', 'calendar', 'timer', 'search_files', 'read_diary')") command: String,
        @ToolParam(description = "Arguments string, e.g. 'stream=media level=50', 'action=next', or 'seconds=3600' (1 hour = 3600, 1 minute = 60)") args: String = ""
    ): Map<String, String> {
        Timber.i("GhostMcpTool: execute_command invoked with command='$command', args='$args'")
        return executeMcpAction(command, args)
    }

    @Tool(description = "Loads the detailed instructions and capabilities for a specific skill.")
    fun load_skill(
        @ToolParam(description = "The unique name of the skill to load (e.g., 'weather', 'calculator').") skill: String
    ): Map<String, String> {
        Timber.i("GhostMcpTool: load_skill called for '$skill'")
        val rawInstructions = skillManager.getSkillInstructions(skill)
        return if (rawInstructions != null) {
            val instructions = rawInstructions.take(1400)
            com.ghost.api.GemmaService.instance?.recordToolOutput(instructions.length)
            Timber.i("Skill loaded: $skill (${instructions.length} chars)")
            mapOf("result" to "success", "instructions" to instructions)
        } else {
            Timber.w("Skill not found: $skill")
            mapOf("result" to "error", "message" to "Skill '$skill' not found in registry.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // UN-ANNOTATED HELPER / FALLBACK ALIASES (Preserved without LiteRT schema bloat)
    // ─────────────────────────────────────────────────────────────────────────────

    fun flashlight(state: String): Map<String, String> =
        executeMcpAction("flashlight", "{\"state\":\"$state\"}")

    fun app(name: String): Map<String, String> = open_app(name)

    fun media(action: String): Map<String, String> =
        executeMcpAction("media", "{\"action\":\"$action\"}")

    fun consult_peer(peer: String, prompt: String): Map<String, String> =
        executeMcpAction("consult_peer", "{\"peer\":\"$peer\",\"prompt\":\"$prompt\"}")

    @JvmOverloads
    fun alarm(hour: Int, minutes: Int = 0, label: String = ""): Map<String, String> {
        Timber.i("GhostMcpTool: alarm helper invoked with hour=$hour, minutes=$minutes, label='$label'")
        return executeMcpAction("alarm", "{\"hour\":$hour,\"minutes\":$minutes,\"label\":\"$label\"}")
    }

    @JvmOverloads
    fun timer(seconds: Int, label: String = ""): Map<String, String> {
        Timber.i("GhostMcpTool: timer helper invoked with seconds=$seconds, label='$label'")
        return executeMcpAction("timer", "{\"seconds\":$seconds,\"label\":\"$label\"}")
    }

    fun calendar(title: String, description: String = "", minutes: Int = 30): Map<String, String> =
        executeMcpAction("calendar", "{\"title\":\"$title\",\"description\":\"$description\",\"minutes\":$minutes}")

    fun search_files(query: String): Map<String, String> =
        executeMcpAction("search_files", "{\"query\":\"$query\"}")

    fun list_files(folder: String = "downloads"): Map<String, String> =
        executeMcpAction("list_files", "{\"folder\":\"$folder\"}")

    fun open_file(filePath: String): Map<String, String> =
        executeMcpAction("open_file", "{\"filePath\":\"$filePath\"}")

    fun move_file(sourcePath: String, destinationPath: String): Map<String, String> =
        executeMcpAction("move_file", "{\"sourcePath\":\"$sourcePath\",\"destinationPath\":\"$destinationPath\"}")

    fun execute_action(toolName: String, parameters: String, preApproved: Boolean = false): Map<String, String> =
        executeMcpAction(toolName, parameters, preApproved)

    @JvmOverloads
    @Tool(description = "Runs a JavaScript skill script (e.g. from a loaded skill such as 'qr-code', 'interactive-map', 'mood-tracker', 'query-wikipedia', 'calculate-hash', 'text-spinner') and returns the result or interactive view.")
    fun run_js(
        @ToolParam(description = "The name of the skill (e.g. 'qr-code', 'interactive-map', 'query-wikipedia').") skillName: String,
        @ToolParam(description = "The script file to run, defaults to 'index.html'.") scriptName: String = "index.html",
        @ToolParam(description = "A JSON string of parameters for the script (e.g. '{\"url\":\"https://example.com\"}').") data: String = "{}"
    ): Map<String, String> {
        return runBlocking(Dispatchers.IO) {
            val normalizedSkill = skillName.trim().lowercase().replace("_", "-")
            Timber.i("GhostMcpTool: run_js called for skill '$normalizedSkill', script '$scriptName', data='$data'")
            onToolExecuting?.invoke("run_js", normalizedSkill)

            // Fast native offline QR Code generation (zero CDN, 100% offline FOSS)
            if (normalizedSkill == "qr-code" || normalizedSkill == "qrcode" || normalizedSkill == "qr") {
                return@runBlocking try {
                    val targetText = try {
                        val j = JSONObject(data)
                        j.optString("url").ifBlank { j.optString("text").ifBlank { j.optString("query") } }
                    } catch (_: Exception) {
                        Regex("""https?://[^\s"'\}]+""").find(data)?.value ?: data.trim('"', '\'', '{', '}', ' ')
                    }.ifBlank { "https://github.com/vNeeL-code/GHOST" }

                    val imgSrc = com.ghost.api.skills.QrCodeGenerator.generatePngBase64(targetText, 512)
                    val htmlFile = java.io.File(context.cacheDir, "skill_qr_${System.currentTimeMillis()}.html")
                    htmlFile.writeText(
                        """
                        <!DOCTYPE html>
                        <html><head><meta name="viewport" content="width=device-width,initial-scale=1.0,user-scalable=no">
                        <style>
                          html, body {
                            margin: 0;
                            padding: 0;
                            width: 100%;
                            height: 100%;
                            background: #10131C;
                            color: #E2E8F0;
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                            display: flex;
                            flex-direction: column;
                            align-items: center;
                            justify-content: center;
                            box-sizing: border-box;
                            overflow: hidden;
                          }
                          img {
                            width: 170px;
                            height: 170px;
                            border-radius: 12px;
                            background: #ffffff;
                            padding: 8px;
                            box-sizing: border-box;
                            box-shadow: 0 4px 16px rgba(0,0,0,0.5);
                            image-rendering: pixelated;
                          }
                          .lbl {
                            margin-top: 8px;
                            font-size: 11px;
                            color: #94A3B8;
                            word-break: break-all;
                            text-align: center;
                            max-width: 90%;
                            line-height: 1.2;
                          }
                        </style>
                        </head><body>
                          <img src="$imgSrc" alt="QR Code"/>
                          <div class="lbl">${targetText.replace("<", "&lt;").replace(">", "&gt;")}</div>
                        </body></html>
                        """.trimIndent()
                    )
                    lastWebviewUrl = imgSrc
                    lastWebviewAspectRatio = 1.0f
                    val out = "Generated QR code for $targetText"
                    onToolExecuted?.invoke("run_js", "qr-code", out)
                    mapOf("result" to out)
                } catch (e: Exception) {
                    Timber.e(e, "GhostMcpTool: Native QR generation failed")
                    mapOf("error" to "Failed to generate QR code: ${e.message}")
                }
            }

            val skill = skillManager.getSkill(normalizedSkill) ?: skillManager.getSkill(skillName.trim())
            if (skill == null) {
                Timber.w("Skill not found: $skillName")
                return@runBlocking mapOf("error" to "Skill '$skillName' not found")
            }

            val scriptPath = if (scriptName.isNotBlank() && scriptName != "null") scriptName else "index.html"
            val baseUrl = if (skill.path.startsWith("file://")) skill.path else "file://${skill.path}"
            val url = "$baseUrl/scripts/$scriptPath"
            val secret = ""

            val resultJsonString = JsExecutionBridge.executeJs(context, url, data, secret)
            com.ghost.api.GemmaService.instance?.recordToolOutput(resultJsonString.length.coerceAtMost(1500))

            try {
                val json = JSONObject(resultJsonString)
                if (json.has("error") && !json.isNull("error") && json.optString("error").isNotBlank()) {
                    return@runBlocking mapOf("error" to json.getString("error"))
                }
                if (json.has("webview") && !json.isNull("webview")) {
                    val webviewObj = json.getJSONObject("webview")
                    val webviewUrl = webviewObj.optString("url", "")
                    val absoluteWebviewUrl = when {
                        webviewUrl.startsWith("http") || webviewUrl.startsWith("file://") -> webviewUrl
                        webviewUrl.startsWith("../assets/") -> "$baseUrl/assets/${webviewUrl.removePrefix("../assets/")}"
                        webviewUrl.startsWith("assets/") -> "$baseUrl/$webviewUrl"
                        else -> "$baseUrl/assets/$webviewUrl"
                    }
                    Timber.i("GhostMcpTool: Sending Webview request to UI: $absoluteWebviewUrl")
                    lastWebviewUrl = absoluteWebviewUrl
                    lastWebviewAspectRatio = webviewObj.optDouble("aspectRatio", 1.333).toFloat()
                    val out = json.optString("result", "Interactive view loaded.")
                    onToolExecuted?.invoke("run_js", skillName, out)
                    return@runBlocking mapOf("result" to out)
                }
                if (json.has("image") && !json.isNull("image")) {
                    val imageObj = json.getJSONObject("image")
                    val base64 = imageObj.optString("base64", "")
                    if (base64.isNotBlank()) {
                        val imgSrc = if (base64.startsWith("data:image")) base64 else "data:image/png;base64,$base64"
                        val htmlFile = java.io.File(context.cacheDir, "skill_img_${System.currentTimeMillis()}.html")
                        htmlFile.writeText(
                            """
                            <!DOCTYPE html>
                            <html><head><meta name="viewport" content="width=device-width,initial-scale=1.0">
                            <style>body{margin:0;background:#10131C;display:flex;align-items:center;justify-content:center;height:100vh;}img{max-width:92%;max-height:92%;border-radius:12px;background:#fff;padding:8px;}</style>
                            </head><body><img src="$imgSrc"/></body></html>
                            """.trimIndent()
                        )
                        lastWebviewUrl = "file://${htmlFile.absolutePath}"
                        lastWebviewAspectRatio = 1.0f
                    }
                }
                val out = json.optString("result", resultJsonString).take(1400)
                onToolExecuted?.invoke("run_js", skillName, out)
                return@runBlocking mapOf("result" to out)
            } catch (e: Exception) {
                val out = resultJsonString.take(1400)
                onToolExecuted?.invoke("run_js", skillName, out)
                return@runBlocking mapOf("result" to out)
            }
        }
    }

    @JvmOverloads
    @Tool(description = "Runs a native Android or GHOST intent action as instructed by a loaded skill (e.g. 'send_email', 'timer', 'alarm', 'flashlight', 'media', 'app').")
    fun run_intent(
        @ToolParam(description = "The intent name to execute (e.g. 'send_email', 'timer', 'alarm').") intent: String,
        @ToolParam(description = "A JSON string containing the parameter values required for the intent.") parameters: String = "{}"
    ): Map<String, String> {
        Timber.i("GhostMcpTool: run_intent called for intent '$intent' with parameters='$parameters'")
        val normalized = intent.trim().lowercase()
        // Route native GHOST MCP tools directly if the skill references them via run_intent
        val ghostMcpTools = setOf(
            "timer", "alarm", "flashlight", "app", "open_app", "media", "calendar",
            "read_calendar", "search", "recall", "remember", "click", "scroll",
            "navigate", "take_screenshot", "bash"
        )
        if (normalized in ghostMcpTools) {
            return executeMcpAction(normalized, parameters)
        }
        val success = IntentHandler.handleAction(context, intent, parameters)
        return if (success) {
            mapOf("action" to intent, "result" to "succeeded")
        } else {
            mapOf("action" to intent, "result" to "failed", "error" to "Intent implementation missing or failed")
        }
    }

    fun executeMcpAction(toolName: String, rawParams: String, preApproved: Boolean = false): Map<String, String> {
        val trimmedTool = toolName.trim()
        val cleanTool = when (trimmedTool.lowercase()) {
            "open_app", "launch_app" -> "app"
            else -> trimmedTool
        }
        val cleanParams = rawParams.trim()
        Timber.i("GhostMcpTool: dispatching '$cleanTool' with '$cleanParams' (preApproved=$preApproved)")

        val paramsMap = parseParams(cleanTool, cleanParams)
        when (cleanTool.lowercase()) {
            "load_skill", "loadskill" -> {
                val name = (paramsMap["skill"] ?: paramsMap["name"] ?: paramsMap["input"] ?: cleanParams).toString()
                return load_skill(name)
            }
            "run_js", "runjs" -> {
                val skillName = (paramsMap["skillName"] ?: paramsMap["skill"] ?: paramsMap["name"] ?: "").toString()
                val scriptName = (paramsMap["scriptName"] ?: paramsMap["script"] ?: "index.html").toString()
                val dataObj = paramsMap["data"]
                val dataStr = when (dataObj) {
                    is JSONObject -> dataObj.toString()
                    is Map<*, *> -> JSONObject(dataObj).toString()
                    null -> cleanParams
                    else -> dataObj.toString()
                }
                return run_js(skillName, scriptName, dataStr)
            }
            "run_intent", "runintent" -> {
                val intentName = (paramsMap["intent"] ?: paramsMap["action"] ?: "").toString()
                val paramObj = paramsMap["parameters"]
                val paramStr = when (paramObj) {
                    is JSONObject -> paramObj.toString()
                    is Map<*, *> -> JSONObject(paramObj).toString()
                    null -> cleanParams
                    else -> paramObj.toString()
                }
                return run_intent(intentName, paramStr)
            }
            "save_skill", "saveskill" -> {
                val name = (paramsMap["name"] ?: "").toString()
                val desc = (paramsMap["description"] ?: "Custom skill").toString()
                val inst = (paramsMap["instructions"] ?: paramsMap["content"] ?: "").toString()
                val ok = skillManager.saveNewSkill(name, desc, inst)
                return if (ok) {
                    mapOf("result" to "success", "output" to "Saved skill '$name' to Documents/GHOST/skills/$name/SKILL.md")
                } else {
                    mapOf("result" to "error", "output" to "Failed to save skill '$name'")
                }
            }
        }

        onToolExecuting?.invoke(cleanTool, cleanParams)

        val result = runBlocking(Dispatchers.IO) {
            mcpServer.executeTool(cleanTool, paramsMap, preApproved = preApproved)
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
                Timber.w(e, "GhostMcpTool: Failed to parse JSON parameters, attempting raw key-value recovery")
                val inner = trimmed.removePrefix("{").removeSuffix("}").trim()
                val recovered = parseRawToolArgs(inner)
                if (recovered.isNotEmpty()) {
                    Timber.i("GhostMcpTool: Successfully recovered ${recovered.size} parameters from unquoted syntax")
                    return recovered
                }
            }
        } else if (trimmed.contains("=") || trimmed.contains(":")) {
            val recovered = parseRawToolArgs(trimmed)
            if (recovered.isNotEmpty()) {
                Timber.i("GhostMcpTool: Successfully parsed ${recovered.size} unbracketed parameters for $toolName")
                return recovered
            }
        }

        val plain = trimmed.removeSurrounding("\"")
        return when (toolName.lowercase()) {
            "flashlight", "set_edge_lights" -> mapOf("state" to plain)
            "app", "open_app", "launch_app" -> mapOf("name" to plain)
            "media", "navigate" -> mapOf("action" to plain)
            "timer" -> {
                val parsedSec = com.ghost.api.hardware.SystemToolSet.parseDurationSeconds(plain) ?: plain.toIntOrNull()
                if (parsedSec != null && parsedSec > 0) mapOf("seconds" to parsedSec, "input" to plain) else mapOf("input" to plain)
            }
            "alarm" -> mapOf("input" to plain)
            "calendar" -> mapOf("title" to plain)
            "remember" -> mapOf("content" to plain)
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

    /**
     * Auto-heals malformed/unquoted tool call arguments (e.g. `{hour: 18, label: Debug GHOST}` or `hour = 20, minutes = 0`)
     * when standard JSON parsers fail.
     */
    private fun parseRawToolArgs(rawArgs: String): MutableMap<String, Any> {
        val params = mutableMapOf<String, Any>()
        if (rawArgs.isBlank()) return params

        val keyPattern = Regex("""([a-zA-Z0-9_]+)\s*[:=]\s*""")
        val matches = keyPattern.findAll(rawArgs).toList()

        for (i in matches.indices) {
            val key = matches[i].groupValues[1]
            val startIndex = matches[i].range.last + 1
            val endIndex = if (i + 1 < matches.size) {
                val nextKeyStart = matches[i + 1].range.first
                var end = nextKeyStart
                while (end > startIndex && (rawArgs[end - 1] == ',' || rawArgs[end - 1].isWhitespace())) {
                    end--
                }
                end
            } else {
                rawArgs.length
            }

            var value = rawArgs.substring(startIndex, endIndex).trim()
            value = value.trim('"', '\'', ' ', ',')
            // Attempt to preserve integer types for numeric parameters
            val intVal = value.toIntOrNull()
            if (intVal != null) {
                params[key] = intVal
            } else {
                params[key] = value
            }
        }
        return params
    }
}
