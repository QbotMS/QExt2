package com.qext2.primary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun applySleepRefreshAtActivityStartWhenPendingAndStationary() {
        val shouldApply = ReservePolicy.shouldApplySleepRefresh(
            sleepRefreshPending = true,
            isMoving = false,
            elapsedSec = 20L,
            stopDurationSec = 0L,
            minStopForRefreshSec = 5_400L,
        )
        assertTrue(shouldApply)
    }

    @Test
    fun applySleepRefreshAfterLongStopWhenPending() {
        val shouldApply = ReservePolicy.shouldApplySleepRefresh(
            sleepRefreshPending = true,
            isMoving = false,
            elapsedSec = 4_000L,
            stopDurationSec = 6_000L,
            minStopForRefreshSec = 5_400L,
        )
        assertTrue(shouldApply)
    }

    @Test
    fun noSleepRefreshWhileMoving() {
        val shouldApply = ReservePolicy.shouldApplySleepRefresh(
            sleepRefreshPending = true,
            isMoving = true,
            elapsedSec = 10L,
            stopDurationSec = 10_000L,
            minStopForRefreshSec = 5_400L,
        )
        assertFalse(shouldApply)
    }

    @Test
    fun noSleepRefreshWithoutPendingFlag() {
        val shouldApply = ReservePolicy.shouldApplySleepRefresh(
            sleepRefreshPending = false,
            isMoving = false,
            elapsedSec = 4_000L,
            stopDurationSec = 10_000L,
            minStopForRefreshSec = 5_400L,
        )
        assertFalse(shouldApply)
    }
}
