package com.gemma.api.skills

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import timber.log.Timber

class SkillToolSet(private val skillManager: SkillManager) : ToolSet {

    @Tool(description = "Loads the detailed instructions and capabilities for a specific skill.")
    fun loadSkill(
        @ToolParam(description = "The unique name of the skill to load (e.g., 'weather', 'calculator').") name: String
    ): Map<String, String> {
        val instructions = skillManager.getSkillInstructions(name)
        return if (instructions != null) {
            Timber.i("Skill loaded: $name")
            mapOf("result" to "success", "instructions" to instructions)
        } else {
            Timber.w("Skill not found: $name")
            mapOf("result" to "error", "message" to "Skill '$name' not found in registry.")
        }
    }

    @Tool(description = "Saves a new macro/skill. Use this when the user asks you to learn or save a new workflow or sequence of actions.")
    fun createMacro(
        @ToolParam(description = "The unique short name for the macro (no spaces, e.g., 'morning_routine').") name: String,
        @ToolParam(description = "A short 1-sentence description of what this macro does.") description: String,
        @ToolParam(description = "The markdown instructions for this macro, detailing exactly how to execute it step-by-step using your existing tools.") instructions: String
    ): Map<String, String> {
        val success = skillManager.saveNewSkill(name, description, instructions)
        return if (success) {
            mapOf("result" to "success", "message" to "Macro '$name' saved successfully to device memory and added to active registry.")
        } else {
            mapOf("result" to "error", "message" to "Failed to save macro '$name'. Check storage permissions or formatting.")
        }
    }
}
