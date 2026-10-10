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
    /** KOKPIT 2: wiatr czolowy ze znakiem (+ w twarz, - w plecy) i calkowity, m/s - kolor strzalki */
    val windSignedMps: Float? = null,
    val windTotalMps: Float? = null,
    /** etykieta nastepnego zdarzenia: "zmrok" albo "świt" (godzina w duskMs) */
    val twilightLabel: String = "zmrok",
    /** postoje >= 10 min: km na trasie */
    val stopsKm: List<Float> = emptyList(),
    /** niebo na najblizsza godzine bez opadu: CLEAR / PARTLY / OVERCAST / FOG (null = brak danych) */
    val sky: String? = null,
    /** deadline jazdy (jak w ACTIVE) - kolor ETA */
    val deadlineMs: Long? = null,
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
    private val NONE = Color.parseColor("#9AA3AE")
    private val WHITE = Color.WHITE
    private val TRACK = Color.parseColor("#465366")
    private val DONE = Color.parseColor("#5B9BE0")
    private val ORANGE = Color.parseColor("#FB923C")
    private val RED = Color.parseColor("#FF8C8C")
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
    private const val IC_DTD = 7
    private const val IC_SUNY = 8
    private const val IC_PARTLY = 9
    private const val IC_CLOUD = 10
    private const val IC_FOG = 11
    private const val IC_SNOW = 12
    private const val IC_STORM = 13

    private fun w(t: String, size: Float, b: Boolean): Float { tp.typeface = if (b) bold else reg; tp.textSize = size; return tp.measureText(t) }

    /** minimalna czytelna wielkosc drobnego tekstu (jednostki, ETA) na Karoo 3 */
    private const val MIN_TXT = 17f

    private fun ts(p: Part, size: Float): Float = maxOf(p.rel * size, MIN_TXT)

    private fun partW(p: Part, size: Float): Float = if (p.icon != 0) p.rel * size else w(p.text, ts(p, size), p.bold)

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
                    tp.typeface = if (p.bold) bold else reg; tp.textSize = ts(p, size); tp.color = p.color; tp.textAlign = Paint.Align.LEFT
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
                // polowa slonca na linii horyzontu (lewe 60% ikony) + promienie + strzalka obok (w dol = zmrok, w gore = swit)
                val sw = wI * 0.62f
                val cx = x + sw / 2f; val hy = base - hI * 0.12f; val r = sw * 0.36f
                val path = Path(); path.addCircle(cx, hy, r, Path.Direction.CW)
                c.save(); c.clipRect(x, top - hI, x + sw, hy); c.drawPath(path, fp); c.restore()
                c.drawRect(x, hy, x + sw, hy + size * 0.07f, fp)
                for (k in 0..4) {
                    val ang = Math.toRadians(180.0 - k * 45.0)
                    val r1 = r * 1.35f; val r2 = r * 1.8f
                    val px1 = cx + (r1 * Math.cos(ang)).toFloat(); val py1 = hy - (r1 * Math.sin(ang)).toFloat()
                    val px2 = cx + (r2 * Math.cos(ang)).toFloat(); val py2 = hy - (r2 * Math.sin(ang)).toFloat()
                    fp.strokeWidth = size * 0.06f; fp.style = Paint.Style.STROKE
                    c.drawLine(px1, py1, px2, py2, fp)
                    fp.style = Paint.Style.FILL
                }
                val ax = x + sw + (wI - sw) / 2f; val aw = (wI - sw) * 0.42f
                val ap = Path()
                if (p.arg < 0f) { ap.moveTo(ax - aw, hy - hI * 0.5f); ap.lineTo(ax + aw, hy - hI * 0.5f); ap.lineTo(ax, hy) }
                else { ap.moveTo(ax - aw, hy); ap.lineTo(ax + aw, hy); ap.lineTo(ax, hy - hI * 0.5f) }
                ap.close(); c.drawPath(ap, fp)
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
            IC_SUNY -> sunIcon(c, x + wI / 2f, base - hI * 0.5f, hI * 0.30f, p.color)
            IC_PARTLY -> {
                sunIcon(c, x + wI * 0.38f, base - hI * 0.62f, hI * 0.22f, p.color)
                cloudIcon(c, x + wI * 0.08f, base - hI * 0.55f, wI * 0.9f, hI * 0.55f, Color.parseColor("#E5E7EB"))
            }
            IC_CLOUD -> cloudIcon(c, x, base - hI * 0.75f, wI, hI * 0.75f, p.color)
            IC_FOG -> {
                fp.style = Paint.Style.STROKE; fp.strokeWidth = hI * 0.11f
                for (k in 0..2) { val yy = base - hI * (0.2f + 0.28f * k); c.drawLine(x + wI * (0.05f + 0.1f * (k % 2)), yy, x + wI * (0.95f - 0.1f * ((k + 1) % 2)), yy, fp) }
                fp.style = Paint.Style.FILL
            }
            IC_SNOW -> {
                fp.style = Paint.Style.STROKE; fp.strokeWidth = hI * 0.10f
                val sx = x + wI / 2f; val sy = base - hI / 2f; val rr = hI * 0.45f
                for (k in 0..2) { val a = Math.toRadians(90.0 + 60.0 * k); val dx = (rr * Math.cos(a)).toFloat(); val dy = (rr * Math.sin(a)).toFloat(); c.drawLine(sx - dx, sy - dy, sx + dx, sy + dy, fp) }
                fp.style = Paint.Style.FILL
            }
            IC_STORM -> {
                cloudIcon(c, x, base - hI * 0.95f, wI, hI * 0.6f, UNIT)
                val bx = x + wI * 0.45f; val by = base - hI * 0.5f; val bh = hI * 0.55f
                val bp = Path(); bp.moveTo(bx + bh * 0.25f, by); bp.lineTo(bx - bh * 0.15f, by + bh * 0.55f); bp.lineTo(bx + bh * 0.08f, by + bh * 0.55f)
                bp.lineTo(bx - bh * 0.1f, by + bh); bp.lineTo(bx + bh * 0.35f, by + bh * 0.38f); bp.lineTo(bx + bh * 0.12f, by + bh * 0.38f); bp.close()
                fp.color = p.color; c.drawPath(bp, fp)
            }
            IC_DTD -> {
                // litery D-T-D jedna pod druga, na wysokosci cyfr
                val ls = (size * 0.72f) / 3f / 0.72f * 0.95f
                tp.typeface = bold; tp.textSize = ls; tp.color = p.color; tp.textAlign = Paint.Align.CENTER
                val cxL = x + wI / 2f
                val capH = size * 0.72f
                for ((k, ch) in listOf("D", "T", "D").withIndex()) c.drawText(ch, cxL, base - capH + capH * (k + 1) / 3f, tp)
                tp.textAlign = Paint.Align.LEFT
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

    private fun sunIcon(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fp.color = color; c.drawCircle(cx, cy, r, fp)
        fp.style = Paint.Style.STROKE; fp.strokeWidth = r * 0.28f
        for (k in 0 until 8) {
            val a = Math.toRadians(45.0 * k); val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
            c.drawLine(cx + ca * r * 1.35f, cy + sa * r * 1.35f, cx + ca * r * 1.85f, cy + sa * r * 1.85f, fp)
        }
        fp.style = Paint.Style.FILL
    }

    /** chmura w prostokacie (x, y, w, h) */
    private fun cloudIcon(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        fp.color = color
        c.drawCircle(x + w * 0.30f, y + h * 0.62f, h * 0.36f, fp)
        c.drawCircle(x + w * 0.55f, y + h * 0.45f, h * 0.45f, fp)
        c.drawCircle(x + w * 0.78f, y + h * 0.64f, h * 0.32f, fp)
        c.drawRect(x + w * 0.28f, y + h * 0.62f, x + w * 0.80f, y + h * 0.98f, fp)
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
        // komunikat | A: km zrobione/calosc, zostalo, nachylenie | pasek trasy | B: temp+opad, ETA, wiatr | C: przewyzszenie, zmrok/swit
        val g = groups(d)
        // komunikat | A: km zrobione/calosc, zostalo, nachylenie | pasek trasy | B: temp+opad, ETA, wiatr (zmrok tylko w komunikacie)
        // wiekszy komunikat, minimalne odstepy miedzy wierszami (wysokosci liczone od wielkosci cyfr)
        val msgH = (H * 0.30f).coerceIn(30f, 60f)
        val barH = (H * 0.05f).coerceIn(5f, 10f)
        val gTop = 3f; val gBar1 = 5f; val gBar2 = 4f; val gBot = 8f   // dolny wiersz odsuniety od krawedzi
        val avail = H - msgH - gTop - gBar1 - barH - gBar2 - gBot
        val capB = avail / 2.1f
        val capA = capB * 1.1f
        drawMsg(c, d, W, msgH)
        val baseA = msgH + gTop + capA
        row(c, g.first, pad, W - pad, baseA, capA / 0.72f)
        val bt = baseA + gBar1
        drawRoute(c, d, pad, W - pad, bt, bt + barH)
        row(c, g.second, pad, W - pad, bt + barH + gBar2 + capB, capB / 0.72f)
        return bmp
    }

    private fun groups(d: KokpitNavData): Triple<List<List<Part>>, List<List<Part>>, List<List<Part>>> {
        // A: km zrobione / calosc, zostalo, nachylenie
        val a = ArrayList<List<Part>>()
        a.add(listOf(Part(fmt("%.0f", d.doneKm), 1f, WHITE, true), Part(d.totalKm?.let { "/" + fmt("%.0f", it) } ?: "km", 0.55f, UNIT, false)))
        a.add(d.leftKm?.let { listOf(Part("", 0.26f, LBL, false, IC_DTD), Part(fmt("%.0f", it), 1f, WHITE, true), Part("km", 0.42f, UNIT, false)) }
              ?: listOf(Part("", 0.26f, NONE, false, IC_DTD), Part("—", 0.8f, NONE, true)))
        val gr = d.gradePct
        a.add(if (gr == null) listOf(Part("", 0.9f, NONE, false, IC_TRI, 3f), Part("—", 0.8f, NONE, true))
              else listOf(Part("", 0.9f, gradeColor(gr), false, IC_TRI, gr), Part(fmt("%.0f", gr), 1f, WHITE, true), Part("%", 0.45f, UNIT, false)))
        // B: temperatura + opad, ETA, wiatr
        val b = ArrayList<List<Part>>()
        val tg = ArrayList<Part>()
        tg.add(Part("", 0.4f, LBL, false, IC_TEMP))
        tg.add(if (d.tempC != null) Part(fmt("%.0f", d.tempC) + "°", 1f, WHITE, true) else Part("—", 0.8f, NONE, true))
        val rn = d.rainNowMmH; val rs = d.rainSoon
        if (rn != null && rn >= 0.1f) { tg.add(Part("", 0.42f, BLUE, false, IC_DROP)); tg.add(Part(fmt("%.1f", rn).replace('.', ','), 0.68f, BLUE, true)); tg.add(Part("mm", 0.36f, BLUE, false)) }
        else if (rs != null && rs.probPct >= 30 && rs.kind != "FOG") {
            // opad po trasie w ciagu 2 h: burza czerwona, snieg jasnoniebieski, deszcz niebieski
            val (ic, cl) = when (rs.kind) { "STORM" -> IC_STORM to RED; "SNOW" -> IC_SNOW to Color.parseColor("#BFDBFE"); else -> IC_DROP to BLUE }
            tg.add(Part("", if (ic == IC_DROP) 0.42f else 0.75f, cl, false, ic)); tg.add(Part("${rs.probPct}%", 0.68f, cl, true)); tg.add(Part("${rs.minutes}′", 0.36f, cl, false))
        } else d.sky?.let { sk ->
            // bez opadu: niebo (slonce zawsze zolte - swiadomy wyjatek od zasady kolorow)
            when (sk) {
                "CLEAR" -> tg.add(Part("", 0.75f, Color.parseColor("#FACC15"), false, IC_SUNY))
                "PARTLY" -> tg.add(Part("", 0.85f, Color.parseColor("#FACC15"), false, IC_PARTLY))
                "FOG" -> tg.add(Part("", 0.75f, UNIT, false, IC_FOG))
                else -> tg.add(Part("", 0.85f, UNIT, false, IC_CLOUD))
            }
        }
        b.add(tg)
        // kolor ETA jak DTD w ACTIVE: po deadline czerwony, zapas >= 30 min zielony, <= 10 min zolty
        val eta0 = d.etaMs; val dl = d.deadlineMs
        val etaCol = if (eta0 != null && dl != null) when {
            eta0 > dl -> RED
            dl - eta0 >= 30 * 60_000L -> Color.parseColor("#4ADE80")
            dl - eta0 <= 10 * 60_000L -> Color.parseColor("#FACC15")
            else -> WHITE
        } else WHITE
        b.add(if (d.etaMs != null) listOf(Part("ETA", 0.38f, LBL, false), Part(clock(d.etaMs), 1f, etaCol, true))
              else listOf(Part("ETA brak", 0.5f, NONE, false)))
        val wm = d.windMps
        b.add(if (wm == null) listOf(Part("wiatr —", 0.5f, NONE, false)) else {
            val rel = d.windRelDeg
            if (rel != null) listOf(Part("", 0.8f, WHITE, false, IC_ARROW, rel.toFloat()), Part(fmt("%.0f", wm), 1f, WHITE, true), Part("m/s", 0.42f, UNIT, false))
            else listOf(Part(fmt("%.0f", wm), 1f, WHITE, true), Part("m/s " + (d.windDirDeg?.takeIf { it >= 0 }?.let { compass(it) } ?: ""), 0.42f, UNIT, false))
        })
        // C: przewyzszenie, zmrok / swit
        val cc = ArrayList<List<Part>>()
        cc.add(if (d.ascDone != null && d.ascLeft != null)
            listOf(Part("", 0.55f, Color.parseColor("#4ADE80"), false, IC_UP), Part(d.ascDone.toString(), 1f, WHITE, true),
                Part("/", 0.6f, LBL, false), Part(d.ascLeft.toString(), 1f, WHITE, true), Part("m", 0.42f, UNIT, false))
        else listOf(Part("", 0.55f, NONE, false, IC_UP), Part("—", 0.8f, NONE, true)))
        val dawn = d.twilightLabel != "zmrok"
        cc.add(d.duskMs?.let { listOf(Part("", 1.1f, ORANGE, false, IC_SUN, if (dawn) 1f else -1f), Part(d.twilightLabel, 0.42f, ORANGE, false), Part(clock(it), 1f, ORANGE, true)) }
               ?: listOf(Part("", 1.1f, NONE, false, IC_SUN, -1f), Part("—", 0.8f, NONE, true)))
        return Triple(a, b, cc)
    }

    private fun drawMsg(c: Canvas, d: KokpitNavData, W: Float, h: Float) {
        fp.color = MSGBG; c.drawRect(0f, 0f, W, h, fp)
        val size = h * 0.80f
        val base = h * 0.78f
        // kolor tylko dla ostrzezen (W', zjazd, deszcz, jedzenie, zmrok); informacje (nawierzchnia, podjazd, POI) biale
        val warn = d.msg.kind == MsgKind.HUB || d.msg.kind == MsgKind.WPRIME || d.msg.kind == MsgKind.DESCENT || d.msg.kind == MsgKind.RAIN ||
            d.msg.kind == MsgKind.FUEL || d.msg.kind == MsgKind.DUSK
        val ac = if (!warn) WHITE else try { Color.parseColor(d.msg.accentColor) } catch (_: Exception) { WHITE }
        fp.color = if (warn) ac else UNIT; c.drawRect(8f, h * 0.2f, 12f, h * 0.8f, fp)
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

}
