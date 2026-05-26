package com.qext2.primary.active

import java.util.Locale

data class WeatherMsgState(
    val weatherFresh: Boolean,
    val temperatureC: Float?,
    val windSpeedMps: Float?,
    val rain1hMm: Float?,
    val condition: String?,
    val nowMs: Long,
)

class WeatherMessageProducer(private val logger: (String) -> Unit = {}) {

    private val cooldowns = mutableMapOf<String, Long>()

    companion object {
        private const val RAIN_THRESHOLD_MM = 1.0
        private const val WIND_THRESHOLD_MPS = 8.0
        private const val HEAT_THRESHOLD_C = 35.0f
        private const val COLD_THRESHOLD_C = 0.0f
    }

    fun checkAndProduce(state: WeatherMsgState): ActiveMessage? {
        if (!state.weatherFresh) {
            logger("WEATHER_REJECT reason=weather_not_fresh")
            return null
        }

        return checkRain(state)
            ?: checkWind(state)
            ?: checkHeat(state)
            ?: checkCold(state)
    }

    private fun checkRain(state: WeatherMsgState): ActiveMessage? {
        val rain = state.rain1hMm ?: return null
        if (rain < RAIN_THRESHOLD_MM) return null
        if (!useCooldown("rain", state.nowMs, 600_000L)) return null
        logger("WEATHER_TRIGGER type=rain rain=${String.format(Locale.US, "%.1f", rain)}mm")
        return ActiveMessage(
            id = "weather_rain_${state.nowMs}",
            title = "DESZCZ",
            line1 = "${String.format(Locale.US, "%.1f", rain)} mm/h",
            line2 = null,
            severity = ActiveMessageSeverity.WARNING,
            priority = ActiveMessagePriority.WARNING,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = state.nowMs,
            expiresAtMs = state.nowMs + 10_000L,
        )
    }

    private fun checkWind(state: WeatherMsgState): ActiveMessage? {
        val wind = state.windSpeedMps ?: return null
        if (wind < WIND_THRESHOLD_MPS) return null
        if (!useCooldown("wind", state.nowMs, 600_000L)) return null
        logger("WEATHER_TRIGGER type=wind wind=${String.format(Locale.US, "%.1f", wind)}m/s")
        return ActiveMessage(
            id = "weather_wind_${state.nowMs}",
            title = "WIATR",
            line1 = "${String.format(Locale.US, "%.1f", wind)} m/s",
            line2 = null,
            severity = ActiveMessageSeverity.WARNING,
            priority = ActiveMessagePriority.WARNING,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = state.nowMs,
            expiresAtMs = state.nowMs + 10_000L,
        )
    }

    private fun checkHeat(state: WeatherMsgState): ActiveMessage? {
        val temp = state.temperatureC ?: return null
        if (temp < HEAT_THRESHOLD_C) return null
        if (!useCooldown("heat", state.nowMs, 900_000L)) return null
        logger("WEATHER_TRIGGER type=heat temp=${String.format(Locale.US, "%.0f", temp)}C")
        return ActiveMessage(
            id = "weather_heat_${state.nowMs}",
            title = "UPAL",
            line1 = "${String.format(Locale.US, "%.0f", temp)}°C",
            line2 = null,
            severity = ActiveMessageSeverity.WARNING,
            priority = ActiveMessagePriority.WARNING,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = state.nowMs,
            expiresAtMs = state.nowMs + 10_000L,
        )
    }

    private fun checkCold(state: WeatherMsgState): ActiveMessage? {
        val temp = state.temperatureC ?: return null
        if (temp > COLD_THRESHOLD_C) return null
        if (!useCooldown("cold", state.nowMs, 900_000L)) return null
        logger("WEATHER_TRIGGER type=cold temp=${String.format(Locale.US, "%.0f", temp)}C")
        return ActiveMessage(
            id = "weather_cold_${state.nowMs}",
            title = "MROZ",
            line1 = "${String.format(Locale.US, "%.0f", temp)}°C",
            line2 = null,
            severity = ActiveMessageSeverity.WARNING,
            priority = ActiveMessagePriority.WARNING,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = state.nowMs,
            expiresAtMs = state.nowMs + 10_000L,
        )
    }

    private fun useCooldown(key: String, nowMs: Long, cooldownMs: Long): Boolean {
        val last = cooldowns[key]
        if (last != null && nowMs - last < cooldownMs) {
            logger("WEATHER_SUPPRESS type=$key cooldown")
            return false
        }
        cooldowns[key] = nowMs
        return true
    }
}
