package com.qext2.primary.engine

object ReservePolicy {
    /** Laczne obciazenie dnia w XSS: baza dobowa + biezaca sesja. */
    fun effectiveLoad(dailyBase: Float, sessionLoad: Float): Float {
        val base = StatsCalculator.safetyFloat(dailyBase)
        val session = StatsCalculator.safetyFloat(sessionLoad)
        return (base + session).coerceIn(0f, 9999f)
    }

}
