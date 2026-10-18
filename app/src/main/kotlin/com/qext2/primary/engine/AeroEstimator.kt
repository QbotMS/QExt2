package com.qext2.primary.engine

import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.model.PrimaryRideSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AERO v2 (2026-10-18) - zmiana OPORU (powietrze, nawierzchnia, jazda za kims) w trakcie jazdy, bez oznaczania pozycji.
 * v2: tylko rowna jazda (kadencja +-8, moc +-20% w 20 s), ostatnie 30 s vs poprzednie 60 s, prog 0,10 CdA;
 *     restart po zmianie kierunku tylko przy wietrze >= 3 m/s. Sprawdzone na jezdzie 18.10 (2 zmiany chwytu na km 10-12).
 * Dok.: docs/AERO.md
 *
 * Co sekunde bilans mocy: moc x sprawnosc = toczenie + nachylenie + przyspieszanie + POWIETRZE.
 * Z "powietrza" i predkosci: CdA (Pa / (0.5 rho v^3)). Liczone jako stosunek SUM (odporne na szum pojedynczej sekundy).
 *
 * Poziom = ciag waznych sekund w tym samym kierunku jazdy. Gdy ostatnie 12 s odbiega od wczesniejszej czesci poziomu
 * o wiecej niz szum (i min. 0,02 CdA) przez 5 kolejnych sekund -> ZMIANA: poprzedni poziom staje sie odniesieniem.
 * Wynik = ile watow mniej/wiecej potrzeba przy BIEZACEJ predkosci: 0.5 rho (CdA_teraz - CdA_odn) v^3.
 * Porownanie tylko w obrebie jednego kierunku i bez dlugiej przerwy (wiatr i nawierzchnia wtedy sie znosza).
 *
 * Stan: 0 = brak wyniku (warunki nie pozwalaja: podjazd > 4%, hamowanie, < 18 km/h, < 60 W, kolka),
 *       1 = zbiera poziom, 2 = wynik (dW, pewnosc 0/1).
 */
object AeroEstimator {
    data class Out(val state: Int = 0, val dW: Int = 0, val conf: Int = 0, val cdaNow: Float = 0f, val cdaRef: Float = 0f)

    @Volatile var out = Out()
        private set

    private const val ETA = 0.976          // sprawnosc napedu
    private const val G = 9.81
    private const val CRR = 0.007          // stale: zmiane nawierzchni AERO tez ma lapac (to tez zmiana oporu)
    private const val BIKE_KG = 9.0
    private const val VMIN = 5.0           // m/s (18 km/h)
    private const val PMIN = 60
    private const val GMAX = 4.0           // %
    private const val AMAX = 0.4           // m/s2
    private const val CAD_MIN = 50
    // 2026-10-18 v2 (decyzja Michala, sprawdzone na jezdzie 18.10, km 10-12): liczymy tylko sekundy ROWNEJ jazdy
    // (kadencja w ostatnich 20 s +-8 obr/min, moc 3 s w granicach +-20% sredniej z 20 s) i porownujemy ostatnie 30 s z poprzednimi 60 s.
    private const val STEADY_N = 20
    private const val CAD_TOL = 8
    private const val PWR_TOL = 0.20
    private const val RECENT = 30          // s - "teraz"
    private const val REF = 60             // s - odniesienie przed "teraz"
    private const val PERSIST = 5          // s - zmiana musi sie utrzymac
    private const val STEP_MIN = 0.10      // CdA - zmiana oporu do zgloszenia
    private const val GAP_RESET_S = 30     // s nierownej jazdy / przerwy -> od nowa
    private const val HEAD_RESET_DEG = 35.0
    private const val WIND_HEAD_MPS = 3.0  // restart po zmianie kierunku tylko przy wietrze >= 3 m/s

    private class S(val pa: Double, val x: Double)

    private val level = ArrayDeque<S>()
    private val cadW = ArrayDeque<Int>()
    private val pwrW = ArrayDeque<Int>()
    private var ref: Double? = null
    private var lastT = 0L
    private var lastV = -1.0
    private var invalidN = 0
    private var heading0: Double? = null
    private var pend = 0
    private var massKg = 0.0
    private var massT = 0L

    @Synchronized fun reset() {
        level.clear(); cadW.clear(); pwrW.clear(); ref = null; lastT = 0L; lastV = -1.0; invalidN = 0; heading0 = null; pend = 0; out = Out()
    }

    private fun restart(reason: String) {
        if (level.isNotEmpty() || ref != null) com.qext2.primary.util.RideFileLog.append("AERO_RESET $reason")
        level.clear(); ref = null; pend = 0; heading0 = null
    }

    private fun angle(a: Double, b: Double): Double { val d = abs(a - b) % 360.0; return if (d > 180.0) 360.0 - d else d }

    private fun cda(from: Int, to: Int): Double {
        var pa = 0.0; var x = 0.0
        for (i in from until to) { pa += level[i].pa; x += level[i].x }
        return if (x > 0.0) pa / x else 0.0
    }

    /** Wolane co ~1 s z petli okien jazdy (QExt2PrimaryExtension). */
    @Synchronized fun feed(now: Long, p: PrimaryRideSnapshot, agg: RideDataAggregator?) {
        if (now - lastT < 900L) return
        val dt = if (lastT == 0L) 1.0 else ((now - lastT) / 1000.0).coerceIn(0.5, 5.0)
        lastT = now
        val speedOk = p.speedFreshnessMs < 3_000L
        val v = p.speedKmh / 3.6
        val a = if (lastV < 0.0 || !speedOk) 0.0 else (v - lastV) / dt
        lastV = if (speedOk) v else -1.0
        if (now - massT > 60_000L || massKg <= 0.0) {
            val w = AthleteDataStore.load().bodyWeightKg.toDouble()
            massKg = (if (w in 40.0..200.0) w else 90.0) + BIKE_KG; massT = now
        }
        // okno rownosci: kadencja i moc z ostatnich 20 s (kazda sekunda, takze nierowna)
        cadW.addLast(p.cadence); while (cadW.size > STEADY_N) cadW.removeFirst()
        pwrW.addLast(p.power3s); while (pwrW.size > STEADY_N) pwrW.removeFirst()
        val grade = try { agg?.getEffectiveGrade() } catch (_: Exception) { null } ?: p.gradePercent
        val base = p.powerFreshnessMs < 3_000L && speedOk && p.cadenceFreshnessMs < 5_000L && p.gradeFreshnessMs < 15_000L &&
            v >= VMIN && p.power3s >= PMIN && p.cadence >= CAD_MIN && abs(grade) <= GMAX && abs(a) <= AMAX
        val steady = base && cadW.size >= STEADY_N && run {
            val cs = cadW.sorted(); val cm = cs[cs.size / 2]
            val pm = pwrW.average()
            cadW.all { abs(it - cm) <= CAD_TOL } && pm > 0.0 && abs(p.power3s - pm) <= PWR_TOL * pm
        }
        if (!steady) {
            invalidN++
            if (invalidN > GAP_RESET_S) restart("przerwa")
            out = out.copy(state = 0)
            return
        }
        invalidN = 0
        val hd = try { agg?.getHeadingDeg() } catch (_: Exception) { null }
        val windMs = try { agg?.getWindNow()?.mps?.toDouble() } catch (_: Exception) { null } ?: 0.0
        if (hd != null) {
            val h0 = heading0
            if (h0 == null) heading0 = hd
            else if (windMs >= WIND_HEAD_MPS && angle(hd, h0) > HEAD_RESET_DEG) { restart("kierunek"); heading0 = hd }
            else if (windMs < WIND_HEAD_MPS) heading0 = hd
        }
        val tc = (try { agg?.getKarooTemperatureC() } catch (_: Exception) { null })?.toDouble()?.takeIf { it in -30.0..50.0 } ?: 12.0
        val rho = 1.225 * 288.15 / (tc + 273.15)
        val pa = p.power3s * ETA - massKg * G * v * (grade / 100.0 + CRR) - massKg * v * a
        val x = 0.5 * rho * v * v * v
        level.addLast(S(pa, x))
        while (level.size > RECENT + REF) level.removeFirst()
        val n = level.size

        // szukanie zmiany: ostatnie RECENT s vs wczesniejsza czesc (do REF s)
        if (n >= RECENT + REF / 2) {
            val cOld = cda(0, n - RECENT)
            val cRec = cda(n - RECENT, n)
            pend = if (abs(cRec - cOld) >= STEP_MIN) pend + 1 else 0
            if (pend >= PERSIST) {
                ref = cOld
                repeat(n - RECENT) { level.removeFirst() }
                pend = 0
                com.qext2.primary.util.RideFileLog.append(String.format(java.util.Locale.US,
                    "AERO_STEP ref=%.3f new=%.3f v=%.1f d=%+.0fW", cOld, cRec, v * 3.6, 0.5 * rho * (cRec - cOld) * v * v * v))
            }
        }
        val n2 = level.size
        val cNow = cda((n2 - RECENT).coerceAtLeast(0), n2)
        val r = ref
        out = when {
            r == null && n2 < RECENT -> Out(1, 0, 0, cNow.toFloat(), 0f)
            r == null -> Out(2, 0, 1, cNow.toFloat(), cNow.toFloat())
            else -> Out(2, (0.5 * rho * (cNow - r) * v * v * v).roundToInt(), if (n2 >= 20) 1 else 0, cNow.toFloat(), r.toFloat())
        }
    }

    /** tekst wartosci do pola: "—" brak, "···" zbiera, "−11" / "+6" / "0" W */
    fun valueText(o: Out = out): String = when (o.state) {
        0 -> "—"
        1 -> "···"
        else -> when { o.dW < 0 -> "−" + (-o.dW); o.dW > 0 -> "+" + o.dW; else -> "0" }
    }
}
