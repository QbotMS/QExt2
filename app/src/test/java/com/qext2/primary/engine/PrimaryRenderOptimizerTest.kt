package com.qext2.primary.engine

import com.qext2.primary.model.PrimaryRideSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryRenderOptimizerTest {

    @Test
    fun optimizerDoesNotChangeOutputWhenValuesSame() {
        val opt = PrimaryRenderOptimizer()
        opt.enabled = true

        val snap1 = PrimaryRideSnapshot(
            hr = 120, cadence = 70, power3s = 180, speedKmh = 24.0,
            powerValue = "180", hrValue = "120", cadenceValue = "70",
            speedValue = "24.0",
        )
        val dec1 = opt.decide(snap1, 24.0)
        assertTrue("First render should pass", dec1.shouldRender)

        Thread.sleep(350)

        val snap2 = PrimaryRideSnapshot(
            hr = 120, cadence = 70, power3s = 180, speedKmh = 24.0,
            powerValue = "180", hrValue = "120", cadenceValue = "70",
            speedValue = "24.0",
        )
        val dec2 = opt.decide(snap2, 24.0)
        assertFalse("Same values after throttle window should be deduped", dec2.shouldRender)
    }

    @Test
    fun optimizerUpdatesWhenValueChanges() {
        val opt = PrimaryRenderOptimizer()
        opt.enabled = true

        val snap1 = PrimaryRideSnapshot(
            hr = 120, cadence = 70, power3s = 180, speedKmh = 24.0,
            powerValue = "180", hrValue = "120", cadenceValue = "70",
            speedValue = "24.0",
        )
        opt.decide(snap1, 24.0)

        Thread.sleep(350)

        val snap2 = PrimaryRideSnapshot(
            hr = 125, cadence = 70, power3s = 180, speedKmh = 24.0,
            powerValue = "180", hrValue = "125", cadenceValue = "70",
            speedValue = "24.0",
        )
        val dec2 = opt.decide(snap2, 24.0)
        assertTrue("Changed HR after throttle window should trigger render", dec2.shouldRender)
    }

    @Test
    fun optimizerDisabledAlwaysRenders() {
        val opt = PrimaryRenderOptimizer()
        opt.enabled = false

        val snap1 = PrimaryRideSnapshot(
            hr = 120, cadence = 70, power3s = 180, speedKmh = 24.0,
            powerValue = "180", hrValue = "120", cadenceValue = "70",
            speedValue = "24.0",
        )
        val dec1 = opt.decide(snap1, 24.0)
        assertTrue("Disabled optimizer must always render", dec1.shouldRender)
    }
}
