package com.ghost.api.skills

import android.content.Context
import timber.log.Timber
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.ghost.api.logic.JsExecutionBridge
import com.ghost.api.logic.IntentHandler
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * AgentToolSet - The Gallery-Main equivalent for GHOST
 * Exposes 'run_js' and 'run_intent' directly to the Gemma Engine,
 * fulfilling the SKILL.md system prompt requirements.
 */
class AgentToolSet(
    private val context: Context,
    private val skillManager: SkillManager
) : ToolSet {

    @Volatile var lastWebviewUrl: String? = null
    @Volatile var lastWebviewAspectRatio: Float? = null

    fun consumeLastWebview(): Pair<String?, Float?> {
        val result = Pair(lastWebviewUrl, lastWebviewAspectRatio)
        lastWebviewUrl = null
        lastWebviewAspectRatio = null
        return result
    }

    @Tool(description = "Runs a JS script from a skill to perform complex calculations, fetch data, or return interactive UIs.")
    fun runJs(
        @ToolParam(description = "The name of the skill") skillName: String,
        @ToolParam(description = "The script name to run. Use 'index.html' if not provided by user") scriptName: String,
        @ToolParam(description = "The data to pass to the script as a JSON string. Use empty string if not provided.") data: String
    ): Map<String, String> {
        return runBlocking {
            Timber.i("AgentToolSet: run_js called for skill '$skillName', script '$scriptName'")

            val skill = skillManager.getSkill(skillName.trim())
            if (skill == null) {
                Timber.w("Skill not found: $skillName")
                return@runBlocking mapOf("error" to "Skill '$skillName' not found")
            }

            // Construct the URL to the index.html.
            // In GHOST, skill.path is the directory path containing SKILL.md.
            // The JS execution point is typically in scripts/index.html.
            val scriptPath = if (scriptName.isNotBlank() && scriptName != "null") scriptName else "index.html"
            val url = "file://${skill.path}/scripts/$scriptPath"

            // GHOST does not currently implement blocking UI for secrets natively here, 
            // but we can pass an empty secret or read from a vault if implemented later.
            val secret = ""

            // Execute the JS
            val resultJsonString = JsExecutionBridge.executeJs(context, url, data, secret)

            try {
                val json = JSONObject(resultJsonString)
                if (json.has("error")) {
                    return@runBlocking mapOf("error" to json.getString("error"))
                }
                
                // If it returns an image or webview, we package it so the Chat UI can extract it later
                if (json.has("webview")) {
                    val webviewObj = json.getJSONObject("webview")
                    val webviewUrl = webviewObj.optString("url", "")
                    
                    // We must resolve the absolute path for the webview UI
                    val absoluteWebviewUrl = if (webviewUrl.startsWith("http")) webviewUrl 
                                             else "file://${skill.path}/assets/$webviewUrl"
                                             
                    Timber.i("AgentToolSet: Sending Webview request to UI: $absoluteWebviewUrl")
                    
                    lastWebviewUrl = absoluteWebviewUrl
                    lastWebviewAspectRatio = webviewObj.optDouble("aspectRatio", 1.333).toFloat()
                    
                    return@runBlocking mapOf(
                        "result" to json.optString("result", "Interactive view loaded.")
                    )
                }

                return@runBlocking mapOf("result" to json.optString("result", "Success"))
            } catch (e: Exception) {
                // If the return is not JSON, return it raw
                return@runBlocking mapOf("result" to resultJsonString)
            }
        }
    }

    @Tool(description = "Run an Android intent. It is used to interact with the OS to perform native actions.")
    fun runIntent(
        @ToolParam(description = "The intent to run (e.g. send_email)") intent: String,
        @ToolParam(description = "A JSON string containing the parameter values required for the intent.") parameters: String
    ): Map<String, String> {
        Timber.i("AgentToolSet: run_intent called for intent '$intent'")
        
        val success = IntentHandler.handleAction(context, intent, parameters)
        return if (success) {
            mapOf("action" to intent, "result" to "succeeded")
        } else {
            mapOf("action" to intent, "result" to "failed", "error" to "Intent implementation missing or failed")
        }
    }
}
