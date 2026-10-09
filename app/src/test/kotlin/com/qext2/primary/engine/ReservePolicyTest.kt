package com.qext2.primary.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ReservePolicyTest {

    @Test
    fun effectiveLoadAddsDailyBaseAndSession() {
        assertEquals(84.5f, ReservePolicy.effectiveLoad(60f, 24.5f), 0.0001f)
    }

    @Test
    fun effectiveLoadSanitizesNegativeAndNan() {
        assertEquals(0f, ReservePolicy.effectiveLoad(-2f, Float.NaN), 0.0001f)
    }
}
