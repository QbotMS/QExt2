package com.qext2.primary.active

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BRAK RADARU / LAMPY (2026-10-14). */
class RadarMissingTest {
    private fun st(state: String, sinceS: Long, seen: Boolean, nowS: Long, elapsed: Long = 600L) = SensorState(
        speedKmh = 25.0, cadence = 85, hr = 130, power = 180, powerFreshnessMs = 1000L, cadenceFreshnessMs = 1000L,
        hrFreshnessMs = 1000L, hasRoute = true, elapsedSec = elapsed, nowMs = nowS * 1000L,
        radarState = state, radarSinceMs = sinceS * 1000L, radarSeen = seen)

    @Test fun rozlaczenie_w_trakcie_po_15_s_i_co_2_min() {
        val p = SensorMessageProducer()
        assertNull(p.checkAndProduce(st("SEARCHING", 1000, true, 1010)))
        assertEquals("BRAK RADARU", p.checkAndProduce(st("SEARCHING", 1000, true, 1016))?.title)
        assertNull(p.checkAndProduce(st("SEARCHING", 1000, true, 1060)))
        assertEquals("BRAK RADARU", p.checkAndProduce(st("SEARCHING", 1000, true, 1140))?.title)
    }

    @Test fun brak_od_startu_tylko_raz() {
        val p = SensorMessageProducer()
        assertEquals("BRAK RADARU", p.checkAndProduce(st("SEARCHING", 0, false, 1000))?.title)
        assertNull(p.checkAndProduce(st("SEARCHING", 0, false, 2000)))
    }

    @Test fun brak_radaru_w_urzadzeniach_cisza() {
        assertNull(SensorMessageProducer().checkAndProduce(st("NA", 0, false, 1000)))
    }

    @Test fun polaczony_cisza() {
        assertNull(SensorMessageProducer().checkAndProduce(st("OK", 0, true, 1000)))
    }
}
