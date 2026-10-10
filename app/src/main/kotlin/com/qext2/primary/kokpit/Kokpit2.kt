package com.qext2.primary.kokpit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
        val d = d1.copy(turnKind = tk[ti], turnDistM = td[ti])
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
        val g = 5f
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
        val showZone = d.hrShowZone && z != null
        val hrTxt = if (showZone) "Z$z" else d.hr?.toString() ?: "—"
        val hrCol = when { d.hr == null && !showZone -> NONE; z == 5 -> BAD; z == 4 -> YEL; else -> WHITE }
        val heartCol = when (d.hrDriftLevel) { 2 -> BAD; 1 -> ORANGE; else -> WHITE }
        heart(c, lx0, capTop + (big * CAP - 20f) / 2f, 22f, heartCol)
        t(c, hrTxt, lx0 + 26f, bigBase, big, hrCol)

        val cv = d.cadence?.toString() ?: "—"
        t(c, cv, rx, bigBase, big, if (d.cadence != null) WHITE else NONE, true, Paint.Align.RIGHT)
        t(c, "KAD", rx - w(cv, big) - 5f, capTop + 15f * CAP, 15f, UNIT, false, Paint.Align.RIGHT)

        val ref = 40f
        val refBase = capTop + ref * CAP
        val npTxt = d.cpe5W?.let { kotlin.math.round(it).toInt().toString() } ?: "—"
        t(c, npTxt, cx - g, refBase, ref, if (d.cpe5W != null) WHITE else NONE, true, Paint.Align.RIGHT)
        val ls = 19f
        val lc = trendCol(d.cpTrend)
        val lw = w("NP", ls, false)
        val lRight = cx - g - w(npTxt, ref) - 2f
        t(c, "NP", lRight, capTop + ls * CAP, ls, lc, false, Paint.Align.RIGHT)
        t(c, "5", lRight - lw / 2f, capTop + ls * CAP + ls * 0.85f, ls, lc, false, Paint.Align.CENTER)
        val avTxt = d.avgSpeedKmh?.let { fmt("%.1f", it) } ?: "—"
        t(c, avTxt, cx + g, refBase, ref, if (d.avgSpeedKmh != null) WHITE else NONE)
        val sym = 20f
        avgSym(c, cx + g + w(avTxt, ref) + 2f, refBase - ref * CAP / 2f - sym / 2f, sym, trendCol(d.avgSpeedTrend))

        // --- dolny wiersz: W' (lewo), MOC | PREDKOSC (przy srodku), BIEG (prawo); wszystko na linii base
        val wb = d.wbalPct
        val wTxt = wb?.toString() ?: "—"
        val wCol = when { wb == null -> NONE; d.wbalTrend == "rising" -> GOOD; d.wbalTrend == "falling" || d.wbalTrend == "plummeting" -> BAD; else -> WHITE }
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
        val pCol = if (d.powerW == null) NONE else d.powerColor
        // predkosc zolta (jak w starych polach: domyslny kolor predkosci #F2C230), moc biala - latwo odroznic
        val spCol = if (d.speedKmh != null) SPEED else NONE
        tp.textScaleX = sxW
        tp.letterSpacing = lsW
        // moc: do srodka z lewej, kolor z oceny tempa (PacingEngine)
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
        sp.color = color; sp.strokeWidth = sz * 0.12f
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

    // ---------- komunikat: tlo wg waznosci ----------
    private fun drawMsg(c: Canvas, d: KokpitNavData, vw: Float, h: Float) {
        val m = d.msg
        val crit = m.kind == MsgKind.WPRIME || m.kind == MsgKind.DESCENT ||
            (m.kind == MsgKind.HUB && m.accentColor.equals("#FF8C8C", ignoreCase = true))
        val warn = !crit && (m.kind == MsgKind.RAIN || m.kind == MsgKind.FUEL || m.kind == MsgKind.DUSK || m.kind == MsgKind.HUB)
        val bg = when { crit -> MSG_CRIT; warn -> MSG_WARN; else -> MSG_INFO }
        val fg = if (warn) BLACK else WHITE
        fp.color = bg; c.drawRect(0f, 0f, vw, h, fp)
        var x = 8f
        val demoW = if (d.demo) w("DEMO", 18f) + 8f else 0f
        val right = vw - 8f - demoW
        val leadCol = if (crit || warn) fg else if (m.kind == MsgKind.NONE) UNIT else WHITE
        var s = (31f * h / 41f).coerceAtMost(40f)
        fun total(sz: Float): Float = (if (m.lead.isNotEmpty()) w(m.lead, sz, false) + sz * 0.3f else 0f) + (if (m.accent.isNotEmpty()) w(m.accent, sz) else 0f)
        while (s > 14f && x + total(s) > right) s -= 1f
        val base = h / 2f + s * CAP / 2f
        if (m.lead.isNotEmpty()) { t(c, m.lead, x, base, s, leadCol, false); x += w(m.lead, s, false) + s * 0.3f }
        if (m.accent.isNotEmpty()) t(c, m.accent, x, base, s, fg)
        if (d.demo) t(c, "DEMO", vw - 8f, h / 2f + 18f * CAP / 2f, 18f, if (warn) BLACK else ORANGE, true, Paint.Align.RIGHT)
    }

    // ---------- wiersz pogody: temp + opad/niebo | wiatr | nachylenie ----------
    private fun gw(g: List<Item>, inner: Float) = g.sumOf { it.width.toDouble() }.toFloat() + inner * (g.size - 1)
    private fun drawG(g: List<Item>, x0: Float, inner: Float) { var x = x0; for ((i, item) in g.withIndex()) { if (i > 0) x += inner; item.draw(x); x += item.width } }

    // ---------- gorny wiersz (zmienne): temperatura | pogoda | manewr | nachylenie - STALE KOLUMNY ----------
    private fun rowWeather(c: Canvas, d: KokpitNavData, vw: Float, base: Float) {
        // JEDNA wielkosc dla temperatury, manewru i nachylenia: najwieksza (do 42 px), przy ktorej wzorce
        // najszerszych wartosci mieszcza sie w wierszu - liczona raz z wzorcow, nie z biezacych wartosci
        val wxRef0 = 30f + 4f + w("88%", 32f) + 4f + w("88′", 18f)
        fun rowW(sz: Float) = (16f + 4f + w("-88°", sz)) + 6f + wxRef0 + 18f +
            (30f + 8f + w("888", sz) + 2f + w("km", 16f, false)) + 8f + (26f + 4f + w("-88", sz) + 4f + w("%", 20f, false))
        val vs = 42f   // temperatura i nachylenie zawsze 42 px (decyzja Michala); manewr dopasowany do reszty miejsca
        val capH = vs * CAP
        val tempG = listOf(Item(16f) { x -> thermo(c, x, base, 16f, capH, LBL) },
            if (d.tempC != null) txt(fmt("%.0f", d.tempC) + "°", vs, WHITE, base) else txt("—", vs, NONE, base))
        val wx = ArrayList<Item>()
        val rn = d.rainNowMmH; val rs = d.rainSoon
        if (rn != null && rn >= 0.1f) {
            wx.add(Item(22f) { x -> drop(c, x, base, 22f, 26f, BLUE) })
            wx.add(txt(fmt("%.1f", rn).replace('.', ','), 36f, BLUE, base))
            wx.add(txt("mm", 20f, BLUE, base, false))
        } else if (rs != null && rs.probPct >= 30 && rs.kind != "FOG") {
            val cl = when (rs.kind) { "STORM" -> RED; "SNOW" -> Color.parseColor("#BFDBFE"); else -> BLUE }
            when (rs.kind) {
                "STORM" -> wx.add(Item(30f) { x -> storm(c, x, base, 30f, 26f, cl) })
                "SNOW" -> wx.add(Item(26f) { x -> snow(c, x, base, 26f, cl) })
                else -> wx.add(Item(22f) { x -> drop(c, x, base, 22f, 26f, cl) })
            }
            wx.add(txt("${rs.probPct}%", 36f, cl, base))
            wx.add(txt("${rs.minutes}′", 20f, cl, base))
        } else d.sky?.let { sk ->
            when (sk) {
                "CLEAR" -> wx.add(Item(30f) { x -> sun(c, x + 15f, base - capH / 2f, 8f, Color.parseColor("#FACC15")) })
                "PARTLY" -> wx.add(Item(34f) { x ->
                    sun(c, x + 12f, base - capH * 0.62f, 7f, Color.parseColor("#FACC15"))
                    cloud(c, x + 3f, base - capH * 0.55f, 31f, capH * 0.55f, Color.parseColor("#E5E7EB"))
                })
                "FOG" -> wx.add(Item(30f) { x -> fog(c, x, base, 30f, capH, UNIT) })
                else -> wx.add(Item(34f) { x -> cloud(c, x, base - capH * 0.75f, 34f, capH * 0.75f, UNIT) })
            }
        }
        val gr = d.gradePct
        val grG = if (gr == null) listOf(Item(26f) { x -> tri(c, x, base, 26f, capH, 3f, NONE) }, txt("—", vs, NONE, base))
            else listOf(Item(26f) { x -> tri(c, x, base, 26f, capH, gr, gradeColor(gr)) }, txt(fmt("%.0f", gr), vs, WHITE, base), txt("%", 20f, UNIT, base, false))
        // pozycje z wzorcow najszerszych wartosci (nie z biezacych) - kolumny stoja w miejscu
        // wzorce = realne maksima (temp. i nachylenie do -18, opad do 88% za 88 min) - zmierzone na zrzucie z Karoo
        val tempRef = 16f + 4f + w("-18°", vs)
        val wxRef = 30f + 4f + w("88%", 36f) + 4f + w("88′", 20f)
        val grRef = 26f + 4f + w("-18", vs) + 4f + w("%", 20f, false)
        val xTemp = 8f
        val xWx = xTemp + tempRef + 6f
        val xGr = vw - 8f - grRef
        // manewr odsuniety od pogody (18 px), stoi tuz przed nachyleniem
        // wzorce pogody/nachylenia maja ok. 13 px zapasu (zmierzone na Karoo: burza 80% 15' konczy sie 13 px przed wzorcem) -
        // manewr moze z niego korzystac; stoi 6 px przed wzorcem nachylenia
        // zmierzone na Karoo (build 290): najszersza pogoda (burza 80% 15') konczy sie 13 px przed koncem wzorca,
        // najszersze nachylenie (-12 %) zaczyna sie 17 px za poczatkiem wzorca -> manewr: 8 px od pogody, 6 px od nachylenia
        val turnL = xWx + wxRef - 13f + 8f
        val turnR = xGr + 17f - 6f
        val turn = turnItem(c, d, base, vs, turnR - turnL)
        val xTurn = turnL
        // nachylenie zakotwiczone do prawej krawedzi (jak temperatura do lewej)
        drawG(tempG, xTemp, 4f); drawG(wx, xWx, 4f); turn.draw(xTurn); drawG(grG, vw - 8f - gw(grG, 4f), 4f)
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

    // ---------- wiersz km: DST | DTD | ETA ----------
    // ---------- dolny wiersz (stabilne): DST | DTD | ETA | wiatr - STALE KOLUMNY ----------
    private fun rowKm(c: Canvas, d: KokpitNavData, vw: Float, base: Float) {
        val vs = 46f
        val capH = vs * CAP
        val d10 = kotlin.math.floor(d.doneKm.coerceAtLeast(0f) * 10f).toInt()
        val dDec = "." + (d10 % 10).toString()
        val dds = vs * 0.55f
        val dstG = listOf(vlabel(c, "DST", base, capH), txt((d10 / 10).toString(), vs, WHITE, base),
            Item(w(dDec, dds)) { x -> t(c, dDec, x, base - capH + dds * CAP, dds, WHITE) })
        val dtdG = d.leftKm?.let { listOf(vlabel(c, "DTD", base, capH), txt(fmt("%.0f", it), vs, WHITE, base)) }
            ?: listOf(vlabel(c, "DTD", base, capH), txt("—", vs, NONE, base))
        val eta = d.etaMs; val dl = d.deadlineMs
        val etaCol = if (eta != null && dl != null) when {
            eta > dl -> RED
            dl - eta >= 30 * 60_000L -> GREEN
            dl - eta <= 10 * 60_000L -> Color.parseColor("#FACC15")
            else -> WHITE
        } else WHITE
        val etaG = if (eta != null) listOf(vlabel(c, "ETA", base, capH), txt(clock(eta), vs, etaCol, base))
            else listOf(vlabel(c, "ETA", base, capH), txt("—", vs, NONE, base))
        val wm = d.windMps
        val windG: List<Item> = if (wm == null) listOf(txt("wiatr —", 20f, NONE, base, false)) else {
            val hs = d.windSignedMps
            val wcol = if (hs == null) WHITE else {
                val tot = maxOf(d.windTotalMps ?: abs(hs), abs(hs))
                when { hs >= 3f && hs >= 0.7f * tot -> RED; hs <= -3f && -hs >= 0.7f * tot -> GREEN; else -> WHITE }
            }
            val wg = ArrayList<Item>()
            d.windRelDeg?.let { rel -> wg.add(Item(32f) { x -> arrow(c, x + 16f, base - capH / 2f, 32f, rel.toFloat(), wcol) }) }
            wg.add(txt(fmt("%.0f", wm), vs, WHITE, base))
            wg.add(Item(20f * capH / 28f) { x -> msUnit(c, x, base, capH) })
            wg
        }
        // kolumny z wzorcow najszerszych wartosci; odstepy rowne
        val dstRef = 10f + 2f + w("888", vs) + 2f + w(".8", dds)
        val dtdRef = 10f + 2f + w("888", vs)
        val etaRef = 10f + 2f + w("88:88", vs)
        val windRef = 32f + 4f + w("88", vs) + 4f + 20f * capH / 28f
        val gap = ((vw - 16f) - (dstRef + dtdRef + etaRef + windRef)) / 3f
        var x = 8f
        drawG(dstG, x, 2f); x += dstRef + gap
        drawG(dtdG, x, 2f); x += dtdRef + gap
        drawG(etaG, x, 2f); x += etaRef + gap
        drawG(windG, vw - 8f - gw(windG, 4f), 4f)   // wiatr zakotwiczony do prawej krawedzi (jak DST do lewej)
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
        val warn = kind == "UTURN" || kind == "RB_U" || kind.startsWith("SHARP")
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
    private fun vlabel(c: Canvas, s: String, base: Float, capH: Float): Item = Item(10f) { x ->
        val ls = 12f; val lc = ls * CAP
        val top = base - capH
        val ys = listOf(top + lc, (top + base) / 2f + lc / 2f, base)
        for ((i, ch) in s.take(3).withIndex()) t(c, ch.toString(), x + 5f, ys[i], ls, LBL, true, Paint.Align.CENTER)
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
