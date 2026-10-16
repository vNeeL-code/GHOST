package com.ghost.api.logic

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentBaggerTest {

    private val dummyContext: Context = ContextWrapper(null)

    @Test
    fun testExactAppLaunchIsWireSpeedDirect() {
        val intents = IntentBagger.bagIntents(dummyContext, "open camera")
        assertEquals(1, intents.size)
        val intent = intents[0]
        assertEquals("open_app", intent.tool)
        assertEquals("Camera", intent.appLabel)
        assertTrue("Exact alias/known app must be wire-speed direct", intent.isWireSpeedDirect)
    }

    @Test
    fun testTypoAppLaunchDropsToCognitiveReAct() {
        val intents = IntentBagger.bagIntents(dummyContext, "open cammera")
        assertEquals(1, intents.size)
        val intent = intents[0]
        assertEquals("open_app", intent.tool)
        assertEquals("Cammera", intent.appLabel)
        assertFalse("Typo app must NOT be wire-speed direct (should trigger LLM ReAct loop)", intent.isWireSpeedDirect)
    }

    @Test
    fun testConversationalAppMentionsNeverTriggerWireSpeed() {
        // "just the auto browser pipe was stuck failing to open browser"
        val intents1 = IntentBagger.bagIntents(dummyContext, "just the auto browser pipe was stuck failing to open browser")
        val direct1 = intents1.firstOrNull { it.isWireSpeedDirect }
        assertTrue("Conversational mention of browser must NEVER trigger wire-speed direct", direct1 == null)

        // "ah as in you didnt need to launch browser again but im assuming that was a reflex"
        val intents2 = IntentBagger.bagIntents(dummyContext, "ah as in you didnt need to launch browser again but im assuming that was a reflex")
        val direct2 = intents2.firstOrNull { it.isWireSpeedDirect }
        assertTrue("Negated/conversational launch of browser must NEVER trigger wire-speed direct", direct2 == null)

        // "why did chrome crash"
        val intents3 = IntentBagger.bagIntents(dummyContext, "why did chrome crash")
        val direct3 = intents3.firstOrNull { it.isWireSpeedDirect }
        assertTrue("Question about chrome crash must NEVER trigger wire-speed direct", direct3 == null)
    }

    @Test
    fun testBareAppAliasIsWireSpeedDirect() {
        val intents = IntentBagger.bagIntents(dummyContext, "chrome")
        val intent = intents.firstOrNull { it.appLabel == "Chrome" }
        assertNotNull(intent)
        assertTrue("Bare app alias in isolation must be wire-speed direct", intent!!.isWireSpeedDirect)
    }

    @Test
    fun testWebUrlIsWireSpeedDirect() {
        val intents = IntentBagger.bagIntents(dummyContext, "go to https://github.com")
        assertEquals(1, intents.size)
        val intent = intents[0]
        assertEquals("open_system_browser_bar", intent.tool)
        assertEquals("https://github.com", intent.browserUrl)
        assertTrue("Direct web URLs must be wire-speed direct", intent.isWireSpeedDirect)
    }

    @Test
    fun testWebUrlWithConversationalContextIsNotWireSpeedDirect() {
        val intents = IntentBagger.bagIntents(dummyContext, "what's wrong with https://github.com")
        val intent = intents.firstOrNull { it.browserUrl == "https://github.com" }
        assertNotNull(intent)
        assertFalse("URL inside conversational question must NOT be wire-speed direct", intent!!.isWireSpeedDirect)
    }

    @Test
    fun testVolumeIntents() {
        val muteIntents = IntentBagger.bagIntents(dummyContext, "mute volume")
        val mute = muteIntents.firstOrNull { it.tool == "volume" }
        assertNotNull(mute)
        assertTrue(mute!!.paramsJson.contains("action=mute"))

        val upIntents = IntentBagger.bagIntents(dummyContext, "turn volume up")
        val up = upIntents.firstOrNull { it.tool == "volume" }
        assertNotNull(up)
        assertTrue(up!!.paramsJson.contains("action=up"))

        val levelIntents = IntentBagger.bagIntents(dummyContext, "set volume to 75")
        val level = levelIntents.firstOrNull { it.tool == "volume" }
        assertNotNull(level)
        assertTrue(level!!.paramsJson.contains("level=75"))
    }

    @Test
    fun testStatusIntent() {
        val batteryIntents = IntentBagger.bagIntents(dummyContext, "check battery")
        val status = batteryIntents.firstOrNull { it.tool == "status" }
        assertNotNull(status)
        assertFalse("Status checks should pass through LLM envelope", status!!.isWireSpeedDirect)
    }

    @Test
    fun testFormatPromptEnvelopeCore6Syntax() {
        val appIntent = IntentBagger.BaggedIntent(
            tool = "open_app",
            paramsJson = "{\"name\":\"Camera\"}",
            hint = "Launch Camera app immediately",
            appLabel = "Camera"
        )
        val volIntent = IntentBagger.BaggedIntent(
            tool = "volume",
            paramsJson = "stream=media level=50",
            hint = "Adjust volume"
        )
        val statusIntent = IntentBagger.BaggedIntent(
            tool = "status",
            paramsJson = "",
            hint = "Check telemetry"
        )
        val torchIntent = IntentBagger.BaggedIntent(
            tool = "flashlight",
            paramsJson = "{\"state\":\"ON\"}",
            hint = "Turn on torch"
        )
        val alarmIntent = IntentBagger.BaggedIntent(
            tool = "alarm",
            paramsJson = "{\"hour\":7,\"minutes\":30}",
            hint = "Set alarm"
        )

        val envelope = IntentBagger.formatPromptEnvelope(listOf(appIntent, volIntent, statusIntent, torchIntent, alarmIntent))
        assertTrue(envelope.contains("open_app(\"Camera\")"))
        assertTrue(envelope.contains("execute_command(\"volume\", \"stream=media level=50\")"))
        assertTrue(envelope.contains("execute_command(\"status\", \"\")"))
        assertTrue(envelope.contains("toggle_torch(enabled = true)"))
        assertTrue(envelope.contains("set_alarm(hour = 7, minute = 30)"))
    }
}
