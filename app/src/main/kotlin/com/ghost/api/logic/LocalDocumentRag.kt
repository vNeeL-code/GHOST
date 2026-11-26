package com.ghost.api.logic

import android.content.Context
import android.os.Environment
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import timber.log.Timber
import java.io.File
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LocalDocumentRag — On-Device PDF / Long-File Reader & Local Wiki / Static Cartridge RAG.
 *
 * Powered by [EmbeddingSidecar] (Gecko-110M 768-dim CPU XNNPACK when mounted, or 0 MB Hash-256 fallback).
 *
 * Solves two problems without ever overflowing Gemma 4's context window:
 * 1. Reading arbitrary long files & PDFs on-device (paginated window reading + semantic needle-in-a-haystack search).
 * 2. Searching portable local knowledge bases & static persona cartridges dropped in `Documents/GHOST/wiki/`
 *    (`.md`, `.txt`, `.pdf`, `llms.txt`, manuals, lore sheets) cleanly separated from mutable Room state.
 */
object LocalDocumentRag {

    private const val CHUNK_TARGET_CHARS = 520
    private const val CHUNK_OVERLAP_CHARS = 100
    private const val MAX_CHUNKS_PER_FILE = 400
    private const val MAX_CACHED_FILES = 12
    private const val MAX_WIKI_FILES = 40
    private const val MAX_FILE_BYTES = 20L * 1024L * 1024L
    private const val MAX_PDF_PAGES_PER_WINDOW = 3
    private const val MAX_TEXT_LINES_PER_WINDOW = 100

    private val SUPPORTED_WIKI_EXTS = setOf("md", "txt", "pdf", "json", "csv", "log", "yaml", "yml")
    private val pdfBoxInitialized = AtomicBoolean(false)

    data class DocumentChunk(
        val citation: String,
        val text: String,
        val vector: FloatArray
    )

    data class SearchHit(
        val citation: String,
        val text: String,
        val score: Float
    )

    data class ReadWindowResult(
        val content: String,
        val startOffset: Int,
        val unitsRead: Int,
        val totalUnits: Int,
        val unitLabel: String,
        val hasMore: Boolean
    )

    private data class CacheKey(
        val canonicalPath: String,
        val lastModified: Long,
        val fileSize: Long,
        val modelTag: String
    )

    private val indexCache = object : LinkedHashMap<CacheKey, List<DocumentChunk>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, List<DocumentChunk>>?): Boolean {
            return size > MAX_CACHED_FILES
        }
    }

    private fun ensurePdfBoxInitialized(context: Context?) {
        if (context == null) return
        if (pdfBoxInitialized.compareAndSet(false, true)) {
            try {
                PDFBoxResourceLoader.init(context.applicationContext)
            } catch (e: Throwable) {
                pdfBoxInitialized.set(false)
                Timber.w(e, "LocalDocumentRag: PDFBoxResourceLoader init failed")
            }
        }
    }

    /**
     * Returns `/sdcard/Documents/GHOST/wiki/` (falling back to app-scoped `GHOST/wiki/` if needed)
     * and seeds a short README explaining how to drop local docs or static persona cartridges.
     */
    fun getWikiDir(context: Context): File {
        val dir = try {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val publicWiki = File(docs, "GHOST/wiki")
            if (!publicWiki.exists()) publicWiki.mkdirs()
            if (publicWiki.exists() && publicWiki.canRead()) publicWiki else throw IllegalStateException("unreadable")
        } catch (_: Exception) {
            val fallback = context.getExternalFilesDir(null) ?: context.filesDir
            File(fallback, "GHOST/wiki").apply { if (!exists()) mkdirs() }
        }

        val readme = File(dir, "README_WIKI.md")
        if (!readme.exists()) {
            try {
                readme.writeText(
                    """
                    # GHOST Local Wiki & Static Cartridges

                    Drop any `.md`, `.txt`, `.pdf`, or static persona cartridge (`llms.txt`, manuals, notes, lore sheets) into this folder.

                    GHOST indexes these files on-device using your active embedding sidecar (Gecko-110M / EmbeddingGemma / Hash-256) and retrieves only the most relevant passages so long documents never overflow Gemma 4's context window.

                    Ask Gemma: *"Check my local wiki for..."* or *"Search <filename.pdf> for..."*
                    """.trimIndent(),
                    Charsets.UTF_8
                )
            } catch (_: Exception) {
                // Non-fatal on Scoped Storage ownership edge cases
            }
        }
        return dir
    }

    /**
     * Reads a paginated window of a local PDF (by page, 1-indexed) or text/markdown/code file (by line, 1-indexed).
     */
    fun readFileWindow(
        context: Context?,
        file: File,
        startOffset: Int = 1,
        maxUnits: Int? = null
    ): ReadWindowResult {
        if (file.length() > MAX_FILE_BYTES) {
            throw IllegalArgumentException("File exceeds ${MAX_FILE_BYTES / (1024 * 1024)} MB on-device safety cap (${file.name})")
        }
        val safeStart = startOffset.coerceAtLeast(1)
        return if (file.extension.equals("pdf", ignoreCase = true)) {
            readPdfWindow(context, file, safeStart, (maxUnits ?: MAX_PDF_PAGES_PER_WINDOW).coerceIn(1, 5))
        } else {
            readTextWindow(file, safeStart, (maxUnits ?: MAX_TEXT_LINES_PER_WINDOW).coerceIn(1, 200))
        }
    }

    private fun readPdfWindow(
        context: Context?,
        file: File,
        startPage: Int,
        maxPages: Int
    ): ReadWindowResult {
        ensurePdfBoxInitialized(context)
        PDDocument.load(file, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val totalPages = doc.numberOfPages
            if (totalPages == 0 || startPage > totalPages) {
                return ReadWindowResult(
                    content = "",
                    startOffset = startPage,
                    unitsRead = 0,
                    totalUnits = totalPages,
                    unitLabel = "pages",
                    hasMore = false
                )
            }
            val endPage = minOf(totalPages, startPage + maxPages - 1)
            val sb = StringBuilder()
            val stripper = PDFTextStripper()
            for (p in startPage..endPage) {
                stripper.startPage = p
                stripper.endPage = p
                val pageText = stripper.getText(doc).trim()
                sb.append("--- Page ").append(p).append(" / ").append(totalPages).append(" ---\n")
                sb.append(pageText.ifBlank { "[No selectable text on page $p]" }).append("\n\n")
            }
            val content = sb.toString().trim().take(6500)
            return ReadWindowResult(
                content = content,
                startOffset = startPage,
                unitsRead = (endPage - startPage + 1),
                totalUnits = totalPages,
                unitLabel = "pages",
                hasMore = endPage < totalPages
            )
        }
    }

    private fun readTextWindow(
        file: File,
        startLine: Int,
        maxLines: Int
    ): ReadWindowResult {
        val collected = mutableListOf<String>()
        var totalLines = 0
        file.bufferedReader(Charsets.UTF_8).useLines { seq ->
            for (line in seq) {
                totalLines++
                if (totalLines >= startLine && collected.size < maxLines) {
                    collected.add(line)
                }
            }
        }
        val content = collected.joinToString("\n").take(6500)
        return ReadWindowResult(
            content = content,
            startOffset = startLine,
            unitsRead = collected.size,
            totalUnits = totalLines,
            unitLabel = "lines",
            hasMore = (startLine - 1 + collected.size) < totalLines
        )
    }

    /**
     * Semantic + lexical needle-in-a-haystack search over a single PDF or text file.
     * Embeds & LRU-caches chunks with the active [EmbeddingSidecar] backend.
     */
    fun searchDocument(
        context: Context?,
        file: File,
        query: String,
        topK: Int = 5
    ): List<SearchHit> {
        if (query.isBlank() || !file.exists() || !file.isFile) return emptyList()
        val chunks = getOrBuildIndex(context, file, displayName = file.name)
        return rankChunks(chunks, query, topK)
    }

    /**
     * Searches all supported documents inside `Documents/GHOST/wiki/` (or a provided directory for testing).
     */
    fun searchWiki(
        context: Context?,
        query: String,
        topK: Int = 5,
        wikiRootOverride: File? = null
    ): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val root = wikiRootOverride ?: (context?.let { getWikiDir(it) } ?: return emptyList())
        if (!root.exists() || !root.isDirectory) return emptyList()

        val wikiFiles = mutableListOf<File>()
        fun collect(dir: File, depth: Int) {
            if (depth > 3 || wikiFiles.size >= MAX_WIKI_FILES) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    collect(f, depth + 1)
                } else if (f.isFile && f.extension.lowercase(Locale.ROOT) in SUPPORTED_WIKI_EXTS) {
                    if (f.name.equals("README_WIKI.md", ignoreCase = true) && children.size > 1) continue
                    wikiFiles.add(f)
                    if (wikiFiles.size >= MAX_WIKI_FILES) return
                }
            }
        }
        collect(root, 0)
        if (wikiFiles.isEmpty()) return emptyList()

        val allChunks = mutableListOf<DocumentChunk>()
        for (file in wikiFiles) {
            val relName = file.relativeTo(root).path.replace('\\', '/')
            allChunks.addAll(getOrBuildIndex(context, file, displayName = "wiki/$relName"))
        }
        return rankChunks(allChunks, query, topK)
    }

    /**
     * Pure-Kotlin overlapping text chunker (exposed internally for unit testing).
     */
    internal fun chunkLabeledBlocks(
        blocks: List<Pair<String, String>>,
        targetChars: Int = CHUNK_TARGET_CHARS,
        overlapChars: Int = CHUNK_OVERLAP_CHARS,
        maxChunks: Int = MAX_CHUNKS_PER_FILE
    ): List<Pair<String, String>> {
        val raw = mutableListOf<Pair<String, String>>()
        for ((label, rawText) in blocks) {
            val clean = rawText.replace("\r\n", "\n").replace(Regex("""[ \t]+"""), " ").trim()
            if (clean.isEmpty()) continue
            if (clean.length <= targetChars) {
                raw.add(label to clean)
                continue
            }
            var cursor = 0
            while (cursor < clean.length) {
                val hardEnd = minOf(clean.length, cursor + targetChars)
                val end = if (hardEnd < clean.length) {
                    val breakIdx = findCleanBreak(clean, cursor + (targetChars / 2), hardEnd)
                    if (breakIdx > cursor) breakIdx else hardEnd
                } else {
                    hardEnd
                }
                val slice = clean.substring(cursor, end).trim()
                if (slice.isNotEmpty()) {
                    raw.add(label to slice)
                }
                if (end >= clean.length) break
                cursor = (end - overlapChars).coerceAtLeast(cursor + 1)
            }
        }

        if (raw.size <= maxChunks) return raw
        // Stride-sample evenly across giant books so indexing stays bounded and covers the whole file
        val stride = raw.size.toDouble() / maxChunks.toDouble()
        return List(maxChunks) { idx ->
            raw[(idx * stride).toInt().coerceAtMost(raw.lastIndex)]
        }
    }

    private fun findCleanBreak(text: String, minPos: Int, maxPos: Int): Int {
        for (i in maxPos downTo minPos) {
            val c = text[i - 1]
            if (c == '\n' || c == '.' || c == '!' || c == '?') return i
        }
        for (i in maxPos downTo minPos) {
            if (text[i - 1].isWhitespace()) return i
        }
        return maxPos
    }

    private fun getOrBuildIndex(
        context: Context?,
        file: File,
        displayName: String
    ): List<DocumentChunk> {
        if (file.length() > MAX_FILE_BYTES) {
            Timber.w("LocalDocumentRag: Skipping ${file.name} (${file.length()} bytes > cap)")
            return emptyList()
        }
        val backend = EmbeddingSidecar.activeBackend
        val key = CacheKey(
            canonicalPath = try { file.canonicalPath } catch (_: Exception) { file.absolutePath },
            lastModified = file.lastModified(),
            fileSize = file.length(),
            modelTag = backend.modelTag
        )
        synchronized(indexCache) {
            indexCache[key]?.let { return it }
        }

        val labeledBlocks = try {
            extractLabeledBlocks(context, file, displayName)
        } catch (e: Exception) {
            Timber.w(e, "LocalDocumentRag: Failed extracting text from ${file.name}")
            emptyList()
        }
        if (labeledBlocks.isEmpty()) return emptyList()

        val chunkPairs = chunkLabeledBlocks(labeledBlocks)
        val embedded = chunkPairs.map { (citation, text) ->
            DocumentChunk(
                citation = citation,
                text = text,
                vector = backend.embedText(text)
            )
        }
        synchronized(indexCache) {
            indexCache[key] = embedded
        }
        Timber.i("LocalDocumentRag: Indexed ${embedded.size} chunks for $displayName (${backend.modelTag})")
        return embedded
    }

    private fun extractLabeledBlocks(
        context: Context?,
        file: File,
        displayName: String
    ): List<Pair<String, String>> {
        if (file.extension.equals("pdf", ignoreCase = true)) {
            ensurePdfBoxInitialized(context)
            val blocks = mutableListOf<Pair<String, String>>()
            PDDocument.load(file).use { doc ->
                val stripper = PDFTextStripper()
                val total = doc.numberOfPages
                for (p in 1..total) {
                    stripper.startPage = p
                    stripper.endPage = p
                    val pageText = stripper.getText(doc).trim()
                    if (pageText.isNotEmpty()) {
                        blocks.add("[$displayName p.$p]" to pageText)
                    }
                }
            }
            return blocks
        }

        // Plain text / markdown / code: group every ~25 lines into a labeled block
        val blocks = mutableListOf<Pair<String, String>>()
        val current = StringBuilder()
        var blockStartLine = 1
        var lineNo = 0
        file.bufferedReader(Charsets.UTF_8).useLines { seq ->
            for (line in seq) {
                lineNo++
                if (current.isEmpty()) blockStartLine = lineNo
                current.append(line).append('\n')
                if (current.length >= CHUNK_TARGET_CHARS || (lineNo - blockStartLine + 1) >= 25) {
                    blocks.add("[$displayName L$blockStartLine-L$lineNo]" to current.toString())
                    current.setLength(0)
                }
            }
        }
        if (current.isNotEmpty()) {
            blocks.add("[$displayName L$blockStartLine-L$lineNo]" to current.toString())
        }
        return blocks
    }

    private fun rankChunks(
        chunks: List<DocumentChunk>,
        query: String,
        topK: Int
    ): List<SearchHit> {
        if (chunks.isEmpty()) return emptyList()
        val queryVec = EmbeddingSidecar.embedText(query)
        val queryTokens = query.lowercase(Locale.ROOT)
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length >= 2 }
            .toSet()

        return chunks.map { chunk ->
            val semantic = EmbeddingSidecar.cosineSimilarity(queryVec, chunk.vector).coerceAtLeast(0f)
            val lexical = if (queryTokens.isEmpty()) {
                0f
            } else {
                val lower = chunk.text.lowercase(Locale.ROOT)
                val hits = queryTokens.count { lower.contains(it) }
                hits.toFloat() / queryTokens.size.toFloat()
            }
            val blended = (0.78f * semantic) + (0.22f * lexical)
            SearchHit(chunk.citation, chunk.text, blended)
        }
            .sortedByDescending { it.score }
            .take(topK.coerceIn(1, 8))
    }

    fun clearCache() {
        synchronized(indexCache) {
            indexCache.clear()
        }
    }
}
