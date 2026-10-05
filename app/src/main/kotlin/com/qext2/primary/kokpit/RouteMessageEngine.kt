package com.qext2.primary.kokpit

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * KOMUNIKATY (pasek w polu KOKPIT nawigacja). Jeden komunikat naraz, wg priorytetu:
 *  1. pilne: deszcz teraz / deszcz w ciagu 30 min, jedzenie (bilans <= -30 g), zmrok (meta po zmroku / < 45 min)
 *  2. najblizsza rzecz na trasie w promieniu 3 km: stromy zjazd, podjazd, zmiana nawierzchni
 *  3. domyslnie: nastepna zmiana nawierzchni lub nastepny podjazd (dowolnie daleko)
 * Czysta logika (bez Androida). Pozycja i odcinki w km wzdluz trasy (konwencja QExt2: dystans jazdy).
 */
enum class SurfClass { PAVED, GRAVEL, LOOSE }

data class SurfSeg(val kmStart: Float, val kmEnd: Float, val surface: SurfClass)
data class ClimbInfo(val startKm: Float, val lengthKm: Float, val gradePct: Float)
data class DescentInfo(val distAheadKm: Float, val gradePct: Float)
data class RainSoon(val minutes: Int, val probPct: Int, val mmPerH: Float)

data class RouteMsgInput(
    val nowMs: Long,
    val hasRoute: Boolean,
    val posKm: Float,
    val surfaces: List<SurfSeg> = emptyList(),
    val climbs: List<ClimbInfo> = emptyList(),
    val descent: DescentInfo? = null,
    val rainNowMmH: Float? = null,
    val rainSoon: RainSoon? = null,
    val carbBalanceG: Int? = null,
    val duskMs: Long = 0L,
    val etaMs: Long = 0L,
)

enum class MsgKind { RAIN, FUEL, DUSK, DESCENT, CLIMB, SURFACE, NONE }

/** lead = zwykly tekst, accent = wyrozniony fragment w kolorze accentColor (#RRGGBB). */
data class RouteMsg(val kind: MsgKind, val lead: String, val accent: String, val accentColor: String)

object RouteMessageEngine {
    const val NEAR_KM = 3.0f
    const val RAIN_SOON_MIN = 30
    const val FUEL_ALERT_G = -30
    const val DUSK_WARN_MIN = 45

    private fun km(v: Float): String {
        val r = if (v < 10f) (v * 10f).roundToInt() / 10f else v.roundToInt().toFloat()
        return (if (v < 10f) String.format(java.util.Locale.US, "%.1f", r) else String.format(java.util.Locale.US, "%.0f", r)).replace('.', ',')
    }

    private fun clock(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(java.util.Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    fun surfName(s: SurfClass): String = when (s) { SurfClass.PAVED -> "asfalt"; SurfClass.GRAVEL -> "szuter"; SurfClass.LOOSE -> "sypkie" }
    fun surfColor(s: SurfClass): String = when (s) { SurfClass.PAVED -> "#C9D2DC"; SurfClass.GRAVEL -> "#D9A04E"; SurfClass.LOOSE -> "#E0563B" }

    fun surfaceAt(segs: List<SurfSeg>, km: Float): SurfClass? =
        segs.firstOrNull { km >= it.kmStart && km < it.kmEnd }?.surface

    /** Nastepna zmiana nawierzchni: (odleglosc km, nowa klasa, dlugosc ciaglego odcinka km). */
    fun nextSurfaceChange(segs: List<SurfSeg>, pos: Float): Triple<Float, SurfClass, Float>? {
        if (segs.isEmpty()) return null
        val sorted = segs.sortedBy { it.kmStart }
        val cur = surfaceAt(sorted, pos)
        var i = sorted.indexOfFirst { it.kmEnd > pos && it.surface != cur && it.kmStart >= pos - 0.01f }
        if (i < 0) return null
        val s = sorted[i]
        var end = s.kmEnd
        var j = i + 1
        while (j < sorted.size && sorted[j].surface == s.surface && abs(sorted[j].kmStart - end) < 0.05f) { end = sorted[j].kmEnd; j++ }
        return Triple((s.kmStart - pos).coerceAtLeast(0f), s.surface, end - s.kmStart)
    }

    fun nextClimb(climbs: List<ClimbInfo>, pos: Float): ClimbInfo? =
        climbs.filter { it.startKm >= pos - 0.05f }.minByOrNull { it.startKm }

    fun pick(i: RouteMsgInput): RouteMsg {
        // 1. pilne
        val rn = i.rainNowMmH
        if (rn != null && rn >= 0.1f) return RouteMsg(MsgKind.RAIN, "pada:", String.format(java.util.Locale.US, "%.1f mm/h", rn).replace('.', ','), "#60A5FA")
        val rs = i.rainSoon
        if (rs != null && rs.minutes in 0..RAIN_SOON_MIN && rs.probPct >= 40) return RouteMsg(MsgKind.RAIN, "deszcz za ${rs.minutes} min:", "${rs.probPct}%", "#60A5FA")
        val cb = i.carbBalanceG
        if (cb != null && cb <= FUEL_ALERT_G) return RouteMsg(MsgKind.FUEL, "zjedz:", "${cb} g", "#E9A23B")
        if (i.duskMs > 0L) {
            if (i.etaMs > i.duskMs && i.duskMs > i.nowMs) return RouteMsg(MsgKind.DUSK, "meta po zmroku:", "zmrok ${clock(i.duskMs)}", "#F87171")
            val toDusk = ((i.duskMs - i.nowMs) / 60000L).toInt()
            if (toDusk in 0..DUSK_WARN_MIN) return RouteMsg(MsgKind.DUSK, "do zmroku:", "$toDusk min", "#FB923C")
        }
        if (!i.hasRoute) return RouteMsg(MsgKind.NONE, "brak trasy", "", "#9AA5B1")
        // 2. blisko na trasie
        val near = ArrayList<Pair<Float, RouteMsg>>()
        i.descent?.let { d ->
            if (d.distAheadKm <= NEAR_KM) {
                val s = surfaceAt(i.surfaces, i.posKm + d.distAheadKm)
                val tail = if (s != null && s != SurfClass.PAVED) " ${surfName(s)}" else ""
                near.add(d.distAheadKm to RouteMsg(MsgKind.DESCENT, "za ${km(d.distAheadKm)} km: zjazd", "${d.gradePct.roundToInt()}%$tail", "#F87171"))
            }
        }
        val c = nextClimb(i.climbs, i.posKm)
        val sc = nextSurfaceChange(i.surfaces, i.posKm)
        c?.let { cl ->
            val d = (cl.startKm - i.posKm).coerceAtLeast(0f)
            if (d <= NEAR_KM) near.add(d to climbMsg(d, cl))
        }
        sc?.let { (d, s, len) -> if (d <= NEAR_KM) near.add(d to surfMsg(d, s, len)) }
        near.minByOrNull { it.first }?.let { return it.second }
        // 3. domyslnie
        val far = ArrayList<Pair<Float, RouteMsg>>()
        c?.let { cl -> far.add((cl.startKm - i.posKm) to climbMsg((cl.startKm - i.posKm).coerceAtLeast(0f), cl)) }
        sc?.let { (d, s, len) -> far.add(d to surfMsg(d, s, len)) }
        far.minByOrNull { it.first }?.let { return it.second }
        return RouteMsg(MsgKind.NONE, "do mety bez zmian nawierzchni i podjazdów", "", "#9AA5B1")
    }

    private fun climbMsg(d: Float, cl: ClimbInfo) =
        RouteMsg(MsgKind.CLIMB, "za ${km(d)} km: podjazd", "${km(cl.lengthKm)} km · ${cl.gradePct.roundToInt()}%", "#FB923C")

    private fun surfMsg(d: Float, s: SurfClass, len: Float) =
        RouteMsg(MsgKind.SURFACE, "za ${km(d)} km:", "${surfName(s)} ${km(len)} km", surfColor(s))
}
