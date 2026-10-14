package com.qext2.primary.active

/**
 * Klasa koloru mocy (2026-10-14). Czysta funkcja - testy PowerColorModelTest. Opis zasad: RideDataAggregator.unifiedPowerColors.
 * Zwraca: LOW (bialy, slabo) | GREEN | YELLOW | RED | ALARM.
 */
object PowerColorModel {
    const val LOW_FRAC = 0.75f
    const val RED_AFTER_MS = 10_000L

    fun classify(p30: Float, ltp: Float, ceiling: Float, climbing: Boolean, wbalPct: Int, overForMs: Long, alarm: Boolean): String {
        if (alarm) return "ALARM"
        if (p30 < LOW_FRAC * ltp) return "LOW"
        if (climbing) return when {
            wbalPct !in 0..100 -> "GREEN"
            wbalPct > 50 -> "GREEN"
            wbalPct >= 30 -> "YELLOW"
            else -> "RED"
        }
        if (p30 < ceiling) return "GREEN"
        return if (overForMs >= RED_AFTER_MS) "RED" else "YELLOW"
    }
}
