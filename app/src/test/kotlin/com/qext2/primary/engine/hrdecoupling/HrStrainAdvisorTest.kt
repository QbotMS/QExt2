package com.qext2.primary.engine.hrdecoupling

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HrStrainAdvisorTest {

    private lateinit var buffer: HrDecouplingBuffer
    private lateinit var advisor: HrStrainAdvisor
    private var baseTime = 1_000_000L
    private val maxHr = 180

    @Before
    fun setUp() {
        buffer = HrDecouplingBuffer()
        advisor = HrStrainAdvisor(buffer)
    }

    @After
    fun tearDown() {
        advisor.reset()
        buffer.clear()
    }

    private fun nextSample(
        elapsedSec: Long, power: Int, hr: Int, cadence: Int = 70,
        speedKmh: Double = 25.0,
    ) {
        buffer.add(HrSample(
            timestampMs = baseTime + elapsedSec * 1000L,
            hr = hr, power = power, cadence = cadence,
            speedKmh = speedKmh,
            elapsedSec = elapsedSec,
        ))
    }

    private fun fillValidSamples(fromSec: Long, toSec: Long, power: Int, hr: Int) {
        var s = fromSec
        while (s <= toSec) {
            nextSample(s, power, hr)
            s++
        }
    }

    private fun assess(elapsedSec: Long): HrStrainResult {
        return advisor.assess(baseTime + elapsedSec * 1000L, maxHr)
    }

    @Test
    fun `no baseline — NEUTRAL`() {
        fillValidSamples(0L, 200L, 150, 120)
        val result = assess(200L)
        assertEquals(StatusColor.NEUTRAL, result.color)
        assertTrue(result.reasonCode.contains("BASELINE") || result.reasonCode.contains("NO_DATA"))
    }

    @Test
    fun `no maxHR — NEUTRAL`() {
        fillValidSamples(0L, 2000L, 150, 120)
        val result = advisor.assess(baseTime + 2000_000L, 0)
        assertEquals(StatusColor.NEUTRAL, result.color)
        assertEquals("HR_NO_MAX_HR", result.reasonCode)
    }

    @Test
    fun `HR Z2 plus decoupling under 3 percent — GOOD`() {
        fillValidSamples(480L, 1080L, 150, 120)
        fillValidSamples(1800L, 2280L, 150, 123)
        val result = assess(2280L)
        assertEquals(StatusColor.GOOD, result.color)
    }

    @Test
    fun `HR Z2 plus decoupling 7 percent — WARN`() {
        fillValidSamples(480L, 1080L, 150, 120)
        fillValidSamples(1800L, 2280L, 150, 131)
        val result = assess(2280L)
        assertEquals(StatusColor.WARN, result.color)
    }

    @Test
    fun `decoupling over 10 percent — BAD`() {
        fillValidSamples(480L, 1080L, 150, 120)
        fillValidSamples(1800L, 2280L, 150, 138)
        val result = assess(2280L)
        assertEquals("reason=${result.reasonCode} decoupling=${result.decouplingPct}", StatusColor.BAD, result.color)
    }

    @Test
    fun `HR Z4 without decoupling active — WARN from zone`() {
        fillValidSamples(0L, 800L, 150, 160)
        val result = assess(800L)
        assertEquals(StatusColor.WARN, result.color)
    }

    @Test
    fun `HR Z5 — BAD`() {
        fillValidSamples(0L, 800L, 150, 175)
        val result = assess(800L)
        assertEquals(StatusColor.BAD, result.color)
    }

    @Test
    fun `low power samples not counted into window`() {
        for (s in 0L..100L) {
            nextSample(s, 30, 100)
        }
        val result = assess(100L)
        assertTrue(result.reasonCode.contains("BASELINE") || result.reasonCode.contains("NO_DATA"))
    }

    @Test
    fun `stopped samples excluded — auto pause stops elapsed time`() {
        fillValidSamples(480L, 1080L, 150, 120)
        fillValidSamples(1800L, 2000L, 80, 90)
        val result = assess(2000L)
        assertEquals(StatusColor.NEUTRAL, result.color)
    }

    @Test
    fun `buffer clear and advisor reset`() {
        fillValidSamples(480L, 1080L, 150, 120)
        fillValidSamples(1800L, 2280L, 150, 123)
        assess(2280L)
        buffer.clear()
        advisor.reset()
        val result = assess(2280L)
        assertEquals(StatusColor.NEUTRAL, result.color)
    }
}
