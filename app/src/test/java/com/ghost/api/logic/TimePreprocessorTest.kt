package com.ghost.api.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TimePreprocessorTest {

    @Test
    fun testSanitizeTimeTokens() {
        // Stuttering zeroes from BPE token splitting
        assertEquals("I'll set it for 8:00 PM.", TimePreprocessor.sanitizeTimeTokens("I'll set it for 8:0000 PM."))
        assertEquals("Wait, defaulting to 2:00.", TimePreprocessor.sanitizeTimeTokens("Wait, defaulting to 2:000."))
        assertEquals("8:00 PM", TimePreprocessor.sanitizeTimeTokens("8:000 PM"))
        assertEquals("8:00 PM", TimePreprocessor.sanitizeTimeTokens("8:0 PM"))
        
        // Normal times unaffected
        assertEquals("20:00", TimePreprocessor.sanitizeTimeTokens("20:00"))
        assertEquals("8:00 PM", TimePreprocessor.sanitizeTimeTokens("8:00 PM"))
        assertEquals("07:30", TimePreprocessor.sanitizeTimeTokens("07:30"))
    }

    @Test
    fun testParseTimeColonAndDot() {
        val p1 = TimePreprocessor.parseTime("set alarm for 8:00 PM")
        assertNotNull(p1)
        assertEquals(20, p1!!.hour24)
        assertEquals(0, p1.minute)
        assertEquals("8:00 PM", p1.formatted12h)
        assertEquals("20:00", p1.formatted24h)

        val p2 = TimePreprocessor.parseTime("set alarm for 8:0000 PM")
        assertNotNull(p2)
        assertEquals(20, p2!!.hour24)
        assertEquals(0, p2.minute)

        val p3 = TimePreprocessor.parseTime("alarm at 20:00")
        assertNotNull(p3)
        assertEquals(20, p3!!.hour24)
        assertEquals(0, p3.minute)

        val p4 = TimePreprocessor.parseTime("alarm at 8.30 pm")
        assertNotNull(p4)
        assertEquals(20, p4!!.hour24)
        assertEquals(30, p4.minute)
    }

    @Test
    fun testParseNaturalTimeWords() {
        val midnight = TimePreprocessor.parseTime("meet me at midnight")
        assertNotNull(midnight)
        assertEquals(0, midnight!!.hour24)
        assertEquals(0, midnight.minute)

        val noon = TimePreprocessor.parseTime("lunch at noon")
        assertNotNull(noon)
        assertEquals(12, noon!!.hour24)
        assertEquals(0, noon.minute)

        val halfPast = TimePreprocessor.parseTime("wake me up at half past 7")
        assertNotNull(halfPast)
        assertEquals(7, halfPast!!.hour24)
        assertEquals(30, halfPast.minute)

        val quarterTo = TimePreprocessor.parseTime("quarter to 9 pm")
        assertNotNull(quarterTo)
        assertEquals(20, quarterTo!!.hour24)
        assertEquals(45, quarterTo.minute)

        val quarterPast = TimePreprocessor.parseTime("quarter past 10")
        assertNotNull(quarterPast)
        assertEquals(10, quarterPast!!.hour24)
        assertEquals(15, quarterPast.minute)
    }

    @Test
    fun testParseSimpleAmPmAndContext() {
        val p1 = TimePreprocessor.parseTime("alarm for 8pm")
        assertNotNull(p1)
        assertEquals(20, p1!!.hour24)
        assertEquals(0, p1.minute)

        val p2 = TimePreprocessor.parseTime("wake me at 8 tonight")
        assertNotNull(p2)
        assertEquals(20, p2!!.hour24)
        assertEquals(0, p2.minute)

        val p3 = TimePreprocessor.parseTime("wake me at 7 morning")
        assertNotNull(p3)
        assertEquals(7, p3!!.hour24)
        assertEquals(0, p3.minute)
    }

    @Test
    fun testMilitaryTime() {
        val m1 = TimePreprocessor.parseTime("shift starts at 2000")
        assertNotNull(m1)
        assertEquals(20, m1!!.hour24)
        assertEquals(0, m1.minute)

        val m2 = TimePreprocessor.parseTime("drill at 0830")
        assertNotNull(m2)
        assertEquals(8, m2!!.hour24)
        assertEquals(30, m2.minute)
    }
}
