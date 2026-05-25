package com.ghost.api

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import timber.log.Timber

/**
 * Process-wide singleton that keeps a single LiteRT-LM Engine alive across
 * the application lifecycle.
 *
 * Why: Prevents massive memory allocations (and leaks) from recreating the
 * Engine across Service restarts. Ensures the native model weights (2GB+)
 * are only loaded into memory exactly once per process.
 */
object EngineHolder {

    private const val TAG = "EngineHolder"

    private var engine: Engine? = null
    private var currentModelPath: String? = null
    private var currentBackendLabel: String? = null

    private fun backendLabel(backend: Backend): String =
        if (backend is Backend.CPU) "CPU" else if (backend is Backend.GPU) "GPU" else "NPU"

    /**
     * Return the existing Engine if the model path matches, otherwise close the
     * old one and create a fresh Engine for the new model.
     *
     * @param modelPath  absolute path to the .litertlm model file
     * @param cacheDir   app's cacheDir.path
     */
    @Synchronized
    @JvmOverloads
    @OptIn(ExperimentalApi::class)
    fun getOrCreate(modelPath: String, cacheDir: String, backend: Backend = Backend.CPU()): Engine {
        val existing = engine
        val requestedBackendLabel = backendLabel(backend)
        if (existing != null && currentModelPath == modelPath && currentBackendLabel == requestedBackendLabel) {
            Timber.d("getOrCreate: reusing engine for $modelPath (${currentBackendLabel ?: "unknown"})")
            return existing
        }

        // Different model or first call — close old engine first
        if (existing != null) {
            Timber.i("getOrCreate: runtime changed (model=$currentModelPath/${currentBackendLabel ?: "?"} -> $modelPath/$requestedBackendLabel), closing old engine")
            try {
                existing.close()
            } catch (e: Exception) {
                Timber.w(e, "getOrCreate: error closing old engine")
            }
            engine = null
            currentModelPath = null
        }

        Timber.i("getOrCreate: creating new engine for $modelPath with $requestedBackendLabel")
        return try {
            // Disabled MTP speculative decoding - causes massive TTFT/OOM on mobile GPUs
            ExperimentalFlags.enableSpeculativeDecoding = false
            
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = Backend.GPU(), // must be GPU for Gemma 3n/4
                audioBackend = Backend.CPU(),  // must be CPU for Gemma 3n/4
                maxNumTokens = Constants.MAX_TOKENS, 
                cacheDir = cacheDir
            )

            val newEngine = Engine(engineConfig).also { it.initialize() }
            
            engine = newEngine
            currentModelPath = modelPath
            currentBackendLabel = requestedBackendLabel
            Timber.i("getOrCreate: engine ready for $modelPath (${currentBackendLabel})")
            newEngine
        } catch (e: Exception) {
            Timber.e(e, "getOrCreate: failed to create engine for $modelPath")
            throw e
        }
    }

    /**
     * Explicitly close and release the engine. Normal transitions should NOT call this.
     */
    @Synchronized
    fun close() {
        Timber.i("close: releasing engine for $currentModelPath")
        try {
            engine?.close()
        } catch (e: Exception) {
            Timber.w(e, "close: error closing engine")
        }
        engine = null
        currentModelPath = null
        currentBackendLabel = null
        Timber.i("close: done")
    }

    @Synchronized
    fun isReady(modelPath: String): Boolean = engine != null && currentModelPath == modelPath
}
