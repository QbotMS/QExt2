package com.qext2.primary.weather

/** Biezaca pogoda dla pol ACTIVE/komunikatow - zrodlo: Open-Meteo (RouteWeather). OpenWeather usuniety 2026-10-14. */
data class WeatherData(
    val temperatureC: Float,
    val feelsLikeC: Float?,
    val windSpeedMps: Float,
    val windDirectionDeg: Int,
    val humidityPct: Int,
    val rain1hMm: Float?,
    val snow1hMm: Float?,
    val condition: String,
    val updatedAt: Long,
    val source: String = "open-meteo",
)

object WeatherClient {
    private const val FRESH_MAX_MS = 30 * 60_000L

    fun isFresh(data: WeatherData): Boolean {
        val age = System.currentTimeMillis() - data.updatedAt
        return age in 0..FRESH_MAX_MS
    }
}
