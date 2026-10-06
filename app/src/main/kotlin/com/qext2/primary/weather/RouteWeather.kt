package com.qext2.primary.weather

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "QExt2RouteWx"

/** Geometria trasy z routePolyline Karoo (Google polyline, precyzja 5): pozycja po dystansie wzdluz trasy. */
class RouteLine(private val lat: DoubleArray, private val lon: DoubleArray, private val cum: DoubleArray) {
    val lengthM: Double get() = if (cum.isEmpty()) 0.0 else cum.last()

    fun at(d: Double): Pair<Double, Double> {
        if (cum.size < 2) return lat.firstOrNull()?.let { it to lon[0] } ?: (0.0 to 0.0)
        val x = d.coerceIn(0.0, lengthM)
        var lo = 0; var hi = cum.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= x) lo = m else hi = m }
        val seg = (cum[hi] - cum[lo]).takeIf { it > 0.0 } ?: return lat[lo] to lon[lo]
        val f = (x - cum[lo]) / seg
        return (lat[lo] + (lat[hi] - lat[lo]) * f) to (lon[lo] + (lon[hi] - lon[lo]) * f)
    }

    companion object {
        fun decode5(enc: String): List<Pair<Double, Double>> {
            val out = ArrayList<Pair<Double, Double>>()
            var i = 0; var la = 0L; var lo = 0L
            fun next(): Long? {
                var res = 0L; var shift = 0
                while (true) {
                    if (i >= enc.length) return null
                    val b = enc[i++].code - 63
                    res = res or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                return if (res and 1L != 0L) (res shr 1).inv() else res shr 1
            }
            while (i < enc.length) {
                val dla = next() ?: break; val dlo = next() ?: break
                la += dla; lo += dlo
                out.add(la / 1e5 to lo / 1e5)
            }
            return out
        }

        fun haversine(a: Double, b: Double, c: Double, d: Double): Double {
            val r = 6371000.0
            val p1 = Math.toRadians(a); val p2 = Math.toRadians(c)
            val dp = p2 - p1; val dl = Math.toRadians(d - b)
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * atan2(sqrt(h), sqrt(1 - h))
        }

        fun build(enc: String?, reversed: Boolean): RouteLine? {
            if (enc.isNullOrBlank()) return null
            var pts = decode5(enc)
            if (pts.size < 2) return null
            if (reversed) pts = pts.reversed()
            val la = DoubleArray(pts.size) { pts[it].first }
            val lo = DoubleArray(pts.size) { pts[it].second }
            val cum = DoubleArray(pts.size)
            for (k in 1 until pts.size) cum[k] = cum[k - 1] + haversine(la[k - 1], lo[k - 1], la[k], lo[k])
            return RouteLine(la, lo, cum)
        }
    }
}

/** Zjawiska od najmniej do najbardziej istotnego. */
enum class WxKind(val severity: Int) { CLEAR(0), PARTLY(0), OVERCAST(0), FOG(1), DRIZZLE(2), RAIN(3), SNOW(4), STORM(5) }

data class WxPoint(val lat: Double, val lon: Double, val minutes: Int, val kmAhead: Float)

/** Najgorsze zjawisko na trasie w horyzoncie ~2 h: kiedy (min), gdzie (km przed toba), szansa, natezenie. */
data class WxEvent(val kind: WxKind, val minutes: Int, val probPct: Int, val mmPerH: Float, val kmAhead: Float)

data class RouteWx(
    val tempC: Float?,
    val windMps: Float?,
    val windDirDeg: Int?,
    val humidityPct: Int?,
    val rainNowMmH: Float?,
    val nowKind: WxKind,
    val sky: WxKind,
    val event: WxEvent?,
    val points: Int,
    val fetchedAt: Long,
)

object RouteWeatherClient {
    private const val FRESH_MAX_MS = 35 * 60_000L

    fun isFresh(w: RouteWx?): Boolean = w != null && System.currentTimeMillis() - w.fetchedAt in 0..FRESH_MAX_MS

    fun classify(code: Int, precipMm15: Double, rainMm15: Double, snowCm15: Double): WxKind? = when {
        code in 95..99 -> WxKind.STORM
        snowCm15 > 0.0 || code in 71..77 || code in 85..86 -> WxKind.SNOW
        rainMm15 >= 0.1 || precipMm15 >= 0.1 || code in 61..67 || code in 80..82 -> WxKind.RAIN
        code in 51..57 -> WxKind.DRIZZLE
        code == 45 || code == 48 -> WxKind.FOG
        else -> null
    }

    /** Niebo tylko z kodu pogody WMO (0-1 bezchmurnie, 2 czesciowo, 3 pochmurno, 45/48 mgla). */
    fun sky(code: Int, @Suppress("UNUSED_PARAMETER") cloudPct: Int): WxKind = when (code) {
        0, 1 -> WxKind.CLEAR
        2 -> WxKind.PARTLY
        45, 48 -> WxKind.FOG
        else -> WxKind.OVERCAST
    }

    private fun url(pts: List<WxPoint>): String {
        val la = pts.joinToString(",") { String.format(java.util.Locale.US, "%.4f", it.lat) }
        val lo = pts.joinToString(",") { String.format(java.util.Locale.US, "%.4f", it.lon) }
        return "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
            "&current=temperature_2m,relative_humidity_2m,precipitation,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m" +
            "&minutely_15=precipitation,rain,snowfall,weather_code&forecast_minutely_15=10" +
            "&hourly=precipitation_probability,weather_code&forecast_hours=3" +
            "&wind_speed_unit=ms&timeformat=unixtime&timezone=UTC"
    }

    suspend fun fetch(system: KarooSystemService, pts: List<WxPoint>): RouteWx? {
        if (pts.isEmpty()) return null
        val u = url(pts)
        Log.i(TAG, "QEXT_ROUTE_WX_FETCH points=${pts.size}")
        return withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                val idRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val settled = java.util.concurrent.atomic.AtomicBoolean(false)
                fun cleanup() { idRef.getAndSet(null)?.let { system.removeConsumer(it) } }
                val id = system.addConsumer<OnHttpResponse>(
                    params = OnHttpResponse.MakeHttpRequest(method = "GET", url = u, waitForConnection = true),
                    onError = { msg ->
                        Log.w(TAG, "QEXT_ROUTE_WX_FAILED reason=onError msg=$msg")
                        if (settled.compareAndSet(false, true)) { cleanup(); cont.resume(null) }
                    },
                    onEvent = { resp ->
                        val s = resp.state
                        if (s is HttpResponseState.Complete) {
                            if (settled.compareAndSet(false, true)) {
                                cleanup()
                                val body = s.body
                                val r = if (s.statusCode == 200 && body != null) try {
                                    parse(String(body), pts, System.currentTimeMillis())
                                } catch (e: Exception) { Log.w(TAG, "QEXT_ROUTE_WX_FAILED reason=parse msg=${e.message}"); null }
                                else { Log.w(TAG, "QEXT_ROUTE_WX_FAILED status=${s.statusCode}"); null }
                                cont.resume(r)
                            }
                        }
                    }
                )
                idRef.set(id)
                if (settled.get()) cleanup()
                cont.invokeOnCancellation { cleanup() }
            }
        }
    }

    /** Odpowiedz Open-Meteo: obiekt (1 punkt) albo tablica obiektow (wiele punktow, w kolejnosci zapytania). */
    fun parse(json: String, pts: List<WxPoint>, nowMs: Long): RouteWx {
        val t = json.trim()
        val objs: List<JSONObject> = if (t.startsWith("[")) JSONArray(t).let { a -> List(a.length()) { a.getJSONObject(it) } } else listOf(JSONObject(t))
        val nowSec = nowMs / 1000L
        val first = objs.first()
        val cur = first.optJSONObject("current")
        val curCode = cur?.optInt("weather_code", 0) ?: 0
        val curCloud = cur?.optInt("cloud_cover", 0) ?: 0
        val curPrecip = cur?.optDouble("precipitation", 0.0) ?: 0.0
        var event: WxEvent? = null
        for ((k, o) in objs.withIndex()) {
            val p = pts.getOrNull(k) ?: break
            val m = o.optJSONObject("minutely_15") ?: continue
            val tm = m.optJSONArray("time") ?: continue
            val target = nowSec + p.minutes * 60L
            var bi = -1; var bd = Long.MAX_VALUE
            for (i in 0 until tm.length()) { val dd = kotlin.math.abs(tm.getLong(i) - target); if (dd < bd) { bd = dd; bi = i } }
            if (bi < 0) continue
            val h = o.optJSONObject("hourly")
            fun hourly(name: String): Int {
                val ht = h?.optJSONArray("time"); val hv = h?.optJSONArray(name)
                if (ht == null || hv == null) return 0
                var v = 0
                for (i in 0 until ht.length()) if (ht.getLong(i) <= tm.getLong(bi)) v = hv.optInt(i, 0)
                return v
            }
            val code = m.optJSONArray("weather_code")?.optInt(bi, -1)?.takeIf { it >= 0 } ?: hourly("weather_code")
            val pr = m.optJSONArray("precipitation")?.optDouble(bi, 0.0) ?: 0.0
            val rn = m.optJSONArray("rain")?.optDouble(bi, 0.0) ?: 0.0
            val sn = m.optJSONArray("snowfall")?.optDouble(bi, 0.0) ?: 0.0
            val kind = classify(code, pr, rn, sn) ?: continue
            val prob = maxOf(hourly("precipitation_probability"), if (pr >= 0.1) 50 else 0)
            val ev = WxEvent(kind, p.minutes, prob, (pr * 4.0).toFloat(), p.kmAhead)
            val e0 = event
            if (e0 == null || ev.kind.severity > e0.kind.severity) event = ev
        }
        val r = RouteWx(
            tempC = cur?.optDouble("temperature_2m", Double.NaN)?.takeIf { !it.isNaN() }?.toFloat(),
            windMps = cur?.optDouble("wind_speed_10m", Double.NaN)?.takeIf { !it.isNaN() }?.toFloat(),
            windDirDeg = cur?.optInt("wind_direction_10m", -1)?.takeIf { it >= 0 },
            humidityPct = cur?.optInt("relative_humidity_2m", -1)?.takeIf { it >= 0 },
            rainNowMmH = (curPrecip * 4.0).toFloat().takeIf { it >= 0.1f },
            nowKind = classify(curCode, curPrecip, 0.0, 0.0) ?: sky(curCode, curCloud),
            sky = sky(curCode, curCloud),
            event = event,
            points = objs.size,
            fetchedAt = nowMs,
        )
        Log.i(TAG, "QEXT_ROUTE_WX points=${objs.size} sky=${r.sky} now=${r.nowKind} event=${event?.kind}@${event?.minutes}min/${event?.kmAhead}km p=${event?.probPct}")
        return r
    }
}
