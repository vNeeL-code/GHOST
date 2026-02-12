package com.gemma.api.agent

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import com.gemma.api.GemmaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * RLMBridge — Kotlin ↔ Python bridge for Recursive Language Models.
 *
 * Enables Gemma to programmatically chunk and recursively process
 * large contexts using a Python REPL with sub-LLM calls back to
 * the on-device Gemma 3n engine.
 *
 * Architecture:
 *   KoogAgent → [[THINK:query]] → RLMBridge → Python RLM_REPL
 *                                                   ↕
 *                                              GemmaBridge callback
 *                                                   ↕
 *                                          GemmaEngine.generateOneShot()
 */
class RLMBridge(
    private val appContext: Context,
    private val engine: GemmaEngine
) {
    private var rlmModule: PyObject? = null
    @Volatile private var isInitialized = false

    /**
     * Initialize Chaquopy Python runtime and register Gemma as the inference backend.
     * Safe to call multiple times — no-ops after first init.
     */
    fun initialize() {
        if (isInitialized) return

        try {
            // Start Python if not already running
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(appContext))
            }

            val python = Python.getInstance()
            rlmModule = python.getModule("rlm_bridge")

            // Register the inference callback so Python can call back to Gemma
            rlmModule?.callAttr("init", InferenceCallback())
            isInitialized = true
            Timber.i("RLMBridge: Initialized (Python + Gemma callback ready)")
        } catch (e: Exception) {
            Timber.e(e, "RLMBridge: Failed to initialize")
        }
    }

    /**
     * Run RLM reasoning on a context + query.
     * Blocks the calling coroutine while Python executes the iterative REPL loop.
     *
     * @param context The context to analyze (conversation history, documents, etc.)
     * @param query The user's question or task
     * @return The final answer from RLM, or an error message
     */
    suspend fun completion(context: String, query: String): String {
        if (!isInitialized) {
            return "RLM not initialized"
        }

        return withContext(Dispatchers.IO) {
            try {
                Timber.i("RLMBridge: Starting (context: ${context.length} chars, query: ${query.take(80)})")
                val result = rlmModule?.callAttr("completion", context, query, 8)
                val answer = result?.toString() ?: "RLM returned null"
                Timber.i("RLMBridge: Done (${answer.length} chars): ${answer.take(100)}")
                answer
            } catch (e: Exception) {
                Timber.e(e, "RLMBridge: Completion failed")
                "RLM error: ${e.message}"
            }
        }
    }

    /**
     * Callback object passed to Python.
     * Python calls infer(prompt) → this calls GemmaEngine.generateOneShot()
     * on the current thread via runBlocking (safe since we're on Dispatchers.IO).
     */
    inner class InferenceCallback {
        fun infer(prompt: String): String {
            Timber.d("RLMBridge: Python→Kotlin inference (${prompt.length} chars)")
            return runBlocking {
                engine.generateOneShot(prompt)
            }
        }
    }
}
