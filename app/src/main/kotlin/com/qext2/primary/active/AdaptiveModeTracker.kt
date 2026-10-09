package com.qext2.primary.active

/**
 * Tryb AUTO (D1, plan v2 2026-10-09) -- system sam dobiera tryb jazdy; Michal trybu nie zmienia.
 *
 * Tryb = strategia na reszte jazdy:
 *   prognoza RSRV na mecie = RSRV teraz - tempo spadku RSRV (ostatnie 20 min) x czas do mety (ETA);
 *   < 20 %  -> OSTROZNY (sufit x0.88), > 60 % -> OFENSYWNY (x1.12), inaczej NORMALNY (x1.00).
 * Zmiana trybu dopiero po 5 min stabilnej decyzji (histereza). Bez trasy/ETA albo przed 10 min danych -> NORMALNY.
 */
class AdaptiveModeTracker {
    private val hist = ArrayDeque<Pair<Long, Int>>()   // (czas ms, RSRV %)
    private var currentMode = 0                       // -1 ostrozny, 0 normalny, +1 ofensywny
    private var pendingMode = 0
    private var pendingSinceMs = 0L

    fun update(nowMs: Long, rsrvPct: Int, remainingSec: Double?, hasRoute: Boolean): Float {
        if (rsrvPct in 0..100) hist.addLast(nowMs to rsrvPct)
        while (hist.isNotEmpty() && nowMs - hist.first().first > WINDOW_MS) hist.removeFirst()
        val raw = rawMode(nowMs, rsrvPct, remainingSec, hasRoute)
        if (raw == currentMode) { pendingMode = raw; pendingSinceMs = nowMs }
        else if (raw != pendingMode) { pendingMode = raw; pendingSinceMs = nowMs }
        else if (nowMs - pendingSinceMs >= HYSTERESIS_MS) currentMode = raw
        return factor(currentMode)
    }

    /** Prognoza RSRV na mecie albo null (brak trasy, ETA lub za malo historii). */
    fun projectedAtFinish(nowMs: Long, rsrvPct: Int, remainingSec: Double?, hasRoute: Boolean): Double? {
        if (!hasRoute || remainingSec == null || remainingSec <= 0.0 || rsrvPct !in 0..100) return null
        val first = hist.firstOrNull() ?: return null
        val spanH = (nowMs - first.first) / 3_600_000.0
        if (spanH < MIN_HISTORY_H) return null
        val ratePerH = ((first.second - rsrvPct) / spanH).coerceAtLeast(0.0)
        return rsrvPct - ratePerH * remainingSec / 3600.0
    }

    private fun rawMode(nowMs: Long, rsrvPct: Int, remainingSec: Double?, hasRoute: Boolean): Int {
        val p = projectedAtFinish(nowMs, rsrvPct, remainingSec, hasRoute) ?: return 0
        return when { p < LOW_PCT -> -1; p > HIGH_PCT -> 1; else -> 0 }
    }

    fun getCurrentMode(): Int = currentMode

    fun reset() { hist.clear(); currentMode = 0; pendingMode = 0; pendingSinceMs = 0L }

    companion object {
        const val WINDOW_MS = 20 * 60_000L
        const val HYSTERESIS_MS = 5 * 60_000L
        const val MIN_HISTORY_H = 10.0 / 60.0
        const val LOW_PCT = 20.0
        const val HIGH_PCT = 60.0
        fun factor(mode: Int): Float = when (mode) { -1 -> 0.88f; 1 -> 1.12f; else -> 1.00f }
        /** JEDNO mapowanie trybu z SETUP (0 ostrozny, 1 normalny, 2 ofensywny, 3 AUTO). */
        fun modeFactorFor(code: Int, autoFactor: Float): Float = when (code) {
            0 -> 0.88f; 1 -> 1.00f; 2 -> 1.12f; else -> autoFactor
        }
    }
}
