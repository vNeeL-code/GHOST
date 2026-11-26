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

    private val ALLOWED_GENERIC_ACTIONS = setOf(
        Intent.ACTION_VIEW,
        Intent.ACTION_SEND,
        Intent.ACTION_SENDTO,
        Intent.ACTION_DIAL,
        Intent.ACTION_WEB_SEARCH,
        Intent.ACTION_SEARCH,
        Intent.ACTION_MAIN,
        Intent.ACTION_INSERT,
        Intent.ACTION_EDIT,
        "android.intent.action.SET_ALARM",
        "android.intent.action.SET_TIMER",
        "android.intent.action.SHOW_ALARMS",
        "android.intent.action.SHOW_TIMERS",
        "android.media.action.MEDIA_PLAY_FROM_SEARCH",
        "com.google.android.gms.actions.CREATE_NOTE"
    )

    private val ALLOWED_DATA_SCHEMES = setOf(
        "http", "https", "mailto", "tel", "sms", "smsto", "geo", "market", "spotify", "vnd.youtube"
    )

    fun handleAction(context: Context, action: String, parameters: String): Boolean {
        return try {
            val json = JSONObject(parameters)
            
            // 1. Generic Android Intents (Strict Allowlist — prevents prompt-injection privilege escalation)
            if (action.startsWith("android.intent.action.") || action.startsWith("android.media.action.") || action.startsWith("com.")) {
                if (action !in ALLOWED_GENERIC_ACTIONS) {
                    Log.w(TAG, "Blocked non-allowlisted intent action: $action")
                    return false
                }

                // Never allow arbitrary background service starts or broadcasts from LLM tool calls
                val mode = json.optString("start_mode", "activity").lowercase()
                if (mode != "activity") {
                    Log.w(TAG, "Blocked non-activity intent launch mode '$mode' for $action")
                    return false
                }

                val intent = Intent(action)
                
                // Parse optional data/type (validate URI scheme to block file:// / content:// internal leaks)
                val dataStr = json.optString("data", "")
                val typeStr = json.optString("type", "")
                if (dataStr.isNotEmpty()) {
                    val uri = Uri.parse(dataStr)
                    val scheme = uri.scheme?.lowercase() ?: ""
                    if (scheme !in ALLOWED_DATA_SCHEMES) {
                        Log.w(TAG, "Blocked unsafe URI scheme '$scheme' in intent $action")
                        return false
                    }
                    if (typeStr.isNotEmpty()) {
                        intent.setDataAndType(uri, typeStr)
                    } else {
                        intent.data = uri
                    }
                } else if (typeStr.isNotEmpty()) {
                    intent.type = typeStr
                }
                
                // Optional package hint only (block explicit internal class targeting & self-package spoofing)
                val compPkg = json.optString("component_package", "")
                if (compPkg.isNotEmpty() && compPkg != context.packageName && !compPkg.startsWith("com.android.systemui")) {
                    intent.setPackage(compPkg)
                }
                
                // Parse safe scalar extras
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
                
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
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
                    val url = json.optString("url").trim()
                    val uri = Uri.parse(url)
                    val scheme = uri.scheme?.lowercase() ?: ""
                    if (scheme !in setOf("http", "https")) {
                        Log.w(TAG, "Blocked unsafe open_url scheme '$scheme'")
                        return false
                    }
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
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
                    val paramsMap = mutableMapOf<String, Any?>()
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        paramsMap[k] = json.get(k)
                    }
                    val parsed = com.ghost.api.hardware.SystemToolSet.parseAlarmParams(paramsMap)
                    if (parsed != null) {
                        val (hour, minutes, label) = parsed
                        com.ghost.api.hardware.SystemToolSet(context).alarm(hour, minutes, label)
                        true
                    } else {
                        val hour = json.optInt("hour", -1)
                        if (hour >= 0) {
                            val minutes = json.optInt("minutes", 0)
                            val label = json.optString("label", "")
                            com.ghost.api.hardware.SystemToolSet(context).alarm(hour, minutes, label)
                            true
                        } else false
                    }
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
                    com.ghost.api.hardware.UiMacroToolSet(context).click(target)
                    true
                }
                "scroll" -> {
                    val direction = json.optString("direction", "DOWN")
                    com.ghost.api.hardware.UiMacroToolSet(context).scroll(direction)
                    true
                }
                "navigate" -> {
                    val navAction = json.optString("action", "BACK")
                    com.ghost.api.hardware.UiMacroToolSet(context).navigate(navAction)
                    true
                }
                "take_screenshot" -> {
                    Log.w(TAG, "take_screenshot is not implemented via intent dispatch; use Accessibility Service")
                    false
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
                    Log.w(TAG, "bash execution via intent dispatch is blocked for security; requires explicit confirmation")
                    false
                }
                "execute_background_search", "search" -> {
                    val query = json.optString("query", json.optString("input", ""))
                    com.ghost.api.hardware.NetworkToolSet(context).execute_background_search(query, 3)
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
