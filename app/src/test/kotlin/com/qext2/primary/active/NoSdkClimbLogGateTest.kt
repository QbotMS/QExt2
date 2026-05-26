package com.qext2.primary.active

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoSdkClimbLogGateTest {

    @Test
    fun `logs no_sdk_climbs only once per route`() {
        val gate = NoSdkClimbLogGate()
        assertTrue(gate.shouldLogNoSdkClimbs("route:A|dist=10000"))
        assertFalse(gate.shouldLogNoSdkClimbs("route:A|dist=10000"))
    }

    @Test
    fun `route change allows logging again`() {
        val gate = NoSdkClimbLogGate()
        assertTrue(gate.shouldLogNoSdkClimbs("route:A|dist=10000"))
        assertTrue(gate.shouldLogNoSdkClimbs("route:B|dist=9000"))
    }

    @Test
    fun `sdk climbs availability resets block for same route`() {
        val gate = NoSdkClimbLogGate()
        val key = "route:A|dist=10000"
        assertTrue(gate.shouldLogNoSdkClimbs(key))
        assertFalse(gate.shouldLogNoSdkClimbs(key))
        gate.onSdkClimbsAvailable(key)
        assertTrue(gate.shouldLogNoSdkClimbs(key))
    }
}
