package com.qext2.primary.surface

import android.util.Log
import com.qext2.primary.model.SurfaceType
import io.hammerhead.karooext.models.OnNavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

private const val TAG = "QEXT_SURFACE"

/**
 * Segment nawierzchni z profilu QBota.
 */
data class SurfaceSegment(
    val kmStart: Float,
    val kmEnd: Float,
    val surface: SurfaceType,
)

/**
 * Cache profilu nawierzchni dla bieżącej trasy.
 *
 * Klucz = hash polyline z OnNavigationState.
 * Zmiana trasy → automatyczne czyszczenie i refetch.
 *
 * Źródła (priorytet):
 *  1. QBot REST /api/surface/{route_id}   (prefetch / on-demand)
 *  2. RouteGraph surfacetype stream        (fallback, jeśli zainstalowany)
 *  3. PAVED default                        (gdy brak danych)
 */
class SurfaceProfileCache(
    private val qbotBaseUrl: String,  // np. "https://qbot.cytr.us"
    private val qbotBearer: String,
    private val httpGet: ((String, Map<String, String>, (Int, String?) -> Unit) -> Unit)? = null,
) {
    private var lastPolylineHash: Int? = null
    private var segments: List<SurfaceSegment> = emptyList()
    private var fetchJob: Job? = null

    // Bieżąca nawierzchnia emitowana na zewnątrz (dla aggregatora)
    private val _currentSurface = MutableStateFlow(SurfaceType.PAVED)
    val currentSurfaceFlow: Flow<SurfaceType> = _currentSurface

    // Fallback z RouteGraph stream (0.0/1.0/2.0)
    private var routeGraphSurface: SurfaceType = SurfaceType.PAVED
    private var hasQBotData: Boolean = false

    // HTTP przez HttpURLConnection (jak reszta projektu)

    /**
     * Wołane przy każdej zmianie OnNavigationState.
     * Gdy polyline się zmienił → czyść cache → fetchuj nowe dane.
     */
    fun onNavigationState(state: OnNavigationState, routeName: String?) {
        val polyline = when (state.state) {
            is OnNavigationState.NavigationState.NavigatingRoute ->
                (state.state as OnNavigationState.NavigationState.NavigatingRoute).routePolyline
            is OnNavigationState.NavigationState.NavigatingToDestination ->
                (state.state as OnNavigationState.NavigationState.NavigatingToDestination).polyline
            else -> null
        } ?: run {
            clearCache()
            return
        }

        val newHash = polyline.hashCode()
        if (newHash == lastPolylineHash) return  // ta sama trasa, nic nie rób

        Log.i(TAG, "SURFACE_CACHE new polyline hash=$newHash route='$routeName'")
        clearCache()
        lastPolylineHash = newHash

        if (routeName != null) {
            val g = httpGet
            if (g != null) {
                fetchViaKaroo(routeName, g)
            } else {
                fetchJob?.cancel()
                fetchJob = CoroutineScope(Dispatchers.IO).launch {
                    fetchFromQBot(routeName)
                }
            }
        }
    }

    /**
     * Aktualizacja z RouteGraph surfacetype stream.
     * Używana gdy brak danych QBota.
     */
    fun onRouteGraphSurface(value: Float) {
        routeGraphSurface = SurfaceType.fromRouteGraph(value)
        if (!hasQBotData) {
            _currentSurface.update { routeGraphSurface }
        }
    }

    /**
     * Lookup nawierzchni dla bieżącej pozycji na trasie.
     * @param kmAlongRoute kilometry od startu trasy
     */
    fun surfaceAt(kmAlongRoute: Float): SurfaceType {
        if (segments.isEmpty()) return if (hasQBotData) SurfaceType.PAVED else routeGraphSurface
        // Binarny lookup po segmentach posortowanych wg kmStart
        val seg = segments.firstOrNull { kmAlongRoute in it.kmStart..it.kmEnd }
            ?: segments.lastOrNull { kmAlongRoute >= it.kmStart }
            ?: segments.first()
        return seg.surface
    }

    /**
     * Ile km każdego typu nawierzchni pozostało od bieżącej pozycji do końca.
     */
    fun initialByType(): Map<SurfaceType, Float> = remainingByType(0f)

    fun remainingByType(kmAlongRoute: Float): Map<SurfaceType, Float> {
        val remaining = segments.filter { it.kmEnd > kmAlongRoute }
        return SurfaceType.values().associateWith { type ->
            remaining.filter { it.surface == type }.sumOf { seg ->
                val start = maxOf(seg.kmStart, kmAlongRoute)
                (seg.kmEnd - start).toDouble()
            }.toFloat().coerceAtLeast(0f)
        }
    }

    private fun clearCache() {
        fetchJob?.cancel()
        segments = emptyList()
        hasQBotData = false
        lastPolylineHash = null
        _currentSurface.update { SurfaceType.PAVED }
        Log.i(TAG, "SURFACE_CACHE cleared")
    }

    private fun fetchViaKaroo(
        routeName: String,
        httpGet: (String, Map<String, String>, (Int, String?) -> Unit) -> Unit,
    ) {
        val url = "$qbotBaseUrl/api/surface/by-name?name=" +
            java.net.URLEncoder.encode(routeName, "UTF-8")
        Log.i(TAG, "SURFACE_FETCH via_karoo start route='$routeName'")
        httpGet(url, mapOf("Authorization" to "Bearer $qbotBearer")) { code, body ->
            if (code != 200 || body == null) {
                Log.w(TAG, "SURFACE_FETCH via_karoo failed status=$code")
            } else {
                val parsed = parseSurfaceJson(body)
                if (parsed.isNotEmpty()) {
                    segments = parsed.sortedBy { it.kmStart }
                    hasQBotData = true
                    Log.i(TAG, "SURFACE_FETCH OK segments=${segments.size} route='$routeName'")
                } else {
                    Log.w(TAG, "SURFACE_FETCH via_karoo empty_profile")
                }
            }
        }
    }

    private suspend fun fetchFromQBot(routeName: String, retryCount: Int = 3) {
        // Spróbuj zmapować nazwę trasy na route_id przez QBot
        // Endpoint: GET /api/surface/by-name?name={routeName}
        // Fallback: GET /api/surface/find?q={routeName}
        for (attempt in 1..retryCount) {
            try {
                val encodedName = java.net.URLEncoder.encode(routeName, "UTF-8")
                val url = "$qbotBaseUrl/api/surface/by-name?name=$encodedName"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $qbotBearer")
                    connectTimeout = 8_000
                    readTimeout = 10_000
                }
                val code = conn.responseCode
                if (code == 202) {
                    Log.i(TAG, "SURFACE_FETCH not_ready attempt=$attempt, retry in 30s")
                    conn.disconnect()
                    delay(30_000L)
                    continue
                }
                if (code != 200) {
                    Log.w(TAG, "SURFACE_FETCH HTTP $code attempt=$attempt")
                    conn.disconnect()
                    if (attempt < retryCount) delay(5_000L)
                    continue
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val parsed = parseSurfaceJson(body)
                if (parsed.isNotEmpty()) {
                    segments = parsed.sortedBy { it.kmStart }
                    hasQBotData = true
                    Log.i(TAG, "SURFACE_FETCH OK segments=${segments.size} route='$routeName'")
                }
                break
            } catch (e: Exception) {
                Log.e(TAG, "SURFACE_FETCH error attempt=$attempt: ${e.message}")
                if (attempt < retryCount) delay(5_000L)
            }
        }
    }

    private fun parseSurfaceJson(json: String): List<SurfaceSegment> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SurfaceSegment(
                    kmStart = obj.getDouble("km_start").toFloat(),
                    kmEnd = obj.getDouble("km_end").toFloat(),
                    surface = SurfaceType.fromQBot(obj.optString("surface", "paved")),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "SURFACE_PARSE error: ${e.message}")
            emptyList()
        }
    }
}
