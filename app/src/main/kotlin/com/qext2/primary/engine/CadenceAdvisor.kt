package com.qext2.primary.engine

import org.json.JSONObject

/**
 * E6.4 (plan v2): doradca biegow z NATURALNEJ kadencji Michala (model QBota z jego jazd, /ride-readiness "cadenceModel").
 * Klucz roweru = nr ANT przerzutki AXS (10625 Grizl, 27856 Grail), "none" = brak AXS (Monster).
 * Kolor tylko gdy model dla roweru istnieje (>= 15 jazd) i przedzial ma dane: czerwony < p10-3, zolty < p25-3.
 * Ponizej 50% CP i na zjazdach (< -2%) brak porady.
 */
object CadenceAdvisor {
    @Volatile private var bikes: Map<String, Map<Pair<Int, Int>, Pair<Int, Int>>> = emptyMap()
    const val RED = 0xFFFF5252.toInt()
    const val AMBER = 0xFFFACC15.toInt()

    fun load(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val b = JSONObject(json).optJSONObject("bikes") ?: return
            val out = mutableMapOf<String, Map<Pair<Int, Int>, Pair<Int, Int>>>()
            for (k in b.keys()) {
                val arr = b.getJSONArray(k); val m = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>>()
                for (i in 0 until arr.length()) { val e = arr.getJSONArray(i); m[e.getInt(0) to e.getInt(1)] = e.getInt(2) to e.getInt(3) }
                out[k] = m
            }
            bikes = out
        } catch (_: Exception) {}
    }

    fun hasModel(bikeKey: String?): Boolean = bikeKey != null && bikes[bikeKey]?.isNotEmpty() == true

    /** Kolor pola biegu albo null (brak porady). */
    fun color(bikeKey: String?, powerW: Int?, cpW: Float, gradePct: Double, cadence: Int?): Int? {
        if (bikeKey == null || powerW == null || cadence == null || cadence <= 0 || cpW <= 0f) return null
        val m = bikes[bikeKey] ?: return null
        val pct = powerW / cpW
        if (pct < 0.5f || gradePct < -2.0) return null
        val pb = if (pct < 0.7f) 1 else if (pct < 0.85f) 2 else if (pct < 1.0f) 3 else 4
        val gb = if (gradePct < 2.0) 0 else if (gradePct < 5.0) 1 else 2
        val (p10, p25) = m[pb to gb] ?: return null
        return when { cadence < p10 - 3 -> RED; cadence < p25 - 3 -> AMBER; else -> null }
    }
}
