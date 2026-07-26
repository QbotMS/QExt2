package com.qext2.primary.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AthleteDataTodayFactorTest {

    @Test
    fun `wartosc w zakresie zostaje bez zmian`() {
        assertEquals(0.93f, AthleteData.clampTodayFactor(0.93f), 0.0001f)
        assertEquals(1.00f, AthleteData.clampTodayFactor(1.00f), 0.0001f)
    }

    @Test
    fun `ponizej minimum przycinane do 0_70`() {
        assertEquals(0.70f, AthleteData.clampTodayFactor(0.42f), 0.0001f)
        assertEquals(0.70f, AthleteData.clampTodayFactor(-3f), 0.0001f)
    }

    @Test
    fun `powyzej maksimum przycinane do 1_10`() {
        assertEquals(1.10f, AthleteData.clampTodayFactor(1.50f), 0.0001f)
    }

    @Test
    fun `NaN i nieskonczonosc daja neutralne 1_0`() {
        assertEquals(1.0f, AthleteData.clampTodayFactor(Float.NaN), 0.0001f)
        assertEquals(1.0f, AthleteData.clampTodayFactor(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(1.0f, AthleteData.clampTodayFactor(Float.NEGATIVE_INFINITY), 0.0001f)
    }

    @Test
    fun `baro adjustment nie wychodzi poza kanon`() {
        val d = AthleteData(todayFactor = 0.72f, baroMultiplier = 0.80f)
        val out = d.applyBaroAdjustment(baroSensitive = true)
        assertEquals(0.70f, out.todayFactor, 0.0001f)
    }

    // --- bramka wieku danych (audyt pkt B1) ---

    private val h = 3_600_000L

    @Test
    fun `dane swieze - pelna wartosc`() {
        val now = 1_000_000_000L
        assertEquals(0.86f, AthleteData.ageAdjustedTodayFactor(0.86f, now - 5 * h, now), 0.0001f)
        assertEquals(0.86f, AthleteData.ageAdjustedTodayFactor(0.86f, now - 24 * h, now), 0.0001f)
    }

    @Test
    fun `36h - polowa odchylenia`() {
        val now = 1_000_000_000L
        assertEquals(0.93f, AthleteData.ageAdjustedTodayFactor(0.86f, now - 36 * h, now), 0.0001f)
    }

    @Test
    fun `powyzej 48h - neutralne 1_0`() {
        val now = 1_000_000_000L
        assertEquals(1.0f, AthleteData.ageAdjustedTodayFactor(0.86f, now - 60 * h, now), 0.0001f)
        assertEquals(1.0f, AthleteData.ageAdjustedTodayFactor(1.08f, now - 60 * h, now), 0.0001f)
    }

    @Test
    fun `brak odczytu - neutralne 1_0`() {
        assertEquals(1.0f, AthleteData.ageAdjustedTodayFactor(0.86f, 0L, 1_000_000_000L), 0.0001f)
    }

    @Test
    fun `rampa dziala tez w gore`() {
        val now = 1_000_000_000L
        // 1.10 przy 36 h -> polowa odchylenia = 1.05
        assertEquals(1.05f, AthleteData.ageAdjustedTodayFactor(1.10f, now - 36 * h, now), 0.0001f)
    }

    @Test
    fun `flaga degradacji`() {
        val now = 1_000_000_000L
        assertEquals(false, AthleteData.isTodayFactorDegraded(now - 10 * h, now))
        assertEquals(true, AthleteData.isTodayFactorDegraded(now - 30 * h, now))
        assertEquals(true, AthleteData.isTodayFactorDegraded(0L, now))
    }

    // --- swiezosc gotowosci NA DZIS (audyt RSRV 2026-07-26) ---

    private val zone = java.time.ZoneId.of("Europe/Warsaw")
    private fun ms(y: Int, mo: Int, d: Int, hh: Int, mm: Int): Long =
        java.time.LocalDateTime.of(y, mo, d, hh, mm).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `gotowosc pobrana tego samego dnia jest swieza`() {
        val rideStart = ms(2026, 7, 26, 10, 50)
        val fetch = ms(2026, 7, 26, 8, 45)
        assertEquals(true, AthleteData.readinessFreshForRide(fetch, rideStart, zone))
    }

    @Test
    fun `gotowosc z wczoraj NIE jest swieza mimo ponizej 24h`() {
        // to jest dokladnie przypadek przecieku: 18 h temu, ale WCZORAJ
        val rideStart = ms(2026, 7, 26, 9, 0)
        val fetch = ms(2026, 7, 25, 15, 0)
        assertEquals(false, AthleteData.readinessFreshForRide(fetch, rideStart, zone))
    }

    @Test
    fun `brak pobrania lub brak startu - nieswieze`() {
        assertEquals(false, AthleteData.readinessFreshForRide(0L, ms(2026, 7, 26, 9, 0), zone))
        assertEquals(false, AthleteData.readinessFreshForRide(ms(2026, 7, 26, 8, 0), 0L, zone))
    }
}
