package com.qext2.primary.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WindUnitsTest {
    @Test fun `wszystkie jednostki na m s`() {
        assertEquals(2.186, WindUnits.toMps(7.87, 0), 0.01)    // log 08.10: 7.87 km/h = 2.2 m/s
        assertEquals(5.0, WindUnits.toMps(5.0, 1), 1e-9)
        assertEquals(4.4704, WindUnits.toMps(10.0, 2), 1e-4)
        assertEquals(5.14444, WindUnits.toMps(10.0, 3), 1e-4)
    }
}
