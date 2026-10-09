package com.qext2.primary.eta

import com.qext2.primary.model.SurfaceType
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/*
 * ETA v2 (plan: docs/ETA_V2_PLAN.md).
 * ETA = godzina przyjazdu NA MIEJSCE (jazda + krotkie postoje).
 *  Poziom 2: plan czasu z profilu wysokosci Karoo + tabela predkosci QBota + nawierzchnia (QBot/RouteGraph),
 *            korygowany "jak jedziesz dzis" (tylko czas ruchu).
 *  Poziom 3: dwie srednie predkosci (szybka 5 min, wolna 1 h) startujace od typowej predkosci z poprzednich jazd.
 * Czysta logika (bez Androida) -> testowalna jednostkowo.
 */

/** Tabela predkosci QBota (qbot_route_time_tools.SPEED_TABLE, tryb "normalny"), km/h. */
object EtaSpeedTable {
    val GRADE_EDGES = doubleArrayOf(-8.0, -6.0, -4.0, -2.0, -1.0, 1.0, 2.0, 4.0, 6.0, 8.0)
    val PAVED = doubleArrayOf(20.0, 20.0, 30.6, 25.5, 23.7, 22.5, 20.6, 17.2, 12.4, 9.3, 7.2)
    val UNPAVED = doubleArrayOf(21.8, 22.7, 21.3, 19.4, 19.4, 18.8, 16.7, 14.2, 11.8, 9.3, 7.2)
    const val MICRO_MIN_PER_KM = 0.22
    const val SHORT_BREAK_EVERY_KM = 9.0
    const val SHORT_BREAK_MIN = 4.5

    fun bin(gradePct: Double): Int {
        for (j in GRADE_EDGES.indices) if (gradePct < GRADE_EDGES[j]) return j
        return GRADE_EDGES.size
    }

    /** Nieznana nawierzchnia -> srednia asfalt/szuter (jak QBot, bez biasu). */
    fun speedKmh(gradePct: Double, surface: SurfaceType?): Double {
        val b = bin(gradePct)
        return when (surface) {
            SurfaceType.PAVED -> PAVED[b]
            SurfaceType.GRAVEL, SurfaceType.LOOSE -> UNPAVED[b]
            null -> (PAVED[b] + UNPAVED[b]) / 2.0
        }
    }

    /** Krotkie postoje (mikro + przerwy) na danym dystansie, w sekundach. Ciagle, bez skokow. */
    fun shortStopsSec(km: Double): Double =
        (MICRO_MIN_PER_KM * km + (km / SHORT_BREAK_EVERY_KM) * SHORT_BREAK_MIN) * 60.0
}

/** Dekoder Google encoded polyline; profil wysokosci Karoo: precyzja 1, pary (dystans_m, wysokosc_m). */
object ElevationPolyline {
    fun decode(encoded: String, factor: Double = 10.0): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var index = 0
        var a = 0L
        var b = 0L
        val len = encoded.length
        fun next(): Long? {
            var result = 0L
            var shift = 0
            var byte: Int
            do {
                if (index >= len) return null
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1f).toLong() shl shift)
                shift += 5
            } while (byte >= 0x20)
            return if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
        }
        while (index < len) {
            val da = next() ?: break
            val db = next() ?: break
            a += da
            b += db
            out.add(a / factor to b / factor)
        }
        return out
    }
}

/** Plan czasu jazdy: skumulowany czas ruchu [s] na granicach odcinkow co stepM. */
class EtaPlan(val stepM: Double, val lengthM: Double, val cumSec: DoubleArray, val grades: DoubleArray = DoubleArray(0)) {
    val totalSec: Double get() = cumSec.last()

    fun timeAt(posM: Double): Double {
        val p = posM.coerceIn(0.0, lengthM)
        val i = (p / stepM).toInt().coerceIn(0, cumSec.size - 2)
        val segStart = i * stepM
        val segEnd = min((i + 1) * stepM, lengthM)
        val f = if (segEnd > segStart) ((p - segStart) / (segEnd - segStart)).coerceIn(0.0, 1.0) else 1.0
        return cumSec[i] + f * (cumSec[i + 1] - cumSec[i])
    }
}

object EtaPlanner {
    data class Result(
        val plan: EtaPlan?,
        val points: Int,
        val lengthM: Double,
        val avgSpacingM: Double,
        val reason: String,
    )

    const val STEP_M = 100.0
    const val GRADE_HALF_WINDOW_M = 100.0   // okno 200 m, jak kalibracja tabeli w QBot
    const val MAX_AVG_SPACING_M = 400.0
    const val MAX_LENGTH_MISMATCH = 0.05

    fun build(
        rawPoints: List<Pair<Double, Double>>,
        routeDistanceM: Double?,
        surfaceAtKm: ((Double) -> SurfaceType?)?,
    ): Result {
        val pts = rawPoints
            .filter { it.first.isFinite() && it.second.isFinite() }
            .sortedBy { it.first }
            .let { list ->
                val dedup = ArrayList<Pair<Double, Double>>()
                for (p in list) {
                    if (dedup.isNotEmpty() && abs(dedup.last().first - p.first) < 0.5) dedup[dedup.size - 1] = p
                    else dedup.add(p)
                }
                dedup
            }
        if (pts.size < 2) return Result(null, pts.size, 0.0, 0.0, "too_few_points")
        val d0 = pts.first().first
        val length = pts.last().first - d0
        val spacing = length / (pts.size - 1)
        if (length < 500.0) return Result(null, pts.size, length, spacing, "too_short")
        if (spacing > MAX_AVG_SPACING_M) return Result(null, pts.size, length, spacing, "too_sparse")
        if (routeDistanceM != null && routeDistanceM > 0.0 &&
            abs(length - routeDistanceM) / routeDistanceM > MAX_LENGTH_MISMATCH
        ) return Result(null, pts.size, length, spacing, "length_mismatch")

        val xs = DoubleArray(pts.size) { pts[it].first - d0 }
        val es = DoubleArray(pts.size) { pts[it].second }
        fun elevAt(x: Double): Double {
            if (x <= xs[0]) return es[0]
            if (x >= xs[xs.size - 1]) return es[es.size - 1]
            var lo = 0
            var hi = xs.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (xs[mid] <= x) lo = mid else hi = mid
            }
            val span = xs[hi] - xs[lo]
            return if (span <= 0.0) es[lo] else es[lo] + (es[hi] - es[lo]) * (x - xs[lo]) / span
        }

        val n = kotlin.math.ceil(length / STEP_M).toInt().coerceAtLeast(1)
        val cum = DoubleArray(n + 1)
        val gr = DoubleArray(n)
        for (i in 0 until n) {
            val s = i * STEP_M
            val e = min((i + 1) * STEP_M, length)
            val segLen = e - s
            val mid = (s + e) / 2.0
            val a = max(0.0, mid - GRADE_HALF_WINDOW_M)
            val b = min(length, mid + GRADE_HALF_WINDOW_M)
            val grade = if (b > a) ((elevAt(b) - elevAt(a)) / (b - a) * 100.0).coerceIn(-30.0, 30.0) else 0.0
            gr[i] = grade
            val surface = surfaceAtKm?.invoke(mid / 1000.0)
            val v = EtaSpeedTable.speedKmh(grade, surface).coerceAtLeast(3.0)
            cum[i + 1] = cum[i] + segLen / (v / 3.6)
        }
        return Result(EtaPlan(STEP_M, length, cum, gr), pts.size, length, spacing, "ok")
    }
}

class EtaEngine(
    private val log: (String) -> Unit = {},
    private val loadPriorKmh: () -> Double = { 0.0 },
    private val savePriorKmh: (Double) -> Unit = {},
) {
    data class Output(
        val etaMs: Long,
        val level: Int,
        val remMovingSec: Double,
        val stopsSec: Double,
        val factor: Double,
    )

    companion object {
        const val LONG_STOP_SEC = 20 * 60.0
        const val TAU_FAST_SEC = 300.0
        const val TAU_SLOW_SEC = 3600.0
        const val DEFAULT_PRIOR_KMH = 18.0
        const val TRUST_PLAN_SEC = 900.0
        val CHECKPOINTS = doubleArrayOf(0.25, 0.5, 0.75)
    }

    // --- trasa / plan
    private var routeKey: Int? = null
    private var routePoints: List<Pair<Double, Double>> = emptyList()
    private var routeDistanceM: Double? = null
    private var plan: EtaPlan? = null
    private var planSurfaceKnown = false

    // --- przejazd
    private var lastTickMs = 0L
    private var lastRideDistanceM = 0.0
    private var rideStarted = false
    private var movingSec = 0.0
    private var movingDistM = 0.0
    private var curStopSec = 0.0
    private var shortStopSec = 0.0
    private var longStopSec = 0.0
    private var prevPos: Double? = null
    private var aF = 0.0; private var pF = 0.0; private var aS = 0.0; private var pS = 0.0
    private var vFast = Double.NaN; private var vSlow = Double.NaN
    private var lastPriorSaveMs = 0L
    private var lastPeriodicLogMs = 0L
    private val checkpointPreds = ArrayList<Triple<Double, Long, Long>>() // frac, nowe, stare
    private var arrivalLogged = false
    private val longStopsKm = ArrayList<Double>()   // postoje >= 10 min (km jazdy)

    @Synchronized
    fun setRoute(encoded: String?, routeDistance: Double?, surfaceKnown: Boolean, surfaceAtKm: ((Double) -> SurfaceType?)?) {
        if (encoded.isNullOrEmpty()) {
            if (routeKey != null) log("QEXT_ETA_PROFILE points=0 valid=false reason=no_elevation_polyline")
            routeKey = -1; routePoints = emptyList(); plan = null; prevPos = null
            return
        }
        val key = encoded.hashCode() * 31 + encoded.length
        if (key == routeKey && surfaceKnown == planSurfaceKnown) return
        val newRoute = key != routeKey
        routeKey = key
        routeDistanceM = routeDistance
        if (newRoute) {
            routePoints = ElevationPolyline.decode(encoded)
            prevPos = null
            aF = 0.0; pF = 0.0; aS = 0.0; pS = 0.0   // E4.4: nowa trasa = kalibracja od zera
        }
        rebuild(surfaceKnown, surfaceAtKm)
    }

    @Synchronized
    fun clearRoute() {
        routeKey = null; routePoints = emptyList(); plan = null; prevPos = null
    }

    private fun rebuild(surfaceKnown: Boolean, surfaceAtKm: ((Double) -> SurfaceType?)?) {
        val r = EtaPlanner.build(routePoints, routeDistanceM, if (surfaceKnown) surfaceAtKm else null)
        plan = r.plan
        planSurfaceKnown = surfaceKnown
        log(
            "QEXT_ETA_PROFILE points=${r.points} len_m=${"%.0f".format(r.lengthM)} " +
                "avg_spacing_m=${"%.1f".format(r.avgSpacingM)} route_m=${routeDistanceM?.let { "%.0f".format(it) } ?: "--"} " +
                "valid=${r.plan != null} reason=${r.reason} surface=${if (surfaceKnown) "known" else "unknown"} " +
                "plan_move_min=${r.plan?.let { "%.0f".format(it.totalSec / 60.0) } ?: "--"}"
        )
    }

    /** E1.7: nowa jazda (trasa zostaje, kalibracja i postoje od zera). */
    @Synchronized
    fun resetSession() {
        // E4.4: koniec jazdy bez dojazdu pod mete -- zapisz rzeczywisty koniec (samokontrola prognoz)
        if (!arrivalLogged && checkpointPreds.isNotEmpty() && lastTickMs > 0L) {
            val parts = checkpointPreds.joinToString(" ") { (cp, n, o) -> "f$cp:new=${errMin(n, lastTickMs)}min,old=${errMin(o, lastTickMs)}min" }
            log("QEXT_ETA_ARRIVAL reason=ride_end actual=${hhmm(lastTickMs)} $parts")
        }
        resetRide(); lastRideDistanceM = 0.0; lastTickMs = 0L
    }

    private fun resetRide() {
        rideStarted = false; movingSec = 0.0; movingDistM = 0.0
        curStopSec = 0.0; shortStopSec = 0.0; longStopSec = 0.0
        prevPos = null; aF = 0.0; pF = 0.0; aS = 0.0; pS = 0.0
        vFast = Double.NaN; vSlow = Double.NaN
        checkpointPreds.clear(); arrivalLogged = false
        longStopsKm.clear()
    }

    private fun posFromRemaining(remainingM: Double): Double? {
        val p = plan ?: return null
        val rd = routeDistanceM
        val scale = if (rd != null && rd > 0.0) p.lengthM / rd else 1.0
        return (p.lengthM - remainingM * scale).coerceIn(0.0, p.lengthM)
    }

    @Synchronized
    fun tick(
        nowMs: Long,
        isMoving: Boolean,
        speedKmh: Double,
        remainingM: Double,
        hasRoute: Boolean,
        rideDistanceM: Double,
        oldEtaMs: Long,
        surfaceKnown: Boolean,
        surfaceAtKm: ((Double) -> SurfaceType?)?,
    ): Output {
        if (rideDistanceM + 500.0 < lastRideDistanceM) resetRide()   // nowa jazda
        lastRideDistanceM = rideDistanceM
        if (routePoints.isNotEmpty() && surfaceKnown != planSurfaceKnown) rebuild(surfaceKnown, surfaceAtKm)

        val dt = if (lastTickMs == 0L) 1.0 else ((nowMs - lastTickMs) / 1000.0).coerceIn(0.0, 5.0)
        lastTickMs = nowMs

        // --- postoje i ruch
        if (isMoving) {
            if (curStopSec > 0.0) {
                if (curStopSec <= LONG_STOP_SEC) shortStopSec += curStopSec else longStopSec += curStopSec
                if (curStopSec >= 600.0) longStopsKm.add(rideDistanceM / 1000.0)
                curStopSec = 0.0
            }
            rideStarted = true
            movingSec += dt
            if (speedKmh > 0.0) movingDistM += speedKmh / 3.6 * dt
        } else if (rideStarted) {
            curStopSec += dt
        }

        // --- poziom 3: dwie srednie
        if (vFast.isNaN()) {
            val prior = loadPriorKmh().takeIf { it in 8.0..45.0 } ?: DEFAULT_PRIOR_KMH
            vFast = prior; vSlow = prior
        }
        if (isMoving && speedKmh >= 3.0) {
            vFast += (1.0 - exp(-dt / TAU_FAST_SEC)) * (speedKmh - vFast)
            vSlow += (1.0 - exp(-dt / TAU_SLOW_SEC)) * (speedKmh - vSlow)
        }

        // --- poziom 2: korekta planu
        val pos = if (hasRoute && remainingM > 0.0) posFromRemaining(remainingM) else null
        val p = plan
        if (p != null && pos != null) {
            val prev = prevPos
            if (isMoving && prev != null) {
                val dPos = pos - prev
                // E4.4: ucz sie tylko przy realnym postepie po trasie (poza trasa pozycja stoi -> bez kalibracji)
                if (dPos > 0.5 && dPos <= 25.0 * dt + 5.0) {
                    val dPlan = p.timeAt(pos) - p.timeAt(prev)
                    val kF = exp(-dt / TAU_FAST_SEC)
                    val kS = exp(-dt / TAU_SLOW_SEC)
                    aF = aF * kF + dt; pF = pF * kF + dPlan
                    aS = aS * kS + dt; pS = pS * kS + dPlan
                }
            }
            prevPos = pos
        }
        val rF = if (pF > 30.0) aF / pF else 1.0
        val rS = if (pS > 120.0) aS / pS else 1.0
        val w = min(1.0, pS / TRUST_PLAN_SEC)
        val factor = (1.0 + w * ((0.3 * rF + 0.7 * rS) - 1.0)).coerceIn(0.6, 1.8)

        val remKm = remainingM / 1000.0
        var level = 0
        var remMoving = 0.0
        if (hasRoute && remainingM > 0.0) {
            if (p != null && pos != null) {
                level = 2
                remMoving = (p.totalSec - p.timeAt(pos)) * factor
            } else {
                level = 3
                val v = max(5.0, 0.3 * vFast + 0.7 * vSlow)
                remMoving = remKm / v * 3600.0
            }
        }

        // --- pula krotkich postojow
        val doneKm = rideDistanceM / 1000.0
        val expDone = EtaSpeedTable.shortStopsSec(doneKm)
        val s = if (doneKm >= 15.0 && expDone > 0.0) {
            val raw = (shortStopSec / expDone).coerceIn(0.5, 2.0)
            1.0 + min(1.0, doneKm / 40.0) * (raw - 1.0)
        } else 1.0
        var pool = EtaSpeedTable.shortStopsSec(remKm) * s
        if (!isMoving && curStopSec in 0.0..LONG_STOP_SEC) pool = max(0.0, pool - curStopSec)

        val etaMs = if (level > 0) (nowMs + ((remMoving + pool) * 1000.0).toLong()).coerceAtLeast(nowMs + 60_000L) else 0L

        // --- zapis typowej predkosci (wartosc startowa poziomu 3)
        if (movingSec >= 1800.0 && nowMs - lastPriorSaveMs >= 300_000L) {
            lastPriorSaveMs = nowMs
            val avg = movingDistM / movingSec * 3.6
            if (avg in 8.0..45.0) savePriorKmh(avg)
        }

        // --- logi walidacyjne
        if (level > 0) {
            val total = rideDistanceM + remainingM
            val frac = if (total > 0.0) rideDistanceM / total else 0.0
            for (cp in CHECKPOINTS) {
                if (frac >= cp && checkpointPreds.none { it.first == cp }) {
                    checkpointPreds.add(Triple(cp, etaMs, oldEtaMs))
                    log("QEXT_ETA_CHECK frac=$cp level=$level new=${hhmm(etaMs)} old=${hhmm(oldEtaMs)} factor=${"%.2f".format(factor)} stops_min=${"%.0f".format(pool / 60.0)}")
                }
            }
            if (nowMs - lastPeriodicLogMs >= 300_000L) {
                lastPeriodicLogMs = nowMs
                log(
                    "QEXT_ETA level=$level rem_km=${"%.1f".format(remKm)} rem_move_min=${"%.0f".format(remMoving / 60.0)} " +
                        "stops_min=${"%.0f".format(pool / 60.0)} factor=${"%.2f".format(factor)} s=${"%.2f".format(s)} " +
                        "vF=${"%.1f".format(vFast)} vS=${"%.1f".format(vSlow)} new=${hhmm(etaMs)} old=${hhmm(oldEtaMs)}"
                )
            }
        }
        if (hasRoute && remainingM in 0.0..500.0 && !arrivalLogged && checkpointPreds.isNotEmpty()) {
            arrivalLogged = true
            val parts = checkpointPreds.joinToString(" ") { (cp, n, o) ->
                "f$cp:new=${errMin(n, nowMs)}min,old=${errMin(o, nowMs)}min"
            }
            log("QEXT_ETA_ARRIVAL actual=${hhmm(nowMs)} short_stops_min=${"%.0f".format(shortStopSec / 60.0)} long_stops_min=${"%.0f".format(longStopSec / 60.0)} $parts")
        }

        return Output(etaMs, level, remMoving, pool, factor)
    }

    @Synchronized
    fun longStopsKm(): List<Double> = ArrayList(longStopsKm)

    /** (pozycja teraz, pozycja po `sec` s jazdy wg planu) w metrach od startu trasy; null bez planu. */
    @Synchronized
    fun posAfterMovingSec(remainingM: Double, sec: Double): Pair<Double, Double>? {
        val p = plan ?: return null
        val pos = posFromRemaining(remainingM) ?: return null
        val target = p.timeAt(pos) + sec
        val cs = p.cumSec
        var i = (pos / p.stepM).toInt().coerceIn(0, cs.size - 1)
        while (i < cs.size && cs[i] < target) i++
        val d = if (i >= cs.size) p.lengthM else i * p.stepM
        return pos to d
    }

    /** Najblizszy stromy zjazd przed toba (z profilu Karoo): (odleglosc m, najmniejsze nachylenie %) albo null. */
    @Synchronized
    fun steepDescentAhead(remainingM: Double, horizonM: Double = 3000.0, thresholdPct: Double = -6.0): Pair<Double, Double>? {
        val p = plan ?: return null
        if (p.grades.isEmpty() || remainingM <= 0.0) return null
        val pos = posFromRemaining(remainingM) ?: return null
        val last = min(p.grades.size - 1, ((pos + horizonM) / p.stepM).toInt())
        var i = (pos / p.stepM).toInt() + 1
        while (i <= last) {
            if (p.grades[i] <= thresholdPct) {
                var j = i
                var mn = p.grades[i]
                while (j + 1 < p.grades.size && p.grades[j + 1] <= thresholdPct) { j++; mn = min(mn, p.grades[j]) }
                return max(0.0, i * p.stepM - pos) to mn
            }
            i++
        }
        return null
    }

    private fun hhmm(ms: Long): String {
        if (ms <= 0L) return "--"
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    private fun errMin(predMs: Long, actualMs: Long): String =
        if (predMs <= 0L) "--" else "%+.0f".format((predMs - actualMs) / 60000.0)
}
