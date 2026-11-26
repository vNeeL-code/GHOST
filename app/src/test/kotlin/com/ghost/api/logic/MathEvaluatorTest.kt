package com.ghost.api.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathEvaluatorTest {

    @Test
    fun testBasicArithmeticAndPrecedence() {
        assertEquals("14", MathEvaluator.evaluate("2 + 3 * 4").getOrThrow())
        assertEquals("20", MathEvaluator.evaluate("(2 + 3) * 4").getOrThrow())
        assertEquals("9716", MathEvaluator.evaluate("347 * 28").getOrThrow())
        assertEquals("16", MathEvaluator.evaluate("2 ^ 4").getOrThrow())
        assertEquals("9", MathEvaluator.evaluate("sqrt(81)").getOrThrow())
    }

    @Test
    fun testPercentagesAndRestaurantTips() {
        assertEquals("13.23", MathEvaluator.evaluate("18% tip on $73.50").getOrThrow())
        assertEquals("12", MathEvaluator.evaluate("15% of 80").getOrThrow())
        assertEquals("92", MathEvaluator.evaluate("80 + 15%").getOrThrow())
        assertEquals("80", MathEvaluator.evaluate("100 - 20%").getOrThrow())
    }

    @Test
    fun testDivisionByZeroFailsCleanly() {
        assertTrue(MathEvaluator.evaluate("42 / 0").isFailure)
    }
}
