package com.qext2.primary.active

import com.qext2.primary.engine.KarooClimb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveClimbResolverTest {

    @Test
    fun `real mode with sdk climbs empty returns no message source`() {
        val r = ActiveClimbResolver.resolve(
            nowMs = 1_000L,
            fakeMode = false,
            hasRoute = true,
            navClimbs = emptyList(),
            distanceMeters = 5_000.0,
            distanceToDestinationMeters = 2_000.0,
            ascentLeftM = 200,
            effectiveGrade = 4.0,
        )
        assertNull(r.state)
        assertEquals("no_sdk_climbs", r.reason)
    }

    @Test
    fun `fake mode can generate synthetic climb`() {
        val r = ActiveClimbResolver.resolve(
            nowMs = 1_000L,
            fakeMode = true,
            hasRoute = true,
            navClimbs = emptyList(),
            distanceMeters = 5_000.0,
            distanceToDestinationMeters = 2_000.0,
            ascentLeftM = 200,
            effectiveGrade = 4.0,
        )
        assertNotNull(r.state)
        assertEquals("fake_synthetic", r.reason)
    }

    @Test
    fun `real mode uses sdk climb candidate`() {
        val climbs = listOf(
            KarooClimb(index = 0, startDistance = 7_000.0, length = 1_200.0, totalElevation = 180.0, grade = 5.0),
        )
        val r = ActiveClimbResolver.resolve(
            nowMs = 1_000L,
            fakeMode = false,
            hasRoute = true,
            navClimbs = climbs,
            distanceMeters = 6_750.0,
            distanceToDestinationMeters = 2_000.0,
            ascentLeftM = 200,
            effectiveGrade = 3.0,
        )
        assertNotNull(r.state)
        assertEquals("sdk_climb", r.reason)
        assertEquals(250.0, r.state!!.distanceToClimbM, 0.001)
        // Kontrakt 2026-06: zywe ascentLeftM (aktualizuje sie w trakcie wjazdu)
        // ma pierwszenstwo przed statycznym totalElevation z SDK.
        assertEquals(200, r.state!!.climbElevationM)
    }

    @Test
    fun `real mode falls back to sdk elevation when ascentLeft missing`() {
        val climbs = listOf(
            KarooClimb(index = 0, startDistance = 7_000.0, length = 1_200.0, totalElevation = 180.0, grade = 5.0),
        )
        val r = ActiveClimbResolver.resolve(
            nowMs = 1_000L,
            fakeMode = false,
            hasRoute = true,
            navClimbs = climbs,
            distanceMeters = 6_750.0,
            distanceToDestinationMeters = 2_000.0,
            ascentLeftM = 0,
            effectiveGrade = 3.0,
        )
        assertNotNull(r.state)
        assertEquals(180, r.state!!.climbElevationM)
    }
}
