package com.qext2.primary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsCalculatorTest {

    @Test
    fun `NP equals average for constant power`() {
        val calc = StatsCalculator(ftpWatts = 200)
        repeat(300) { sec ->
            calc.update(200, 150, sec.toLong(), sec.toLong())
        }
        val np = calc.npWatts()
        assertTrue("NP should be close to 200W for constant power, got $np", np in 198..202)
    }

    @Test
    fun `NP higher than average for variable power`() {
        val calc = StatsCalculator(ftpWatts = 200)
        for (sec in 0..149L) calc.update(100, 140, sec, sec)
        for (sec in 150..299L) calc.update(300, 160, sec, sec)
        val np = calc.npWatts()
        assertTrue("NP should be > 200W for variable power, got $np", np > 200)
    }

    @Test
    fun `NP is zero before 30s of data`() {
        val calc = StatsCalculator(ftpWatts = 200)
        repeat(29) { calc.update(200, 150, it.toLong(), it.toLong()) }
        assertEquals(0, calc.npWatts())
    }

    @Test
    fun `NP ignores dead time when movingSec does not advance`() {
        val calc = StatsCalculator(ftpWatts = 200)
        repeat(120) { sec -> calc.update(200, 150, sec.toLong(), sec.toLong()) }
        val npAfter120s = calc.npWatts()
        repeat(60) { sec -> calc.update(0, 150, 120L, (120 + sec).toLong()) }
        val npAfterDeadTime = calc.npWatts()
        assertEquals(
            "NP should be unchanged when movingSec does not advance (dead time)",
            npAfter120s, npAfterDeadTime
        )
    }

    @Test
    fun `W prime depletes at expected rate above LTP`() {
        val calc = StatsCalculator(ftpWatts = 250)
        calc.setWPrimeParams(wPrime = 4.0f, ltp = 200f)
        repeat(50) { sec ->
            calc.update(280, 160, sec.toLong(), sec.toLong())
            calc.wBalancePercent()
        }
        val pct = calc.wBalancePercent()
        assertTrue("W' should be < 10% after 50s at 80W above LTP, got $pct%", pct < 10)
    }

    @Test
    fun `W prime recovers below LTP`() {
        val calc = StatsCalculator(ftpWatts = 250)
        calc.setWPrimeParams(wPrime = 4.0f, ltp = 200f)
        repeat(50) { calc.update(300, 160, it.toLong(), it.toLong()) }
        val afterEffort = calc.wBalancePercent()
        for (sec in 50..999L) calc.update(100, 120, sec, sec)
        val afterRecovery = calc.wBalancePercent()
        assertTrue(
            "Recovery should increase W' (was $afterEffort%, now $afterRecovery%)",
            afterRecovery > afterEffort
        )
    }

    @Test
    fun `W prime not active without params set`() {
        val calc = StatsCalculator()
        assertEquals(-1, calc.wBalancePercent())
    }

    @Test
    fun `carbsGPerH increases with intensity`() {
        val calc = StatsCalculator()
        val lowIF = calc.carbsGPerH(0.5f, 3600L, 1.0f, 20f, 75f)
        val highIF = calc.carbsGPerH(1.0f, 3600L, 1.0f, 20f, 75f)
        assertTrue("Higher IF should give more carbs (low=$lowIF, high=$highIF)", highIF > lowIF)
    }

    @Test
    fun `carbsGPerH increases with duration`() {
        val calc = StatsCalculator()
        val short = calc.carbsGPerH(0.75f, 3600L, 1.0f, 20f, 75f)
        val long = calc.carbsGPerH(0.75f, 10800L, 1.0f, 20f, 75f)
        assertTrue("Longer ride should give more carbs/h (short=$short, long=$long)", long > short)
    }

    @Test
    fun `fluidLPerH increases with temperature`() {
        val calc = StatsCalculator()
        val cold = calc.fluidLPerH(0.75f, 5f)
        val hot = calc.fluidLPerH(0.75f, 35f)
        assertTrue("Hotter temp should need more fluid (cold=$cold, hot=$hot)", hot > cold)
    }

    @Test
    fun `batteryDrainPctPerHour returns null before minimum window`() {
        val calc = StatsCalculator()
        val now = System.currentTimeMillis()
        calc.updateBattery(100, false, now)
        calc.updateBattery(99, false, now + 100_000L)
        assertNull(calc.batteryDrainPctPerHour(now + 100_000L))
    }

    @Test
    fun `batteryDrainPctPerHour is correct after sufficient time`() {
        val calc = StatsCalculator()
        val start = System.currentTimeMillis()
        calc.updateBattery(100, false, start)
        calc.updateBattery(90, false, start + 3600_000L)
        val drain = calc.batteryDrainPctPerHour(start + 3600_000L)
        assertNotNull(drain)
        assertTrue("Expected ~10%/h drain, got $drain", drain!! in 9.0f..11.0f)
    }

    @Test
    fun `batteryDrainPctPerHour returns zero drain when battery unchanged`() {
        val calc = StatsCalculator()
        val start = System.currentTimeMillis()
        calc.updateBattery(85, false, start)
        calc.updateBattery(85, false, start + 600_000L)
        val drain = calc.batteryDrainPctPerHour(start + 600_000L)
        assertNotNull(drain)
        assertEquals(0f, drain!!)
    }

    @Test
    fun `batteryDrainPctPerHour returns null when charging`() {
        val calc = StatsCalculator()
        val start = System.currentTimeMillis()
        calc.updateBattery(80, true, start)
        calc.updateBattery(90, true, start + 3600_000L)
        assertNull(calc.batteryDrainPctPerHour(start + 3600_000L))
    }

    @Test
    fun `batteryDrainPctPerHour preserves state when currentPct is null`() {
        val calc = StatsCalculator()
        val start = System.currentTimeMillis()
        calc.updateBattery(100, false, start)
        calc.updateBattery(90, false, start + 3600_000L)
        val drainBeforeNull = calc.batteryDrainPctPerHour(start + 3600_000L)
        calc.updateBattery(null, null, start + 3601_000L)
        val drainAfterNull = calc.batteryDrainPctPerHour(start + 3601_000L)
        assertNotNull(drainBeforeNull)
        assertNotNull(drainAfterNull)
        assertTrue(drainAfterNull!! > 0f)
    }

    @Test
    fun `snapshot and restore preserves NP state`() {
        val calc = StatsCalculator(ftpWatts = 200)
        repeat(300) { calc.update(220, 150, it.toLong(), it.toLong()) }
        val npBefore = calc.npWatts()
        val snap = calc.snapshotForCrashRecovery()
        val calc2 = StatsCalculator(ftpWatts = 200)
        calc2.restoreFromSnapshot(snap)
        assertEquals(npBefore, calc2.npWatts())
    }

    @Test
    fun `snapshot and restore preserves battery state`() {
        val calc = StatsCalculator()
        val start = System.currentTimeMillis()
        calc.updateBattery(100, false, start)
        calc.updateBattery(85, false, start + 7200_000L)
        val snap = calc.snapshotForCrashRecovery()
        val calc2 = StatsCalculator()
        calc2.restoreFromSnapshot(snap)
        val drain = calc2.batteryDrainPctPerHour(start + 7200_000L)
        assertNotNull(drain)
        assertTrue("Expected positive drain after restore, got $drain", drain!! > 0f)
    }

    @Test
    fun `reset clears all state`() {
        val calc = StatsCalculator(ftpWatts = 200)
        repeat(200) { calc.update(220, 150, it.toLong(), it.toLong()) }
        calc.reset()
        assertEquals(0, calc.npWatts())
        assertEquals(0f, calc.decouplingPercent(), 0.001f)
        assertEquals(0L, calc.snapshotForCrashRecovery().count4thPowers)
    }

    @Test
    fun `decouplingPercent returns zero before enough data`() {
        val calc = StatsCalculator()
        repeat(100) { calc.update(200, 150, it.toLong(), it.toLong()) }
        assertEquals(0f, calc.decouplingPercent(), 0.001f)
    }

    @Test
    fun `decouplingPercent detects drift when HR rises at same power`() {
        val calc = StatsCalculator()
        for (i in 1..60) calc.update(200, 150, i.toLong(), i.toLong())
        for (i in 61..121) calc.update(200, 165, i.toLong(), i.toLong())
        val drift = calc.decouplingPercent()
        assertTrue("Should detect positive drift (HR rose at same power), got $drift%", drift > 0f)
    }

    @Test
    fun `tssValue returns zero with no data`() {
        val calc = StatsCalculator()
        assertEquals(0f, calc.tssValue(3600L), 0.001f)
    }

    @Test
    fun `tssValue equals 100 for 1h at FTP`() {
        val calc = StatsCalculator(ftpWatts = 250)
        repeat(3600) { sec -> calc.update(250, 150, sec.toLong(), sec.toLong()) }
        val tss = calc.tssValue(3600L)
        assertTrue("1h at FTP should give TSS ~100, got $tss", tss in 90f..110f)
    }

    @Test
    fun `viValue equals 1 for constant power`() {
        val calc = StatsCalculator(ftpWatts = 200)
        repeat(300) { calc.update(200, 150, it.toLong(), it.toLong()) }
        val vi = calc.viValue()
        assertTrue("VI should be ~1.0 for constant power, got $vi", vi in 0.95f..1.05f)
    }

    @Test
    fun `reserve decreases with TSS accumulation`() {
        val calc = StatsCalculator()
        calc.todayFactor = 1.0f
        calc.captureStartReserve()
        repeat(100) { calc.update(200, 150, it.toLong(), it.toLong()) }
        val tss = calc.tssValue(100L)
        val reserve = calc.rideReservePercent(tss, 0.8f, 0f, 100L)
        assertTrue("Reserve should decrease with TSS (TSS=$tss, reserve=$reserve)", reserve < 100)
    }

    @Test
    fun `reserve decreases more with higher decoupling`() {
        val calc = StatsCalculator()
        calc.todayFactor = 1.0f
        calc.captureStartReserve()
        val tss = 80f
        val reserveNoDecouple = calc.rideReservePercent(tss, 0.8f, 0f, 3600L)
        calc.reset()
        calc.todayFactor = 1.0f
        calc.captureStartReserve()
        repeat(200) {
            val hr = if (it < 100) 140 else 160
            calc.update(200, hr, it.toLong(), it.toLong())
        }
        val reserveWithDecouple = calc.rideReservePercent(tss, 0.8f, calc.decouplingPercent(), 3600L)
        assertTrue(
            "Decoupling should lower reserve (noDecouple=$reserveNoDecouple withDecouple=$reserveWithDecouple decouple=${calc.decouplingPercent()}%)",
            reserveWithDecouple <= reserveNoDecouple
        )
    }

    @Test
    fun `reserve linear TSS at 48 gives ~88`() {
        val calc = StatsCalculator()
        calc.todayFactor = 1.0f
        calc.captureStartReserve()
        val reserve = calc.rideReservePercent(48f, 0f, 0f, 3600L)
        assertTrue("TSS=48 → ~88 (got $reserve)", reserve in 86..90)
    }

    @Test
    fun `reserve linear TSS at 204 gives ~48`() {
        val calc = StatsCalculator()
        calc.todayFactor = 1.0f
        calc.captureStartReserve()
        val reserve = calc.rideReservePercent(204f, 0f, 0f, 3600L)
        assertTrue("TSS=204 → ~48 (got $reserve)", reserve in 46..50)
    }

    @Test
    fun `reserve linear TSS at 280 gives ~28`() {
        val calc = StatsCalculator()
        calc.todayFactor = 1.0f
        calc.captureStartReserve()
        val reserve = calc.rideReservePercent(280f, 0f, 0f, 3600L)
        assertTrue("TSS=280 → ~28 (got $reserve)", reserve in 26..30)
    }
}
