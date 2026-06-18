package com.qext2.primary.active

/**
 * Adaptacyjny tryb jazdy (AUTO).
 *
 * Śledzi średnią moc z ostatnich 20 minut (rolling window).
 * Gdy kolarze konsekwentnie jedzie mocniej → AUTO przesuwa się w OFENSYWNA.
 * Gdy zachowawczo → DEFENSYWNA.
 *
 * Działa TYLKO gdy riding mode == AUTO (3).
 * Tryby fixowane (DEFENSYWNA/NORMALNA/OFENSYWNA) nie są dotykane.
 *
 * TodayFactor jest inicjalizacją (punkt startowy przed jazdą).
 * sessionBias nadpisuje go po 20 minutach danych.
 */
class AdaptiveModeTracker {

    private val windowMs = 20 * 60 * 1000L       // 20 minut
    private val hysteresisMs = 5 * 60 * 1000L    // 5 minut stabilności przed zmianą
    private val minSamples = 60                   // ~1 minuta próbek

    private val powerWindow = ArrayDeque<Pair<Long, Int>>()  // (timestampMs, watts)
    private var currentMode = 0   // 0=NORMALNA, -1=DEFENSYWNA, +1=OFENSYWNA
    private var pendingMode = 0
    private var pendingModeSinceMs = 0L

    /**
     * Wołane co sekundę z pętli aggregatora.
     * @param nowMs     aktualny timestamp
     * @param powerW    bieżąca moc (3s avg)
     * @param effectiveLtp  LTP × todayFactor
     * @return modeFactor: 0.88 / 1.00 / 1.12
     */
    fun update(nowMs: Long, powerW: Int, effectiveLtp: Float): Float {
        // Dodaj próbkę
        powerWindow.addLast(nowMs to powerW)

        // Usuń starsze niż 20 min
        while (powerWindow.isNotEmpty() && nowMs - powerWindow.first().first > windowMs) {
            powerWindow.removeFirst()
        }

        // Za mało danych — zostań przy bieżącym trybie
        if (powerWindow.size < minSamples) return modeToFactor(currentMode)

        val ltp = effectiveLtp.coerceAtLeast(50f)
        val avgPow = powerWindow.map { it.second }.average().toFloat()
        val sessionBias = avgPow / ltp

        val rawMode = when {
            sessionBias > 1.06f -> 1
            sessionBias < 0.92f -> -1
            else -> 0
        }

        // Histereza: wymagaj 5 minut stabilności przed zmianą trybu
        if (rawMode != currentMode) {
            if (rawMode != pendingMode) {
                pendingMode = rawMode
                pendingModeSinceMs = nowMs
            } else if (nowMs - pendingModeSinceMs >= hysteresisMs) {
                currentMode = rawMode
                pendingMode = rawMode
            }
        } else {
            pendingMode = rawMode
            pendingModeSinceMs = nowMs
        }

        return modeToFactor(currentMode)
    }

    fun getCurrentMode(): Int = currentMode

    fun reset() {
        powerWindow.clear()
        currentMode = 0
        pendingMode = 0
        pendingModeSinceMs = 0L
    }

    private fun modeToFactor(mode: Int): Float = when (mode) {
        -1 -> 0.88f
        1  -> 1.12f
        else -> 1.00f
    }
}
