package com.qext2.primary.statsv2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsV2NewTest {

    @Test
    fun planDeltaFromEtaFixedAfterFirstKm() {
        StatsV2Extras.resetForTest()
        assertNull(StatsV2Extras.plan(1_000_000L, 0.5f).first)          // przed 1. km brak odniesienia
        assertEquals(0, StatsV2Extras.plan(1_000_000L, 1.2f).first)      // odniesienie ustalone
        assertEquals(14, StatsV2Extras.plan(1_000_000L + 14 * 60_000L, 40f).first)   // ETA pozniej o 14 min = za planem
        assertEquals(-5, StatsV2Extras.plan(1_000_000L - 5 * 60_000L, 41f).first)
        assertNull(StatsV2Extras.plan(2_000_000L, 0.1f).first)          // nowa jazda: odniesienie od nowa
    }

    @Test
    fun tempoPerMovingHour() {
        RideWindows.reset()
        var t = 1_000L
        var km = 0f
        for (s in 0 until 3600 + 600) {
            km += 20f / 3600f                       // 20 km/h
            RideWindows.feed(t, if (s < 3600) 200 else 150, null, true, km)
            t += 1000L
        }
        val (hours, partial) = RideWindows.tempoSnapshot()
        assertEquals(2, hours.size)
        assertTrue(partial)
        assertEquals(200, hours[0].first)
        assertEquals(20f, hours[0].second, 0.3f)
        assertEquals(150, hours[1].first)
    }
}
