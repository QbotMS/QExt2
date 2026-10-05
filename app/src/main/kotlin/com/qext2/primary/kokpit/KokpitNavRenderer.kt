package com.qext2.primary.kokpit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/** Dane pola KOKPIT nawigacja. null = brak danych. */
data class KokpitNavData(
    val msg: RouteMsg = RouteMsg(MsgKind.NONE, "", "", "#9AA5B1"),
    val doneKm: Float = 0f,
    val totalKm: Float? = null,
    val leftKm: Float? = null,
    val duskMs: Long? = null,
    val etaMs: Long? = null,
    /** pozostala czesc trasy: (dlugosc km, kolor #RRGGBB) po kolei; null = brak profilu nawierzchni */
    val ahead: List<Pair<Float, String>>? = null,
    val gradePct: Float? = null,
    val ascDone: Int? = null,
    val ascLeft: Int? = null,
    val tempC: Float? = null,
    val rainNowMmH: Float? = null,
    val rainSoon: RainSoon? = null,
    val windMps: Float? = null,
    val windDirDeg: Int? = null,
    /** kierunek wiatru wzgledem jazdy z rozszerzenia karoo-headwind (0 = strzalka w gore); null = brak */
    val windRelDeg: Int? = null,
    /** etykieta nastepnego zdarzenia: "zmrok" albo "świt" (godzina w duskMs) */
    val twilightLabel: String = "zmrok",
    /** postoje >= 10 min: km na trasie */
    val stopsKm: List<Float> = emptyList(),
    val demo: Boolean = false,
)

/**
 * Uklad dopasowany do wysokosci pola (Karoo daje 143 px przy 2 polach na mapie, 216 px przy jednym):
 * komunikat (gora) | wartosci | cienki pasek trasy | wiersz z ikonami. Czcionki rosna z wysokoscia
 * i sa zmniejszane tylko, gdy wiersz nie miesci sie na szerokosc. Etykiety dolnego wiersza zastapione ikonami.
 */
object KokpitNavRenderer {
    private val BG = Color.parseColor("#14181D")
    private val MSGBG = Color.parseColor("#1E2731")
    private val LBL = Color.parseColor("#AEB8C4")
    private val UNIT = Color.parseColor("#9AA5B1")
    private val NONE = Color.parseColor("#6B7682")
    private val WHITE = Color.WHITE
    private val TRACK = Color.parseColor("#2B3542")
    private val DONE = Color.parseColor("#3E7CB1")
    private val ORANGE = Color.parseColor("#FB923C")
    private val RED = Color.parseColor("#F87171")
    private val BLUE = Color.parseColor("#60A5FA")
    private val DARK = Color.parseColor("#111315")

    private val bold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val reg: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private fun fmt(p: String, vararg a: Any): String = String.format(java.util.Locale.US, p, *a)

    /** czesc wiersza: tekst (rel = rozmiar wzgledem wartosci) albo ikona (icon != 0, szerokosc rel*size) */
    private class Part(val text: String, val rel: Float, val color: Int, val bold: Boolean, val icon: Int = 0, val arg: Float = 0f)

    private const val IC_SUN = 1
    private const val IC_TRI = 2
    private const val IC_UP = 3
    private const val IC_TEMP = 4
    private const val IC_DROP = 5
    private const val IC_ARROW = 6

    private fun w(t: String, size: Float, b: Boolean): Float { tp.typeface = if (b) bold else reg; tp.textSize = size; return tp.measureText(t) }

    private fun partW(p: Part, size: Float): Float = if (p.icon != 0) p.rel * size else w(p.text, p.rel * size, p.bold)

    private fun groupW(g: List<Part>, size: Float): Float = g.sumOf { partW(it, size).toDouble() }.toFloat() + size * 0.1f * (g.size - 1)

    /** rozmieszcza grupy rowno na szerokosci, linia bazowa base; zwraca uzyty rozmiar */
    private fun row(c: Canvas, groups: List<List<Part>>, left: Float, right: Float, base: Float, size0: Float): Float {
        val minGap = size0 * 0.5f
        var size = size0
        while (size > 8f && groups.sumOf { groupW(it, size).toDouble() }.toFloat() + minGap * (groups.size - 1) > right - left) size -= 1f
        val total = groups.sumOf { groupW(it, size).toDouble() }.toFloat()
        val gap = if (groups.size > 1) ((right - left) - total) / (groups.size - 1) else 0f
        var x = left
        for (g in groups) {
            for ((i, p) in g.withIndex()) {
                if (i > 0) x += size * 0.1f
                if (p.icon != 0) icon(c, p, x, base, size) else {
                    tp.typeface = if (p.bold) bold else reg; tp.textSize = p.rel * size; tp.color = p.color; tp.textAlign = Paint.Align.LEFT
                    c.drawText(p.text, x, base, tp)
                }
                x += partW(p, size)
            }
            x += gap
        }
        return size
    }

    private fun icon(c: Canvas, p: Part, x: Float, base: Float, size: Float) {
        val wI = p.rel * size
        val hI = size * 0.62f
        val top = base - hI
        fp.color = p.color
        when (p.icon) {
            IC_SUN -> {
                val cx = x + wI / 2f; val cy = base - hI * 0.15f; val r = wI * 0.32f
                val path = Path(); path.addCircle(cx, cy, r, Path.Direction.CW)
                c.save(); c.clipRect(x, top, x + wI, cy); c.drawPath(path, fp); c.restore()
                c.drawRect(x, cy, x + wI, cy + size * 0.06f, fp)
            }
            IC_TRI -> {
                val g = p.arg
                val hh = hI * (0.35f + (kotlin.math.abs(g).coerceAtMost(15f) / 15f) * 0.65f)
                val path = Path()
                if (g >= 0f) { path.moveTo(x, base); path.lineTo(x + wI, base); path.lineTo(x + wI, base - hh) }
                else { path.moveTo(x, base - hh); path.lineTo(x, base); path.lineTo(x + wI, base) }
                path.close(); c.drawPath(path, fp)
            }
            IC_UP -> {
                val path = Path(); val cx = x + wI / 2f
                path.moveTo(cx, top); path.lineTo(x + wI, top + hI * 0.45f); path.lineTo(cx + wI * 0.16f, top + hI * 0.45f)
                path.lineTo(cx + wI * 0.16f, base); path.lineTo(cx - wI * 0.16f, base); path.lineTo(cx - wI * 0.16f, top + hI * 0.45f)
                path.lineTo(x, top + hI * 0.45f); path.close(); c.drawPath(path, fp)
            }
            IC_TEMP -> {
                val cx = x + wI / 2f
                c.drawRect(cx - wI * 0.14f, top, cx + wI * 0.14f, base - hI * 0.25f, fp)
                c.drawCircle(cx, base - hI * 0.2f, wI * 0.3f, fp)
            }
            IC_DROP -> {
                val path = Path(); val cx = x + wI / 2f
                path.moveTo(cx, top); path.quadTo(x + wI, top + hI * 0.62f, cx, base); path.quadTo(x, top + hI * 0.62f, cx, top)
                path.close(); c.drawPath(path, fp)
            }
            IC_ARROW -> {
                val cx = x + wI / 2f; val cy = base - hI / 2f; val r = min(wI, hI) * 0.55f
                val a = Math.toRadians(p.arg.toDouble())
                fun pt(dx: Float, dy: Float): Pair<Float, Float> {
                    val xx = dx * Math.cos(a) - dy * Math.sin(a); val yy = dx * Math.sin(a) + dy * Math.cos(a)
                    return (cx + xx.toFloat()) to (cy + yy.toFloat())
                }
                val path = Path()
                val (x1, y1) = pt(0f, -r); val (x2, y2) = pt(r * 0.8f, r * 0.7f); val (x3, y3) = pt(0f, r * 0.25f); val (x4, y4) = pt(-r * 0.8f, r * 0.7f)
                path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.lineTo(x4, y4); path.close()
                c.drawPath(path, fp)
            }
        }
    }

    private fun clock(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return fmt("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    private fun gradeColor(g: Float): Int = when {
        g <= -8f -> Color.parseColor("#3B4BA8"); g <= -5f -> Color.parseColor("#3E7CB1"); g <= -2f -> Color.parseColor("#2DD4BF")
        g < 1f -> Color.parseColor("#9AA5B1"); g < 2f -> Color.parseColor("#86EFAC"); g < 5f -> Color.parseColor("#22C55E")
        g < 8f -> Color.parseColor("#EAB308"); g < 11f -> Color.parseColor("#FDBA74"); g < 14f -> Color.parseColor("#F97316")
        g < 20f -> Color.parseColor("#EF4444"); else -> Color.parseColor("#A855F7")
    }

    private fun compass(deg: Int): String {
        val n = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return n[(((deg % 360) + 360 + 22) % 360) / 45]
    }

    @Synchronized
    fun render(width: Int, height: Int, d: KokpitNavData): Bitmap {
        val W = width.coerceAtLeast(160).toFloat(); val H = height.coerceAtLeast(60).toFloat()
        val bmp = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val pad = 8f
        val msgH = (H * 0.20f).coerceIn(26f, 42f)
        val barH = (H * 0.065f).coerceIn(8f, 14f)
        val rest = H - msgH - barH
        val valH = rest * 0.52f
        val infH = rest - valH
        // 1. komunikat (gora)
        drawMsg(c, d, W, msgH)
        // 2. wartosci: baseline przy dole wiersza
        val vSize = valH * 0.80f
        val vBase = msgH + valH * 0.86f
        drawValues(c, d, pad, W - pad, vBase, vSize)
        // 3. pasek trasy
        val bt = msgH + valH
        drawRoute(c, d, pad, W - pad, bt, bt + barH)
        // 4. wiersz z ikonami
        val iSize = infH * 0.66f
        val iBase = bt + barH + infH * 0.80f
        drawInfo(c, d, pad, W - pad, iBase, iSize)
        return bmp
    }

    private fun drawMsg(c: Canvas, d: KokpitNavData, W: Float, h: Float) {
        fp.color = MSGBG; c.drawRect(0f, 0f, W, h, fp)
        val size = h * 0.66f
        val base = h * 0.74f
        val ac = try { Color.parseColor(d.msg.accentColor) } catch (_: Exception) { WHITE }
        fp.color = ac; c.drawRect(8f, h * 0.2f, 12f, h * 0.8f, fp)
        val parts = ArrayList<Part>()
        if (d.msg.lead.isNotEmpty()) parts.add(Part(d.msg.lead, 1f, if (d.msg.kind == MsgKind.NONE) UNIT else WHITE, false))
        if (d.msg.accent.isNotEmpty()) parts.add(Part(d.msg.accent, 1f, ac, true))
        val right = if (d.demo) W - 8f - w("DEMO", size * 0.6f, true) - 8f else W - 8f
        var s = size
        while (s > 10f && groupW(parts, s) + s * 0.25f > right - 20f) s -= 1f
        var x = 20f
        for (p in parts) {
            tp.typeface = if (p.bold) bold else reg; tp.textSize = s; tp.color = p.color; tp.textAlign = Paint.Align.LEFT
            c.drawText(p.text, x, base, tp); x += w(p.text, s, p.bold) + s * 0.3f
        }
        if (d.demo) { tp.typeface = bold; tp.textSize = size * 0.6f; tp.color = ORANGE; tp.textAlign = Paint.Align.RIGHT; c.drawText("DEMO", W - 8f, base, tp) }
    }

    private fun drawValues(c: Canvas, d: KokpitNavData, l: Float, r: Float, base: Float, size: Float) {
        val groups = ArrayList<List<Part>>()
        groups.add(listOfNotNull(Part(fmt("%.0f", d.doneKm), 1f, WHITE, true),
            Part(d.totalKm?.let { "/" + fmt("%.0f", it) } ?: "km", 0.5f, UNIT, false)))
        d.leftKm?.let { groups.add(listOf(Part("↓", 0.6f, LBL, false), Part(fmt("%.0f", it), 1f, WHITE, true), Part("km", 0.42f, UNIT, false))) }
        d.duskMs?.let { groups.add(listOf(Part("", 0.62f, ORANGE, false, IC_SUN), Part(clock(it), 0.86f, ORANGE, true))) }
        val etaCol = if (d.twilightLabel == "zmrok" && d.etaMs != null && d.duskMs != null && d.etaMs > d.duskMs) RED else WHITE
        groups.add(if (d.etaMs != null) listOf(Part("ETA", 0.42f, LBL, false), Part(clock(d.etaMs), 1f, etaCol, true))
                   else listOf(Part("ETA brak", 0.5f, NONE, false)))
        row(c, groups, l, r, base, size)
    }

    private fun drawRoute(c: Canvas, d: KokpitNavData, l: Float, r: Float, tt: Float, b: Float) {
        fp.color = TRACK; c.drawRect(l, tt, r, b, fp)
        val total = d.totalKm ?: return
        if (total <= 0f) return
        val frac = (d.doneKm / total).coerceIn(0f, 1f)
        val fx = l + (r - l) * frac
        fp.color = DONE; c.drawRect(l, tt, fx, b, fp)
        val ahead = d.ahead
        if (ahead != null && ahead.isNotEmpty()) {
            val sum = ahead.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.001f)
            var x = fx
            for ((len, col) in ahead) {
                val ww = (r - fx) * (len / sum)
                fp.color = try { Color.parseColor(col) } catch (_: Exception) { TRACK }
                c.drawRect(x + 0.5f, tt, x + ww - 0.5f, b, fp)
                x += ww
            }
        }
        for (k in d.stopsKm) {
            val sx0 = l + (r - l) * (k / total).coerceIn(0f, 1f)
            fp.color = DARK; c.drawRect(sx0 - 4f, tt, sx0 + 4f, b, fp)
            fp.color = Color.parseColor("#F59E0B"); c.drawRect(sx0 - 2.5f, tt, sx0 + 2.5f, b, fp)
        }
        fp.color = DARK; c.drawRect(fx - 4f, tt - 4f, fx + 4f, b + 4f, fp)
        fp.color = WHITE; c.drawRect(fx - 2f, tt - 4f, fx + 2f, b + 4f, fp)
    }

    private fun drawInfo(c: Canvas, d: KokpitNavData, l: Float, r: Float, base: Float, size: Float) {
        val groups = ArrayList<List<Part>>()
        val g = d.gradePct
        groups.add(if (g == null) listOf(Part("", 0.9f, NONE, false, IC_TRI, 3f), Part("—", 0.8f, NONE, true))
                   else listOf(Part("", 0.9f, gradeColor(g), false, IC_TRI, g), Part(fmt("%.0f", g), 1f, WHITE, true), Part("%", 0.5f, UNIT, false)))
        if (d.ascDone != null && d.ascLeft != null)
            groups.add(listOf(Part("", 0.55f, Color.parseColor("#4ADE80"), false, IC_UP), Part(d.ascDone.toString(), 1f, WHITE, true),
                Part("/", 0.6f, LBL, false), Part(d.ascLeft.toString(), 1f, WHITE, true)))
        val tg = ArrayList<Part>()
        tg.add(Part("", 0.4f, LBL, false, IC_TEMP))
        tg.add(if (d.tempC != null) Part(fmt("%.0f", d.tempC) + "°", 1f, WHITE, true) else Part("—", 0.8f, NONE, true))
        val rn = d.rainNowMmH; val rs = d.rainSoon
        if (rn != null && rn >= 0.1f) { tg.add(Part("", 0.5f, BLUE, false, IC_DROP)); tg.add(Part(fmt("%.1f", rn).replace('.', ','), 0.9f, BLUE, true)); tg.add(Part("mm", 0.45f, BLUE, false)) }
        else if (rs != null && rs.probPct >= 30) { tg.add(Part("", 0.5f, BLUE, false, IC_DROP)); tg.add(Part("${rs.probPct}%", 0.9f, BLUE, true)); tg.add(Part("${rs.minutes}′", 0.45f, BLUE, false)) }
        groups.add(tg)
        val wm = d.windMps
        groups.add(if (wm == null) listOf(Part("wiatr —", 0.5f, NONE, false)) else {
            val rel = d.windRelDeg
            if (rel != null) listOf(Part("", 0.8f, WHITE, false, IC_ARROW, rel.toFloat()), Part(fmt("%.0f", wm), 1f, WHITE, true), Part("m/s", 0.45f, UNIT, false))
            else listOf(Part(fmt("%.0f", wm), 1f, WHITE, true), Part("m/s " + (d.windDirDeg?.takeIf { it >= 0 }?.let { compass(it) } ?: ""), 0.45f, UNIT, false))
        })
        row(c, groups, l, r, base, size)
    }
}
