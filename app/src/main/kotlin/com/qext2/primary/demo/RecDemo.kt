package com.qext2.primary.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.gate.GateController
import com.qext2.primary.info.InfoDemo
import com.qext2.primary.info.InfoWx
import com.qext2.primary.kokpit.DynItem
import com.qext2.primary.kokpit.Kokpit2Route
import com.qext2.primary.kokpit.KokpitInstData
import com.qext2.primary.kokpit.KokpitNavData
import com.qext2.primary.kokpit.MsgKind
import com.qext2.primary.kokpit.RainSoon
import com.qext2.primary.kokpit.RouteBar
import com.qext2.primary.kokpit.RouteMessageEngine
import com.qext2.primary.kokpit.RouteMsg
import com.qext2.primary.kokpit.SurfClass
import com.qext2.primary.statsv2.StatsV2Data
import com.qext2.primary.util.RideFileLog
import org.json.JSONObject

/**
 * Tryb NAGRANIE: odtwarzacz scenariusza z assets/demo_nagranie.json (60 klatek, petla).
 * Plik generuje QBot (scripts/qext2_demo_scenario.py) -- ten sam plik gra symulator QExt2 w Garazu.
 * Wszystko zmyslone: brak wspolrzednych, nazw miejsc i progow Michala. Pogoda INFO z InfoDemo (tez zmyslona).
 * Z danych zbudowane sa klasy pol 1:1 jak z jazdy (KokpitNavData, KokpitInstData, StatsV2Data), demo = false
 * (bez napisu DEMO w kadrze); stan trybu pokazuje tylko mala kropka (mark), ktora mozna ukryc w SETUP.
 */
object RecDemo {
    private class Seg(val from: Float, val to: Float, val surf: SurfClass)

    @Volatile private var frames: List<JSONObject>? = null
    @Volatile private var st: JSONObject? = null
    @Volatile private var segs: List<Seg> = emptyList()

    fun init(ctx: Context) {
        RecMode.init(ctx)
        if (frames != null) return
        try {
            val txt = ctx.assets.open("demo_nagranie.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(txt)
            val s = o.getJSONObject("static")
            val a = o.getJSONArray("frames")
            val sg = s.getJSONArray("segments")
            segs = (0 until sg.length()).map { i ->
                val x = sg.getJSONObject(i)
                Seg(x.getDouble("from").toFloat(), x.getDouble("to").toFloat(), SurfClass.valueOf(x.getString("surface")))
            }
            st = s
            frames = (0 until a.length()).map { a.getJSONObject(it) }
            RideFileLog.append("REC_DEMO scenariusz ${a.length()} klatek")
        } catch (e: Exception) {
            RideFileLog.append("REC_DEMO_FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Tryb NAGRANIE wlaczony i scenariusz wczytany. */
    fun active(ctx: Context): Boolean { init(ctx); return RecMode.on() && frames != null }

    private fun frame(now: Long): JSONObject? = frames?.let { it[RecPolicy.frameIndex(now, it.size)] }

    private fun f(o: JSONObject, k: String) = o.getDouble(k).toFloat()

    private fun total() = st?.optDouble("totalKm", 84.0)?.toFloat() ?: 84f

    private fun etaMs(now: Long, km: Float): Long {
        val avg = st?.optDouble("avgKmh", 24.0) ?: 24.0
        return now + ((total() - km) / avg * 3_600_000.0).toLong()
    }

    /** Pozostala trasa wg nawierzchni: (km, klasa) po kolei od biezacego km. */
    private fun ahead(km: Float): List<Pair<Float, SurfClass>> =
        segs.filter { it.to > km }.map { (it.to - maxOf(it.from, km)) to it.surf }

    private fun argb(s: SurfClass) = when (s) { SurfClass.PAVED -> Kokpit2Route.PAVED; SurfClass.GRAVEL -> Kokpit2Route.GRAVEL; SurfClass.LOOSE -> Kokpit2Route.LOOSE }

    private fun stops(km: Float): List<Float> {
        val a = st?.optJSONArray("stopsKm") ?: return emptyList()
        return (0 until a.length()).map { a.getDouble(it).toFloat() }.filter { it < km }
    }

    private fun msg(o: JSONObject): RouteMsg {
        val m = o.getJSONObject("msg")
        val kind = try { MsgKind.valueOf(m.getString("kind")) } catch (_: Exception) { MsgKind.NONE }
        return RouteMsg(kind, m.getString("lead"), m.getString("accent"), m.getString("color"))
    }

    // zmyslone manewry na trasie scenariusza (km), zeby strzalka nawigacji tez zyla
    private val turns = listOf(42.45f to "SLIGHT_LEFT", 43.70f to "RIGHT", 44.60f to "SLIGHT_RIGHT", 45.90f to "LEFT", 46.60f to "RB_R")

    fun nav(now: Long): KokpitNavData? {
        val o = frame(now) ?: return null
        val s = st ?: return null
        val km = f(o, "km")
        val tot = total()
        val d = o.getJSONObject("dyn")
        val turn = turns.firstOrNull { it.first > km }
        return KokpitNavData(
            msg = msg(o), doneKm = km, totalKm = tot, leftKm = tot - km,
            duskMs = now + 2 * 3_600_000L + 40 * 60_000L, twilightLabel = "zmrok", etaMs = etaMs(now, km),
            ahead = ahead(km).map { it.first to RouteMessageEngine.surfColor(it.second) },
            stopsKm = stops(km), gradePct = f(o, "grade"), ascDone = o.getInt("ascDone"), ascLeft = o.getInt("ascLeft"),
            tempC = s.optDouble("tempC", 14.0).toFloat(), rainNowMmH = null,
            rainSoon = RainSoon(35, 60, 1.2f, "RAIN", 14f), sky = s.optString("sky", "PARTLY"),
            windMps = s.optDouble("windMps", 4.5).toFloat(), windRelDeg = o.getInt("windRelDeg"),
            windSignedMps = f(o, "windSignedMps"), windTotalMps = s.optDouble("windMps", 4.5).toFloat(),
            turnDistM = turn?.let { ((it.first - km) * 1000f).toDouble() }, turnKind = turn?.second,
            dyn = DynItem(d.getString("kind"), d.getString("value"), d.getString("unit")),
            demo = false,
        )
    }

    fun inst(now: Long): KokpitInstData? {
        val o = frame(now) ?: return null
        val s = st ?: return null
        val km = f(o, "km")
        val cp = s.optDouble("cpW", 250.0).toFloat()
        val ceil = s.optInt("ceilingW", 225)
        val p = o.getInt("power")
        val np5 = o.getInt("np5")
        val v = f(o, "speedKmh")
        val avg = s.optDouble("avgKmh", 24.0).toFloat()
        val hr = o.getInt("hr")
        val lthr = s.optInt("lthr", 165).toFloat()
        val r = hr / lthr
        val alarm = o.optBoolean("alarm", false)
        val cogsA = s.getJSONArray("cogs")
        val cogs = (0 until cogsA.length()).map { cogsA.getInt(it) }
        val pc = when {
            alarm -> Color.BLACK
            p >= ceil -> Color.parseColor("#FF5252")
            p >= ceil * 0.85f -> Color.parseColor("#4ADE80")
            else -> Color.WHITE
        }
        return KokpitInstData(
            powerW = p, cpW = cp, cpe5W = np5.toFloat(), if5 = np5 / cp, wbalTrend = o.optString("wbalTrend", "stable"),
            powerColor = pc, powerBg = if (alarm) Color.parseColor("#FF5252") else 0,
            speedKmh = v, avgSpeedKmh = avg,
            hr = hr, hrAvg = s.optInt("hrAvg", 141), hrZone = when { r < 0.81f -> 1; r < 0.90f -> 2; r < 0.95f -> 3; r < 1.06f -> 4; else -> 5 },
            wbalPct = o.getInt("wbalPct"), cadence = o.getInt("cadence").takeIf { it > 0 }, cadenceAvg = s.optInt("cadAvg", 84),
            optCadLow = s.optInt("optCadLow", 80), optCadHigh = s.optInt("optCadHigh", 95),
            gearFront = s.optInt("gearFront", 40), gearRear = o.getInt("rearCog"), cogs = cogs, recCog = null,
            hrShowZone = AthleteDataStore.loadHrZoneMode(), powerCeilingW = ceil,
            hrDriftLevel = 0,
            cpTrend = when { p > np5 + 5 -> 1; p < np5 - 5 -> -1; else -> 0 },
            avgSpeedTrend = when { v > avg + 0.5f -> 1; v < avg - 0.5f -> -1; else -> 0 },
            route = RouteBar(km, total(), ahead(km).map { it.first to argb(it.second) }, stops(km)),
            demo = false,
        )
    }

    fun stats(now: Long): StatsV2Data? {
        val o = frame(now) ?: return null
        val s = st ?: return null
        val km = f(o, "km")
        val tot = total()
        val cp = s.optDouble("cpW", 250.0).toFloat()
        val np = o.getInt("np")
        val ifv = np / cp
        val zone = when { ifv < 0.55f -> 1; ifv < 0.75f -> 2; ifv < 0.90f -> 3; ifv < 1.05f -> 4; ifv < 1.20f -> 5; else -> 6 }
        val t = o.getInt("t")
        val moving = s.optLong("movingSecAtStart", 6120L) + t
        val stopsSec = s.optLong("stopsSec", 840L)
        val ah = ahead(km)
        fun rem(c: SurfClass) = ah.filter { it.second == c }.sumOf { it.first.toDouble() }.toFloat()
        val wnp = s.getJSONArray("winNp"); val wef = s.getJSONArray("winEf")
        val tp = s.getJSONArray("tempo")
        val eta = etaMs(now, km)
        return StatsV2Data(
            np = np, npZone = zone, ifv = ifv, vi = 1.06f, rsrv = o.getInt("rsrv"), xss = f(o, "xss"), kcal = o.getInt("kcal"),
            hasRoute = true, doneKm = km, totalKm = tot, etaMs = eta,
            avgGrossKmh = km / ((moving + stopsSec) / 3600f), movingSec = moving, stopsSec = stopsSec,
            surfPaved = rem(SurfClass.PAVED), surfGravel = rem(SurfClass.GRAVEL), surfLoose = rem(SurfClass.LOOSE),
            ascDone = o.getInt("ascDone"), ascLeft = o.getInt("ascLeft"),
            carbRate = s.optInt("carbRate", 70), carbSpent = (o.getInt("kcal") * 0.55f / 4f).toInt(),
            fluidRate = s.optDouble("fluidRate", 0.6).toFloat(), cadAvg = s.optInt("cadAvg", 84),
            batDrain = s.optDouble("batDrain", 6.5).toFloat(), batLeftSec = 6L * 3600L + 20L * 60L,
            twilightMs = now + 2 * 3_600_000L + 40 * 60_000L, twilightDawn = false,
            winNp = (0 until wnp.length()).map { wnp.getInt(it) } + o.getInt("np5"),
            winEf = (0 until wef.length()).map { wef.getDouble(it).toFloat() as Float? } + 1.40f,
            winPartial = true, typEf = s.optDouble("typEf", 1.42).toFloat(), cpW = cp,
            ahead = ah.map { it.first to argb(it.second) }, stopsKm = stops(km),
            demo = false,
            xssPlan = s.optDouble("xssPlan", 160.0).toFloat(), planDeltaMin = -6, planRefEtaMs = eta + 6 * 60_000L,
            tempo = (0 until tp.length()).map { tp.getJSONArray(it).let { x -> x.getInt(0) to x.getDouble(1).toFloat() } },
            tempoPartial = true,
            gate = GateController.Ui(GateController.State.FAR, "BRAMA", "za daleko · 38,2 km"),
            lightState = "OK", planDriveDelta = -4, planStopDelta = -2, planFromQbot = true,
            np5Now = o.getInt("np5"),
        )
    }

    /** Pogoda INFO: zmyslona (InfoDemo), od biezacego km scenariusza do mety. */
    fun infoWx(now: Long, d: StatsV2Data): InfoWx = InfoDemo.wx(now, d.doneKm, d.totalKm ?: (d.doneKm + 40f))

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E5484D"); alpha = 210 }

    /** Kropka trybu NAGRANIE w prawym gornym rogu pola. Poza trybem (albo kropka ukryta) obrazek bez zmian. */
    fun mark(bmp: Bitmap): Bitmap {
        if (!RecMode.on() || !RecMode.dot()) return bmp
        return try {
            val b = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
            val r = maxOf(3f, b.width / 120f)
            Canvas(b).drawCircle(b.width - r * 2f, r * 2f, r, dotPaint)
            b
        } catch (_: Exception) { bmp }
    }
}
