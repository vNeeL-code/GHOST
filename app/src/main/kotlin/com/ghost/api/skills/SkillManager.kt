package com.ghost.api.skills

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * SkillManager - Standardized Skill Loader (Edge Gallery Protocol)
 */
class SkillManager(private val context: Context) {
    
    data class Skill(
        val name: String,
        val description: String,
        val instructions: String,
        val path: String,
        val requireSecret: Boolean = false
    )

    private val skills = mutableMapOf<String, Skill>()

    /**
     * Loads skills from a directory (e.g., the user's desktop skills folder).
     */
    fun loadSkillsFromDir(dirPath: String) {
        val dir = File(dirPath)
        if (!dir.exists() || !dir.isDirectory) {
            Timber.e("Skill directory not found: $dirPath")
            return
        }

        dir.listFiles { f -> f.isDirectory }?.forEach { skillDir ->
            val skillFile = File(skillDir, "SKILL.md")
            if (skillFile.exists()) {
                parseSkill(skillFile, skillDir.absolutePath)?.let {
                    skills[it.name] = it
                    Timber.i("Loaded skill: ${it.name}")
                }
            }
        }
    }

    /**
     * Loads built-in skills from the app's assets folder.
     */
    fun loadSkillsFromAssets() {
        try {
            val assetManager = context.assets
            val skillDirs = assetManager.list("skills") ?: return
            
            skillDirs.forEach { dirName ->
                val skillFilePath = "skills/$dirName/SKILL.md"
                try {
                    val stream = assetManager.open(skillFilePath)
                    val content = stream.bufferedReader().use { it.readText() }
                    parseSkillContent(content, "assets/$skillFilePath")?.let {
                        skills[it.name] = it
                        Timber.i("Loaded built-in skill: ${it.name}")
                    }
                } catch (e: Exception) {
                    // Skill file might not exist in this subdir
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load skills from assets")
        }
    }

    private fun parseSkill(file: File, path: String): Skill? {
        return try {
            parseSkillContent(file.readText(), path)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse skill at ${file.absolutePath}")
            null
        }
    }

    private fun parseSkillContent(content: String, path: String): Skill? {
        return try {
            val metadataPart = content.substringAfter("---").substringBefore("---")
            val instructions = content.substringAfterLast("---").trim()

            val lines = metadataPart.lines()
            val name = lines.find { it.startsWith("name:") }?.substringAfter(":")?.trim() ?: "unknown"
            val description = lines.find { it.startsWith("description:") }?.substringAfter(":")?.trim() ?: ""
            val requireSecret = lines.any { it.contains("require-secret: true") }

            Skill(name, description, instructions, path, requireSecret)
        } catch (e: Exception) {
            null
        }
    }

    fun getSkill(name: String): Skill? = skills[name]

    fun getAllSkills(): List<Skill> = skills.values.toList()
    
    fun getSkillsListPrompt(): String {
        if (skills.isEmpty()) return "No skills available."
        return skills.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    fun getSkillInstructions(name: String): String? = skills[name]?.instructions

    fun saveNewSkill(name: String, description: String, instructions: String): Boolean {
        // Implementation for dynamic skill saving
        return true
    }

    fun buildSystemPromptPatch(): String {
        if (skills.isEmpty()) return ""
        
        return buildString {
            append("\n\nAVAILABLE SKILLS:\n")
            append(getSkillsListPrompt())
            append("\n\nIf a user's request aligns with a skill, you MUST call the appropriate tool ('run_js' or 'run_intent') as described in the skill's instructions.\n")
            append("To see instructions for a specific skill, use 'loadSkill(name)'.")
        }
    }
}
