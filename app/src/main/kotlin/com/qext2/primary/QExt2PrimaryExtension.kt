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
    private var windowsJob: kotlinx.coroutines.Job? = null
    private var msgHubJob: kotlinx.coroutines.Job? = null
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
    // E1.1 (plan v2): obliczenia zyja od START do KONIEC nagrywania jazdy, niezaleznie od widocznosci pol.
    @Volatile private var rideRecording = false
    private var rideStateConsumerId: String? = null

    companion object {
        var instance: QExt2PrimaryExtension? = null
        /** Wersja kontraktu dev fields FIT (docs/KONTRAKT_DANYCH.md pkt 6). */
        const val FIT_SCHEMA = 2
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        logBuildBaseline()
        runStartupSelfCheck()
        AthleteDataStore.init(this)
        com.qext2.primary.eta.EtaSpeedTable.historyMinPerKm = AthleteDataStore.loadEtaStopsMinPerKm()
        com.qext2.primary.eta.EtaFileLog.init(getExternalFilesDir(null) ?: filesDir)
        com.qext2.primary.util.RideFileLog.init(getExternalFilesDir(null) ?: filesDir)
        com.qext2.primary.util.RideFileLog.append("START QExt2 versionCode=${BuildConfig.VERSION_CODE}")
        val surfaceCache = SurfaceProfileCache(
            qbotBaseUrl = BuildConfig.QBOT_BASE_URL,
            qbotBearer = BuildConfig.QBOT_BEARER,
            httpGet = ::karooHttpGet,
        )
        _surfaceCache = surfaceCache
        com.qext2.primary.surface.SurfaceBridge.init(surfaceCache)
        fetchTypicalEf()
        val system = KarooSystemService(this)
        _karooSystem = system
        _karooSystemFlow.value = system
        system.connect { connected ->
            serviceScope.launch {
                if (connected) {
                    if (_aggregator == null) {
                        _aggregator = RideDataAggregator(system)
                        _aggregatorFlow.value = _aggregator
                        // STATS v2 PRZEBIEG: okna 5 min (NP, EF) karmione co ~1 s
                        com.qext2.primary.statsv2.RideWindows.reset()
                        // wspolna kolejka komunikatow (ACTIVE + KOKPIT): producenci co 1 s
                        com.qext2.primary.active.ActiveMessageHub.reset()
                        msgHubJob?.cancel()
                        _aggregator?.let { a ->
                            msgHubJob = serviceScope.launch {
                                while (true) {
                                    com.qext2.primary.active.ActiveMessageHub.tick(a, System.currentTimeMillis())
                                    kotlinx.coroutines.delay(1_000L)
                                }
                            }
                        }
                        if (com.qext2.primary.statsv2.RideWindows.typEf == null) fetchTypicalEf()
                        windowsJob?.cancel()
                        _aggregator?.let { a ->
                            windowsJob = serviceScope.launch {
                                kotlinx.coroutines.flow.combine(a.snapshot, a.statsSnapshot) { p, st -> p to st }.collect { (p, st) ->
                                    com.qext2.primary.statsv2.RideWindows.feed(
                                        System.currentTimeMillis(),
                                        if (p.powerFreshnessMs < 8_000L) p.power3s else null,
                                        if (p.hrFreshnessMs < 12_000L && p.hr > 0) p.hr else null,
                                        p.speedKmh > 3.0,
                                        st.distanceKm,
                                    )
                                }
                            }
                        }
                    }
                    if (visibleFieldCount > 0 && !aggregatorStreaming) {
                        _aggregator?.startStreaming()
                        aggregatorStreaming = true
                        startBatteryPolling()
                        startWeatherPolling()
                    }
                    ensureDefaultLocation()
                    fetchAthleteData(system)
                    subscribeRideState(system)
                } else {
                    batteryPollJob?.cancel()
                    batteryPollJob = null
                    weatherPollJob?.cancel()
                    weatherPollJob = null
                    // Mrugniecie polaczenia Karoo (czeste przy wgraniu trasy) NIE moze
                    // niszczyc sesji: miekki stop, agregator zostaje (reconnect wznowi ze snapshotu).
                    _aggregator?.stopStreamingSoft()
                    aggregatorStreaming = false
                }
            }
        }
    }

    /** E1.1: stan nagrywania z Karoo. Recording/Paused -> licz; Idle po jezdzie -> zamknij jazde. */
    private fun subscribeRideState(system: KarooSystemService) {
        if (rideStateConsumerId != null) return
        rideStateConsumerId = try {
            system.addConsumer<io.hammerhead.karooext.models.RideState> { st -> serviceScope.launch { onRideState(st) } }
        } catch (e: Exception) {
            Log.w(TAG, "QEXT_RIDE_STATE_SUB_FAIL msg=${e.message}"); null
        }
    }

    private fun onRideState(st: io.hammerhead.karooext.models.RideState) {
        val recordingNow = st !is io.hammerhead.karooext.models.RideState.Idle
        com.qext2.primary.util.RideFileLog.append("RIDE_STATE ${st::class.simpleName} recording=$recordingNow visible=$visibleFieldCount running=$aggregatorStreaming")
        if (recordingNow) {
            rideRecording = true
            stopJob?.cancel(); stopJob = null
            if (_aggregator != null && !aggregatorStreaming) {
                _aggregator?.startStreaming()
                aggregatorStreaming = true
                startBatteryPolling(); startWeatherPolling()
            }
        } else if (rideRecording) {
            // KONIEC jazdy: zamknij sesje (baza RSRV dnia, reset licznikow); pola widoczne -> czysty start na podglad.
            rideRecording = false
            _aggregator?.stopStreaming()
            aggregatorStreaming = false
            batteryPollJob?.cancel(); batteryPollJob = null
            weatherPollJob?.cancel(); weatherPollJob = null
            if (visibleFieldCount > 0 && _aggregator != null) {
                _aggregator?.startStreaming()
                aggregatorStreaming = true
                startBatteryPolling(); startWeatherPolling()
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
        if (visibleFieldCount == 0 && aggregatorStreaming && !rideRecording) {
            stopJob?.cancel()
            stopJob = serviceScope.launch {
                delay(20_000L)
                if (visibleFieldCount == 0 && aggregatorStreaming && !rideRecording) {
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

    private val _types: List<DataTypeImpl> = listOf(CompositePrimaryDataType(), CompositeActiveDataType(), BpActiveStaticDataType(), StatsDataType(), com.qext2.primary.statsv2.StatsV2DataType(), com.qext2.primary.kokpit.KokpitNavDataType(), com.qext2.primary.kokpit.KokpitInstDataType(),
        // wersje produkcyjne (zawsze dane z jazdy) obok testowych z demo
        com.qext2.primary.statsv2.StatsV2DataType("qext2-stats-live", forceLive = true),
        com.qext2.primary.kokpit.KokpitNavDataType("qext2-kokpit-nav-live", forceLive = true),
        com.qext2.primary.kokpit.KokpitInstDataType("qext2-kokpit-inst-live", forceLive = true))
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
        // E-FIT schemat 2 (plan v2): wersja schematu/modelu + NP5; serwer: fit_ingest model_schema
        val fSchema = DeveloperField(8.toShort(), 2.toShort(), "qext2_schema", "v")
        val fNp5 = DeveloperField(9.toShort(), 132.toShort(), "qext2_np5_w", "W")
        val job = serviceScope.launch {
            while (true) {
                val agg = _aggregator
                if (agg != null && aggregatorStreaming) {
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
                                    FieldValue(fSchema, FIT_SCHEMA.toDouble()),
                                    FieldValue(fNp5, s.np5Watts.toDouble()),
                                )
                            )
                        )
                    }
                }
                delay(1000L)
            }
        }
        emitter.setCancellable { job.cancel() }
        Log.i(TAG, "QEXT_FIT_START writing 10 developer fields @1Hz schema=$FIT_SCHEMA")
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
    /** STATS v2 PRZEBIEG: typowe EF z QBota (mediana 90 dni). Bledy ciche - linia "typ." po prostu sie nie pokaze. */
    private fun fetchTypicalEf() {
        try {
            karooHttpGet("${BuildConfig.QBOT_BASE_URL}/api/ef/typical", mapOf("Authorization" to "Bearer ${BuildConfig.QBOT_BEARER}")) { code, body ->
                if (code == 200 && body != null) {
                    try {
                        val o = org.json.JSONObject(body)
                        val ef = if (o.isNull("ef")) null else o.getDouble("ef").toFloat()
                        com.qext2.primary.statsv2.RideWindows.typEf = ef
                        android.util.Log.i("QExt2Primary", "QEXT_EF_TYPICAL ef=$ef n=${o.optInt("n")}")
                        com.qext2.primary.util.RideFileLog.append("EF_TYPICAL ef=$ef n=${o.optInt("n")}")
                    } catch (e: Exception) {
                        android.util.Log.w("QExt2Primary", "QEXT_EF_TYPICAL parse_error msg=${e.message}")
                    }
                } else { android.util.Log.w("QExt2Primary", "QEXT_EF_TYPICAL failed status=$code"); com.qext2.primary.util.RideFileLog.append("EF_TYPICAL_FAIL status=$code") }
            }
        } catch (e: Exception) {
            android.util.Log.w("QExt2Primary", "QEXT_EF_TYPICAL crash msg=${e.message}")
        }
    }

    private fun karooHttpGet(
        url: String,
        headers: Map<String, String>,
        onDone: (Int, String?) -> Unit,
    ) {
        val system = _karooSystem
        if (system == null) {
            onDone(-1, null)
            return
        }
        var id: String? = null
        id = system.addConsumer<OnHttpResponse>(
            params = OnHttpResponse.MakeHttpRequest(
                method = "GET",
                url = url,
                headers = headers,
                body = null,
                waitForConnection = true,
            ),
            onError = { _ ->
                id?.let { system.removeConsumer(it) }
                onDone(-1, null)
            },
            onEvent = { resp ->
                val st = resp.state
                if (st is io.hammerhead.karooext.models.HttpResponseState.Complete) {
                    id?.let { system.removeConsumer(it) }
                    onDone(st.statusCode, st.body?.let { String(it) })
                }
            },
        )
    }

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
        val baseUrl = BuildConfig.QEXT_READINESS_URL.trim()
            .ifEmpty { "https://qbot.cytr.us/ride-readiness" }
        // Raport kasety dla serwera: stan przelacznika override + lista koronek.
        // Serwer zapisuje to per dzien (qbot_v2.qext2_cassette_report) - dzieki temu
        // wiadomo, ktora kaseta byla fizycznie zamontowana (AXS config bywa nieaktualny).
        val cassOvr = if (AthleteDataStore.loadCassetteOverrideEnabled()) "1" else "0"
        val cassCogs = AthleteDataStore.loadCassetteCogsRaw().replace(" ", "")
        // E3.3: odczyt GET z tokenem urzadzenia; zgloszenie kasety osobno (POST) -- GET niczego nie zapisuje
        val url = baseUrl
        val authHeaders = BuildConfig.QEXT_READINESS_TOKEN.takeIf { it.isNotBlank() }
            ?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
        if (!isRetry) postCassetteReport(system, baseUrl, authHeaders, cassOvr == "1", cassCogs)
        Log.i(TAG, "QEXT_READINESS_FETCH_START url=$url retry=$isRetry")
        fetchConsumerId = system.addConsumer<OnHttpResponse>(
            params = OnHttpResponse.MakeHttpRequest(method = "GET", url = url, headers = authHeaders, waitForConnection = true),
            onError = { msg ->
                Log.w(TAG, "QEXT_READINESS_FETCH_FAILED reason=onError msg=$msg")
                scheduleReadinessRetry(system, "onError")
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
                                todayFactor = AthleteData.clampTodayFactor(json.optDouble("todayFactor", 1.0).toFloat()),
                                ltpWatts = json.optInt("ltpWatts", 0),
                                // ctlXss: CTL wyrazone w XSS (fitmodel_daily.ctl_xss, ModelQ = kanon).
                                // NIE czytamy pola "ctl" jako fallbacku: ono pochodzi z Intervals.icu
                                // i jest liczone w TSS, wiec uzyte jako budzet XSS przywrociloby
                                // mieszanie skal (audyt pkt C1). 0 = brak -> budzet na stalej.
                                ctlXss = json.optDouble("ctlXss", 0.0).toFloat(),
                                atl = json.optDouble("atl", 40.0).toFloat(),
                                humidityPercent = json.optDouble("humidityPercent", 50.0).toFloat(),
                                sunsetTimestampMs = json.optLong("sunsetTimestampMs", json.optLong("twilightMs", json.optLong("sunsetMs", 0L))),
                                maxHr = json.optInt("maxHrBpm", json.optInt("MaxHRBPM", json.optInt("maxHr", json.optInt("maxHeartRate", 180)))),
                                lthrBpm = json.optInt("lthrBpm", 132),
                                // Serwer wysyla klucz "weightKg" -- alias konieczny, inaczej
                                // waga leciala na domyslne 75 kg i zanizala model zywienia.
                                bodyWeightKg = json.optDouble("bodyWeightKg", json.optDouble("weightKg", 75.0)).toFloat(),
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
                            json.optDouble("shortStopsMinPerKm", 0.0).takeIf { it > 0.0 && it < 5.0 }?.let {
                                AthleteDataStore.saveEtaStopsMinPerKm(it)
                                com.qext2.primary.eta.EtaSpeedTable.historyMinPerKm = it   // E4.4
                            }
                            val adjusted = data.applyBaroAdjustment(AthleteDataStore.loadBaroSensitive())
                            _aggregator?.updateAthleteData(adjusted)
                            AthleteDataStore.saveLastRefresh()
                            Log.i(TAG, "QEXT_READINESS_FETCH_SAVED source=$url wPrimeKj=${data.wPrimeKj} ltpWatts=${data.ltpWatts} ftpWatts=${data.ftp} factor=${adjusted.todayFactor}")
                        } catch (e: Exception) {
                            Log.w(TAG, "QEXT_READINESS_FETCH_FAILED reason=parse_error msg=${e.message}")
                            scheduleReadinessRetry(system, "parse_error")
                        }
                    } else {
                        Log.w(TAG, "QEXT_READINESS_FETCH_FAILED reason=http_status status=${s.statusCode} error=${s.error ?: "no body"}")
                        scheduleReadinessRetry(system, "http_${s.statusCode}")
                    }
                }
            }
        )
    }

    /** E5.4/A27: ponawianie pobrania danych zawodnika -- 2, 4, 6, 8, 10 min, potem do nastepnego pokazania pola. */
    private fun scheduleReadinessRetry(system: KarooSystemService, reason: String) {
        if (fetchAttempts >= 5) { com.qext2.primary.util.RideFileLog.append("READINESS_RETRY_GIVEUP reason=$reason"); return }
        fetchAttempts++
        val waitMs = 120_000L * fetchAttempts
        com.qext2.primary.util.RideFileLog.append("READINESS_RETRY n=$fetchAttempts in=${waitMs / 1000}s reason=$reason")
        serviceScope.launch { delay(waitMs); fetchAthleteData(system, isRetry = true) }
    }

    /** E3.3: zgloszenie kasety (stan przelacznika + koronki) jako POST, z tokenem; bledy nie wplywaja na odczyt. */
    private fun postCassetteReport(system: KarooSystemService, url: String, auth: Map<String, String>, override: Boolean, cogs: String) {
        try {
            val body = org.json.JSONObject().put("cassette_override", override).put("cassette_cogs", cogs).toString().toByteArray()
            var id: String? = null
            id = system.addConsumer<OnHttpResponse>(
                params = OnHttpResponse.MakeHttpRequest(method = "POST", url = url,
                    headers = auth + mapOf("Content-Type" to "application/json"), body = body, waitForConnection = true),
                onError = { msg -> id?.let { system.removeConsumer(it) }; Log.w(TAG, "QEXT_CASSETTE_POST_FAILED msg=$msg") },
                onEvent = { resp ->
                    val st = resp.state
                    if (st is HttpResponseState.Complete) { id?.let { system.removeConsumer(it) }; Log.i(TAG, "QEXT_CASSETTE_POST status=${st.statusCode}") }
                },
            )
        } catch (e: Exception) { Log.w(TAG, "QEXT_CASSETTE_POST_CRASH msg=${e.message}") }
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
        // Open-Meteo nie wymaga klucza (OpenWeather tylko jako zapas)
        if (weatherPollJob?.isActive == true) return
        weatherPollJob = serviceScope.launch {
            while (_aggregator != null) {
                _aggregator?.fetchWeatherIfNeeded()
                // 15 min = krok prognozy; po nieudanym pobraniu (np. brak polaczenia z telefonem) ponow za 1 min
                kotlinx.coroutines.delay(if (_aggregator?.weatherIsFresh() == true) 900_000L else 60_000L)
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
