package com.qext2.primary.weather

import com.qext2.primary.active.WeatherMessageProducer
import com.qext2.primary.active.WeatherMsgState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherClientTest {

    @Test
    fun weatherNoKeyNoMessage() {
        val producer = WeatherMessageProducer()
        val state = WeatherMsgState(
            weatherFresh = false,
            temperatureC = null,
            windSpeedMps = null,
            rain1hMm = null,
            condition = null,
            nowMs = System.currentTimeMillis(),
        )
        assertNull(producer.checkAndProduce(state))
    }

    @Test
    fun weatherDefaultValuesDoNotAlert() {
        val producer = WeatherMessageProducer()
        val state = WeatherMsgState(
            weatherFresh = true,
            temperatureC = 20f,
            windSpeedMps = 2f,
            rain1hMm = 0f,
            condition = "Clear",
            nowMs = System.currentTimeMillis(),
        )
        assertNull("Default normal weather must not produce alerts", producer.checkAndProduce(state))
    }

    @Test
    fun weatherStaleNoActiveMessage() {
        val producer = WeatherMessageProducer()
        val state = WeatherMsgState(
            weatherFresh = false,
            temperatureC = 36f,
            windSpeedMps = 10f,
            rain1hMm = 3f,
            condition = "Rain",
            nowMs = System.currentTimeMillis(),
        )
        assertNull("Stale weather must not produce alerts", producer.checkAndProduce(state))
    }

    @Test
    fun weatherValidRainGeneratesMessage() {
        val producer = WeatherMessageProducer()
        val state = WeatherMsgState(
            weatherFresh = true,
            temperatureC = 15f,
            windSpeedMps = 3f,
            rain1hMm = 1.5f,
            condition = "Rain",
            nowMs = System.currentTimeMillis(),
        )
        val msg = producer.checkAndProduce(state)
        assertNotNull("Rain must produce message", msg)
        assertEquals("WX DESZCZ", msg!!.title)
    }

    @Test
    fun weatherValidWindGeneratesMessage() {
        val producer = WeatherMessageProducer()
        val state = WeatherMsgState(
            weatherFresh = true,
            temperatureC = 15f,
            windSpeedMps = 12f,
            rain1hMm = null,
            condition = "Wind",
            nowMs = System.currentTimeMillis(),
        )
        val msg = producer.checkAndProduce(state)
        assertNotNull("Strong wind must produce message", msg)
        assertEquals("WX SILNY WIATR", msg!!.title)
    }

    @Test
    fun weatherValidHeatGeneratesMessage() {
        val producer = WeatherMessageProducer()
        val state = WeatherMsgState(
            weatherFresh = true,
            temperatureC = 37f,
            windSpeedMps = 2f,
            rain1hMm = null,
            condition = "Clear",
            nowMs = System.currentTimeMillis(),
        )
        val msg = producer.checkAndProduce(state)
        assertNotNull("Heat must produce message", msg)
        assertEquals("WX UPAL", msg!!.title)
    }

    @Test
    fun weatherValidColdGeneratesMessage() {
        val producer = WeatherMessageProducer()
        val state = WeatherMsgState(
            weatherFresh = true,
            temperatureC = -5f,
            windSpeedMps = 2f,
            rain1hMm = null,
            condition = "Snow",
            nowMs = System.currentTimeMillis(),
        )
        val msg = producer.checkAndProduce(state)
        assertNotNull("Cold must produce message", msg)
        assertEquals("WX MROZ", msg!!.title)
    }

    fun weatherFreshIsCalculatedCorrectly() {
        val now = System.currentTimeMillis()
        val fresh = WeatherClient.isFresh(com.qext2.primary.weather.WeatherData(
            temperatureC = 20f, feelsLikeC = null, windSpeedMps = 1f,
            windDirectionDeg = 180, humidityPct = 50, rain1hMm = null,
            snow1hMm = null, condition = "Clear", updatedAt = now - 10_000L
        ))
        assertTrue("Weather data from 10s ago must be fresh", fresh)

        val stale = WeatherClient.isFresh(com.qext2.primary.weather.WeatherData(
            temperatureC = 20f, feelsLikeC = null, windSpeedMps = 1f,
            windDirectionDeg = 180, humidityPct = 50, rain1hMm = null,
            snow1hMm = null, condition = "Clear", updatedAt = now - 40 * 60_000L
        ))
        assertTrue("Weather data from 40min ago must be stale", !stale)
    }
}
