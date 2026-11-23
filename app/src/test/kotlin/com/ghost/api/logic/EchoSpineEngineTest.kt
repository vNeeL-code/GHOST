package com.ghost.api.logic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoSpineEngineTest {

    private val defaultSensory = EchoSpineEngine.EchoSensoryState(
        batteryPercent = 82,
        isCharging = false,
        tempC = 31,
        ramUsedPercent = 64,
        isLateNight = false,
        nowFormatted = "Monday, November 23, 2026 · 6:08 PM",
        callSign = "Carolina",
        userTitle = "Operator"
    )

    @After
    fun tearDown() {
        EchoSpineEngine.activeClassifier = EchoSpineEngine.LexicalEchoClassifier
    }

    @Test
    fun `clock query returns exact weekday and timestamp without LLM`() {
        val resp = EchoSpineEngine.handlePureText(
            rawInput = "what day of the week is it?",
            reason = EchoSpineEngine.EchoReason.ECHO_TIER,
            sensory = defaultSensory
        )
        assertTrue("Expected weekday in response: ${resp.text}", resp.text.contains("Monday, November 23, 2026"))
        assertTrue(resp.text.contains("Operator"))
    }

    @Test
    fun `battery query reflects worried threshold at 20 percent`() {
        val lowBatt = defaultSensory.copy(batteryPercent = 18, isCharging = false)
        val resp = EchoSpineEngine.handlePureText(
            rawInput = "how much battery do we have?",
            reason = EchoSpineEngine.EchoReason.RAM_VALVE_SUSPENDED,
            sensory = lowBatt
        )
        assertTrue(resp.text.contains("18%"))
        assertTrue("Expected low-battery warning at <=20%: ${resp.text}", resp.text.contains("plug in", ignoreCase = true))
    }

    @Test
    fun `greeting adapts to late night and low battery sensory states`() {
        val lateNight = defaultSensory.copy(isLateNight = true)
        val nightResp = EchoSpineEngine.handlePureText(
            rawInput = "hey",
            reason = EchoSpineEngine.EchoReason.DOWNLOADING_WEIGHTS,
            sensory = lateNight
        )
        assertTrue(nightResp.text.contains("Operator"))

        val lowBatt = defaultSensory.copy(batteryPercent = 15, isCharging = false)
        val lowResp = EchoSpineEngine.handlePureText(
            rawInput = "hello",
            reason = EchoSpineEngine.EchoReason.ECHO_TIER,
            sensory = lowBatt
        )
        assertTrue("Expected low-battery greeting: ${lowResp.text}", lowResp.text.contains("15%"))
    }

    @Test
    fun `fallback honestly reports cortex state without faking generation`() {
        val resp = EchoSpineEngine.handlePureText(
            rawInput = "explain quantum chromodynamics in detail",
            reason = EchoSpineEngine.EchoReason.DOWNLOADING_WEIGHTS,
            sensory = defaultSensory
        )
        assertTrue(
            "Expected honest Echo explanation: ${resp.text}",
            resp.text.contains("spinal cord", ignoreCase = true) || resp.text.contains("Echo", ignoreCase = true)
        )
    }

    @Test
    fun `custom EchoIntentClassifier plugin overrides lexical matcher cleanly`() {
        EchoSpineEngine.activeClassifier = EchoSpineEngine.EchoIntentClassifier {
            EchoSpineEngine.EchoIntent.THERMAL_STATUS
        }
        val resp = EchoSpineEngine.handlePureText(
            rawInput = "totally arbitrary semantic phrasing",
            reason = EchoSpineEngine.EchoReason.ECHO_TIER,
            sensory = defaultSensory.copy(tempC = 55)
        )
        assertTrue(resp.text.contains("55°C"))
        assertTrue(resp.text.contains("hot", ignoreCase = true))
        assertEquals(resp.text, resp.spokenTts)
    }
}
