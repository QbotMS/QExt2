package com.qext2.primary.engine

object ReservePolicy {
    private const val STARTUP_STATIONARY_WINDOW_SEC = 30L

    /** Laczne obciazenie dnia w XSS: baza dobowa + biezaca sesja. */
    fun effectiveLoad(dailyBase: Float, sessionLoad: Float): Float {
        val base = StatsCalculator.safetyFloat(dailyBase)
        val session = StatsCalculator.safetyFloat(sessionLoad)
        return (base + session).coerceIn(0f, 9999f)
    }

    fun shouldApplySleepRefresh(
        sleepRefreshPending: Boolean,
        isMoving: Boolean,
        elapsedSec: Long,
        stopDurationSec: Long,
        minStopForRefreshSec: Long,
    ): Boolean {
        if (!sleepRefreshPending || isMoving) return false
        if (elapsedSec in 0..STARTUP_STATIONARY_WINDOW_SEC) return true
        return stopDurationSec >= minStopForRefreshSec
    }
}
