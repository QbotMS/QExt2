package com.qext2.primary.statsv2

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Dane symulacyjne dla STATS v2 (test bez jazdy). Cykl 120 s: wartosci przechodza przez rozne stany
 * (strefy NP, IF ponizej/powyzej zoltego pola, RSRV zielony/zolty/czerwony, postep trasy), zeby
 * zobaczyc wszystkie reguly rysowania. Wlaczane w ustawieniach QExt2 ("STATS v2: dane demo").
 */
object StatsV2Demo {
    fun at(nowMs: Long): StatsV2Data {
        val t = ((nowMs / 1000L) % 120L).toFloat()
        val f = t / 120f                                   // 0..1 postep cyklu
        val total = 164f
        val done = total * f
        val left = total - done
        val ifv = 0.40f + 0.75f * f                       // .40 -> 1.15
        val np = (150 + 160 * f).toInt()                   // 150 -> 310 W
        val cp = 250f
        val r = np / cp
        val zone = when { r < 0.55f -> 1; r < 0.75f -> 2; r < 0.90f -> 3; r < 1.05f -> 4; r < 1.20f -> 5; else -> 6 }
        val rsrv = (100 - 95 * f).toInt().coerceIn(0, 100) // 100 -> 5
        val moving = (f * 8.5f * 3600f).toLong()
        val stops = (f * 1.2f * 3600f).toLong()
        val gross = max(60L, moving + stops)
        val paved0 = 92f; val gravel0 = 61f; val loose0 = 11f
        // pozostale km wg klasy: zjadane w kolejnosci asfalt -> szuter -> sypkie
        var rem = done
        val paved = max(0f, paved0 - rem); rem = max(0f, rem - paved0)
        val gravel = max(0f, gravel0 - rem); rem = max(0f, rem - gravel0)
        val loose = max(0f, loose0 - rem)
        val ascTot = 1280
        val ascDone = (ascTot * f).toInt()
        return StatsV2Data(
            np = np,
            npZone = zone,
            ifv = ifv,
            vi = 1.00f + 0.25f * (0.5f + 0.5f * sin(t / 9f)),
            rsrv = rsrv,
            xss = 20f + 160f * f,
            kcal = (300 + 2900 * f).toInt(),
            hasRoute = true,
            doneKm = done,
            totalKm = total,
            etaMs = nowMs + ((left / 19f) * 3600_000f).toLong() + 20 * 60_000L,
            avgGrossKmh = done / (gross / 3600f),
            movingSec = moving,
            stopsSec = stops,
            surfPaved = paved,
            surfGravel = gravel,
            surfLoose = loose,
            ascDone = ascDone,
            ascLeft = ascTot - ascDone,
            carbRate = 60 + (30 * f).toInt(),
            carbSpent = (min(1f, f) * 1480).toInt(),
            fluidRate = 0.5f + 0.3f * f,
            cadAvg = 78 + (8 * sin(t / 7f)).toInt(),
            batDrain = 4f + 4f * f,
            batLeftSec = ((100f - 60f * f) / (4f + 4f * f) * 3600f).toLong(),
            demo = true,
        )
    }
}
