package pl.qbot.karoo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideStateTest {
    @Test
    fun flatAverageSpeedIsStable() {
        val state = RideState()
        for (t in 0..600) {
            val speed = 25.0
            val dist = if (t == 0) 0.0 else speed / 3.6 * t
            state.update(
                RideSample(
                    tSec = t.toDouble(),
                    speedKmh = speed,
                    powerW = 160.0,
                    hrBpm = 140.0,
                    cadenceRpm = 65.0,
                    altitudeM = 120.0,
                    distanceM = dist,
                    gradePct = 0.0
                )
            )
        }

        assertEquals(25.0, state.avgMovingKmh!!, 0.2)
        assertEquals(25.0, state.avgGrossKmh!!, 0.2)
    }

    @Test
    fun pauseSeparatesGrossAndMovingAverage() {
        val state = RideState()
        var dist = 0.0

        for (t in 0..1800) {
            val event = when (t) {
                600 -> RideEvent.PAUSE
                900 -> RideEvent.RESUME
                else -> RideEvent.NONE
            }
            val moving = t !in 600 until 900
            val speed = if (moving) 24.0 else 0.0
            if (t > 0 && moving) dist += speed / 3.6

            state.update(
                RideSample(
                    tSec = t.toDouble(),
                    speedKmh = speed,
                    powerW = if (moving) 150.0 else 0.0,
                    hrBpm = 135.0,
                    cadenceRpm = if (moving) 64.0 else 0.0,
                    altitudeM = 120.0,
                    distanceM = dist,
                    gradePct = 0.0,
                    event = event
                )
            )
        }

        assertTrue(state.avgMovingKmh!! > state.avgGrossKmh!!)
        assertTrue(state.avgGrossKmh!! in 15.0..20.1)
        assertTrue(state.avgMovingKmh!! in 23.0..25.0)
    }

    @Test
    fun uiRecreateDoesNotResetState() {
        val state = RideState()
        var dist = 0.0
        var before = 0.0
        var after = 0.0

        for (t in 0..800) {
            val speed = 23.0
            if (t > 0) dist += speed / 3.6
            state.update(
                RideSample(
                    tSec = t.toDouble(),
                    speedKmh = speed,
                    powerW = 155.0,
                    hrBpm = 137.0,
                    cadenceRpm = 64.0,
                    altitudeM = 120.0,
                    distanceM = dist,
                    gradePct = 0.0,
                    event = if (t == 700) RideEvent.UI_RECREATE else RideEvent.NONE
                )
            )
            if (t == 699) before = state.avgMovingKmh!!
            if (t == 701) after = state.avgMovingKmh!!
        }

        assertEquals(1, state.uiRecreateCount)
        assertEquals(before, after, 1.0)
    }

    @Test
    fun gradeSwitchesToDescent() {
        val state = RideState()
        val fields = FieldComputers()
        var descentSeen = false
        var dist = 0.0
        var alt = 120.0

        for (t in 0..1300) {
            val grade = when {
                t < 600 -> 0.0
                t < 1200 -> 6.0
                else -> -6.0
            }
            val speed = when {
                t < 600 -> 24.0
                t < 1200 -> 12.0
                else -> 36.0
            }
            if (t > 0) {
                val dd = speed / 3.6
                dist += dd
                alt += dd * grade / 100.0
            }
            state.update(
                RideSample(
                    tSec = t.toDouble(),
                    speedKmh = speed,
                    powerW = 150.0,
                    hrBpm = 140.0,
                    cadenceRpm = 65.0,
                    altitudeM = alt,
                    distanceM = dist,
                    gradePct = grade
                )
            )
            val out = fields.grade(state)
            if (t > 1210 && out.reason == "descent") {
                descentSeen = true
                break
            }
        }

        assertTrue(descentSeen)
    }

    @Test
    fun sensorDropoutMakesSpeedStaleNotReset() {
        val state = RideState()
        val fields = FieldComputers()
        var dist = 0.0
        var staleSeen = false

        for (t in 0..400) {
            val speed = 23.0
            if (t > 0) dist += speed / 3.6
            val event = when (t) {
                300 -> RideEvent.SENSOR_DROPOUT
                360 -> RideEvent.SENSOR_RESTORE
                else -> RideEvent.NONE
            }
            val hasSensor = t !in 300 until 360
            state.update(
                RideSample(
                    tSec = t.toDouble(),
                    speedKmh = if (hasSensor) speed else null,
                    powerW = if (hasSensor) 155.0 else null,
                    hrBpm = if (hasSensor) 137.0 else null,
                    cadenceRpm = if (hasSensor) 64.0 else null,
                    altitudeM = 120.0,
                    distanceM = dist,
                    gradePct = 0.0,
                    event = event
                )
            )
            if (t in 310..359 && fields.speed(state).status == FieldStatus.STALE) {
                staleSeen = true
            }
        }

        assertTrue(staleSeen)
        assertTrue(state.avgMovingKmh!! > 20.0)
    }

    @Test
    fun hrZeroIsNoDataNotInvalid() {
        val state = RideState()
        state.update(RideSample(tSec = 1.0, hrBpm = 0.0))
        val out = FieldComputers().hr(state)
        assertEquals("HR", out.name)
        assertEquals("WAIT", out.value)
        assertEquals(FieldStatus.NO_DATA, out.status)
        assertEquals("hr_zero_or_not_ready", out.reason)
    }

    @Test
    fun hr25IsInvalid() {
        val state = RideState()
        state.update(RideSample(tSec = 1.0, hrBpm = 25.0))
        val out = FieldComputers().hr(state)
        assertEquals("INV", out.value)
        assertEquals(FieldStatus.INVALID, out.status)
        assertEquals("hr_out_of_range", out.reason)
    }

    @Test
    fun hrValidIsOk() {
        val state = RideState()
        state.update(RideSample(tSec = 1.0, hrBpm = 72.0))
        val out = FieldComputers().hr(state)
        assertEquals("72", out.value)
        assertEquals(FieldStatus.OK, out.status)
        assertEquals("hr_present", out.reason)
    }
}
