package com.qext2.primary.kokpit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.RectF
import android.graphics.Typeface
import com.qext2.primary.engine.RideDataAggregator
import com.qext2.primary.model.StatsRideSnapshot
import com.qext2.primary.model.SurfaceType
import com.qext2.primary.surface.SurfaceBridge
import kotlin.math.abs

/*
 * KOKPIT 2 (2026-10-10) - pola "QExt2 KOKPIT 2 nav" (gorne) i "QExt2 KOKPIT 2 instr" (dolne).
 * Wyglad wg kanwy "QExt2 - pola Karoo", plansza "KOKPIT - v10 ROBOCZA" (Kokpit9.dc.html), 474x126 na pole.
 * Dane z tych samych zrodel co KOKPIT (KokpitNavDataType / KokpitInstDataType, parametr v2 = true).
 * Demo: przelacznik "KOKPIT 2: dane demo" w SETUP (AthleteDataStore.loadKokpit2Demo).
 */

/** Belka trasy w dolnym polu: km przejechane, dlugosc trasy, nawierzchnia przed toba (gotowe kolory), postoje. */
data class RouteBar(
    val doneKm: Float,
    val totalKm: Float?,
    val ahead: List<Pair<Float, Int>>?,
    val stopsKm: List<Float>,
)

object Kokpit2Route {
    /** kolory "crispy" na ekran Karoo: asfalt prawie bialy, szuter bursztyn, trudny czerwony */
    val PAVED = Color.parseColor("#F2F4F7")
    val GRAVEL = Color.parseColor("#FFB300")
    val LOOSE = Color.parseColor("#FF2A2A")

    private fun col(t: SurfaceType): Int = when (t) {
        SurfaceType.PAVED -> PAVED
        SurfaceType.GRAVEL -> GRAVEL
        SurfaceType.LOOSE -> LOOSE
    }

    /** to samo liczenie pozycji i nawierzchni co KokpitNavDataType.toData */
    fun of(agg: RideDataAggregator?, s: StatsRideSnapshot): RouteBar {
        val pos = agg?.getRoutePositionM()?.let { (it / 1000.0).toFloat() } ?: s.distanceKm.coerceAtLeast(0f)
        val dtdKm = ((agg?.getDistanceToDestinationMeters() ?: 0.0) / 1000.0).toFloat()
        val total = if (s.hasRoute && dtdKm > 0.05f) pos + dtdKm else null
        val segs = agg?.navSurfaceSegments() ?: SurfaceBridge.segmentsSnapshot()
        val ahead = if (segs.isNotEmpty() && total != null) segs.sortedBy { it.kmStart }.filter { it.kmEnd > pos }
            .map { (it.kmEnd - maxOf(it.kmStart, pos)) to col(it.surface) } else null
        val stops = try { agg?.getLongStopsKm()?.map { it.toFloat() } } catch (_: Exception) { null } ?: emptyList()
        return RouteBar(pos, total, ahead, stops)
    }
}

/** Dane przykladowe KOKPIT 2 (przelacznik w SETUP). */
object Kokpit2Demo {
    fun route(now: Long): RouteBar {
        val t = ((now / 1000L) % 120L).toFloat(); val f = t / 120f
        val total = 164f; val done = total * f
        val segs = listOf(92f to Kokpit2Route.PAVED, 61f to Kokpit2Route.GRAVEL, 11f to Kokpit2Route.LOOSE)
        var rem = done
        val ahead = segs.mapNotNull { (len, col) -> val r = len - rem; rem = maxOf(0f, rem - len); if (r > 0f) r to col else null }
        return RouteBar(done, total, ahead, listOf(22f, 41.5f))
    }

    /** demo nawigacji: wiatr czolowy liczony z obracajacego sie kierunku (kolor strzalki zmienia sie w kolko) */
    fun nav(d0: KokpitNavData): KokpitNavData {
        // rotacja scenariuszy pogody co 8 s - takze najszersze wartosci (temp. ujemna 2-cyfrowa, wiatr 2-cyfrowy, nachylenie -12%)
        val now = System.currentTimeMillis()
        val d1 = when (((now / 8000L) % 6L).toInt()) {
            0 -> d0.copy(tempC = 24f, rainNowMmH = null, rainSoon = null, sky = "CLEAR", windMps = 4f)
            1 -> d0.copy(tempC = -7f, rainNowMmH = null, rainSoon = RainSoon(25, 70, 1.5f, "SNOW", 8f), windMps = 6f)
            2 -> d0.copy(tempC = 8f, rainNowMmH = 2.4f, rainSoon = null, windMps = 9f)
            3 -> d0.copy(tempC = 12f, rainNowMmH = null, rainSoon = RainSoon(40, 60, 1.2f, "RAIN", 10f), sky = "OVERCAST", windMps = 5f)
            4 -> d0.copy(tempC = 31f, rainNowMmH = null, rainSoon = RainSoon(15, 80, 6f, "STORM", 4f), windMps = 12f, gradePct = -12f)
            else -> d0.copy(tempC = -12f, rainNowMmH = null, rainSoon = null, sky = "FOG", windMps = 14f, gradePct = 12f)
        }
        // manewry co 5 s (wszystkie rodzaje, odleglosci w m i km)
        val tk = listOf("LEFT", "RB_R", "UTURN", "SLIGHT_RIGHT", "SHARP_LEFT", "RB_S", "RIGHT", "RB_L", "STRAIGHT", "SHARP_RIGHT", "SLIGHT_LEFT", "RB_U")
        val td = listOf(300.0, 1240.0, 80.0, 650.0, 150.0, 450.0, 25.0, 2400.0, 900.0, 60.0, 1800.0, 220.0)
        val ti = ((now / 5000L) % tk.size).toInt()
        // pole dynamiczne co 4 s - wszystkie rodzaje, w tym najszersze (wymagana srednia zolta i czerwona)
        val dyn = listOf(DynItem("ATD", "640", "m"), DynItem("WIND", "18", "km"), DynItem("shop", "12", "km"),
            DynItem("water", "3,4", "km"), DynItem("AVG", "21,5", "km/h", 1), DynItem("AVG", "24,8", "km/h", 2))[((now / 4000L) % 6L).toInt()]
        // 2026-10-17: przelacznik AERO -> demo pola AERO (oszczednosc, strata, zbiera, brak)
        val dynA = if (com.qext2.primary.data.AthleteDataStore.loadKokpit2DynAero())
            listOf(DynItem("AERO", "−11", "W", 0, 1), DynItem("AERO", "−4", "W", 0, 0), DynItem("AERO", "+6", "W", 0, 1),
                DynItem("AERO", "···", "W"), DynItem("AERO", "—", "W"))[((now / 4000L) % 5L).toInt()] else dyn
        val d = d1.copy(turnKind = tk[ti], turnDistM = td[ti], dyn = dynA, aero = AeroTop("−8", 1), routeSrc = "Q", nowMs = now)
        val rel = d.windRelDeg ?: return d
        val ws = d.windMps ?: 4f
        // strzalka = kierunek wiatru wzgledem jazdy: w gore (0 st.) wieje w plecy, w dol w twarz -> czolowy = -ws*cos
        return d.copy(windSignedMps = (-ws * kotlin.math.cos(Math.toRadians(rel.toDouble()))).toFloat(), windTotalMps = ws)
    }
}


/** Rodzaj manewru z geometrii trasy w punkcie skretu (odleglosc wzdluz trasy w m). */
object TurnClassifier {
    private fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val la1 = Math.toRadians(a.first); val la2 = Math.toRadians(b.first); val dl = Math.toRadians(b.second - a.second)
        val y = kotlin.math.sin(dl) * kotlin.math.cos(la2)
        val x = kotlin.math.cos(la1) * kotlin.math.sin(la2) - kotlin.math.sin(la1) * kotlin.math.cos(la2) * kotlin.math.cos(dl)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }
    private fun diff(a: Double, b: Double): Double { var d = b - a; while (d > 180.0) d -= 360.0; while (d <= -180.0) d += 360.0; return d }

    /** LEFT/RIGHT, SLIGHT_*, SHARP_*, UTURN, STRAIGHT; rondo: RB_R / RB_S / RB_L / RB_U (+ = w prawo) */
    fun classify(line: com.qext2.primary.weather.RouteLine, at: Double): String? {
        val len = line.lengthM
        if (len < 100.0) return null
        val t = at.coerceIn(0.0, len)
        val net = diff(bearing(line.at(t - 40.0), line.at(t - 5.0)), bearing(line.at(t + 5.0), line.at(t + 40.0)))
        // rondo: na +-35 m droga skreca w obie strony (wjazd w prawo, objazd w lewo, wyjazd w prawo)
        var left = 0.0; var right = 0.0; var prev: Double? = null
        var s = t - 35.0
        while (s < t + 35.0) {
            val h = bearing(line.at(s), line.at(s + 5.0))
            prev?.let { val dd = diff(it, h); if (dd > 0.0) right += dd else left -= dd }
            prev = h; s += 5.0
        }
        val a = abs(net)
        return if (left >= 40.0 && right >= 40.0) when { a >= 150.0 -> "RB_U"; net > 30.0 -> "RB_R"; net < -30.0 -> "RB_L"; else -> "RB_S" }
        else when {
            a < 20.0 -> "STRAIGHT"
            a < 45.0 -> if (net > 0.0) "SLIGHT_RIGHT" else "SLIGHT_LEFT"
            a < 135.0 -> if (net > 0.0) "RIGHT" else "LEFT"
            a < 160.0 -> if (net > 0.0) "SHARP_RIGHT" else "SHARP_LEFT"
            else -> "UTURN"
        }
    }
}

/** Czcionka KOKPIT 2: Saira Semi Condensed (OFL, assets/fonts) dla wartosci - SemiBold, moc i predkosc - Bold; podpisy systemowe. */
object Kokpit2Fonts {
    @Volatile private var r: Typeface? = null
    @Volatile private var s: Typeface? = null
    @Volatile private var b: Typeface? = null
    private val fbB: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val fbR: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    fun init(ctx: Context) {
        if (b != null) return
        try {
            s = Typeface.createFromAsset(ctx.assets, "fonts/SairaSemiCondensed-SemiBold.ttf")
            b = Typeface.createFromAsset(ctx.assets, "fonts/SairaSemiCondensed-Bold.ttf")
        } catch (e: Exception) {
            com.qext2.primary.util.RideFileLog.append("FONT_FAIL Kokpit2 ${e.javaClass.simpleName} ${e.message}")
        }
    }
    val reg: Typeface get() = r ?: fbR
    val semi: Typeface get() = s ?: fbB
    val bold: Typeface get() = b ?: fbB
}

private const val CAP = 0.688f   // wysokosc cyfr / rozmiar czcionki (Saira Semi Condensed, OS/2 capHeight 688/1000)

/* ============================== dolne pole: instr ============================== */

object Kokpit2InstRenderer {
    private val BG = Color.parseColor("#14181D")
    private val UNIT = Color.parseColor("#9AA5B1")
    private val NONE = Color.parseColor("#9AA3AE")
    private val DIV = Color.parseColor("#2A3038")
    private val TRACK = Color.parseColor("#465366")
    private val DONE = Color.parseColor("#2E7BFF")
    private val STOP = Color.parseColor("#FF3DF5")
    private val POS = Color.parseColor("#39FF14")
    private val GOOD = Color.parseColor("#4ADE80")
    private val BAD = Color.parseColor("#FF8C8C")
    private val SUB = Color.parseColor("#C9D2DC")
    private val YEL = Color.parseColor("#FACC15")
    private val ORANGE = Color.parseColor("#FB923C")
    private val SPEED = Color.parseColor("#F2C230")
    /** 0.4.27: domyslny kolor predkosci (decyzja Michala 22.10) */
    private val CYAN = Color.parseColor("#67E8F9")
    /** wielkosc cyfry po przecinku predkosci wzgledem cyfr glownych */
    private const val DEC = 0.55f
    private val WHITE = Color.WHITE
    private val BLACK = Color.BLACK
    private val PZ = listOf(0.00f to "#9AA3AE", 0.55f to "#6FA8FF", 0.75f to "#22C55E", 0.90f to "#EAB308", 1.05f to "#F97316", 1.20f to "#EF4444")

    private val bold: Typeface get() = Kokpit2Fonts.semi   // Saira Semi Condensed SemiBold
    private val reg: Typeface get() = Kokpit2Fonts.reg
    /** true = moc i predkosc: Saira Condensed Bold */
    private var heavy = false
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private fun fmt(p: String, vararg a: Any): String = String.format(java.util.Locale.US, p, *a)

    private fun t(c: Canvas, s: String, x: Float, base: Float, size: Float, color: Int, b: Boolean = true, align: Paint.Align = Paint.Align.LEFT) {
        tp.typeface = if (heavy) Kokpit2Fonts.bold else if (b) bold else reg; tp.textSize = size; tp.color = color; tp.textAlign = align
        c.drawText(s, x, base, tp)
        tp.textAlign = Paint.Align.LEFT
    }
    private fun w(s: String, size: Float, b: Boolean = true): Float { tp.typeface = if (heavy) Kokpit2Fonts.bold else if (b) bold else reg; tp.textSize = size; return tp.measureText(s) }

    private fun trendCol(v: Int) = when { v > 0 -> GOOD; v < 0 -> BAD; else -> SUB }

    @Synchronized
    fun render(width: Int, height: Int, d: KokpitInstData): Bitmap {
        val W = width.coerceAtLeast(200).toFloat(); val H = height.coerceAtLeast(80).toFloat()
        val bmp = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(BG)
        // uklad z makiety 474x126; wieksze pole: ta sama skala, nadmiar wysokosci na gorze
        val k = minOf(H / 126f, W / 474f)
        c.save()
        c.scale(k, k)
        draw(c, d, W / k, (H / k - 126f).coerceAtLeast(0f))   // ekran jazdy: 478x143 -> ok. 16 px nadmiaru
        c.restore()
        return bmp
    }

    private fun draw(c: Canvas, d: KokpitInstData, vw: Float, ext: Float) {
        val cx = vw / 2f
        val base = 123f + ext      // dolny wiersz 3 px od dolnej krawedzi (belka zostaje u gory)
        val g = 9f   // odstep mocy i predkosci od kreski srodkowej (byl 5)
        val lx0 = 6f
        val rx = vw - 4f

        // belka trasy na gorze pola
        d.route?.let { routeBar(c, it, 8f, vw - 8f, 6f, 18f, vw) }
        // separator miedzy W i V
        fp.color = DIV; c.drawRect(cx - 1f, 24f, cx + 1f, 126f + ext, fp)

        // --- gorny wiersz: tetno (lewo), NP 5 | srednia predkosc (przy srodku), KAD (prawo)
        // gorny wiersz staly; nadmiar wysokosci idzie do dolnego wiersza (moc i predkosc)
        val capTop = 27f   // gorny wiersz (tetno, NP 5, sr. V, KAD) 2 px nizej
        val big = 52f
        val bigBase = capTop + big * CAP
        val z = d.hrZone
        val showZone = Kokpit2Config.color("hr", "zone") == "zone" && z != null   // Setup QBota: strefa / bpm
        val hrTxt = if (showZone) "Z$z" else d.hr?.toString() ?: "—"
        val hrCol = when { d.hr == null && !showZone -> NONE; z == 5 -> BAD; z == 4 -> YEL; else -> WHITE }
        val heartCol = when (d.hrDriftLevel) { 2 -> BAD; 1 -> ORANGE; else -> WHITE }
        heart(c, lx0, capTop + (big * CAP - 20f) / 2f, 22f, heartCol)
        t(c, hrTxt, lx0 + 26f, bigBase, big, hrCol)

        val cv = d.cadence?.toString() ?: "—"
        t(c, cv, rx, bigBase, big, if (d.cadence != null) WHITE else NONE, true, Paint.Align.RIGHT)
        t(c, "KAD", rx - w(cv, big) - 5f, capTop + 15f * CAP, 15f, UNIT, false, Paint.Align.RIGHT)

        val ref = 46f   // 0.4.27: NP 5 i srednia wieksze (40 -> 46)
        val refBase = capTop + ref * CAP
        val npTxt = d.cpe5W?.let { kotlin.math.round(it).toInt().toString() } ?: "—"
        t(c, npTxt, cx - g, refBase, ref, if (d.cpe5W != null) WHITE else NONE, true, Paint.Align.RIGHT)
        val ls = 19f
        val lc = trendCol(d.cpTrend)
        val lw = w("NP", ls, false)
        val lRight = cx - g - maxOf(w(npTxt, ref), w("288", ref)) - 2f   // 0.4.27: podpis w stalym miejscu (wzorzec 288)
        t(c, "NP", lRight, capTop + ls * CAP, ls, lc, false, Paint.Align.RIGHT)
        t(c, "5", lRight - lw / 2f, capTop + ls * CAP + ls * 0.85f, ls, lc, false, Paint.Align.CENTER)
        // 19.10: trojkat trendu NP 5 jak przy sredniej (lustrzanie, z lewej podpisu): zielony w gore = biezaca moc podnosi NP 5
        if (d.cpTrend != 0) {
            val aw = 14f; val ah = 16f; val ax = lRight - lw - 4f - aw; val ay = refBase - ref * CAP / 2f - ah / 2f
            val p = Path()
            if (d.cpTrend > 0) { p.moveTo(ax + aw / 2f, ay); p.lineTo(ax + aw, ay + ah); p.lineTo(ax, ay + ah) }
            else { p.moveTo(ax, ay); p.lineTo(ax + aw, ay); p.lineTo(ax + aw / 2f, ay + ah) }
            p.close(); fp.color = lc; c.drawPath(p, fp)
        }
        val avTxt = d.avgSpeedKmh?.let { fmt("%.1f", it) } ?: "—"
        t(c, avTxt, cx + g, refBase, ref, if (d.avgSpeedKmh != null) WHITE else NONE)
        // 18.10: znak sredniej wiekszy i grubszy + strzalka: zielona w gore = podnosisz srednia, czerwona w dol = obnizasz
        val sym = 24f
        val sx0 = cx + g + maxOf(w(avTxt, ref), w("28.8", ref)) + 2f; val sy0 = refBase - ref * CAP / 2f - sym / 2f
        avgSym(c, sx0, sy0, sym, if (d.avgSpeedTrend == 0) SUB else trendCol(d.avgSpeedTrend))
        if (d.avgSpeedTrend != 0) {
            val ax = sx0 + sym + 3f; val aw = 14f; val ah = 16f; val ay = sy0 + (sym - ah) / 2f
            val p = Path()
            if (d.avgSpeedTrend > 0) { p.moveTo(ax + aw / 2f, ay); p.lineTo(ax + aw, ay + ah); p.lineTo(ax, ay + ah) }
            else { p.moveTo(ax, ay); p.lineTo(ax + aw, ay); p.lineTo(ax + aw / 2f, ay + ah) }
            p.close(); fp.color = trendCol(d.avgSpeedTrend); c.drawPath(p, fp)
        }

        // --- dolny wiersz: W' (lewo), MOC | PREDKOSC (przy srodku), BIEG (prawo); wszystko na linii base
        val wb = d.wbalPct
        val wTxt = wb?.toString() ?: "—"
        val wCol = when {
            wb == null -> NONE
            Kokpit2Config.color("wbal", "trend") == "level" -> if (wb > 50) WHITE else if (wb >= 20) YEL else BAD
            d.wbalTrend == "rising" -> GOOD; d.wbalTrend == "falling" || d.wbalTrend == "plummeting" -> BAD; else -> WHITE
        }
        // jak liczba koronki; miejsce liczone na 2 cyfry - "100" (tylko na starcie) zwezone do tej szerokosci
        run { val ww = w(wTxt, 52f); val ref2 = w("88", 52f); if (ww > ref2) tp.textScaleX = ref2 / ww; t(c, wTxt, lx0, base, 52f, wCol); tp.textScaleX = 1f }
        t(c, "W′%", lx0, base - 52f * CAP - 7f, 16f, UNIT, false)
        val wRight = lx0 + maxOf(minOf(w(wTxt, 52f), w("88", 52f)), w("W′%", 16f, false))

        // bieg: maly blat, duza koronka, wyrownany do prawej
        t(c, "BIEG", rx, base - 52f * CAP - 5f, 15f, UNIT, false, Paint.Align.RIGHT)   // nad cyframi biegu, niezaleznie od czcionki
        val gearLeft: Float
        if (d.gearFront != null && d.gearRear != null) {
            var x = rx
            val r1 = d.gearRear.toString(); t(c, r1, x, base, 52f, WHITE, true, Paint.Align.RIGHT); x -= w(r1, 52f)
            t(c, "×", x, base, 20f, UNIT, true, Paint.Align.RIGHT); x -= w("×", 20f)
            val f1 = d.gearFront.toString(); t(c, f1, x, base, 36f, Color.parseColor("#AEB8C4"), true, Paint.Align.RIGHT)   // blat szary - rzadko sie zmienia; x -= w(f1, 36f)
            gearLeft = x
        } else { t(c, "—", rx, base, 52f, NONE, true, Paint.Align.RIGHT); gearLeft = rx - w("—", 52f) }

        // moc i predkosc: zawsze ta sama, najwieksza mozliwa wielkosc (start 90 px)
        val v10 = d.speedKmh?.let { kotlin.math.round(it * 10f).toInt() }
        val sInt = v10?.let { (it / 10).toString() } ?: "—"
        val sDec = v10?.let { "." + (it % 10).toString() } ?: ""
        val pv = d.powerW?.toString() ?: "—"
        val cp = d.cpW; val pw = d.powerW
        val zone = if (pw != null && cp != null && cp > 0f) { val r = pw / cp; var i = 0; for (kk in PZ.indices) if (r >= PZ[kk].first) i = kk; i } else null
        // 1) wielkosc dopasowana do szerokosci, 2) cyfry wyzsze o 8 px (gora tam, gdzie byl piorun+strefa),
        // 3) gdy po powiekszeniu brakuje szerokosci -> cyfry zwezone (textScaleX, min. 0.75), nie nizsze
        // JEDNOSTKI NA STALE (nie ruszaja sie z wartoscia): piorun+strefa przyklejone do W',
        // V km/h przyklejone do biegu (km/h wchodzi nad cyfry blatu)
        val uWRef = unitWWidth(5)
        val wRightRef = lx0 + maxOf(w("88", 52f), w("W′%", 16f, false))
        val gearLeftRef = rx - w("52", 52f) - w("×", 20f) - w("52", 36f)
        val xUnitW = wRightRef + 4f
        val xUnitV = gearLeftRef - 2f - w("V", 22f)
        heavy = true
        // WIELKOSC STALA dla pola: wzorzec najszerszego przypadku (moc 888, predkosc 88.8), nie biezace wartosci
        fun digVRef(sz: Float) = w("88", sz) + w(".8", sz * DEC)
        val availW = (cx - g) - (xUnitW + uWRef + 4f)
        val availV = (xUnitV - 4f) - (cx + g)
        val topRowBottom = capTop + big * CAP
        var vs = (base - topRowBottom - 3f) / CAP   // wielkosc mocy/predkosci bez zmian po obnizeniu gornego wiersza
        while (vs > 44f && minOf(availV / digVRef(vs), availW / w("888", vs)) < 0.80f) vs -= 1f
        vs += 10f  // na sztywno +10 px (decyzja Michala 2026-10-10: +5, +3, +2)
        val sxV0 = minOf(1f, availV / digVRef(vs)).coerceAtLeast(0.70f)
        val sxW0 = minOf(1f, availW / w("888", vs)).coerceAtLeast(0.70f)
        // tylko wartosc szersza niz wzorzec (np. moc 4-cyfrowa) jest dodatkowo zwezona; wielkosc bez zmian
        fun digV(sz: Float) = w(sInt, sz) + (if (sDec.isNotEmpty()) w(sDec, sz * DEC) else 0f)
        val sxV = minOf(sxV0, availV / digV(vs)).coerceAtLeast(0.6f)
        val sxW = minOf(sxW0, availW / w(pv, vs)).coerceAtLeast(0.6f)
        // luz po bokach -> wiekszy odstep miedzy cyframi; wartosc stala dla pola
        val lsW = if (sxW0 >= 1f && sxW >= sxW0) ((availW - w("888", vs)) / (3f * vs)).coerceIn(0f, 0.10f) else 0f
        val lsV = if (sxV0 >= 1f && sxV >= sxV0) ((availV - digVRef(vs)) / (3f * vs)).coerceIn(0f, 0.10f) else 0f
        val top = base - vs * CAP
        val pCol = if (d.powerW == null) NONE else if (Kokpit2Config.color("power", "pacing") == "zones" && zone != null) Color.parseColor(PZ[zone].second) else d.powerColor
        // predkosc zolta (jak w starych polach: domyslny kolor predkosci #F2C230), moc biala - latwo odroznic
        val spCol = if (d.speedKmh == null) NONE else when (Kokpit2Config.color("speed", "cyan")) { "white" -> WHITE; "yellow" -> SPEED; else -> CYAN }   // 0.4.27: cyjan domyslnie
        tp.textScaleX = sxW
        tp.letterSpacing = lsW
        // moc: do srodka z lewej, kolor z oceny tempa (PacingEngine)
        // ALARM (> pulap > 30 s): czerwone tlo pod cyframi, cyfry kontrastowe (bez tla bylyby czarne na ciemnym)
        if (d.powerBg != 0 && d.powerW != null) { val pw0 = w(pv, vs); fp.color = d.powerBg; c.drawRoundRect(RectF(cx - g - pw0 - 5f, top - 5f, cx - g + 4f, base + 4f), 8f, 8f, fp) }
        t(c, pv, cx - g, base, vs, pCol, true, Paint.Align.RIGHT)
        tp.textScaleX = sxV
        tp.letterSpacing = lsV
        // predkosc: od srodka w prawo, czesc dziesietna mniejsza (gora rowno z cyframi)
        var x = cx + g
        t(c, sInt, x, base, vs, spCol); x += w(sInt, vs)
        if (sDec.isNotEmpty()) { val ds = vs * DEC; t(c, sDec, x, top + ds * CAP, ds, spCol) }
        tp.textScaleX = 1f
        tp.letterSpacing = 0f
        heavy = false
        unitW(c, xUnitW, top, base, zone)
        unitV(c, xUnitV, top, base)
    }

    /** szerokosc bloku piorun + numer strefy (W pod spodem jest wezsze) */
    private fun unitWWidth(zone: Int?): Float {
        val bh = 33f
        return bh * 14f / 22f - 1f + (if (zone != null) w((zone + 1).toString(), bh / CAP) * ZSX else 0f)
    }
    private const val ZSX = 0.8f

    /** V nad km/h; prawa krawedz kolumny = right; gora = gorna krawedz cyfr */
    private fun unitV(c: Canvas, left: Float, top: Float, base: Float) {
        // V, a bezposrednio po prawej km nad /h (ta sama wysokosc co V); gora = gorna krawedz cyfr
        t(c, "V", left, top + 22f * CAP, 22f, UNIT, true)
        val kx = left + w("V", 22f) + 1f
        t(c, "km", kx, top + 11f * CAP, 11f, UNIT, false)
        t(c, "/h", kx, top + 22f * CAP, 11f, UNIT, false)
    }

    /** piorun + numer strefy (oba w kolorze strefy), pod nimi W; lewa krawedz = left; gora = gorna krawedz cyfr */
    private fun unitW(c: Canvas, left: Float, top: Float, base: Float, zone: Int?) {
        val bh = 33f
        val zc = if (zone != null) Color.parseColor(PZ[zone].second) else UNIT
        bolt(c, left, top, bh, zc)
        if (zone != null) { tp.textScaleX = ZSX; t(c, (zone + 1).toString(), left + bh * 14f / 22f - 1f, top + bh, bh / CAP, zc); tp.textScaleX = 1f }
        t(c, "W", left + unitWWidth(zone), base, 17f, UNIT, false, Paint.Align.RIGHT)   // W tuz przy cyfrach mocy
    }

    private fun routeBar(c: Canvas, rb: RouteBar, l: Float, r: Float, top: Float, bot: Float, vw: Float) {
        fp.color = TRACK; c.drawRect(l, top, r, bot, fp)
        val total = rb.totalKm ?: return
        if (total <= 0f) return
        val frac = (rb.doneKm / total).coerceIn(0f, 1f)
        val fx = l + (r - l) * frac
        fp.color = DONE; c.drawRect(l, top, fx, bot, fp)
        val ahead = rb.ahead
        if (ahead != null && ahead.isNotEmpty()) {
            val sum = ahead.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.001f)
            var x = fx
            for ((len, col) in ahead) {
                val ww = (r - fx) * (len / sum)
                fp.color = col
                c.drawRect(x + 0.5f, top, x + ww - 0.5f, bot, fp)
                x += ww
            }
        }
        for (km in rb.stopsKm) {
            val sx = l + (r - l) * (km / total).coerceIn(0f, 1f)
            fp.color = STOP; c.drawRect(sx - 2.5f, top, sx + 2.5f, bot, fp)
        }
        // pozycja: limonkowe kolo z czarna obwodka
        val mx = fx.coerceIn(10f, vw - 10f); val my = (top + bot) / 2f
        fp.color = POS; c.drawCircle(mx, my, 9f, fp)
        sp.color = BLACK; sp.strokeWidth = 2f; c.drawCircle(mx, my, 9f, sp)
    }

    private fun heart(c: Canvas, x: Float, y: Float, wd: Float, color: Int) {
        val k = wd / 38f
        val p = Path()
        p.moveTo(x + 19 * k, y + 33 * k); p.lineTo(x + 5 * k, y + 18 * k)
        p.cubicTo(x - 1 * k, y + 11 * k, x + 3 * k, y + 2 * k, x + 10 * k, y + 2 * k)
        p.cubicTo(x + 14 * k, y + 2 * k, x + 17 * k, y + 5 * k, x + 19 * k, y + 8 * k)
        p.cubicTo(x + 21 * k, y + 5 * k, x + 24 * k, y + 2 * k, x + 28 * k, y + 2 * k)
        p.cubicTo(x + 35 * k, y + 2 * k, x + 39 * k, y + 11 * k, x + 33 * k, y + 18 * k)
        p.close(); fp.color = color; c.drawPath(p, fp)
    }

    private fun avgSym(c: Canvas, x: Float, y: Float, sz: Float, color: Int) {
        sp.color = color; sp.strokeWidth = sz * 0.16f
        c.drawCircle(x + sz / 2f, y + sz / 2f, sz * 0.325f, sp)
        c.drawLine(x + sz * 0.15f, y + sz * 0.85f, x + sz * 0.85f, y + sz * 0.15f, sp)
    }

    private fun bolt(c: Canvas, x: Float, y: Float, h: Float, color: Int) {
        val k = h / 22f
        val p = Path()
        p.moveTo(x + 8 * k, y); p.lineTo(x + 0 * k, y + 13 * k); p.lineTo(x + 6 * k, y + 13 * k)
        p.lineTo(x + 4 * k, y + 22 * k); p.lineTo(x + 14 * k, y + 8 * k); p.lineTo(x + 8 * k, y + 8 * k); p.close()
        fp.color = color; c.drawPath(p, fp)
    }
}

/* ============================== gorne pole: nav ============================== */

object Kokpit2NavRenderer {
    private val BG = Color.parseColor("#14181D")
    private val MSG_INFO = Color.parseColor("#1E2731")
    private val MSG_WARN = Color.parseColor("#FFC21A")
    private val MSG_CRIT = Color.parseColor("#8B0A1A")
    private val LBL = Color.parseColor("#AEB8C4")
    private val UNIT = Color.parseColor("#9AA5B1")
    private val NONE = Color.parseColor("#9AA3AE")
    private val WHITE = Color.WHITE
    private val BLACK = Color.BLACK
    private val RED = Color.parseColor("#FF8C8C")
    private val GREEN = Color.parseColor("#4ADE80")
    private val BLUE = Color.parseColor("#60A5FA")
    private val ORANGE = Color.parseColor("#FB923C")

    private val bold: Typeface get() = Kokpit2Fonts.semi   // Saira Semi Condensed SemiBold
    private val reg: Typeface get() = Kokpit2Fonts.reg
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private fun fmt(p: String, vararg a: Any): String = String.format(java.util.Locale.US, p, *a)

    private fun t(c: Canvas, s: String, x: Float, base: Float, size: Float, color: Int, b: Boolean = true, align: Paint.Align = Paint.Align.LEFT) {
        tp.typeface = if (b) bold else reg; tp.textSize = size; tp.color = color; tp.textAlign = align
        c.drawText(s, x, base, tp)
        tp.textAlign = Paint.Align.LEFT
    }
    private fun w(s: String, size: Float, b: Boolean = true): Float { tp.typeface = if (b) bold else reg; tp.textSize = size; return tp.measureText(s) }

    /** element wiersza: szerokosc + rysowanie od x (linia bazowa wiersza znana w srodku) */
    private class Item(val width: Float, val draw: (Float) -> Unit)

    private fun txt(s: String, size: Float, color: Int, base: Float, b: Boolean = true) =
        Item(w(s, size, b)) { x -> t(cv!!, s, x, base, size, color, b) }

    private var cv: Canvas? = null

    @Synchronized
    fun render(width: Int, height: Int, d: KokpitNavData): Bitmap {
        val W = width.coerceAtLeast(160).toFloat(); val H = height.coerceAtLeast(60).toFloat()
        val bmp = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        cv = c
        c.drawColor(BG)
        val k = minOf(H / 126f, W / 474f)
        val vw = W / k
        // komunikat zawsze na samej gorze, wiersze na dole (nadmiar wysokosci miedzy nimi)
        val ext = (H / k - 126f).coerceAtLeast(0f)
        c.save(); c.scale(k, k); drawMsg(c, d, vw, 41f + ext); c.restore()   // nadmiar wysokosci -> wiekszy komunikat
        c.save(); c.translate(0f, H - 126f * k); c.scale(k, k)
        rowWeather(c, d, vw, 76f)
        rowKm(c, d, vw, 122f)
        c.restore()
        cv = null
        return bmp
    }

    private fun place(items: List<List<Item>>, left: Float, right: Float, inner: Float = 4f) {
        val widths = items.map { g -> g.sumOf { it.width.toDouble() }.toFloat() + inner * (g.size - 1) }
        val total = widths.sum()
        val gap = if (items.size > 1) ((right - left - total) / (items.size - 1)).coerceAtLeast(6f) else 0f
        var x = left
        for ((gi, g) in items.withIndex()) {
            for ((i, item) in g.withIndex()) { if (i > 0) x += inner; item.draw(x); x += item.width }
            if (gi < items.size - 1) x += gap
        }
    }

    /** 0.4.27: sygnalizator kamery w pasku komunikatow przy lewej krawedzi: stale czarne tlo, duza kropka, pod nia male REC
     *  (czerwone = nagrywa, szare = polaczona); tekst komunikatu zaczyna sie za nim. Zwraca x poczatku tekstu. */
    private fun recStrip(c: Canvas, d: KokpitNavData, h: Float): Float {
        if (d.camRec <= 0) return 8f
        val col = if (d.camRec == 2) Color.parseColor("#EF4444") else Color.parseColor("#6B7280")
        val ls = 12f; val pitch = 10f; val r0 = 9f; val tot = 2f * r0 + 3f + 3f * pitch; val top = (h - tot) / 2f
        fp.color = BLACK; c.drawRect(0f, 0f, 24f, h, fp)
        fp.color = col; c.drawCircle(12f, top + r0, r0, fp)
        for ((i, ch) in listOf("R", "E", "C").withIndex()) t(c, ch, 12f, top + 2f * r0 + 3f + ls * CAP + i * pitch, ls, col, true, Paint.Align.CENTER)
        return 30f
    }

    // ---------- komunikat: tlo wg waznosci ----------
    private fun drawMsg(c: Canvas, d: KokpitNavData, vw: Float, h: Float) {
        val m = d.msg
        // poziom (tlo) z ustawien QBota; centrala wg waznosci nadawcy
        val lvl = when (m.kind) {
            MsgKind.HUB -> if (m.accentColor.equals("#FF8C8C", ignoreCase = true)) "crit" else "warn"
            MsgKind.NONE -> "info"
            else -> Kokpit2Config.level(m.cfgId, when (m.kind) { MsgKind.WPRIME, MsgKind.DESCENT -> "crit"; MsgKind.RAIN, MsgKind.FUEL, MsgKind.DUSK -> "warn"; else -> "info" })
        }
        val crit = lvl == "crit"
        val warn = lvl == "warn"
        val bg = when { crit -> MSG_CRIT; warn -> MSG_WARN; else -> MSG_INFO }
        val fg = if (warn) BLACK else WHITE
        fp.color = bg; c.drawRect(0f, 0f, vw, h, fp)
        var x = recStrip(c, d, h)
        val demoW = if (d.demo) w("DEMO", 18f) + 8f else 0f
        val right = vw - 8f - demoW
        val leadCol = if (crit || warn) fg else if (m.kind == MsgKind.NONE) UNIT else WHITE
        // 16.10.2026: stala, duza czcionka. Gdy tekst sie nie miesci: 1) bez szarego podpisu (o ile jest tresc), 2) najwyzej do 26 px, 3) koniec "…"
        val s0 = (31f * h / 41f).coerceAtMost(40f)
        var lead = m.lead
        var accent = m.accent
        fun total(sz: Float): Float = (if (lead.isNotEmpty()) w(lead, sz, false) + sz * 0.3f else 0f) + (if (accent.isNotEmpty()) w(accent, sz) else 0f)
        var s = s0
        if (x + total(s) > right && lead.isNotEmpty() && accent.isNotEmpty() && lead.none { it.isDigit() }) lead = ""   // podpis z liczba (km, %, min) zostaje
        while (s > 26f && x + total(s) > right) s -= 1f
        while (x + total(s) > right && accent.length > 2) accent = accent.dropLast(2) + "…"
        val base = h / 2f + s * CAP / 2f
        if (lead.isNotEmpty()) { t(c, lead, x, base, s, leadCol, false); x += w(lead, s, false) + s * 0.3f }
        if (accent.isNotEmpty()) t(c, accent, x, base, s, fg)
        if (d.demo) t(c, "DEMO", vw - 8f, h / 2f + 18f * CAP / 2f, 18f, if (warn) BLACK else ORANGE, true, Paint.Align.RIGHT)
    }

    // ---------- wiersz pogody: temp + opad/niebo | wiatr | nachylenie ----------
    private fun gw(g: List<Item>, inner: Float) = g.sumOf { it.width.toDouble() }.toFloat() + inner * (g.size - 1)
    private fun drawG(g: List<Item>, x0: Float, inner: Float) { var x = x0; for ((i, item) in g.withIndex()) { if (i > 0) x += inner; item.draw(x); x += item.width } }

    // ---------- gorny wiersz (zmienne): temperatura | pogoda | manewr | nachylenie - STALE KOLUMNY ----------
    private class G(val items: List<Item>, val ref: Float, val inner: Float)

    /** wiersz z kolumn o stalych szerokosciach (wzorce najszerszych wartosci): pierwsza do lewej, ostatnia do prawej, srodkowe rowno */
    private fun row(gs: List<G>, vw: Float) {
        val gap = ((vw - 16f) - gs.sumOf { it.ref.toDouble() }.toFloat()) / (gs.size - 1).coerceAtLeast(1)
        var x = 8f
        for ((i, g) in gs.withIndex()) {
            if (i == gs.size - 1) drawG(g.items, vw - 8f - gw(g.items, g.inner), g.inner)
            else { drawG(g.items, x, g.inner); x += g.ref + gap }
        }
    }
    private fun rowGap(gs: List<G>, vw: Float) = ((vw - 16f) - gs.sumOf { it.ref.toDouble() }.toFloat()) / (gs.size - 1).coerceAtLeast(1)

    private fun gDst(c: Canvas, d: KokpitNavData, base: Float, vs: Float): G {
        val capH = vs * CAP
        val d10 = kotlin.math.floor(d.doneKm.coerceAtLeast(0f) * 10f).toInt()
        val dDec = "." + (d10 % 10).toString()
        val dds = vs * 0.55f
        val g = listOf(vlabel(c, "DST", base, capH), txt((d10 / 10).toString(), vs, WHITE, base),
            Item(w(dDec, dds)) { x -> t(c, dDec, x, base - capH + dds * CAP, dds, WHITE) })
        return G(g, 10f + 2f + w("888", vs) + 2f + w(".8", dds), 2f)
    }

    private fun gTemp(c: Canvas, d: KokpitNavData, base: Float, vs: Float): G {
        val capH = vs * CAP
        val g = listOf(Item(13f) { x -> thermo(c, x, base, 13f, capH, LBL) },
            if (d.tempC != null) txt(fmt("%.0f", d.tempC) + "°", vs, WHITE, base) else txt("—", vs, NONE, base))
        return G(g, 13f + 4f + w("-18°", vs), 4f)
    }

    /** 0.4.27: sila opadu = 1-3 kropelki (slaby < 2,5 / umiarkowany < 7,6 / ulewa mm/h) */
    private fun rainLevel(mm: Float?): Int = when { mm == null -> 1; mm < 2.5f -> 1; mm < 7.6f -> 2; else -> 3 }
    private fun drops(c: Canvas, x: Float, base: Float, n: Int, col: Int) { for (i in 0 until n) drop2(c, x + i * 12f, base - (i % 2) * 3f, 11f, 22f, col) }

    /** 0.4.27: kropla: spiczasty czubek, pelny brzuszek, gradient od jasnego i odblask */
    private fun drop2(c: Canvas, x: Float, base: Float, wI: Float, hI: Float, col: Int) {
        val cx = x + wI / 2f; val top = base - hI
        val p = Path(); p.moveTo(cx, top)
        p.cubicTo(cx + wI * 0.18f, top + hI * 0.28f, cx + wI * 0.5f, top + hI * 0.5f, cx + wI * 0.5f, top + hI * 0.7f)
        p.arcTo(RectF(cx - wI * 0.5f, top + hI * 0.7f - wI * 0.5f, cx + wI * 0.5f, top + hI * 0.7f + wI * 0.5f), 0f, 180f, false)
        p.cubicTo(cx - wI * 0.5f, top + hI * 0.5f, cx - wI * 0.18f, top + hI * 0.28f, cx, top)
        p.close()
        fp.shader = LinearGradient(0f, top, 0f, base, Color.parseColor("#DBEAFE"), col, Shader.TileMode.CLAMP)
        c.drawPath(p, fp); fp.shader = null
        fp.color = Color.argb(204, 255, 255, 255)
        val hx = cx - wI * 0.2f; val hy = top + hI * 0.68f
        c.save(); c.rotate(-17f, hx, hy)
        c.drawOval(RectF(hx - wI * 0.09f, hy - hI * 0.12f, hx + wI * 0.09f, hy + hI * 0.12f), fp)
        c.restore()
    }

    /** szerokosc kolumny pogody (stala): 3 kropelki + minuty + szansa */
    private fun wxRef(): Float = 36f + 3f + w("88′", 20f) + 3f + w("88%", 15f, false)

    /** 0.4.27: pogoda w JEDNYM wierszu: kropelki + minuty + szansa % (radar: same minuty); bez opadu ikona nieba na srodku kolumny */
    private fun gWx(c: Canvas, d: KokpitNavData, base: Float, vs: Float): G {
        val capH = vs * CAP
        val wx = ArrayList<Item>()
        val rn = d.rainNowMmH; val rs = d.rainSoon
        val ref = wxRef()
        if (rn != null && rn >= 0.1f) {
            val n1 = rainLevel(rn)
            wx.add(Item(12f * n1) { x -> drops(c, x, base, n1, BLUE) })
            wx.add(txt("teraz", 22f, BLUE, base, false))
        } else if (rs != null && (rs.radar || (rs.probPct >= 30 && rs.kind != "FOG"))) {
            val cl = when (rs.kind) { "STORM" -> RED; "SNOW" -> Color.parseColor("#BFDBFE"); else -> BLUE }
            when (rs.kind) {
                "STORM" -> wx.add(Item(34f) { x -> storm(c, x, base, 34f, 32f, cl) })
                "SNOW" -> wx.add(Item(30f) { x -> snow(c, x, base, 30f, cl) })
                else -> { val n2 = rainLevel(rs.mmPerH); wx.add(Item(12f * n2) { x -> drops(c, x, base, n2, cl) }) }
            }
            wx.add(txt("${rs.minutes}′", 20f, cl, base))
            if (!rs.radar) wx.add(txt("${rs.probPct}%", 15f, cl, base, false))
        } else d.sky?.let { sk ->
            val iw = if (sk == "CLEAR") 44f else 66f; val ox = (ref - iw) / 2f
            when (sk) {
                "CLEAR" -> wx.add(Item(ref) { x -> sun(c, x + ox + 22f, base - capH / 2f, 11f, Color.parseColor("#FACC15")) })
                "PARTLY" -> wx.add(Item(ref) { x ->
                    sun(c, x + ox + 20f, base - capH / 2f - 2f, 9f, Color.parseColor("#FACC15"))
                    cloud(c, x + ox + 8f, base - capH * 0.92f, 58f, capH * 1.0f, Color.parseColor("#E5E7EB"))
                })
                "FOG" -> wx.add(Item(ref) { x -> fog(c, x + ox, base, 66f, capH * 1.15f, UNIT) })
                else -> wx.add(Item(ref) { x -> cloud(c, x + ox, base - capH * 1.0f, 66f, capH * 1.1f, Color.parseColor("#C9D2DC")) })
            }
        }
        if (rs?.cached == true && wx.isNotEmpty()) wx.add(txt("P", 13f, UNIT, base, false))   // z zapasu prognozy (brak sieci)
        return G(wx, ref, 3f)
    }

    private fun gWind(c: Canvas, d: KokpitNavData, base: Float, vs: Float): G {
        val capH = vs * CAP
        val wm = d.windMps
        val ref = 24f + 4f + w("88", vs) + 4f + 20f * capH / 28f   // miejsce na wiatr 2-cyfrowy
        if (wm == null) return G(listOf(txt("wiatr —", 20f, NONE, base, false)), ref, 4f)
        val hs = d.windSignedMps
        val wMin = Kokpit2Config.cp("wind", "min_mps", 3.0).toFloat()
        val wShare = (Kokpit2Config.cp("wind", "share", 70.0) / 100.0).toFloat()
        val wcol = if (hs == null || Kokpit2Config.color("wind", "headtail") == "white") WHITE else {
            val tot = maxOf(d.windTotalMps ?: abs(hs), abs(hs))
            when { hs >= wMin && hs >= wShare * tot -> RED; hs <= -wMin && -hs >= wShare * tot -> GREEN; else -> WHITE }
        }
        val wg = ArrayList<Item>()
        d.windRelDeg?.let { rel -> wg.add(Item(24f) { x -> arrow(c, x + 12f, base - capH / 2f, 26f, rel.toFloat(), wcol) }) }
        wg.add(txt(fmt("%.0f", wm), vs, WHITE, base))
        wg.add(Item(20f * capH / 28f) { x -> msUnit(c, x, base, capH) })
        if (d.windCached) wg.add(txt("P", 13f, UNIT, base, false))   // z zapasu prognozy (brak sieci)
        return G(wg, ref, 4f)
    }

    /** 0.4.27: AERO -- logo "CdA" z liter po skosie (C wyzej, d, A nizej), kolor logo = pewnosc */
    private fun cdaLogo(c: Canvas, base: Float, capH: Float, col: Int): Item = Item(21f) { x ->
        val ls = 16f; val top = base - capH
        t(c, "C", x, top + ls * CAP, ls, col)
        t(c, "d", x + 7f, top + capH * 0.5f + ls * CAP / 2f, ls, col)
        t(c, "A", x + 13f, base, ls, col)
    }

    /** 0.4.27: AERO = 4. pozycja gornego wiersza (zielone logo = liczy sie, zolte = zbiera dane, szare = brak), male W */
    private fun gAero(c: Canvas, d: KokpitNavData, base: Float, vs: Float): G {
        val capH = vs * CAP; val av = d.aero
        val ref = 21f + 2f + w("−18", vs) + 2f + w("W", 14f, false)
        val lc = when { av == null || av.conf < 0 -> LBL; av.conf == 1 -> GREEN; else -> Color.parseColor("#FACC15") }
        val g = arrayListOf(cdaLogo(c, base, capH, lc), if (av != null) txt(av.value, vs, WHITE, base) else txt("—", vs, NONE, base))
        if (av != null) g.add(txt("W", 14f, UNIT, base, false))
        return G(g, ref, 2f)
    }

    private fun lum(col: Int): Double {
        fun f(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * f(Color.red(col)) + 0.7152 * f(Color.green(col)) + 0.0722 * f(Color.blue(col))
    }

    /** 0.4.27 (decyzja Michala: od +-3%): cala kratka nachylenia w kolorze nachylenia, cyfry kontrastowe */
    private fun gGrade(c: Canvas, d: KokpitNavData, base: Float, vs: Float): G {
        val capH = vs * CAP
        val gr = d.gradePct
        val ref = 26f + 4f + w("-18", vs) + 4f + w("%", 20f, false)
        if (gr == null) return G(listOf(Item(26f) { x -> tri(c, x, base, 26f, capH, 3f, NONE) }, txt("—", vs, NONE, base)), ref, 4f)
        val shade = abs(gr) >= 3f
        val bg = gradeColor(gr)
        val fg = if (shade) (if (lum(bg) > 0.3) BLACK else WHITE) else WHITE
        val g = ArrayList<Item>()
        if (shade) g.add(Item(0f) { x -> fp.color = bg; c.drawRoundRect(RectF(x - 6f, base - capH - 9f, x + 600f, base + 10f), 6f, 6f, fp) })
        g.add(Item(26f) { x -> tri(c, x, base, 26f, capH, gr, if (shade) fg else bg) })
        g.add(txt(fmt("%.0f", gr), vs, fg, base))
        g.add(txt("%", 20f, if (shade) fg else UNIT, base, false))
        return G(g, ref, 4f)
    }

    /** 0.4.27: plakietka zrodla trasy: Q (biala) = trase zna QBot, K (obrys) = tylko Karoo */
    private fun srcBadge(c: Canvas, src: String, base: Float, capH: Float): Item = Item(18f) { x ->
        val q = src == "Q"; val top = base - capH
        fp.color = if (q) Color.parseColor("#F2F4F7") else BG
        c.drawRoundRect(RectF(x, top, x + 18f, base), 4f, 4f, fp)
        if (!q) { sp.color = UNIT; sp.strokeWidth = 2f; c.drawRoundRect(RectF(x + 1f, top + 1f, x + 17f, base - 1f), 4f, 4f, sp) }
        t(c, src, x + 9f, base - (capH - 16f * CAP) / 2f, 16f, if (q) BLACK else LBL, true, Paint.Align.CENTER)
    }

    /** 0.4.27: DTD z plakietka Q/K; bez trasy DOM (km do domu w linii prostej) */
    private fun gDtd(c: Canvas, d: KokpitNavData, base: Float, vs: Float): G {
        val capH = vs * CAP
        val ref = 18f + 3f + 10f + 3f + w("888", vs)
        val hk = d.homeKm
        if (d.leftKm == null && hk != null) return G(listOf(vlabel(c, "DOM", base, capH), txt(fmt("%.0f", hk), vs, WHITE, base)), ref, 3f)
        val g = ArrayList<Item>()
        d.routeSrc?.let { g.add(srcBadge(c, it, base, capH)) }
        g.add(vlabel(c, "DTD", base, capH))
        val lk = d.leftKm
        g.add(if (lk != null) txt(fmt("%.0f", lk), vs, WHITE, base) else txt("—", vs, NONE, base))
        return G(g, ref, 3f)
    }

    private fun sunDown(c: Canvas, x: Float, base: Float, sz: Float, col: Int) {
        val cy = base - sz * 0.22f; val r = sz * 0.32f
        fp.color = col
        c.drawArc(RectF(x + sz * 0.4f - r, cy - r, x + sz * 0.4f + r, cy + r), 180f, 180f, true, fp)
        c.drawRect(x, cy, x + sz * 0.8f, cy + sz * 0.09f, fp)
        val p = Path(); p.moveTo(x + sz * 0.66f, base - sz * 0.95f); p.lineTo(x + sz, base - sz * 0.95f); p.lineTo(x + sz * 0.83f, base - sz * 0.62f); p.close()
        c.drawPath(p, fp)
    }

    private fun clockIco(c: Canvas, x: Float, base: Float, sz: Float, col: Int) {
        val cx = x + sz / 2f; val cy = base - sz / 2f
        sp.color = col; sp.strokeWidth = sz * 0.11f
        c.drawCircle(cx, cy, sz * 0.44f, sp)
        val p = Path(); p.moveTo(cx, cy - sz * 0.28f); p.lineTo(cx, cy); p.lineTo(cx + sz * 0.22f, cy + sz * 0.1f)
        c.drawPath(p, sp)
    }

    /** 0.4.27: ETA; bez trasy -- odliczanie do zmroku (gdy <= 3 h), inaczej zegar */
    private fun gEta(c: Canvas, d: KokpitNavData, base: Float, vs: Float): G {
        val capH = vs * CAP
        val eta = d.etaMs; val dl = d.deadlineMs
        val ref = 10f + 2f + w("88:88", vs)
        val now = d.nowMs
        if (eta == null && d.leftKm == null && now != null) {
            val du = d.duskMs
            val left = if (du != null && d.twilightLabel == "zmrok") (du - now) / 60_000L else -1L
            if (left in 1L..180L) {
                val hm = "${left / 60}:" + (left % 60).toString().padStart(2, '0')
                return G(listOf(Item(24f) { x -> sunDown(c, x, base, 24f, ORANGE) }, txt(hm, vs, ORANGE, base)), ref, 3f)
            }
            return G(listOf(Item(22f) { x -> clockIco(c, x, base - 2f, 22f, LBL) }, txt(clock(now), vs, WHITE, base)), ref, 3f)
        }
        val etaCol = if (eta != null && dl != null) when {
            eta > dl -> RED
            dl - eta >= Kokpit2Config.cp("eta", "green_min", 30.0).toLong() * 60_000L -> GREEN
            dl - eta <= Kokpit2Config.cp("eta", "yellow_min", 10.0).toLong() * 60_000L -> Color.parseColor("#FACC15")
            else -> WHITE
        } else WHITE
        val g = if (eta != null) listOf(vlabel(c, "ETA", base, capH), txt(clock(eta), vs, etaCol, base))
            else listOf(vlabel(c, "ETA", base, capH), txt("—", vs, NONE, base))
        return G(g, ref, 2f)
    }

    /** 0.4.27: ikony pola dynamicznego zamiast etykiet */
    private fun icoBasket(c: Canvas, x: Float, base: Float, sz: Float, col: Int) {
        val top = base - sz * 0.62f
        val p = Path(); p.moveTo(x, top); p.lineTo(x + sz, top); p.lineTo(x + sz * 0.84f, base); p.lineTo(x + sz * 0.16f, base); p.close()
        fp.color = col; c.drawPath(p, fp)
        sp.color = col; sp.strokeWidth = sz * 0.1f
        c.drawArc(RectF(x + sz / 2f - sz * 0.32f, top - sz * 0.32f, x + sz / 2f + sz * 0.32f, top + sz * 0.32f), 180f, 180f, false, sp)
        fp.color = BG
        for (i in 0 until 3) { val bx = x + sz * (0.3f + 0.2f * i); c.drawRect(bx - sz * 0.04f, top + sz * 0.12f, bx + sz * 0.04f, top + sz * 0.46f, fp) }
    }

    private fun icoFood(c: Canvas, x: Float, base: Float, sz: Float, col: Int) {
        val top = base - sz; val fx = x + sz * 0.3f; val kx = x + sz * 0.75f
        sp.color = col; sp.strokeWidth = sz * 0.11f
        c.drawLine(fx, top + sz * 0.4f, fx, base, sp)
        for (o in floatArrayOf(-0.14f, 0f, 0.14f)) c.drawLine(fx + o * sz, top, fx + o * sz, top + sz * 0.36f, sp)
        c.drawLine(fx - 0.15f * sz, top + sz * 0.38f, fx + 0.15f * sz, top + sz * 0.38f, sp)
        c.drawLine(kx, top + sz * 0.5f, kx, base, sp)
        val p = Path(); p.moveTo(kx - sz * 0.06f, top + sz * 0.56f); p.quadTo(kx - sz * 0.08f, top, kx + sz * 0.12f, top); p.lineTo(kx + sz * 0.07f, top + sz * 0.56f); p.close()
        fp.color = col; c.drawPath(p, fp)
    }

    private fun icoAero(c: Canvas, x: Float, base: Float, sz: Float, col: Int) {
        val r = sz * 0.2f; val y = base - r
        sp.color = col; sp.strokeWidth = sz * 0.09f
        c.drawCircle(x + r, y, r, sp); c.drawCircle(x + sz * 1.2f - r, y, r, sp)
        val p = Path(); p.moveTo(x + r, y); p.lineTo(x + sz * 0.55f, y); p.lineTo(x + sz * 1.2f - r, y - sz * 0.05f); c.drawPath(p, sp)
        c.drawLine(x + sz * 0.4f, y - sz * 0.45f, x + sz * 0.95f, y - sz * 0.35f, sp)
        fp.color = col; c.drawCircle(x + sz * 1.05f, y - sz * 0.42f, sz * 0.11f, fp)
    }

    private fun avgSymNav(c: Canvas, x: Float, y: Float, sz: Float, color: Int) {
        sp.color = color; sp.strokeWidth = sz * 0.16f
        c.drawCircle(x + sz / 2f, y + sz / 2f, sz * 0.325f, sp)
        c.drawLine(x + sz * 0.15f, y + sz * 0.85f, x + sz * 0.85f, y + sz * 0.15f, sp)
    }

    /** pole dynamiczne (2026-10-14): rotacja co 4 s albo wymagana srednia, gdy ETA blisko granicy.
     *  0.4.27: wartosci wielkosci ETA, ikony zamiast etykiet (koszyk, sztucce, srednia, kropla) */
    private fun gDyn(c: Canvas, d: KokpitNavData, base: Float, vs: Float): G {
        val ds = vs; val capH = ds * CAP; val us = 16f; val ic = 30f
        val ref = ic + 3f + w("88,8", ds) + 3f + w("km/h", us, false)
        val di = d.dyn ?: return G(emptyList(), ref, 3f)
        val col = when (di.warn) { 2 -> RED; 1 -> Color.parseColor("#FACC15"); else -> WHITE }
        val g = ArrayList<Item>()
        when (di.kind) {
            "ATD", "ASC" -> g.add(vlabel(c, di.kind, base, capH))
            "WIND" -> g.add(Item(24f) { x -> arrow(c, x + 12f, base - capH / 2f, 24f, 180f, RED) })
            "water" -> g.add(Item(18f) { x -> drop2(c, x + 1f, base, 16f, 26f, Color.parseColor("#3B82F6")) })
            "AVG" -> g.add(Item(ic) { x -> avgSymNav(c, x, base - ic + 2f, ic - 2f, LBL) })
            "AERO" -> g.add(Item(ic * 1.2f) { x -> icoAero(c, x, base, ic, LBL) })
            "shop" -> g.add(Item(ic) { x -> icoBasket(c, x, base, ic, LBL) })
            else -> g.add(Item(ic) { x -> icoFood(c, x, base, ic, LBL) })
        }
        g.add(txt(di.value, ds, col, base))
        g.add(txt(di.unit, us, UNIT, base, false))
        if (di.conf >= 0) g.add(Item(12f) { x ->
            fp.color = Color.parseColor(if (di.conf == 1) "#4ADE80" else "#FACC15"); c.drawCircle(x + 6f, base - capH / 2f, 5f, fp) })
        return G(g, ref, 3f)
    }

    /** 0.4.27: gorny wiersz -- STALA wielkosc 36 px, 5 pozycji w stalych slotach (wzorce najszerszych wartosci, wiatr 2-cyfrowy):
     *  DST | TEMP | POGODA (na srodku slotu) | AERO | WIATR (do prawej slotu); odstepy 2 px, calosc wysrodkowana w polu */
    private fun rowWeather(c: Canvas, d: KokpitNavData, vw: Float, base: Float) {
        val tv = 36f
        val gs = listOf(gDst(c, d, base, tv), gTemp(c, d, base, tv), gWx(c, d, base, tv), gAero(c, d, base, tv), gWind(c, d, base, tv))
        val gap = 2f
        val tot = gs.sumOf { it.ref.toDouble() }.toFloat() + gap * (gs.size - 1)
        var x = 8f + maxOf(0f, (vw - 16f - tot) / 2f)
        for ((i, g) in gs.withIndex()) {
            val iw = gw(g.items, g.inner)
            val ox = when (i) { 2 -> (g.ref - iw) / 2f; gs.size - 1 -> g.ref - iw; else -> 0f }
            drawG(g.items, x + maxOf(0f, ox), g.inner)
            x += g.ref + gap
        }
    }

    /** logo QBot (jak favicon.svg serwisu): pomaranczowe Q z linia tetna, bez tla; kwadrat sz x sz, lewy-gorny rog (x, y) */
    private fun qlogo(c: Canvas, x: Float, y: Float, sz: Float) {
        val k = sz / 48f
        val o = Color.parseColor("#FF7A00")
        sp.color = o; sp.strokeCap = Paint.Cap.ROUND; sp.strokeJoin = Paint.Join.ROUND
        sp.strokeWidth = 5f * k
        c.drawCircle(x + 24f * k, y + 24f * k, 17f * k, sp)
        c.drawLine(x + 34f * k, y + 34f * k, x + 42f * k, y + 42f * k, sp)
        sp.strokeWidth = 3.5f * k
        val pts = floatArrayOf(12f, 27f, 16f, 27f, 19f, 23f, 23f, 29f, 27f, 16f, 31f, 30f, 34f, 24f, 37f, 24f)
        val path = Path(); path.moveTo(x + pts[0] * k, y + pts[1] * k)
        var i = 2; while (i < pts.size) { path.lineTo(x + pts[i] * k, y + pts[i + 1] * k); i += 2 }
        c.drawPath(path, sp)
        fp.color = o; c.drawCircle(x + 39f * k, y + 24f * k, 2.5f * k, fp)
        sp.strokeJoin = Paint.Join.MITER
    }

    /** 0.4.27: dolny wiersz -- 4 komorki o stalych szerokosciach (wzorce), rowne odstepy; wartosci na srodku komorki,
     *  nachylenie przy prawej krawedzi (tlo nachylenia do krawedzi pola) */
    private fun rowKm(c: Canvas, d: KokpitNavData, vw: Float, base: Float) {
        var vs = 46f
        fun build(v: Float) = listOf(gDtd(c, d, base, v), gEta(c, d, base, v), gDyn(c, d, base, v), gGrade(c, d, base, v))
        var gs = build(vs)
        while (vs > 38f && rowGap(gs, vw) < 8f) { vs -= 1f; gs = build(vs) }
        val gap = rowGap(gs, vw)
        var x = 8f
        for ((i, g) in gs.withIndex()) {
            val iw = gw(g.items, g.inner)
            val ox = if (i == gs.size - 1) g.ref - iw else (g.ref - iw) / 2f
            drawG(g.items, x + maxOf(0f, ox), g.inner)
            x += g.ref + gap
        }
    }

    /** znak nastepnego manewru: ikona + odleglosc (ta sama wielkosc co wartosci wiersza); szerokosc stala - uklad nie skacze */
    private fun turnItem(c: Canvas, d: KokpitNavData, base: Float, vs0: Float, maxW: Float = Float.MAX_VALUE): Item {
        // wielkosc dopasowana raz do stalej kolumny (wzorzec 888 km) - nie zalezy od biezacej wartosci
        fun fw(sz: Float) = 28f * sz / vs0 + 6f + w("888", sz) + 2f + w("km", 16f * sz / vs0, false)
        var vs = vs0
        while (vs > 24f && fw(vs) > maxW) vs -= 1f
        val iconS = 28f * vs / vs0
        val us = 16f * vs / vs0
        val fullW = fw(vs)
        val kind = d.turnKind; val dist = d.turnDistM
        if (kind == null || dist == null) return Item(fullW) { }
        val warn = Kokpit2Config.color("turn", "sharp_warn") == "sharp_warn" && (kind == "UTURN" || kind == "RB_U" || kind.startsWith("SHARP"))
        val col = if (warn) MSG_WARN else WHITE
        val num: String; val unit: String
        if (dist < 950.0) {
            val m = if (dist < 100.0) (Math.round(dist / 5.0) * 5L) else (Math.round(dist / 10.0) * 10L)
            num = m.toString(); unit = "m"
        } else { num = fmt("%.1f", dist / 1000.0).replace('.', ','); unit = "km" }
        return Item(fullW) { x ->
            turnIcon(c, kind, x, base - iconS + 1f, iconS, col)
            t(c, num, x + iconS + 6f, base, vs, col)   // 6 px miedzy ikona a dystansem
            t(c, unit, x + iconS + 6f + w(num, vs) + 2f, base, us, UNIT, false)
        }
    }

    /** ikona manewru w kwadracie s x s (siatka 32), lewy-gorny rog (x, y) */
    private fun turnIcon(c: Canvas, kind: String, x: Float, y: Float, s: Float, col: Int) {
        val k = s / 32f
        sp.color = col; sp.strokeWidth = 4f * k; sp.strokeCap = Paint.Cap.ROUND; sp.strokeJoin = Paint.Join.ROUND
        fun ln(vararg xy: Float) {
            val p = Path(); p.moveTo(x + xy[0] * k, y + xy[1] * k)
            var i = 2; while (i < xy.size) { p.lineTo(x + xy[i] * k, y + xy[i + 1] * k); i += 2 }
            c.drawPath(p, sp)
        }
        fun ring(cx: Float, cy: Float) = c.drawCircle(x + cx * k, y + cy * k, 7f * k, sp)
        when (kind) {
            "LEFT" -> { val p = Path(); p.moveTo(x + 21f * k, y + 31f * k); p.lineTo(x + 21f * k, y + 15f * k); p.quadTo(x + 21f * k, y + 9f * k, x + 15f * k, y + 9f * k); p.lineTo(x + 5f * k, y + 9f * k); c.drawPath(p, sp); ln(11f, 3f, 5f, 9f, 11f, 15f) }
            "RIGHT" -> { val p = Path(); p.moveTo(x + 11f * k, y + 31f * k); p.lineTo(x + 11f * k, y + 15f * k); p.quadTo(x + 11f * k, y + 9f * k, x + 17f * k, y + 9f * k); p.lineTo(x + 27f * k, y + 9f * k); c.drawPath(p, sp); ln(21f, 3f, 27f, 9f, 21f, 15f) }
            "SLIGHT_LEFT" -> { ln(20f, 31f, 20f, 19f, 8f, 7f); ln(7f, 17f, 7f, 6f, 18f, 6f) }
            "SLIGHT_RIGHT" -> { ln(12f, 31f, 12f, 19f, 24f, 7f); ln(25f, 17f, 25f, 6f, 14f, 6f) }
            "SHARP_LEFT" -> { ln(22f, 31f, 22f, 7f, 7f, 22f); ln(6f, 12f, 6f, 23f, 17f, 23f) }
            "SHARP_RIGHT" -> { ln(10f, 31f, 10f, 7f, 25f, 22f); ln(26f, 12f, 26f, 23f, 15f, 23f) }
            "UTURN" -> { val p = Path(); p.moveTo(x + 23f * k, y + 31f * k); p.lineTo(x + 23f * k, y + 12f * k); p.arcTo(RectF(x + 9f * k, y + 5f * k, x + 23f * k, y + 19f * k), 0f, -180f, false); p.lineTo(x + 9f * k, y + 25f * k); c.drawPath(p, sp); ln(3f, 19f, 9f, 25f, 15f, 19f) }
            "STRAIGHT" -> { ln(16f, 31f, 16f, 3f); ln(10f, 9f, 16f, 3f, 22f, 9f) }
            "RB_R" -> { ring(14f, 15f); ln(14f, 31f, 14f, 22f); ln(21f, 15f, 30f, 15f); ln(25f, 10f, 30f, 15f, 25f, 20f) }
            "RB_S" -> { ring(16f, 17f); ln(16f, 31f, 16f, 24f); ln(16f, 10f, 16f, 1f); ln(11f, 6f, 16f, 1f, 21f, 6f) }
            "RB_L" -> { ring(18f, 15f); ln(18f, 31f, 18f, 22f); ln(11f, 15f, 2f, 15f); ln(7f, 10f, 2f, 15f, 7f, 20f) }
            else -> { ring(16f, 12f); ln(20f, 31f, 20f, 18f); ln(12f, 18f, 12f, 30f); ln(7f, 25f, 12f, 30f, 17f, 25f) }   // RB_U
        }
        sp.strokeJoin = Paint.Join.MITER
    }

    /** trzy litery jedna pod druga: od gornej krawedzi cyfr do linii bazowej */
    private fun vlabel(c: Canvas, s: String, base: Float, capH: Float, col: Int = LBL): Item = Item(10f) { x ->
        val ls = 11f; val lc = ls * CAP
        val top = base - capH
        val ys = listOf(top + lc, (top + base) / 2f + lc / 2f, base)
        for ((i, ch) in s.take(3).withIndex()) t(c, ch.toString(), x + 5f, ys[i], ls, col, true, Paint.Align.CENTER)
    }

    /** m / s jako ulamek: kreska 3/4 wysokosci cyfr, m i s przyklejone (min. 1 px odstepu) */
    private fun msUnit(c: Canvas, x: Float, base: Float, capH: Float) {
        val top = base - capH
        val k = capH / 28f
        sp.color = LBL; sp.strokeWidth = 2f * k
        c.drawLine(x + 8f * k, top + 24.5f * k, x + 15.5f * k, top + 3.5f * k, sp)
        val fs = 16f * k
        t(c, "m", x + 5.5f * k, top + 11f * k, fs, LBL, true, Paint.Align.CENTER)
        t(c, "s", x + 16.3f * k, top + 25.5f * k, fs, LBL, true, Paint.Align.CENTER)
    }

    private fun clock(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return fmt("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    private fun gradeColor(g: Float): Int = when {
        g <= -8f -> Color.parseColor("#3B4BA8"); g <= -5f -> Color.parseColor("#5B9BE0"); g <= -2f -> Color.parseColor("#2DD4BF")
        g < 1f -> Color.parseColor("#9AA5B1"); g < 2f -> Color.parseColor("#86EFAC"); g < 5f -> Color.parseColor("#22C55E")
        g < 8f -> Color.parseColor("#EAB308"); g < 11f -> Color.parseColor("#FDBA74"); g < 14f -> Color.parseColor("#F97316")
        g < 20f -> Color.parseColor("#EF4444"); else -> Color.parseColor("#A855F7")
    }

    // ---------- ikony (dol ikony na linii bazowej) ----------
    /** klasyczna strzalka (grot + trzonek) obrocona o deg, srodek (cx, cy), bok kwadratu sz */
    private fun arrow(c: Canvas, cx: Float, cy: Float, sz: Float, deg: Float, color: Int) {
        val k = sz / 28f
        val a = Math.toRadians(deg.toDouble())
        val ca = kotlin.math.cos(a).toFloat(); val sa = kotlin.math.sin(a).toFloat()
        val pts = listOf(0f to -13f, 10f to -1f, 3.5f to -1f, 3.5f to 13f, -3.5f to 13f, -3.5f to -1f, -10f to -1f)
        val p = Path()
        for ((i, pt) in pts.withIndex()) {
            val x = pt.first * k; val y = pt.second * k
            val rx = cx + x * ca - y * sa; val ry = cy + x * sa + y * ca
            if (i == 0) p.moveTo(rx, ry) else p.lineTo(rx, ry)
        }
        p.close(); fp.color = color; c.drawPath(p, fp)
    }

    private fun tri(c: Canvas, x: Float, base: Float, wI: Float, hI: Float, g: Float, color: Int) {
        val hh = hI * (0.35f + (abs(g).coerceAtMost(15f) / 15f) * 0.65f)
        val p = Path()
        if (g >= 0f) { p.moveTo(x, base); p.lineTo(x + wI, base); p.lineTo(x + wI, base - hh) }
        else { p.moveTo(x, base - hh); p.lineTo(x, base); p.lineTo(x + wI, base) }
        p.close(); fp.color = color; c.drawPath(p, fp)
    }

    /** termometr: rurka z obrysem, zbiorniczek na dole, slupek rteci i kreski skali */
    private fun thermo(c: Canvas, x: Float, base: Float, wI: Float, hI: Float, color: Int) {
        val cx = x + wI / 2f
        val tube = wI * 0.42f
        val bulbR = wI * 0.36f
        val bulbCy = base - bulbR
        val top = base - hI
        sp.color = color; sp.strokeWidth = 2f; sp.strokeCap = Paint.Cap.ROUND
        c.drawRoundRect(RectF(cx - tube / 2f, top, cx + tube / 2f, bulbCy), tube / 2f, tube / 2f, sp)
        fp.color = color
        c.drawCircle(cx, bulbCy, bulbR, fp)
        c.drawRect(cx - tube * 0.2f, top + hI * 0.35f, cx + tube * 0.2f, bulbCy, fp)
        for (k in 1..3) { val yy = top + hI * (0.15f + 0.15f * k); c.drawLine(cx + tube / 2f + 1f, yy, cx + tube / 2f + 3f, yy, sp) }
    }

    private fun drop(c: Canvas, x: Float, base: Float, wI: Float, hI: Float, color: Int) {
        val top = base - hI; val cx = x + wI / 2f
        val p = Path()
        p.moveTo(cx, top); p.quadTo(x + wI, top + hI * 0.62f, cx, base); p.quadTo(x, top + hI * 0.62f, cx, top)
        p.close(); fp.color = color; c.drawPath(p, fp)
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fp.color = color; c.drawCircle(cx, cy, r, fp)
        sp.color = color; sp.strokeWidth = r * 0.28f
        for (k in 0 until 8) {
            val a = Math.toRadians(45.0 * k); val ca = kotlin.math.cos(a).toFloat(); val sa = kotlin.math.sin(a).toFloat()
            c.drawLine(cx + ca * r * 1.35f, cy + sa * r * 1.35f, cx + ca * r * 1.85f, cy + sa * r * 1.85f, sp)
        }
    }

    private fun cloud(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        fp.color = color
        c.drawCircle(x + w * 0.30f, y + h * 0.62f, h * 0.36f, fp)
        c.drawCircle(x + w * 0.55f, y + h * 0.45f, h * 0.45f, fp)
        c.drawCircle(x + w * 0.78f, y + h * 0.64f, h * 0.32f, fp)
        c.drawRect(x + w * 0.28f, y + h * 0.62f, x + w * 0.80f, y + h * 0.98f, fp)
    }

    private fun fog(c: Canvas, x: Float, base: Float, wI: Float, hI: Float, color: Int) {
        sp.color = color; sp.strokeWidth = hI * 0.11f
        for (k in 0..2) { val yy = base - hI * (0.2f + 0.28f * k); c.drawLine(x + wI * 0.08f, yy, x + wI * 0.92f, yy, sp) }
    }

    private fun snow(c: Canvas, x: Float, base: Float, sz: Float, color: Int) {
        sp.color = color; sp.strokeWidth = sz * 0.12f
        val sx = x + sz / 2f; val sy = base - sz / 2f; val rr = sz * 0.45f
        for (k in 0..2) {
            val a = Math.toRadians(90.0 + 60.0 * k); val dx = (rr * kotlin.math.cos(a)).toFloat(); val dy = (rr * kotlin.math.sin(a)).toFloat()
            c.drawLine(sx - dx, sy - dy, sx + dx, sy + dy, sp)
        }
    }

    private fun storm(c: Canvas, x: Float, base: Float, wI: Float, hI: Float, color: Int) {
        cloud(c, x, base - hI * 0.95f, wI, hI * 0.6f, UNIT)
        val bx = x + wI * 0.45f; val by = base - hI * 0.5f; val bh = hI * 0.55f
        val p = Path(); p.moveTo(bx + bh * 0.25f, by); p.lineTo(bx - bh * 0.15f, by + bh * 0.55f); p.lineTo(bx + bh * 0.08f, by + bh * 0.55f)
        p.lineTo(bx - bh * 0.1f, by + bh); p.lineTo(bx + bh * 0.35f, by + bh * 0.38f); p.lineTo(bx + bh * 0.12f, by + bh * 0.38f); p.close()
        fp.color = color; c.drawPath(p, fp)
    }
}
