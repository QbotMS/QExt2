package com.qext2.primary.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * AERO v3 „POZYCJA” (2026-10-19) — czysta logika bez Androida (ten sam kod na Karoo, w testach i w odtwarzaniu jazd).
 * Dok.: docs/AERO.md (rozdz. v3). Zadanie: QBot docs/QEXT2_AERO_V3_TASK.md.
 *
 * Cel (decyzja Michala 19.10): pokazac w W zmiane oporu, na ktory Michal ma wplyw (pozycja), z odjetym wiatrem i droga.
 * Co sekunde bilans energii (sumy, wiec na odcinku liczy sie roznica wysokosci i predkosci na koncach, nie chwilowe nachylenie):
 *   Pa = P*eta - m*g*dh/dt - 1/2*m*d(v^2)/dt - m*g*Crr[nawierzchnia]*v      (moc na powietrze)
 *   X  = 1/2*rho*va*|va|*v,  va = v + wiatr_czolowy                         (CdA = sum Pa / sum X)
 * „Teraz” = ostatnie RECENT s waznej jazdy, porownane z poprzednimi (do REF s). Zmiana utrzymana PERSIST s -> nowe odniesienie.
 * Wynik dW = 1/2*rho*(CdA_teraz - CdA_odn)*va^2*v przy biezacej predkosci (minus = zysk).
 * Zmiana klasy nawierzchni -> od nowa (to nie pozycja). Szuter / nawierzchnia nieznana / brak parametrow QBota -> pewnosc niska.
 */
class AeroCore(var params: AeroParams = AeroParams.FALLBACK, val stepRel: Double = STEP_REL, val persist: Int = PERSIST) {

    /** surface: 0 nieznana (bez trasy), 1 asfalt, 2 szuter; headwindMps: skladowa czolowa (+ w twarz) PRZED skalowaniem,
     *  null = brak wiatru; headingDeg: kurs GPS (tylko do resetu, gdy wiatr nieznany). */
    data class In(
        val tMs: Long, val powerW: Double, val speedMps: Double, val cadence: Int,
        val altM: Double?, val gradePct: Double?, val headwindMps: Double?, val headingDeg: Double?,
        val surface: Int, val tempC: Double?, val fresh: Boolean = true,
    )

    /** state 0 brak warunkow, 1 zbiera, 2 wynik; conf 0 brak, 1 niska, 2 dobra; surface jak w In. */
    data class Out(
        val state: Int = 0, val dW: Int = 0, val conf: Int = 0, val cdaNow: Float = 0f, val cdaRef: Float = 0f,
        val windHead: Float = 0f, val surface: Int = 0, val event: String? = null,
    )

    companion object {
        const val VERSION = 4           // 4 (19.10): wiatr x windScale z QBota (aeroModel), domyslnie 0,3
        const val G = 9.81
        const val VMIN = 5.0            // m/s (18 km/h)
        const val PMIN = 60.0
        const val CAD_MIN = 50
        const val PEDAL_N = 5           // s -- tyle ostatnich sekund z pedalowaniem (bez toczenia sie / hamowania)
        const val GRADE_MAX = 4.0       // % na ostatnich ~10 s (z wysokosci)
        const val AMAX = 1.5            // m/s2 -- tylko bledy czujnika (przyspieszanie liczy bilans energii)
        const val RECENT = 30           // s -- „teraz”
        const val REF = 60              // s -- odniesienie
        const val PERSIST = 8           // s -- zmiana musi sie utrzymac
        const val STEP_REL = 0.10       // zmiana CdA >= 10 % ... (szum 30 s na jazdach 16/18.10: rozstep cwiartkowy ~22 %)
        const val STEP_W = 6.0          // ... i >= 6 W przy biezacej predkosci
        const val GAP_RESET_S = 30      // s bez waznej jazdy -> od nowa
        const val HEAD_RESET_DEG = 35.0 // tylko gdy wiatr nieznany
    }

    private class S(val pa: Double, val x: Double, val surf: Int)

    private val level = ArrayDeque<S>()
    private val pedal = ArrayDeque<Boolean>()
    private val altHist = ArrayDeque<Pair<Double, Double>>()   // (dystans m, wysokosc wygladzona) z ostatnich ~10 s
    private var ref: Double? = null
    private var refSurf = -1
    private var lastT = 0L
    private var lastV = -1.0
    private var lastAlt: Double? = null
    private var dist = 0.0
    private var invalidN = 0
    private var heading0: Double? = null
    private var pend = 0
    var out = Out(); private set

    fun reset() {
        buf.clear(); level.clear(); pedal.clear(); altHist.clear(); ref = null; refSurf = -1; lastT = 0L; lastV = -1.0
        lastAlt = null; dist = 0.0; invalidN = 0; heading0 = null; pend = 0; out = Out()
    }

    private fun restart(): String? {
        val had = level.isNotEmpty() || ref != null
        level.clear(); ref = null; refSurf = -1; pend = 0; heading0 = null
        return if (had) "reset" else null
    }

    private fun angle(a: Double, b: Double): Double { val d = abs(a - b) % 360.0; return if (d > 180.0) 360.0 - d else d }

    private fun cda(from: Int, to: Int): Double {
        var pa = 0.0; var x = 0.0
        for (i in from until to) { pa += level[i].pa; x += level[i].x }
        return if (x > 0.0) pa / x else 0.0
    }

    private fun crr(surf: Int): Double = when (surf) { 1 -> params.crrAsphalt; 2 -> params.crrGravel; else -> params.crrAsphalt }

    /** Opoznienie przetwarzania o LAG s: wysokosc srednia WYSRODKOWANA (i-2..i+2) i moc 3 s wysrodkowana (z chwili i+1) --
     *  inaczej srednie kroczace spozniaja sie wzgledem predkosci i na pagorkach bilans energii sie rozjezdza. */
    private val buf = ArrayDeque<In>()
    private val LAG = 2
    private val ALT_HALF = 0      // wysokosc surowa (Karoo juz wygladza; srednia 3-5 s psula bilans na zalamaniach profilu)

    fun step(raw: In): Out {
        buf.addLast(raw); while (buf.size > 2 * LAG + 1) buf.removeFirst()
        if (buf.size < 2 * LAG + 1) { out = out.copy(state = if (out.state == 2) 2 else 0); return out }
        val i0 = buf[LAG]
        val alts = (LAG - ALT_HALF..LAG + ALT_HALF).mapNotNull { buf[it].altM }
        val i = i0.copy(powerW = buf[LAG + 1].powerW, altM = if (alts.size == 2 * ALT_HALF + 1 && i0.altM != null) alts.average() else i0.altM)
        return process(i)
    }

    private fun process(i: In): Out {
        val dt = if (lastT == 0L) 1.0 else ((i.tMs - lastT) / 1000.0).coerceIn(0.5, 5.0)
        lastT = i.tMs
        val v = if (i.fresh) i.speedMps else 0.0
        val h = i.altM
        val vPrev = lastV
        val hPrev = lastAlt
        lastV = if (i.fresh) v else -1.0
        lastAlt = h
        dist += max(0.0, v) * dt
        if (h != null) { altHist.addLast(dist to h); while (altHist.size > 10) altHist.removeFirst() }
        pedal.addLast(i.fresh && i.cadence >= CAD_MIN && i.powerW >= PMIN); while (pedal.size > PEDAL_N) pedal.removeFirst()

        val a = if (vPrev < 0.0) 0.0 else (v - vPrev) / dt
        val gradeWin = if (altHist.size >= 5 && altHist.last().first - altHist.first().first > 20.0)
            100.0 * (altHist.last().second - altHist.first().second) / (altHist.last().first - altHist.first().first)
        else i.gradePct ?: 0.0
        val valid = i.fresh && vPrev >= 0.0 && v >= VMIN && pedal.size >= PEDAL_N && pedal.all { it } &&
            abs(a) <= AMAX && abs(gradeWin) <= GRADE_MAX
        val windKnown = i.headwindMps != null
        val head = (i.headwindMps ?: 0.0) * params.windFactor
        var ev: String? = null
        if (!valid) {
            invalidN++
            if (invalidN > GAP_RESET_S) ev = restart()?.let { "przerwa" }
            out = out.copy(state = 0, event = ev, windHead = head.toFloat(), surface = i.surface)
            return out
        }
        invalidN = 0
        // nawierzchnia: inna klasa niz w odniesieniu/poziomie -> od nowa (to nie pozycja)
        val curSurf = level.lastOrNull()?.surf ?: refSurf
        if (curSurf >= 0 && curSurf != i.surface) ev = restart()?.let { "nawierzchnia" }
        // wiatr nieznany: po zmianie kierunku nie da sie porownac -> od nowa (jak v2)
        if (!windKnown && i.headingDeg != null) {
            val h0 = heading0
            if (h0 == null) heading0 = i.headingDeg
            else if (angle(i.headingDeg, h0) > HEAD_RESET_DEG) { ev = restart()?.let { "kierunek" } ?: ev; heading0 = i.headingDeg }
        }
        val tc = i.tempC?.takeIf { it in -30.0..50.0 } ?: 12.0
        val rho = 1.225 * 288.15 / (tc + 273.15)
        val m = params.massKg
        val vm = 0.5 * (v + vPrev)
        val dh = if (h != null && hPrev != null) h - hPrev else (i.gradePct ?: 0.0) / 100.0 * vm * dt
        val va = vm + head
        val pa = i.powerW * params.eta - m * G * dh / dt - 0.5 * m * (v * v - vPrev * vPrev) / dt - m * G * crr(i.surface) * vm
        val x = 0.5 * rho * va * abs(va) * vm
        level.addLast(S(pa, x, i.surface))
        while (level.size > RECENT + REF) level.removeFirst()
        val n = level.size
        val vaNow = v + head
        val wFactor = 0.5 * rho * vaNow * abs(vaNow) * v   // W na 1 m2 CdA przy biezacej predkosci

        if (n >= RECENT + REF / 2) {
            val cOld = cda(0, n - RECENT)
            val cRec = cda(n - RECENT, n)
            val d = cRec - cOld
            val big = cOld > 0.05 && abs(d) >= stepRel * cOld && abs(d) * wFactor >= STEP_W
            pend = if (big) pend + 1 else 0
            if (pend >= persist) {
                ref = cOld; refSurf = i.surface
                repeat(n - RECENT) { level.removeFirst() }
                pend = 0
                ev = String.format(java.util.Locale.US, "step ref=%.3f new=%.3f v=%.1f d=%+.0fW", cOld, cRec, v * 3.6, d * wFactor)
            }
        }
        val n2 = level.size
        val cNow = cda((n2 - RECENT).coerceAtLeast(0), n2)
        val r = ref
        val good = params.fromQbot && i.surface == 1 && windKnown
        val conf = if (good) 2 else 1
        out = when {
            r == null && n2 < RECENT -> Out(1, 0, 0, cNow.toFloat(), 0f, head.toFloat(), i.surface, ev)
            r == null -> Out(2, 0, conf, cNow.toFloat(), cNow.toFloat(), head.toFloat(), i.surface, ev)
            else -> Out(2, ((cNow - r) * wFactor).roundToInt(), if (n2 >= 20) conf else 1, cNow.toFloat(), r.toFloat(),
                head.toFloat(), i.surface, ev)
        }
        return out
    }
}

/** Parametry modelu z QBota (/ride-readiness "aeroModel", fitmodel/cda.py karoo_aero_model). */
data class AeroParams(
    val massKg: Double, val eta: Double, val crrAsphalt: Double, val crrGravel: Double,
    val windFactor: Double, val fromQbot: Boolean, val version: String,
) {
    companion object {
        /** wiatr z prognozy Open-Meteo (10 m) -> przy rowerzyscie. QBot (QEXT2_AERO_V3_TASK.md „windScale”, 19.10): mnoznik k z jazd
         *  (ten sam model co Centrum CdA), mediana 0,25; aeroModel.windScale; brak pola / stary pakiet -> 0,3 (domyslny QBota). */
        const val WIND_FACTOR = 0.3
        /** zapas bez QBota = stale jak w v1/v2 (waga z Setup + 9 kg, Crr 0,007) */
        val FALLBACK = AeroParams(100.0 + 9.0, 0.976, 0.007, 0.007, WIND_FACTOR, false, "-")
        fun fallback(bodyKg: Double?): AeroParams =
            FALLBACK.copy(massKg = (bodyKg?.takeIf { it in 40.0..200.0 } ?: 100.0) + 9.0)

        /** aeroModel.windScale (QBot, od 19.10); starsze pole windFactor; brak / poza 0..1,2 -> 0,3 */
        fun windScale(o: org.json.JSONObject): Double {
            val ws = o.optDouble("windScale", Double.NaN)
            if (!ws.isNaN() && ws in 0.0..1.2) return ws
            val wf = o.optDouble("windFactor", Double.NaN)
            return if (!wf.isNaN() && wf in 0.0..1.2) wf else WIND_FACTOR
        }

        /** null = zly / pusty JSON */
        fun fromJson(json: String?): AeroParams? = try {
            val o = org.json.JSONObject(json ?: "")
            val rider = o.optDouble("riderKg", Double.NaN)
            val extra = o.optDouble("extraKg", 14.0)
            val crr = o.optJSONObject("crr")
            val ca = crr?.optDouble("asphalt", Double.NaN) ?: Double.NaN
            val cg = crr?.optDouble("gravel", Double.NaN) ?: Double.NaN
            if (rider.isNaN() || rider !in 40.0..200.0 || ca.isNaN() || cg.isNaN() || ca !in 0.002..0.03 || cg !in 0.002..0.04) null
            else AeroParams(rider + extra, o.optDouble("eta", 0.976).takeIf { it in 0.85..1.0 } ?: 0.976, ca, cg,
                windScale(o), true,
                o.optString("version", "?") + "/" + o.optString("updatedAt", ""))
        } catch (e: Exception) { null }
    }
}
