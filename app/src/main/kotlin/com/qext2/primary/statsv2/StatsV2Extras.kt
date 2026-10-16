package com.qext2.primary.statsv2

/**
 * STATS v2 (16.10.2026): dane spoza snapshotu jazdy.
 * - xssPlan: plan dnia Trenera dla roweru (QBot /ride-readiness -> xssPlanToday), pasek pod XSS.
 * - PLAN CZASU: na razie roznica biezacej ETA wobec ETA ustalonej po 1. km (pozniej: model czasu przejazdu QBota).
 */
object StatsV2Extras {
    @Volatile var xssPlan: Float? = null

    private var refEtaMs: Long? = null
    private var lastDistKm = 0f

    /** (roznica w min: + = za planem, ETA odniesienia). Nowa jazda = dystans wrocil do zera. */
    @Synchronized
    fun plan(etaMs: Long?, distKm: Float): Pair<Int?, Long?> {
        if (distKm + 0.5f < lastDistKm) refEtaMs = null
        lastDistKm = distKm
        if (etaMs == null) return null to refEtaMs
        if (refEtaMs == null && distKm >= 1f) refEtaMs = etaMs
        val ref = refEtaMs ?: return null to null
        return Math.round((etaMs - ref) / 60_000.0).toInt() to ref
    }

    @Synchronized
    fun resetForTest() {
        refEtaMs = null
        lastDistKm = 0f
        xssPlan = null
    }
}
