package com.qext2.primary.statsv2

import android.util.Log
import com.qext2.primary.BuildConfig
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.gate.KarooSdkHttpCaller
import com.qext2.primary.util.RideFileLog
import org.json.JSONObject
import java.net.URLEncoder

/**
 * PLAN CZASU (16.10.2026): profil czasu przejazdu trasy z modelu QBota (GET /api/plan/by-name -> [km, ruch_min, postoje_min]).
 * Karoo porownuje z faktycznym czasem ruchu i postojow w biezacym km. Pobierane raz na trase (ponowienie po 2 min przy bledzie).
 */
object PlanProfile {
    private const val TAG = "QEXT_PLAN"
    @Volatile private var route: String? = null
    @Volatile private var items: List<FloatArray> = emptyList()
    @Volatile private var inflight = false
    @Volatile private var failMs = 0L

    fun tick(routeName: String?) {
        if (routeName == null) { route = null; items = emptyList(); return }
        if (routeName == route && items.isNotEmpty()) return
        if (inflight || (routeName == route && System.currentTimeMillis() - failMs < 120_000L)) return
        val system = QExt2PrimaryExtension.instance?.karooSystem ?: return
        route = routeName; items = emptyList(); inflight = true
        val url = BuildConfig.QBOT_BASE_URL.trim().trimEnd('/') + "/api/plan/by-name?name=" + URLEncoder.encode(routeName, "UTF-8")
        KarooSdkHttpCaller(system).callWithBody(url, mapOf("Authorization" to "Bearer ${BuildConfig.QBOT_BEARER}"), { code, body ->
            inflight = false
            val parsed = if (code == 200) parse(body) else emptyList()
            if (parsed.isNotEmpty() && route == routeName) { items = parsed; log("PLAN ok items=${parsed.size}") }
            else { failMs = System.currentTimeMillis(); log("PLAN_FAIL code=$code") }
        }, { msg -> inflight = false; failMs = System.currentTimeMillis(); log("PLAN_FAIL sdk=$msg") })
    }

    fun parse(body: String?): List<FloatArray> = try {
        val arr = JSONObject(body ?: "").getJSONArray("items")
        (0 until arr.length()).map { i -> val a = arr.getJSONArray(i); floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat()) }
    } catch (e: Exception) { emptyList() }

    /** (ruch_min, postoje_min) wg planu w danym km; null = brak profilu. */
    fun at(km: Float): Pair<Float, Float>? = interp(items, km)

    fun interp(its: List<FloatArray>, km: Float): Pair<Float, Float>? {
        if (its.isEmpty()) return null
        if (km <= its.first()[0]) return its.first()[1] * (km / its.first()[0].coerceAtLeast(0.01f)).coerceIn(0f, 1f) to its.first()[2] * (km / its.first()[0].coerceAtLeast(0.01f)).coerceIn(0f, 1f)
        if (km >= its.last()[0]) return its.last()[1] to its.last()[2]
        var i = 1
        while (i < its.size && its[i][0] < km) i++
        val a = its[i - 1]; val b = its[i]
        val f = if (b[0] == a[0]) 0f else (km - a[0]) / (b[0] - a[0])
        return (a[1] + (b[1] - a[1]) * f) to (a[2] + (b[2] - a[2]) * f)
    }

    private fun log(m: String) { Log.i(TAG, m); try { RideFileLog.append(m) } catch (_: Exception) {} }
}
