package com.qext2.primary.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * RSRV v2 -- zapas na jazde w stylu Garmin Stamina, tylko z czujnikow (QBot DECISIONS 2026-10-08).
 *
 * WZORZEC: QBot fitmodel/rsrv_v2.py -- te same stale, ta sama kolejnosc dzialan, te same przypadki testowe
 * (ReserveModelV2Test <-> tests/test_rsrv_v2.py). Zmiana tutaj = zmiana tam.
 *
 * - start zawsze 100 %; zapas tylko maleje (bez odbudowy na postoju, bez podlogi);
 * - co sekunde jazdy ubywa 1 / T(x), T(x) = czas do wyczerpania przy x = moc / CP (krzywa moc-czas);
 * - x z mocy usrednionej EMA 20 min (krotkie zrywy liczy W'bal);
 * - tetno (jak Garmin) moze x tylko PODBIC, gdy organizm pracuje wyraznie ciezej niz zwykle przy tej mocy:
 *   x_hr = HR_A + HR_B * (HR - RHR) / (LTHR - RHR), powyzej progu szumu HR_DEADBAND, tylko przy pedalowaniu;
 * - bez: XSS, formy dnia, cisnienia, dryfu, odbudowy na postoju, wpisow recznych.
 */
object ReserveModelV2 {
    const val TAU_S = 1200.0
    const val HR_A = 0.087
    const val HR_B = 0.683
    const val RHR_BPM = 47.0
    const val HR_DEADBAND = 0.05
    const val HR_MIN_SAMPLES = 300L
    const val HR_MIN_VALID = 60
    const val MIN_PEDAL_FRACTION = 0.3
    /** Brak swiezej mocy w ruchu dluzej niz tyle -> RSRV nieznany ("czekam"), a nie zmyslony. */
    const val NO_POWER_LIMIT_S = 300L

    private val CURVE_X = doubleArrayOf(0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.2)
    private val CURVE_T_H = doubleArrayOf(40.0, 22.0, 13.0, 8.0, 4.0, 2.0, 1.0, 0.33)

    class State {
        var emaP = 0.0
        var emaH = 0.0
        var nP = 0L
        var nH = 0L
        var load = 0.0
        var noPowerMovingS = 0L
    }

    /** Ulamek zapasu zuzywany w 1 s przy intensywnosci x (= moc / CP). */
    fun ratePerSecond(x: Double): Double {
        if (x <= 0.0) return 0.0
        val tH = when {
            x <= CURVE_X[0] -> CURVE_T_H[0] * (CURVE_X[0] / x).pow(4.6)
            x >= CURVE_X[CURVE_X.size - 1] -> CURVE_T_H[CURVE_T_H.size - 1]
            else -> {
                var j = 1
                while (j < CURVE_X.size && CURVE_X[j] <= x) j++
                val x0 = CURVE_X[j - 1]; val x1 = CURVE_X[j]
                val t0 = CURVE_T_H[j - 1]; val t1 = CURVE_T_H[j]
                exp(ln(t0) + (ln(t1) - ln(t0)) * (x - x0) / (x1 - x0))
            }
        }
        return 1.0 / (tH * 3600.0)
    }

    /** Jedna sekunda jazdy (ruch + swieza moc; 0 W na zjezdzie tez). Na postoju nie wolac. */
    fun tick(s: State, powerW: Int, hrBpm: Int, cpW: Double, lthrBpm: Double, hrMax: Int, dtS: Double = 1.0) {
        if (cpW <= 0.0) return
        s.nP++
        val p = powerW.coerceAtLeast(0).toDouble()
        s.emaP += (p - s.emaP) * (if (s.nP >= TAU_S) 1.0 / TAU_S else 1.0 / s.nP)
        if (hrBpm in HR_MIN_VALID..hrMax) {
            s.nH++
            s.emaH += (hrBpm - s.emaH) * (if (s.nH >= TAU_S) 1.0 / TAU_S else 1.0 / s.nH)
        }
        var x = s.emaP / cpW
        if (s.nH >= HR_MIN_SAMPLES && s.emaP > MIN_PEDAL_FRACTION * cpW && lthrBpm > RHR_BPM + 10.0) {
            val xHr = HR_A + HR_B * (s.emaH - RHR_BPM) / (lthrBpm - RHR_BPM)
            if (xHr - HR_DEADBAND > x) x = xHr - HR_DEADBAND
        }
        s.load += ratePerSecond(x) * dtS.coerceAtLeast(0.0)
    }

    /** RSRV w % z dziennej bazy (wczesniejsze jazdy dzis) i biezacej jazdy. */
    fun percent(dailyBaseLoad: Double, sessionLoad: Double): Int {
        val v = 100.0 * (1.0 - dailyBaseLoad.coerceAtLeast(0.0) - sessionLoad.coerceAtLeast(0.0))
        return v.roundToInt().coerceIn(0, 100)
    }
}
