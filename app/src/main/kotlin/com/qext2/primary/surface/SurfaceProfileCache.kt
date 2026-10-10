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
/** wpis do logcat i do dziennika jazdy (ride_log) - przebieg pobierania nawierzchni i POI z QBota */
private fun logR(m: String) { Log.i(TAG, m); com.qext2.primary.util.RideFileLog.append(m) }
private fun logW(m: String) { Log.w(TAG, m); com.qext2.primary.util.RideFileLog.append(m) }

/**
 * Segment nawierzchni z profilu QBota.
 */
/** Punkt z QBota (/api/poi/by-name): cat = water | shop | food, today = godziny na dzis. */
data class PoiPoint(val km: Float, val cat: String, val name: String, val today: String?)

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
    @Volatile private var pois: List<PoiPoint> = emptyList()
    private var fetchJob: Job? = null

    // Bieżąca nawierzchnia emitowana na zewnątrz (dla aggregatora)
    private val _currentSurface = MutableStateFlow(SurfaceType.PAVED)
    val currentSurfaceFlow: Flow<SurfaceType> = _currentSurface

    // Fallback z RouteGraph stream (0.0/1.0/2.0)
    private var routeGraphSurface: SurfaceType = SurfaceType.PAVED
    private var hasQBotData: Boolean = false
    // E4.3 (plan v2): generacja trasy -- spozniona odpowiedz dla poprzedniej trasy jest ignorowana
    @Volatile private var generation = 0
    private val retryScope = CoroutineScope(Dispatchers.Default)
    private fun retryDelayMs(attempt: Int): Long = when (attempt) { 0 -> 30_000L; 1 -> 60_000L; 2 -> 120_000L; else -> 300_000L }
    private val MAX_ATTEMPTS = 10

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

        logR("SURFACE_CACHE new polyline hash=$newHash route='$routeName'")
        clearCache()
        lastPolylineHash = newHash

        if (routeName != null) {
            val g = httpGet
            if (g != null) {
                val gen = generation
                fetchViaKaroo(routeName, g, gen, 0)
                fetchPoisViaKaroo(routeName, g, gen, 0)
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
    fun hasProfile(): Boolean = segments.isNotEmpty()

    fun segmentsSnapshot(): List<SurfaceSegment> = segments

    fun poisSnapshot(): List<PoiPoint> = pois

    private fun fetchPoisViaKaroo(
        routeName: String,
        httpGet: (String, Map<String, String>, (Int, String?) -> Unit) -> Unit,
        gen: Int,
        attempt: Int,
    ) {
        val url = "$qbotBaseUrl/api/poi/by-name?name=" + java.net.URLEncoder.encode(routeName, "UTF-8")
        httpGet(url, mapOf("Authorization" to "Bearer $qbotBearer")) { code, body ->
            if (gen != generation) { logR("POI_FETCH stale gen=$gen"); return@httpGet }
            if (code != 200 || body == null) {
                logW("POI_FETCH failed status=$code attempt=$attempt")
                if (attempt + 1 < MAX_ATTEMPTS) retryScope.launch {
                    delay(retryDelayMs(attempt))
                    if (gen == generation) fetchPoisViaKaroo(routeName, httpGet, gen, attempt + 1)
                }
                return@httpGet
            }
            try {
                val arr = JSONArray(body)
                val out = ArrayList<PoiPoint>(arr.length())
                for (k in 0 until arr.length()) {
                    val o = arr.getJSONObject(k)
                    out.add(PoiPoint(o.getDouble("km").toFloat(), o.optString("cat"), o.optString("name"), if (o.isNull("today")) null else o.optString("today")))
                }
                pois = out.sortedBy { it.km }
                logR("POI_FETCH OK pois=${pois.size} route='$routeName'")
            } catch (e: Exception) {
                logW("POI_FETCH parse_error msg=${e.message}")
            }
        }
    }

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
        generation++
        fetchJob?.cancel()
        segments = emptyList()
        pois = emptyList()
        hasQBotData = false
        lastPolylineHash = null
        _currentSurface.update { SurfaceType.PAVED }
        logR("SURFACE_CACHE cleared")
    }

    private fun fetchViaKaroo(
        routeName: String,
        httpGet: (String, Map<String, String>, (Int, String?) -> Unit) -> Unit,
        gen: Int,
        attempt: Int,
    ) {
        val url = "$qbotBaseUrl/api/surface/by-name?name=" +
            java.net.URLEncoder.encode(routeName, "UTF-8")
        logR("SURFACE_FETCH via_karoo start route='$routeName'")
        httpGet(url, mapOf("Authorization" to "Bearer $qbotBearer")) { code, body ->
            if (gen != generation) { logR("SURFACE_FETCH stale gen=$gen"); return@httpGet }
            val ok = code == 200 && body != null && parseSurfaceJson(body).isNotEmpty()
            com.qext2.primary.util.RideFileLog.append("SURFACE_FETCH status=$code ok=$ok attempt=$attempt route='$routeName'")
            if (!ok && attempt + 1 < MAX_ATTEMPTS) retryScope.launch {
                // 202 = trasa nieprzeliczona/nieznaleziona albo blad sieci -> ponow (30 s, 1, 2, potem co 5 min)
                delay(retryDelayMs(attempt))
                if (gen == generation) fetchViaKaroo(routeName, httpGet, gen, attempt + 1)
            }
            if (code != 200 || body == null) {
                logW("SURFACE_FETCH via_karoo failed status=$code")
            } else {
                val parsed = parseSurfaceJson(body)
                if (parsed.isNotEmpty()) {
                    segments = parsed.sortedBy { it.kmStart }
                    hasQBotData = true
                    logR("SURFACE_FETCH OK segments=${segments.size} route='$routeName'")
                } else {
                    logW("SURFACE_FETCH via_karoo empty_profile")
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
                    logR("SURFACE_FETCH not_ready attempt=$attempt, retry in 30s")
                    conn.disconnect()
                    delay(30_000L)
                    continue
                }
                if (code != 200) {
                    logW("SURFACE_FETCH HTTP $code attempt=$attempt")
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
                    logR("SURFACE_FETCH OK segments=${segments.size} route='$routeName'")
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
