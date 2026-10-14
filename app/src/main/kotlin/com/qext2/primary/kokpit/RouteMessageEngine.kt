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
/** radar=true: z radaru QBota (QbotRadar) - bez % (probPct=-1), sila slownie, durMin = czas trwania (gdy pada: ile jeszcze). */
data class RainSoon(val minutes: Int, val probPct: Int, val mmPerH: Float, val kind: String = "RAIN", val kmAhead: Float = 0f,
                    val radar: Boolean = false, val sila: String = "", val durMin: Int = 0)
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

enum class MsgKind { HUB, WPRIME, RAIN, FUEL, DUSK, DESCENT, CLIMB, SURFACE, POI, NONE }

/** lead = zwykly tekst, accent = wyrozniony fragment w kolorze accentColor (#RRGGBB). */
/** cfgId = id komunikatu w ustawieniach QBota (Setup > QExt2): wl/wyl, poziom, kolejnosc; "" = bez ustawien. */
data class RouteMsg(val kind: MsgKind, val lead: String, val accent: String, val accentColor: String, val cfgId: String = "")

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

    /** Wszystkie aktualne komunikaty, od najwazniejszego. Progi, wl/wyl i kolejnosc - z ustawien QBota (Kokpit2Config),
     *  domyslnie dotychczasowe wartosci. */
    fun candidates(i: RouteMsgInput): List<RouteMsg> {
        val C = Kokpit2Config
        val out = ArrayList<RouteMsg>()
        // 0. W' (wymaga natychmiastowej reakcji)
        if (C.on("wprime")) i.wprime?.let { wp -> out.add(RouteMsg(MsgKind.WPRIME, "W′ ${wp.pct}%:", wp.state, if (wp.critical) "#FF8C8C" else "#FB923C", "wprime")) }
        // 1. pilne
        val rn = i.rainNowMmH
        if (rn != null && rn >= C.p("rain_now", "min_mmh", 0.1).toFloat()) {
            if (C.on("rain_now")) out.add(RouteMsg(MsgKind.RAIN, "pada:", String.format(java.util.Locale.US, "%.1f mm/h", rn).replace('.', ','), "#60A5FA", "rain_now"))
        } else {
            val rs = i.rainSoon
            if (rs != null && rs.radar) {
                // radar QBota: pada / deszcz za X min, sila slownie + czas trwania (bez %)
                if (rs.minutes <= 2) { if (C.on("rain_now")) out.add(RouteMsg(MsgKind.RAIN, "pada:", "${rs.sila} · jeszcze ~${rs.durMin} min", "#60A5FA", "rain_now")) }
                else if (C.on("rain_soon") && rs.minutes <= C.pi("rain_soon", "lead_min", RAIN_SOON_MIN))
                    out.add(RouteMsg(MsgKind.RAIN, "deszcz za ${rs.minutes} min:", "${rs.sila} · ~${rs.durMin} min", "#60A5FA", "rain_soon"))
            } else if (rs != null) {
                val where = if (rs.kmAhead >= 0.5f) " (${km(rs.kmAhead)} km)" else ""
                when (rs.kind) {
                    "STORM" -> if (C.on("storm") && rs.minutes <= C.pi("storm", "lead_min", 60)) out.add(RouteMsg(MsgKind.RAIN, "burza za ${rs.minutes} min$where:", "${rs.probPct}%", "#FF8C8C", "storm"))
                    "SNOW" -> if (C.on("snow") && rs.minutes <= C.pi("snow", "lead_min", RAIN_SOON_MIN)) out.add(RouteMsg(MsgKind.RAIN, "śnieg za ${rs.minutes} min$where:", "${rs.probPct}%", "#BFDBFE", "snow"))
                    "FOG" -> {}
                    else -> if (C.on("rain_soon") && rs.minutes in 0..C.pi("rain_soon", "lead_min", RAIN_SOON_MIN) && rs.probPct >= C.pi("rain_soon", "min_prob", 40))
                        out.add(RouteMsg(MsgKind.RAIN, "deszcz za ${rs.minutes} min$where:", "${rs.probPct}%", "#60A5FA", "rain_soon"))
                }
            }
        }
        val cb = i.carbBalanceG
        if (C.on("fuel") && cb != null && cb <= C.pi("fuel", "alert_g", FUEL_ALERT_G)) out.add(RouteMsg(MsgKind.FUEL, "zjedz:", "${cb} g", "#E9A23B", "fuel"))
        if (i.duskMs > 0L && i.duskMs > i.nowMs) {
            val toDusk = ((i.duskMs - i.nowMs) / 60000L).toInt()
            if (i.etaMs > i.duskMs) { if (C.on("dusk_after")) out.add(RouteMsg(MsgKind.DUSK, "meta po zmroku:", "zmrok ${clock(i.duskMs)}", "#FF8C8C", "dusk_after")) }
            else if (C.on("dusk_soon") && toDusk in 0..C.pi("dusk_soon", "warn_min", DUSK_WARN_MIN)) out.add(RouteMsg(MsgKind.DUSK, "do zmroku:", "$toDusk min", "#FB923C", "dusk_soon"))
        }
        if (!i.hasRoute) { if (out.isEmpty()) out.add(RouteMsg(MsgKind.NONE, "brak trasy", "", "#9AA5B1")); return C.sortByOrder(out) }
        // 2. trasa w zasiegu (po odleglosci)
        val near = ArrayList<Pair<Float, RouteMsg>>()
        if (C.on("descent")) i.descent?.let { d ->
            if (d.distAheadKm <= C.p("descent", "near_km", NEAR_KM.toDouble()).toFloat()) {
                val s = surfaceAt(i.surfaces, i.posKm + d.distAheadKm)
                val tail = if (s != null && s != SurfClass.PAVED) " ${surfName(s)}" else ""
                near.add(d.distAheadKm to RouteMsg(MsgKind.DESCENT, "za ${km(d.distAheadKm)} km: zjazd", "${d.gradePct.roundToInt()}%$tail", "#FF8C8C", "descent"))
            }
        }
        val c = if (C.on("climb")) nextClimb(i.climbs, i.posKm) else null
        val sc = if (C.on("surface")) nextSurfaceChange(i.surfaces, i.posKm) else null
        val climbNear = C.p("climb", "near_km", NEAR_KM.toDouble()).toFloat()
        val surfNear = C.p("surface", "near_km", NEAR_KM.toDouble()).toFloat()
        c?.let { cl -> val d = (cl.startKm - i.posKm).coerceAtLeast(0f); if (d <= climbNear) near.add(d to climbMsg(d, cl, "climb")) }
        sc?.let { (d, s, len) -> if (d <= surfNear) near.add(d to surfMsg(d, s, len, "surface")) }
        out.addAll(near.sortedBy { it.first }.map { it.second })
        // 3. punkty (woda / sklep / jedzenie) w zasiegu; woda najpierw, zamkniete pomijane (wg ustawien)
        if (C.on("poi")) {
            val poiNear = C.p("poi", "near_km", POI_NEAR_KM.toDouble()).toFloat()
            val skipClosed = C.pb("poi", "skip_closed", true)
            val p = i.pois.filter { it.km >= i.posKm - 0.05f && it.km - i.posKm <= poiNear && (!skipClosed || it.today?.startsWith("zamkn") != true) }
                .sortedWith(compareBy({ if (it.cat == "water") 0 else 1 }, { it.km }))
                .firstOrNull()
            p?.let { out.add(poiMsg((it.km - i.posKm).coerceAtLeast(0f), it)) }
        }
        // 4. domyslnie (dalej niz zasieg)
        if (near.isEmpty()) {
            val far = ArrayList<Pair<Float, RouteMsg>>()
            c?.let { cl -> far.add((cl.startKm - i.posKm) to climbMsg((cl.startKm - i.posKm).coerceAtLeast(0f), cl, "climb_far")) }
            sc?.let { (d, s, len) -> far.add(d to surfMsg(d, s, len, "surface_far")) }
            far.minByOrNull { it.first }?.let { out.add(it.second) }
        }
        if (out.isEmpty()) out.add(RouteMsg(MsgKind.NONE, "", "", "#9AA5B1"))
        return C.sortByOrder(out)
    }

    fun pick(i: RouteMsgInput): RouteMsg = candidates(i).first()

    fun isUrgent(k: MsgKind) = k == MsgKind.WPRIME || k == MsgKind.RAIN || k == MsgKind.FUEL || k == MsgKind.DUSK

    private fun poiMsg(d: Float, p: PoiInfo): RouteMsg {
        val what = when (p.cat) { "water" -> "woda"; "shop" -> "sklep"; "food" -> "jedzenie"; else -> p.cat }
        val name = if (p.name.isNotBlank() && p.cat != "water") p.name.take(18) else ""
        val hrs = p.today?.let { if (name.isEmpty()) it else " · $it" } ?: ""
        return RouteMsg(MsgKind.POI, "za ${km(d)} km: $what", "$name$hrs".trim(), "#4ADE80", "poi")
    }

    private fun climbMsg(d: Float, cl: ClimbInfo, id: String = "climb") =
        RouteMsg(MsgKind.CLIMB, "za ${km(d)} km: podjazd", "${km(cl.lengthKm)} km · ${cl.gradePct.roundToInt()}%", "#FB923C", id)

    private fun surfMsg(d: Float, s: SurfClass, len: Float, id: String = "surface") =
        RouteMsg(MsgKind.SURFACE, "za ${km(d)} km:", "${surfName(s)} ${km(len)} km", surfColor(s), id)
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
        val solo = Kokpit2Config.rot("urgent_solo_s", 20L) * 1000L
        val step = Kokpit2Config.rot("rotate_s", 8L).coerceAtLeast(1L) * 1000L
        if (urgentKind != null && cands.size > 1 && nowMs - urgentSince > solo) {
            val slot = (nowMs - urgentSince - solo) / step
            want = if (slot % 2L == 1L) top else cands[1 + ((slot / 2L) % (cands.size - 1).toLong()).toInt()]
        }
        val cur = current
        if (cur != null && cur.kind != want.kind && nowMs - currentSince < Kokpit2Config.rot("min_hold_s", 5L) * 1000L) {
            val still = cands.firstOrNull { it.kind == cur.kind }
            if (still != null) { current = still; return still }
        }
        if (cur == null || cur.kind != want.kind) currentSince = nowMs
        current = want
        return want
    }
}
