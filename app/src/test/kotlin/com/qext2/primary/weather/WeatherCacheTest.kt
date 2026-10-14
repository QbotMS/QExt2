package com.qext2.primary.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zapas prognozy na brak sieci (2026-10-14). */
class WeatherCacheTest {
    private val t0 = 1_800_000_000L - (1_800_000_000L % 3600L)   // pelna godzina [s]
    private fun body(rainAtH: Int = -1) = """{"hourly":{"time":[${(0..12).joinToString(",") { "${t0 + it * 3600L}" }}],
        "wind_speed_10m":[${(0..12).joinToString(",") { "5.0" }}],"wind_direction_10m":[${(0..12).joinToString(",") { "270" }}],
        "wind_gusts_10m":[${(0..12).joinToString(",") { "9.0" }}],
        "precipitation":[${(0..12).joinToString(",") { if (it == rainAtH) "1.2" else "0.0" }}],
        "precipitation_probability":[${(0..12).joinToString(",") { if (it == rainAtH) "70" else "10" }}],
        "temperature_2m":[${(0..12).joinToString(",") { "12.0" }}],
        "weather_code":[${(0..12).joinToString(",") { if (it == rainAtH) "61" else "3" }}]}}"""
    private val now = (t0 + 600L) * 1000L   // 10 min po pelnej godzinie

    @Test fun wiatr_z_zapasu() {
        val d = WeatherCache.parse(body(), doubleArrayOf(52.2), doubleArrayOf(21.0), now - 60_000L)
        val w = WeatherCache.lookup(d, 52.21, 21.01, now)
        assertNotNull(w)
        assertEquals(5.0f, w!!.windMps, 0.01f); assertEquals(270, w.windFromDeg); assertEquals(9.0f, w.gustMps!!, 0.01f)
    }

    @Test fun za_stary_zapas_null() {
        val d = WeatherCache.parse(body(), doubleArrayOf(52.2), doubleArrayOf(21.0), now - 13 * 3_600_000L)
        assertNull(WeatherCache.lookup(d, 52.2, 21.0, now))
    }

    @Test fun deszcz_z_zapasu_za_ile_minut() {
        // godzina 2 (t0+2h) opisuje opad t0+1h..t0+2h -> start za 50 min
        val d = WeatherCache.parse(body(rainAtH = 2), doubleArrayOf(52.2), doubleArrayOf(21.0), now)
        val r = WeatherCache.rainSoon(d, 52.2, 21.0, now)
        assertNotNull(r); assertEquals(50, r!!.minutes); assertTrue(r.cached); assertEquals("RAIN", r.kind)
    }

    @Test fun bez_deszczu_null() {
        val d = WeatherCache.parse(body(), doubleArrayOf(52.2), doubleArrayOf(21.0), now)
        assertNull(WeatherCache.rainSoon(d, 52.2, 21.0, now))
    }

    @Test fun punkty_bez_trasy_jeden() = assertEquals(1, WeatherCache.points(null, 52.2, 21.0).size)
}
