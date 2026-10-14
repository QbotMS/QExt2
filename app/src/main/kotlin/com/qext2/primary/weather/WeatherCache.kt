package com.qext2.primary.weather

import android.util.Log
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.kokpit.RainSoon
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

private const val TAG = "QExt2WxCache"

/**
 * ZAPAS PROGNOZY na brak sieci (decyzja Michala 2026-10-14).
 * Pobierany przy starcie jazdy i co godzine, gdy jest siec: prognoza godzinowa na 12 h (Open-Meteo) dla punktow
 * co 10 km trasy (max 40), bez trasy - dla biezacego miejsca. Zapisany w pamieci Karoo (przetrwa restart).
 * Uzycie, gdy swieza pogoda (RouteWeather) jest nieaktualna: wiatr (strzalka, wiatr w twarz, porywy, pacing)
 * i opad (pole OPAD + komunikat) z najblizszego punktu i biezacej godziny; na ekranie znacznik "P".
 */
data class CachedWx(val windMps: Float, val windFromDeg: Int, val gustMps: Float?, val tempC: Float?, val ageMin: Int)

class WxCacheData(
    val fetchedAt: Long,
    val lat: DoubleArray, val lon: DoubleArray,
    val times: LongArray,                      // s, UTC (godzina pelna)
    val ws: Array<FloatArray>, val wd: Array<IntArray>, val wg: Array<FloatArray>,
    val pr: Array<FloatArray>, val pp: Array<IntArray>, val tc: Array<FloatArray>, val wc: Array<IntArray>,
)

object WeatherCache {
    const val MAX_AGE_MS = 12 * 3_600_000L
    const val REFRESH_MS = 60 * 60_000L
    const val STEP_M = 10_000.0
    const val MAX_POINTS = 40

    @Volatile private var data: WxCacheData? = null
    @Volatile private var loaded = false

    fun current(): WxCacheData? { ensureLoaded(); return data }

    /** punkty: co 10 km trasy (max 40), bez trasy - biezace miejsce */
    fun points(line: RouteLine?, lat: Double, lon: Double): List<Pair<Double, Double>> {
        if (line == null || line.lengthM < 1000.0) return listOf(lat to lon)
        val n = minOf(MAX_POINTS, (line.lengthM / STEP_M).toInt() + 2)
        val step = line.lengthM / (n - 1)
        return List(n) { line.at(it * step) }
    }

    private fun url(pts: List<Pair<Double, Double>>): String {
        val la = pts.joinToString(",") { String.format(java.util.Locale.US, "%.3f", it.first) }
        val lo = pts.joinToString(",") { String.format(java.util.Locale.US, "%.3f", it.second) }
        return "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
            "&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,precipitation_probability,temperature_2m,weather_code" +
            "&forecast_hours=13&past_hours=1&wind_speed_unit=ms&timeformat=unixtime&timezone=UTC"
    }

    /** pobranie + zapis; true = ok */
    suspend fun fetch(system: KarooSystemService, pts: List<Pair<Double, Double>>): Boolean {
        if (pts.isEmpty()) return false
        val u = url(pts)
        val body: String? = withTimeoutOrNull(25_000L) {
            suspendCancellableCoroutine { cont ->
                val idRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val settled = java.util.concurrent.atomic.AtomicBoolean(false)
                fun cleanup() { idRef.getAndSet(null)?.let { system.removeConsumer(it) } }
                val id = system.addConsumer<OnHttpResponse>(
                    params = OnHttpResponse.MakeHttpRequest(method = "GET", url = u, waitForConnection = false),
                    onError = { _ -> if (settled.compareAndSet(false, true)) { cleanup(); cont.resume(null) } },
                    onEvent = { resp ->
                        val s = resp.state
                        if (s is HttpResponseState.Complete && settled.compareAndSet(false, true)) {
                            cleanup()
                            cont.resume(if (s.statusCode == 200) s.body?.let { String(it) } else null)
                        }
                    },
                )
                idRef.set(id)
                if (settled.get()) cleanup()
                cont.invokeOnCancellation { cleanup() }
            }
        }
        if (body == null) return false
        val now = System.currentTimeMillis()
        val d = try { parse(body, pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray(), now) } catch (e: Exception) {
            Log.w(TAG, "QEXT_WX_CACHE_PARSE_FAIL ${e.message}"); null
        } ?: return false
        data = d; loaded = true
        try {
            AthleteDataStore.saveWxCache(JSONObject().put("fetchedAt", now)
                .put("lat", JSONArray(pts.map { it.first })).put("lon", JSONArray(pts.map { it.second })).put("body", body).toString())
        } catch (_: Exception) {}
        return true
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val s = AthleteDataStore.loadWxCache() ?: return
            val o = JSONObject(s)
            val la = o.getJSONArray("lat"); val lo = o.getJSONArray("lon")
            data = parse(o.getString("body"), DoubleArray(la.length()) { la.getDouble(it) }, DoubleArray(lo.length()) { lo.getDouble(it) }, o.getLong("fetchedAt"))
        } catch (_: Exception) { data = null }
    }

    /** Czysta funkcja (testy): odpowiedz Open-Meteo (obiekt albo tablica obiektow) -> zapas. */
    fun parse(body: String, lat: DoubleArray, lon: DoubleArray, fetchedAt: Long): WxCacheData {
        val t = body.trim()
        val objs = if (t.startsWith("[")) JSONArray(t).let { a -> List(a.length()) { a.getJSONObject(it) } } else listOf(JSONObject(t))
        val h0 = objs.first().getJSONObject("hourly")
        val ta = h0.getJSONArray("time")
        val times = LongArray(ta.length()) { ta.getLong(it) }
        val n = minOf(objs.size, lat.size)
        fun f(name: String, def: Float) = Array(n) { k -> val a = objs[k].getJSONObject("hourly").optJSONArray(name)
            FloatArray(times.size) { i -> a?.optDouble(i, Double.NaN)?.takeIf { !it.isNaN() }?.toFloat() ?: def } }
        fun i(name: String, def: Int) = Array(n) { k -> val a = objs[k].getJSONObject("hourly").optJSONArray(name)
            IntArray(times.size) { j -> a?.optInt(j, def) ?: def } }
        return WxCacheData(fetchedAt, lat.copyOf(n), lon.copyOf(n), times,
            f("wind_speed_10m", Float.NaN), i("wind_direction_10m", -1), f("wind_gusts_10m", Float.NaN),
            f("precipitation", 0f), i("precipitation_probability", 0), f("temperature_2m", Float.NaN), i("weather_code", 0))
    }

    private fun nearest(d: WxCacheData, lat: Double, lon: Double): Int {
        var bi = 0; var bd = Double.MAX_VALUE
        for (k in d.lat.indices) { val dd = RouteLine.haversine(lat, lon, d.lat[k], d.lon[k]); if (dd < bd) { bd = dd; bi = k } }
        return bi
    }

    /** Czysta funkcja: wiatr z zapasu dla miejsca i chwili (najblizsza godzina); null = brak / za stary. */
    fun lookup(d: WxCacheData?, lat: Double, lon: Double, nowMs: Long): CachedWx? {
        if (d == null || nowMs - d.fetchedAt > MAX_AGE_MS || d.times.isEmpty()) return null
        val k = nearest(d, lat, lon)
        val nowS = nowMs / 1000L
        var hi = 0; var hd = Long.MAX_VALUE
        for (j in d.times.indices) { val dd = kotlin.math.abs(d.times[j] - nowS); if (dd < hd) { hd = dd; hi = j } }
        if (hd > 5_400L) return null
        val ws = d.ws[k][hi]; val wd = d.wd[k][hi]
        if (ws.isNaN() || wd < 0) return null
        return CachedWx(ws, wd, d.wg[k][hi].takeIf { !it.isNaN() }, d.tc[k][hi].takeIf { !it.isNaN() }, ((nowMs - d.fetchedAt) / 60_000L).toInt())
    }

    /** Czysta funkcja: pierwszy opad w ciagu 2 h z zapasu (godzina T opisuje opad T-1h..T). */
    fun rainSoon(d: WxCacheData?, lat: Double, lon: Double, nowMs: Long): RainSoon? {
        if (d == null || nowMs - d.fetchedAt > MAX_AGE_MS) return null
        val k = nearest(d, lat, lon)
        val nowS = nowMs / 1000L
        for (j in d.times.indices) {
            val t = d.times[j]
            if (t <= nowS) continue
            val startMin = ((t - 3600L - nowS) / 60L).toInt().coerceAtLeast(0)
            if (startMin > 120) break
            val mm = d.pr[k][j]; val p = d.pp[k][j]; val code = d.wc[k][j]
            if (mm >= 0.2f || p >= 50 || code in 61..67 || code in 80..82 || code in 95..99) {
                val kind = when { code in 95..99 -> "STORM"; code in 71..77 || code in 85..86 -> "SNOW"; else -> "RAIN" }
                return RainSoon(startMin, p, mm, kind, 0f, cached = true)
            }
        }
        return null
    }

    fun lookupHere(nowMs: Long): CachedWx? {
        val lat = AthleteDataStore.loadLocationLat() ?: return null
        val lon = AthleteDataStore.loadLocationLon() ?: return null
        return lookup(current(), lat, lon, nowMs)
    }

    fun rainSoonHere(nowMs: Long): RainSoon? {
        val lat = AthleteDataStore.loadLocationLat() ?: return null
        val lon = AthleteDataStore.loadLocationLon() ?: return null
        return rainSoon(current(), lat, lon, nowMs)
    }
}
