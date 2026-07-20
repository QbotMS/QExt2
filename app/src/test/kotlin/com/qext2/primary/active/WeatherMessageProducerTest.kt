package com.qext2.primary.active

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherMessageProducerTest {

    private fun state(
        fresh: Boolean = true,
        temp: Float? = 15f,
        wind: Float? = 3f,
        rain: Float? = null,
        condition: String? = null,
        nowMs: Long = 1_000_000L,
    ) = WeatherMsgState(
        weatherFresh = fresh,
        temperatureC = temp,
        windSpeedMps = wind,
        rain1hMm = rain,
        condition = condition,
        nowMs = nowMs,
    )

    @Test
    fun `rain fires from condition even when rain1hMm is null`() {
        val msg = WeatherMessageProducer().checkAndProduce(state(condition = "Rain"))
        assertEquals("WX DESZCZ", msg?.title)
        assertEquals("OPADY", msg?.line1)
    }

    @Test
    fun `drizzle fires from condition even when rain1hMm is null`() {
        val msg = WeatherMessageProducer().checkAndProduce(state(condition = "Drizzle"))
        assertEquals("WX MŻAWKA", msg?.title)
    }

    @Test
    fun `storm wins over rain`() {
        val msg = WeatherMessageProducer().checkAndProduce(state(condition = "Thunderstorm", rain = 1.0f))
        assertEquals("WX BURZA", msg?.title)
    }

    @Test
    fun `heavy rain amount escalates to ulewa`() {
        val msg = WeatherMessageProducer().checkAndProduce(state(condition = "Rain", rain = 3.0f))
        assertEquals("WX ULEWA", msg?.title)
    }

    @Test
    fun `rain amount above light threshold is deszcz not mzawka`() {
        val msg = WeatherMessageProducer().checkAndProduce(state(condition = null, rain = 0.8f))
        assertEquals("WX DESZCZ", msg?.title)
    }

    @Test
    fun `small rain amount is mzawka`() {
        val msg = WeatherMessageProducer().checkAndProduce(state(condition = null, rain = 0.2f))
        assertEquals("WX MŻAWKA", msg?.title)
    }

    @Test
    fun `cold and wet from condition fires cold-wet warning`() {
        val msg = WeatherMessageProducer().checkAndProduce(state(temp = 5f, condition = "Rain"))
        assertEquals("WX ZIMNO+MOKRO", msg?.title)
    }

    @Test
    fun `dry clouds produce no message`() {
        val msg = WeatherMessageProducer().checkAndProduce(state(condition = "Clouds"))
        assertNull(msg)
    }

    @Test
    fun `stale weather produces no message`() {
        val msg = WeatherMessageProducer().checkAndProduce(state(fresh = false, condition = "Rain"))
        assertNull(msg)
    }
}
