package com.qext2.primary.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** TE SAME przypadki i wyniki co QBot tests/test_rsrv_v2.py (wzorzec fitmodel/rsrv_v2.py). */
class ReserveModelV2Test {

    private fun run(n: Int, p: Int, hr: Int, cp: Double = 240.0, lthr: Double = 148.0, base: Double = 0.0): Int {
        val s = ReserveModelV2.State()
        repeat(n) { ReserveModelV2.tick(s, p, hr, cp, lthr, 184) }
        return ReserveModelV2.percent(base, s.load)
    }

    @Test
    fun `curve points`() {
        assertEquals(1.0, ReserveModelV2.ratePerSecond(1.0) * 3600, 1e-9)
        assertEquals(0.25, ReserveModelV2.ratePerSecond(0.8) * 3600, 1e-9)
        assertEquals(1.0 / 13.0, ReserveModelV2.ratePerSecond(0.6) * 3600, 1e-9)
        assertEquals(0.0, ReserveModelV2.ratePerSecond(0.0), 0.0)
    }

    @Test
    fun `golden cases match QBot reference`() {
        assertEquals(75, run(7200, 168, 0))              // 2 h @ 0,7 CP
        assertEquals(75, run(3600, 192, 0))              // 1 h @ 0,8 CP
        assertEquals(86, run(3600, 144, 148))            // tetno na progu przy 0,6 CP -> podbija
        assertEquals(100, run(3600, 0, 0))               // 0 W -> bez ubytku
        assertEquals(45, run(3600, 192, 0, base = 0.3))  // druga jazda tego dnia
        assertEquals(92, run(3600, 144, 250))            // tetno spoza zakresu ignorowane
        assertEquals(90, run(3000, 168, 125))            // normalne tetno nie zmienia wyniku
    }

    @Test
    fun `hr never lowers reserve cost`() {
        assertEquals(run(3600, 192, 0), run(3600, 192, 90))
    }

    @Test
    fun `percent bounds`() {
        assertEquals(100, ReserveModelV2.percent(0.0, 0.0))
        assertEquals(0, ReserveModelV2.percent(0.9, 0.5))
    }
}
