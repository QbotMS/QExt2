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
        // Bramka jakosci driftu (DECISIONS 2026-07-18): potrzeba >=200 sparowanych
        // probek w tych samych binach mocy (~7 min porownywalnej jazdy) - stary
        // test karmil 60 i dostawal 0% z bramki, nie z braku dryfu.
        for (i in 1..300) calc.update(200, 150, i.toLong(), i.toLong())
        for (i in 301..600) calc.update(200, 165, i.toLong(), i.toLong())
        val drift = calc.decouplingPercent()
        assertTrue("Should detect positive drift (HR rose at same power), got $drift%", drift > 0f)
    }

    @Test
    fun `viValue equals 1 for constant power`() {
        val calc = StatsCalculator(ftpWatts = 200)
        repeat(300) { calc.update(200, 150, it.toLong(), it.toLong()) }
        val vi = calc.viValue()
        assertTrue("VI should be ~1.0 for constant power, got $vi", vi in 0.95f..1.05f)
    }

    @Test
    fun `reserve v2 starts at 100 and drains only while moving with power`() {
        val calc = StatsCalculator(ftpWatts = 240)
        assertEquals(100, calc.rideReservePercentV2(0.0))
        for (t in 1..3600) calc.update(192, 0, t.toLong(), t.toLong())   // 1 h @ 0,8 CP
        assertEquals(75, calc.rideReservePercentV2(0.0))
        for (t in 3601..5400) calc.update(0, 0, 3600L, t.toLong())       // postoj: czas ruchu stoi
        assertEquals(75, calc.rideReservePercentV2(0.0))
        assertTrue(calc.reserveReady())
        calc.reset()
        assertEquals(100, calc.rideReservePercentV2(0.0))
    }

    @Test
    fun `E1_4 odswiezenie profilu nie napelnia wyczerpanego W'`() {
        val calc = StatsCalculator(ftpWatts = 240)
        calc.setWPrimeParams(20f, 240f)
        assertEquals(100, calc.wBalancePercent(0L))
        // ponad CP: W' schodzi do zera
        for (sec in 1..400L) calc.update(500, 170, sec, sec)
        assertEquals(0, calc.wBalancePercent(1L))
        // ten sam i zmieniony profil z serwera w trakcie jazdy
        calc.setWPrimeParams(20f, 240f)
        assertEquals(0, calc.wBalancePercent(2L))
        calc.setWPrimeParams(22f, 245f)
        assertEquals(0, calc.wBalancePercent(3L))
    }

    @Test
    fun `E1_5 wynik nie zalezy od czestosci probek`() {
        val a = StatsCalculator(ftpWatts = 240); a.setWPrimeParams(20f, 240f)
        val b = StatsCalculator(ftpWatts = 240); b.setWPrimeParams(20f, 240f)
        for (sec in 1..600L) a.update(if (sec % 120 < 60) 320 else 150, 150, sec, sec, dtSec = 1.0)
        for (sec in 2..600L step 2) b.update(if (sec % 120 < 60) 320 else 150, 150, sec, sec, dtSec = 2.0)
        assertTrue("XSS ${a.xssValue()} vs ${b.xssValue()}", kotlin.math.abs(a.xssValue() - b.xssValue()) < 0.5f)
        assertTrue("W'bal ${a.wBalancePercent(0L)} vs ${b.wBalancePercent(0L)}", kotlin.math.abs(a.wBalancePercent(0L) - b.wBalancePercent(0L)) <= 2)
    }

    @Test
    fun `E1_5 luka w danych nie zmienia W'bal ani XSS`() {
        val c = StatsCalculator(ftpWatts = 240); c.setWPrimeParams(20f, 240f)
        for (sec in 1..60L) c.update(400, 160, sec, sec)
        val x = c.xssValue(); val w = c.wBalancePercent(0L)
        c.update(400, 160, 61, 61, dtSec = 0.0)
        assertEquals(x, c.xssValue(), 0.0001f)
        assertEquals(w, c.wBalancePercent(1L))
    }

    @Test
    fun `E2_2 XSS wzor ModelQ - godzina przy CP = 100, ponizej CP proporcjonalnie`() {
        val a = StatsCalculator(ftpWatts = 240); a.setWPrimeParams(20f, 240f)
        for (sec in 1..3600L) a.update(240, 150, sec, sec)
        assertEquals(100f, a.xssValue(), 0.5f)
        val b = StatsCalculator(ftpWatts = 240); b.setWPrimeParams(20f, 240f)
        for (sec in 1..3600L) b.update(180, 140, sec, sec)
        assertEquals(75f, b.xssValue(), 0.5f)
    }

    @Test
    fun `E2_2 XSS nadwyzka nad CP wazona zmeczeniem`() {
        val c = StatsCalculator(ftpWatts = 240); c.setWPrimeParams(20f, 240f)
        for (sec in 1..60L) c.update(360, 170, sec, sec)
        // 60 s: praca do CP = 60/3600*100 = 1.667; nadwyzka 120/240 * (1 + zmeczenie 0..0.36) * 1.667
        val x = c.xssValue()
        assertTrue("XSS $x", x > 1.667f + 0.833f && x < 1.667f + 0.833f * 1.4f)
    }

    @Test
    fun `D6 slabsza forma szybciej zuzywa RSRV, start 100`() {
        val a = StatsCalculator(ftpWatts = 240); a.todayFactor = 1.0f
        val b = StatsCalculator(ftpWatts = 240); b.todayFactor = 0.9f
        assertEquals(100, a.rideReservePercentV2(0.0)); assertEquals(100, b.rideReservePercentV2(0.0))
        for (sec in 1..3600L) { a.update(180, 0, sec, sec); b.update(180, 0, sec, sec) }
        assertTrue("forma 0.9 ${b.rideReservePercentV2(0.0)} < forma 1.0 ${a.rideReservePercentV2(0.0)}",
            b.rideReservePercentV2(0.0) < a.rideReservePercentV2(0.0))
    }

    @Test
    fun `E2_3 NP5 liczy toczenie jako zero, a brak pomiaru pomija`() {
        val a = StatsCalculator(ftpWatts = 240)
        for (sec in 1..300L) a.update(if (sec % 120 < 60) 300 else 0, 150, sec, sec)
        val np = a.np5Watts()
        assertNotNull(np)
        assertTrue("NP5 z zerami $np", np!! in 170..250)
        val b = StatsCalculator(ftpWatts = 240)
        for (sec in 1..300L) b.update(300, 150, sec, sec, powerFresh = sec % 120 < 60)
        assertNull("pokrycie < 90% -> brak NP5", b.np5Watts())
    }

    @Test
    fun `E6_1 spalanie CHO jak glycogen py i zalecenie 70 procent max 90`() {
        // 200 W przy CP 240: frakcja 0.8167 -> 200/0.23/4184*0.8167/4 g/s = 152.7 g/h
        assertEquals(152.7, StatsCalculator.choGPerSec(200.0, 240.0) * 3600, 0.5)
        val c = StatsCalculator(ftpWatts = 240)
        for (sec in 1..3600L) c.update(200, 140, sec, sec)
        assertEquals(153, c.choBurnedG(), 1)
        assertEquals(90, c.choRecommendedGPerH())
        val e = StatsCalculator(ftpWatts = 240)
        for (sec in 1..3600L) e.update(120, 120, sec, sec)
        // 120 W: frakcja 0.5 -> 56.1 g/h spalania -> zalecenie ok. 39 g/h
        assertEquals(39, e.choRecommendedGPerH(), 1)
    }
}
