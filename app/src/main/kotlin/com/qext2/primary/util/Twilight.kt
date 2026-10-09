package com.qext2.primary.util

/**
 * Najblizsze zdarzenie zmierzch/swit (fix 2026-10-09: o 17:00 pokazywal sie swit).
 * Karoo (CIVIL_DAWN/CIVIL_DUSK) po minieciu zdarzenia podaje juz godzine z NASTEPNEGO dnia, wiec nie wolno
 * sprawdzac "swit w przyszlosci" przed "zmierzch w przyszlosci". Bierzemy najblizsze przyszle z obu
 * (przesuwajac przeszle o pelne doby).
 * @return (czas ms, true = swit) albo null, gdy brak danych.
 */
object Twilight {
    private const val DAY = 86_400_000L
    fun next(nowMs: Long, dawnMs: Long, duskMs: Long): Pair<Long, Boolean>? {
        fun fwd(t: Long): Long? {
            if (t <= 0L) return null
            var x = t
            while (x <= nowMs) x += DAY
            while (x - DAY > nowMs) x -= DAY
            return x
        }
        val d = fwd(dawnMs)?.let { it to true }
        val k = fwd(duskMs)?.let { it to false }
        return listOfNotNull(d, k).minByOrNull { it.first }
    }
}
