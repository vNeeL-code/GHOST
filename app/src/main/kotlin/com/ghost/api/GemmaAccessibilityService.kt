package com.ghost.api

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber
import android.graphics.Bitmap
import android.view.Display
import java.util.concurrent.Executor
import java.util.function.Consumer
import android.accessibilityservice.AccessibilityService.ScreenshotResult

class GemmaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("GemmaAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        // Audit Fix: "Stop the Scraping"
        // Only wake up for major window state changes (App switching)
        // Ignoring TYPE_VIEW_SCROLLED, TYPE_VIEW_CLICKED, etc. to save CPU/Battery.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
             val packageName = event.packageName?.toString()
             val className = event.className?.toString()
             
             if (packageName != null) {
                 Timber.d("Window Change: $packageName / $className")
                 // We could push this to context if needed, but for now we just log
                 // and avoid the heavy DFS traversal.
             }
        }
    }

    override fun onInterrupt() {
        Timber.w("GemmaAccessibilityService interrupted")
    }

    // Agentic Capability: Semantic Grep
    // Iterative BFS with hard node + char caps — safe on Chrome/YouTube/complex apps.
    // Previously used recursive DFS which caused stack overflow on deep trees. Fixed.
    fun getSemanticScreenDump(): String {
        val root = rootInActiveWindow ?: return "[[NO ACTIVE WINDOW]]"

        val sb = StringBuilder()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        val toRecycle = mutableListOf<AccessibilityNodeInfo>()
        queue.add(root)

        var nodeCount = 0
        val MAX_NODES = 300
        val MAX_CHARS = 4000

        try {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            val searchQueue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            searchQueue.add(root)
            
            while (searchQueue.isNotEmpty() && allNodes.size < 1000) {
                val node = searchQueue.poll() ?: continue
                toRecycle.add(node)
                allNodes.add(node)
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { searchQueue.add(it) }
                }
            }
            
            // Audit Fix: Drain remaining items in searchQueue to prevent memory leak
            searchQueue.forEach { toRecycle.add(it) }

            val prioritized = allNodes.sortedWith(compareByDescending<AccessibilityNodeInfo> { 
                it.isClickable || it.isEditable || it.isFocusable 
            }.thenByDescending { 
                !(it.text ?: it.contentDescription).isNullOrBlank() 
            })

            for (node in prioritized) {
                if (nodeCount >= MAX_NODES || sb.length >= MAX_CHARS) break
                
                val rawText = (node.text ?: node.contentDescription)?.toString()
                val text = rawText?.trim()?.take(100)?.replace("\n", " ")
                val isClickable = node.isClickable
                val isEditable = node.isEditable
                val isFocusable = node.isFocusable
                val role = node.className?.toString()?.split('.')?.lastOrNull() ?: "View"

                if (!text.isNullOrEmpty() || isClickable || isEditable || isFocusable) {
                    nodeCount++
                    sb.append("[$role]")
                    if (!text.isNullOrEmpty()) sb.append(" \"$text\"")
                    if (isClickable) sb.append(" (tap)")
                    if (isEditable) sb.append(" (input)")
                    if (isFocusable && !isClickable) sb.append(" (focus)")
                    sb.append("\n")
                }
            }

        } finally {
            toRecycle.forEach { try { it.recycle() } catch (e: IllegalStateException) {} }
        }

        return if (sb.isEmpty()) "[[SCREEN: no readable content]]"
        else sb.toString().trim()
    }

    // Agentic Capability: Visual Patch
    fun captureScreen(callback: (Bitmap?) -> Unit) {
        Timber.i("captureScreen called, API level: ${android.os.Build.VERSION.SDK_INT}")

        val service = instance
        if (service == null) {
            Timber.w("AccessibilityService not bound - vision disabled")
            callback(null)
            return
        }

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Timber.e("Screenshot not supported on this API level")
            callback(null)
            return
        }

        service.takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                Timber.i("Screenshot successful")
                try {
                    val hardwareBuffer = result.hardwareBuffer
                    try {
                        // DO NOT call hardwareBuffer.close() here - Bitmap.wrapHardwareBuffer manages it? 
                        // Actually, docs say you MUST close it after wrapping IF you copy it.
                        // We copy to software bitmap to prevent use-after-free on hardware buffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        
                        if (bitmap != null) {
                            sensoryStream.updateVisual(bitmap)
                        }
                        callback(bitmap)
                    } finally {
                        hardwareBuffer.close() // Close original buffer logic - ALWAYS
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error processing screenshot")
                    callback(null)
                }
            }

            override fun onFailure(errorCode: Int) {
                Timber.e("Screenshot failed with error code: $errorCode")
                callback(null)
            }
        })
    }


    // Agentic Capability: Action Execution
    fun performClick(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var found = false
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        val toRecycle = mutableListOf<AccessibilityNodeInfo>()
        queue.add(root)

        try {
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                toRecycle.add(node)

                val text = (node.text ?: node.contentDescription)?.toString()
                if (text?.contains(targetText, ignoreCase = true) == true) {
                    var clickNode: AccessibilityNodeInfo? = node
                    while (clickNode != null && !clickNode.isClickable) {
                        val parent = clickNode.parent
                        if (parent != null) toRecycle.add(parent)
                        clickNode = parent
                    }
                    if (clickNode != null && clickNode.isClickable) {
                        clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        found = true
                        break
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } finally {
            // Drain remaining queue and recycle all
            queue.forEach { toRecycle.add(it) }
            toRecycle.forEach { try { it.recycle() } catch (e: IllegalStateException) { Timber.v("Failed to recycle node: ${e.message}") } }
        }
        return found
    }

    fun performScroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        val toRecycle = mutableListOf<AccessibilityNodeInfo>()
        queue.add(root)
        var result = false

        try {
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                toRecycle.add(node)

                if (node.isScrollable) {
                    val action = if (direction.uppercase() == "UP")
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    node.performAction(action)
                    result = true
                    break
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } finally {
            queue.forEach { toRecycle.add(it) }
            toRecycle.forEach { try { it.recycle() } catch (e: IllegalStateException) { Timber.v("Failed to recycle node: ${e.message}") } }
        }
        return result
    }

    fun performGlobal(action: String): Boolean {
        return when(action.uppercase()) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            else -> false
        }
    }

    fun performType(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focused != null && focused.isEditable) {
            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            focused.recycle()
            return result
        }
        focused?.recycle()

        // Fallback: Find any editable node via BFS
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        val toRecycle = mutableListOf<AccessibilityNodeInfo>()
        queue.add(root)
        var result = false

        try {
            while (!queue.isEmpty()) {
                val node = queue.poll() ?: continue
                toRecycle.add(node)

                if (node.isEditable) {
                    val arguments = android.os.Bundle()
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    break
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } finally {
            queue.forEach { toRecycle.add(it) }
            toRecycle.forEach { try { it.recycle() } catch (e: IllegalStateException) { Timber.v("Failed to recycle node: ${e.message}") } }
        }
        return result
    }

    // === SENSORY CORTEX ===
    class SensoryStream {
        private val visualCortex = java.util.concurrent.atomic.AtomicReference<Bitmap?>()
        private val semanticCortex = java.util.concurrent.atomic.AtomicReference<String>("[[NO VISUAL CONTEXT]]")
        private val audioCortex = java.util.concurrent.atomic.AtomicReference<ShortArray?>()
        // DeviceContext is defined in logic/ContextManager, but we need to avoid circular deps.
        // We'll store a string representation or simple object for now, or just let ContextManager handle proprioception directly.
        // However, for "Unified Perception", we want a snapshot.
        
        fun updateVisual(bitmap: Bitmap?) {
            visualCortex.set(bitmap)
        }

        fun updateSemantic(text: String) {
            semanticCortex.set(text)
        }

        fun updateAudio(data: ShortArray?) {
            audioCortex.set(data)
        }
        
        fun getVisual(): Bitmap? = visualCortex.get()

        fun getUnifiedPerception(): String {
            val visual = visualCortex.get()
            val semantic = semanticCortex.get()
            val audio = audioCortex.get()
            
            return buildString {
                append("👁️ VISUAL: ${if (visual != null) "${visual.width}x${visual.height} screenshot captured" else "blind (no image)"}\n")
                append("🧠 SEMANTIC: ${semantic.take(100)}${if (semantic.length > 100) "..." else ""}\n")
                append("👂 AUDIO: ${if (audio != null) "${audio.size/16000f}s captured" else "silent"}\n")
            }
        }
    }

    val sensoryStream = SensoryStream()

    companion object {
        var instance: GemmaAccessibilityService? = null
        
        fun getSemantics(): String? = instance?.sensoryStream?.getUnifiedPerception() // Helper

        fun safePerformGlobal(action: String): Boolean {
            return instance?.let { service ->
                when (action.uppercase()) {
                    "BACK" -> service.performGlobalAction(GLOBAL_ACTION_BACK)
                    "HOME" -> service.performGlobalAction(GLOBAL_ACTION_HOME)
                    "RECENTS" -> service.performGlobalAction(GLOBAL_ACTION_RECENTS)
                    else -> false
                }
            } ?: false
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.i("GemmaAccessibilityService: Sensory Cortex Online")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Timber.i("GemmaAccessibilityService: Sensory Cortex Offline")
    }
}
