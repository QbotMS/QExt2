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
    private val disp = AeroDisplay()   // 21.10 (v5): spokojny ekran, co 5 s, do 5 W
    private var lastRefLog: Double? = null

    /** Widok na Karoo (KOKPIT 2): odswiezany co 5 s, zaokraglony do 5 W. FIT dalej z `out` (surowe dW co sekunde). */
    @Volatile var shown = Out()
        private set
    private var lastT = 0L
    private var paramsT = 0L
    @Volatile private var modelJson: String? = null

    /** Model z /ride-readiness (JSON "aeroModel"); null / zly -> zapas. Wolane przy starcie (z pamieci) i po pobraniu. */
    @Synchronized fun setModel(json: String?) { modelJson = json; paramsT = 0L }

    /** wersja modelu QBota albo null (zapas) -- do dziennika */
    fun modelVersion(): String? = AeroParams.fromJson(modelJson)?.version

    @Synchronized fun reset() { core.reset(); disp.reset(); lastT = 0L; out = Out(); shown = Out(); lastRefLog = null }

    /** v5: stale odniesienie = CdA wykrytego roweru z Centrum CdA (aeroModel.cdaByBike). Brak QBota / roweru / wartosci
     *  -> null (stary tryb, odniesienie z jazdy). Rower jest znany po ~60 s jazdy (BikeDetector). */
    private fun refFor(agg: RideDataAggregator?): Double? {
        val p = core.params
        if (!p.fromQbot) return null
        val name = when (try { agg?.detectedBike() } catch (_: Exception) { null }) {
            BikeDetector.Bike.GRAIL -> "Grail"; BikeDetector.Bike.GRIZL -> "Grizl"; BikeDetector.Bike.MONSTER -> "Monster"
            else -> null
        }
        return name?.let { p.cdaByBike[it] }
    }

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
        val fr = refFor(agg)
        core.fixedRef = fr
        if (fr != lastRefLog) {
            lastRefLog = fr
            com.qext2.primary.util.RideFileLog.append("AERO_REF " + (if (fr == null) "brak (odniesienie z jazdy)" else
                String.format(java.util.Locale.US, "bike=%s cda=%.3f", agg?.detectedBike(), fr)))
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
        val s = disp.feed(o, now)
        shown = Out(s.state, s.dW, s.conf, s.cdaNow, s.cdaRef, s.windHead, s.surface)
    }

    /** tekst wartosci do pola: "—" brak, "···" zbiera, "−11" / "+6" / "0" W */
    fun valueText(o: Out = out): String = when (o.state) {
        0 -> "—"
        1 -> "···"
        else -> when { o.dW < 0 -> "−" + (-o.dW); o.dW > 0 -> "+" + o.dW; else -> "0" }
    }
}
