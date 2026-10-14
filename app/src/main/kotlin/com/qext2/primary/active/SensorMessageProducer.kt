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
    /** Wiek danych zawodnika w godzinach (audyt pkt B1). Domyslnie swieze. */
    val athleteDataAgeH: Double = 0.0,
    /** todayFactor JUZ po bramce wieku -- to, czym naprawde liczy model. */
    val effectiveTodayFactor: Float = 1.0f,
    /** lampa z radarem: OK | SEARCHING | NA | IDLE, od kiedy (ms), czy w tej jezdzie byla juz polaczona */
    val radarState: String = "NA",
    val radarSinceMs: Long = 0L,
    val radarSeen: Boolean = false,
)

class SensorMessageProducer(private val logger: (String) -> Unit = {}) {

    private val cooldowns = mutableMapOf<String, Long>()
    private var routeFired = false
    private var sensorsFired = false
    private var staleAthleteFired = false
    private var radarStartFired = false

    fun checkAndProduce(state: SensorState): ActiveMessage? {
        return checkPowerMissing(state)
            ?: checkHrMissing(state)
            ?: checkRadarMissing(state)
            ?: checkSensorsMissing(state)
            ?: checkStaleAthleteData(state)
            ?: checkRouteMissing(state)
    }

    /**
     * Dane zawodnika starsze niz doba -> model jedzie na zdegradowanej dyspozycji.
     * Raz na jazde, zeby bylo wiadomo skad inne liczby (audyt pkt B1).
     */
    private fun checkStaleAthleteData(s: SensorState): ActiveMessage? {
        if (staleAthleteFired) return null
        if (s.elapsedSec <= 10L) return null
        if (s.athleteDataAgeH <= 24.0) return null
        staleAthleteFired = true
        val ageTxt = if (s.athleteDataAgeH >= 480.0) ">20d"
            else "${s.athleteDataAgeH.toInt()}h"
        logger("TRIGGER type=stale_athlete ageH=${s.athleteDataAgeH.toInt()} tf=${s.effectiveTodayFactor}")
        return ActiveMessage(
            id = "sensor_stale_athlete_${s.nowMs}",
            title = "DANE STARE",
            line1 = "%s · %.2f".format(ageTxt, s.effectiveTodayFactor),
            line2 = null,
            severity = ActiveMessageSeverity.WARNING,
            priority = ActiveMessagePriority.WARNING,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = s.nowMs,
            expiresAtMs = s.nowMs + 15_000L,
        )
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
            line1 = "SPRAWDŹ PAS HR",
            line2 = null,
            severity = ActiveMessageSeverity.INFO,
            priority = ActiveMessagePriority.INFO,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = s.nowMs,
            expiresAtMs = s.nowMs + 8_000L,
        )
    }

    /**
     * Lampa z radarem (2026-10-14): radar sparowany w Karoo, ale niepolaczony (Searching) >= 15 s.
     * Rozlaczenie w trakcie jazdy -> co 2 min, dopoki nie wroci; nie polaczyl sie od startu -> raz (po 30 s jazdy).
     * Brak radaru w urzadzeniach Karoo (NotAvailable) -> cisza.
     */
    private fun checkRadarMissing(s: SensorState): ActiveMessage? {
        if (s.radarState != "SEARCHING") return null
        if (s.elapsedSec <= 30L) return null
        if (s.nowMs - s.radarSinceMs < 15_000L) return null
        if (s.radarSeen) {
            if (!useCooldown("radar", s.nowMs, 120_000L)) return null
        } else {
            if (radarStartFired) return null
            radarStartFired = true
        }
        logger("TRIGGER type=radar seen=${s.radarSeen}")
        return ActiveMessage(
            id = "sensor_radar_${s.nowMs}",
            title = "BRAK RADARU",
            line1 = "SPRAWDŹ LAMPĘ",
            line2 = null,
            severity = ActiveMessageSeverity.WARNING,
            priority = ActiveMessagePriority.WARNING,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = s.nowMs,
            expiresAtMs = s.nowMs + 10_000L,
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
        staleAthleteFired = false
        radarStartFired = false
        cooldowns.clear()
    }
}
