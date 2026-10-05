package com.qext2.primary.kokpit

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * KOMUNIKATY (pasek w polu KOKPIT nawigacja). Lista kandydatow wg priorytetu:
 *  1. pilne: deszcz teraz / deszcz w ciagu 30 min, jedzenie (bilans <= -30 g), zmrok (meta po zmroku / < 45 min)
 *  2. na trasie w promieniu 3 km (od najblizszego): stromy zjazd, podjazd, zmiana nawierzchni
 *  3. punkty z QBota (woda / sklep / jedzenie) w promieniu 2 km
 *  4. domyslnie: nastepna zmiana nawierzchni lub nastepny podjazd (dowolnie daleko)
 * Co i jak dlugo jest na ekranie decyduje RouteMessageRotator (nizej).
 * Czysta logika (bez Androida). Pozycja i odcinki w km wzdluz trasy (konwencja QExt2: dystans jazdy).
 */
enum class SurfClass { PAVED, GRAVEL, LOOSE }

data class SurfSeg(val kmStart: Float, val kmEnd: Float, val surface: SurfClass)
data class ClimbInfo(val startKm: Float, val lengthKm: Float, val gradePct: Float)
data class DescentInfo(val distAheadKm: Float, val gradePct: Float)
data class RainSoon(val minutes: Int, val probPct: Int, val mmPerH: Float)
/** Punkt z QBota: cat = water | shop | food; today = godziny na dzis (np. "06:00–20:00", "zamknięte") albo null. */
/** Stan W' (jak w ClimbPacingProducer): pct, stan (BOMBA m:ss / ODBUDOWA m:ss / TRZYMASZ! / PRZEPAŁ), krytyczny. */
data class WPrimeInfo(val pct: Int, val state: String, val critical: Boolean)
data class PoiInfo(val km: Float, val cat: String, val name: String, val today: String?)

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
    val pois: List<PoiInfo> = emptyList(),
    val wprime: WPrimeInfo? = null,
)

enum class MsgKind { WPRIME, RAIN, FUEL, DUSK, DESCENT, CLIMB, SURFACE, POI, NONE }

/** lead = zwykly tekst, accent = wyrozniony fragment w kolorze accentColor (#RRGGBB). */
data class RouteMsg(val kind: MsgKind, val lead: String, val accent: String, val accentColor: String)

object RouteMessageEngine {
    const val NEAR_KM = 3.0f
    const val POI_NEAR_KM = 2.0f
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
        val i = sorted.indexOfFirst { it.kmEnd > pos && it.surface != cur && it.kmStart >= pos - 0.01f }
        if (i < 0) return null
        val s = sorted[i]
        var end = s.kmEnd
        var j = i + 1
        while (j < sorted.size && sorted[j].surface == s.surface && abs(sorted[j].kmStart - end) < 0.05f) { end = sorted[j].kmEnd; j++ }
        return Triple((s.kmStart - pos).coerceAtLeast(0f), s.surface, end - s.kmStart)
    }

    fun nextClimb(climbs: List<ClimbInfo>, pos: Float): ClimbInfo? =
        climbs.filter { it.startKm >= pos - 0.05f }.minByOrNull { it.startKm }

    /** Wszystkie aktualne komunikaty, od najwazniejszego. */
    fun candidates(i: RouteMsgInput): List<RouteMsg> {
        val out = ArrayList<RouteMsg>()
        // 0. W' (najwyzszy priorytet - wymaga natychmiastowej reakcji)
        i.wprime?.let { wp -> out.add(RouteMsg(MsgKind.WPRIME, "W′ ${wp.pct}%:", wp.state, if (wp.critical) "#F87171" else "#FB923C")) }
        // 1. pilne
        val rn = i.rainNowMmH
        if (rn != null && rn >= 0.1f) out.add(RouteMsg(MsgKind.RAIN, "pada:", String.format(java.util.Locale.US, "%.1f mm/h", rn).replace('.', ','), "#60A5FA"))
        else {
            val rs = i.rainSoon
            if (rs != null && rs.minutes in 0..RAIN_SOON_MIN && rs.probPct >= 40) out.add(RouteMsg(MsgKind.RAIN, "deszcz za ${rs.minutes} min:", "${rs.probPct}%", "#60A5FA"))
        }
        val cb = i.carbBalanceG
        if (cb != null && cb <= FUEL_ALERT_G) out.add(RouteMsg(MsgKind.FUEL, "zjedz:", "${cb} g", "#E9A23B"))
        if (i.duskMs > 0L && i.duskMs > i.nowMs) {
            val toDusk = ((i.duskMs - i.nowMs) / 60000L).toInt()
            if (i.etaMs > i.duskMs) out.add(RouteMsg(MsgKind.DUSK, "meta po zmroku:", "zmrok ${clock(i.duskMs)}", "#F87171"))
            else if (toDusk in 0..DUSK_WARN_MIN) out.add(RouteMsg(MsgKind.DUSK, "do zmroku:", "$toDusk min", "#FB923C"))
        }
        if (!i.hasRoute) { if (out.isEmpty()) out.add(RouteMsg(MsgKind.NONE, "brak trasy", "", "#9AA5B1")); return out }
        // 2. trasa w promieniu 3 km (po odleglosci)
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
        c?.let { cl -> val d = (cl.startKm - i.posKm).coerceAtLeast(0f); if (d <= NEAR_KM) near.add(d to climbMsg(d, cl)) }
        sc?.let { (d, s, len) -> if (d <= NEAR_KM) near.add(d to surfMsg(d, s, len)) }
        out.addAll(near.sortedBy { it.first }.map { it.second })
        // 3. punkty (woda / sklep / jedzenie) w promieniu 2 km; woda najpierw, zamkniete pomijane
        val p = i.pois.filter { it.km >= i.posKm - 0.05f && it.km - i.posKm <= POI_NEAR_KM && it.today?.startsWith("zamkn") != true }
            .sortedWith(compareBy({ if (it.cat == "water") 0 else 1 }, { it.km }))
            .firstOrNull()
        p?.let { out.add(poiMsg((it.km - i.posKm).coerceAtLeast(0f), it)) }
        // 4. domyslnie (dalej niz 3 km)
        if (near.isEmpty()) {
            val far = ArrayList<Pair<Float, RouteMsg>>()
            c?.let { cl -> far.add((cl.startKm - i.posKm) to climbMsg((cl.startKm - i.posKm).coerceAtLeast(0f), cl)) }
            sc?.let { (d, s, len) -> far.add(d to surfMsg(d, s, len)) }
            far.minByOrNull { it.first }?.let { out.add(it.second) }
        }
        if (out.isEmpty()) out.add(
            if (i.surfaces.isEmpty()) RouteMsg(MsgKind.NONE, "brak danych o nawierzchni (QBot)", "", "#9AA5B1")
            else RouteMsg(MsgKind.NONE, "do mety bez zmian nawierzchni i podjazdów", "", "#9AA5B1")
        )
        return out
    }

    fun pick(i: RouteMsgInput): RouteMsg = candidates(i).first()

    fun isUrgent(k: MsgKind) = k == MsgKind.WPRIME || k == MsgKind.RAIN || k == MsgKind.FUEL || k == MsgKind.DUSK

    private fun poiMsg(d: Float, p: PoiInfo): RouteMsg {
        val what = when (p.cat) { "water" -> "woda"; "shop" -> "sklep"; "food" -> "jedzenie"; else -> p.cat }
        val name = if (p.name.isNotBlank() && p.cat != "water") p.name.take(18) else ""
        val hrs = p.today?.let { if (name.isEmpty()) it else " · $it" } ?: ""
        return RouteMsg(MsgKind.POI, "za ${km(d)} km: $what", "$name$hrs".trim(), "#4ADE80")
    }

    private fun climbMsg(d: Float, cl: ClimbInfo) =
        RouteMsg(MsgKind.CLIMB, "za ${km(d)} km: podjazd", "${km(cl.lengthKm)} km · ${cl.gradePct.roundToInt()}%", "#FB923C")

    private fun surfMsg(d: Float, s: SurfClass, len: Float) =
        RouteMsg(MsgKind.SURFACE, "za ${km(d)} km:", "${surfName(s)} ${km(len)} km", surfColor(s))
}

/**
 * Kolejnosc i czas wyswietlania komunikatow:
 *  - zawsze najwazniejszy aktualny komunikat (lista z RouteMessageEngine.candidates);
 *  - pilny (deszcz/jedzenie/zmrok) najpierw sam przez 20 s, potem na zmiane co 8 s z kolejnymi z listy (po kolei);
 *  - kazdy komunikat trzyma sie min. 5 s (bez migania na granicy 3 km), chyba ze zniknie z listy;
 *  - komunikat znika sam, gdy przestaje byc aktualny (minales punkt, przestalo padac, zjadles).
 */
class RouteMessageRotator {
    private var current: RouteMsg? = null
    private var currentSince = 0L
    private var urgentKind: MsgKind? = null
    private var urgentSince = 0L

    fun next(nowMs: Long, cands: List<RouteMsg>): RouteMsg {
        if (cands.isEmpty()) return RouteMsg(MsgKind.NONE, "", "", "#9AA5B1")
        val top = cands[0]
        if (top.kind == MsgKind.WPRIME) { current = top; currentSince = nowMs; return top }
        if (RouteMessageEngine.isUrgent(top.kind)) {
            if (urgentKind != top.kind) { urgentKind = top.kind; urgentSince = nowMs }
        } else urgentKind = null
        var want = top
        if (urgentKind != null && cands.size > 1 && nowMs - urgentSince > 20_000L) {
            val slot = (nowMs - urgentSince - 20_000L) / 8_000L
            want = if (slot % 2L == 1L) top else cands[1 + ((slot / 2L) % (cands.size - 1).toLong()).toInt()]
        }
        val cur = current
        if (cur != null && cur.kind != want.kind && nowMs - currentSince < 5_000L) {
            val still = cands.firstOrNull { it.kind == cur.kind }
            if (still != null) { current = still; return still }
        }
        if (cur == null || cur.kind != want.kind) currentSince = nowMs
        current = want
        return want
    }
}
