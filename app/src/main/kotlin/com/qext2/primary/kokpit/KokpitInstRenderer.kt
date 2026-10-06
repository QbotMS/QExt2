package com.qext2.primary.kokpit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Dane dolnego pola KOKPIT (instrumenty). null = brak danych. */
data class KokpitInstData(
    val powerW: Int? = null,
    val cpW: Float? = null,
    val cpe5W: Float? = null,
    val powerColor: Int = Color.WHITE,
    val speedKmh: Float? = null,
    val avgSpeedKmh: Float? = null,
    val speedColor: Int = Color.parseColor("#F2C230"),
    val hr: Int? = null,
    val hrAvg: Int? = null,
    val hrZone: Int? = null,      // 1..5
    val wbalPct: Int? = null,
    val cadence: Int? = null,
    val cadenceAvg: Int? = null,
    val optCadLow: Int = 80,
    val optCadHigh: Int = 95,
    val gearFront: Int? = null,
    val gearRear: Int? = null,
    val cogs: List<Int> = emptyList(),   // od najmniejszej
    val recCog: Int? = null,
    /** true = strefa tetna (Z1..Z5), false = bpm - przelacznik w SETUP (hr_zone_mode) */
    val hrShowZone: Boolean = true,
    /** bezpieczny pulap mocy z PacingEngine (null = brak); moc > pulap = czerwona, >= 95% = zolta */
    val powerCeilingW: Int? = null,
    /** dryf tetna: 0 brak, 1 umiarkowany (>= 6%), 2 duzy (>= 10%) - barwi ikone serca */
    val hrDriftLevel: Int = 0,
    /** trendy srednich: +1 rosnie, -1 maleje, 0 bez wyraznej zmiany */
    val cpTrend: Int = 0,
    val avgSpeedTrend: Int = 0,
    val hrAvgTrend: Int = 0,
    val cadAvgTrend: Int = 0,
    val demo: Boolean = false,
)

/**
 * Polkole 180 st.: lewa cwiartka = moc (od lewego dolu do szczytu, strefy wg CP, wypelnienie w kolorze
 * biezacej strefy, bialy trojkat = CPe5), prawa = predkosc (od szczytu w dol, zolty trojkat = srednia).
 * Brzegi: tetno + W' (lewo), kadencja + bieg z kaseta (prawo). Skaluje sie do wysokosci pola.
 */
object KokpitInstRenderer {
    private val BG = Color.parseColor("#14181D")
    private val LBL = Color.parseColor("#D5DCE3")
    private val UNIT = Color.parseColor("#9AA5B1")
    private val AVG = Color.parseColor("#A3ADB8")
    private val NONE = Color.parseColor("#9AA3AE")
    private val TRACK = Color.parseColor("#465366")
    private val DARK = Color.parseColor("#111315")
    private val YEL = Color.parseColor("#F2C230")
    private val WHITE = Color.WHITE

    private val PZ = listOf(0.00f to "#9AA3AE", 0.55f to "#6FA8FF", 0.75f to "#22C55E", 0.90f to "#EAB308", 1.05f to "#F97316", 1.20f to "#EF4444")
    private const val PMAX = 1.5f
    private const val SMAX = 45f
    private val SZ = listOf(0f to "#4F6E80", 15f to "#5B9BE0", 25f to "#60A5FA", 35f to "#93C5FD")
    private val HRZ = listOf("#9CA3AF", "#60A5FA", "#4ADE80", "#FACC15", "#FF8C8C")

    private val bold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val reg: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private fun fmt(p: String, vararg a: Any): String = String.format(java.util.Locale.US, p, *a)
    private fun col(h: String) = Color.parseColor(h)

    private fun t(c: Canvas, s: String, x: Float, base: Float, size: Float, color: Int, b: Boolean = true, align: Paint.Align = Paint.Align.LEFT) {
        tp.typeface = if (b) bold else reg; tp.textSize = size; tp.color = color; tp.textAlign = align
        c.drawText(s, x, base, tp)
    }
    private fun w(s: String, size: Float, b: Boolean = true): Float { tp.typeface = if (b) bold else reg; tp.textSize = size; return tp.measureText(s) }

    private fun zoneIdx(ratio: Float): Int { var i = 0; for (k in PZ.indices) if (ratio >= PZ[k].first) i = k; return i }

    @Synchronized
    fun render(width: Int, height: Int, d: KokpitInstData): Bitmap {
        val W = width.coerceAtLeast(200).toFloat(); val H = height.coerceAtLeast(80).toFloat()
        val bmp = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(BG)
        if (H < 170f) {
            // uklad z mockupu (474x126) skalowany jednolicie; nadmiar wysokosci (np. pole 143 px) jako margines,
            // zeby luki nie rosly szerzej niz pozwalaja boki
            val sc = minOf(H / 126f, W / 474f)
            val hc = 126f * sc
            c.save(); c.translate(0f, (H - hc) / 2f)
            renderCompact(c, d, W, hc)
            c.restore()
            return bmp
        }
        val cx = W / 2f
        val sw = (H * 0.10f).coerceIn(10f, 18f)          // grubosc luku
        val r = min(H - sw / 2f - 8f, W * 0.245f)         // promien (srodek luku)
        val cy = H - 6f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        drawArcs(c, d, oval, cx, cy, r, sw)
        val sideW = cx - r - sw / 2f - 14f
        if (H < 170f) {
            // pole niskie (2 pola na mapie): tylko to, co czytelne w jezdzie - duze cyfry, bez drobnych opisow
            drawCenterCompact(c, d, cx, cy, r, sw)
            drawLeftCompact(c, d, 6f, sideW, H)
            drawRightCompact(c, d, W - 6f - sideW, sideW, H)
        } else {
            drawCenter(c, d, cx, cy, r, sw)
            drawLeft(c, d, 6f, sideW, H)
            drawRight(c, d, W - 6f - sideW, sideW, H)
        }
        if (d.demo) t(c, "DEMO", cx, H * 0.16f, H * 0.09f, col("#FB923C"), true, Paint.Align.CENTER)
        return bmp
    }

    private fun arc(c: Canvas, oval: RectF, start: Float, sweep: Float, color: Int, sw: Float, alpha: Int = 255) {
        sp.color = color; sp.alpha = alpha; sp.strokeWidth = sw
        c.drawArc(oval, start, sweep, false, sp)
        sp.alpha = 255
    }

    /** kat Androida (0 = godz. 3, zgodnie z zegarem): moc 180->270, predkosc 270->360 */
    private fun pAng(ratio: Float) = 180f + 89f * (ratio / PMAX).coerceIn(0f, 1f)
    private fun sAng(v: Float) = 271f + 89f * (v / SMAX).coerceIn(0f, 1f)

    private fun pt(cx: Float, cy: Float, rr: Float, ang: Float): Pair<Float, Float> {
        val a = Math.toRadians(ang.toDouble()); return (cx + rr * cos(a).toFloat()) to (cy + rr * sin(a).toFloat())
    }

    private fun tick(c: Canvas, cx: Float, cy: Float, r: Float, sw: Float, ang: Float, color: Int) {
        val (x1, y1) = pt(cx, cy, r + sw / 2f + 2f, ang); val (x2, y2) = pt(cx, cy, r + sw / 2f + 7f, ang)
        sp.color = color; sp.strokeWidth = 2f; c.drawLine(x1, y1, x2, y2, sp)
    }

    private fun needle(c: Canvas, cx: Float, cy: Float, r: Float, sw: Float, ang: Float) {
        val (x1, y1) = pt(cx, cy, r - sw / 2f, ang); val (x2, y2) = pt(cx, cy, r + sw / 2f, ang)
        sp.color = DARK; sp.strokeWidth = 7f; c.drawLine(x1, y1, x2, y2, sp)
        sp.color = WHITE; sp.strokeWidth = 4f; c.drawLine(x1, y1, x2, y2, sp)
    }

    private fun marker(c: Canvas, cx: Float, cy: Float, r: Float, sw: Float, ang: Float, color: Int) {
        val (ix, iy) = pt(cx, cy, r + sw / 2f + 1f, ang)
        val (ox, oy) = pt(cx, cy, r + sw / 2f + 15f, ang)
        val a = Math.toRadians(ang.toDouble()); val px = (-sin(a) * 8).toFloat(); val py = (cos(a) * 8).toFloat()
        val p = Path(); p.moveTo(ix, iy); p.lineTo(ox + px, oy + py); p.lineTo(ox - px, oy - py); p.close()
        fp.color = color; c.drawPath(p, fp)
        sp.color = DARK; sp.strokeWidth = 2f; c.drawPath(p, sp)
    }

    private fun drawArcs(c: Canvas, d: KokpitInstData, oval: RectF, cx: Float, cy: Float, r: Float, sw: Float) {
        arc(c, oval, 180f, 89.5f, TRACK, sw); arc(c, oval, 270.5f, 89.5f, TRACK, sw)
        for (k in PZ.indices) {
            val a0 = pAng(PZ[k].first); val a1 = pAng(if (k + 1 < PZ.size) PZ[k + 1].first else PMAX)
            arc(c, oval, a0 + 0.5f, (a1 - a0 - 1f).coerceAtLeast(0.5f), col(PZ[k].second), sw, 115)
            if (k > 0) tick(c, cx, cy, r, sw, a0, col(PZ[k].second))
        }
        for (k in SZ.indices) {
            val a0 = sAng(SZ[k].first); val a1 = sAng(if (k + 1 < SZ.size) SZ[k + 1].first else SMAX)
            arc(c, oval, a0 + 0.5f, (a1 - a0 - 1f).coerceAtLeast(0.5f), col(SZ[k].second), sw, 115)
            if (k > 0) tick(c, cx, cy, r, sw, a0, UNIT)
        }
        val cp = d.cpW
        val pw = d.powerW
        if (pw != null && cp != null && cp > 0f) {
            val ratio = pw / cp
            arc(c, oval, 180f, pAng(ratio) - 180f, col(PZ[zoneIdx(ratio)].second), sw)
            d.cpe5W?.takeIf { it > 0f }?.let { marker(c, cx, cy, r, sw, pAng(it / cp), WHITE) }
            needle(c, cx, cy, r, sw, pAng(ratio))
        }
        d.speedKmh?.let { v ->
            arc(c, oval, 270.5f, sAng(v) - 270.5f, col("#60A5FA"), sw)
            d.avgSpeedKmh?.takeIf { it > 0f }?.let { marker(c, cx, cy, r, sw, sAng(it), YEL) }
            needle(c, cx, cy, r, sw, sAng(v))
        }
    }

    private fun drawCenter(c: Canvas, d: KokpitInstData, cx: Float, cy: Float, r: Float, sw: Float) {
        val inner = r - sw / 2f
        val vSize = inner * 0.40f
        val hSize = inner * 0.12f
        val subSize = inner * 0.25f
        val gap = inner * 0.05f
        val hBase = cy - inner * 0.66f
        val vBase = cy - inner * 0.27f
        val sBase = cy - inner * 0.02f
        // separator
        fp.color = col("#2A3038"); c.drawRect(cx - 1f, hBase - hSize, cx + 1f, cy - 4f, fp)
        // naglowki
        t(c, "MOC", cx - gap - w(" W", hSize, false), hBase, hSize, LBL, true, Paint.Align.RIGHT)
        t(c, " W", cx - gap, hBase, hSize, UNIT, false, Paint.Align.RIGHT)
        t(c, "V", cx + gap, hBase, hSize, LBL, true)
        t(c, " km/h", cx + gap + w("V", hSize), hBase, hSize, UNIT, false)
        // wartosci
        val pv = d.powerW?.toString() ?: "—"
        val maxW = inner * 0.86f
        var vs = vSize
        while (vs > 10f && (w(pv, vs) > maxW || w(d.speedKmh?.let { fmt("%.1f", it) } ?: "—", vs) > maxW)) vs -= 1f
        t(c, pv, cx - gap, vBase, vs, if (d.powerW != null) d.powerColor else NONE, true, Paint.Align.RIGHT)
        t(c, d.speedKmh?.let { fmt("%.1f", it) } ?: "—", cx + gap, vBase, vs, if (d.speedKmh != null) d.speedColor else NONE, true)
        // pod spodem: CPe5 i srednia
        val cpe = d.cpe5W?.let { fmt("%.0f", it) } ?: "—"
        t(c, cpe, cx - gap, sBase, subSize, WHITE, true, Paint.Align.RIGHT)
        t(c, "▲ CPe5 ", cx - gap - w(cpe, subSize), sBase, subSize * 0.55f, WHITE, false, Paint.Align.RIGHT)
        t(c, "▲ Ø ", cx + gap, sBase, subSize * 0.55f, YEL, false)
        t(c, d.avgSpeedKmh?.let { fmt("%.1f", it) } ?: "—", cx + gap + w("▲ Ø ", subSize * 0.55f, false), sBase, subSize, WHITE, true)
    }

    // ======================= POLE NISKIE (2 pola na mapie) - wg mockupu "KOKPIT - AKTUALNY" =======================
    private val GOLD = Color.parseColor("#E8B931")
    private val BLUE = Color.parseColor("#60A5FA")
    private val GOOD = Color.parseColor("#4ADE80")
    private val BAD = Color.parseColor("#FB923C")
    private val AVGC = Color.parseColor("#A3ADB8")
    private val SUB = Color.parseColor("#C9D2DC")

    /** trojkat trendu: dir +1 w gore, -1 w dol; srodek (x, y), szerokosc w */
    private fun trend(c: Canvas, x: Float, y: Float, w: Float, dir: Int, color: Int) {
        if (dir == 0) return
        val h = w * 0.85f
        val p = Path()
        if (dir > 0) { p.moveTo(x, y - h / 2f); p.lineTo(x + w / 2f, y + h / 2f); p.lineTo(x - w / 2f, y + h / 2f) }
        else { p.moveTo(x - w / 2f, y - h / 2f); p.lineTo(x + w / 2f, y - h / 2f); p.lineTo(x, y + h / 2f) }
        p.close(); fp.color = color; c.drawPath(p, fp)
    }

    /** symbol sredniej: okrag przeciety ukosna kreska, wpisany w kwadrat o boku sz, lewy-gorny rog (x, y) */
    private fun avgSym(c: Canvas, x: Float, y: Float, sz: Float, color: Int) {
        sp.color = color; sp.strokeWidth = sz * 0.11f
        c.drawCircle(x + sz / 2f, y + sz / 2f, sz * 0.325f, sp)
        c.drawLine(x + sz * 0.15f, y + sz * 0.85f, x + sz * 0.85f, y + sz * 0.15f, sp)
    }

    private fun bolt(c: Canvas, x: Float, y: Float, h: Float, color: Int) {
        val k = h / 22f
        val p = Path()
        p.moveTo(x + 9 * k, y); p.lineTo(x + 1 * k, y + 13 * k); p.lineTo(x + 7 * k, y + 13 * k)
        p.lineTo(x + 5 * k, y + 22 * k); p.lineTo(x + 15 * k, y + 8 * k); p.lineTo(x + 9 * k, y + 8 * k); p.close()
        fp.color = color; c.drawPath(p, fp)
    }

    /** kreska-znacznik promieniowo przez caly luk, wystajaca po obu stronach */
    /** kreska-znacznik w obrebie grubosci luku (bez wystawania), grubsza */
    private fun markLine(c: Canvas, ox: Float, oy: Float, r: Float, sw: Float, ang: Float, color: Int) {
        val (x1, y1) = pt(ox, oy, r - sw / 2f, ang); val (x2, y2) = pt(ox, oy, r + sw / 2f, ang)
        sp.color = DARK; sp.strokeWidth = 8f; c.drawLine(x1, y1, x2, y2, sp)
        sp.color = color; sp.strokeWidth = 5f; c.drawLine(x1, y1, x2, y2, sp)
    }

    private fun capBase(top: Float, size: Float) = top + size * 0.72f

    private fun powerCol(d: KokpitInstData): Int {
        val pw = d.powerW ?: return NONE
        val ceil = d.powerCeilingW?.takeIf { it in 1..5000 } ?: return WHITE
        return when { pw > ceil -> col("#FF8C8C"); pw >= ceil * 0.95f -> col("#FACC15"); else -> WHITE }
    }

    private fun renderCompact(c: Canvas, d: KokpitInstData, W: Float, H: Float) {
        val s = H / 126f
        val cx = W / 2f
        val cy = H - 2f * s                            // luki 2 px nizej
        val sw = 15f * s
        val dx = 24f * (W / 474f)
        // promien z wysokosci, ale nie wiekszy niz pozwala szerokosc (boki min. 96 px na tetno/W'/kadencje/bieg)
        val r = minOf((H - 4f * s) - 2f * s - sw / 2f, W / 2f - dx - sw / 2f - 92f * (W / 474f))
        val lox = cx - dx; val rox = cx + dx           // srodki cwiartek
        val inner = r - sw / 2f
        val ovL = RectF(lox - r, cy - r, lox + r, cy + r)
        val ovR = RectF(rox - r, cy - r, rox + r, cy + r)
        val base = H - 10f * s                         // wspolna linia dolu cyfr (moc, V, W', bieg)

        // --- luki: tor, przygaszone strefy, wypelnienie, znaczniki, wskazowki
        sp.strokeCap = Paint.Cap.BUTT
        arc(c, ovL, 180f, 90f, TRACK, sw); arc(c, ovR, 0f, -90f, TRACK, sw)
        fun pA(ratio: Float) = 180f + 90f * (ratio / PMAX).coerceIn(0f, 1f)      // moc: lewy dol -> szczyt
        fun sA(v: Float) = 360f - 90f * (v / SMAX).coerceIn(0f, 1f)             // predkosc: prawy dol -> szczyt
        for (k in PZ.indices) {
            val a0 = pA(PZ[k].first); val a1 = pA(if (k + 1 < PZ.size) PZ[k + 1].first else PMAX)
            arc(c, ovL, a0 + 0.6f, (a1 - a0 - 1.2f).coerceAtLeast(0.5f), col(PZ[k].second), sw, 115)
        }
        for (k in SZ.indices) {
            val a0 = sA(SZ[k].first); val a1 = sA(if (k + 1 < SZ.size) SZ[k + 1].first else SMAX)
            arc(c, ovR, a0 - 0.6f, (a1 - a0 + 1.2f).coerceAtMost(-0.5f), col(SZ[k].second), sw, 115)
        }
        val cp = d.cpW; val pw = d.powerW
        if (pw != null && cp != null && cp > 0f) {
            val ratio = pw / cp
            arc(c, ovL, 180f, pA(ratio) - 180f, col(PZ[zoneIdx(ratio)].second), sw)
            d.cpe5W?.takeIf { it > 0f }?.let { markLine(c, lox, cy, r, sw, pA(it / cp), WHITE) }
            needle(c, lox, cy, r, sw, pA(ratio))
        }
        d.speedKmh?.let { v ->
            arc(c, ovR, 360f, sA(v) - 360f, BLUE, sw)
            d.avgSpeedKmh?.takeIf { it > 0f }?.let { markLine(c, rox, cy, r, sw, sA(it), YEL) }
            needle(c, rox, cy, r, sw, sA(v))
        }
        // symbole na koncach lukow (szczyt): zlota blyskawica / niebieskie V
        bolt(c, lox + 3f * s, cy - r - 9f * s, 22f * s, UNIT)
        t(c, "V", rox - 3f * s, cy - r + 9f * s, 22f * s, UNIT, true, Paint.Align.RIGHT)
        // separator
        fp.color = col("#2A3038"); c.drawRect(cx - 1f, 30f * s, cx + 1f, H - 4f * s, fp)

        // --- odniesienia (wg mockupu): "CP/5 214" przy lewym luku i "17.5 (/)" przy prawym;
        //     zmiana sygnalizowana kolorem etykiety: zielony = rosnie, czerwony = spada, szary = bez zmian
        val g = 6f * s
        val refSize = 32f * s
        val refBase = capBase(39f * s, refSize)
        fun trendCol(t: Int) = when { t > 0 -> GOOD; t < 0 -> col("#FF8C8C"); else -> SUB }
        val cpTxt = d.cpe5W?.let { fmt("%.0f", it) } ?: "—"
        t(c, cpTxt, cx - g, refBase, refSize, WHITE, true, Paint.Align.RIGHT)
        val cpLX = cx - g - w(cpTxt, refSize) - 2f * s     // CP/5 blizej wartosci
        val lbl = 16f * s
        val cpCol = trendCol(d.cpTrend)
        val capTop = refBase - refSize * 0.72f
        t(c, "CP", cpLX, capTop + lbl * 0.72f, lbl, cpCol, false, Paint.Align.RIGHT)
        t(c, "5", cpLX - w("CP", lbl, false) / 2f, capTop + lbl * 0.72f + lbl * 0.80f, lbl, cpCol, false, Paint.Align.CENTER)
        val avTxt = d.avgSpeedKmh?.let { fmt("%.1f", it) } ?: "—"
        t(c, avTxt, cx + g, refBase, refSize, WHITE, true)
        val symSz = 16f * s
        avgSym(c, cx + g + w(avTxt, refSize) + 2f * s, refBase - refSize * 0.36f - symSz / 2f, symSz, trendCol(d.avgSpeedTrend))

        // --- glowne wartosci: moc dosunieta do srodka z lewej, predkosc z prawej; czesc dziesietna
        //     predkosci polowa wielkosci, gorna krawedz rowno z gorna krawedzia cyfr
        val xl = lox - inner + 7f * s
        val xr = rox + inner - 7f * s
        val pv = d.powerW?.toString() ?: "—"
        val v10 = d.speedKmh?.let { kotlin.math.round(it * 10f).toInt() }
        val sInt = v10?.let { (it / 10).toString() } ?: "—"
        val sDec = v10?.let { "." + (it % 10).toString() } ?: ""
        val vg = 8f * s
        val pL = xl + w("W", 17f * s) + 4f * s
        val uS = 13f * s
        val unitW = maxOf(w("km", uS, false), w("/h", uS, false))
        val sR = xr - unitW - 3f * s
        // moc i predkosc nie mniejsze niz tetno/kadencja (54 px); jednostki rysowane tylko, gdy sie mieszcza
        var vs = 60f * s
        while (vs > 54f * s && (w(pv, vs) > cx - vg - pL || w(sInt, vs) + w(sDec, vs / 2f) > sR - cx - vg)) vs -= 1f
        if (cx - vg - w(pv, vs) >= pL - 2f * s) t(c, "W", xl, base, 17f * s, UNIT, true)
        t(c, "km", xr - unitW / 2f, base - uS * 0.95f, uS, UNIT, false, Paint.Align.CENTER)
        t(c, "/h", xr - unitW / 2f, base, uS, UNIT, false, Paint.Align.CENTER)
        t(c, pv, cx - vg, base, vs, powerCol(d), true, Paint.Align.RIGHT)
        val spCol = if (d.speedKmh != null) WHITE else NONE
        t(c, sInt, cx + vg, base, vs, spCol, true)
        if (sDec.isNotEmpty()) {
            val ds = vs / 2f
            t(c, sDec, cx + vg + w(sInt, vs), base - vs * 0.72f + ds * 0.72f, ds, spCol, true)
        }

        // --- lewy brzeg: serce + tetno (strefa albo bpm), srednie tetno z trendem, W' bal
        val leftEdge = 4f * s
        val big = 54f * s
        val topBase = capBase(10f * s, big)          // tetno i kadencja 8 px nizej
        heart(c, leftEdge + 2f * s, topBase - 20f * s, 22f * s, when (d.hrDriftLevel) { 2 -> col("#FF8C8C"); 1 -> col("#FB923C"); else -> WHITE })
        val z = d.hrZone
        val showZone = d.hrShowZone && z != null
        val hrTxt = if (showZone) "Z$z" else d.hr?.toString() ?: "—"
        val hrCol = when { d.hr == null && !showZone -> NONE; z == 5 -> col("#FF8C8C"); z == 4 -> col("#FACC15"); else -> WHITE }
        t(c, hrTxt, leftEdge + 28f * s, topBase, big, hrCol, true)
        // W'
        val wb = d.wbalPct
        val wTxt = wb?.toString() ?: "—"
        // 3 cyfry (100%) mniejsze, zeby "%" i "W'" nie wchodzily na luk mocy
        var wSize = 50f * s
        run {
            val rOut = r + sw / 2f
            while (wSize > 20f) {
                // lewy luk jest najszerszy na dole - sprawdzamy na linii dolu cyfr, nie na ich gorze
                val dyA = (cy - base).coerceIn(0f, rOut)
                val arcX = lox - kotlin.math.sqrt(rOut * rOut - dyA * dyA)
                if (leftEdge + 2f * s + w(wTxt, wSize) + 6f * s <= arcX) break
                wSize -= 1f
            }
        }
        val wCol = when { wb == null -> NONE; wb > 50 -> WHITE; wb >= 20 -> col("#FACC15"); else -> col("#FF8C8C") }
        t(c, wTxt, leftEdge + 2f * s, base, wSize, wCol, true)
        val pctX = leftEdge + 2f * s + w(wTxt, wSize) + 3f * s
        val w3 = wTxt.length >= 3
        // 3 cyfry (100): bez "%", male "W'" nad liczba - nic nie stoi przy luku
        if (w3) t(c, "W′", leftEdge + 2f * s, base - wSize * 0.72f - 5f * s, 16f * s, UNIT, false)
        else t(c, "%", pctX, base, 17f * s, UNIT, false)
        // etykieta nad "%": nie moze wejsc na luk mocy - zmniejsz czcionke, jesli trzeba
        val lblBase = base - 17f * s * 0.72f - 3f * s
        var lblSize = 16f * s
        val yTop = lblBase - lblSize * 0.72f
        val rOut = r + sw / 2f
        val dyA = (cy - yTop).coerceAtMost(rOut)
        val arcX = lox - kotlin.math.sqrt(rOut * rOut - dyA * dyA) - 3f * s
        while (lblSize > 10f && pctX + w("W′", lblSize, false) > arcX) lblSize -= 0.5f
        if (!w3) t(c, "W′", pctX, lblBase, lblSize, UNIT, false)

        // --- prawy brzeg: KAD + kadencja, srednia kadencja z trendem, BIEG + bieg na cala szerokosc
        val rightEdge = W - 4f * s
        val cv = d.cadence?.toString() ?: "—"
        t(c, cv, rightEdge, topBase, big, if (d.cadence != null) WHITE else NONE, true, Paint.Align.RIGHT)
        // etykieta KAD wyrownana do gornej krawedzi cyfr kadencji
        t(c, "KAD", rightEdge - w(cv, big) - 5f * s, topBase - big * 0.72f + 15f * s * 0.72f, 15f * s, UNIT, false, Paint.Align.RIGHT)
        val gx0 = rox + r + sw / 2f + 4f * s
        val gx1 = W - 6f * s
        val gSize = 50f * s                            // bieg zawsze tej samej wielkosci
        t(c, "BIEG", gx0, base - gSize * 0.72f - 4f * s, 15f * s, UNIT, false)
        val gtxt = if (d.gearFront != null && d.gearRear != null) "${d.gearFront}×${d.gearRear}" else "—"
        tp.typeface = bold; tp.textSize = gSize; tp.textAlign = Paint.Align.LEFT; tp.color = if (d.gearRear != null) WHITE else NONE
        val nat = tp.measureText(gtxt)
        tp.textScaleX = if (nat > 0f) ((gx1 - gx0) / nat).coerceIn(0.5f, 1.6f) else 1f
        c.drawText(gtxt, gx0, base, tp)
        tp.textScaleX = 1f
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

    private fun drawCenterCompact(c: Canvas, d: KokpitInstData, cx: Float, cy: Float, r: Float, sw: Float) {
        val inner = r - sw / 2f
        val gap = inner * 0.06f
        val hSize = 17f
        val hBase = cy - inner * 0.66f
        val vBase = cy - inner * 0.30f
        val sBase = cy - inner * 0.02f
        fp.color = col("#2A3038"); c.drawRect(cx - 1f, hBase - hSize, cx + 1f, cy - 4f, fp)
        t(c, "W", cx - gap, hBase, hSize, UNIT, true, Paint.Align.RIGHT)
        t(c, "km/h", cx + gap, hBase, hSize, UNIT, true)
        val pv = d.powerW?.toString() ?: "—"
        val sv = d.speedKmh?.let { fmt("%.0f", it) } ?: "—"
        val maxW = inner * 0.90f
        var vs = inner * 0.46f
        while (vs > 12f && (w(pv, vs) > maxW || w(sv, vs) > maxW)) vs -= 1f
        t(c, pv, cx - gap, vBase, vs, if (d.powerW != null) d.powerColor else NONE, true, Paint.Align.RIGHT)
        t(c, sv, cx + gap, vBase, vs, if (d.speedKmh != null) d.speedColor else NONE, true)
        // CPe5 i srednia predkosc (jak trojkaty na luku: bialy / zolty)
        val ss = inner * 0.25f
        val cpe = d.cpe5W?.let { fmt("%.0f", it) } ?: "—"
        t(c, cpe, cx - gap, sBase, ss, WHITE, true, Paint.Align.RIGHT)
        tri(c, cx - gap - w(cpe, ss) - 14f, sBase - ss * 0.36f, 7f, WHITE)
        val av = d.avgSpeedKmh?.let { fmt("%.0f", it) } ?: "—"
        tri(c, cx + gap + 7f, sBase - ss * 0.36f, 7f, YEL)
        t(c, av, cx + gap + 17f, sBase, ss, WHITE, true)
    }

    /** maly trojkat-znacznik (jak na luku), srodek (x, y) */
    private fun tri(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val p = Path(); p.moveTo(x - r, y - r * 0.8f); p.lineTo(x + r, y - r * 0.8f); p.lineTo(x, y + r * 0.9f); p.close()
        fp.color = color; c.drawPath(p, fp)
    }

    private fun drawLeftCompact(c: Canvas, d: KokpitInstData, x: Float, wd: Float, H: Float) {
        // tetno: strefa albo bpm (przelacznik w SETUP)
        val z = d.hrZone
        val showZone = d.hrShowZone && z != null
        val txt = if (showZone) "Z$z" else d.hr?.toString() ?: "—"
        val colr = if (showZone) col(HRZ[(z!! - 1).coerceIn(0, 4)]) else if (d.hr != null) WHITE else NONE
        var big = H * 0.40f
        while (big > 12f && w(txt, big) > wd * 0.78f) big -= 1f
        t(c, txt, x, H * 0.42f, big, colr, true)
        if (!showZone && d.hr != null) t(c, "bpm", x + w(txt, big) + 5f, H * 0.42f, 17f, UNIT, false)
        // W' bal: etykieta, pasek, liczba w kolorze zapasu
        val wb = d.wbalPct
        val by = H * 0.64f; val bh = H * 0.09f
        t(c, "W′", x, by - 4f, 17f, UNIT, true)
        val vs = H * 0.30f
        val vtxt = wb?.toString() ?: "—"
        val vx = x + wd - w(vtxt, vs)
        val bx = x + w("W′", 17f, true) + 6f
        val bw = (vx - 8f - bx).coerceAtLeast(10f)
        fp.color = TRACK; c.drawRect(bx, by + 4f, bx + bw, by + 4f + bh, fp)
        if (wb != null) {
            val wc = when { wb > 50 -> col("#2F7D4A"); wb >= 20 -> col("#C9A227"); else -> col("#C2412D") }
            fp.color = wc; c.drawRect(bx, by + 4f, bx + bw * wb.coerceIn(0, 100) / 100f, by + 4f + bh, fp)
        }
        val tc = when { wb == null -> NONE; wb > 50 -> col("#4ADE80"); wb >= 20 -> col("#FACC15"); else -> col("#FF8C8C") }
        t(c, vtxt, vx, H * 0.92f, vs, tc, true)
    }

    private fun drawRightCompact(c: Canvas, d: KokpitInstData, x: Float, wd: Float, H: Float) {
        val r = x + wd
        val cv = d.cadence?.toString() ?: "—"
        val big = H * 0.40f
        t(c, cv, r, H * 0.42f, big, if (d.cadence != null) WHITE else NONE, true, Paint.Align.RIGHT)
        t(c, "rpm", r - w(cv, big) - 5f, H * 0.42f, 17f, UNIT, false, Paint.Align.RIGHT)
        // pasek kadencji ze strefa optymalna i srednia (zolty trojkat)
        val bx = x; val bwid = wd; val by = H * 0.53f; val bh = H * 0.07f
        fun px(rpm: Int) = bx + bwid * ((rpm - 40).coerceIn(0, 80) / 80f)
        fp.color = TRACK; c.drawRect(bx, by, bx + bwid, by + bh, fp)
        fp.color = col("#2F7D4A"); c.drawRect(px(d.optCadLow), by, px(d.optCadHigh), by + bh, fp)
        d.cadenceAvg?.let { a ->
            val ax = px(a); val p = Path(); p.moveTo(ax - 6f, by - 9f); p.lineTo(ax + 6f, by - 9f); p.lineTo(ax, by - 1f); p.close()
            fp.color = YEL; c.drawPath(p, fp)
        }
        d.cadence?.let { cad -> val mx = px(cad); fp.color = DARK; c.drawRect(mx - 3f, by - 3f, mx + 3f, by + bh + 3f, fp); fp.color = WHITE; c.drawRect(mx - 1.5f, by - 3f, mx + 1.5f, by + bh + 3f, fp) }
        // bieg (bez wskazania docelowego)
        val gtxt = if (d.gearFront != null && d.gearRear != null) "${d.gearFront}×${d.gearRear}" else "—"
        var gs = H * 0.30f
        while (gs > 12f && w(gtxt, gs) > wd) gs -= 1f
        t(c, gtxt, r, H * 0.92f, gs, if (d.gearRear != null) WHITE else NONE, true, Paint.Align.RIGHT)
    }

    private fun drawLeft(c: Canvas, d: KokpitInstData, x: Float, wd: Float, H: Float) {
        val lab = H * 0.10f
        val big = H * 0.30f
        // TETNO
        t(c, "TĘTNO", x, H * 0.13f, lab, LBL, true)
        val z = d.hrZone
        val zc = if (z != null) col(HRZ[(z - 1).coerceIn(0, 4)]) else NONE
        var xx = x
        val zs = z?.let { "Z$it" } ?: "—"
        t(c, zs, xx, H * 0.44f, big, zc, true); xx += w(zs, big) + 4f
        t(c, d.hr?.toString() ?: "", xx, H * 0.29f, H * 0.14f, col("#C9D2DC"), true)
        t(c, d.hrAvg?.let { "Ø $it" } ?: "", xx, H * 0.44f, H * 0.13f, AVG, false)
        // drabinka stref
        val ly = H * 0.50f; val lh = H * 0.045f
        val segW = (wd - 4 * 2f) / 5f
        for (k in 0 until 5) {
            fp.color = col(HRZ[k]); fp.alpha = if (z == k + 1) 255 else 80
            val hh = if (z == k + 1) lh * 1.6f else lh
            c.drawRect(x + k * (segW + 2f), ly + lh * 1.6f - hh, x + k * (segW + 2f) + segW, ly + lh * 1.6f, fp)
        }
        fp.alpha = 255
        // W'
        t(c, "W′ BAL %", x, H * 0.72f, lab, LBL, true)
        val wb = d.wbalPct
        val by = H * 0.80f; val bh = H * 0.10f; val bw = wd * 0.56f
        fp.color = TRACK; c.drawRect(x, by, x + bw, by + bh, fp)
        if (wb != null) {
            val wc = when { wb > 50 -> col("#2F7D4A"); wb >= 20 -> col("#C9A227"); else -> col("#C2412D") }
            fp.color = wc; c.drawRect(x, by, x + bw * wb.coerceIn(0, 100) / 100f, by + bh, fp)
            val tc = when { wb > 50 -> col("#4ADE80"); wb >= 20 -> col("#FACC15"); else -> col("#FF8C8C") }
            t(c, wb.toString(), x + bw + 6f, by + bh * 1.05f, H * 0.22f, tc, true)
        } else t(c, "—", x + bw + 6f, by + bh, H * 0.18f, NONE, true)
    }

    private fun drawRight(c: Canvas, d: KokpitInstData, x: Float, wd: Float, H: Float) {
        val r = x + wd
        val lab = H * 0.10f
        val big = H * 0.30f
        t(c, "KADENCJA rpm", r, H * 0.13f, lab, LBL, true, Paint.Align.RIGHT)
        val cv = d.cadence?.toString() ?: "—"
        t(c, cv, r, H * 0.42f, big, if (d.cadence != null) WHITE else NONE, true, Paint.Align.RIGHT)
        d.cadenceAvg?.let { t(c, "Ø $it", r - w(cv, big) - 8f, H * 0.42f, H * 0.16f, AVG, false, Paint.Align.RIGHT) }
        // pasek kadencji 40..120 rpm ze strefa optymalna i srednia
        val bx = x + wd * 0.1f; val bwid = wd * 0.9f; val by = H * 0.49f; val bh = H * 0.07f
        fun cx(rpm: Int) = bx + bwid * ((rpm - 40).coerceIn(0, 80) / 80f)
        fp.color = TRACK; c.drawRect(bx, by, bx + bwid, by + bh, fp)
        fp.color = col("#2F7D4A"); c.drawRect(cx(d.optCadLow), by, cx(d.optCadHigh), by + bh, fp)
        d.cadenceAvg?.let { a ->
            val ax = cx(a); val p = Path(); p.moveTo(ax - 5f, by - 8f); p.lineTo(ax + 5f, by - 8f); p.lineTo(ax, by - 1f); p.close()
            fp.color = YEL; c.drawPath(p, fp)
        }
        d.cadence?.let { cad -> val mx = cx(cad); fp.color = DARK; c.drawRect(mx - 3f, by - 3f, mx + 3f, by + bh + 3f, fp); fp.color = WHITE; c.drawRect(mx - 1.5f, by - 3f, mx + 1.5f, by + bh + 3f, fp) }
        // BIEG
        val gtxt = if (d.gearFront != null && d.gearRear != null) "${d.gearFront}×${d.gearRear}" else "—"
        t(c, gtxt, r, H * 0.73f, H * 0.17f, if (d.gearRear != null) WHITE else NONE, true, Paint.Align.RIGHT)
        t(c, "BIEG", r - w(gtxt, H * 0.17f) - 6f, H * 0.73f, lab, LBL, true, Paint.Align.RIGHT)
        val cogs = d.cogs
        if (cogs.isNotEmpty()) {
            val n = cogs.size
            val gap = 2f
            val cw = ((wd * 0.9f) - gap * (n - 1)) / n
            val baseY = H - 6f
            val maxH = H * 0.19f
            for ((i, cg) in cogs.withIndex()) {
                val hh = maxH * (0.3f + 0.7f * i / (n - 1).coerceAtLeast(1))
                val x0 = r - (n - i) * (cw + gap) + gap
                fp.color = when { cg == d.gearRear -> WHITE; cg == d.recCog -> col("#4ADE80"); else -> col("#3A4552") }
                c.drawRect(x0, baseY - hh, x0 + cw, baseY, fp)
            }
        }
    }
}
