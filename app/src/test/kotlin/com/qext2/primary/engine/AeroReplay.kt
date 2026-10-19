package com.qext2.primary.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Odtworzenie prawdziwej jazdy (eksport QBota 1 Hz: moc, predkosc, wysokosc, kadencja, GPS, nawierzchnia QBota kat. 1-5)
 * tym samym kodem co na Karoo (AeroCore). Moc jak na Karoo: srednia 3 s. Wiatr: staly (prognoza z FIT) x cos(kat wzgledem kursu).
 */
object AeroReplay {
    data class Row(val t: Long, val p: Double?, val v: Double?, val dist: Double?, val alt: Double?, val cad: Int?, val temp: Double?,
                   val lat: Double?, val lon: Double?, val cat: Int?, val v1State: Int?)
    data class Step(val t: Long, val km: Double, val out: AeroCore.Out)
    data class Result(val n: Int, val state: IntArray, val conf: IntArray, val steps: List<Step>, val resets: Map<String, Int>,
                      val outs: List<Pair<Row, AeroCore.Out>>)

    fun load(name: String): List<Row> {
        val ins = AeroReplay::class.java.classLoader!!.getResourceAsStream("aero/$name")
            ?: java.io.File("app/src/test/resources/aero/$name").inputStream()
        val lines = java.util.zip.GZIPInputStream(ins).bufferedReader().readLines().filter { !it.startsWith("#") }
        val h = lines.first().split(","); fun ix(k: String) = h.indexOf(k)
        return lines.drop(1).map { l ->
            val c = l.split(",")
            fun d(k: String) = c.getOrNull(ix(k))?.takeIf { it.isNotEmpty() }?.toDouble()
            Row(c[0].toLong(), d("p"), d("v"), d("dist"), d("alt"), d("cad")?.toInt(), d("temp"), d("lat"), d("lon"), d("cat")?.toInt(),
                d("a_st")?.toInt())
        }
    }

    /** kategoria QBota -> klasa pola: 1 asfalt, 2-4 szuter, 5 / brak = nieznana */
    fun surf(cat: Int?): Int = when (cat) { 1 -> 1; 2, 3, 4 -> 2; else -> 0 }

    fun bearing(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2); val dl = Math.toRadians(lo2 - lo1)
        val b = Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)))
        return (b + 360.0) % 360.0
    }

    fun run(rows: List<Row>, params: AeroParams, windMs: Double?, windFrom: Double, stepRel: Double = AeroCore.STEP_REL, persist: Int = AeroCore.PERSIST): Result {
        val core = AeroCore(params, stepRel, persist)
        val p3 = ArrayDeque<Double>()
        val state = IntArray(3); val conf = IntArray(3)
        val steps = ArrayList<Step>(); val resets = HashMap<String, Int>()
        val outs = ArrayList<Pair<Row, AeroCore.Out>>()
        var course: Double? = null
        for (k in rows.indices) {
            val r = rows[k]
            p3.addLast(r.p ?: 0.0); while (p3.size > 3) p3.removeFirst()
            val back = rows.getOrNull(k - 5)
            if (back?.lat != null && r.lat != null && back.lon != null && r.lon != null && (r.dist ?: 0.0) - (back.dist ?: 0.0) > 10.0)
                course = bearing(back.lat, back.lon, r.lat, r.lon)
            val hw = if (windMs == null || course == null) null else windMs * cos(Math.toRadians(windFrom - course!!))
            val o = core.step(AeroCore.In(r.t * 1000L, p3.average(), r.v ?: 0.0, r.cad ?: 0, r.alt, null, hw, course, surf(r.cat),
                r.temp, r.v != null && r.p != null))
            state[o.state]++; if (o.state == 2) conf[o.conf]++
            o.event?.let { e -> if (e.startsWith("step")) steps.add(Step(r.t, (r.dist ?: 0.0) / 1000.0, o)) else resets.merge(e, 1, Int::plus) }
            outs.add(r to o)
        }
        return Result(rows.size, state, conf, steps, resets, outs)
    }
}
