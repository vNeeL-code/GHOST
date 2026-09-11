package com.ghost.api.hardware

import android.content.Context
import android.content.Intent
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

class UiMacroToolSet(private val context: Context) : ToolSet {

    @Tool(description = "Click UI element")
    fun click(
        @ToolParam(description = "Text or description of element to click") target: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("UI", 1200)
        val success = com.ghost.api.GemmaAccessibilityService.instance?.performClick(target) ?: false
        return if (success) mapOf("result" to "success") else mapOf("result" to "error")
    }

    @Tool(description = "Scroll screen")
    fun scroll(
        @ToolParam(description = "Direction: up, down, left, right") direction: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("UI", 1200)
        val success = com.ghost.api.GemmaAccessibilityService.instance?.performScroll(direction) ?: false
        return if (success) mapOf("result" to "success") else mapOf("result" to "error")
    }

    @Tool(description = "Navigate Android")
    fun navigate(
        @ToolParam(description = "Action: home, back, recents, notifications") action: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("UI", 1200)
        val success = com.ghost.api.GemmaAccessibilityService.instance?.performGlobal(action) ?: false
        return if (success) mapOf("result" to "success") else mapOf("result" to "error")
    }

    @Tool(description = "Read current screen text semantics")
    fun read_screen(): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("SCREEN")
        return try {
            val content = com.ghost.api.GemmaAccessibilityService.getSemantics() ?: "[[SCREEN NOT ACCESSIBLE]]"
            mapOf("result" to "success", "content" to content)
        } finally {
            com.ghost.api.GemmaService.instance?.hideWorkSignal()
        }
    }

    @Tool(description = "Type text into focused element")
    fun type(text: String): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showWorkSignal("UI", 1200)
        val success = com.ghost.api.GemmaAccessibilityService.instance?.performType(text) ?: false
        return if (success) mapOf("result" to "success") else mapOf("result" to "error")
    }
}
