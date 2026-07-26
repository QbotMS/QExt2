package com.qext2.primary.weather

import android.util.Log
import com.qext2.primary.BuildConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

private const val TAG = "QExt2Weather"

data class WeatherData(
    val temperatureC: Float,
    val feelsLikeC: Float?,
    val windSpeedMps: Float,
    val windDirectionDeg: Int,
    val humidityPct: Int,
    val rain1hMm: Float?,
    val snow1hMm: Float?,
    val condition: String,
    val updatedAt: Long,
    val source: String = "openweathermap",
)

object WeatherClient {

    private const val FRESH_MAX_MS = 30 * 60_000L

    fun isKeyConfigured(): Boolean =
        BuildConfig.OPENWEATHER_API_KEY.isNotBlank()

    suspend fun fetch(system: KarooSystemService, lat: Double, lon: Double): WeatherData? {
        val apiKey = BuildConfig.OPENWEATHER_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "QEXT_WEATHER_FETCH_FAILED reason=openweather_key_missing")
            return null
        }

        val url = "${BuildConfig.OPENWEATHER_BASE_URL}?lat=$lat&lon=$lon&appid=$apiKey&units=metric"
        Log.i(TAG, "QEXT_WEATHER_FETCH_START lat=$lat lon=$lon")

        // Audyt baterii 2026-07-26: limit skrocony 15s -> 8s (krocej trzyma radio przy
        // slabym zasiegu). Usuwanie konsumenta odporne na wyscig: jesli callback strzeli
        // SYNCHRONICZNIE zanim addConsumer zwroci id, stary kod nie usuwal listenera
        // (consumerId=null) -> narastajacy wyciek/CPU na dlugiej jezdzie. Teraz id trzymany
        // w AtomicReference, a po zapisaniu sprawdzamy czy juz sie rozstrzygnelo.
        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { cont ->
                val idRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val settled = java.util.concurrent.atomic.AtomicBoolean(false)
                fun cleanup() { idRef.getAndSet(null)?.let { system.removeConsumer(it) } }
                val id = system.addConsumer<OnHttpResponse>(
                    params = OnHttpResponse.MakeHttpRequest(method = "GET", url = url, waitForConnection = true),
                    onError = { msg ->
                        Log.w(TAG, "QEXT_WEATHER_FETCH_FAILED reason=onError msg=$msg")
                        if (settled.compareAndSet(false, true)) { cleanup(); cont.resume(null) }
                    },
                    onEvent = { resp ->
                        val s = resp.state
                        if (s is HttpResponseState.Complete) {
                            if (settled.compareAndSet(false, true)) { cleanup(); cont.resume(parseResponse(s)) }
                        }
                    }
                )
                idRef.set(id)
                if (settled.get()) cleanup()  // callback strzelil zanim id sie zapisalo
                cont.invokeOnCancellation { cleanup() }
            }
        }
    }

    private fun parseResponse(s: HttpResponseState.Complete): WeatherData? {
        Log.i(TAG, "QEXT_WEATHER_FETCH_HTTP status=${s.statusCode}")
        val body = s.body ?: return null.also {
            Log.w(TAG, "QEXT_WEATHER_FETCH_FAILED reason=empty_body")
        }
        if (s.statusCode != 200) {
            Log.w(TAG, "QEXT_WEATHER_FETCH_FAILED reason=http_status status=${s.statusCode}")
            return null
        }
        return try {
            val json = JSONObject(String(body))
            val main = json.getJSONObject("main")
            val wind = json.optJSONObject("wind")
            val weather = json.getJSONArray("weather").getJSONObject(0)
            val rain = json.optJSONObject("rain")
            val snow = json.optJSONObject("snow")
            val data = WeatherData(
                temperatureC = main.getDouble("temp").toFloat(),
                feelsLikeC = main.optDouble("feels_like", Double.NaN).let { if (it.isNaN()) null else it.toFloat() },
                windSpeedMps = wind?.optDouble("speed", Double.NaN)?.toFloat() ?: 0f,
                windDirectionDeg = wind?.optInt("deg", -1) ?: -1,
                humidityPct = main.getInt("humidity"),
                rain1hMm = rain?.optDouble("1h")?.toFloat(),
                snow1hMm = snow?.optDouble("1h")?.toFloat(),
                condition = weather.getString("main"),
                updatedAt = System.currentTimeMillis(),
            )
            Log.i(TAG, "QEXT_WEATHER_FETCH_PARSED temp=${data.temperatureC} wind=${data.windSpeedMps}m/s humidity=${data.humidityPct}% condition=${data.condition}")
            data
        } catch (e: Exception) {
            Log.w(TAG, "QEXT_WEATHER_FETCH_FAILED reason=parse_error msg=${e.message}")
            null
        }
    }

    fun isFresh(data: WeatherData): Boolean {
        val age = System.currentTimeMillis() - data.updatedAt
        return age in 0..FRESH_MAX_MS
    }
}
