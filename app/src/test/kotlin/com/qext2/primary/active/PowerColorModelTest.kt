package com.qext2.primary.active

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerColorModelTest {
    private fun c(p30: Float, climbing: Boolean = false, wbal: Int = 90, over: Long = 0L, alarm: Boolean = false) =
        PowerColorModel.classify(p30, 194f, 210f, climbing, wbal, over, alarm)

    @Test fun plasko_slabo_bialy() = assertEquals("LOW", c(140f))
    @Test fun plasko_pasmo_zielony() { assertEquals("GREEN", c(150f)); assertEquals("GREEN", c(209f)) }
    @Test fun plasko_nad_sufitem_zolty_potem_czerwony() { assertEquals("YELLOW", c(215f, over = 5_000L)); assertEquals("RED", c(215f, over = 12_000L)) }
    @Test fun alarm_ma_pierwszenstwo() = assertEquals("ALARM", c(150f, alarm = true))
    @Test fun podjazd_wg_wprim() {
        assertEquals("GREEN", c(300f, climbing = true, wbal = 70))
        assertEquals("YELLOW", c(300f, climbing = true, wbal = 40))
        assertEquals("RED", c(300f, climbing = true, wbal = 20))
    }
    @Test fun auto_bez_obnizki() {
        assertEquals(1.0f, AdaptiveModeTracker.modeFactorFor(3, 0.88f), 0.001f)
        assertEquals(1.12f, AdaptiveModeTracker.modeFactorFor(3, 1.12f), 0.001f)
        assertEquals(0.88f, AdaptiveModeTracker.modeFactorFor(0, 1.0f), 0.001f)
    }
}
