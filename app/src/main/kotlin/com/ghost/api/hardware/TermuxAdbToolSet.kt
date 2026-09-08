package com.ghost.api.hardware

import android.content.Context
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import timber.log.Timber

/**
 * Termux ADB Bridge for Power Users.
 * Provides advanced shell/UI automation capabilities using the local tcpip 5555 bridge.
 * This is an optional extension that assumes the user has set up `adb tcpip 5555`.
 */
class TermuxAdbToolSet(private val context: Context) : ToolSet {

    private fun runAdb(cmd: String): Map<String, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "adb -s 127.0.0.1:5555 shell $cmd"))
            val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val errorFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader().use { it.readText() }
            }
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            val output = try { outputFuture.get(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { "" }
            val error = try { errorFuture.get(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { "" }
            
            if (process.exitValue() == 0) {
                mapOf("result" to "success", "output" to output.take(2000))
            } else {
                mapOf("result" to "error", "message" to error.take(500))
            }
        } catch (e: Exception) {
            mapOf("result" to "error", "message" to (e.message ?: "Failed to execute ADB command"))
        }
    }

    @Tool(description = "Tap on the screen at specific coordinates (Power User)")
    fun adb_tap(
        @ToolParam(description = "X coordinate") x: Int,
        @ToolParam(description = "Y coordinate") y: Int
    ): Map<String, String> {
        Timber.i("ADB Tap: $x, $y")
        return runAdb("input tap $x $y")
    }

    @Tool(description = "Swipe on the screen from one point to another (Power User)")
    fun adb_swipe(
        @ToolParam(description = "Start X coordinate") x1: Int,
        @ToolParam(description = "Start Y coordinate") y1: Int,
        @ToolParam(description = "End X coordinate") x2: Int,
        @ToolParam(description = "End Y coordinate") y2: Int,
        @ToolParam(description = "Duration in milliseconds") duration: Int = 300
    ): Map<String, String> {
        Timber.i("ADB Swipe: $x1, $y1 -> $x2, $y2")
        return runAdb("input swipe $x1 $y1 $x2 $y2 $duration")
    }

    @Tool(description = "Input text directly using ADB (Power User)")
    fun adb_input_text(
        @ToolParam(description = "Text to input") text: String
    ): Map<String, String> {
        val escapedText = text.replace(" ", "%s").replace("\"", "\\\"")
        Timber.i("ADB Input Text: $escapedText")
        return runAdb("input text \"$escapedText\"")
    }

    @Tool(description = "Fetch the current foreground app window (Power User)")
    fun adb_dumpsys_window(): Map<String, String> {
        Timber.i("ADB Dumpsys Window")
        // Get the top window/activity
        val result = runAdb("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'")
        return result
    }

    @Tool(description = "Send a keyevent via ADB (Power User)")
    fun adb_keyevent(
        @ToolParam(description = "Keycode (e.g. 4 for BACK, 3 for HOME)") keycode: Int
    ): Map<String, String> {
        Timber.i("ADB Keyevent: $keycode")
        return runAdb("input keyevent $keycode")
    }

    @Tool(description = "Run a shell/bash command")
    fun bash(
        @ToolParam(description = "The command") command: String
    ): Map<String, String> {
        com.ghost.api.GemmaService.instance?.showPipContent("Terminal", "Executing: ${command.take(20)}...")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val output = try { outputFuture.get(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { "" }
            mapOf("result" to "success", "output" to output.take(2000))
        } catch (e: Exception) { mapOf("result" to "error", "message" to e.message.toString()) }
    }
}
