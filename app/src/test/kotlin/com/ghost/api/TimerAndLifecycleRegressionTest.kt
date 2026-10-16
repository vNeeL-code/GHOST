package com.ghost.api

import com.ghost.api.hardware.SystemToolSet
import com.ghost.api.logic.ActionFlavorTexts
import com.ghost.api.logic.FastPathRouter
import com.ghost.api.logic.InputSource
import com.ghost.api.logic.RoutingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerAndLifecycleRegressionTest {

    @Test
    fun `parseDurationSeconds handles natural word and numeric durations`() {
        assertEquals(3600, SystemToolSet.parseDurationSeconds("an hour"))
        assertEquals(3600, SystemToolSet.parseDurationSeconds("set a timer for an hour"))
        assertEquals(3600, SystemToolSet.parseDurationSeconds("one hour"))
        assertEquals(3600, SystemToolSet.parseDurationSeconds("1 hour"))
        assertEquals(60, SystemToolSet.parseDurationSeconds("a minute"))
        assertEquals(60, SystemToolSet.parseDurationSeconds("one minute"))
        assertEquals(1800, SystemToolSet.parseDurationSeconds("half an hour"))
        assertEquals(1800, SystemToolSet.parseDurationSeconds("half hour"))
        assertEquals(900, SystemToolSet.parseDurationSeconds("quarter of an hour"))
        assertEquals(900, SystemToolSet.parseDurationSeconds("quarter hour"))
        assertEquals(5400, SystemToolSet.parseDurationSeconds("an hour and a half"))
        assertEquals(5400, SystemToolSet.parseDurationSeconds("one and a half hours"))
        assertEquals(5400, SystemToolSet.parseDurationSeconds("1.5 hours"))
        assertEquals(5400, SystemToolSet.parseDurationSeconds("1 hour 30 minutes"))
        assertEquals(150, SystemToolSet.parseDurationSeconds("2 minutes 30 seconds"))
        assertEquals(600, SystemToolSet.parseDurationSeconds("10m"))
        assertEquals(45, SystemToolSet.parseDurationSeconds("45s"))
        assertEquals(2700, SystemToolSet.parseDurationSeconds("forty five minutes"))
        assertNull(SystemToolSet.parseDurationSeconds("hello world"))
    }

    @Test
    fun `parseTimerParams handles explicit and natural parameter maps`() {
        assertEquals(Pair(3600, ""), SystemToolSet.parseTimerParams(mapOf("seconds" to 3600)))
        assertEquals(Pair(3600, "Tea"), SystemToolSet.parseTimerParams(mapOf("hours" to 1, "label" to "Tea")))
        assertEquals(Pair(3600, ""), SystemToolSet.parseTimerParams(mapOf("minutes" to 60)))
        assertEquals(Pair(5400, ""), SystemToolSet.parseTimerParams(mapOf("hours" to 1, "minutes" to 30)))
        assertEquals(Pair(3600, ""), SystemToolSet.parseTimerParams(mapOf("duration" to "an hour")))
        assertEquals(Pair(3600, ""), SystemToolSet.parseTimerParams(mapOf("input" to "1 hour")))
        assertEquals(Pair(1800, ""), SystemToolSet.parseTimerParams(mapOf("input" to "half an hour")))
    }

    @Test
    fun `formatDuration formats seconds cleanly`() {
        assertEquals("1 hour", SystemToolSet.formatDuration(3600))
        assertEquals("2 hours", SystemToolSet.formatDuration(7200))
        assertEquals("1 minute", SystemToolSet.formatDuration(60))
        assertEquals("1 hour 30 minutes", SystemToolSet.formatDuration(5400))
        assertEquals("1 minute 30 seconds", SystemToolSet.formatDuration(90))
        assertEquals("45 seconds", SystemToolSet.formatDuration(45))
    }

    @Test
    fun `kinetic and presence barks generate non-blank persona flavor lines`() {
        assertTrue(ActionFlavorTexts.impactSpikeTts("Operator").isNotBlank())
        assertTrue(ActionFlavorTexts.continuousShakeTts("Operator").isNotBlank())
        assertTrue(ActionFlavorTexts.deviceUnlockedTts("Operator").isNotBlank())
        assertTrue(ActionFlavorTexts.deviceLockedTts("Operator").isNotBlank())
    }

    @Test
    fun `fastPathRouter populates spokenTts for deterministic hardware actions`() {
        val volRes = FastPathRouter.route("volume 50%", InputSource.USER_VOICE) as RoutingResult.HandledFastPath
        assertEquals("SET_VOLUME", volRes.action)
        assertNotNull(volRes.spokenTts)
        assertTrue(volRes.spokenTts!!.isNotBlank())

        val torchRes = FastPathRouter.route("torch on", InputSource.USER_VOICE) as RoutingResult.HandledFastPath
        assertEquals("TOGGLE_FLASHLIGHT", torchRes.action)
        assertNotNull(torchRes.spokenTts)
        assertTrue(torchRes.spokenTts!!.isNotBlank())
    }
}
