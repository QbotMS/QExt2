package com.qext2.primary.statsv2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanProfileTest {
    @Test
    fun interpolatesPlanAtKm() {
        val its = listOf(floatArrayOf(1f, 3f, 0f), floatArrayOf(11f, 33f, 4f), floatArrayOf(21f, 63f, 8f))
        val p = PlanProfile.interp(its, 16f)!!
        assertEquals(48f, p.first, 0.01f)
        assertEquals(6f, p.second, 0.01f)
        assertEquals(63f, PlanProfile.interp(its, 30f)!!.first, 0.01f)
        assertNull(PlanProfile.interp(emptyList(), 5f))
    }
}
