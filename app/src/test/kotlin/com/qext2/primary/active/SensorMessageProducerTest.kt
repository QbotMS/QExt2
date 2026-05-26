package com.qext2.primary.active

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SensorMessageProducerTest {

    private val producer = SensorMessageProducer()

    private fun state(
        speedKmh: Double = 20.0,
        cadence: Int = 70,
        hr: Int = 130,
        power: Int = 200,
        powerFreshnessMs: Long = 1_000L,
        cadenceFreshnessMs: Long = 1_000L,
        hrFreshnessMs: Long = 1_000L,
        hasRoute: Boolean = true,
        elapsedSec: Long = 30L,
        nowMs: Long = 1_000_000L,
    ) = SensorState(
        speedKmh = speedKmh,
        cadence = cadence,
        hr = hr,
        power = power,
        powerFreshnessMs = powerFreshnessMs,
        cadenceFreshnessMs = cadenceFreshnessMs,
        hrFreshnessMs = hrFreshnessMs,
        hasRoute = hasRoute,
        elapsedSec = elapsedSec,
        nowMs = nowMs,
    )

    @Test
    fun `stale power triggers warning`() {
        val msg = producer.checkAndProduce(state(powerFreshnessMs = 15_000L))
        assertNotNull(msg)
        assertEquals("BRAK MOCY", msg!!.title)
        assertEquals(ActiveMessageSeverity.WARNING, msg.severity)
    }

    @Test
    fun `fresh power does not trigger`() {
        val msg = producer.checkAndProduce(state(powerFreshnessMs = 5_000L))
        assertNull(msg)
    }

    @Test
    fun `stale power ignored at low speed`() {
        val msg = producer.checkAndProduce(state(speedKmh = 3.0, powerFreshnessMs = 15_000L))
        assertNull(msg)
    }

    @Test
    fun `stale power ignored with low cadence and low HR`() {
        val msg = producer.checkAndProduce(state(cadence = 10, hr = 60, powerFreshnessMs = 15_000L))
        assertNull(msg)
    }

    @Test
    fun `stale power triggers with cadence only`() {
        val msg = producer.checkAndProduce(state(cadence = 25, hr = 50, powerFreshnessMs = 15_000L))
        assertNotNull(msg)
    }

    @Test
    fun `stale power triggers with HR only`() {
        val msg = producer.checkAndProduce(state(cadence = 10, hr = 100, powerFreshnessMs = 15_000L))
        assertNotNull(msg)
    }

    @Test
    fun `stale HR triggers info`() {
        val msg = producer.checkAndProduce(state(hrFreshnessMs = 20_000L))
        assertNotNull(msg)
        assertEquals("BRAK HR", msg!!.title)
        assertEquals(ActiveMessageSeverity.INFO, msg.severity)
    }

    @Test
    fun `fresh HR does not trigger`() {
        val msg = producer.checkAndProduce(state(hrFreshnessMs = 5_000L))
        assertNull(msg)
    }

    @Test
    fun `stale HR ignored at low power`() {
        val msg = producer.checkAndProduce(state(power = 80, hrFreshnessMs = 20_000L))
        assertNull(msg)
    }

    @Test
    fun `stale HR ignored at low speed`() {
        val msg = producer.checkAndProduce(state(speedKmh = 3.0, hrFreshnessMs = 20_000L))
        assertNull(msg)
    }

    @Test
    fun `no route triggers once per session`() {
        assertNotNull(producer.checkAndProduce(state(hasRoute = false)))
        assertNull(producer.checkAndProduce(state(hasRoute = false)))
        assertNull(producer.checkAndProduce(state(hasRoute = false, nowMs = 200_000L)))
    }

    @Test
    fun `route present does not trigger`() {
        val msg = producer.checkAndProduce(state(hasRoute = true))
        assertNull(msg)
    }

    @Test
    fun `reset allows route trigger again`() {
        assertNotNull(producer.checkAndProduce(state(hasRoute = false)))
        assertNull(producer.checkAndProduce(state(hasRoute = false)))
        producer.reset()
        assertNotNull(producer.checkAndProduce(state(hasRoute = false)))
    }

    @Test
    fun `power cooldown suppresses duplicate`() {
        assertNotNull(producer.checkAndProduce(state(nowMs = 100_000L, powerFreshnessMs = 15_000L)))
        assertNull(producer.checkAndProduce(state(nowMs = 110_000L, powerFreshnessMs = 15_000L)))
    }

    @Test
    fun `power cooldown expires`() {
        assertNotNull(producer.checkAndProduce(state(nowMs = 100_000L, powerFreshnessMs = 15_000L)))
        assertNotNull(producer.checkAndProduce(state(nowMs = 161_000L, powerFreshnessMs = 15_000L)))
    }

    @Test
    fun `hr cooldown suppresses duplicate`() {
        assertNotNull(producer.checkAndProduce(state(nowMs = 100_000L, hrFreshnessMs = 20_000L)))
        assertNull(producer.checkAndProduce(state(nowMs = 150_000L, hrFreshnessMs = 20_000L)))
    }

    @Test
    fun `power stale but HR fresh, only power triggers`() {
        val msg = producer.checkAndProduce(state(powerFreshnessMs = 15_000L, hrFreshnessMs = 5_000L))
        assertNotNull(msg)
        assertEquals("BRAK MOCY", msg!!.title)
    }

    @Test
    fun `all sensors missing after 10s triggers BRAK SENSOROW`() {
        val msg = producer.checkAndProduce(state(
            speedKmh = 0.0, cadence = 0, hr = 0, power = 0,
            elapsedSec = 15L,
            powerFreshnessMs = 20_000L,
            cadenceFreshnessMs = 20_000L,
            hrFreshnessMs = 20_000L,
        ))
        assertNotNull(msg)
        assertEquals("BRAK SENSORÓW", msg!!.title)
        assertEquals(ActiveMessageSeverity.WARNING, msg.severity)
    }

    @Test
    fun `all sensors missing under 10s does not trigger`() {
        val msg = producer.checkAndProduce(state(
            speedKmh = 0.0, cadence = 0, hr = 0, power = 0,
            elapsedSec = 5L,
            powerFreshnessMs = 20_000L,
            cadenceFreshnessMs = 20_000L,
            hrFreshnessMs = 20_000L,
        ))
        assertNull(msg)
    }

    @Test
    fun `sensors one-shot does not repeat`() {
        val base = state(
            speedKmh = 0.0, cadence = 0, hr = 0, power = 0,
            elapsedSec = 15L,
            powerFreshnessMs = 20_000L, cadenceFreshnessMs = 20_000L, hrFreshnessMs = 20_000L,
        )
        assertNotNull(producer.checkAndProduce(base.copy(nowMs = 100_000L)))
        assertNull(producer.checkAndProduce(base.copy(nowMs = 200_000L)))
        assertNull(producer.checkAndProduce(base.copy(nowMs = 500_000L)))
    }

    @Test
    fun `sensors reset re-enables`() {
        val base = state(
            speedKmh = 0.0, cadence = 0, hr = 0, power = 0,
            elapsedSec = 15L,
            powerFreshnessMs = 20_000L, cadenceFreshnessMs = 20_000L, hrFreshnessMs = 20_000L,
        )
        assertNotNull(producer.checkAndProduce(base))
        assertNull(producer.checkAndProduce(base))
        producer.reset()
        assertNotNull(producer.checkAndProduce(base))
    }

    @Test
    fun `BRAK MOCY still requires movement`() {
        val msg = producer.checkAndProduce(state(speedKmh = 3.0, powerFreshnessMs = 15_000L))
        assertNull(msg)
    }

    @Test
    fun `BRAK TRASY remains suppressed across producer reuse`() {
        assertNotNull(producer.checkAndProduce(state(hasRoute = false, nowMs = 100_000L)))
        assertNull(producer.checkAndProduce(state(hasRoute = false, nowMs = 200_000L)))
        assertNull(producer.checkAndProduce(state(hasRoute = false, nowMs = 500_000L)))
        assertNull(producer.checkAndProduce(state(hasRoute = false, nowMs = 1_000_000L)))
    }

    @Test
    fun `only reset() re-enables BRAK TRASY`() {
        assertNotNull(producer.checkAndProduce(state(hasRoute = false)))
        assertNull(producer.checkAndProduce(state(hasRoute = false)))
        producer.reset()
        assertNotNull(producer.checkAndProduce(state(hasRoute = false)))
    }
}
