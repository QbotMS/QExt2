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
    /** 18.10: ciagle NP z ostatnich 5 min ruchu (StatsCalculator.np5Watts) */
    val np5Now: Int? = null,
    /** 0.4.26: kamera DJI Osmo - bateria % (null = brak polaczenia), camOn = kamera wybrana w SETUP */
    val camBat: Int? = null,
    val camOn: Boolean = false,
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
    /** 0.4.27: linia EF w PRZEBIEG (decyzja Michala) */
    private val MAGENTA = Color.parseColor("#FF3DF5")

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
        val rows = floatArrayOf(113f, 136f, 100f, 86f, 120f, 108f)   // 0.4.27: BRAMA/ZASILANIE wyzszy, CARB nizszy
        var y = 0f
        val col3 = (BW - 2 * g) / 3f
        // 1: NP / IF / VI
        drawNp(c, RectF(0f, y, col3, y + rows[0]), d)
        drawIf(c, RectF(col3 + g, y, 2 * col3 + g, y + rows[0]), d.ifv)
        drawViKad(c, RectF(2 * col3 + 2 * g, y, BW, y + rows[0]), d)   // 0.4.27: VI + srednia kadencja
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
        // 4: PLAN CZASU na cala szerokosc (0.4.27)
        drawPlan(c, RectF(0f, y, BW, y + rows[3]), d)
        y += rows[3] + g
        // 5: TEMPO co godzine
        drawTempo(c, RectF(0f, y, BW, y + rows[4]), d)
        y += rows[4] + g
        // 6: BRAMA (przycisk, nakladka w field_stats_v2.xml) 44% | ZASILANIE (lampa + Karoo + kamera) -- 0.4.27
        val wGate = (BW - g) * 0.44f
        drawBrama(c, RectF(0f, y, wGate, y + rows[5]), d.gate)
        drawPower(c, RectF(wGate + g, y, BW, y + rows[5]), d)
        y += rows[5] + g
        // 7: CARB CEL | CARB SPAL. | WODA (0.4.27)
        drawFood(c, RectF(0f, y, BW, BH), d)
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
    /** Pasek etykiety: ikona + nazwa + jednostka; zwraca dolna krawedz paska (px). 0.4.27: 21 px (bylo 24). */
    private fun label(c: Canvas, r: RectF, iconPath: List<String>?, name: String, unit: String, material: Boolean = false): Float {
        val left = X(r.left) + F(10f)
        val top = Y(r.top) + F(5f)
        val hRow = F(25f)
        var x = left
        if (iconPath != null) {
            icon(c, iconPath, x, top + F(2f), F(20f), Color.parseColor("#F0F3F6"), material)
            x += F(25f)
        }
        text(c, name, x, top + hRow / 2f, F(21f), LABEL)
        x += textW(name, F(21f)) + F(5f)
        if (unit.isNotEmpty()) text(c, unit, x, top + hRow / 2f, F(19f), UNIT)
        return top + hRow
    }

    private fun rightLabel(c: Canvas, r: RectF, parts: List<Triple<String, Float, Int>>) {
        val top = Y(r.top) + F(5f)
        val cy = top + F(12.5f)
        var x = X(r.right) - F(10f)
        for ((t, size, col) in parts.reversed()) {
            text(c, t, x, cy, F(size * 0.9f), col, Paint.Align.RIGHT)
            x -= textW(t, F(size * 0.9f)) + F(5f)
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
        if (d.np != null && d.npZone != null) rightLabel(c, r, listOf(Triple("Z${d.npZone}", 36f, zoneColor(d.npZone))))
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
        // 0.4.27: liczba zawsze po stronie z wiekszym miejscem -- nigdy na znaczniku
        val spL = mx - F(6f) - l; val spR = rr - (mx + F(6f)); val onLeft = spL >= spR; val sp0 = maxOf(spL, spR)
        var fs = F(42f)
        while (fs > F(22f) && textW(txt, fs) + F(12f) > sp0) fs -= 1f
        if (onLeft) barText(c, txt, mx - F(6f), cy, fs, sp0, cols[0], Paint.Align.RIGHT) else barText(c, txt, mx + F(6f), cy, fs, sp0, cols[0], Paint.Align.LEFT)
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
        var tx = l + F(100f)
        if (d.demo) { text(c, "DEMO", tx, T + F(21f), F(18f), AMBER); tx += textW("DEMO", F(18f)) + F(8f) }
        d.twilightMs?.let {
            val lbl = if (d.twilightDawn) "świt" else "zmrok"
            text(c, lbl, tx, T + F(26f), F(22f), SUB); tx += textW(lbl, F(22f)) + F(6f)
            text(c, clock(it), tx, T + F(24f), F(38f), WHITE)   // 0.4.27: zmrok wiekszy
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
        val sumSize = F(44f)
        val sumBase = Y(r.bottom) - F(6f)
        val ct = Y(r.top) + F(31f)
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
            fp.alpha = if (d.winPartial && k == n - 1) 100 else 150   // 0.4.27: przygaszone pod linia EF
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
            stp.color = Color.BLACK; stp.strokeWidth = F(11f); c.drawPath(path, stp)
            stp.color = MAGENTA; stp.strokeWidth = F(5.5f); c.drawPath(path, stp)   // 0.4.27: magenta
            for ((k, e) in efv) {
                val px = xAt(k) + bw / 2f; val py = yOf(e)
                fp.color = Color.BLACK; c.drawCircle(px, py, F(6f), fp)
                fp.color = MAGENTA; c.drawCircle(px, py, F(4.4f), fp)
            }
        }
        // wiersz liczb: ostatnie pelne okno, EF (typowe), najlepsze okno
        val li = lastDone.coerceAtLeast(0)
        val sb = sumBase
        fun grp(x: Float, label: String, value: String, unit: String, align: Paint.Align) {
            val lw = textW(label, F(22f)) + F(5f); val vw = textW(value, sumSize); val uw = if (unit.isEmpty()) 0f else F(5f) + textW(unit, F(18f))
            val total = lw + vw + uw
            val x0 = when (align) { Paint.Align.LEFT -> x; Paint.Align.RIGHT -> x - total; else -> x - total / 2f }
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = F(22f); tp.color = SUB; c.drawText(label, x0, sb, tp)
            tp.textSize = sumSize; tp.color = WHITE; c.drawText(value, x0 + lw, sb, tp)
            if (unit.isNotEmpty()) { tp.textSize = F(18f); tp.color = UNIT; c.drawText(unit, x0 + lw + vw + F(5f), sb, tp) }
        }
        grp(l, "5′", (d.np5Now ?: np[li]).toString(), "W", Paint.Align.LEFT)
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
        rightLabel(c, r, listOf(Triple("razem", 26f, SUB), Triple((dn + lf).toString(), 42f, WHITE)))
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

    /** 0.4.27: kolumny ZASILANIE / CARB: podpis i liczba od tej samej krawedzi, STALA wielkosc cyfr, jednostka na linii cyfr */
    private class Col(val x: Float, val w: Float, val sep: Float, val cw: Float)
    private fun cols(r: RectF, fr: FloatArray): List<Col> {
        val l0 = X(r.left); val wt = X(r.right) - l0
        var acc = 0f
        return fr.map { f -> val o = Col(l0 + wt * acc + F(14f), wt * f - F(22f), l0 + wt * acc, wt * f); acc += f; o }
    }
    private fun miniCol(c: Canvas, col: Col, top: Float, lab: String, v: String?, u: String, color: Int, vs: Float, ly: Float, vy: Float) {
        text(c, lab, col.x, top + F(ly), F(19f), UNIT)
        if (v == null) { text(c, "brak", col.x, top + F(vy), F(26f), NONE); return }
        val sz = F(vs)
        text(c, v, col.x, top + F(vy), sz, color)
        if (u.isNotEmpty()) text(c, u, col.x + textW(v, sz) + F(3f), top + F(vy) + (sz - F(20f)) * 0.34f, F(20f), UNIT)
    }
    private fun colSep(c: Canvas, x: Float, top: Float, t0: Float, t1: Float) { fp.color = BG; c.drawRect(x - F(1f), top + F(t0), x + F(1f), top + F(t1), fp) }
    /** wartosc + mala jednostka wysrodkowane wokol cx */
    private fun cval(c: Canvas, cx: Float, cy: Float, v: String, vs: Float, u: String, us: Float, color: Int) {
        val tw = textW(v, vs) + (if (u.isNotEmpty()) F(3f) + textW(u, us) else 0f)
        val x0 = cx - tw / 2f
        text(c, v, x0, cy, vs, color)
        if (u.isNotEmpty()) text(c, u, x0 + textW(v, vs) + F(3f), cy + (vs - us) * 0.34f, us, UNIT)
    }

    /** 0.4.27: dolny rzad -- trzy rowne kolumny CARB CEL | CARB SPAL. | WODA */
    private fun drawFood(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        val top = Y(r.top); val cs = cols(r, floatArrayOf(1f / 3f, 1f / 3f, 1f / 3f))
        miniCol(c, cs[0], top, "CARB CEL", d.carbRate?.toString(), "g/h", WHITE, 44f, 17f, 55f)
        miniCol(c, cs[1], top, "CARB SPAL.", d.carbSpent?.toString(), "g", WHITE, 44f, 17f, 55f)
        miniCol(c, cs[2], top, "WODA", d.fluidRate?.let { fmt("%.1f", it) }, "l/h", WHITE, 44f, 17f, 55f)
        colSep(c, cs[1].sep, top, 8f, 78f); colSep(c, cs[2].sep, top, 8f, 78f)
    }

    /** 0.4.27: VI i srednia kadencja w jednej kratce, poziomo; podpis sredniej: O nad KAD */
    private fun drawViKad(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        val l = X(r.left) + F(10f); val rr = X(r.right) - F(10f); val top = Y(r.top); val hh = Y(r.bottom) - top
        val y1 = top + hh * 0.28f; val y2 = top + hh * 0.74f
        text(c, "VI", l, y1, F(22f), LABEL)
        val vi = d.vi?.let { fmt("%.2f", it) }
        if (vi == null) text(c, "brak", rr, y1, F(24f), NONE, Paint.Align.RIGHT) else text(c, vi, rr, y1, fit(vi, F(48f), rr - l - F(40f)), viColor(d.vi), Paint.Align.RIGHT)
        text(c, "Ø", l + F(3f), y2 - F(11f), F(19f), LABEL)
        text(c, "KAD", l, y2 + F(11f), F(19f), LABEL)
        val kv = d.cadAvg?.toString()
        if (kv == null) text(c, "brak", rr, y2, F(24f), NONE, Paint.Align.RIGHT) else text(c, kv, rr, y2, fit(kv, F(48f), rr - l - F(46f)), WHITE, Paint.Align.RIGHT)
        fp.color = BG; c.drawRect(X(r.left) + F(8f), top + hh * 0.51f - F(1f), X(r.right) - F(8f), top + hh * 0.51f + F(1f), fp)
    }

    /** 0.4.27: tryb lampy z tekstu LightController (MIGANIE / STAŁE MOCNE / STAŁE SŁABE / WYŁĄCZONA) */
    private fun lampMode(t: String?): String? {
        val u = (t ?: "").uppercase()
        return when {
            "MIG" in u -> "FLASH"
            Regex("S[ŁL]AB").containsMatchIn(u) -> "WEAK"
            Regex("STA[ŁL]").containsMatchIn(u) -> "STRONG"
            Regex("WY[ŁL]|OFF").containsMatchIn(u) -> "OFF"
            else -> null
        }
    }

    /** 0.4.27: ikona lampy jak kontrolka w aucie: korpus "D" + wiazka. 4 promienie = stale mocne, 2 = stale slabe, gwiazdka = miganie, obrys = wyl. */
    private fun lampIco(c: Canvas, mode: String, x: Float, cy: Float, col: Int) {
        val h = F(26f); val wB = F(14f); val top = cy - h / 2f; val xf = x + wB; val x0 = xf + F(5f)
        val p = Path(); p.moveTo(xf, top); p.lineTo(xf, top + h); p.cubicTo(xf - wB * 1.35f, top + h, xf - wB * 1.35f, top, xf, top); p.close()
        if (mode == "OFF") { stp.color = col; stp.strokeWidth = F(2.5f); c.drawPath(p, stp); return }
        fp.color = col; c.drawPath(p, fp)
        stp.color = col; stp.strokeWidth = F(3f); stp.strokeCap = Paint.Cap.ROUND
        if (mode == "FLASH") {
            val sx0 = x0 + F(9f); val sr = F(8f)
            for (a in 0 until 3) {
                val an = a * Math.PI / 3.0 + Math.PI / 2.0
                val dx = (Math.cos(an) * sr).toFloat(); val dy = (Math.sin(an) * sr).toFloat()
                c.drawLine(sx0 - dx, cy - dy, sx0 + dx, cy + dy, stp)
            }
        } else {
            val fr = if (mode == "WEAK") floatArrayOf(0.3f, 0.7f) else floatArrayOf(0.12f, 0.37f, 0.63f, 0.88f)
            for (f in fr) { val y = top + h * f; c.drawLine(x0, y, x0 + F(16f), y, stp) }
        }
        stp.strokeCap = Paint.Cap.BUTT
    }

    /** 0.4.27: ZASILANIE = LAMPA (ikona w kolorze trybu nad bateria) | KAROO (%/h nad czasem do konca) | KAM (% pod wartoscia);
     *  kolumny 34/40/26 %, wszystko wysrodkowane w kolumnie; dotkniecie = tryb lampy (niebieska ramka = aktywne) */
    private fun drawPower(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        val lu = d.light; val state = d.lightState; val top = Y(r.top)
        if (lu?.controllable == true) {
            stp.color = Color.parseColor("#2E7BFF"); stp.strokeWidth = F(4f)
            c.drawRoundRect(RectF(X(r.left) + F(3f), Y(r.top) + F(3f), X(r.right) - F(3f), Y(r.bottom) - F(3f)), F(6f), F(6f), stp)
        }
        val cam = d.camOn || d.camBat != null
        val cs = cols(r, if (cam) floatArrayOf(0.34f, 0.40f, 0.26f) else floatArrayOf(0.5f, 0.5f))
        val ok = lu != null && (state == "OK" || state == null)
        val mode = if (ok) lampMode(lu?.flash ?: lu?.mode) else null
        val bat = if (ok) lu?.battery?.replace(Regex("[^0-9]"), "")?.takeIf { it.isNotEmpty() } else null
        val c0 = cs[0].sep + cs[0].cw / 2f
        val dot = if (state == "SEARCHING") Color.parseColor("#FF5252") else if (ok) Color.parseColor("#4ADE80") else null
        text(c, "LAMPA", c0 - (if (dot != null) F(8f) else 0f), top + F(20f), F(19f), UNIT, Paint.Align.CENTER)
        if (dot != null) { fp.color = dot; c.drawCircle(c0 + textW("LAMPA", F(19f)) / 2f + F(4f), top + F(20f), F(6f), fp) }
        when {
            state == "SEARCHING" -> text(c, "BRAK", c0, top + F(62f), F(34f), Color.parseColor("#FF8C8C"), Paint.Align.CENTER)
            !ok -> text(c, "—", c0, top + F(62f), F(34f), NONE, Paint.Align.CENTER)
            else -> {
                if (mode != null) lampIco(c, mode, c0 - F(19f), top + F(48f), when (mode) { "OFF" -> WHITE; "FLASH" -> Color.parseColor("#FB923C"); else -> Color.parseColor("#EF4444") })
                if (bat != null) cval(c, c0, top + F(84f), bat, F(34f), "%", F(18f), WHITE)
            }
        }
        val c1 = cs[1].sep + cs[1].cw / 2f
        text(c, "KAROO", c1, top + F(20f), F(19f), UNIT, Paint.Align.CENTER)
        val bd = d.batDrain
        if (bd == null) text(c, "brak", c1, top + F(54f), F(26f), NONE, Paint.Align.CENTER) else cval(c, c1, top + F(54f), fmt("%.0f", bd), F(40f), "%/h", F(20f), WHITE)
        d.batLeftSec?.let { cval(c, c1, top + F(88f), "~" + hm(it), F(28f), "h", F(18f), WHITE) }
        if (cam) {
            val c2 = cs[2].sep + cs[2].cw / 2f; val cb = d.camBat
            text(c, "KAM", c2, top + F(20f), F(19f), UNIT, Paint.Align.CENTER)
            if (cb == null) text(c, "brak", c2, top + F(58f), F(24f), NONE, Paint.Align.CENTER)
            else {
                text(c, cb.toString(), c2, top + F(54f), F(36f), when { cb < 20 -> Color.parseColor("#FF5252"); cb < 35 -> AMBER; else -> WHITE }, Paint.Align.CENTER)
                text(c, "%", c2, top + F(86f), F(19f), UNIT, Paint.Align.CENTER)
            }
        }
        for (i in 1 until cs.size) colSep(c, cs[i].sep, top, 12f, 96f)
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

    /** 0.4.27: PLAN CZASU na cala szerokosc, duze liczby; zrodlo planu przy naglowku; jazda i postoje w jednym wierszu po prawej */
    private fun drawPlan(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        label(c, r, null, "PLAN CZASU", if (d.planFromQbot) "· plan QBota" else "")
        if (!d.planFromQbot) d.planRefEtaMs?.let { rightLabel(c, r, listOf(Triple("ETA", 20f, SUB), Triple(clock(it), 24f, WHITE))) }
        val l = X(r.left) + F(12f); val rr = X(r.right) - F(12f); val T = Y(r.top); val cy = T + F(55f)
        val dm = d.planDeltaMin
        if (dm == null) { none(c, (X(r.left) + X(r.right)) / 2f, cy, "po 1. km z ETA"); return }
        fun sg(v: Int) = (if (v > 0) "+" else if (v < 0) "−" else "") + kotlin.math.abs(v)
        val s0 = sg(dm)
        val col = when { dm >= 5 -> AMBER; dm <= -5 -> Color.parseColor("#4ADE80"); else -> WHITE }
        text(c, s0, l, cy, F(62f), col)
        val dd = d.planDriveDelta; val ds = d.planStopDelta
        val word = if (dd != null && ds != null) "min" else when { dm > 0 -> "min za planem"; dm < 0 -> "min przed planem"; else -> "min · zgodnie" }
        text(c, word, l + textW(s0, F(62f)) + F(8f), cy + F(8f), F(24f), UNIT)
        if (dd != null && ds != null) {
            var x = rr
            for ((name, v) in listOf("postoje" to ds, "jazda" to dd)) {
                val sv = sg(v)
                text(c, sv, x, cy, F(42f), if (v >= 5) AMBER else WHITE, Paint.Align.RIGHT); x -= textW(sv, F(42f)) + F(6f)
                text(c, name, x, cy + F(4f), F(22f), SUB, Paint.Align.RIGHT); x -= textW(name, F(22f)) + F(22f)
            }
        }
    }

    /** 0.4.26 (osmo-2110): bateria kamery DJI Osmo (OsmoLink, stan z Bluetooth). */
    private fun drawCamBat(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        label(c, r, listOf(P_BATT), "CAM BAT", "")
        val cx = (X(r.left) + X(r.right)) / 2f; val cy = Y(r.top) + F(58f)
        val b = d.camBat
        if (b == null) { none(c, cx, cy, if (d.camOn) "brak łącza" else "brak kamery"); return }
        val col = when { b < 20 -> Color.parseColor("#FF5252"); b < 35 -> AMBER; else -> WHITE }
        val vs = b.toString()
        val wv = textW(vs, F(52f)) + F(4f) + textW("%", F(24f))
        val x0 = cx - wv / 2f
        text(c, vs, x0, cy, F(52f), col)
        text(c, "%", x0 + textW(vs, F(52f)) + F(4f), cy + F(8f), F(24f), UNIT)
    }

    /** 0.4.27: TEMPO -- 3 ostatnie godziny, wieksze liczby, moc w kolorze strefy */
    private fun drawTempo(c: Canvas, r: RectF, d: StatsV2Data) {
        cell(c, r)
        label(c, r, null, "TEMPO", "co godzinę")
        val T = Y(r.top); val l = X(r.left) + F(12f); val rr = X(r.right) - F(10f)
        val all = d.tempo
        if (all.isEmpty()) { none(c, (l + rr) / 2f, T + F(72f), "po 5 min jazdy"); return }
        if (all.size >= 2 && all.first().first > 0) {
            val pct = Math.round((all.last().first - all.first().first) * 100f / all.first().first)
            val ps = (if (pct > 0) "+" else if (pct < 0) "−" else "") + kotlin.math.abs(pct) + "%"
            rightLabel(c, r, listOf(Triple("moc od 1. h", 26f, SUB), Triple(ps, 32f, if (pct <= -10) AMBER else WHITE)))
        }
        val start = (all.size - 3).coerceAtLeast(0)
        val shown = all.subList(start, all.size)
        val colW = (rr - l) / 3f
        shown.forEachIndexed { i, hw ->
            val idx = start + i
            val x = l + colW * i
            val isNow = d.tempoPartial && idx == all.size - 1
            text(c, if (isNow) "teraz" else "${idx + 1}. h", x, T + F(43f), F(26f), SUB)
            val ws = hw.first.toString()
            text(c, ws, x, T + F(72f), F(46f), zoneColor5(hw.first, d.cpW))
            text(c, "W", x + textW(ws, F(46f)) + F(3f), T + F(78f), F(22f), UNIT)
            val ks = fmt("%.1f", hw.second)
            text(c, ks, x, T + F(104f), F(28f), SUB)
            text(c, "km/h", x + textW(ks, F(28f)) + F(3f), T + F(106f), F(18f), UNIT)
            if (i < shown.size - 1) { fp.color = BG; c.drawRect(x + colW - F(8f), T + F(30f), x + colW - F(6f), T + F(116f), fp) }
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

    private class WxS(val x: Float, val tMin: Float, val km: Float, val feels: Float?, val mm: Float, val prob: Float?, val head: Float?, val gust: Float?, val ws: Float? = null)

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

    /** serie prognozy (os: km na trasie albo minuty od pobrania), budowane RAZ na obiekt prognozy */
    private class Ser(val x: FloatArray, val y: FloatArray)
    private class Sers(val t: Ser, val feels: Ser, val mm: Ser, val prob: Ser, val head: Ser, val gust: Ser, val ws: Ser)
    private var sersFor: com.qext2.primary.info.InfoWx? = null
    private var sersVal: Sers? = null
    private fun sers(wx: com.qext2.primary.info.InfoWx): Sers {
        sersVal?.let { if (sersFor === wx) return it }
        fun mk(sel: (com.qext2.primary.info.WxItem) -> Float?): Ser {
            val pts = wx.items.mapNotNull { w -> sel(w)?.let { v -> (if (wx.route) (w.km ?: 0f) else w.t) to v } }.sortedBy { it.first }
            return Ser(FloatArray(pts.size) { pts[it].first }, FloatArray(pts.size) { pts[it].second })
        }
        val s = Sers(mk { it.t }, mk { it.feels }, mk { it.mm }, mk { it.prob }, mk { it.head }, mk { it.gust }, mk { it.ws })
        sersFor = wx; sersVal = s
        return s
    }
    private fun lerpS(s: Ser, q: Float): Float? {
        val x = s.x; val y = s.y; val n = x.size
        if (n == 0) return null
        if (q <= x[0]) return y[0]
        if (q >= x[n - 1]) return y[n - 1]
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (x[m] <= q) lo = m else hi = m }
        val dx = x[hi] - x[lo]
        return if (dx <= 0f) y[lo] else y[lo] + (y[hi] - y[lo]) * (q - x[lo]) / dx
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

    /** 0.4.27: wykresy pogody schodkami (10 odcinkow) zamiast plynnej linii */
    private class Bk(val x0: Float, val x1: Float, val v: Float?, val t0: Float)
    private fun buckets(samples: List<WxS>, sel: (WxS) -> Float?): List<Bk> {
        val n = samples.size - 1
        val out = ArrayList<Bk>(10)
        for (b in 0 until 10) {
            val i0 = Math.round(b * n / 10f); val i1 = Math.round((b + 1) * n / 10f)
            var sum = 0f; var k = 0
            for (i in i0..i1) { val v = sel(samples[i]); if (v != null) { sum += v; k++ } }
            out.add(Bk(samples[i0].x, samples[i1].x, if (k > 0) sum / k else null, samples[i0].tMin))
        }
        return out
    }
    private fun stepPath(samples: List<WxS>, sel: (WxS) -> Float?, toY: (Float) -> Float): Path {
        val p = Path(); var first = true
        for (b in buckets(samples, sel)) {
            val v = b.v ?: continue
            val y = toY(v)
            if (first) { p.moveTo(b.x0, y); first = false } else p.lineTo(b.x0, y)
            p.lineTo(b.x1, y)
        }
        return p
    }

    private fun drawWeather(c: Canvas, r: RectF, d: StatsV2Data, wx: com.qext2.primary.info.InfoWx?, scale: Int, nowMs: Long, dawnMs: Long?, duskMs: Long?) {
        cell(c, r)
        val oy = r.top - 2f
        fun yy(v: Float) = Y(oy + v)
        val route = wx?.route ?: d.hasRoute
        // naglowek + skala (aktywna w plakietce)
        text(c, "POGODA", X(10f), yy(23f), F(24f), LABEL)
        val subTxt = if (route) "na trasie" else "kolejne godziny"
        text(c, subTxt, X(10f) + textW("POGODA", F(24f)) + F(8f), yy(25f), F(18f), UNIT)
        // 16.10: zrodlo pogody -- plakietka "Q" + wiek danych; zielona = QBot na zywo (co 15 min z radarem), biala = QBot, zolta = starsze niz 30 min
        wx?.let { w0 ->
            val age = w0.ageMin + (nowMs - w0.fetchedMs) / 60_000f
            val bcol = Color.parseColor(when { age > 30f -> "#F2C230"; w0.live -> "#39FF14"; else -> "#F2F4F7" })
            val bt = "Q ${Math.round(age)}′"
            val bx = X(10f) + textW("POGODA", F(24f)) + F(8f) + textW(subTxt, F(18f)) + F(10f)
            fp.color = bcol; c.drawRoundRect(RectF(bx, yy(11f), bx + textW(bt, F(18f)) + F(12f), yy(35f)), F(4f), F(4f), fp)
            text(c, bt, bx + F(6f), yy(23f), F(18f), Color.parseColor("#111315"))
        }
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
        val X0 = X(10f); val X1 = X(470f); val XN = X0 + F(38f); val W = X1 - XN   // XN = TU I TERAZ (16.10: +8 px)
        val kmNow = d.doneKm
        val n = 80
        val samples = ArrayList<WxS>(n + 1)
        val spanKm: Float; val spanMin: Float
        if (route) {
            val lastKm = items.last().km ?: kmNow
            val rest = ((d.totalKm ?: lastKm) - kmNow).coerceAtLeast(1f)
            spanKm = when (scale) { 0 -> 20f; 1 -> 50f; else -> rest }.coerceAtMost(rest.coerceAtLeast(1f))
            val S = sers(wx)   // 18.10: serie liczone raz na prognoze (wczesniej filtrowanie listy przy kazdej probce, co 1,5 s)
            for (i in 0..n) {
                val f = i / n.toFloat(); val km = kmNow + spanKm * f
                val t = (lerpS(S.t, km) ?: 0f) - since
                samples.add(WxS(XN + W * f, t, km, lerpS(S.feels, km), lerpS(S.mm, km) ?: 0f, lerpS(S.prob, km), lerpS(S.head, km), lerpS(S.gust, km), lerpS(S.ws, km)))
            }
            spanMin = (samples.last().tMin - samples.first().tMin).coerceAtLeast(1f)
        } else {
            spanKm = 0f
            spanMin = when (scale) { 0 -> 60f; 1 -> 180f; else -> 360f }
            val S = sers(wx)
            for (i in 0..n) {
                val f = i / n.toFloat(); val tm = spanMin * f; val q = tm + since
                samples.add(WxS(XN + W * f, tm, 0f, lerpS(S.feels, q), lerpS(S.mm, q) ?: 0f, lerpS(S.prob, q), lerpS(S.head, q), lerpS(S.gust, q), lerpS(S.ws, q)))
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
            val path = stepPath(samples, { it.feels }) { ty(it) }   // 0.4.27: schodki
            stp.color = Color.BLACK; stp.strokeWidth = F(11f); c.drawPath(path, stp)
            stp.color = Color.parseColor("#FF8A00"); stp.strokeWidth = F(6.5f); c.drawPath(path, stp)   // 16.10: grubsza, pomaranczowa
            for (v in tl) ylab(c, "${Math.round(v)}°", X(14f), ty(v), SUB)
            val minS = samples.filter { it.feels != null }.minByOrNull { it.feels!! }!!
            val idx = samples.indexOf(minS)
            val where = if (route) "km ${Math.round(minS.km)}" else "o ${clockAt(minS.tMin)}"
            text(c, "min ${Math.round(minS.feels!!)}° · $where", X1, yy(60f), F(22f), SUB, Paint.Align.RIGHT)
            fun lab(sm: WxS, al: Paint.Align) { val fv = sm.feels ?: return; val y = ty(fv); text(c, "${Math.round(fv)}°", sm.x + (if (al == Paint.Align.LEFT) F(48f) else if (al == Paint.Align.RIGHT) -F(48f) else 0f), if (y < TT + F(34f)) y + F(24f) else y - F(22f), F(26f), WHITE, al) }
            lab(samples.first(), Paint.Align.LEFT); lab(samples.last(), Paint.Align.RIGHT)
            if (idx in 9 until n - 8) {
                fp.color = Color.parseColor("#111315"); c.drawCircle(minS.x, ty(minS.feels!!), F(9f), fp)
                fp.color = Color.parseColor("#FACC15"); c.drawCircle(minS.x, ty(minS.feels!!), F(6.5f), fp)   // zolta = minimum
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
        for (bk in buckets(samples) { it.mm }) {
            val v = bk.v ?: continue
            if (v <= 0.05f) continue
            fp.color = Color.parseColor(if (radarUntil != null && bk.t0 <= radarUntil) "#7FB2FF" else "#2E7BFF")   // jasny = radar, ciemny = model
            c.drawRect(bk.x0 + F(2f), RB - v.coerceAtMost(rMax) / rMax * (RB - RT), bk.x1 - F(2f), RB, fp)
        }
        stp.color = Color.parseColor("#465366"); stp.strokeWidth = F(2f); c.drawLine(X0, RB, X1, RB, stp)
        val pPath = stepPath(samples, { it.prob }) { RB - it / 100f * (RB - RT) }
        stp.color = cyan; stp.strokeWidth = F(2.5f); c.drawPath(pPath, stp)
        ylab(c, fmt("%.0f", rMax), X(14f), RT, SUB); ylab(c, "0", X(14f), RB, SUB)
        ylab(c, "szansa 100%", X(466f), RT, cyan, true); ylab(c, "0", X(466f), RB, cyan, true)
        val fr = samples.firstOrNull { it.mm >= 0.3f }
        if (fr != null) text(c, "deszcz za ${hmm((fr.tMin).coerceAtLeast(0f))}" + (if (route) " · km ${Math.round(fr.km)}" else " · ${clockAt(fr.tMin)}"), X1, yy(198f), F(22f), cyan, Paint.Align.RIGHT)
        else text(c, "bez opadu", X1, yy(198f), F(22f), SUB, Paint.Align.RIGHT)
        }
        // ---------------- wiatr (+ w twarz / - w plecy)
        val dw = if (showRain) 0f else -66f
        text(c, "WIATR", X(16f), yy(306f + dw), F(24f), LABEL)
        text(c, "m/s", X(16f) + textW("WIATR", F(24f)) + F(6f), yy(308f + dw), F(20f), UNIT)
        val WT = yy(if (showRain) 334f else 282f); val Y0 = yy(if (showRain) 364f else 338f)
        // 0.4.10: bez trasy kierunek wzgledny nie ma sensu (kurs sie zmienia) -> sama sila wiatru w czasie
        val hs = if (route) samples.mapNotNull { it.head } else emptyList()
        val wsv = if (route) emptyList() else samples.mapNotNull { it.ws }
        val gmax = samples.filter { (it.head ?: 0f) > 0f }.mapNotNull { it.gust }.maxOrNull() ?: 0f
        // 16.10: bez porywow -- w jezdzie liczy sie staly wiatr; skala wg wiatru (min. 4 m/s, zeby slaby wiatr nie wygladal jak mocny)
        val wmax = if (route) Math.max(4.0, Math.ceil((hs.maxOfOrNull { kotlin.math.abs(it) } ?: 0f).toDouble())).toFloat()
                   else Math.max(4.0, Math.ceil((wsv.maxOrNull() ?: 0f).toDouble())).toFloat() / 2f   // cala wysokosc = 0..maks
        val ky = (Y0 - WT) / wmax
        stp.color = Color.parseColor("#2A3341"); stp.strokeWidth = F(1.5f)
        c.drawLine(X0, Y0 - wmax * ky, X1, Y0 - wmax * ky, stp); c.drawLine(X0, Y0 + wmax * ky, X1, Y0 + wmax * ky, stp)
        if (hs.isNotEmpty()) {
            fun area(pos: Boolean): Path {
                val p = Path(); p.moveTo(X0, Y0)
                for (sm in samples) { val v = sm.head ?: 0f; p.lineTo(sm.x, Y0 - (if (pos) Math.max(0f, v) else Math.min(0f, v)) * ky) }
                p.lineTo(X1, Y0); p.close(); return p
            }
            // 16.10: pod wiatr czerwony, w plecy zielony; gdy wiatr robi sie boczny (mala skladowa wzdluz jazdy):
            // czerwony przechodzi w rozowy, zielony w blado rozowy
            fun mix(cBase: Int, cSide: Int, k: Float): Int {
                fun ch(a: Int, b: Int) = (a + (b - a) * k).toInt().coerceIn(0, 255)
                return Color.rgb(ch(Color.red(cSide), Color.red(cBase)), ch(Color.green(cSide), Color.green(cBase)), ch(Color.blue(cSide), Color.blue(cBase)))
            }
            val red = Color.parseColor("#FF3B30"); val pinkSide = Color.parseColor("#F29BC4")
            val green = Color.parseColor("#34C759"); val palePink = Color.parseColor("#F3D3E2")
            for (bk in buckets(samples) { it.head }) {
                val v = bk.v ?: continue
                if (kotlin.math.abs(v) < 0.05f) continue
                val k2 = (kotlin.math.abs(v) / wmax).coerceIn(0f, 1f)
                fp.color = if (v > 0f) mix(red, pinkSide, k2) else mix(green, palePink, k2)
                c.drawRect(bk.x0 + F(1f), if (v > 0f) Y0 - v * ky else Y0, bk.x1 - F(1f), if (v > 0f) Y0 else Y0 - v * ky, fp)
            }
            val mx = hs.maxOrNull() ?: 0f
            text(c, listOfNotNull(if (mx > 0.5f) "maks. czołowy ${Math.round(mx)}" else null, samples.first().gust?.let { "porywy teraz ${Math.round(it)}" }).joinToString(" · "), X1, yy(308f + dw), F(22f), SUB, Paint.Align.RIGHT)
        }
        if (route) {
            stp.color = Color.parseColor("#C9D2DC"); stp.strokeWidth = F(1.5f); c.drawLine(X0, Y0, X1, Y0, stp)
            ylab(c, "+${wmax.toInt()}", X(14f), Y0 - wmax * ky, Color.parseColor("#F0A6CF")); ylab(c, "−${wmax.toInt()}", X(14f), Y0 + wmax * ky, Color.parseColor("#8FD18A"))
        } else if (wsv.isNotEmpty()) {
            val YB = Y0 + wmax * ky; val topV = 2f * wmax
            fp.color = Color.parseColor("#2E5F8F")
            for (bk in buckets(samples) { it.ws }) { val v = bk.v ?: continue; if (v > 0.05f) c.drawRect(bk.x0 + F(1f), YB - v * ky, bk.x1 - F(1f), YB, fp) }
            val ln = stepPath(samples, { it.ws }) { YB - it * ky }
            stp.color = Color.BLACK; stp.strokeWidth = F(8f); c.drawPath(ln, stp)
            stp.color = Color.parseColor("#9CC7FF"); stp.strokeWidth = F(4.5f); c.drawPath(ln, stp)
            stp.color = Color.parseColor("#C9D2DC"); stp.strokeWidth = F(1.5f); c.drawLine(X0, YB, X1, YB, stp)
            ylab(c, "${topV.toInt()}", X(14f), Y0 - wmax * ky, SUB); ylab(c, "0", X(14f), YB, SUB)
            val mxS = samples.filter { it.ws != null }.maxByOrNull { it.ws!! }
            text(c, "teraz ${Math.round(samples.first().ws ?: 0f)}" + (mxS?.let { " · maks. ${Math.round(it.ws!!)} o ${clockAt(it.tMin)}" } ?: "") +
                (samples.first().gust?.let { " · porywy ${Math.round(it)}" } ?: ""), X1, yy(308f + dw), F(22f), SUB, Paint.Align.RIGHT)
        }
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
        for (t in ticks.drop(1)) if (!t.end && t.x - XN >= F(150f) && !(endT != null && endT.x - t.x < F(150f))) keep.add(t)
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
            valUnit(c, t.top, t.u, xx, yy(416f), F(32f), F(22f), WHITE, al)
            text(c, t.bot, xx, yy(448f), F(22f), UNIT, al)
        }
        // TU I TERAZ: zielona kropka w kazdym wykresie
        fun nowDot(y: Float) { fp.color = Color.parseColor("#111315"); c.drawCircle(XN, y, F(9f), fp); fp.color = Color.parseColor("#39FF14"); c.drawCircle(XN, y, F(6.5f), fp) }
        samples.first().feels?.let { f0 -> if (fs.isNotEmpty()) { val lo2 = Math.floor(fs.minOrNull()!!.toDouble()).toFloat() - 1f; val hi2 = Math.ceil(fs.maxOrNull()!!.toDouble()).toFloat() + 1f; nowDot(TB - (f0 - lo2) / (hi2 - lo2) * (TB - TT)) } }
        if (showRain) nowDot(RB - samples.first().mm.coerceAtMost(Math.max(2.0, Math.ceil(samples.maxOf { it.mm }.toDouble())).toFloat()) / Math.max(2.0, Math.ceil(samples.maxOf { it.mm }.toDouble())).toFloat() * (RB - RT))
        nowDot(if (route) Y0 - (samples.first().head ?: 0f) * ky else Y0 + wmax * ky - (samples.first().ws ?: 0f) * ky)
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
