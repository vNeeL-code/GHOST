package com.ghost.api.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDocumentRagTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        EmbeddingSidecar.activeBackend = EmbeddingSidecar.LocalSemanticHashBackend
        LocalDocumentRag.clearCache()
    }

    @Test
    fun testReadTextWindowPaginatesLongFile() {
        val file = tempFolder.newFile("long_notes.txt")
        file.writeText((1..240).joinToString("\n") { "Line $it: telemetry record alpha-$it" })

        val page1 = LocalDocumentRag.readFileWindow(context = null, file = file, startOffset = 1, maxUnits = 100)
        assertEquals(100, page1.unitsRead)
        assertEquals(240, page1.totalUnits)
        assertEquals("lines", page1.unitLabel)
        assertTrue(page1.hasMore)
        assertTrue(page1.content.contains("Line 1:"))
        assertTrue(page1.content.contains("Line 100:"))
        assertFalse(page1.content.contains("Line 101:"))

        val page3 = LocalDocumentRag.readFileWindow(context = null, file = file, startOffset = 201, maxUnits = 100)
        assertEquals(40, page3.unitsRead)
        assertFalse(page3.hasMore)
        assertTrue(page3.content.contains("Line 240:"))
    }

    @Test
    fun testSearchDocumentFindsNeedleInLongTextFile() {
        val file = tempFolder.newFile("handbook.md")
        val sections = buildString {
            repeat(60) { i ->
                append("Section $i: Routine maintenance checklist for coolant pumps, gaskets, and standard bolts.\n")
            }
            append("\nSection 61: The emergency override passphrase for the orbital airlock is CRIMSON-FALCON-42.\n\n")
            repeat(60) { i ->
                append("Section ${62 + i}: Daily battery inspection logs and ambient room humidity readings.\n")
            }
        }
        file.writeText(sections)

        val hits = LocalDocumentRag.searchDocument(
            context = null,
            file = file,
            query = "emergency override passphrase orbital airlock",
            topK = 3
        )
        assertTrue("Expected hits for needle query", hits.isNotEmpty())
        assertTrue(
            "Top hit should contain the needle passphrase, got: ${hits.first().text}",
            hits.first().text.contains("CRIMSON-FALCON-42")
        )
        assertTrue(hits.first().citation.startsWith("[handbook.md L"))
    }

    @Test
    fun testSearchWikiAcrossMultipleCartridgeFiles() {
        val wikiDir = tempFolder.newFolder("wiki")
        java.io.File(wikiDir, "llms.txt").writeText(
            "# Carolina Cartridge\nCallsign: Carolina. Hardware chassis: RedMagic 10 Air with Gecko-110M sidecar.\n"
        )
        java.io.File(wikiDir, "recipes.md").writeText(
            "# Campfire Coffee\nBoil 250ml spring water, steep coarse dark roast for four minutes.\n"
        )
        java.io.File(wikiDir, "freelancer_lore.md").writeText(
            "# Model 44 Sidearm\nThe Charon Model 44 sidearm does not jam; computer-assisted aim can be overloaded.\n"
        )

        val hits = LocalDocumentRag.searchWiki(
            context = null,
            query = "Charon Model 44 sidearm jam computer assisted aim",
            topK = 2,
            wikiRootOverride = wikiDir
        )
        assertTrue(hits.isNotEmpty())
        assertTrue(
            "Top wiki hit should come from freelancer_lore.md, got: ${hits.first().citation}",
            hits.first().citation.contains("freelancer_lore.md")
        )
        assertTrue(hits.first().text.contains("Model 44"))
    }

    @Test
    fun testChunkLabeledBlocksPreservesOverlapAndRespectsCap() {
        val bigText = (1..400).joinToString(" ") { "word$it" }
        val chunks = LocalDocumentRag.chunkLabeledBlocks(
            blocks = listOf("[manual.pdf p.1]" to bigText),
            targetChars = 300,
            overlapChars = 60,
            maxChunks = 5
        )
        assertEquals(5, chunks.size)
        assertTrue(chunks.all { it.first == "[manual.pdf p.1]" && it.second.isNotBlank() })
    }
}
