package com.ghost.api.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EmbeddingSidecarTest {

    @Test
    fun `embedText produces L2 unit-normalized 256-dim vector`() {
        val vec = EmbeddingSidecar.embedText("that cozy Italian restaurant with the outdoor patio")
        assertEquals(EmbeddingSidecar.DEFAULT_DIMS, vec.size)
        var sumSq = 0.0
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq)
        assertTrue("Expected unit L2 norm (~1.0), got $norm", norm in 0.99..1.01)
    }

    @Test
    fun `packFloatArray and unpackFloatArray roundtrip bit-identically`() {
        val original = EmbeddingSidecar.embedText("GHOST offline semantic memory sidecar")
        val blob = EmbeddingSidecar.packFloatArray(original)
        assertEquals(original.size * 4, blob.size)
        val restored = EmbeddingSidecar.unpackFloatArray(blob)
        assertEquals(original.size, restored.size)
        for (i in original.indices) {
            assertEquals(original[i], restored[i], 0.0f)
        }
    }

    @Test
    fun `cosineSimilarity ranks semantically related memory above unrelated memory`() {
        val query = EmbeddingSidecar.embedText("restaurant patio dinner")
        val related = EmbeddingSidecar.embedText("favorite Italian restaurant with a sunny patio for dinner")
        val unrelated = EmbeddingSidecar.embedText("bluetooth headphones disconnected at midnight")

        val relatedScore = EmbeddingSidecar.cosineSimilarity(query, related)
        val unrelatedScore = EmbeddingSidecar.cosineSimilarity(query, unrelated)
        assertTrue(
            "Expected related ($relatedScore) > unrelated ($unrelatedScore)",
            relatedScore > unrelatedScore + 0.15f
        )
    }

    @Test
    fun `SemanticEchoClassifier routes paraphrased intents beyond exact regexes`() {
        assertEquals(
            EchoSpineEngine.EchoIntent.BATTERY_STATUS,
            EmbeddingSidecar.SemanticEchoClassifier.classify("how many volts do we have left")
        )
        assertEquals(
            EchoSpineEngine.EchoIntent.THERMAL_STATUS,
            EmbeddingSidecar.SemanticEchoClassifier.classify("check cpu temperature")
        )
        assertEquals(
            EchoSpineEngine.EchoIntent.TIME_AND_DATE,
            EmbeddingSidecar.SemanticEchoClassifier.classify("check the clock")
        )
    }
}
