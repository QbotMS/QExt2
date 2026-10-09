package com.qext2.primary.util

/**
 * E5.1 (plan v2, decyzja 09.10): jedyna jednostka wiatru w QExt2 = m/s (obliczenia i ekran).
 * karoo-headwind nadaje w jednostce ze SWOICH ustawien -- QExt2 zna ja z SETUP (domyslnie km/h).
 * 0 = km/h, 1 = m/s, 2 = mph, 3 = wezly.
 */
object WindUnits {
    @Volatile var sourceUnit: Int = 0
    val LABELS = listOf("km/h", "m/s", "mph", "kn")
    fun toMps(v: Double, unit: Int = sourceUnit): Double = when (unit) {
        1 -> v
        2 -> v * 0.44704
        3 -> v * 0.514444
        else -> v / 3.6
    }
}
