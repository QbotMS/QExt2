package com.qext2.primary.weather

import android.util.Log
import com.qext2.primary.BuildConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    fun fetch(system: KarooSystemService, lat: Double, lon: Double, onResult: (WeatherData?) -> Unit) {
        val apiKey = BuildConfig.OPENWEATHER_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "QEXT_WEATHER_FETCH_FAILED reason=openweather_key_missing")
            onResult(null)
            return
        }

        val url = "${BuildConfig.OPENWEATHER_BASE_URL}?lat=$lat&lon=$lon&appid=$apiKey&units=metric"
        Log.i(TAG, "QEXT_WEATHER_FETCH_START lat=$lat lon=$lon")

        var consumerId: String? = null
        val latch = CountDownLatch(1)
        var result: WeatherData? = null

        consumerId = system.addConsumer<OnHttpResponse>(
            params = OnHttpResponse.MakeHttpRequest(method = "GET", url = url, waitForConnection = true),
            onError = { msg ->
                Log.w(TAG, "QEXT_WEATHER_FETCH_FAILED reason=onError msg=$msg")
                latch.countDown()
            },
            onEvent = { resp ->
                val s = resp.state
                if (s is HttpResponseState.Complete) {
                    Log.i(TAG, "QEXT_WEATHER_FETCH_HTTP status=${s.statusCode}")
                    val body = s.body
                    if (s.statusCode == 200 && body != null) {
                        try {
                            val json = JSONObject(String(body))
                            val main = json.getJSONObject("main")
                            val wind = json.optJSONObject("wind")
                            val weather = json.getJSONArray("weather").getJSONObject(0)
                            val rain = json.optJSONObject("rain")
                            val snow = json.optJSONObject("snow")
                            result = WeatherData(
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
                            Log.i(TAG, "QEXT_WEATHER_FETCH_PARSED temp=${result!!.temperatureC} wind=${result!!.windSpeedMps}m/s humidity=${result!!.humidityPct}% condition=${result!!.condition}")
                        } catch (e: Exception) {
                            Log.w(TAG, "QEXT_WEATHER_FETCH_FAILED reason=parse_error msg=${e.message}")
                        }
                    } else {
                        Log.w(TAG, "QEXT_WEATHER_FETCH_FAILED reason=http_status status=${s.statusCode}")
                    }
                }
                latch.countDown()
            }
        )

        try {
            latch.await(15, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Log.w(TAG, "QEXT_WEATHER_FETCH_FAILED reason=timeout")
        }
        consumerId?.let { system.removeConsumer(it) }
        onResult(result)
    }

    fun isFresh(data: WeatherData): Boolean {
        val age = System.currentTimeMillis() - data.updatedAt
        return age in 0..FRESH_MAX_MS
    }
}
