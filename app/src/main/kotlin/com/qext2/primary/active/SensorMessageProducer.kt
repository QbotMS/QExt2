package com.qext2.primary.active

data class SensorState(
    val speedKmh: Double,
    val cadence: Int,
    val hr: Int,
    val power: Int,
    val powerFreshnessMs: Long,
    val cadenceFreshnessMs: Long,
    val hrFreshnessMs: Long,
    val hasRoute: Boolean,
    val elapsedSec: Long,
    val nowMs: Long,
)

class SensorMessageProducer(private val logger: (String) -> Unit = {}) {

    private val cooldowns = mutableMapOf<String, Long>()
    private var routeFired = false
    private var sensorsFired = false

    fun checkAndProduce(state: SensorState): ActiveMessage? {
        return checkPowerMissing(state)
            ?: checkHrMissing(state)
            ?: checkSensorsMissing(state)
            ?: checkRouteMissing(state)
    }

    private fun checkPowerMissing(s: SensorState): ActiveMessage? {
        if (s.speedKmh <= 5.0) return null
        if (s.cadence <= 20 && s.hr <= 90) return null
        if (s.powerFreshnessMs < 10_000L) return null
        if (!useCooldown("power", s.nowMs, 60_000L)) {
            logger("SUPPRESS type=power cooldown")
            return null
        }
        logger("TRIGGER type=power")
        return ActiveMessage(
            id = "sensor_power_${s.nowMs}",
            title = "BRAK MOCY",
            line1 = "SPRAWDŹ SENSOR",
            line2 = null,
            severity = ActiveMessageSeverity.WARNING,
            priority = ActiveMessagePriority.WARNING,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = s.nowMs,
            expiresAtMs = s.nowMs + 8_000L,
        )
    }

    private fun checkHrMissing(s: SensorState): ActiveMessage? {
        if (s.speedKmh <= 5.0) return null
        if (s.power <= 120) return null
        if (s.hrFreshnessMs < 15_000L) return null
        if (!useCooldown("hr", s.nowMs, 90_000L)) {
            logger("SUPPRESS type=hr cooldown")
            return null
        }
        logger("TRIGGER type=hr")
        return ActiveMessage(
            id = "sensor_hr_${s.nowMs}",
            title = "BRAK HR",
            line1 = "PACING OGRANICZONY",
            line2 = null,
            severity = ActiveMessageSeverity.INFO,
            priority = ActiveMessagePriority.INFO,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = s.nowMs,
            expiresAtMs = s.nowMs + 8_000L,
        )
    }

    private fun checkSensorsMissing(s: SensorState): ActiveMessage? {
        if (s.elapsedSec <= 10L) return null
        if (s.powerFreshnessMs < 10_000L) return null
        if (s.cadenceFreshnessMs < 10_000L) return null
        if (s.hrFreshnessMs < 15_000L) return null
        if (sensorsFired) return null
        sensorsFired = true
        logger("TRIGGER type=sensors")
        return ActiveMessage(
            id = "sensor_sensors_${s.nowMs}",
            title = "BRAK SENSORÓW",
            line1 = "POWER / HR / CAD",
            line2 = null,
            severity = ActiveMessageSeverity.WARNING,
            priority = ActiveMessagePriority.WARNING,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = s.nowMs,
            expiresAtMs = s.nowMs + 8_000L,
        )
    }

    private fun checkRouteMissing(s: SensorState): ActiveMessage? {
        if (s.hasRoute || routeFired) return null
        routeFired = true
        logger("TRIGGER type=route")
        return ActiveMessage(
            id = "sensor_route_${s.nowMs}",
            title = "BRAK TRASY",
            line1 = "CLIMB OFF",
            line2 = null,
            severity = ActiveMessageSeverity.INFO,
            priority = ActiveMessagePriority.INFO_LOW,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = s.nowMs,
            expiresAtMs = s.nowMs + 15_000L,
        )
    }

    private fun useCooldown(key: String, now: Long, cooldownMs: Long): Boolean {
        val last = cooldowns[key]
        if (last != null && now - last < cooldownMs) return false
        cooldowns[key] = now
        return true
    }

    fun reset() {
        routeFired = false
        sensorsFired = false
    }
}
