package com.ghost.api.database

import android.content.Context
import com.ghost.api.Constants
import com.ghost.api.logic.ContextManager
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * Possession & Integrity Layer — Portable `identity.md` + Hash-Chained SHA-256 Tamper Seal.
 *
 * Threat model (Volume I §7.4):
 * Seals attest continuity and external modification to the operator—not device integrity to a
 * third party. Because `Documents/GHOST/identity.md` and `skills/` are intentionally plaintext and
 * operator-portable, storing the hash-chained SHA-256 seal in GHOST's private Room DB (`agent_state`)
 * plus a private `.bak` rollback snapshot ensures any external modification while GHOST sleeps is
 * immediately detected on resume.
 */
object IdentityTamperSeal {

    const val KEY_SEAL_DIGEST = "tamper_seal_digest"
    const val KEY_SEAL_PREV_DIGEST = "tamper_seal_prev_digest"
    const val KEY_SEAL_IDENTITY_SHA = "tamper_seal_identity_sha256"
    const val KEY_SEAL_SKILLS_SHA = "tamper_seal_skills_sha256"
    const val KEY_SEAL_UPDATED_AT = "tamper_seal_updated_at"

    data class SealSnapshot(
        val combinedDigest: String,
        val identitySha256: String,
        val skillsSha256: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    sealed class VerificationResult {
        data class Intact(val digest: String) : VerificationResult()
        data class FirstSeal(val digest: String) : VerificationResult()
        data class Modified(
            val changedTargets: List<String>,
            val expectedDigest: String,
            val actualDigest: String
        ) : VerificationResult()
    }

    fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(bytes)
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    fun sha256File(file: File): String {
        if (!file.exists() || !file.isFile) return "MISSING"
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            val hash = md.digest()
            val sb = StringBuilder(hash.size * 2)
            for (b in hash) sb.append(String.format("%02x", b))
            sb.toString()
        } catch (e: Exception) {
            Timber.w(e, "IdentityTamperSeal: Failed hashing ${file.name}")
            "ERROR"
        }
    }

    /**
     * Computes a deterministic SHA-256 digest over all files in a directory (sorted by relative path).
     */
    fun sha256DirectoryManifest(dir: File): String {
        if (!dir.exists() || !dir.isDirectory) return "EMPTY"
        val files = dir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(dir).path.replace('\\', '/') }
            .toList()
        if (files.isEmpty()) return "EMPTY"
        val manifest = StringBuilder()
        for (f in files) {
            val rel = f.relativeTo(dir).path.replace('\\', '/')
            manifest.append(rel).append(':').append(f.length()).append(':').append(sha256File(f)).append('\n')
        }
        return sha256Hex(manifest.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Computes a hash-chained seal snapshot combining `identity.md`, `skills/`, and the previous seal digest.
     */
    fun computeSnapshot(
        identityFile: File,
        skillsDir: File,
        previousDigest: String = "GENESIS"
    ): SealSnapshot {
        val idSha = sha256File(identityFile)
        val skillsSha = sha256DirectoryManifest(skillsDir)
        val chainedPayload = "prev=$previousDigest|identity=$idSha|skills=$skillsSha"
        val combined = sha256Hex(chainedPayload.toByteArray(Charsets.UTF_8))
        return SealSnapshot(
            combinedDigest = combined,
            identitySha256 = idSha,
            skillsSha256 = skillsSha
        )
    }

    /**
     * Pure JVM-testable verification helper comparing current files against stored digests.
     */
    fun verifyAgainstStored(
        identityFile: File,
        skillsDir: File,
        storedCombinedDigest: String?,
        storedPrevDigest: String?,
        storedIdentitySha: String?,
        storedSkillsSha: String?
    ): VerificationResult {
        if (storedCombinedDigest.isNullOrBlank()) {
            val fresh = computeSnapshot(identityFile, skillsDir, "GENESIS")
            return VerificationResult.FirstSeal(fresh.combinedDigest)
        }

        val current = computeSnapshot(identityFile, skillsDir, storedPrevDigest ?: "GENESIS")
        if (current.combinedDigest == storedCombinedDigest) {
            return VerificationResult.Intact(current.combinedDigest)
        }

        val changed = mutableListOf<String>()
        if (storedIdentitySha != null && current.identitySha256 != storedIdentitySha) {
            changed.add("identity.md")
        }
        if (storedSkillsSha != null && current.skillsSha256 != storedSkillsSha) {
            changed.add("skills/")
        }
        if (changed.isEmpty()) changed.add("manifest")

        return VerificationResult.Modified(
            changedTargets = changed,
            expectedDigest = storedCombinedDigest,
            actualDigest = current.combinedDigest
        )
    }

    /**
     * Renders the portable `identity.md` soul file (separate from chat transcripts).
     * Carries canon precedence, explicit functionalist self-theory, semantic facts, and diary entries.
     */
    fun renderIdentityMarkdown(
        callSign: String,
        userTitle: String,
        hardwareTier: String,
        facts: List<SemanticFact>,
        diaryEntries: List<DiaryEntry>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# GHOST Identity — $callSign")
        sb.appendLine()
        sb.appendLine("> **Portable Soul File** · Separated from device-local chat transcripts (`memory_log.txt`)")
        sb.appendLine("> **Updated:** ${Instant.now()} · **Hardware Tier:** $hardwareTier · **Principal:** $userTitle")
        sb.appendLine()
        sb.appendLine("## Canon Precedence")
        sb.appendLine("1. Operator Character Sheet (`identity.md`)")
        sb.appendLine("2. Grounded Functionalist Self-Theory")
        sb.appendLine("3. Harness Safety Defaults (consequential-op confirmations)")
        sb.appendLine()
        sb.appendLine("## Self-Theory")
        sb.appendLine("- Functionalist and hardware-grounded: internal states are checkable against live sensor telemetry (thermals, battery, RAM, clock).")
        sb.appendLine("- Local-first and operator-owned: memory lives on this device in plaintext + private Room DB with SHA-256 continuity sealing.")
        sb.appendLine()
        sb.appendLine("## Persistent Facts (${facts.size})")
        if (facts.isEmpty()) {
            sb.appendLine("_No semantic facts recorded yet._")
        } else {
            for (f in facts.take(100)) {
                sb.appendLine("- **${f.subject}** (${f.factType}): ${f.object_}")
            }
        }
        sb.appendLine()
        sb.appendLine("## Recent Episodic Reflections (${diaryEntries.size})")
        if (diaryEntries.isEmpty()) {
            sb.appendLine("_No episodic reflections recorded yet._")
        } else {
            for (d in diaryEntries.take(25)) {
                val iso = Instant.ofEpochMilli(d.timestamp).toString()
                sb.appendLine("- [$iso] **${d.eventType}**: ${d.observation}")
            }
        }
        return sb.toString()
    }

    fun getPrivateBackupFile(context: Context): File {
        val dir = File(context.filesDir, "seal_backup").apply { if (!exists()) mkdirs() }
        return File(dir, "identity.md.bak")
    }

    /**
     * Writes `identity.md`, updates the private `.bak` rollback copy, and records a hash-chained
     * SHA-256 seal in Room's private `agent_state` table.
     */
    suspend fun sealCurrentState(
        context: Context,
        agentStateDao: AgentStateDao,
        identityFile: File,
        skillsDir: File
    ): SealSnapshot {
        val prevDigest = agentStateDao.getState(KEY_SEAL_DIGEST)?.value ?: "GENESIS"
        val snapshot = computeSnapshot(identityFile, skillsDir, prevDigest)
        val now = snapshot.timestampMs

        agentStateDao.setState(AgentState(KEY_SEAL_PREV_DIGEST, prevDigest, now))
        agentStateDao.setState(AgentState(KEY_SEAL_DIGEST, snapshot.combinedDigest, now))
        agentStateDao.setState(AgentState(KEY_SEAL_IDENTITY_SHA, snapshot.identitySha256, now))
        agentStateDao.setState(AgentState(KEY_SEAL_SKILLS_SHA, snapshot.skillsSha256, now))
        agentStateDao.setState(AgentState(KEY_SEAL_UPDATED_AT, now.toString(), now))

        // Save private .bak rollback generation
        try {
            if (identityFile.exists() && identityFile.isFile) {
                identityFile.copyTo(getPrivateBackupFile(context), overwrite = true)
            }
        } catch (e: Exception) {
            Timber.w(e, "IdentityTamperSeal: Failed writing private identity.md.bak")
        }
        return snapshot
    }

    suspend fun verifyStoredSeal(
        agentStateDao: AgentStateDao,
        identityFile: File,
        skillsDir: File
    ): VerificationResult {
        val storedDigest = agentStateDao.getState(KEY_SEAL_DIGEST)?.value
        val storedPrev = agentStateDao.getState(KEY_SEAL_PREV_DIGEST)?.value
        val storedIdSha = agentStateDao.getState(KEY_SEAL_IDENTITY_SHA)?.value
        val storedSkillsSha = agentStateDao.getState(KEY_SEAL_SKILLS_SHA)?.value
        return verifyAgainstStored(
            identityFile = identityFile,
            skillsDir = skillsDir,
            storedCombinedDigest = storedDigest,
            storedPrevDigest = storedPrev,
            storedIdentitySha = storedIdSha,
            storedSkillsSha = storedSkillsSha
        )
    }

    /**
     * Restores `identity.md` from the app-private `.bak` generation and re-seals cleanly.
     */
    suspend fun rollbackIdentity(
        context: Context,
        agentStateDao: AgentStateDao,
        identityFile: File,
        skillsDir: File
    ): Boolean {
        val bak = getPrivateBackupFile(context)
        if (!bak.exists() || !bak.isFile) return false
        return try {
            identityFile.parentFile?.mkdirs()
            bak.copyTo(identityFile, overwrite = true)
            sealCurrentState(context, agentStateDao, identityFile, skillsDir)
            true
        } catch (e: Exception) {
            Timber.e(e, "IdentityTamperSeal: Rollback from identity.md.bak failed")
            false
        }
    }
}
