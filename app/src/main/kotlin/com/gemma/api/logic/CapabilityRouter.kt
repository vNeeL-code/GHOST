package com.gemma.api.logic

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import timber.log.Timber

/**
 * CapabilityRouter - The "Meta-Tool" for the On-Demand capability architecture.
 * Allows Gemma to request specialized toolsets only when needed, keeping the 
 * NPU system prompt small and fast for general conversation.
 */
class CapabilityRouter(private val onActivationRequested: (String) -> Unit) : ToolSet {

    @Tool(description = "Requests access to specialized device capabilities (e.g., hardware sensors, network tools, system controls). Use this if your current tools are insufficient.")
    fun requestCapabilities(
        @ToolParam(description = "The capability module to load: 'HARDWARE' (sensors/battery), 'NETWORK' (browser/connectivity), 'SYSTEM' (apps/media)") module: String
    ): Map<String, String> {
        val upperModule = module.uppercase()
        Timber.i("Gemma requested capability activation: $upperModule")
        onActivationRequested(upperModule)
        
        return mapOf(
            "result" to "queued",
            "message" to "Module $upperModule activation sequence initiated. Your tools will be updated in the next turn. Proceed with your current response."
        )
    }
}
