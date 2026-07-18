package com.qext2.primary

import android.util.Log
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.qext2.primary.data.AthleteData
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.model.SurfaceType
import com.qext2.primary.surface.SurfaceProfileCache
import com.qext2.primary.datatypes.BpActiveStaticDataType
import com.qext2.primary.datatypes.CompositeActiveDataType
import com.qext2.primary.datatypes.CompositePrimaryDataType
import com.qext2.primary.datatypes.StatsDataType
import com.qext2.primary.engine.RideDataAggregator
import com.qext2.primary.field.StatsAdvancedFieldPolicy
import com.qext2.primary.weather.WeatherClient
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "QExt2Ext"

class QExt2PrimaryExtension : KarooExtension("qext2", BuildConfig.VERSION_NAME) {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var _karooSystem: KarooSystemService? = null
    val karooSystem: KarooSystemService? get() = _karooSystem
    private var _aggregator: RideDataAggregator? = null
    val aggregator: RideDataAggregator? get() = _aggregator
    private val _aggregatorFlow = MutableStateFlow<RideDataAggregator?>(null)
    val aggregatorFlow: StateFlow<RideDataAggregator?> = _aggregatorFlow.asStateFlow()
    private var _surfaceCache: SurfaceProfileCache? = null
    private val _karooSystemFlow = MutableStateFlow<KarooSystemService?>(null)
    val karooSystemFlow: StateFlow<KarooSystemService?> = _karooSystemFlow.asStateFlow()
    private var fetchConsumerId: String? = null
    private var fetchAttempts = 0
    private var batteryPollJob: Job? = null
    private var weatherPollJob: Job? = null
    private var visibleFieldCount = 0
    private var aggregatorStreaming = false
    private var stopJob: Job? = null

    companion object {
        var instance: QExt2PrimaryExtension? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        logBuildBaseline()
        runStartupSelfCheck()
        AthleteDataStore.init(this)
        val surfaceCache = SurfaceProfileCache(
            qbotBaseUrl = BuildConfig.QBOT_BASE_URL,
            qbotBearer = BuildConfig.QBOT_BEARER,
        )
        _surfaceCache = surfaceCache
        com.qext2.primary.surface.SurfaceBridge.init(surfaceCache)
        val system = KarooSystemService(this)
        _karooSystem = system
        _karooSystemFlow.value = system
        system.connect { connected ->
            serviceScope.launch {
                if (connected) {
                    if (_aggregator == null) {
                        _aggregator = RideDataAggregator(system)
                        _aggregatorFlow.value = _aggregator
                    }
                    if (visibleFieldCount > 0 && !aggregatorStreaming) {
                        _aggregator?.startStreaming()
                        aggregatorStreaming = true
                        startBatteryPolling()
                        startWeatherPolling()
                    }
                    ensureDefaultLocation()
                    fetchAthleteData(system)
                } else {
                    batteryPollJob?.cancel()
                    batteryPollJob = null
                    weatherPollJob?.cancel()
                    weatherPollJob = null
                    _aggregator?.stopStreaming()
                    aggregatorStreaming = false
                    _aggregator = null
                    _aggregatorFlow.value = null
                }
            }
        }
    }

    fun onFieldVisible() {
        visibleFieldCount++
        stopJob?.cancel()
        stopJob = null
        if (_aggregator != null && !aggregatorStreaming) {
            _aggregator?.startStreaming()
            aggregatorStreaming = true
            startBatteryPolling()
            startWeatherPolling()
            Log.i(TAG, "QEXT_AGG_START visibleFields=$visibleFieldCount")
        }
        if (visibleFieldCount == 1) {
            val staleMs = 30 * 60 * 1000L
            if (System.currentTimeMillis() - AthleteDataStore.loadLastRefresh() > staleMs) {
                _karooSystem?.let { fetchAthleteData(it) }
                Log.i(TAG, "QEXT_AUTO_FETCH triggered on field visible")
            }
        }
    }

    fun onFieldHidden() {
        if (visibleFieldCount > 0) visibleFieldCount--
        if (visibleFieldCount == 0 && aggregatorStreaming) {
            stopJob?.cancel()
            stopJob = serviceScope.launch {
                delay(20_000L)
                if (visibleFieldCount == 0 && aggregatorStreaming) {
                    _aggregator?.stopStreamingSoft()
                    aggregatorStreaming = false
                    batteryPollJob?.cancel(); batteryPollJob = null
                    weatherPollJob?.cancel(); weatherPollJob = null
                    Log.i(TAG, "QEXT_AGG_SOFT_STOP idle (no visible field 20s)")
                }
            }
        }
    }

    private fun logBuildBaseline() {
        Log.i(
            TAG,
            "QEXT_BUILD_BASELINE applicationId=${BuildConfig.APPLICATION_ID} versionName=${BuildConfig.VERSION_NAME} " +
                "marker='QExt2 LAB baseline|real_ride_gate_pass|synthetic_gate_pass' " +
                "lab_baseline_enabled=true known_missing_sources=[GEAR] advanced_fields_policy=WAIT_NO_MODEL"
        )
    }

    private fun runStartupSelfCheck() {
        val failures = mutableListOf<String>()
        try {
            Class.forName("pl.qbot.karoo.core.FieldComputers")
        } catch (_: Throwable) {
            failures.add("FieldComputers_missing")
        }
        try {
            Class.forName("com.qext2.primary.core.LabRideStateRepository")
        } catch (_: Throwable) {
            failures.add("LabRideStateRepository_missing")
        }
        val policyDecision = StatsAdvancedFieldPolicy.waitNoModel("startup_check")
        if (policyDecision.value != "WAIT" || policyDecision.reason.isBlank()) {
            failures.add("advanced_policy_inactive")
        }
        if (failures.isEmpty()) {
            Log.i(TAG, "QEXT_SELF_CHECK PASS")
        } else {
            Log.w(TAG, "QEXT_SELF_CHECK FAIL reason=${failures.joinToString(",")}")
        }
    }

    private val _types: List<DataTypeImpl> = listOf(CompositePrimaryDataType(), CompositeActiveDataType(), BpActiveStaticDataType(), StatsDataType())
    override val types: List<DataTypeImpl> get() = _types

    override fun startFit(emitter: Emitter<FitEffect>) {
        // WATEK 2 Strona A: co sekunde zapis stanu modelu QExt2 do FIT jako developer fields.
        // Kontrakt nazw MUSI sie zgadzac ze Strona B (QBot fit_ingest / fitmodel_qext2_ride).
        val fWbal = DeveloperField(0.toShort(), 2.toShort(), "qext2_wbal_pct", "%")
        val fCp = DeveloperField(1.toShort(), 132.toShort(), "qext2_cp_eff_w", "W")
        val fWp = DeveloperField(2.toShort(), 136.toShort(), "qext2_wprime_eff_kj", "kJ")
        val fCf = DeveloperField(3.toShort(), 136.toShort(), "qext2_cf", "factor")
        val fZero = DeveloperField(4.toShort(), 2.toShort(), "qext2_wbal_zero", "bool")
        val fRdy = DeveloperField(5.toShort(), 136.toShort(), "qext2_readiness", "factor")
        val fRsrv = DeveloperField(6.toShort(), 2.toShort(), "qext2_rsrv_pct", "%")
        val fXss = DeveloperField(7.toShort(), 136.toShort(), "qext2_xss", "pts")
        val job = serviceScope.launch {
            while (true) {
                val agg = _aggregator
                if (agg != null) {
                    val s = agg.statsSnapshot.value
                    if (s.wBalancePercent >= 0) {
                        val zero = if (s.wBalancePercent <= 0) 1.0 else 0.0
                        emitter.onNext(
                            WriteToRecordMesg(
                                listOf(
                                    FieldValue(fWbal, s.wBalancePercent.toDouble()),
                                    FieldValue(fCp, s.cpEffW.toDouble()),
                                    FieldValue(fWp, s.wPrimeEffKj.toDouble()),
                                    FieldValue(fCf, s.cfEff.toDouble()),
                                    FieldValue(fZero, zero),
                                    FieldValue(fRdy, s.readiness.toDouble()),
                                    FieldValue(fRsrv, s.rideReservePercent.toDouble()),
                                    FieldValue(fXss, s.xssValue.toDouble()),
                                )
                            )
                        )
                    }
                }
                delay(1000L)
            }
        }
        emitter.setCancellable { job.cancel() }
        Log.i(TAG, "QEXT_FIT_START writing 8 developer fields @1Hz")
    }

    fun refetchAthleteData() {
        _karooSystem?.let { fetchAthleteData(it) }
    }

    fun refreshDeadlineConfig() {
        _aggregator?.refreshDeadlineFromStore()
    }

    fun refreshBaroSensitive(baroSensitive: Boolean) {
        val data = AthleteDataStore.load().applyBaroAdjustment(baroSensitive)
        _aggregator?.updateAthleteData(data)
    }

    fun refreshCapTwilight(capTwilight: Boolean) {
        _aggregator?.refreshCapTwilightFromStore()
    }

    fun refreshModeFactor() {
        _aggregator?.refreshModeFactor()
    }

    fun refreshCassetteOverride() {
        _aggregator?.refreshCassetteOverride()
    }

    /**
     * Wołane gdy OnNavigationState się zmienia (z aggregatora lub zewnętrznie).
     * Czyści cache i fetchuje profil nawierzchni dla nowej trasy.
     */
    fun onNavigationStateForSurface(
        state: io.hammerhead.karooext.models.OnNavigationState,
        routeName: String?,
    ) {
        _surfaceCache?.onNavigationState(state, routeName)
    }

    /**
     * Fallback z RouteGraph surfacetype stream.
     */
    fun onRouteGraphSurface(value: Float) {
        _surfaceCache?.onRouteGraphSurface(value)
    }

    /**
     * Aktualizacja surface w aggregatorze z cache.
     * Wołana z pętli 1 Hz w RideDataAggregator.
     */
    fun currentSurface(kmAlongRoute: Float): SurfaceType =
        _surfaceCache?.surfaceAt(kmAlongRoute) ?: SurfaceType.PAVED

    fun remainingSurface(kmAlongRoute: Float) =
        _surfaceCache?.remainingByType(kmAlongRoute) ?: emptyMap()

    private fun fetchAthleteData(system: KarooSystemService, isRetry: Boolean = false) {
        if (!isRetry) fetchAttempts = 0
        fetchConsumerId?.let { system.removeConsumer(it) }
        val url = BuildConfig.QEXT_READINESS_URL.trim()
            .ifEmpty { "https://qbot.cytr.us/ride-readiness" }
        Log.i(TAG, "QEXT_READINESS_FETCH_START url=$url retry=$isRetry")
        fetchConsumerId = system.addConsumer<OnHttpResponse>(
            params = OnHttpResponse.MakeHttpRequest(method = "GET", url = url, waitForConnection = false),
            onError = { msg ->
                Log.w(TAG, "QEXT_READINESS_FETCH_FAILED reason=onError msg=$msg")
                if (fetchAttempts < 1) {
                    fetchAttempts++
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ fetchAthleteData(system, isRetry = true) }, 60_000L)
                }
            },
            onEvent = { resp ->
                val s = resp.state
                if (s is HttpResponseState.Complete) {
                    Log.i(TAG, "QEXT_READINESS_FETCH_HTTP status=${s.statusCode}")
                    val body = s.body
                    if (s.statusCode == 200 && body != null) {
                        try {
                            val json = JSONObject(String(body))
                            val wPrimeKj = json.optDouble("wPrimeKj", 3.75)
                            val ltpWatts = json.optInt("ltpWatts", 0)
                            Log.i(TAG, "QEXT_READINESS_FETCH_PARSED wPrimeKj=$wPrimeKj ltpWatts=$ltpWatts ftpWatts=${json.optInt("ftpWatts", 250)}")
                            val sig = json.optJSONObject("signals")
                            val sleepDataDate = json.optString("sleepDataDate")
                                .ifBlank { sig?.optString("sleepDataDate") ?: "" }
                            val reasons = mutableListOf<String>()
                            val ftpPresent = json.has("ftpWatts") && json.optDouble("ftpWatts", 0.0) > 0.0
                            val ltpPresent = json.optInt("ltpWatts", 0) > 0
                            val hrvPresent = sig != null && sig.has("hrvToday") && sig.optInt("hrvToday", 0) > 0
                            if (!ftpPresent) reasons.add("FTP missing")
                            if (!ltpPresent) reasons.add("LTP missing")
                            if (!ftpPresent && !ltpPresent && !hrvPresent) reasons.add("QBot profile incomplete")
                            val data = AthleteData(
                                ftp = json.optInt("ftpWatts", 250),
                                wPrimeKj = json.optDouble("wPrimeKj", 3.75),
                                todayFactor = json.optDouble("todayFactor", 1.0).toFloat(),
                                ltpWatts = json.optInt("ltpWatts", 0),
                                ctl = json.optDouble("ctl", 60.0).toFloat(),
                                atl = json.optDouble("atl", 40.0).toFloat(),
                                humidityPercent = json.optDouble("humidityPercent", 50.0).toFloat(),
                                sunsetTimestampMs = json.optLong("sunsetTimestampMs", json.optLong("twilightMs", json.optLong("sunsetMs", 0L))),
                                maxHr = json.optInt("maxHrBpm", json.optInt("MaxHRBPM", json.optInt("maxHr", json.optInt("maxHeartRate", 180)))),
                                bodyWeightKg = json.optDouble("bodyWeightKg", 75.0).toFloat(),
                                xertStatus = sig?.optString("xertStatus", "--") ?: "--",
                                hrvToday = sig?.optInt("hrvToday", 0) ?: 0,
                                hrvBaseline30d = sig?.optDouble("hrvBaseline30d", 0.0)?.toFloat() ?: 0f,
                                hrvDeviation30d = sig?.optDouble("hrvDeviation30d", 0.0)?.toFloat() ?: 0f,
                                sleepTodayH = sig?.optDouble("sleepTodayH", 0.0)?.toFloat() ?: 0f,
                                sleepBaseline30d = sig?.optDouble("sleepBaseline30d", 0.0)?.toFloat() ?: 0f,
                                sleepDev = sig?.optDouble("sleepDev", 0.0)?.toFloat() ?: 0f,
                                restingHrDev = sig?.optDouble("restingHrDev", 0.0)?.toFloat() ?: 0f,
                                pressureHpa = json.optDouble("pressureHpa", 1013.0).toFloat(),
                                pressureChange24h = json.optDouble("pressureChange24h", 0.0).toFloat(),
                                pressureDeficit = json.optDouble("pressureDeficit", 0.0).toFloat(),
                                baroMultiplier = json.optDouble("baroMultiplier", 1.0).toFloat(),
                                partial = json.optJSONArray("sources")?.toString()?.contains("partial") == true,
                                warningReasons = reasons.joinToString("|"),
                                fetchTimestamp = System.currentTimeMillis()
                            )
                            AthleteDataStore.save(data)
                            if (AthleteDataStore.updateSleepDataDateMarker(sleepDataDate)) {
                                Log.i(TAG, "Sleep marker updated: $sleepDataDate pending=${AthleteDataStore.loadSleepRefreshPending()}")
                            }
                            val adjusted = data.applyBaroAdjustment(AthleteDataStore.loadBaroSensitive())
                            _aggregator?.updateAthleteData(adjusted)
                            AthleteDataStore.saveLastRefresh()
                            Log.i(TAG, "QEXT_READINESS_FETCH_SAVED source=$url wPrimeKj=${data.wPrimeKj} ltpWatts=${data.ltpWatts} ftpWatts=${data.ftp} factor=${adjusted.todayFactor}")
                        } catch (e: Exception) {
                            Log.w(TAG, "QEXT_READINESS_FETCH_FAILED reason=parse_error msg=${e.message}")
                        }
                    } else {
                        Log.w(TAG, "QEXT_READINESS_FETCH_FAILED reason=http_status status=${s.statusCode} error=${s.error ?: "no body"}")
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        exportCarbData()
        _aggregator?.stopStreaming()
        _aggregator = null
        _aggregatorFlow.value = null
        batteryPollJob?.cancel()
        batteryPollJob = null
        weatherPollJob?.cancel()
        weatherPollJob = null
        _karooSystem?.disconnect()
        _karooSystem = null
        _karooSystemFlow.value = null
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun exportCarbData() {
        val agg = _aggregator ?: return
        val intake = agg.getCarbIntakeG()
        val needed = agg.getCarbNeededG()
        if (intake == 0 && needed == 0) return
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            dir.mkdirs()
            val rideStartMs = agg.getRideStartMs()
            val now = System.currentTimeMillis()
            val fileName = "carb_export_${rideStartMs}.csv"
            val file = File(dir, fileName)
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val balance = agg.getCarbBalanceG()
            val packetSize = AthleteDataStore.loadCarbPacketSize()
            file.writeText(buildString {
                appendLine("# QExt2 CARB Export")
                appendLine("# Ride start: $rideStartMs (${df.format(Date(rideStartMs))})")
                appendLine("# Export time: ${df.format(Date(now))}")
                appendLine("field,value")
                appendLine("carb_intake_total_g,$intake")
                appendLine("carb_needed_total_g,$needed")
                appendLine("carb_balance_g,$balance")
                appendLine("carb_packet_size_g,$packetSize")
            })
            Log.i(TAG, "CARB export: ${file.absolutePath} intake=${intake}g needed=${needed}g balance=${balance}g")
        } catch (e: Exception) {
            Log.e(TAG, "CARB export failed: ${e.message}")
        }
    }

    private fun startBatteryPolling() {
        if (batteryPollJob?.isActive == true) return
        batteryPollJob = serviceScope.launch {
            while (_aggregator != null) {
                val statusIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (statusIntent != null) {
                    val level = statusIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = statusIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val status = statusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else null
                    _aggregator?.updateBatteryStatus(pct, charging)
                }
                kotlinx.coroutines.delay(30_000L)
            }
        }
    }

    private fun startWeatherPolling() {
        if (!WeatherClient.isKeyConfigured()) return
        if (weatherPollJob?.isActive == true) return
        weatherPollJob = serviceScope.launch {
            while (_aggregator != null) {
                _aggregator?.fetchWeatherIfNeeded()
                kotlinx.coroutines.delay(600_000L)
            }
        }
    }

    private fun ensureDefaultLocation() {
        if (AthleteDataStore.loadLocationLat() != null) return
        val latStr = BuildConfig.WEATHER_LAT.trim()
        val lonStr = BuildConfig.WEATHER_LON.trim()
        if (latStr.isBlank() || lonStr.isBlank()) return
        val lat = latStr.toDoubleOrNull() ?: return
        val lon = lonStr.toDoubleOrNull() ?: return
        AthleteDataStore.saveLocation(lat, lon)
        Log.i(TAG, "QEXT_WEATHER_LOCATION_DEFAULT lat=$lat lon=$lon")
    }
}
