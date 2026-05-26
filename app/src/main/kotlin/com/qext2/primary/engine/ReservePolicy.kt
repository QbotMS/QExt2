package com.qext2.primary.engine

object ReservePolicy {
    private const val STARTUP_STATIONARY_WINDOW_SEC = 30L

    fun effectiveTss(dailyTssBase: Float, sessionTss: Float): Float {
        val base = StatsCalculator.safetyFloat(dailyTssBase)
        val session = StatsCalculator.safetyFloat(sessionTss)
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
