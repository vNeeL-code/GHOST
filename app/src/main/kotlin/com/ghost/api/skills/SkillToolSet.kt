package com.ghost.api.skills

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import timber.log.Timber

class SkillToolSet(private val skillManager: SkillManager) : ToolSet {

    @Tool(description = "Loads the detailed instructions and capabilities for a specific skill.")
    fun loadSkill(
        @ToolParam(description = "The unique name of the skill to load (e.g., 'weather', 'calculator').") name: String
    ): Map<String, String> {
        val rawInstructions = skillManager.getSkillInstructions(name)
        return if (rawInstructions != null) {
            val instructions = rawInstructions.take(1400)
            com.ghost.api.GemmaService.instance?.recordToolOutput(instructions.length)
            Timber.i("Skill loaded: $name (${instructions.length} chars)")
            mapOf("result" to "success", "instructions" to instructions)
        } else {
            Timber.w("Skill not found: $name")
            mapOf("result" to "error", "message" to "Skill '$name' not found in registry.")
        }
    }


}
