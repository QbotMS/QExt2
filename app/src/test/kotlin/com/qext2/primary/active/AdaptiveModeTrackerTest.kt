package com.qext2.primary.active

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveModeTrackerTest {
    private val MIN = 60_000L

    @Test fun `mapowanie trybow z SETUP`() {
        assertEquals(0.88f, AdaptiveModeTracker.modeFactorFor(0, 1f), 0f)
        assertEquals(1.00f, AdaptiveModeTracker.modeFactorFor(1, 0.88f), 0f)
        assertEquals(1.12f, AdaptiveModeTracker.modeFactorFor(2, 1f), 0f)
        assertEquals(0.88f, AdaptiveModeTracker.modeFactorFor(3, 0.88f), 0f)
    }

    @Test fun `bez trasy AUTO = normalny`() {
        val t = AdaptiveModeTracker()
        var f = 0f
        for (m in 0..60) f = t.update(m * MIN, 100 - m, null, false)
        assertEquals(1.00f, f, 0f)
    }

    @Test fun `szybki spadek i dlugo do mety daje ostrozny po histerezie`() {
        val t = AdaptiveModeTracker()
        var f = 0f
        // spadek 1 %/min (60 %/h), do mety 2 h -> prognoza ujemna
        for (m in 0..40) f = t.update(m * MIN, 100 - m, 2 * 3600.0, true)
        assertEquals(0.88f, f, 0f)
    }

    @Test fun `wolny spadek i blisko mety daje ofensywny`() {
        val t = AdaptiveModeTracker()
        var f = 0f
        for (m in 0..40) f = t.update(m * MIN, 95 - m / 10, 600.0, true)
        assertEquals(1.12f, f, 0f)
    }

    @Test fun `przed 5 min stabilnej decyzji tryb sie nie zmienia`() {
        val t = AdaptiveModeTracker()
        var f = 0f
        for (m in 0..13) f = t.update(m * MIN, 100 - m, 2 * 3600.0, true)
        assertEquals(1.00f, f, 0f)
    }
}
