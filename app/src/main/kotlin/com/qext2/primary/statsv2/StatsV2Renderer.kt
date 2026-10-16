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
import com.qext2.primary.gate.GateController.State as GS

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
    /** nastepne zdarzenie: zmierzch albo swit (null = brak danych) */
    val twilightMs: Long? = null,
    val twilightDawn: Boolean = false,
    /** PRZEBIEG: NP i EF okien 5 min (ostatnie moze byc niepelne), typowe EF z QBota (null = brak) */
    val winNp: List<Int> = emptyList(),
    val winEf: List<Float?> = emptyList(),
    val winPartial: Boolean = false,
    val typEf: Float? = null,
    val cpW: Float? = null,
    /** pozostala czesc trasy wg nawierzchni: (km, kolor ARGB) po kolei; null = brak profilu */
    val ahead: List<Pair<Float, Int>>? = null,
    val stopsKm: List<Float> = emptyList(),
    val demo: Boolean = false,
    /** STATS v2 nowy uklad (16.10.2026) */
    val xssPlan: Float? = null,
    val planDeltaMin: Int? = null,
    val planRefEtaMs: Long? = null,
    val tempo: List<Pair<Int, Float>> = emptyList(),
    val tempoPartial: Boolean = false,
    val gate: com.qext2.primary.gate.GateController.Ui? = null,
    val lightState: String? = null,
    val planDriveDelta: Int? = null,
    val planStopDelta: Int? = null,
    val planFromQbot: Boolean = false,
    val light: com.qext2.primary.light.LightController.Ui? = null,
)

object StatsV2Renderer {
    private const val BW = 480f
    private const val BH = 760f

    private val BG = Color.parseColor("#2A3038")
    private val CELL = Color.parseColor("#14181D")
    private val LABEL = Color.parseColor("#D5DCE3")
    private val UNIT = Color.parseColor("#9AA5B1")
    private val SUB = Color.parseColor("#C9D2DC")
    private val NONE = Color.parseColor("#9AA3AE")
    private val WHITE = Color.WHITE
    private val PILL = Color.parseColor("#111315")
    private val TRACK = Color.parseColor("#465366")
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
        tp.typeface = com.qext2.primary.kokpit.Kokpit2Fonts.semi   // ta sama czcionka co KOKPIT 2 (Saira Semi Condensed)
        val bw = w.coerceAtLeast(120)
        val bh = h.coerceAtLeast(160)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        sx = bw / BW; sy = bh / BH; s = min(sx, sy)
        c.drawColor(BG)

        val g = 2f
        // uklad (mockup "STATS - PRZEBIEG"): NP/IF/VI 116, PRZEBIEG 136, RSRV/XSS/KCAL 100, TRASA z nawierzchnia 198,
        // PRZEWYZSZENIE 92, dolny rzad reszta (~108)
        // 2026-10-16: TRASA i PRZEWYZSZENIE przeniesione do INFO; nowe: PLAN CZASU 86, TEMPO 120, BRAMA|SWIATLO 85
        val rows = floatArrayOf(113f, 136f, 100f, 86f, 120f, 85f)
        var y = 0f
        val col3 = (BW - 2 * g) / 3f
        // 1: NP / IF / VI
        drawNp(c, RectF(0f, y, col3, y + rows[0]), d)
        drawIf(c, RectF(col3 + g, y, 2 * col3 + g, y + rows[0]), d.ifv)
        drawValueCell(c, RectF(2 * col3 + 2 * g, y, BW, y + rows[0]), listOf(P_BOLT), null, "VI", "",
            d.vi?.let { fmt("%.2f", it) }, 70f, viColor(d.vi))
        y += rows[0] + g
        // 2: PRZEBIEG (NP w oknach 5 min + EF)
        drawWindows(c, RectF(0f, y, BW, y + rows[1]), d)
        y += rows[1] + g
        // 3: RSRV / XSS / KCAL
        drawRsrv(c, RectF(0f, y, col3, y + rows[2]), d.rsrv)
        drawXss(c, RectF(col3 + g, y, 2 * col3 + g, y + rows[2]), d)
        drawValueCell(c, RectF(2 * col3 + 2 * g, y, BW, y + rows[2]), listOf(P_FLAME), null, "KCAL", "",
            d.kcal?.toString(), 62f, WHITE)
        y += rows[2] + g
        // 4: PLAN CZASU
        drawPlan(c, RectF(0f, y, BW, y + rows[3]), d)
        y += rows[3] + g
        // 5: TEMPO co godzine
        drawTempo(c, RectF(0f, y, BW, y + rows[4]), d)
        y += rows[4] + g
        // 6: BRAMA (przycisk, nakladka w field_stats_v2.xml) | SWIATLO (stan lampy z radarem)
        val half = (BW - g) / 2f
        drawBrama(c, RectF(0f, y, half - 1f, y + rows[5]), d.gate)
        drawLight(c, RectF(half + 1f, y, BW, y + rows[5]), d.lightState, d.light)
        y += rows[5] + g
        // 6: CARB+woda (45%) / KAD (20%) / BAT (35%)
        val inner = BW - 2 * g
        val wFood = inner * 0.45f
        val wKad = inner * 0.20f
        val bottom = BH
        drawFood(c, RectF(0f, y, wFood, bottom), d)
        drawValueCell(c, RectF(wFood + g, y, wFood + g + wKad, bottom), listOf(P_BAR, P_CAD), null, "KAD", "",
            d.cadAvg?.toString(), 54f, WHITE)
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
        if (align == Paint.Align.CENTER && segW < tw + F(2f)) return
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
        4 -> Color.parseColor("#FACC15"); 5 -> Color.parseColor("#FB923C"); 6 -> Color.parseColor("#FF8C8C")
        else -> WHITE
    }

    // jak w starym STATS: < 1.05 bialy, 1.05-1.10 zolty, >= 1.10 czerwony
    private fun viColor(vi: Float?): Int = when {
        vi == null || vi < 1.05f -> WHITE
        vi < 1.10f -> Color.parseColor("#FACC15")
        else -> Color.parseColor("#FF8C8C")
    }

    private fun drawNp(c: Canvas, r: RectF, d: StatsV2Data) {
        // liczba NP biala jak w starym STATS; strefa tylko w etykiecie
        drawValueCell(c, r, listOf(P_BAR, P_BOLT), null, "NP", "W", d.np?.toString(), 78f, WHITE)
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
        // jak w starym STATS: >= 40 zielony, 20-40 zolty, < 20 czerwony
        val col = when { pct >= 40 -> Color.parseColor("#2F7D4A"); pct >= 20 -> Color.parseColor("#C9A227"); else -> Color.parseColor("#C2412D") }
        fp.color = TRACK; c.drawRect(l, t, rr, b, fp)
        val fx = l + (rr - l) * pct / 100f
        fp.color = col; c.drawRect(l, t, fx, b, fp)
        val cy = (t + b) / 2f
        if (pct >= 40) barText(c, pct.toString(), l + F(4f), cy, F(44f), fx - l, col, Paint.Align.LEFT)
        else barText(c, pct.toString(), rr - F(4f), cy, F(44f), rr - fx, TRACK, Paint.Align.RIGHT)
    }

    private fun hm(sec: Long): String = fmt("%d:%02d", sec / 3600, (sec % 3600) / 60)

    private fun clock(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return fmt("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    // nawierzchnia w TRASA (kolory jak w StatsV2DataType / KOKPIT 2)
    private val C_ASF = Color.parseColor("#F2F4F7")
    private val C_GRV = Color.parseColor("#FFB300")
    private val C_BAD = Color.parseColor("#FF2A2A")
    private val ROAD = "ROAD"
    private val ip = Paint(Paint.ANTI_ALIAS_FLAG)

    /** ikona drogi (rysowana): asfalt w perspektywie, biale krawedzie, zolta przerywana linia; kwadrat s x s */
    private fun roadIcon(c: Canvas, x: Float, y: Float, s: Float) {
        val p = android.graphics.Path()
        p.moveTo(x + s * 0.31f, y + s * 0.05f); p.lineTo(x + s * 0.69f, y + s * 0.05f); p.lineTo(x + s, y + s * 0.95f); p.lineTo(x, y + s * 0.95f); p.close()
        ip.style = Paint.Style.FILL
        ip.shader = android.graphics.LinearGradient(0f, y, 0f, y + s, Color.parseColor("#7A818C"), Color.parseColor("#383D45"), android.graphics.Shader.TileMode.CLAMP)
        c.drawPath(p, ip); ip.shader = null
        ip.style = Paint.Style.STROKE; ip.strokeCap = Paint.Cap.ROUND
        ip.color = Color.WHITE; ip.strokeWidth = s * 0.045f
        c.drawLine(x + s * 0.33f, y + s * 0.06f, x + s * 0.05f, y + s * 0.94f, ip)
        c.drawLine(x + s * 0.67f, y + s * 0.06f, x + s * 0.95f, y + s * 0.94f, ip)
        ip.color = Color.parseColor("#FFD43B")
        ip.strokeWidth = s * 0.035f; c.drawLine(x + s * 0.5f, y + s * 0.10f, x + s * 0.5f, y + s * 0.20f, ip)
        ip.strokeWidth = s * 0.048f; c.drawLine(x + s * 0.5f, y + s * 0.31f, x + s * 0.5f, y + s * 0.48f, ip)
        ip.strokeWidth = s * 0.06f; c.drawLine(x + s * 0.5f, y + s * 0.62f, x + s * 0.5f, y + s * 0.88f, ip)
        ip.style = Paint.Style.FILL
    }

    /** emoji systemowe (🪨 / 💩); gdy Karoo nie ma glifu - kolko w kolorze nawierzchni */
    private fun emoji(c: Canvas, e: String, x: Float, cy: Float, size: Float, fallback: Int) {
        val old = tp.typeface
        tp.typeface = Typeface.DEFAULT; tp.textSize = size * 0.92f; tp.textAlign = Paint.Align.LEFT
        if (tp.hasGlyph(e)) { val fm = tp.fontMetrics; tp.color = Color.WHITE; c.drawText(e, x, cy - (fm.ascent + fm.descent) / 2f, tp) }
        else { fp.color = fallback; c.drawCircle(x + size / 2f, cy, size * 0.36f, fp) }
        tp.typeface = old
    }

    private fun drawRoute(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        label(c, r, listOf(P_ROUTE), "TRASA", "", material = true)
        val l = X(r.left) + F(10f); val rr = X(r.right) - F(10f)
        val T = Y(r.top)
        // naglowek: zmrok/swit miedzy winieta a ETA; ETA duze po prawej
        val etaTxt = d.etaMs?.let { clock(it) }
        val etaS = F(46f); val etaCy = T + F(30f)
        if (etaTxt != null) {
            text(c, etaTxt, rr, etaCy, etaS, WHITE, Paint.Align.RIGHT)
            text(c, "ETA", rr - textW(etaTxt, etaS) - F(6f), etaCy + F(6f), F(20f), SUB, Paint.Align.RIGHT)
        } else text(c, "ETA —", rr, etaCy, F(24f), NONE, Paint.Align.RIGHT)
        var tx = l + F(140f)
        if (d.demo) { text(c, "DEMO", tx, T + F(21f), F(18f), AMBER); tx += textW("DEMO", F(18f)) + F(8f) }
        d.twilightMs?.let {
            val lbl = if (d.twilightDawn) "świt" else "zmrok"
            text(c, lbl, tx, T + F(22f), F(19f), SUB); tx += textW(lbl, F(19f)) + F(6f)
            text(c, clock(it), tx, T + F(21f), F(26f), WHITE)
        }
        // wiersz: przejechane / calosc (lewo) + ZOSTALO wg nawierzchni (prawo), w polowie miedzy ETA a belka
        val vcy = T + F(80f)
        var x = l
        val done = fmt("%.0f", d.doneKm)
        text(c, done, x, vcy, F(58f), WHITE); x += textW(done, F(58f)) + F(8f)
        val total = d.totalKm
        if (total != null) {
            text(c, "/", x, vcy + F(6f), F(30f), SUB); x += textW("/", F(30f)) + F(8f)
            val tt = fmt("%.0f", total)
            text(c, tt, x, vcy + F(6f), F(40f), WHITE); x += textW(tt, F(40f)) + F(6f)
        }
        text(c, "km", x, vcy + F(10f), F(22f), UNIT); x += textW("km", F(22f))
        val leftEnd = x
        if (d.hasRoute) d.ahead?.takeIf { it.isNotEmpty() }?.let { segs ->
            fun sum(col: Int) = segs.filter { it.second == col }.sumOf { it.first.toDouble() }.toFloat()
            val parts = listOf(Triple(ROAD, sum(C_ASF), C_ASF), Triple("\uD83E\uDEA8", sum(C_GRV), C_GRV), Triple("\uD83D\uDCA9", sum(C_BAD), C_BAD))
            // wielkosc bloku ikon: najwieksza mieszczaca sie obok "przejechane / calosc"
            var k = 1f
            fun blockW(k: Float) = parts.sumOf { (textW(fmt("%.0f", it.second), F(36f) * k) + F(5f) + F(32f) * k + F(16f)).toDouble() }.toFloat() - F(16f)
            while (k > 0.7f && rr - blockW(k) < leftEnd + F(12f)) k -= 0.05f
            var px = rr; val ns = F(36f) * k; val isz = F(32f) * k
            for ((ic, km, col) in parts.reversed()) {
                val v = fmt("%.0f", km); text(c, v, px, vcy, ns, WHITE, Paint.Align.RIGHT); px -= textW(v, ns) + F(5f)
                if (ic == ROAD) roadIcon(c, px - isz, vcy - isz / 2f, isz) else emoji(c, ic, px - isz, vcy, isz, col)
                px -= isz + F(16f)
            }
        }
        // belka jak na polu mapy: odcinki nawierzchni w kolorach, bez liczb, grubsza
        val bt = T + Y(113f); val bb = bt + Y(34f)
        fp.color = TRACK; c.drawRect(l, bt, rr, bb, fp)
        if (d.hasRoute && total != null && total > 0f) {
            val frac = (d.doneKm / total).coerceIn(0f, 1f)
            val fx = l + (rr - l) * frac
            fp.color = Color.parseColor("#2E7BFF"); c.drawRect(l, bt, fx, bb, fp)
            d.ahead?.takeIf { it.isNotEmpty() }?.let { segs ->
                val sum = segs.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.001f)
                var sx = fx
                for ((len, col) in segs) {
                    val ww = (rr - fx) * (len / sum)
                    fp.color = col; c.drawRect(sx + F(1f), bt, sx + ww - F(1f), bb, fp)
                    sx += ww
                }
            }
            for (km in d.stopsKm) {
                val sx0 = l + (rr - l) * (km / total).coerceIn(0f, 1f)
                fp.color = Color.parseColor("#FF3DF5"); c.drawRect(sx0 - F(3f), bt, sx0 + F(3f), bb, fp)
            }
            val mr = (bb - bt) * 0.6f; val mx = fx.coerceIn(l + mr, rr - mr)
            fp.color = PILL; c.drawCircle(mx, (bt + bb) / 2f, mr, fp)
            fp.color = Color.parseColor("#39FF14"); c.drawCircle(mx, (bt + bb) / 2f, mr - F(3f), fp)
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

    private val stp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    private fun zoneColor5(np: Int, cp: Float?): Int {
        if (cp == null || cp <= 0f) return Color.parseColor("#9AA3AE")
        val q = np / cp
        return Color.parseColor(when { q < 0.55f -> "#9AA3AE"; q < 0.75f -> "#6FA8FF"; q < 0.90f -> "#22C55E"; q < 1.05f -> "#EAB308"; q < 1.20f -> "#F97316"; else -> "#EF4444" })
    }

    /** PRZEBIEG: slupki NP okien 5 min (kolor = strefa wg CP), linia EF (biala z czarna obwodka), wiersz liczb. */
    private fun drawWindows(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        label(c, r, listOf(P_BAR, P_BOLT), "PRZEBIEG", "NP / 5 min")
        if (d.winEf.any { it != null }) rightLabel(c, r, listOf(Triple("EF", 18f, WHITE)))
        val l = X(r.left) + F(10f); val rr = X(r.right) - F(10f)
        val sumSize = F(34f)
        val sumBase = Y(r.bottom) - F(8f)
        val ct = Y(r.top) + F(6f) + F(28f) + F(4f)
        val cb = sumBase - sumSize * 0.72f - F(8f)
        val all = d.winNp
        if (all.isEmpty()) { none(c, (l + rr) / 2f, (ct + sumBase) / 2f, "pierwsze okno po 5 min jazdy"); return }
        val slots = 23
        val n = all.size.coerceAtMost(slots)
        val np = all.takeLast(n); val ef = d.winEf.takeLast(n)
        val gap = F(3f); val bw = (rr - l - gap * (slots - 1)) / slots
        val mx = (np.maxOrNull() ?: 1).coerceAtLeast(50) * 1.08f
        val lastDone = if (d.winPartial) n - 2 else n - 1
        val best = (0..lastDone.coerceAtLeast(0)).maxByOrNull { np[it] }
        fun xAt(k: Int) = rr - (n - k) * (bw + gap) + gap
        for (k in 0 until n) {
            val x = xAt(k)
            val hh = (cb - ct) * (np[k] / mx)
            fp.color = zoneColor5(np[k], d.cpW)
            fp.alpha = if (d.winPartial && k == n - 1) 140 else 220
            c.drawRect(x, cb - hh, x + bw, cb, fp)
            fp.alpha = 255
            if (k == best && n > 1) { stp.color = WHITE; stp.strokeWidth = F(2f); c.drawRect(x, cb - hh, x + bw, cb, stp) }
        }
        // EF: wlasna skala dopasowana do zakresu wartosci
        val efv = ef.mapIndexedNotNull { k, e -> e?.let { k to it } }
        if (efv.size >= 2) {
            val vals = efv.map { it.second } + listOfNotNull(d.typEf)
            val lo = (vals.minOrNull() ?: 1f) - 0.02f; val hi = (vals.maxOrNull() ?: 2f) + 0.02f
            fun yOf(e: Float) = ct + F(3f) + (cb - ct - F(6f)) * (1f - (e - lo) / (hi - lo))
            d.typEf?.let { t ->
                val yy = yOf(t); var x = l
                stp.color = SUB; stp.strokeWidth = F(3f)
                while (x < rr) { c.drawLine(x, yy, minOf(x + F(5f), rr), yy, stp); x += F(9f) }
            }
            val path = Path()
            efv.forEachIndexed { i, (k, e) -> val px = xAt(k) + bw / 2f; val py = yOf(e); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
            stp.color = Color.BLACK; stp.strokeWidth = F(8f); c.drawPath(path, stp)
            stp.color = WHITE; stp.strokeWidth = F(3.5f); c.drawPath(path, stp)
            for ((k, e) in efv) {
                val px = xAt(k) + bw / 2f; val py = yOf(e)
                fp.color = Color.BLACK; c.drawCircle(px, py, F(4.4f), fp)
                fp.color = WHITE; c.drawCircle(px, py, F(3.2f), fp)
            }
        }
        // wiersz liczb: ostatnie pelne okno, EF (typowe), najlepsze okno
        val li = lastDone.coerceAtLeast(0)
        val sb = sumBase
        fun grp(x: Float, label: String, value: String, unit: String, align: Paint.Align) {
            val lw = textW(label, F(20f)) + F(5f); val vw = textW(value, sumSize); val uw = if (unit.isEmpty()) 0f else F(5f) + textW(unit, F(18f))
            val total = lw + vw + uw
            val x0 = when (align) { Paint.Align.LEFT -> x; Paint.Align.RIGHT -> x - total; else -> x - total / 2f }
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = F(20f); tp.color = SUB; c.drawText(label, x0, sb, tp)
            tp.textSize = sumSize; tp.color = WHITE; c.drawText(value, x0 + lw, sb, tp)
            if (unit.isNotEmpty()) { tp.textSize = F(18f); tp.color = UNIT; c.drawText(unit, x0 + lw + vw + F(5f), sb, tp) }
        }
        grp(l, "5′", np[li].toString(), "W", Paint.Align.LEFT)
        ef.getOrNull(li)?.let { e -> grp((l + rr) / 2f, "EF", fmt("%.2f", e), d.typEf?.let { "(" + fmt("%.2f", it) + ")" } ?: "", Paint.Align.CENTER) }
        best?.let { grp(rr, "max", np[it].toString(), "W", Paint.Align.RIGHT) }
    }

    private fun drawAscent(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        label(c, r, listOf(P_GRADE), "PRZEWYŻSZENIE", "m")
        val l = X(r.left) + F(10f); val rr = X(r.right) - F(10f)
        val b = Y(r.bottom) - F(8f); val t = b - F(44f)
        val dn = d.ascDone; val lf = d.ascLeft
        if (dn == null || lf == null || dn + lf <= 0) { none(c, (l + rr) / 2f, (t + b) / 2f); return }
        rightLabel(c, r, listOf(Triple("razem", 22f, SUB), Triple((dn + lf).toString(), 26f, WHITE)))
        val gap = F(2f)
        val tot = (dn + lf).toFloat()
        val gw = (rr - l - gap) * (dn / tot)
        val green = Color.parseColor("#2F7D4A")
        fp.color = green; c.drawRect(l, t, l + gw, b, fp)
        fp.color = TRACK; c.drawRect(l + gw + gap, t, rr, b, fp)
        val cy = (t + b) / 2f
        val sz = F(34f)
        val dTxt = "↑ $dn"
        val lTxt = lf.toString()
        if (gw >= textW(dTxt, sz) + F(16f)) {
            barText(c, dTxt, l + gw / 2f, cy, sz, gw, green, Paint.Align.CENTER)
            barText(c, lTxt, l + gw + gap + (rr - l - gw - gap) / 2f, cy, sz, rr - l - gw - gap, TRACK, Paint.Align.CENTER)
        } else {
            // zielony odcinek za waski na liczbe: wykonane zaraz za nim, pozostale przy prawej krawedzi
            text(c, dTxt, l + gw + gap + F(6f), cy, sz, WHITE)
            text(c, lTxt, rr - F(6f), cy, sz, WHITE, Paint.Align.RIGHT)
        }
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

    private fun drawXss(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        label(c, r, listOf(P_BARS), "XSS", "")
        val l = X(r.left) + F(10f); val rr = X(r.right) - F(10f); val T = Y(r.top)
        val v = d.xss
        if (v == null) { none(c, (l + rr) / 2f, T + F(60f)); return }
        val vs = fmt("%.0f", v)
        val plan = d.xssPlan
        if (plan != null && plan > 0f) {
            val vsz = F(52f)
            val ps = "/" + fmt("%.0f", plan)
            val w = textW(vs, vsz) + F(4f) + textW(ps, F(22f))
            val x0 = (l + rr) / 2f - w / 2f
            text(c, vs, x0, T + F(55f), vsz, WHITE)
            text(c, ps, x0 + textW(vs, vsz) + F(4f), T + F(63f), F(22f), UNIT)
            val bt = T + F(79f); val bb = T + F(93f)
            fp.color = TRACK; c.drawRect(l, bt, rr, bb, fp)
            fp.color = Color.parseColor(if (v > plan) "#22C55E" else "#2E7BFF")
            c.drawRect(l, bt, l + (rr - l) * (v / plan).coerceIn(0f, 1f), bb, fp)
        } else {
            text(c, vs, (l + rr) / 2f, T + F(62f), fit(vs, F(66f), rr - l), WHITE, Paint.Align.CENTER)
        }
    }

    private fun drawPlan(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        label(c, r, null, "PLAN CZASU", "")
        if (d.planFromQbot) rightLabel(c, r, listOf(Triple("model czasu QBota", 20f, SUB)))
        else d.planRefEtaMs?.let { rightLabel(c, r, listOf(Triple("ETA ze startu", 20f, SUB), Triple(clock(it), 24f, WHITE))) }
        val l = X(r.left) + F(12f); val T = Y(r.top); val cy = T + F(58f)
        val dm = d.planDeltaMin
        if (dm == null) { none(c, (X(r.left) + X(r.right)) / 2f, cy, "po 1. km z ETA"); return }
        val s = (if (dm > 0) "+" else if (dm < 0) "−" else "") + kotlin.math.abs(dm)
        val col = when { dm >= 5 -> AMBER; dm <= -5 -> Color.parseColor("#4ADE80"); else -> WHITE }
        text(c, s, l, cy, F(52f), col)
        val dd = d.planDriveDelta; val ds = d.planStopDelta
        val word = if (dd != null && ds != null) "min" else when { dm > 0 -> "min za planem"; dm < 0 -> "min przed planem"; else -> "min · zgodnie z planem" }
        text(c, word, l + textW(s, F(52f)) + F(8f), cy + F(6f), F(22f), UNIT)
        if (dd != null && ds != null) {
            fun sg(v: Int) = (if (v > 0) "+" else if (v < 0) "−" else "") + kotlin.math.abs(v)
            val rr = X(r.right) - F(10f)
            val sv = sg(ds); text(c, sv, rr, cy, F(34f), if (ds >= 5) AMBER else WHITE, Paint.Align.RIGHT)
            var x = rr - textW(sv, F(34f)) - F(6f)
            text(c, "postoje", x, cy + F(2f), F(22f), SUB, Paint.Align.RIGHT); x -= textW("postoje", F(22f)) + F(14f)
            val jv = sg(dd); text(c, jv, x, cy, F(34f), if (dd >= 5) AMBER else WHITE, Paint.Align.RIGHT); x -= textW(jv, F(34f)) + F(6f)
            text(c, "jazda", x, cy + F(2f), F(22f), SUB, Paint.Align.RIGHT)
        }
    }

    private fun drawTempo(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        label(c, r, null, "TEMPO", "co godzinę")
        val T = Y(r.top); val l = X(r.left) + F(12f); val rr = X(r.right) - F(10f)
        val all = d.tempo
        if (all.isEmpty()) { none(c, (l + rr) / 2f, T + F(72f), "po 5 min jazdy"); return }
        if (all.size >= 2 && all.first().first > 0) {
            val pct = Math.round((all.last().first - all.first().first) * 100f / all.first().first)
            val ps = (if (pct > 0) "+" else if (pct < 0) "−" else "") + kotlin.math.abs(pct) + "%"
            rightLabel(c, r, listOf(Triple("moc od 1. h", 20f, SUB), Triple(ps, 24f, if (pct <= -10) AMBER else WHITE)))
        }
        val start = (all.size - 4).coerceAtLeast(0)
        val shown = all.subList(start, all.size)
        val colW = (rr - l) / 4f
        shown.forEachIndexed { i, hw ->
            val idx = start + i
            val x = l + colW * i
            val isNow = d.tempoPartial && idx == all.size - 1
            text(c, if (isNow) "teraz" else "${idx + 1}. h", x, T + F(50f), F(20f), UNIT)
            val ws = hw.first.toString()
            text(c, ws, x, T + F(80f), F(34f), WHITE)
            text(c, "W", x + textW(ws, F(34f)) + F(3f), T + F(84f), F(20f), UNIT)
            val ks = fmt("%.1f", hw.second)
            text(c, ks, x, T + F(106f), F(20f), SUB)
            text(c, "km/h", x + textW(ks, F(20f)) + F(3f), T + F(106f), F(18f), UNIT)
            if (i < shown.size - 1) { fp.color = BG; c.drawRect(x + colW - F(8f), T + F(40f), x + colW - F(6f), T + F(114f), fp) }
        }
    }

    private fun drawBrama(c: Canvas, r: RectF, ui: com.qext2.primary.gate.GateController.Ui?) {
        cell(c, r)
        val st = ui?.state
        val col = when (st) {
            GS.READY -> "#2E7BFF"
            GS.ARMED, GS.SENDING, GS.WAIT -> "#F2C230"
            GS.OPENED -> "#22C55E"
            GS.ERROR, GS.NO_NET, GS.TOO_FAR -> "#FF5252"
            else -> "#465366"
        }
        val off = st == null || st == GS.FAR || st == GS.NO_GPS
        stp.color = Color.parseColor(col); stp.strokeWidth = F(4f)
        stp.pathEffect = if (off) android.graphics.DashPathEffect(floatArrayOf(F(10f), F(7f)), 0f) else null
        c.drawRoundRect(RectF(X(r.left) + F(3f), Y(r.top) + F(3f), X(r.right) - F(3f), Y(r.bottom) - F(3f)), F(6f), F(6f), stp)
        stp.pathEffect = null
        label(c, r, null, "BRAMA", "")
        ui?.sub?.takeIf { it.isNotEmpty() }?.let { rightLabel(c, r, listOf(Triple(it, 20f, SUB))) }
        val main = ui?.main ?: "BRAMA"
        text(c, main, X(r.left) + F(16f), Y(r.top) + F(60f), fit(main, F(34f), X(r.right) - X(r.left) - F(28f)), if (off) NONE else WHITE)
    }

    private fun drawLight(c: Canvas, r: RectF, state: String?, lu: com.qext2.primary.light.LightController.Ui? = null) {
        cell(c, r)
        if (lu?.controllable == true) {
            stp.color = Color.parseColor("#2E7BFF"); stp.strokeWidth = F(4f)
            c.drawRoundRect(RectF(X(r.left) + F(3f), Y(r.top) + F(3f), X(r.right) - F(3f), Y(r.bottom) - F(3f)), F(6f), F(6f), stp)
        }
        label(c, r, null, "ŚWIATŁO", "")
        lu?.battery?.let { b -> rightLabel(c, r, listOf(Triple(b, 20f, SUB))) }
        val over = lu?.flash ?: (if (state == "OK" || state == null) lu?.mode else null)
        if (over != null) {
            text(c, over, X(r.left) + F(16f), Y(r.top) + F(60f), fit(over, F(34f), X(r.right) - X(r.left) - F(48f)), WHITE)
            val dc = when (state) { "OK" -> "#4ADE80"; "SEARCHING" -> "#FF5252"; else -> null }
            dc?.let { fp.color = Color.parseColor(it); c.drawCircle(X(r.right) - F(20f), Y(r.top) + F(60f), F(7f), fp) }
            return
        }
        val txt: String; val col: Int; val dot: String?
        when (state) {
            "OK" -> { txt = "POŁĄCZONA"; col = WHITE; dot = "#4ADE80" }
            "SEARCHING" -> { txt = "BRAK"; col = Color.parseColor("#FF8C8C"); dot = "#FF5252" }
            "NA" -> { txt = "brak lampy"; col = NONE; dot = null }
            else -> { txt = "—"; col = NONE; dot = null }
        }
        dot?.let { fp.color = Color.parseColor(it); c.drawCircle(X(r.right) - F(20f), Y(r.top) + F(20f), F(7f), fp) }
        text(c, txt, X(r.left) + F(16f), Y(r.top) + F(60f), fit(txt, F(34f), X(r.right) - X(r.left) - F(28f)), col)
    }

    // ======================================================================== INFO (16.10.2026)
    @Synchronized
    fun renderInfo(w: Int, h: Int, d: StatsV2Data, wx: com.qext2.primary.info.InfoWx?, scale: Int, nowMs: Long, dawnMs: Long?, duskMs: Long?): Bitmap {
        tp.typeface = com.qext2.primary.kokpit.Kokpit2Fonts.semi
        val bw = w.coerceAtLeast(120)
        val bh = h.coerceAtLeast(160)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        sx = bw / BW; sy = bh / BH; s = min(sx, sy)
        c.drawColor(BG)
        drawRoute(c, RectF(0f, 0f, BW, 201f), d)
        drawAscent(c, RectF(0f, 203f, BW, 295f), d)
        drawWeather(c, RectF(0f, 297f, BW, BH), d, wx, scale, nowMs, dawnMs, duskMs)
        return bmp
    }

    private class WxS(val x: Float, val tMin: Float, val km: Float, val feels: Float?, val mm: Float, val prob: Float?, val head: Float?, val gust: Float?)

    private fun lerpBy(items: List<com.qext2.primary.info.WxItem>, key: (com.qext2.primary.info.WxItem) -> Float, v: Float,
                       sel: (com.qext2.primary.info.WxItem) -> Float?): Float? {
        val pts = items.filter { sel(it) != null }
        if (pts.isEmpty()) return null
        if (v <= key(pts.first())) return sel(pts.first())
        if (v >= key(pts.last())) return sel(pts.last())
        var i = 1
        while (i < pts.size && key(pts[i]) < v) i++
        val a = pts[i - 1]; val b = pts[i]
        val ka = key(a); val kb = key(b)
        val f = if (kb == ka) 0f else (v - ka) / (kb - ka)
        return sel(a)!! + (sel(b)!! - sel(a)!!) * f
    }

    private fun hmm(min: Float): String { val m = Math.round(min); return "${m / 60}:" + (m % 60).toString().padStart(2, '0') }

    /** wartosc duza + jednostka mala (szara), wyrownanie wzgledem x */
    private fun valUnit(c: Canvas, v: String, u: String, x: Float, cy: Float, vs: Float, us: Float, col: Int, align: Paint.Align) {
        val vw = textW(v, vs); val uw = if (u.isEmpty()) 0f else F(3f) + textW(u, us)
        val x0 = when (align) { Paint.Align.LEFT -> x; Paint.Align.RIGHT -> x - vw - uw; else -> x - (vw + uw) / 2f }
        text(c, v, x0, cy, vs, col)
        if (u.isNotEmpty()) text(c, u, x0 + vw + F(3f), cy + F(3f), us, UNIT)
    }

    /** opis osi na ciemnym tle (nie nachodzi na linie wykresu) */
    private fun ylab(c: Canvas, t: String, x: Float, y: Float, col: Int, right: Boolean = false) {
        val tw = textW(t, F(20f)); val x0 = if (right) x - tw else x
        fp.color = Color.parseColor("#14181D")
        c.drawRoundRect(RectF(x0 - F(3f), y - F(12f), x0 + tw + F(3f), y + F(12f)), F(3f), F(3f), fp)
        text(c, t, x0, y, F(20f), col)
    }

    private fun drawWeather(c: Canvas, r: RectF, d: StatsV2Data, wx: com.qext2.primary.info.InfoWx?, scale: Int, nowMs: Long, dawnMs: Long?, duskMs: Long?) {
        cell(c, r)
        val oy = r.top - 2f
        fun yy(v: Float) = Y(oy + v)
        val route = wx?.route ?: d.hasRoute
        // naglowek + skala (aktywna w plakietce)
        text(c, "POGODA", X(10f), yy(23f), F(24f), LABEL)
        text(c, if (route) "na trasie" else "kolejne godziny", X(10f) + textW("POGODA", F(24f)) + F(8f), yy(25f), F(18f), UNIT)
        val labs = if (route) listOf("20 km", "50 km", "całość") else listOf("1 h", "3 h", "całość")
        var rx = X(470f)
        for (i in 2 downTo 0) {
            val t = labs[i]; val tw = textW(t, F(20f))
            if (i == scale) {
                fp.color = Color.parseColor("#F2F4F7"); c.drawRoundRect(RectF(rx - tw - F(16f), yy(9f), rx, yy(37f)), F(4f), F(4f), fp)
                text(c, t, rx - tw - F(8f), yy(23f), F(20f), Color.parseColor("#111315"))
            } else text(c, t, rx - tw - F(4f), yy(23f), F(20f), UNIT)
            rx -= tw + F(22f)
        }
        val items = wx?.items ?: emptyList()
        if (items.isEmpty()) { none(c, X(240f), yy(230f), "pogoda: czekam na QBota"); return }
        val since = (nowMs - wx!!.fetchedMs) / 60_000f
        val X0 = X(10f); val X1 = X(470f); val XN = X0 + F(30f); val W = X1 - XN   // XN = TU I TERAZ
        val kmNow = d.doneKm
        val n = 80
        val samples = ArrayList<WxS>(n + 1)
        val spanKm: Float; val spanMin: Float
        if (route) {
            val lastKm = items.last().km ?: kmNow
            val rest = ((d.totalKm ?: lastKm) - kmNow).coerceAtLeast(1f)
            spanKm = when (scale) { 0 -> 20f; 1 -> 50f; else -> rest }.coerceAtMost(rest.coerceAtLeast(1f))
            for (i in 0..n) {
                val f = i / n.toFloat(); val km = kmNow + spanKm * f
                val key: (com.qext2.primary.info.WxItem) -> Float = { it.km ?: 0f }
                val t = (lerpBy(items, key, km) { it.t } ?: 0f) - since
                samples.add(WxS(XN + W * f, t, km, lerpBy(items, key, km) { it.feels }, lerpBy(items, key, km) { it.mm } ?: 0f,
                    lerpBy(items, key, km) { it.prob }, lerpBy(items, key, km) { it.head }, lerpBy(items, key, km) { it.gust }))
            }
            spanMin = (samples.last().tMin - samples.first().tMin).coerceAtLeast(1f)
        } else {
            spanKm = 0f
            spanMin = when (scale) { 0 -> 60f; 1 -> 180f; else -> 360f }
            for (i in 0..n) {
                val f = i / n.toFloat(); val tm = spanMin * f
                val key: (com.qext2.primary.info.WxItem) -> Float = { it.t - since }
                samples.add(WxS(XN + W * f, tm, 0f, lerpBy(items, key, tm) { it.feels }, lerpBy(items, key, tm) { it.mm } ?: 0f,
                    lerpBy(items, key, tm) { it.prob }, lerpBy(items, key, tm) { it.head }, lerpBy(items, key, tm) { it.gust }))
            }
        }
        val t0 = samples.first().tMin
        // 16.10: bez opadu w zakresie (slupki < 0,1 mm/h i szansa < 30%) pas OPAD znika, miejsce dostaja temperatura i wiatr
        val showRain = samples.any { it.mm >= 0.1f || (it.prob ?: 0f) >= 30f }
        fun clockAt(tMin: Float) = clock(nowMs + (tMin * 60_000f).toLong())
        // noc: przed switem / po zmierzchu (dzisiejsze); granice zgodne z czasem
        val PT = yy(86f); val PB = yy(398f)
        fun isNight(tMin: Float): Boolean {
            val ms = nowMs + (tMin * 60_000f).toLong()
            val da = dawnMs ?: return false; val du = duskMs ?: return false
            return ms < da || (ms > du && ms < da + 86_400_000L)
        }
        var ns: Float? = null
        fp.color = Color.parseColor("#1C2B4A"); fp.alpha = 150
        for ((i, sm) in samples.withIndex()) {
            val nt = isNight(sm.tMin)
            if (nt && ns == null) ns = sm.x
            if ((!nt || i == n) && ns != null) { c.drawRect(ns!!, PT, sm.x, PB, fp); ns = null }
        }
        fp.alpha = 255
        // ---------------- temperatura
        text(c, "TEMPERATURA", X(16f), yy(58f), F(24f), LABEL)
        text(c, "odcz. °C", X(16f) + textW("TEMPERATURA", F(24f)) + F(6f), yy(60f), F(20f), UNIT)
        val TT = yy(98f); val TB = yy(if (showRain) 170f else 212f)
        val fs = samples.mapNotNull { it.feels }
        if (fs.isNotEmpty()) {
            val lo = Math.floor(fs.minOrNull()!!.toDouble()).toFloat() - 1f; val hi = Math.ceil(fs.maxOrNull()!!.toDouble()).toFloat() + 1f
            fun ty(v: Float) = TB - (v - lo) / (hi - lo) * (TB - TT)
            val tl = listOf(hi, (lo + hi) / 2f, lo)   // 16.10: tylko gora, srodek i dol zakresu
            stp.color = Color.parseColor("#2A3341"); stp.strokeWidth = F(1.5f)
            for (v in tl) c.drawLine(X0, ty(v), X1, ty(v), stp)
            val path = Path()
            var first = true
            for (sm in samples) { val f = sm.feels ?: continue; if (first) { path.moveTo(sm.x, ty(f)); first = false } else path.lineTo(sm.x, ty(f)) }
            stp.color = Color.BLACK; stp.strokeWidth = F(7f); c.drawPath(path, stp)
            stp.color = Color.parseColor("#FF7A45"); stp.strokeWidth = F(3.5f); c.drawPath(path, stp)
            for (v in tl) ylab(c, "${Math.round(v)}°", X(14f), ty(v), SUB)
            val minS = samples.filter { it.feels != null }.minByOrNull { it.feels!! }!!
            val idx = samples.indexOf(minS)
            val where = if (route) "km ${Math.round(minS.km)}" else "o ${clockAt(minS.tMin)}"
            text(c, "min ${Math.round(minS.feels!!)}° · $where", X1, yy(60f), F(22f), SUB, Paint.Align.RIGHT)
            fun lab(sm: WxS, al: Paint.Align) { val fv = sm.feels ?: return; val y = ty(fv); text(c, "${Math.round(fv)}°", sm.x + (if (al == Paint.Align.LEFT) F(48f) else if (al == Paint.Align.RIGHT) -F(48f) else 0f), if (y < TT + F(34f)) y + F(24f) else y - F(22f), F(26f), WHITE, al) }
            lab(samples.first(), Paint.Align.LEFT); lab(samples.last(), Paint.Align.RIGHT)
            if (idx in 9 until n - 8) {
                fp.color = Color.parseColor("#14181D"); c.drawCircle(minS.x, ty(minS.feels!!), F(6f), fp)
                stp.color = Color.parseColor("#FF7A45"); stp.strokeWidth = F(3f); c.drawCircle(minS.x, ty(minS.feels!!), F(6f), stp)
                lab(minS, Paint.Align.CENTER)
            }
        } else none(c, X(240f), (TT + TB) / 2f, "brak")
        val RT = yy(224f); val RB = yy(280f)
        if (showRain) {
        // ---------------- opad
        text(c, "OPAD", X(16f), yy(196f), F(24f), LABEL)
        text(c, "mm/h", X(16f) + textW("OPAD", F(24f)) + F(6f), yy(198f), F(20f), UNIT)
        val radarUntil = wx?.radarUntilMin?.let { it - since }
        if (radarUntil != null && radarUntil > 0f) text(c, "· radar ${Math.round(radarUntil)}′", X(16f) + textW("OPAD", F(24f)) + textW("mm/h", F(20f)) + F(14f), yy(198f), F(20f), Color.parseColor("#9CC7FF"))
        val mmax = samples.maxOf { it.mm }
        val rMax = Math.max(2.0, Math.ceil(mmax.toDouble())).toFloat()
        stp.color = Color.parseColor("#2A3341"); stp.strokeWidth = F(1.5f)
        c.drawLine(X0, RT, X1, RT, stp); c.drawLine(X0, (RT + RB) / 2f, X1, (RT + RB) / 2f, stp)
        val cyan = Color.parseColor("#7FDBFF")
        val bwid = W / (n + 1)
        for (sm in samples) if (sm.mm > 0.05f) {
            fp.color = Color.parseColor(if (radarUntil != null && sm.tMin <= radarUntil) "#7FB2FF" else "#2E7BFF")   // jasny = radar, ciemny = model
            c.drawRect(sm.x - bwid / 2f + F(0.5f), RB - sm.mm.coerceAtMost(rMax) / rMax * (RB - RT), sm.x + bwid / 2f - F(0.5f), RB, fp)
        }
        stp.color = Color.parseColor("#465366"); stp.strokeWidth = F(2f); c.drawLine(X0, RB, X1, RB, stp)
        val pPath = Path(); var pf = true
        for (sm in samples) { val p = sm.prob ?: continue; val y = RB - p / 100f * (RB - RT); if (pf) { pPath.moveTo(sm.x, y); pf = false } else pPath.lineTo(sm.x, y) }
        stp.color = cyan; stp.strokeWidth = F(2.5f); c.drawPath(pPath, stp)
        ylab(c, fmt("%.0f", rMax), X(14f), RT, SUB); ylab(c, "0", X(14f), RB, SUB)
        ylab(c, "100%", X(466f), RT, cyan, true); ylab(c, "0", X(466f), RB, cyan, true)
        val fr = samples.firstOrNull { it.mm >= 0.3f }
        if (fr != null) text(c, "deszcz za ${hmm((fr.tMin).coerceAtLeast(0f))}" + (if (route) " · km ${Math.round(fr.km)}" else " · ${clockAt(fr.tMin)}"), X1, yy(198f), F(22f), cyan, Paint.Align.RIGHT)
        else text(c, "bez opadu", X1, yy(198f), F(22f), SUB, Paint.Align.RIGHT)
        }
        // ---------------- wiatr (+ w twarz / - w plecy)
        val dw = if (showRain) 0f else -66f
        text(c, "WIATR", X(16f), yy(306f + dw), F(24f), LABEL)
        text(c, "m/s", X(16f) + textW("WIATR", F(24f)) + F(6f), yy(308f + dw), F(20f), UNIT)
        val WT = yy(if (showRain) 334f else 282f); val Y0 = yy(if (showRain) 364f else 338f)
        val hs = samples.mapNotNull { it.head }
        val gmax = samples.filter { (it.head ?: 0f) > 0f }.mapNotNull { it.gust }.maxOrNull() ?: 0f
        val wmax = Math.max(6.0, Math.ceil(Math.max(hs.maxOfOrNull { kotlin.math.abs(it) } ?: 0f, gmax).toDouble())).toFloat()
        val ky = (Y0 - WT) / wmax
        stp.color = Color.parseColor("#2A3341"); stp.strokeWidth = F(1.5f)
        c.drawLine(X0, Y0 - wmax * ky, X1, Y0 - wmax * ky, stp); c.drawLine(X0, Y0 + wmax * ky, X1, Y0 + wmax * ky, stp)
        if (hs.isNotEmpty()) {
            fun area(pos: Boolean): Path {
                val p = Path(); p.moveTo(X0, Y0)
                for (sm in samples) { val v = sm.head ?: 0f; p.lineTo(sm.x, Y0 - (if (pos) Math.max(0f, v) else Math.min(0f, v)) * ky) }
                p.lineTo(X1, Y0); p.close(); return p
            }
            fp.color = Color.parseColor("#D96AA8"); fp.alpha = 230; c.drawPath(area(true), fp)
            fp.color = Color.parseColor("#3F8F45"); c.drawPath(area(false), fp); fp.alpha = 255
            stp.strokeWidth = F(2f); stp.pathEffect = android.graphics.DashPathEffect(floatArrayOf(F(2f), F(5f)), 0f); stp.color = Color.parseColor("#F2F4F7")
            var gp: Path? = null
            for (sm in samples) {
                val hv = sm.head ?: 0f; val g = sm.gust
                if (hv > 0f && g != null) { val y = Y0 - Math.min(wmax, g) * ky; if (gp == null) { gp = Path(); gp.moveTo(sm.x, y) } else gp.lineTo(sm.x, y) }
                else if (gp != null) { c.drawPath(gp, stp); gp = null }
            }
            gp?.let { c.drawPath(it, stp) }
            stp.pathEffect = null
            val mx = hs.maxOrNull() ?: 0f
            text(c, if (mx > 0.5f) "maks. czołowy ${Math.round(mx)} · poryw ${Math.round(gmax)}" else "głównie w plecy", X1, yy(308f + dw), F(22f), SUB, Paint.Align.RIGHT)
        }
        stp.color = Color.parseColor("#C9D2DC"); stp.strokeWidth = F(1.5f); c.drawLine(X0, Y0, X1, Y0, stp)
        ylab(c, "+${wmax.toInt()}", X(14f), Y0 - wmax * ky, Color.parseColor("#F0A6CF")); ylab(c, "−${wmax.toInt()}", X(14f), Y0 + wmax * ky, Color.parseColor("#8FD18A"))
        // ---------------- os X: teraz + rowne podzialki (+ meta przy calosci), swit/zmierzch
        data class Tk(val x: Float, val top: String, val u: String, val bot: String, val end: Boolean = false)
        val ticks = ArrayList<Tk>()
        ticks.add(Tk(XN, "teraz", "", clockAt(t0)))
        if (route) {
            val pxKm = W / spanKm
            val st = listOf(5f, 10f, 20f, 25f, 50f).firstOrNull { it * pxKm >= F(90f) } ?: 50f
            var k = Math.ceil(((kmNow + 0.01f) / st).toDouble()).toFloat() * st
            while (k <= kmNow + spanKm + 0.01f) {
                val sm = samples.minByOrNull { kotlin.math.abs(it.km - k) }!!
                ticks.add(Tk(XN + W * (k - kmNow) / spanKm, k.toInt().toString(), "km", clockAt(sm.tMin)))
                k += st
            }
            if (scale == 2) ticks.add(Tk(X1, Math.round(kmNow + spanKm).toString(), "km", "meta " + clockAt(samples.last().tMin), true))
        } else {
            val pxMin = W / spanMin
            val st = listOf(15f, 30f, 60f, 120f, 180f).firstOrNull { it * pxMin >= F(90f) } ?: 180f
            val nowMin = (java.util.Calendar.getInstance().apply { timeInMillis = nowMs }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }).toFloat()
            var m = Math.ceil(((nowMin + 1f) / st).toDouble()).toFloat() * st
            while (m - nowMin <= spanMin + 0.01f) {
                val rel = m - nowMin
                ticks.add(Tk(XN + W * rel / spanMin, clockAt(rel), "", "+" + hmm(rel)))
                m += st
            }
        }
        val endT = ticks.firstOrNull { it.end }
        val keep = ArrayList<Tk>(); keep.add(ticks[0])
        for (t in ticks.drop(1)) if (!t.end && t.x - XN >= F(120f) && !(endT != null && endT.x - t.x < F(125f))) keep.add(t)
        if (keep.size > 2) { val lt = keep.last(); val pv = keep[keep.size - 2]; if (X1 - lt.x < F(30f) && lt.x - pv.x < F(130f)) keep.removeAt(keep.size - 1) }
        endT?.let { keep.add(it) }
        // swit / zmierzch w zakresie: linia + podpis na osi (bez kolizji z teraz / meta)
        val sunTicks = ArrayList<Tk>()
        for ((ev, name) in listOf(dawnMs to "świt", duskMs to "zmrok")) {
            ev ?: continue
            for (e in listOf(ev, ev + 86_400_000L)) {
                val rel = (e - nowMs) / 60_000f
                if (rel <= t0 || rel > samples.last().tMin) continue
                val x = if (route) { val sm = samples.minByOrNull { kotlin.math.abs(it.tMin - rel) }!!; sm.x } else XN + W * rel / spanMin
                stp.color = Color.parseColor("#6B7685"); stp.strokeWidth = F(2f); stp.pathEffect = android.graphics.DashPathEffect(floatArrayOf(F(4f), F(5f)), 0f)
                c.drawLine(x, PT, x, PB, stp); stp.pathEffect = null
                if (x - XN >= F(110f) && !(endT != null && endT.x - x < F(110f))) sunTicks.add(Tk(x, name, "", clock(e)))
            }
        }
        for (st in sunTicks) keep.removeAll { !it.end && it !== keep[0] && kotlin.math.abs(it.x - st.x) < F(100f) }
        stp.color = Color.parseColor("#2A3341"); stp.strokeWidth = F(1.5f)
        for (t in keep) { c.drawLine(t.x, TT - F(6f), t.x, TB, stp); if (showRain) c.drawLine(t.x, RT - F(4f), t.x, RB, stp); c.drawLine(t.x, WT - F(4f), t.x, Y0 + wmax * ky, stp) }
        for ((i, t) in keep.withIndex()) {
            val al = if (i == 0) Paint.Align.LEFT else if (t.x > X1 - F(30f)) Paint.Align.RIGHT else Paint.Align.CENTER
            val xx = if (i == 0) X0 + F(4f) else t.x
            valUnit(c, t.top, t.u, xx, yy(416f), F(28f), F(20f), WHITE, al)
            text(c, t.bot, xx, yy(448f), F(20f), UNIT, al)
        }
        // TU I TERAZ: zielona kropka w kazdym wykresie
        fun nowDot(y: Float) { fp.color = Color.parseColor("#111315"); c.drawCircle(XN, y, F(9f), fp); fp.color = Color.parseColor("#39FF14"); c.drawCircle(XN, y, F(6.5f), fp) }
        samples.first().feels?.let { f0 -> if (fs.isNotEmpty()) { val lo2 = Math.floor(fs.minOrNull()!!.toDouble()).toFloat() - 1f; val hi2 = Math.ceil(fs.maxOrNull()!!.toDouble()).toFloat() + 1f; nowDot(TB - (f0 - lo2) / (hi2 - lo2) * (TB - TT)) } }
        if (showRain) nowDot(RB - samples.first().mm.coerceAtMost(Math.max(2.0, Math.ceil(samples.maxOf { it.mm }.toDouble())).toFloat()) / Math.max(2.0, Math.ceil(samples.maxOf { it.mm }.toDouble())).toFloat() * (RB - RT))
        nowDot(Y0 - (samples.first().head ?: 0f) * ky)
        for (t in sunTicks) {
            val al = if (t.x > X1 - F(40f)) Paint.Align.RIGHT else Paint.Align.CENTER
            text(c, t.top, t.x, yy(416f), F(22f), SUB, al)
            text(c, t.bot, t.x, yy(448f), F(20f), SUB, al)
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
        val vw = textW(v, F(46f)) + F(4f) + textW("%/h", F(22f))
        text(c, v, cx - vw / 2f, mid - F(10f), F(46f), WHITE)
        text(c, "%/h", cx - vw / 2f + textW(v, F(46f)) + F(4f), mid - F(4f), F(22f), UNIT)
        d.batLeftSec?.let { text(c, "~" + hm(it) + " h", cx, b - F(6f), F(20f), SUB, Paint.Align.CENTER) }
    }
}
