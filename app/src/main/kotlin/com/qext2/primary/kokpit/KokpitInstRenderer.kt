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
    private val AVG = Color.parseColor("#7C8794")
    private val NONE = Color.parseColor("#6B7682")
    private val TRACK = Color.parseColor("#2B3542")
    private val DARK = Color.parseColor("#111315")
    private val YEL = Color.parseColor("#F2C230")
    private val WHITE = Color.WHITE

    private val PZ = listOf(0.00f to "#6B7280", 0.55f to "#3B82F6", 0.75f to "#22C55E", 0.90f to "#EAB308", 1.05f to "#F97316", 1.20f to "#EF4444")
    private const val PMAX = 1.5f
    private const val SMAX = 45f
    private val SZ = listOf(0f to "#4F6E80", 15f to "#3E7CB1", 25f to "#60A5FA", 35f to "#93C5FD")
    private val HRZ = listOf("#9CA3AF", "#60A5FA", "#4ADE80", "#FACC15", "#F87171")

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
        val cx = W / 2f
        val sw = (H * 0.10f).coerceIn(10f, 18f)          // grubosc luku
        val r = min(H - sw / 2f - 8f, W * 0.245f)         // promien (srodek luku)
        val cy = H - 6f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        drawArcs(c, d, oval, cx, cy, r, sw)
        drawCenter(c, d, cx, cy, r, sw)
        val sideW = cx - r - sw / 2f - 14f
        drawLeft(c, d, 6f, sideW, H)
        drawRight(c, d, W - 6f - sideW, sideW, H)
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
            arc(c, oval, a0 + 0.5f, (a1 - a0 - 1f).coerceAtLeast(0.5f), col(PZ[k].second), sw, 82)
            if (k > 0) tick(c, cx, cy, r, sw, a0, col(PZ[k].second))
        }
        for (k in SZ.indices) {
            val a0 = sAng(SZ[k].first); val a1 = sAng(if (k + 1 < SZ.size) SZ[k + 1].first else SMAX)
            arc(c, oval, a0 + 0.5f, (a1 - a0 - 1f).coerceAtLeast(0.5f), col(SZ[k].second), sw, 82)
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
            val tc = when { wb > 50 -> col("#4ADE80"); wb >= 20 -> col("#FACC15"); else -> col("#F87171") }
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
