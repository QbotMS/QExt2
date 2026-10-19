package com.qext2.primary.engine

import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.model.PrimaryRideSnapshot
import com.qext2.primary.model.SurfaceType

/**
 * AERO v3 „POZYCJA” (2026-10-19) — most Karoo -> AeroCore (logika i testy: AeroCore.kt, docs/AERO.md rozdz. v3).
 * Wejscia na zywo: moc 3 s, predkosc, kadencja, wysokosc z barometru, wiatr czolowy (QExt2 getHeadwindSignedMps),
 * nawierzchnia z profilu trasy QBota w biezacym miejscu (bez trasy: nieznana), temperatura; parametry z /ride-readiness
 * "aeroModel" (zapas: waga z Setup + 9 kg, Crr 0,007).
 * Wynik `out` -> pole dynamiczne KOKPIT 2 (SETUP AERO) i 8 pol FIT qext2_aero_* (docs/KONTRAKT_DANYCH.md).
 */
object AeroEstimator {
    data class Out(val state: Int = 0, val dW: Int = 0, val conf: Int = 0, val cdaNow: Float = 0f, val cdaRef: Float = 0f,
                   val windHead: Float = 0f, val surface: Int = 0)

    @Volatile var out = Out()
        private set

    private val core = AeroCore()
    private var lastT = 0L
    private var paramsT = 0L
    @Volatile private var modelJson: String? = null

    /** Model z /ride-readiness (JSON "aeroModel"); null / zly -> zapas. Wolane przy starcie (z pamieci) i po pobraniu. */
    @Synchronized fun setModel(json: String?) { modelJson = json; paramsT = 0L }

    /** wersja modelu QBota albo null (zapas) -- do dziennika */
    fun modelVersion(): String? = AeroParams.fromJson(modelJson)?.version

    @Synchronized fun reset() { core.reset(); lastT = 0L; out = Out() }

    private fun surfaceNow(agg: RideDataAggregator?): Int = try {
        val pos = agg?.getRoutePositionM()
        val st = if (agg == null || pos == null) null else agg.navSurfaceAt((pos / 1000.0).toFloat())
        when (st) { SurfaceType.PAVED -> 1; SurfaceType.GRAVEL, SurfaceType.LOOSE -> 2; null -> 0 }
    } catch (_: Exception) { 0 }

    /** Wolane co ~1 s z petli okien jazdy (QExt2PrimaryExtension). */
    @Synchronized fun feed(now: Long, p: PrimaryRideSnapshot, agg: RideDataAggregator?) {
        if (now - lastT < 900L) return
        lastT = now
        if (now - paramsT > 60_000L) {
            core.params = AeroParams.fromJson(modelJson) ?: AeroParams.fallback(AthleteDataStore.load().bodyWeightKg.toDouble())
            paramsT = now
        }
        val fresh = p.powerFreshnessMs < 3_000L && p.speedFreshnessMs < 3_000L && p.cadenceFreshnessMs < 5_000L
        val o = core.step(AeroCore.In(
            tMs = now, powerW = p.power3s.toDouble(), speedMps = p.speedKmh / 3.6, cadence = p.cadence,
            altM = try { agg?.getAltitudeM() } catch (_: Exception) { null },
            gradePct = try { agg?.getEffectiveGrade() } catch (_: Exception) { null } ?: p.gradePercent,
            headwindMps = try { agg?.getHeadwindSignedMps()?.toDouble() } catch (_: Exception) { null },
            headingDeg = try { agg?.getHeadingDeg() } catch (_: Exception) { null },
            surface = surfaceNow(agg),
            tempC = try { agg?.getKarooTemperatureC()?.toDouble() } catch (_: Exception) { null },
            fresh = fresh,
        ))
        o.event?.let { e ->
            com.qext2.primary.util.RideFileLog.append(if (e.startsWith("step")) "AERO_STEP v3 $e surf=${o.surface} conf=${o.conf}"
                else "AERO_RESET $e")
        }
        out = Out(o.state, o.dW, o.conf, o.cdaNow, o.cdaRef, o.windHead, o.surface)
    }

    /** tekst wartosci do pola: "—" brak, "···" zbiera, "−11" / "+6" / "0" W */
    fun valueText(o: Out = out): String = when (o.state) {
        0 -> "—"
        1 -> "···"
        else -> when { o.dW < 0 -> "−" + (-o.dW); o.dW > 0 -> "+" + o.dW; else -> "0" }
    }
}
