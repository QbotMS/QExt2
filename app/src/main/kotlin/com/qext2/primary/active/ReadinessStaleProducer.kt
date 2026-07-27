package com.qext2.primary.active

/**
 * Alarm ACTIVE: gotowosc (TodayFactor) starsza niz dzis (audyt RSRV 2026-07-26).
 *
 * Filozofia (ustalona z uzytkownikiem): NIE czekamy i NIE dociagamy w tle. Jesli po
 * starcie nagrywania dane gotowosci sa starsze niz dzisiejszy dzien, walimy komunikatem
 * OD RAZU (grace = 0), zeby uzytkownik sie zatrzymal i wymusil rozwiazanie w SETUP
 * (wylaczyc TodayFactor -> RSRV na neutralnym). Komunikat powtarzamy co REPEAT_MS az
 * sytuacja sie rozwiaze: dociagnie sie swieza gotowosc albo user wylaczy TF -- w obu
 * przypadkach readinessStale schodzi na false i alarm cichnie.
 */
class ReadinessStaleProducer(private val logger: (String) -> Unit = {}) {

    companion object {
        private const val REPEAT_MS = 120_000L   // powtarzaj co 2 min, poki nieswieze
        private const val MSG_TTL_MS = 15_000L    // pojedynczy komunikat widoczny 15 s
    }

    private var lastShownMs = 0L

    fun reset() { lastShownMs = 0L }

    /**
     * Wolane co sekunde w petli agregatora.
     * @param readinessStale true = todayFactor nie z dzisiejszej gotowosci
     * @param recording true = aktywnosc wystartowala (nagrywanie)
     * @return komunikat do pokazania albo null
     */
    fun checkAndProduce(readinessStale: Boolean, recording: Boolean, nowMs: Long): ActiveMessage? {
        if (!readinessStale || !recording) {
            lastShownMs = 0L
            return null
        }
        // grace = 0: pierwszy tick ze stanem "nieswieze" pokazuje od razu.
        if (lastShownMs != 0L && nowMs - lastShownMs < REPEAT_MS) return null
        lastShownMs = nowMs
        logger("READINESS_STALE_ALERT nowMs=$nowMs")
        return ActiveMessage(
            id = "readiness_stale_$nowMs",
            title = "GOTOWOSC NIESWIEZA",
            line1 = "Dane starsze niz dzis",
            line2 = "SETUP: wylacz TF",
            severity = ActiveMessageSeverity.WARNING,
            priority = ActiveMessagePriority.WARNING,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + MSG_TTL_MS,
        )
    }
}
