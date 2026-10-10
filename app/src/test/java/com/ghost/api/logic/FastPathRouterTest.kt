package com.ghost.api.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPathRouterTest {

    @Test
    fun testBareUrlFullMatchBypassesLlm() {
        // Bare HTTPS URL
        val r1 = FastPathRouter.route("https://github.com/vNeeL-code/GHOST", InputSource.USER_TYPED)
        assertTrue("Bare URL should bypass LLM", r1 is RoutingResult.HandledFastPath)
        val h1 = r1 as RoutingResult.HandledFastPath
        assertEquals("OPEN_URL", h1.action)
        assertEquals("https://github.com/vNeeL-code/GHOST", h1.url)

        // Bare HTTP URL
        val r2 = FastPathRouter.route("http://example.com/test?query=1", InputSource.USER_TYPED)
        assertTrue(r2 is RoutingResult.HandledFastPath)

        // Bare domain without scheme (auto-normalized to https)
        val r3 = FastPathRouter.route("github.com/vNeeL-code/GHOST", InputSource.USER_TYPED)
        assertTrue(r3 is RoutingResult.HandledFastPath)
        assertEquals("https://github.com/vNeeL-code/GHOST", (r3 as RoutingResult.HandledFastPath).url)
    }

    @Test
    fun testUrlPlusQuestionDoesNotAutoLaunchAndAppendsHintToUserTurn() {
        val query = "https://github.com/vNeeL-code/GHOST what is this project about?"
        val result = FastPathRouter.route(query, InputSource.USER_TYPED)

        // MUST NOT auto-launch
        assertTrue("URL + question must NOT execute fast path", result is RoutingResult.FallThroughToLlm)
        val ft = result as RoutingResult.FallThroughToLlm

        // Entity must be cleanly extracted
        assertEquals("https://github.com/vNeeL-code/GHOST", ft.extractedEntities["url"])
        assertTrue(ft.toolHints.contains("web_fetch"))

        // Hint must be appended to the dynamic user message turn (preserving static system KV cache)
        assertTrue(ft.augmentedUserMessage.startsWith(query))
        assertTrue(ft.augmentedUserMessage.contains("[Router Context: Detected URL: https://github.com/vNeeL-code/GHOST"))
    }

    @Test
    fun testUntrustedSourcesNeverAutoLaunch() {
        val url = "https://malicious-site.xyz"

        // Notification listener input must NEVER auto-launch browser
        val rNotif = FastPathRouter.route(url, InputSource.NOTIFICATION)
        assertTrue("Notification source must fall through to LLM/reasoning", rNotif is RoutingResult.FallThroughToLlm)

        // API server pipe (localhost:8080) must NEVER auto-launch browser
        val rApi = FastPathRouter.route(url, InputSource.API_SERVER)
        assertTrue("API server source must fall through to LLM/reasoning", rApi is RoutingResult.FallThroughToLlm)

        // Background autonomous reflection must NEVER auto-launch
        val rBg = FastPathRouter.route(url, InputSource.BACKGROUND_TASK)
        assertTrue(rBg is RoutingResult.FallThroughToLlm)
    }

    @Test
    fun testVoiceAndOverlayAreTrustedForFastPath() {
        val url = "https://github.com/vNeeL-code/GHOST"

        val rVoice = FastPathRouter.route(url, InputSource.USER_VOICE)
        assertTrue(rVoice is RoutingResult.HandledFastPath)

        val rOverlay = FastPathRouter.route(url, InputSource.OVERLAY)
        assertTrue(rOverlay is RoutingResult.HandledFastPath)
    }

    @Test
    fun testSchemeAllowlistRejectsDangerousSchemes() {
        // file:// rejection
        val rFile = FastPathRouter.route("file:///etc/hosts", InputSource.USER_TYPED)
        assertTrue(rFile is RoutingResult.FallThroughToLlm)

        // intent:// rejection
        val rIntent = FastPathRouter.route("intent://#Intent;action=android.intent.action.VIEW;end", InputSource.USER_TYPED)
        assertTrue(rIntent is RoutingResult.FallThroughToLlm)

        // javascript: rejection
        val rJs = FastPathRouter.route("javascript:alert(1)", InputSource.USER_TYPED)
        assertTrue(rJs is RoutingResult.FallThroughToLlm)
    }

    @Test
    fun testSettingsKillSwitch() {
        val url = "https://github.com/vNeeL-code/GHOST"
        val result = FastPathRouter.route(url, InputSource.USER_TYPED, isFastPathEnabled = false)
        assertTrue("Kill-switch must disable fast path", result is RoutingResult.FallThroughToLlm)
        assertEquals(url, (result as RoutingResult.FallThroughToLlm).augmentedUserMessage)
    }

    @Test
    fun testHardwareMinimalPairs() {
        // Anchored volume command -> Fast Path
        val v1 = FastPathRouter.route("volume 50%", InputSource.USER_TYPED)
        assertTrue(v1 is RoutingResult.HandledFastPath)
        assertEquals("SET_VOLUME", (v1 as RoutingResult.HandledFastPath).action)

        // Volume with condition / conversational context -> Fall Through to LLM
        val v2 = FastPathRouter.route("volume 50% but only if battery is above 30%", InputSource.USER_TYPED)
        assertTrue("Conditioned command must fall through to LLM", v2 is RoutingResult.FallThroughToLlm)

        // Anchored flashlight -> Fast Path
        val f1 = FastPathRouter.route("flashlight on", InputSource.USER_TYPED)
        assertTrue(f1 is RoutingResult.HandledFastPath)
        assertEquals("TOGGLE_FLASHLIGHT", (f1 as RoutingResult.HandledFastPath).action)

        // Conversational flashlight -> Fall Through
        val f2 = FastPathRouter.route("flashlight on when I open the front door", InputSource.USER_TYPED)
        assertTrue(f2 is RoutingResult.FallThroughToLlm)
    }
}
