package com.qext2.primary.active

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorMessageProducerTest {

    private fun sensorState(
        speedKmh: Double = 25.0,
        cadence: Int = 70,
        hr: Int = 140,
        power: Int = 200,
        powerFreshnessMs: Long = 3_000L,
        cadenceFreshnessMs: Long = 3_000L,
        hrFreshnessMs: Long = 3_000L,
        hasRoute: Boolean = true,
        elapsedSec: Long = 120L,
        nowMs: Long = System.currentTimeMillis(),
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
    fun `power missing alert fires after stale exceeding threshold`() {
        val producer = SensorMessageProducer()
        val now = System.currentTimeMillis()
        val msg = producer.checkAndProduce(sensorState(
            powerFreshnessMs = 15_000L,
            nowMs = now,
        ))
        assertNotNull("Should fire power missing alert when power is stale", msg)
        assertEquals("BRAK MOCY", msg!!.title)
    }

    @Test
    fun `power missing respects cooldown`() {
        val producer = SensorMessageProducer()
        val now = System.currentTimeMillis()
        assertNotNull(producer.checkAndProduce(sensorState(powerFreshnessMs = 15_000L, nowMs = now)))
        assertNull(producer.checkAndProduce(sensorState(powerFreshnessMs = 15_000L, nowMs = now + 30_000L)))
    }

    @Test
    fun `power missing fires again after cooldown expires`() {
        val producer = SensorMessageProducer()
        val now = System.currentTimeMillis()
        assertNotNull(producer.checkAndProduce(sensorState(powerFreshnessMs = 15_000L, nowMs = now)))
        assertNotNull(producer.checkAndProduce(sensorState(powerFreshnessMs = 15_000L, nowMs = now + 61_000L)))
    }

    @Test
    fun `power missing does not fire when speed below threshold`() {
        val producer = SensorMessageProducer()
        assertNull(producer.checkAndProduce(sensorState(speedKmh = 3.0, powerFreshnessMs = 15_000L)))
    }

    @Test
    fun `power missing does not fire when cadence and HR both low`() {
        val producer = SensorMessageProducer()
        assertNull(producer.checkAndProduce(sensorState(
            powerFreshnessMs = 15_000L,
            cadence = 10,
            hr = 60,
        )))
    }

    @Test
    fun `HR missing alert fires when power is high enough`() {
        val producer = SensorMessageProducer()
        val now = System.currentTimeMillis()
        val msg = producer.checkAndProduce(sensorState(
            power = 150,
            hrFreshnessMs = 20_000L,
            nowMs = now,
        ))
        assertNotNull("Should fire HR missing when HR stale and power > 120", msg)
        assertEquals("BRAK HR", msg!!.title)
    }

    @Test
    fun `HR missing does not fire when power is low`() {
        val producer = SensorMessageProducer()
        assertNull(producer.checkAndProduce(sensorState(
            power = 100,
            hrFreshnessMs = 20_000L,
        )))
    }

    @Test
    fun `route missing fires only once`() {
        val producer = SensorMessageProducer()
        val now = System.currentTimeMillis()
        assertNotNull(producer.checkAndProduce(sensorState(hasRoute = false, nowMs = now)))
        assertNull(producer.checkAndProduce(sensorState(hasRoute = false, nowMs = now + 1_000L)))
    }

    @Test
    fun `route missing does not fire when route is available`() {
        val producer = SensorMessageProducer()
        assertNull(producer.checkAndProduce(sensorState(hasRoute = true)))
    }

    @Test
    fun `sensors missing fires only once`() {
        val producer = SensorMessageProducer()
        val now = System.currentTimeMillis()
        val msg = producer.checkAndProduce(sensorState(
            cadence = 10,
            hr = 60,
            power = 100,
            powerFreshnessMs = 15_000L,
            cadenceFreshnessMs = 15_000L,
            hrFreshnessMs = 20_000L,
            elapsedSec = 20L,
            nowMs = now,
        ))
        assertNotNull(msg)
        assertTrue(msg!!.title.contains("SENSOR"))
        assertNull(producer.checkAndProduce(sensorState(
            cadence = 10,
            hr = 60,
            power = 100,
            powerFreshnessMs = 15_000L,
            cadenceFreshnessMs = 15_000L,
            hrFreshnessMs = 20_000L,
            elapsedSec = 20L,
            nowMs = now + 1_000L,
        )))
    }

    @Test
    fun `sensors missing does not fire before minimum elapsed`() {
        val producer = SensorMessageProducer()
        assertNull(producer.checkAndProduce(sensorState(
            speedKmh = 3.0,
            cadence = 10,
            hr = 60,
            power = 100,
            powerFreshnessMs = 15_000L,
            cadenceFreshnessMs = 15_000L,
            hrFreshnessMs = 20_000L,
            elapsedSec = 5L,
        )))
    }

    @Test
    fun `reset clears cooldowns and route sensor flags`() {
        val producer = SensorMessageProducer()
        val now = System.currentTimeMillis()
        assertNotNull(producer.checkAndProduce(sensorState(powerFreshnessMs = 15_000L, nowMs = now)))
        assertNull(producer.checkAndProduce(sensorState(powerFreshnessMs = 15_000L, nowMs = now + 30_000L)))
        producer.reset()
        assertNotNull(producer.checkAndProduce(sensorState(powerFreshnessMs = 15_000L, nowMs = now + 31_000L)))
    }

    @Test
    fun `reset clears route fired flag`() {
        val producer = SensorMessageProducer()
        val now = System.currentTimeMillis()
        assertNotNull(producer.checkAndProduce(sensorState(hasRoute = false, nowMs = now)))
        producer.reset()
        assertNotNull(producer.checkAndProduce(sensorState(hasRoute = false, nowMs = now + 1_000L)))
    }
}
