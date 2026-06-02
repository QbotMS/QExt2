package com.qext2.primary.active

import java.util.Locale

data class WeatherMsgState(
    val weatherFresh: Boolean,
    val temperatureC: Float?,
    val windSpeedMps: Float?, // WX/forecast wind context — NOT live wind (live wind = karoo-headwind)
    val rain1hMm: Float?,
    val condition: String?,
    val nowMs: Long,
)

class WeatherMessageProducer(private val logger: (String) -> Unit = {}) {

    private val cooldowns = mutableMapOf<String, Long>()

    companion object {
        private const val STORM_KEYWORDS = "thunder|storm|burza"
        private const val RAIN_HEAVY_MM = 2.0
        private const val RAIN_LIGHT_MM = 0.5
        private const val COLD_WET_TEMP_C = 8f
        private const val HEAT_THRESHOLD_C = 35f
        private const val COLD_THRESHOLD_C = 0f
        private const val STRONG_WIND_MPS = 12.0
    }

    fun checkAndProduce(state: WeatherMsgState): ActiveMessage? {
        if (!state.weatherFresh) {
            logger("WEATHER_REJECT reason=weather_not_fresh")
            return null
        }
        return checkStorm(state)
            ?: checkHeavyRain(state)
            ?: checkColdWet(state)
            ?: checkLightRain(state)
            ?: checkHeat(state)
            ?: checkCold(state)
            ?: checkStrongWind(state)
    }

    private fun checkStorm(state: WeatherMsgState): ActiveMessage? {
        val cond = state.condition?.lowercase() ?: return null
        if (!STORM_KEYWORDS.split("|").any { cond.contains(it) }) return null
        if (!useCooldown("storm", state.nowMs, 600_000L)) return null
        logger("WEATHER_TRIGGER type=storm cond=$cond")
        return ActiveMessage(
            id = "weather_storm_${state.nowMs}",
            title = "WX BURZA",
            line1 = "SPRAWDZ RADAR",
            line2 = null,
            severity = ActiveMessageSeverity.CRITICAL,
            priority = ActiveMessagePriority.CRITICAL,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = state.nowMs,
            expiresAtMs = state.nowMs + 12_000L,
        )
    }

    private fun checkHeavyRain(state: WeatherMsgState): ActiveMessage? {
        val rain = state.rain1hMm ?: return null
        if (rain < RAIN_HEAVY_MM) return null
        if (!useCooldown("rain_heavy", state.nowMs, 600_000L)) return null
        logger("WEATHER_TRIGGER type=heavy_rain rain=${String.format(Locale.US, "%.1f", rain)}mm")
        return ActiveMessage(
            id = "weather_heavy_rain_${state.nowMs}",
            title = "WX ULEWA",
            line1 = "${String.format(Locale.US, "%.1f", rain)} mm/h",
            line2 = "SHELL / SCHRON?",
            severity = ActiveMessageSeverity.CRITICAL,
            priority = ActiveMessagePriority.CRITICAL,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = state.nowMs,
            expiresAtMs = state.nowMs + 12_000L,
        )
    }

    private fun checkColdWet(state: WeatherMsgState): ActiveMessage? {
        val rain = state.rain1hMm ?: return null
        val temp = state.temperatureC ?: return null
        if (rain < RAIN_LIGHT_MM || temp > COLD_WET_TEMP_C) return null
        if (!useCooldown("cold_wet", state.nowMs, 900_000L)) return null
        logger("WEATHER_TRIGGER type=cold_wet temp=${String.format(Locale.US, "%.0f", temp)}C rain=${String.format(Locale.US, "%.1f", rain)}mm")
        return ActiveMessage(
            id = "weather_cold_wet_${state.nowMs}",
            title = "WX ZIMNO+MOKRO",
            line1 = "${String.format(Locale.US, "%.0f", temp)}°C · ${String.format(Locale.US, "%.1f", rain)} mm/h",
            line2 = "SHELL READY",
            severity = ActiveMessageSeverity.WARNING,
            priority = ActiveMessagePriority.WARNING,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = state.nowMs,
            expiresAtMs = state.nowMs + 10_000L,
        )
    }

    private fun checkLightRain(state: WeatherMsgState): ActiveMessage? {
        val rain = state.rain1hMm ?: return null
        if (rain < RAIN_LIGHT_MM) return null
        if (!useCooldown("rain_light", state.nowMs, 600_000L)) return null
        logger("WEATHER_TRIGGER type=light_rain rain=${String.format(Locale.US, "%.1f", rain)}mm")
        return ActiveMessage(
            id = "weather_light_rain_${state.nowMs}",
            title = "WX DESZCZ",
            line1 = "${String.format(Locale.US, "%.1f", rain)} mm/h",
            line2 = "SHELL READY",
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
            title = "WX UPAL",
            line1 = "${String.format(Locale.US, "%.0f", temp)}°C",
            line2 = "PIJ REGULARNIE",
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
            title = "WX MROZ",
            line1 = "${String.format(Locale.US, "%.0f", temp)}°C",
            line2 = null,
            severity = ActiveMessageSeverity.WARNING,
            priority = ActiveMessagePriority.WARNING,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = state.nowMs,
            expiresAtMs = state.nowMs + 10_000L,
        )
    }

    private fun checkStrongWind(state: WeatherMsgState): ActiveMessage? {
        val wind = state.windSpeedMps ?: return null
        if (wind < STRONG_WIND_MPS) return null
        if (!useCooldown("strong_wind", state.nowMs, 600_000L)) return null
        logger("WEATHER_TRIGGER type=strong_wind wind=${String.format(Locale.US, "%.1f", wind)}m/s")
        return ActiveMessage(
            id = "weather_strong_wind_${state.nowMs}",
            title = "WX SILNY WIATR",
            line1 = "${String.format(Locale.US, "%.1f", wind)} m/s",
            line2 = "LIVE=HEADWIND",
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
