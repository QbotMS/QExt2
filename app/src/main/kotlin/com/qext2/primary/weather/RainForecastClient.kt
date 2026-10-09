package com.qext2.primary.weather

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

private const val TAG = "QExt2Rain"

/** Prognoza opadu na najblizsze 2 h (Open-Meteo, bez klucza). minutes = za ile zacznie padac (0 = juz). */
data class RainForecast(
    val minutes: Int?,
    val probPct: Int,
    val mmPerH: Float,
    val fetchedAt: Long,
)

object RainForecastClient {
    private const val FRESH_MAX_MS = 30 * 60_000L

    fun isFresh(f: RainForecast?): Boolean = f != null && System.currentTimeMillis() - f.fetchedAt in 0..FRESH_MAX_MS

    suspend fun fetch(system: KarooSystemService, lat: Double, lon: Double): RainForecast? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&minutely_15=precipitation&forecast_minutely_15=8" +
            "&hourly=precipitation_probability&forecast_hours=3&timeformat=unixtime&timezone=UTC"
        Log.i(TAG, "QEXT_RAIN_FETCH_START")
        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { cont ->
                val idRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val settled = java.util.concurrent.atomic.AtomicBoolean(false)
                fun cleanup() { idRef.getAndSet(null)?.let { system.removeConsumer(it) } }
                val id = system.addConsumer<OnHttpResponse>(
                    params = OnHttpResponse.MakeHttpRequest(method = "GET", url = url, waitForConnection = true),
                    onError = { msg ->
                        Log.w(TAG, "QEXT_RAIN_FETCH_FAILED reason=onError msg=$msg")
                        if (settled.compareAndSet(false, true)) { cleanup(); cont.resume(null) }
                    },
                    onEvent = { resp ->
                        val s = resp.state
                        if (s is HttpResponseState.Complete) {
                            if (settled.compareAndSet(false, true)) { cleanup(); cont.resume(parse(s)) }
                        }
                    }
                )
                idRef.set(id)
                if (settled.get()) cleanup()
                cont.invokeOnCancellation { cleanup() }
            }
        }
    }

    private fun parse(s: HttpResponseState.Complete): RainForecast? {
        val body = s.body ?: return null
        if (s.statusCode != 200) {
            Log.w(TAG, "QEXT_RAIN_FETCH_FAILED reason=http_status status=${s.statusCode}")
            return null
        }
        return try {
            parseJson(String(body), System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "QEXT_RAIN_FETCH_FAILED reason=parse_error msg=${e.message}")
            null
        }
    }

    /** Pierwszy 15-min slot z opadem >= 0.1 mm; prawdopodobienstwo z prognozy godzinowej. */
    fun parseJson(json: String, nowMs: Long): RainForecast {
        val o = JSONObject(json)
        val m = o.getJSONObject("minutely_15")
        val mt = m.getJSONArray("time"); val mp = m.getJSONArray("precipitation")
        val h = o.optJSONObject("hourly")
        val ht = h?.optJSONArray("time"); val hp = h?.optJSONArray("precipitation_probability")
        fun probAt(sec: Long): Int {
            if (ht == null || hp == null) return 0
            var best = 0
            for (k in 0 until ht.length()) if (ht.getLong(k) <= sec) best = hp.optInt(k, 0)
            return best
        }
        val nowSec = nowMs / 1000L
        for (k in 0 until mt.length()) {
            val t = mt.getLong(k)
            if (t + 900 < nowSec) continue
            val mm = mp.optDouble(k, 0.0)
            if (mm >= 0.1) {
                val minutes = ((t - nowSec) / 60L).toInt().coerceAtLeast(0)
                val f = RainForecast(minutes, probAt(t).coerceAtLeast(50), (mm * 4.0).toFloat(), nowMs)
                Log.i(TAG, "QEXT_RAIN_FORECAST minutes=$minutes prob=${f.probPct} mmh=${f.mmPerH}")
                return f
            }
        }
        // brak opadu w slotach 15-min: zagrozenie z prognozy godzinowej
        if (ht != null && hp != null) for (k in 0 until ht.length()) {
            val t = ht.getLong(k)
            val p = hp.optInt(k, 0)
            if (t + 3600 >= nowSec && p >= 50) {
                val minutes = ((t - nowSec) / 60L).toInt().coerceAtLeast(0)
                Log.i(TAG, "QEXT_RAIN_FORECAST minutes=$minutes prob=$p mmh=0 source=hourly")
                return RainForecast(minutes, p, 0f, nowMs)
            }
        }
        Log.i(TAG, "QEXT_RAIN_FORECAST none")
        return RainForecast(null, 0, 0f, nowMs)
    }
}
