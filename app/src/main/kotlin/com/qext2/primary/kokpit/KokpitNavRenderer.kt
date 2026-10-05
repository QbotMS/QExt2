package com.qext2.primary.kokpit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.min

/** Dane pola KOKPIT nawigacja (gorne pole na ekranie z mapa, 478x143). null = brak danych. */
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
    val demo: Boolean = false,
)

object KokpitNavRenderer {
    private const val BW = 478f
    private const val BH = 143f
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

    private val bold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val reg: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var s = 1f
    private var sx = 1f
    private var sy = 1f
    private fun X(v: Float) = v * sx
    private fun Y(v: Float) = v * sy
    private fun F(v: Float) = v * s
    private fun fmt(p: String, vararg a: Any): String = String.format(java.util.Locale.US, p, *a)

    private fun w(t: String, size: Float, b: Boolean = true): Float { tp.typeface = if (b) bold else reg; tp.textSize = size; return tp.measureText(t) }

    /** tekst z linia bazowa y (px) */
    private fun t(c: Canvas, txt: String, x: Float, base: Float, size: Float, col: Int, b: Boolean = true, align: Paint.Align = Paint.Align.LEFT) {
        tp.typeface = if (b) bold else reg; tp.textSize = size; tp.color = col; tp.textAlign = align
        c.drawText(txt, x, base, tp)
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

    /** grupa: [etykieta] wartosc [jednostka] od x, zwraca szerokosc */
    private fun group(c: Canvas, x: Float, base: Float, label: String, value: String, unit: String, vSize: Float, vCol: Int, draw: Boolean = true): Float {
        var xx = x
        if (label.isNotEmpty()) { if (draw) t(c, label, xx, base, F(13f), LBL, false); xx += w(label, F(13f), false) + F(4f) }
        if (draw) t(c, value, xx, base, vSize, vCol); xx += w(value, vSize)
        if (unit.isNotEmpty()) { xx += F(3f); if (draw) t(c, unit, xx, base, F(13f), UNIT, false); xx += w(unit, F(13f), false) }
        return xx - x
    }

    @Synchronized
    fun render(width: Int, height: Int, d: KokpitNavData): Bitmap {
        val bw = width.coerceAtLeast(160); val bh = height.coerceAtLeast(60)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        sx = bw / BW; sy = bh / BH; s = min(sx, sy)
        c.drawColor(BG)
        drawMsg(c, d)
        drawValues(c, d)
        drawRoute(c, d)
        drawInfo(c, d)
        return bmp
    }

    private fun drawMsg(c: Canvas, d: KokpitNavData) {
        fp.color = MSGBG; c.drawRect(0f, 0f, X(BW), Y(30f), fp)
        val base = Y(30f) / 2f + F(7f)
        var x = X(8f)
        val ac = try { Color.parseColor(d.msg.accentColor) } catch (_: Exception) { WHITE }
        fp.color = ac; c.drawRect(x, Y(7f), x + F(4f), Y(23f), fp)
        x += F(12f)
        if (d.msg.lead.isNotEmpty()) { t(c, d.msg.lead, x, base, F(21f), if (d.msg.kind == MsgKind.NONE) UNIT else WHITE, false); x += w(d.msg.lead, F(21f), false) + F(8f) }
        if (d.msg.accent.isNotEmpty()) t(c, d.msg.accent, x, base, F(21f), ac)
        if (d.demo) t(c, "DEMO", X(BW) - F(8f), base, F(15f), ORANGE, true, Paint.Align.RIGHT)
    }

    private fun drawValues(c: Canvas, d: KokpitNavData) {
        val base = Y(62f)
        val l = X(8f); val r = X(BW - 8f)
        val done = fmt("%.0f", d.doneKm)
        val g1u = d.totalKm?.let { "/ " + fmt("%.0f", it) + " km" } ?: "km"
        val left = d.leftKm?.let { fmt("%.0f", it) }
        val eta = d.etaMs?.let { clock(it) }
        val etaCol = if (d.etaMs != null && d.duskMs != null && d.etaMs > d.duskMs) RED else WHITE
        val dusk = d.duskMs?.let { clock(it) }
        val v = F(28f)
        // szerokosci grup
        val w1 = group(c, 0f, 0f, "", done, g1u, v, WHITE, false)
        val w2 = if (left != null) group(c, 0f, 0f, "zostało", left, "km", v, WHITE, false) else 0f
        val w3 = if (dusk != null) F(22f) + group(c, 0f, 0f, "zmrok", dusk, "", F(24f), ORANGE, false) else 0f
        val w4 = group(c, 0f, 0f, "ETA", eta ?: "brak", "", if (eta != null) v else F(18f), if (eta != null) etaCol else NONE, false)
        val ws = listOf(w1, w2, w3, w4).filter { it > 0f }
        val gap = ((r - l) - ws.sum()) / (ws.size - 1).coerceAtLeast(1)
        var x = l
        group(c, x, base, "", done, g1u, v, WHITE); x += w1 + gap
        if (left != null) { group(c, x, base, "zostało", left, "km", v, WHITE); x += w2 + gap }
        if (dusk != null) {
            sun(c, x, base - F(14f), F(18f))
            group(c, x + F(22f), base, "zmrok", dusk, "", F(24f), ORANGE); x += w3 + gap
        }
        group(c, r - w4, base, "ETA", eta ?: "brak", "", if (eta != null) v else F(18f), if (eta != null) etaCol else NONE)
    }

    private fun sun(c: Canvas, x: Float, y: Float, size: Float) {
        fp.color = ORANGE
        val p = Path()
        val cx = x + size / 2f; val cy = y + size * 0.72f; val r = size * 0.28f
        p.addCircle(cx, cy, r, Path.Direction.CW)
        c.save(); c.clipRect(x, y, x + size, cy); c.drawPath(p, fp); c.restore()
        c.drawRect(x, cy, x + size, cy + F(2f), fp)
    }

    private fun drawRoute(c: Canvas, d: KokpitNavData) {
        val l = X(8f); val r = X(BW - 8f); val tt = Y(70f); val b = Y(92f)
        fp.color = TRACK; c.drawRect(l, tt, r, b, fp)
        val total = d.totalKm
        if (total == null || total <= 0f) {
            t(c, "brak trasy", (l + r) / 2f, (tt + b) / 2f + F(6f), F(16f), NONE, false, Paint.Align.CENTER); return
        }
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
                c.drawRect(x + F(0.5f), tt, x + ww - F(0.5f), b, fp)
                x += ww
            }
        }
        fp.color = Color.parseColor("#111315"); c.drawRect(fx - F(4f), tt - F(5f), fx + F(4f), b + F(5f), fp)
        fp.color = WHITE; c.drawRect(fx - F(2f), tt - F(5f), fx + F(2f), b + F(5f), fp)
    }

    private fun drawInfo(c: Canvas, d: KokpitNavData) {
        val lb = Y(110f); val vb = Y(136f)
        val cols = floatArrayOf(8f, 120f, 268f, 400f).map { X(it) }
        // nachylenie
        t(c, "NACH. %", cols[0], lb, F(13f), LBL, false)
        val g = d.gradePct
        if (g == null) t(c, "brak", cols[0], vb, F(18f), NONE, false) else {
            val gc = gradeColor(g)
            fp.color = gc
            val p = Path(); val x0 = cols[0]; val y0 = vb
            p.moveTo(x0, y0); p.lineTo(x0 + F(26f), y0); p.lineTo(x0 + F(26f), y0 - F(10f + kotlin.math.abs(g).coerceAtMost(15f) * 0.6f)); p.close()
            c.drawPath(p, fp)
            t(c, fmt("%.0f", g), x0 + F(32f), vb, F(24f), WHITE)
        }
        // przewyzszenie
        t(c, "↑ / ZOSTAŁO m", cols[1], lb, F(13f), LBL, false)
        if (d.ascDone == null || d.ascLeft == null) t(c, "brak", cols[1], vb, F(18f), NONE, false) else {
            var x = cols[1]
            val a = d.ascDone.toString(); t(c, a, x, vb, F(24f), WHITE); x += w(a, F(24f)) + F(4f)
            t(c, "/", x, vb, F(16f), LBL, false); x += w("/", F(16f), false) + F(4f)
            t(c, d.ascLeft.toString(), x, vb, F(24f), WHITE)
        }
        // temperatura + opad
        t(c, "TEMP · OPAD", cols[2], lb, F(13f), LBL, false)
        var x = cols[2]
        val tc = d.tempC
        if (tc == null) { t(c, "brak", x, vb, F(18f), NONE, false); x += w("brak", F(18f), false) } else {
            val tv = fmt("%.0f", tc); t(c, tv, x, vb, F(24f), WHITE); x += w(tv, F(24f)) + F(3f)
            t(c, "°C", x, vb, F(12f), UNIT, false); x += w("°C", F(12f), false)
        }
        x += F(8f)
        val rn = d.rainNowMmH; val rs = d.rainSoon
        if (rn != null && rn >= 0.1f) {
            drop(c, x, vb - F(16f), F(15f), BLUE); x += F(18f)
            val v = fmt("%.1f", rn).replace('.', ','); t(c, v, x, vb, F(22f), BLUE); x += w(v, F(22f)) + F(3f)
            t(c, "mm/h", x, vb, F(12f), BLUE, false)
        } else if (rs != null && rs.probPct >= 30) {
            drop(c, x, vb - F(16f), F(15f), BLUE); x += F(18f)
            val v = rs.probPct.toString(); t(c, v, x, vb, F(22f), BLUE); x += w(v, F(22f)) + F(3f)
            t(c, "% ${rs.minutes}′", x, vb, F(12f), BLUE, false)
        }
        // wiatr
        t(c, "WIATR", X(BW - 8f), lb, F(13f), LBL, false, Paint.Align.RIGHT)
        val wm = d.windMps
        if (wm == null) t(c, "brak", X(BW - 8f), vb, F(18f), NONE, false, Paint.Align.RIGHT) else {
            val unit = "m/s" + (d.windDirDeg?.takeIf { it >= 0 }?.let { " " + compass(it) } ?: "")
            val uw = w(unit, F(12f), false)
            t(c, unit, X(BW - 8f), vb, F(12f), UNIT, false, Paint.Align.RIGHT)
            t(c, fmt("%.0f", wm), X(BW - 8f) - uw - F(4f), vb, F(24f), WHITE, true, Paint.Align.RIGHT)
        }
    }

    private fun drop(c: Canvas, x: Float, y: Float, size: Float, col: Int) {
        fp.color = col
        val p = Path()
        p.moveTo(x + size / 2f, y)
        p.quadTo(x + size, y + size * 0.6f, x + size / 2f, y + size)
        p.quadTo(x, y + size * 0.6f, x + size / 2f, y)
        p.close()
        c.drawPath(p, fp)
    }
}
