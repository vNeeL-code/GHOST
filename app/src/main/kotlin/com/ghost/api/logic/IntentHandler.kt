package com.ghost.api.logic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * Handles native Android intents for the agent, mirroring the Edge Gallery IntentHandler.
 * Provides a direct 'Tier 1' execution path for common system actions.
 */
object IntentHandler {
    private const val TAG = "IntentHandler"

    fun handleAction(context: Context, action: String, parameters: String): Boolean {
        return try {
            val json = JSONObject(parameters)
            
            // 1. Generic Android Intents
            if (action.startsWith("android.intent.action.") || action.startsWith("com.")) {
                val intent = Intent(action)
                
                // Parse optional data/type
                val dataStr = json.optString("data", "")
                val typeStr = json.optString("type", "")
                if (dataStr.isNotEmpty() && typeStr.isNotEmpty()) {
                    intent.setDataAndType(Uri.parse(dataStr), typeStr)
                } else if (dataStr.isNotEmpty()) {
                    intent.data = Uri.parse(dataStr)
                } else if (typeStr.isNotEmpty()) {
                    intent.type = typeStr
                }
                
                // Parse Component Name
                val compPkg = json.optString("component_package", "")
                val compCls = json.optString("component_class", "")
                if (compPkg.isNotEmpty() && compCls.isNotEmpty()) {
                    intent.setClassName(compPkg, compCls)
                } else if (compPkg.isNotEmpty()) {
                    intent.setPackage(compPkg)
                }
                
                // Parse Extras
                val extrasObj = json.optJSONObject("extras")
                if (extrasObj != null) {
                    val keys = extrasObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        when (val value = extrasObj.get(key)) {
                            is String -> intent.putExtra(key, value)
                            is Int -> intent.putExtra(key, value)
                            is Boolean -> intent.putExtra(key, value)
                            is Double -> intent.putExtra(key, value)
                        }
                    }
                }
                
                // Launch mode
                val mode = json.optString("start_mode", "activity")
                when (mode) {
                    "service" -> context.startService(intent)
                    "broadcast" -> context.sendBroadcast(intent)
                    else -> {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }
                return true
            }
            
            // 2. Convenience Legacy / App Intents
            when (action) {
                "send_email" -> {
                    val email = json.optString("extra_email")
                    val subject = json.optString("extra_subject")
                    val text = json.optString("extra_text")
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                }
                "send_sms" -> {
                    val phone = json.optString("phone_number")
                    val body = json.optString("sms_body")
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("smsto:$phone")
                        putExtra("sms_body", body)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                }
                "open_url" -> {
                    val url = json.optString("url")
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                }
                // 3. Custom System / Hardware Tools (Legacy MCP)
                "flashlight" -> {
                    val state = json.optString("state", "ON").uppercase()
                    com.ghost.api.hardware.HardwareToolSet(context).flashlight(state)
                    true
                }
                "app" -> {
                    val appName = json.optString("name", "")
                    com.ghost.api.hardware.SystemToolSet(context).app(appName)
                    true
                }
                "media" -> {
                    val act = json.optString("action", "PAUSE")
                    com.ghost.api.hardware.SystemToolSet(context).media(act)
                    true
                }
                "alarm" -> {
                    val hour = json.optInt("hour", 8)
                    val minutes = json.optInt("minutes", 0)
                    val label = json.optString("label", "")
                    com.ghost.api.hardware.SystemToolSet(context).alarm(hour, minutes, label)
                    true
                }
                "timer" -> {
                    val seconds = json.optInt("seconds", 60)
                    val label = json.optString("label", "")
                    com.ghost.api.hardware.SystemToolSet(context).timer(seconds, label)
                    true
                }
                "calendar" -> {
                    val title = json.optString("title", "")
                    val desc = json.optString("description", "")
                    val minutes = json.optInt("minutes", 30)
                    com.ghost.api.hardware.SystemToolSet(context).calendar(title, desc, minutes)
                    true
                }
                "read_calendar" -> {
                    val days = json.optInt("days", 7)
                    com.ghost.api.hardware.SystemToolSet(context).read_calendar(days)
                    true
                }
                "click" -> {
                    val target = json.optString("target", "")
                    com.ghost.api.hardware.SystemToolSet(context).click(target)
                    true
                }
                "scroll" -> {
                    val direction = json.optString("direction", "DOWN")
                    com.ghost.api.hardware.SystemToolSet(context).scroll(direction)
                    true
                }
                "navigate" -> {
                    val navAction = json.optString("action", "BACK")
                    com.ghost.api.hardware.SystemToolSet(context).navigate(navAction)
                    true
                }
                "take_screenshot" -> {
                    com.ghost.api.hardware.SystemToolSet(context).take_screenshot()
                    true
                }
                "remember" -> {
                    val title = json.optString("title", "")
                    val content = json.optString("content", "")
                    com.ghost.api.hardware.SystemToolSet(context).remember(title, content)
                    true
                }
                "recall" -> {
                    val query = json.optString("query", "")
                    com.ghost.api.hardware.SystemToolSet(context).recall(query)
                    true
                }
                "bash" -> {
                    val command = json.optString("command", "")
                    com.ghost.api.hardware.SystemToolSet(context).bash(command)
                    true
                }
                "search" -> {
                    val query = json.optString("query", "")
                    com.ghost.api.hardware.NetworkToolSet(context).search(query, 3)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle intent action: $action", e)
            false
        }
    }
}
