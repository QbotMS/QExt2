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
}
