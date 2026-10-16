package com.qext2.primary.info

import android.util.Log
import com.qext2.primary.BuildConfig
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.gate.KarooSdkHttpCaller
import com.qext2.primary.util.RideFileLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Punkt pogody z QBota. route: km + t (min od chwili pobrania); bez trasy: tylko t. head: + = wiatr w twarz, - = w plecy (m/s). */
data class WxItem(val km: Float?, val t: Float, val feels: Float?, val mm: Float?, val prob: Float?, val head: Float?, val gust: Float?)

data class InfoWx(val route: Boolean, val items: List<WxItem>, val fetchedMs: Long)

/**
 * Pogoda dla pola INFO (16.10.2026). Liczy QBot:
 * - na trasie: GET /api/weather/by-name?name=..&km=.. (silnik route_meteo_engine jak Analiza trasy, czas przesuniety do biezacego km),
 * - bez trasy: GET /api/weather/point?lat=..&lon=..&heading=.. (rdzen pogody QBota, kolejne godziny).
 * Odswiezanie: brak danych, zmiana trasy, co 15 min albo gdy ETA przesunie sie o > 10 min. Po bledzie ponowienie po 2 min.
 */
object InfoWeather {
    private const val TAG = "QEXT_INFO_WX"
    @Volatile var scale = 1
    @Volatile var data: InfoWx? = null
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    @Volatile private var inflight = false
    private var lastKey: String? = null
    private var lastFetchMs = 0L
    private var lastFailMs = 0L
    private var lastEtaMs: Long? = null
    @Volatile private var unknownRoute: String? = null
    @Volatile private var unknownSinceMs = 0L

    fun cycleScale() {
        scale = (scale + 1) % 3
        _version.value = System.currentTimeMillis()
        log("INFO_SCALE $scale")
    }

    @Synchronized
    fun tick(routeNameIn: String?, km: Float, etaMs: Long?, lat: Double?, lon: Double?, heading: Double?) {
        if (inflight) return
        if (unknownRoute != null && System.currentTimeMillis() - unknownSinceMs > 15 * 60_000L) unknownRoute = null   // co 15 min znow proba trasy
        val routeName = routeNameIn?.takeIf { it != unknownRoute }   // trasa spoza QBota / blad trasy -> tryb bez trasy
        val now = System.currentTimeMillis()
        val key = routeName?.let { "R:$it" } ?: "P"
        val etaMoved = etaMs != null && lastEtaMs != null && kotlin.math.abs(etaMs - (lastEtaMs ?: etaMs)) > 10 * 60_000L
        val need = data == null || key != lastKey || now - lastFetchMs > 15 * 60_000L || etaMoved
        if (!need) return
        if (key == lastKey && now - lastFailMs < 120_000L) return
        val system = QExt2PrimaryExtension.instance?.karooSystem ?: return
        val base = BuildConfig.QBOT_BASE_URL.trim().trimEnd('/')
        val url = if (routeName != null) {
            "$base/api/weather/by-name?name=" + URLEncoder.encode(routeName, "UTF-8") + String.format(Locale.US, "&km=%.2f", km)
        } else {
            if (lat == null || lon == null) return
            String.format(Locale.US, "%s/api/weather/point?lat=%.4f&lon=%.4f&hours=7", base, lat, lon) +
                (heading?.let { String.format(Locale.US, "&heading=%.0f", it) } ?: "")
        }
        inflight = true
        log("INFO_WX fetch mode=${if (routeName != null) "trasa" else "punkt"} km=${"%.1f".format(Locale.US, km)}")
        KarooSdkHttpCaller(system).callWithBody(
            url = url,
            headers = mapOf("Authorization" to "Bearer ${BuildConfig.QBOT_BEARER}"),
            onResult = { code, body ->
                val parsed = if (code == 200) parse(body, routeName != null) else null
                synchronized(this) {
                    inflight = false
                    lastKey = key
                    if (parsed != null && parsed.items.isNotEmpty()) {
                        data = parsed
                        lastFetchMs = System.currentTimeMillis()
                        lastEtaMs = etaMs
                        _version.value = lastFetchMs
                        log("INFO_WX ok items=${parsed.items.size}")
                    } else {
                        lastFailMs = System.currentTimeMillis()
                        if (key.startsWith("R:")) { if (data?.route == true) data = null; unknownRoute = routeName; unknownSinceMs = System.currentTimeMillis(); lastFailMs = 0L; log("INFO_WX trasa code=$code -> pogoda bez trasy (ponowna proba trasy za 15 min)") }
                        log("INFO_WX_FAIL code=$code")
                    }
                }
            },
            onError = { msg ->
                synchronized(this) {
                    inflight = false
                    lastKey = key
                    lastFailMs = System.currentTimeMillis()
                }
                log("INFO_WX_FAIL sdk=$msg")
            },
        )
    }

    fun parse(body: String?, route: Boolean): InfoWx? {
        if (body.isNullOrBlank()) return null
        return try {
            val o = JSONObject(body)
            val arr = o.optJSONArray("items") ?: return null
            fun f(j: JSONObject, k: String): Float? = if (j.isNull(k) || !j.has(k)) null else j.optDouble(k, Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            val out = ArrayList<WxItem>(arr.length())
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val t = f(j, "t") ?: continue
                out.add(WxItem(if (route) f(j, "km") else null, t, f(j, "feels"), f(j, "mm"), f(j, "prob"), f(j, "head"), f(j, "gust")))
            }
            InfoWx(route, if (route) out.filter { it.km != null }.sortedBy { it.km } else out.sortedBy { it.t }, System.currentTimeMillis())
        } catch (e: Exception) {
            log("INFO_WX parse_error ${e.message}")
            null
        }
    }

    private fun log(m: String) {
        Log.i(TAG, m)
        try { RideFileLog.append(m) } catch (_: Exception) {}
    }
}
