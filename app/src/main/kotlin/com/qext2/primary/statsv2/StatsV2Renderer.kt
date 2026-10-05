package com.qext2.primary.statsv2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import kotlin.math.max
import kotlin.math.min

/**
 * STATS v2 — pole rysowane jako obrazek (docs/FIELD_LOOK_PLAN.md, mockup "STATS — wartosci w plakietkach").
 * Uklad bazowy 480x760 px, skalowany do rozmiaru komorki. Brak danych = szare "brak" (nigdy zmyslona wartosc).
 */
data class StatsV2Data(
    val np: Int? = null,
    val npZone: Int? = null,          // 1..6 wg NP / CP
    val ifv: Float? = null,
    val vi: Float? = null,
    val rsrv: Int? = null,
    val xss: Float? = null,
    val kcal: Int? = null,
    val hasRoute: Boolean = false,
    val doneKm: Float = 0f,
    val totalKm: Float? = null,
    val etaMs: Long? = null,
    val avgGrossKmh: Float? = null,
    val movingSec: Long = 0L,
    val stopsSec: Long = 0L,
    val surfPaved: Float? = null,     // km do konca; null = brak profilu
    val surfGravel: Float? = null,
    val surfLoose: Float? = null,
    val ascDone: Int? = null,
    val ascLeft: Int? = null,
    val carbRate: Int? = null,
    val carbSpent: Int? = null,
    val fluidRate: Float? = null,
    val cadAvg: Int? = null,
    val batDrain: Float? = null,
    val batLeftSec: Long? = null,
    val demo: Boolean = false,
)

object StatsV2Renderer {
    private const val BW = 480f
    private const val BH = 760f

    private val BG = Color.parseColor("#2A3038")
    private val CELL = Color.parseColor("#14181D")
    private val LABEL = Color.parseColor("#D5DCE3")
    private val UNIT = Color.parseColor("#9AA5B1")
    private val SUB = Color.parseColor("#C9D2DC")
    private val NONE = Color.parseColor("#6B7682")
    private val WHITE = Color.WHITE
    private val PILL = Color.parseColor("#111315")
    private val TRACK = Color.parseColor("#2B3542")
    private val AMBER = Color.parseColor("#F59E0B")

    // --- ikony (Barberfish, Apache 2.0; siatka 38x38) i Material Symbols (960)
    private const val P_BAR = "M6,0 H31 V2 H6 Z"
    private const val P_BOLT = "M18.502,16.002V16.502H19.002H27.227L19.499,33.671V22V21.5H18.999H10.773L14.957,12.205L18.502,4.329V16.002Z"
    private const val P_CAD = "M19,7 A12,12,0,0,1,31,19 A12,12,0,0,1,19,31 A12,12,0,0,1,7,19 A12,12,0,0,1,19,7 Z M19,10 A9,9,0,0,0,10,19 A9,9,0,0,0,19,28 A9,9,0,0,0,28,19 A9,9,0,0,0,19,10 Z M20.1,20.1 L35.1,5.1 L32.9,2.9 L17.9,17.9 Z M12.3,27.9 L7.4,32.8 L5.2,30.6 L10.1,25.7 Z"
    private const val P_GRADE = "M3,34 L3,32 L13,16 L19,22 L28,8 L35,20 L35,34 Z"
    private const val P_GEL = "M12,5 H26 L24,10 V31 A3,3,0,0,1,21,34 H17 A3,3,0,0,1,14,31 V10 Z M17,15 H21 V27 H17 Z"
    private const val P_DROP = "M19,4 C19,4 9,16 9,23 A10,10,0,0,0,29,23 C29,16 19,4 19,4 Z"
    private const val P_FLAME = "M19,3 C21,10 28,13 28,23 A9,9,0,0,1,10,23 C10,18 13,15 15,13 C15,17 17,19 19,19 C18,13 19,8 19,3 Z"
    private const val P_BARS = "M5,26 H12 V34 H5 Z M15.5,18 H22.5 V34 H15.5 Z M26,9 H33 V34 H26 Z"
    private const val P_RING = "M19,4 A15,15,0,1,1,18.99,4 Z M19,7 A12,12,0,1,0,19.01,7 Z"
    private const val P_BATT = "M15,3 H23 V6 H15 Z M10,6 H28 V35 H10 Z M13,9 V32 H25 V9 Z M15,22 H23 V30 H15 Z"
    private const val P_ROAD = "M160-160v-640h80v640h-80Zm280 0v-160h80v160h-80Zm280 0v-640h80v640h-80ZM440-400v-160h80v160h-80Zm0-240v-160h80v160h-80Z"
    private const val P_ROUTE = "M247-167q-47-47-47-113v-327q-35-13-57.5-43.5T120-720q0-50 35-85t85-35q50 0 85 35t35 85q0 39-22.5 69.5T280-607v327q0 33 23.5 56.5T360-200q33 0 56.5-23.5T440-280v-400q0-66 47-113t113-47q66 0 113 47t47 113v327q35 13 57.5 43.5T840-240q0 50-35 85t-85 35q-50 0-85-35t-35-85q0-39 22.5-70t57.5-43v-327q0-33-23.5-56.5T600-760q-33 0-56.5 23.5T520-680v400q0 66-47 113t-113 47q-66 0-113-47Z"

    private val pathCache = HashMap<String, Path?>()
    private fun path(d: String): Path? = pathCache.getOrPut(d) {
        try { PathParser.createPathFromPathData(d) } catch (_: Exception) { null }
    }

    private val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = condensed }
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun fmt(p: String, vararg a: Any): String = String.format(java.util.Locale.US, p, *a)

    private var sx = 1f
    private var sy = 1f
    private var s = 1f
    private fun X(v: Float) = v * sx
    private fun Y(v: Float) = v * sy
    private fun F(v: Float) = v * s

    @Synchronized
    fun render(w: Int, h: Int, d: StatsV2Data): Bitmap {
        val bw = w.coerceAtLeast(120)
        val bh = h.coerceAtLeast(160)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        sx = bw / BW; sy = bh / BH; s = min(sx, sy)
        c.drawColor(BG)

        val g = 2f
        val rows = floatArrayOf(126f, 116f, 200f, 92f, 92f)
        var y = 0f
        val col3 = (BW - 2 * g) / 3f
        // 1: NP / IF / VI
        drawNp(c, RectF(0f, y, col3, y + rows[0]), d)
        drawIf(c, RectF(col3 + g, y, 2 * col3 + g, y + rows[0]), d.ifv)
        drawValueCell(c, RectF(2 * col3 + 2 * g, y, BW, y + rows[0]), listOf(P_BOLT), null, "VI", "",
            d.vi?.let { fmt("%.2f", it) }, 70f, viColor(d.vi))
        y += rows[0] + g
        // 2: RSRV / XSS / KCAL
        drawRsrv(c, RectF(0f, y, col3, y + rows[1]), d.rsrv)
        drawValueCell(c, RectF(col3 + g, y, 2 * col3 + g, y + rows[1]), listOf(P_BARS), null, "XSS", "",
            d.xss?.let { fmt("%.0f", it) }, 66f, WHITE)
        drawValueCell(c, RectF(2 * col3 + 2 * g, y, BW, y + rows[1]), listOf(P_FLAME), null, "KCAL", "",
            d.kcal?.toString(), 62f, WHITE)
        y += rows[1] + g
        // 3: trasa
        drawRoute(c, RectF(0f, y, BW, y + rows[2]), d)
        y += rows[2] + g
        // 4: nawierzchnia
        drawSurface(c, RectF(0f, y, BW, y + rows[3]), d)
        y += rows[3] + g
        // 5: przewyzszenie
        drawAscent(c, RectF(0f, y, BW, y + rows[4]), d)
        y += rows[4] + g
        // 6: CARB+woda (45%) / KAD (20%) / BAT (35%)
        val inner = BW - 2 * g
        val wFood = inner * 0.45f
        val wKad = inner * 0.20f
        val bottom = BH
        drawFood(c, RectF(0f, y, wFood, bottom), d)
        drawValueCell(c, RectF(wFood + g, y, wFood + g + wKad, bottom), listOf(P_BAR, P_CAD), null, "KAD", "",
            d.cadAvg?.toString(), 60f, WHITE)
        drawBattery(c, RectF(wFood + wKad + 2 * g, y, BW, bottom), d)
        return bmp
    }

    // ---------------------------------------------------------------- pomocnicze
    private fun rect(r: RectF) = RectF(X(r.left), Y(r.top), X(r.right), Y(r.bottom))

    private fun cell(c: Canvas, r: RectF) {
        fp.color = CELL
        c.drawRect(rect(r), fp)
    }

    private fun textW(t: String, size: Float): Float {
        tp.textSize = size
        return tp.measureText(t)
    }

    private fun fit(t: String, size: Float, maxW: Float): Float {
        var sz = size
        while (sz > 10f && textW(t, sz) > maxW) sz -= 1f
        return sz
    }

    /** Tekst o realnym rozmiarze [size] (px) wyrownany pionowo do srodka cy (px). */
    private fun text(c: Canvas, t: String, x: Float, cy: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) {
        tp.textSize = size
        tp.color = color
        tp.textAlign = align
        val fm = tp.fontMetrics
        c.drawText(t, x, cy - (fm.ascent + fm.descent) / 2f, tp)
    }

    private val EVEN_ODD = setOf(P_CAD, P_GEL, P_RING, P_BATT)

    private fun icon(c: Canvas, parts: List<String>, x: Float, y: Float, size: Float, color: Int, material: Boolean = false) {
        val m = Matrix()
        if (material) {
            m.postTranslate(0f, 960f)
            m.postScale(size / 960f, size / 960f)
        } else {
            m.postScale(size / 38f, size / 38f)
        }
        m.postTranslate(x, y)
        fp.color = color
        for (pd in parts) {
            val p0 = path(pd) ?: continue
            val p = Path(p0)
            if (pd in EVEN_ODD) p.fillType = Path.FillType.EVEN_ODD
            p.transform(m)
            c.drawPath(p, fp)
        }
    }

    /** Pasek etykiety: ikona + nazwa + jednostka; zwraca dolna krawedz paska (px). */
    private fun label(c: Canvas, r: RectF, iconPath: List<String>?, name: String, unit: String, material: Boolean = false): Float {
        val left = X(r.left) + F(10f)
        val top = Y(r.top) + F(6f)
        val hRow = F(28f)
        var x = left
        if (iconPath != null) {
            icon(c, iconPath, x, top + F(2f), F(24f), Color.parseColor("#F0F3F6"), material)
            x += F(30f)
        }
        text(c, name, x, top + hRow / 2f, F(24f), LABEL)
        x += textW(name, F(24f)) + F(6f)
        if (unit.isNotEmpty()) text(c, unit, x, top + hRow / 2f, F(22f), UNIT)
        return top + hRow
    }

    private fun rightLabel(c: Canvas, r: RectF, parts: List<Triple<String, Float, Int>>) {
        val top = Y(r.top) + F(6f)
        val cy = top + F(14f)
        var x = X(r.right) - F(10f)
        for ((t, size, col) in parts.reversed()) {
            text(c, t, x, cy, F(size), col, Paint.Align.RIGHT)
            x -= textW(t, F(size)) + F(6f)
        }
    }

    private fun none(c: Canvas, cx: Float, cy: Float, t: String = "brak") = text(c, t, cx, cy, F(26f), NONE, Paint.Align.CENTER)

    private fun drawValueCell(c: Canvas, r: RectF, iconPath: List<String>, materialIcon: Boolean?, name: String, unit: String,
                              value: String?, size: Float, color: Int) {
        cell(c, r)
        val lb = label(c, r, iconPath, name, unit, materialIcon == true)
        val cx = (X(r.left) + X(r.right)) / 2f
        val cy = (lb + Y(r.bottom) - F(8f)) / 2f
        if (value == null) { none(c, cx, cy); return }
        val sz = fit(value, F(size), X(r.right) - X(r.left) - F(20f))
        text(c, value, cx, cy, sz, color, Paint.Align.CENTER)
    }

    private fun lum(col: Int): Double {
        fun f(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * f(Color.red(col)) + 0.7152 * f(Color.green(col)) + 0.0722 * f(Color.blue(col))
    }

    /** Liczba na pasku: plakietka gdy jest miejsce i tlo kolorowe; inaczej sam tekst w kontrascie. */
    private fun barText(c: Canvas, t: String, x: Float, cy: Float, size: Float, segW: Float, bg: Int, align: Paint.Align) {
        val tw = textW(t, size)
        val pad = F(6f)
        val need = tw + 2 * pad + F(12f)
        if (align == Paint.Align.CENTER && segW < tw + F(8f)) return
        if (segW >= need && lum(bg) >= 0.05) {
            tp.textSize = size
            val fm = tp.fontMetrics
            val hgt = (fm.descent - fm.ascent) * 0.82f
            val l = when (align) { Paint.Align.LEFT -> x; Paint.Align.RIGHT -> x - tw - 2 * pad; else -> x - tw / 2f - pad }
            fp.color = PILL
            c.drawRect(l, cy - hgt / 2f, l + tw + 2 * pad, cy + hgt / 2f, fp)
            text(c, t, l + pad, cy, size, WHITE)
        } else {
            val col = if (lum(bg) > 0.18) PILL else WHITE
            val xx = when (align) { Paint.Align.LEFT -> x + pad; Paint.Align.RIGHT -> x - pad; else -> x }
            text(c, t, xx, cy, size, col, align)
        }
    }

    // ---------------------------------------------------------------- moduly
    private fun zoneColor(z: Int?): Int = when (z) {
        1 -> Color.parseColor("#9CA3AF"); 2 -> Color.parseColor("#60A5FA"); 3 -> Color.parseColor("#4ADE80")
        4 -> Color.parseColor("#FACC15"); 5 -> Color.parseColor("#FB923C"); 6 -> Color.parseColor("#F87171")
        else -> WHITE
    }

    private fun viColor(vi: Float?): Int = when {
        vi == null || vi <= 1.05f -> WHITE
        vi < 1.10f -> Color.parseColor("#FACC15")
        vi < 1.20f -> Color.parseColor("#FB923C")
        else -> Color.parseColor("#F87171")
    }

    private fun drawNp(c: Canvas, r: RectF, d: StatsV2Data) {
        drawValueCell(c, r, listOf(P_BAR, P_BOLT), null, "NP", "W", d.np?.toString(), 78f, zoneColor(d.npZone))
        if (d.np != null && d.npZone != null) rightLabel(c, r, listOf(Triple("Z${d.npZone}", 22f, zoneColor(d.npZone))))
    }

    private fun drawIf(c: Canvas, r: RectF, v: Float?) {
        cell(c, r)
        val lb = label(c, r, listOf(P_BOLT), "IF", "")
        val l = X(r.left) + F(10f); val rr = X(r.right) - F(10f)
        val t = lb + F(6f); val b = Y(r.bottom) - F(8f)
        if (v == null) { none(c, (l + rr) / 2f, (t + b) / 2f); return }
        val zones = listOf(0.0f to 0.75f, 0.75f to 0.85f, 0.85f to 1.0f, 1.0f to 1.2f)
        val cols = intArrayOf(Color.parseColor("#4F6E80"), Color.parseColor("#F2C230"), Color.parseColor("#E9862B"), Color.parseColor("#E0563B"))
        val wTot = rr - l
        for (i in zones.indices) {
            fp.color = cols[i]
            val x0 = l + zones[i].first / 1.2f * wTot
            val x1 = l + zones[i].second / 1.2f * wTot - (if (i < 3) F(2f) else 0f)
            c.drawRect(x0, t, x1, b, fp)
        }
        val p = (min(v, 1.2f) / 1.2f)
        val mx = l + p * wTot
        fp.color = PILL; c.drawRect(mx - F(4f), t - F(4f), mx + F(4f), b + F(4f), fp)
        fp.color = WHITE; c.drawRect(mx - F(2f), t - F(4f), mx + F(2f), b + F(4f), fp)
        val txt = fmt("%.2f", v).removePrefix("0")
        val cy = (t + b) / 2f
        val pillW = textW(txt, F(42f)) + F(12f)
        if (mx - l - F(10f) >= pillW + F(4f)) barText(c, txt, l + F(4f), cy, F(42f), wTot, cols[0], Paint.Align.LEFT)
        else if (rr - mx - F(10f) >= pillW + F(4f)) barText(c, txt, rr - F(4f), cy, F(42f), wTot, cols[0], Paint.Align.RIGHT)
        else barText(c, txt, rr - F(4f), cy, F(36f), wTot, cols[0], Paint.Align.RIGHT)
    }

    private fun drawRsrv(c: Canvas, r: RectF, v: Int?) {
        cell(c, r)
        val lb = label(c, r, listOf(P_RING), "RSRV", "%")
        val l = X(r.left) + F(10f); val rr = X(r.right) - F(10f)
        val t = lb + F(6f); val b = Y(r.bottom) - F(8f)
        if (v == null) { none(c, (l + rr) / 2f, (t + b) / 2f); return }
        val pct = v.coerceIn(0, 100)
        val col = when { pct > 50 -> Color.parseColor("#2F7D4A"); pct >= 20 -> Color.parseColor("#C9A227"); else -> Color.parseColor("#C2412D") }
        fp.color = TRACK; c.drawRect(l, t, rr, b, fp)
        val fx = l + (rr - l) * pct / 100f
        fp.color = col; c.drawRect(l, t, fx, b, fp)
        val cy = (t + b) / 2f
        if (pct > 50) barText(c, pct.toString(), l + F(4f), cy, F(44f), fx - l, col, Paint.Align.LEFT)
        else barText(c, pct.toString(), rr - F(4f), cy, F(44f), rr - fx, TRACK, Paint.Align.RIGHT)
    }

    private fun hm(sec: Long): String = fmt("%d:%02d", sec / 3600, (sec % 3600) / 60)

    private fun clock(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return fmt("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    private fun drawRoute(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        val lb = label(c, r, listOf(P_ROUTE), "TRASA", "km", material = true)
        if (d.demo) rightLabel(c, r, listOf(Triple("DEMO", 22f, AMBER)))
        val l = X(r.left) + F(10f); val rr = X(r.right) - F(10f)
        // wiersz wartosci
        val vcy = lb + F(32f)
        var x = l
        val done = fmt("%.0f", d.doneKm)
        text(c, done, x, vcy, F(58f), WHITE); x += textW(done, F(58f)) + F(8f)
        val total = d.totalKm
        if (total != null) {
            text(c, "/", x, vcy + F(6f), F(30f), SUB); x += textW("/", F(30f)) + F(8f)
            val tt = fmt("%.0f", total)
            text(c, tt, x, vcy + F(6f), F(40f), WHITE); x += textW(tt, F(40f)) + F(6f)
        }
        text(c, "km", x, vcy + F(10f), F(22f), UNIT)
        val etaTxt = d.etaMs?.let { clock(it) }
        if (etaTxt != null) {
            text(c, etaTxt, rr, vcy, F(58f), WHITE, Paint.Align.RIGHT)
            text(c, "ETA", rr - textW(etaTxt, F(58f)) - F(8f), vcy + F(8f), F(24f), SUB, Paint.Align.RIGHT)
        } else {
            text(c, "ETA brak", rr, vcy + F(8f), F(24f), NONE, Paint.Align.RIGHT)
        }
        // pasek
        val bt = Y(r.top) + Y(112f); val bb = bt + Y(40f)
        fp.color = TRACK; c.drawRect(l, bt, rr, bb, fp)
        if (d.hasRoute && total != null && total > 0f) {
            val frac = (d.doneKm / total).coerceIn(0f, 1f)
            val fx = l + (rr - l) * frac
            fp.color = Color.parseColor("#3E7CB1"); c.drawRect(l, bt, fx, bb, fp)
            fp.color = WHITE; c.drawRect(fx - F(3f), bt - F(8f), fx + F(3f), bb + F(8f), fp)
            val left = fmt("zostało %.0f km", max(0f, total - d.doneKm))
            if (rr - fx > textW(left, F(28f)) + F(20f)) text(c, left, rr - F(8f), (bt + bb) / 2f, F(28f), SUB, Paint.Align.RIGHT)
        } else {
            none(c, (l + rr) / 2f, (bt + bb) / 2f, "brak trasy")
        }
        // stopka
        val fcy = Y(r.bottom) - F(24f)
        val avg = d.avgGrossKmh?.let { fmt("%.1f", it) } ?: "—"
        text(c, "Ø", l, fcy, F(22f), SUB)
        var fx2 = l + textW("Ø", F(22f)) + F(5f)
        text(c, avg, fx2, fcy, F(38f), WHITE); fx2 += textW(avg, F(38f)) + F(4f)
        text(c, "km/h", fx2, fcy + F(4f), F(20f), UNIT)
        val jazda = hm(d.movingSec)
        val jw = textW("jazda ", F(22f)) + textW(jazda, F(38f))
        val jx = (l + rr) / 2f - jw / 2f + F(10f)
        text(c, "jazda", jx, fcy, F(22f), SUB)
        text(c, jazda, jx + textW("jazda ", F(22f)), fcy, F(38f), WHITE)
        val st = hm(d.stopsSec)
        text(c, st, rr, fcy, F(38f), AMBER, Paint.Align.RIGHT)
        text(c, "postoje", rr - textW(st, F(38f)) - F(6f), fcy, F(22f), SUB, Paint.Align.RIGHT)
    }

    /** Pasek odcinkow z liczbami; segs: (wartosc, kolor, tekst). */
    private fun segBar(c: Canvas, l: Float, t: Float, rr: Float, b: Float, segs: List<Triple<Float, Int, String>>, minW: Float) {
        val g = F(2f)
        val n = segs.size
        val avail = rr - l - g * (n - 1)
        val sum = segs.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.0001f)
        val w = segs.map { (it.first / sum) * avail }.toMutableList()
        // minimalna szerokosc dla odcinkow z tekstem
        for (i in w.indices) if (segs[i].third.isNotEmpty() && segs[i].first > 0f && w[i] < minW) w[i] = minW
        val over = w.sum() - avail
        if (over > 0f) {
            val big = w.indices.filter { w[it] > minW }
            val bigSum = big.sumOf { w[it].toDouble() }.toFloat()
            if (bigSum > 0f) for (i in big) w[i] -= over * (w[i] / bigSum)
        }
        var x = l
        val cy = (t + b) / 2f
        for (i in segs.indices) {
            if (w[i] <= 0.5f) continue
            fp.color = segs[i].second
            c.drawRect(x, t, x + w[i], b, fp)
            if (segs[i].third.isNotEmpty()) barText(c, segs[i].third, x + w[i] / 2f, cy, F(34f), w[i], segs[i].second, Paint.Align.CENTER)
            x += w[i] + g
        }
    }

    private fun drawSurface(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        label(c, r, listOf(P_ROAD), "NAWIERZCHNIA", "km", material = true)
        rightLabel(c, r, listOf(Triple("za tobą", 22f, SUB), Triple(fmt("%.0f", d.doneKm), 26f, WHITE)))
        val l = X(r.left) + F(10f); val rr = X(r.right) - F(10f)
        val b = Y(r.bottom) - F(8f); val t = b - F(44f)
        val p = d.surfPaved; val gv = d.surfGravel; val lo = d.surfLoose
        if (p == null || gv == null || lo == null || (p + gv + lo) <= 0.05f) { none(c, (l + rr) / 2f, (t + b) / 2f, "brak danych"); return }
        fun k(v: Float) = if (v >= 0.5f) fmt("%.0f", v) else ""
        segBar(c, l, t, rr, b, listOf(
            Triple(d.doneKm.coerceAtLeast(0f), Color.parseColor("#1E252D"), ""),
            Triple(p, Color.parseColor("#C9D2DC"), k(p)),
            Triple(gv, Color.parseColor("#D9A04E"), k(gv)),
            Triple(lo, Color.parseColor("#E0563B"), k(lo)),
        ), F(30f))
    }

    private fun drawAscent(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        label(c, r, listOf(P_GRADE), "PRZEWYŻSZENIE", "m")
        val l = X(r.left) + F(10f); val rr = X(r.right) - F(10f)
        val b = Y(r.bottom) - F(8f); val t = b - F(44f)
        val dn = d.ascDone; val lf = d.ascLeft
        if (dn == null || lf == null || dn + lf <= 0) { none(c, (l + rr) / 2f, (t + b) / 2f); return }
        rightLabel(c, r, listOf(Triple("razem", 22f, SUB), Triple((dn + lf).toString(), 26f, WHITE)))
        segBar(c, l, t, rr, b, listOf(
            Triple(dn.toFloat(), Color.parseColor("#2F7D4A"), "↑ $dn"),
            Triple(lf.toFloat(), TRACK, lf.toString()),
        ), F(40f))
    }

    private fun drawFood(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        val l = X(r.left); val rr = X(r.right)
        val split = l + (rr - l) * (26.67f / 45f)
        fp.color = BG; c.drawRect(split - F(1f), Y(r.top), split + F(1f), Y(r.bottom), fp)
        // CARB
        val carbR = RectF(r.left, r.top, r.left + (r.right - r.left) * (26.67f / 45f), r.bottom)
        val lb = label(c, carbR, listOf(P_GEL), "CARB", "")
        val cl = l + F(8f); val cr = split - F(8f)
        val b = Y(r.bottom) - F(8f)
        val h = b - lb
        val y1 = lb + h * 0.3f; val y2 = lb + h * 0.78f
        fun line(cy: Float, v: String?, unit: String, cap: String) {
            if (v == null) { text(c, "brak", cl, cy, F(24f), NONE) }
            else {
                text(c, v, cl, cy, F(30f), WHITE)
                text(c, unit, cl + textW(v, F(30f)) + F(4f), cy + F(3f), F(16f), UNIT)
            }
            text(c, cap, cr, cy + F(3f), F(16f), UNIT, Paint.Align.RIGHT)
        }
        line(y1, d.carbRate?.toString(), "g/h", "cel")
        line(y2, d.carbSpent?.toString(), "g", "spal.")
        // woda
        val wcx = (split + rr) / 2f
        val top = Y(r.top) + F(6f)
        icon(c, listOf(P_DROP), wcx - F(12f), top + F(2f), F(24f), Color.parseColor("#F0F3F6"))
        val mid = (lb + b) / 2f
        val fv = d.fluidRate?.let { fmt("%.1f", it) }
        if (fv == null) none(c, wcx, mid) else {
            text(c, fv, wcx, mid - F(8f), F(34f), WHITE, Paint.Align.CENTER)
            text(c, "l/h", wcx, mid + F(22f), F(20f), UNIT, Paint.Align.CENTER)
        }
    }

    private fun drawBattery(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        val lb = label(c, r, listOf(P_BATT), "BAT", "")
        val cx = (X(r.left) + X(r.right)) / 2f
        val b = Y(r.bottom) - F(8f)
        val mid = (lb + b) / 2f
        val v = d.batDrain?.let { fmt("%.0f", it) }
        if (v == null) { none(c, cx, mid); return }
        val vw = textW(v, F(56f)) + F(4f) + textW("%/h", F(24f))
        text(c, v, cx - vw / 2f, mid - F(10f), F(56f), WHITE)
        text(c, "%/h", cx - vw / 2f + textW(v, F(56f)) + F(4f), mid - F(4f), F(24f), UNIT)
        d.batLeftSec?.let { text(c, "~" + hm(it) + " h", cx, b - F(12f), F(24f), SUB, Paint.Align.CENTER) }
    }
}
