package com.qext2.primary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideDataAggregatorSdkPolicyTest {

    @Test
    fun `navigating route yields NAV source and effective true`() {
        val d = RideDataAggregator.routeStateDecision(
            rawRoute = true,
            lastRouteSeenMs = 0L,
            nowMs = 100_000L,
            graceMs = 12_000L,
        )
        assertTrue(d.effectiveRoute)
        assertEquals("NAV", d.source)
    }

    @Test
    fun `idle within grace yields GRACE source`() {
        val d = RideDataAggregator.routeStateDecision(
            rawRoute = false,
            lastRouteSeenMs = 100_000L,
            nowMs = 110_000L,
            graceMs = 12_000L,
        )
        assertTrue(d.effectiveRoute)
        assertEquals("GRACE", d.source)
    }

    @Test
    fun `idle after grace yields MISSING source`() {
        val d = RideDataAggregator.routeStateDecision(
            rawRoute = false,
            lastRouteSeenMs = 100_000L,
            nowMs = 120_001L,
            graceMs = 12_000L,
        )
        assertFalse(d.effectiveRoute)
        assertEquals("MISSING", d.source)
    }

    @Test
    fun `distance to destination alone creates route for POI nav`() {
        assertTrue(RideDataAggregator.resolveHasRoute(effectiveRoute = false, distanceToDestinationMeters = 2500.0))
    }

    @Test
    fun `elapsed parser treats millisecond outlier as ms`() {
        val parsed = RideDataAggregator.parseElapsed(raw = 16_000.0, localGuessSec = 77L)
        assertEquals(16L, parsed.chosenSec)
        assertEquals("ms", parsed.unit)
    }

    @Test
    fun `start plan recreates scope when inactive`() {
        val plan = RideDataAggregator.planStart(
            hasConsumers = false,
            tickActive = false,
            scopeActive = false,
        )
        assertFalse(plan.stopBeforeStart)
        assertTrue(plan.recreateScope)
    }

    @Test
    fun `start plan restarts running ticker before new start`() {
        val plan = RideDataAggregator.planStart(
            hasConsumers = true,
            tickActive = true,
            scopeActive = true,
        )
        assertTrue(plan.stopBeforeStart)
        assertFalse(plan.recreateScope)
    }
}
