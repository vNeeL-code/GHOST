package com.ghost.api.skills

import android.content.Context
import android.os.Environment
import timber.log.Timber
import java.io.File

data class Skill(
    val name: String,
    val description: String,
    val instructions: String,
    val builtIn: Boolean = false
)

class SkillManager(private val context: Context) {

    private val skillsMap = mutableMapOf<String, Skill>()

    fun loadSkillsFromAssets() {
        try {
            val skillDirs = context.assets.list("skills") ?: return
            for (dir in skillDirs) {
                val mdContent = context.assets.open("skills/$dir/SKILL.md").bufferedReader().use { it.readText() }
                parseAndAddSkill(mdContent, builtIn = true)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading skills from assets")
        }
    }

    fun loadSkillsFromSdCard() {
        try {
            val skillsDir = File(Environment.getExternalStorageDirectory(), "Gemma/skills")
            if (!skillsDir.exists()) skillsDir.mkdirs()
            
            skillsDir.listFiles { _, name -> name.endsWith(".md") }?.forEach { file ->
                val mdContent = file.readText()
                parseAndAddSkill(mdContent, builtIn = false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading skills from SD card")
        }
    }

    private fun parseAndAddSkill(content: String, builtIn: Boolean) {
        val parts = content.split("---")
        if (parts.size < 3) return

        val header = parts[1].trim()
        val instructions = parts.drop(2).joinToString("---").trim()

        var name = ""
        var description = ""

        header.lines().forEach { line ->
            when {
                line.startsWith("name:") -> name = line.substringAfter("name:").trim()
                line.startsWith("description:") -> description = line.substringAfter("description:").trim()
            }
        }

        if (name.isNotEmpty()) {
            skillsMap[name] = Skill(name, description, instructions, builtIn)
            Timber.i("Loaded skill: $name (${if (builtIn) "Built-in" else "Custom"})")
        }
    }

    fun saveNewSkill(name: String, description: String, instructions: String): Boolean {
        try {
            val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "").lowercase()
            if (safeName.isEmpty()) return false
            
            val skillsBaseDir = File(Environment.getExternalStorageDirectory(), "Gemma/skills")
            val skillFile = File(skillsBaseDir, "${safeName}.md")
            
            val content = """
                ---
                name: $safeName
                description: $description
                ---
                $instructions
            """.trimIndent()
            
            skillFile.writeText(content)
            
            // Hot reload
            skillsMap[safeName] = Skill(safeName, description, instructions, builtIn = false)
            Timber.i("Saved new macro skill: ${safeName}.md to SD card")
            
            // Trigger a soft reset so the new skill appears in the next turn.
            // No need for reflection — softReset marks the engine for reset on next query.
            try {
                com.ghost.api.GemmaService.instance?.engine?.softReset("")
            } catch (_: Exception) {}
            return true
        } catch (e: Exception) {
            Timber.e(e, "Error saving new skill: ${e.message}")
            return false
        }
    }

    fun getSkillsListPrompt(): String {
        return if (skillsMap.isEmpty()) "None available."
        else skillsMap.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    fun getSkillInstructions(name: String): String? {
        return skillsMap[name]?.instructions
    }
}
