package com.qext2.primary.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RsrvDisplayPolicyTest {

    @Test
    fun `route true and elapsed below 120 shows WAIT`() {
        val d = RsrvDisplayPolicy.decide(
            route = true,
            elapsedSec = 40L,
            reservePercent = 0,
            npWatts = 200,
            ifValue = 0.8f,
            wBalancePercent = 70,
            carbsGph = 60,
        )
        assertEquals("WAIT", d.value)
        assertFalse(d.valid)
        assertEquals("model_not_ready", d.reason)
    }

    @Test
    fun `route true with missing inputs shows WAIT`() {
        val d = RsrvDisplayPolicy.decide(
            route = true,
            elapsedSec = 240L,
            reservePercent = 30,
            npWatts = 0,
            ifValue = 0f,
            wBalancePercent = -1,
            carbsGph = 0,
        )
        assertEquals("WAIT", d.value)
        assertFalse(d.valid)
        assertEquals("model_not_ready", d.reason)
    }

    @Test
    fun `model ready with calculated zero shows valid 0 percent`() {
        val d = RsrvDisplayPolicy.decide(
            route = true,
            elapsedSec = 240L,
            reservePercent = 0,
            npWatts = 210,
            ifValue = 0.82f,
            wBalancePercent = 65,
            carbsGph = 70,
        )
        assertEquals("0%", d.value)
        assertTrue(d.valid)
        assertEquals("calculated", d.reason)
    }

    @Test
    fun `model ready with positive reserve shows valid percent`() {
        val d = RsrvDisplayPolicy.decide(
            route = true,
            elapsedSec = 240L,
            reservePercent = 42,
            npWatts = 210,
            ifValue = 0.82f,
            wBalancePercent = 65,
            carbsGph = 70,
        )
        assertEquals("42%", d.value)
        assertTrue(d.valid)
        assertEquals("calculated", d.reason)
    }
}
