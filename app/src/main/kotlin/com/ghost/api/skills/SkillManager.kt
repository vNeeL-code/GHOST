package com.ghost.api.skills

import android.content.Context
import android.os.Environment
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SkillManager - Standardized Skill Loader (Edge Gallery Protocol + User Plug-and-Play Skills)
 *
 * Lives alongside `/sdcard/Documents/GHOST/memory_log.txt` at `/sdcard/Documents/GHOST/skills/`.
 * Supports:
 * 1. AI Edge Gallery skill folders (`Documents/GHOST/skills/<skill-name>/SKILL.md` + optional `scripts/index.html` & `assets/`)
 * 2. Standalone Markdown skills (`Documents/GHOST/skills/<skill-name>.md`)
 * 3. Automatic seeding of built-in & Edge Gallery demo skills to `Documents/GHOST/skills/` so users can browse, edit, or drag-and-drop new skills at any time.
 */
class SkillManager(private val context: Context) {

    data class Skill(
        val name: String,
        val description: String,
        val instructions: String,
        val path: String,
        val requireSecret: Boolean = false
    )

    private val skills = ConcurrentHashMap<String, Skill>()
    @Volatile private var lastExternalScanMs: Long = 0L

    val publicSkillsDir: File by lazy {
        try {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val dir = File(docs, "GHOST/skills")
            if (!dir.exists()) dir.mkdirs()
            dir
        } catch (e: Exception) {
            val fallback = context.getExternalFilesDir(null) ?: context.filesDir
            File(fallback, "GHOST/skills").apply { if (!exists()) mkdirs() }
        }
    }

    /**
     * Seeds `/sdcard/Documents/GHOST/skills/` with bundled skills from `assets/skills/`
     * if they do not already exist on disk, without overwriting user edits.
     */
    fun seedPublicSkillsFromAssets() {
        try {
            val targetRoot = publicSkillsDir
            if (!targetRoot.exists() && !targetRoot.mkdirs()) {
                Timber.w("SkillManager: Could not create public skills dir at ${targetRoot.absolutePath}")
                return
            }

            val readmeFile = File(targetRoot, "README_SKILLS.txt")
            if (!readmeFile.exists()) {
                try {
                    readmeFile.writeText(
                        """
                        GHOST PLUG-AND-PLAY SKILLS DIRECTORY
                        ====================================
                        Drop any AI Edge Gallery skill folder (<skill-name>/SKILL.md + optional scripts/ & assets/)
                        or any standalone Markdown file (<skill-name>.md) into this folder.

                        GHOST automatically discovers new skills placed here — no app restart required!
                        Ask Gemma: "Load the <skill-name> skill" or just ask her to perform the task.
                        """.trimIndent()
                    )
                } catch (_: Exception) {}
            }

            val assetManager = context.assets
            val skillDirs = assetManager.list("skills") ?: return
            for (dirName in skillDirs) {
                val destSkillDir = File(targetRoot, dirName)
                val destSkillMd = File(destSkillDir, "SKILL.md")
                if (!destSkillMd.exists() || !destSkillMd.canRead()) {
                    copyAssetFolderRecursive("skills/$dirName", destSkillDir)
                }
            }
            Timber.i("SkillManager: Seeded public skills folder at ${targetRoot.absolutePath}")
        } catch (e: Exception) {
            Timber.w(e, "SkillManager: Failed to seed public skills folder")
        }
    }

    private fun copyAssetFolderRecursive(assetPath: String, destDir: File) {
        try {
            val assetManager = context.assets
            val children = assetManager.list(assetPath)
            if (children.isNullOrEmpty()) {
                // Leaf file
                destDir.parentFile?.let { if (!it.exists()) it.mkdirs() }
                assetManager.open(assetPath).use { input ->
                    destDir.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                if (!destDir.exists()) destDir.mkdirs()
                for (child in children) {
                    copyAssetFolderRecursive("$assetPath/$child", File(destDir, child))
                }
            }
        } catch (e: Exception) {
            Timber.d(e, "SkillManager: Skipping asset copy for $assetPath")
        }
    }

    /**
     * Loads skills from a directory (e.g., `/sdcard/Documents/GHOST/skills`).
     * Supports both `<folder>/SKILL.md` and flat `<name>.md` files.
     * Under Scoped Storage, only overwrites built-in asset skills if the external file is actually readable by this app UID.
     */
    fun loadSkillsFromDir(dirPath: String) {
        val dir = File(dirPath)
        if (!dir.exists()) {
            try { dir.mkdirs() } catch (_: Exception) {}
        }
        if (!dir.exists() || !dir.isDirectory) {
            Timber.w("Skill directory not accessible: $dirPath")
            return
        }

        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (entry.isDirectory) {
                val skillFile = File(entry, "SKILL.md")
                if (skillFile.exists() && skillFile.canRead()) {
                    val scriptCheck = File(entry, "scripts")
                    val canReadScripts = !scriptCheck.exists() || (scriptCheck.canRead() && (scriptCheck.listFiles()?.all { it.canRead() } != false))
                    if (canReadScripts) {
                        parseSkill(skillFile, entry.absolutePath, fallbackName = entry.name)?.let {
                            skills[it.name] = it
                            Timber.i("Loaded directory skill: ${it.name} from ${entry.absolutePath}")
                        }
                    }
                }
            } else if (entry.isFile && entry.canRead() && entry.name.endsWith(".md", ignoreCase = true) && !entry.name.equals("README.md", ignoreCase = true)) {
                val baseName = entry.nameWithoutExtension
                parseSkill(entry, entry.parentFile?.absolutePath ?: dir.absolutePath, fallbackName = baseName)?.let {
                    skills[it.name] = it
                    Timber.i("Loaded standalone skill: ${it.name} from ${entry.absolutePath}")
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
                    parseSkillContent(
                        content = content,
                        path = "file:///android_asset/skills/$dirName",
                        fallbackName = dirName
                    )?.let {
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

    /**
     * Hot-reloads any newly dropped skills in `Documents/GHOST/skills` (throttled to at most once every 3s).
     */
    fun refreshExternalSkillsIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastExternalScanMs < 3000L) return
        lastExternalScanMs = now
        try {
            loadSkillsFromDir(publicSkillsDir.absolutePath)
        } catch (_: Exception) {}
    }

    private fun parseSkill(file: File, path: String, fallbackName: String = "unknown"): Skill? {
        return try {
            parseSkillContent(file.readText(), path, fallbackName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse skill at ${file.absolutePath}")
            null
        }
    }

    private fun parseSkillContent(content: String, path: String, fallbackName: String = "unknown"): Skill? {
        return try {
            val trimmed = content.trim()
            if (trimmed.startsWith("---")) {
                val metadataPart = trimmed.substringAfter("---").substringBefore("---")
                val instructions = trimmed.substringAfter("---").substringAfter("---").trim()

                val lines = metadataPart.lines()
                val name = lines.find { it.trim().startsWith("name:") }?.substringAfter(":")?.trim()
                    ?.ifBlank { fallbackName } ?: fallbackName
                val description = lines.find { it.trim().startsWith("description:") }?.substringAfter(":")?.trim() ?: ""
                val requireSecret = lines.any { it.contains("require-secret: true") }

                Skill(name, description, instructions, path, requireSecret)
            } else {
                // Plain markdown file without YAML frontmatter
                val firstLine = trimmed.lines().firstOrNull { it.isNotBlank() }?.removePrefix("#")?.trim() ?: "Custom skill"
                Skill(
                    name = fallbackName,
                    description = firstLine.take(120),
                    instructions = trimmed,
                    path = path,
                    requireSecret = false
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getSkill(name: String): Skill? {
        refreshExternalSkillsIfNeeded()
        val key = name.trim()
        val normalizedKey = key.lowercase().replace("_", "-").replace(" ", "-")
        return skills[key]
            ?: skills[normalizedKey]
            ?: skills.entries.firstOrNull {
                val candidate = it.key.lowercase().replace("_", "-")
                candidate == normalizedKey || candidate.contains(normalizedKey) || normalizedKey.contains(candidate)
            }?.value
    }

    fun getAllSkills(): List<Skill> {
        refreshExternalSkillsIfNeeded()
        return skills.values.sortedBy { it.name }
    }

    fun getSkillsListPrompt(): String {
        refreshExternalSkillsIfNeeded()
        if (skills.isEmpty()) return "No skills available."
        return skills.values.sortedBy { it.name }.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    fun getSkillInstructions(name: String): String? = getSkill(name)?.instructions

    fun saveNewSkill(name: String, description: String, instructions: String): Boolean {
        return try {
            val safeName = name.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-')
            if (safeName.isEmpty()) return false

            val skillDir = File(publicSkillsDir, safeName)
            if (!skillDir.exists()) skillDir.mkdirs()
            val skillFile = File(skillDir, "SKILL.md")

            val mdContent = buildString {
                appendLine("---")
                appendLine("name: $safeName")
                appendLine("description: ${description.replace("\n", " ").trim()}")
                appendLine("---")
                appendLine()
                appendLine(instructions.trim())
            }
            skillFile.writeText(mdContent, Charsets.UTF_8)

            val newSkill = Skill(
                name = safeName,
                description = description.trim(),
                instructions = instructions.trim(),
                path = skillDir.absolutePath,
                requireSecret = false
            )
            skills[safeName] = newSkill
            Timber.i("SkillManager: Saved and hot-reloaded new skill '$safeName' at ${skillFile.absolutePath}")
            true
        } catch (e: Exception) {
            Timber.e(e, "SkillManager: Failed to save skill '$name'")
            false
        }
    }

    fun buildSystemPromptPatch(): String {
        refreshExternalSkillsIfNeeded()
        if (skills.isEmpty()) return ""
        return "\n\nSkills: " + skills.keys.sorted().joinToString(", ") + " (call load_skill(name) for instructions)"
    }
}
