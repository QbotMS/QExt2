package com.qext2.primary.engine

import android.util.Log
import com.qext2.primary.data.AthleteData
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.core.LabRideStateRepository
import com.qext2.primary.core.RideContext
import com.qext2.primary.active.PacingEngine
import com.qext2.primary.model.SurfaceType
import com.qext2.primary.engine.hrdecoupling.HrDecouplingBuffer
import com.qext2.primary.engine.hrdecoupling.HrSample
import com.qext2.primary.engine.hrdecoupling.HrStrainAdvisor
import com.qext2.primary.engine.hrdecoupling.HrStrainResult
import com.qext2.primary.model.PrimaryRideSnapshot
import com.qext2.primary.model.StatsRideSnapshot
import com.qext2.primary.util.QExt2DebugConfig
import com.qext2.primary.weather.WeatherClient
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import android.graphics.Color
import pl.qbot.karoo.core.FieldColor
import pl.qbot.karoo.core.RideSample

private const val TAG = "QExt2Agg"
private const val SLEEP_REFRESH_MIN_STOP_SEC = 90 * 60L
private const val RESERVE_PERSIST_INTERVAL_MS = 15_000L

data class KarooClimb(
    val index: Int,
    val startDistance: Double,
    val length: Double,
    val totalElevation: Double,
    val grade: Double,
)

class RideDataAggregator(private val karooSystem: KarooSystemService) {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private val hrRef = AtomicReference(0)
    private val cadenceRef = AtomicReference(0)
    private val powerRef = AtomicReference(0)
    private val speedRef = AtomicReference(0.0)
    private val gearFrontRef = AtomicReference(0)
    private val gearRearRef = AtomicReference(0)
    private val gradeRef = AtomicReference(0.0)
    private val filteredGradeRef = AtomicReference(0.0)
    private val gradeFilterInitializedRef = AtomicReference(false)
    private val distanceMetersRef = AtomicReference(0.0)
    private val elapsedSecRef = AtomicReference(0L)
    private val temperatureRef = AtomicReference(20f)
    private val distanceToDestinationMetersRef = AtomicReference(0.0)
    private val ascentDoneMRef = AtomicReference(0)
    private val ascentLeftMRef = AtomicReference(0)
    private val elevationRemainingReceivedRef = AtomicReference(false)
    private val elevationGainReceivedRef = AtomicReference(false)
    private val batteryPctRef = AtomicReference<Int?>(null)
    private val batteryChargingRef = AtomicReference<Boolean?>(null)
    private val rearDerailleurBatteryRef = AtomicReference<Int?>(null)
    private val weatherSourceReadyRef = AtomicReference(false)
    private val weatherFreshRef = AtomicReference(false)
    private val weatherTemperatureCRef = AtomicReference<Float?>(null)
    private val weatherWindSpeedMpsRef = AtomicReference<Float?>(null)
    private val weatherWindDirectionDegRef = AtomicReference<Int?>(null)
    private val weatherHumidityPctRef = AtomicReference<Int?>(null)
    private val weatherRain1hMmRef = AtomicReference<Float?>(null)
    private val weatherConditionRef = AtomicReference<String?>(null)
    private val weatherSourceRef = AtomicReference<String?>(null)
    private val sunsetTimestampRef = AtomicReference(0L)
    private val deadlineHourRef = AtomicReference(21)
    private val deadlineMinuteRef = AtomicReference(0)
    private val capTwilightRef = AtomicReference(false)
    private val cassetteOverrideRef = AtomicReference(false)
    private val cassetteCogsRef = AtomicReference(IntArray(0))
    // Nawierzchnia bieżącego segmentu — aktualizowana z cache QBot/RouteGraph fallback
    private val currentSurfaceRef = AtomicReference(SurfaceType.PAVED)
    // Adaptacyjny tryb AUTO i kontekst pacingu
    private val adaptiveTracker = com.qext2.primary.active.AdaptiveModeTracker()
    private val pacingContextRef = AtomicReference(com.qext2.primary.active.PacingContext(
        ceilingW = 9999, targetLowW = 0, targetHighW = 9999,
        isClimbing = false, modeFactor = 1.0f, surface = SurfaceType.PAVED,
        optCadenceLow = 70, optCadenceHigh = 90, isActive = false,
    ))
    private val civilDuskMsRef = AtomicReference(0L)
    private val maxHrRef = AtomicReference(180)
    private val todayFactorRef = AtomicReference(1.0f)
    private val modeFactorRef = AtomicReference(1.0f)
    private val baseLtpWattsRef = AtomicReference(0f)
    private val baseWPrimeKjRef = AtomicReference(0f)
    private val tssRef = AtomicReference(0f)
    private val kcalRef = AtomicReference(0)
    private val npRef = AtomicReference(0)
    private val ifRef = AtomicReference(0f)
    private val viRef = AtomicReference(0f)

    private val hrFreshnessRef = AtomicReference(0L)
    private val cadenceFreshnessRef = AtomicReference(0L)
    private val powerFreshnessRef = AtomicReference(0L)
    private val speedFreshnessRef = AtomicReference(0L)
    private val gearFreshnessRef = AtomicReference(0L)
    private val gradeFreshnessRef = AtomicReference(0L)
    private val gradeLastRawRef = AtomicReference(Double.NaN)
    private var hrAssessTick = 0
    private var hrResultCached: HrStrainResult? = null

    private val consumerIds = mutableListOf<String>()

    private val _snapshot = MutableStateFlow(PrimaryRideSnapshot())
    val snapshot: StateFlow<PrimaryRideSnapshot> = _snapshot
    private val _statsSnapshot = MutableStateFlow(StatsRideSnapshot())
    val statsSnapshot: StateFlow<StatsRideSnapshot> = _statsSnapshot

    private val statsCalc = StatsCalculator(ftpWatts = AthleteDataStore.load().ftp)
    private val hrBuffer = HrDecouplingBuffer()
    private val hrAdvisor = HrStrainAdvisor(hrBuffer)
    private val etaMovingSpeedHistory = ArrayDeque<Pair<Long, Float>>()
    private val lastEtaMsRef = AtomicReference(0L)
    private val lastDeadlineMsRef = AtomicReference(0L)
    private val carbNeededTotalGRef = AtomicReference(0.0)
    private val carbBalanceGRef = AtomicReference(0)
    private val carbLastElapsedSecRef = AtomicReference(0L)
    private val carbSessionInitializedRef = AtomicReference(false)
    private val wasMovingRef = AtomicReference(false)
    private val rideStartMsRef = AtomicReference(0L)
    private val karooElapsedReceivedRef = AtomicReference(false)
    private val rideStartWallMsRef = AtomicReference(0L)
    private val lastChosenElapsedRef = AtomicReference(0L)
    private val lastSdkElapsedRef = AtomicReference(0L)

    private val navRouteActiveRef = AtomicReference(false)
    private val navRouteNameRef = AtomicReference("")
    private val navRouteKeyRef = AtomicReference("")
    private val navClimbsRef = AtomicReference<List<KarooClimb>>(emptyList())
    private val movingElapsedSecRef = AtomicReference(0L)
    private val navLastUpdateMsRef = AtomicReference(0L)
    private val dailyTssBaseRef = AtomicReference(0f)
    private val sleepRefreshPendingRef = AtomicReference(false)
    private val stopStartedMsRef = AtomicReference(0L)
    private val wasActiveUntilMsRef = AtomicReference(0L)
    private val sessionTssRef = AtomicReference(0f)
    private val reservePersistLastMsRef = AtomicReference(0L)

    internal data class RouteStateDecision(
        val rawRoute: Boolean,
        val effectiveRoute: Boolean,
        val source: String,
    )

    internal data class ParsedElapsed(
        val chosenSec: Long,
        val asIsSec: Long,
        val asMsSec: Long,
        val unit: String,
    )

    internal data class TssLogDecision(
        val chosen: String,
        val source: String,
    )

    internal data class StartPlan(
        val stopBeforeStart: Boolean,
        val recreateScope: Boolean,
    )

    internal companion object {
        fun routeStateDecision(rawRoute: Boolean, lastRouteSeenMs: Long, nowMs: Long, graceMs: Long): RouteStateDecision {
            if (rawRoute) return RouteStateDecision(rawRoute = true, effectiveRoute = true, source = "NAV")
            val ago = if (lastRouteSeenMs <= 0L) Long.MAX_VALUE else nowMs - lastRouteSeenMs
            return if (ago in 0 until graceMs) {
                RouteStateDecision(rawRoute = false, effectiveRoute = true, source = "GRACE")
            } else {
                RouteStateDecision(rawRoute = false, effectiveRoute = false, source = "MISSING")
            }
        }

        fun parseElapsed(raw: Double, localGuessSec: Long): ParsedElapsed {
            val asIsSec = raw.toLong()
            val asMsSec = (raw / 1000.0).toLong()
            val chooseAsIs = kotlin.math.abs(asIsSec - localGuessSec) <= kotlin.math.abs(asMsSec - localGuessSec)
            val chosen = if (chooseAsIs) asIsSec else asMsSec
            val unit = if (chooseAsIs) "sec" else "ms"
            return ParsedElapsed(chosenSec = chosen, asIsSec = asIsSec, asMsSec = asMsSec, unit = unit)
        }

        fun isSdkElapsedPlausible(karooElapsedSec: Long, lastSdkElapsedSec: Long, localElapsedSec: Long, lastChosenElapsedSec: Long): Boolean {
            return karooElapsedSec > 0L &&
                karooElapsedSec - lastSdkElapsedSec in 0L..(localElapsedSec - lastChosenElapsedSec + 30L).coerceAtLeast(30L) &&
                karooElapsedSec <= localElapsedSec + 30L
        }

        fun tssLogDecision(sdkTss: Float): TssLogDecision {
            return if (sdkTss > 0f) {
                TssLogDecision(chosen = "%.1f".format(sdkTss), source = "SDK")
            } else {
                TssLogDecision(chosen = "--", source = "MISSING")
            }
        }

        fun resolveHasRoute(effectiveRoute: Boolean, distanceToDestinationMeters: Double): Boolean {
            return effectiveRoute || distanceToDestinationMeters > 0.0
        }

        fun planStart(hasConsumers: Boolean, tickActive: Boolean, scopeActive: Boolean): StartPlan {
            return StartPlan(
                stopBeforeStart = hasConsumers || tickActive,
                recreateScope = !scopeActive,
            )
        }
    }

    init {
        applyAthleteData(AthleteDataStore.load().applyBaroAdjustment(AthleteDataStore.loadBaroSensitive()), resetStats = false)
        statsCalc.captureStartReserve()
        val (h, m) = AthleteDataStore.loadDeadline()
        deadlineHourRef.set(h)
        deadlineMinuteRef.set(m)
        capTwilightRef.set(AthleteDataStore.loadCapTwilight())
        cassetteOverrideRef.set(AthleteDataStore.loadCassetteOverrideEnabled())
        cassetteCogsRef.set(AthleteDataStore.loadCassetteCogs())
    }

    fun startStreaming() {
        val startPlan = planStart(
            hasConsumers = consumerIds.isNotEmpty(),
            tickActive = tickJob?.isActive == true,
            scopeActive = scope.coroutineContext[Job]?.isActive == true,
        )
        if (startPlan.stopBeforeStart) {
            stopStreamingInternal("restart_before_start")
        }
        if (startPlan.recreateScope) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
        Log.i(TAG, "QEXT_AGGREGATOR_START scopeActive=${scope.coroutineContext[Job]?.isActive == true}")
        Log.d(TAG, "startStreaming: subscribing to streams")
        rideStartMsRef.set(System.currentTimeMillis())
        karooElapsedReceivedRef.set(false)
        rideStartWallMsRef.set(System.currentTimeMillis())
        elevationRemainingReceivedRef.set(false)
        elevationGainReceivedRef.set(false)
        movingElapsedSecRef.set(0L)
        PrimaryRideSnapshot.resetLegacyState()
        val (savedElapsed, savedDistance) = AthleteDataStore.loadElapsedSnapshot()
        val resume = savedElapsed > 0L &&
            AthleteDataStore.elapsedSnapshotAgeMs() < 6L * 60 * 60 * 1000
        if (savedElapsed > 0L && !resume) {
            Log.w(TAG, "QEXT_SNAPSHOT_STALE_IGNORED elapsed=${savedElapsed}s — clean start")
            AthleteDataStore.saveElapsedSnapshot(0L, 0.0)
        }
        if (resume) {
            rideStartWallMsRef.set(System.currentTimeMillis() - savedElapsed * 1000L)
            if (savedDistance > 0.0) distanceMetersRef.set(savedDistance)
            AthleteDataStore.loadStatsCalcSnapshot()?.let { raw ->
                try {
                    val parts = raw.split("|")
                    if (parts.getOrNull(0) != "v4") {
                        Log.w(TAG, "QEXT_SNAPSHOT_VERSION_MISMATCH got=${parts.getOrNull(0)} expected=v4 — ignoring")
                        return@let
                    }
                    if (parts.size >= 17) {
                        statsCalc.restoreFromSnapshot(StatsCalculator.StatsCalcSnapshot(
                            count4thPowers = parts[1].toLong(),
                            sumOf4thPowers = parts[2].toDouble(),
                            totalPowerSum = parts[3].toLong(),
                            totalPowerCount = parts[4].toLong(),
                            totalEnergyKj = parts[5].toDouble(),
                            lastMovingSec = parts[6].toLong(),
                            lastReserve = parts[7].toFloat(),
                            startReserve = parts[8].toFloat(),
                            wBalKj = parts[9].toFloat(),
                            batteryPctStart = parts.getOrNull(10)?.toIntOrNull()?.takeIf { it >= 0 },
                            batteryPctCurrent = parts.getOrNull(11)?.toIntOrNull()?.takeIf { it >= 0 },
                            batteryStartMs = parts.getOrNull(12)?.toLongOrNull()?.takeIf { it >= 0 },
                            batteryIsCharging = parts.getOrNull(13)?.takeIf { it != "null" }?.toBooleanStrictOrNull(),
                        ))
                        val savedRoute = parts.getOrNull(14)?.toBooleanStrictOrNull() ?: false
                        val savedDtd = parts[15].toDoubleOrNull() ?: 0.0
                        if (savedRoute) navRouteActiveRef.set(true)
                        if (savedDtd > 0.0) distanceToDestinationMetersRef.set(savedDtd)
                        val savedMoving = parts.getOrNull(16)?.toLongOrNull() ?: 0L
                        if (savedMoving > 0L) movingElapsedSecRef.set(savedMoving)
                        val savedCarb = parts.getOrNull(17)?.toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0
                        carbNeededTotalGRef.set(savedCarb)
                        statsCalc.resetBattery()
                    }
                } catch (_: Exception) {}
            }
            Log.i(TAG, "QEXT_ELAPSED_RESTORED elapsed=${savedElapsed}s distance=${"%.0f".format(savedDistance)}m")
        }
        sanitizeCarbIntake()
        if (!resume) {
            carbNeededTotalGRef.set(0.0)
            AthleteDataStore.resetCarbSessionState()
        }
        carbBalanceGRef.set(0)
        carbLastElapsedSecRef.set(sanitizeCarbElapsed(AthleteDataStore.loadCarbLastElapsedSec()))
        carbSessionInitializedRef.set(false)
        dailyTssBaseRef.set(AthleteDataStore.loadReserveDailyTssBase())
        val today = java.time.LocalDate.now().toString()
        val baseDate = AthleteDataStore.loadReserveDailyTssBaseDate()
        if (baseDate != today) {
            dailyTssBaseRef.set(0f)
            AthleteDataStore.saveReserveDailyTssBase(0f)
            AthleteDataStore.saveReserveDailyTssBaseDate(today)
            Log.i(TAG, "QEXT_RSRV_DAILY_RESET new_day stored=$baseDate today=$today")
        }
        if (dailyTssBaseRef.get() > 500f) {
            dailyTssBaseRef.set(0f)
            AthleteDataStore.saveReserveDailyTssBase(0f)
            Log.w(TAG, "QEXT_RSRV_CLEANUP corrupted dailyTssBase reset to 0")
        }
        sleepRefreshPendingRef.set(AthleteDataStore.loadSleepRefreshPending())
        stopStartedMsRef.set(0L)
        sessionTssRef.set(0f)
        reservePersistLastMsRef.set(0L)
        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.DISTANCE),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.values[DataType.Field.DISTANCE] as? Double
                            ?: s.dataPoint.singleValue
                        if (v != null) distanceMetersRef.set(v)
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.ELAPSED_TIME),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.singleValue
                            ?: (s.dataPoint.values[DataType.Field.ELAPSED_TIME] as? Double)
                        if (v != null) {
                            if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "ELAPSED_TIME raw=$v")
                            val localGuess = ((System.currentTimeMillis() - rideStartWallMsRef.get()) / 1000L).coerceAtLeast(1L)
                            val parsed = parseElapsed(v, localGuess)
                            if (QExt2DebugConfig.DEBUG_LOGGING || QExt2DebugConfig.DEBUG_ACTIVE_PRODUCER_DIAG) {
                                Log.i(TAG, "QEXT_TIME_RAW raw=$v asIs=${parsed.asIsSec}s asMs=${parsed.asMsSec}s local=$localGuess " +
                                    "chosen=${parsed.chosenSec} unit=${parsed.unit}")
                            }
                            elapsedSecRef.set(parsed.chosenSec)
                            karooElapsedReceivedRef.set(true)
                        } else {
                            if (QExt2DebugConfig.DEBUG_LOGGING) Log.w(TAG, "ELAPSED_TIME null value single=${s.dataPoint.singleValue} values=${s.dataPoint.values}")
                        }
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.TEMPERATURE),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.singleValue
                        if (v != null) temperatureRef.set(v.toFloat())
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.DISTANCE_TO_DESTINATION),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = (s.dataPoint.values[DataType.Field.DISTANCE_TO_DESTINATION] as? Double)
                        if (v != null && v >= 0.0) {
                            distanceToDestinationMetersRef.set(v)
                        }
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.ELEVATION_REMAINING),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = (s.dataPoint.values[DataType.Field.ASCENT_REMAINING] as? Double)
                        if (v != null) {
                            ascentLeftMRef.set(v.toInt())
                            elevationRemainingReceivedRef.set(true)
                        }
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.ELEVATION_GAIN),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.values.values.filterIsInstance<Double>().firstOrNull { it >= 0.0 }
                        if (v != null) {
                            ascentDoneMRef.set(v.toInt())
                            elevationGainReceivedRef.set(true)
                        }
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.HEART_RATE),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.singleValue
                        if (v != null) {
                            hrRef.set(v.toInt())
                            hrFreshnessRef.set(System.currentTimeMillis())
                            if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "HR=$v")
                        }
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.CADENCE),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.singleValue
                        if (v != null) {
                            cadenceRef.set(v.toInt())
                            cadenceFreshnessRef.set(System.currentTimeMillis())
                            if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "CAD=$v")
                        }
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.SMOOTHED_3S_AVERAGE_POWER),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "PWR_3S values=${s.dataPoint.values}")
                        val v = s.dataPoint.values[DataType.Field.SMOOTHED_3S_AVERAGE_POWER] as? Double
                            ?: s.dataPoint.singleValue
                        if (v != null) {
                            updatePower(v, "PWR_3S")
                        }
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.SPEED),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.singleValue
                        if (v != null) {
                            speedRef.set(v * 3.6)
                            speedFreshnessRef.set(System.currentTimeMillis())
                            if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "SPD=${v * 3.6}")
                        }
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.SHIFTING_GEARS),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val values = s.dataPoint.values
                        val front = (values[DataType.Field.SHIFTING_FRONT_GEAR_TEETH] as? Double)?.toInt()
                            ?: (values[DataType.Field.SHIFTING_FRONT_GEAR] as? Double)?.toInt() ?: 0
                        val rearTeethReported = (values[DataType.Field.SHIFTING_REAR_GEAR_TEETH] as? Double)?.toInt() ?: 0
                        val rearPos = (values[DataType.Field.SHIFTING_REAR_GEAR] as? Double)?.toInt() ?: 0
                        val rear = resolveRearTeeth(rearPos, rearTeethReported)
                        val rearBattery = listOf(
                            "FIELD_REAR_DERAILLEUR_BATTERY_ID",
                            "FIELD_SHIFTING_REAR_BATTERY_ID",
                            "FIELD_REAR_BATTERY_ID",
                            "FIELD_REAR_DERAILLEUR_BATTERY_PERCENT_ID"
                        ).firstNotNullOfOrNull { key ->
                            (values[key] as? Double)?.toInt()
                        }
                        gearFrontRef.set(front)
                        gearRearRef.set(rear)
                        if (rearBattery != null) rearDerailleurBatteryRef.set(rearBattery.coerceIn(0, 100))
                        gearFreshnessRef.set(System.currentTimeMillis())
                        if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "GEAR=${front}x${rear} (pos=$rearPos axsTeeth=$rearTeethReported ovr=${cassetteOverrideRef.get()})")
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.ELEVATION_GRADE),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.singleValue
                        if (v != null && v in -35.0..35.0) {
                            gradeRef.set(v)
                            val filtered = if (gradeFilterInitializedRef.get()) {
                                filteredGradeRef.get() + 1.0 * (v - filteredGradeRef.get())
                            } else {
                                gradeFilterInitializedRef.set(true)
                                v
                            }
                            filteredGradeRef.set(filtered)
                            if (v != gradeLastRawRef.get()) {
                                gradeFreshnessRef.set(System.currentTimeMillis())
                                gradeLastRawRef.set(v)
                            }
                            if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "GRADE raw=$v")
                        }
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.TRAINING_STRESS_SCORE),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.values[DataType.Field.TRAINING_STRESS_SCORE] as? Double
                        if (v != null) tssRef.set(v.toFloat())
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.CALORIES),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.values[DataType.Field.CALORIES] as? Double
                        if (v != null) kcalRef.set(v.toInt())
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.CIVIL_DUSK),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.singleValue
                            ?: (s.dataPoint.values[DataType.Field.CIVIL_DUSK] as? Double)
                        if (v != null) civilDuskMsRef.set(v.toLong())
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.NORMALIZED_POWER),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.values[DataType.Field.NORMALIZED_POWER] as? Double
                        if (v != null) npRef.set(v.toInt())
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.INTENSITY_FACTOR),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.values[DataType.Field.INTENSITY_FACTOR] as? Double
                        if (v != null) ifRef.set(v.toFloat())
                    }
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnLocationChanged>(
                onEvent = { event ->
                    val now = System.currentTimeMillis()
                    if (now - lastLocationSaveMs.get() < 30_000L) return@addConsumer
                    lastLocationSaveMs.set(now)
                    AthleteDataStore.saveLocation(event.lat, event.lng)
                }
            )
        )

        consumerIds.add(
            karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.VARIABILITY_INDEX),
                onEvent = { event ->
                    val s = event.state
                    if (s is StreamState.Streaming) {
                        val v = s.dataPoint.singleValue
                            ?: (s.dataPoint.values[DataType.Field.VARIABILITY_INDEX] as? Double)
                        if (v != null) viRef.set(v.toFloat())
                    }
                }
            )
        )

        Log.i(TAG, "QEXT_NAV_CONSUMER_START")
        val navConsumerId = try {
            karooSystem.addConsumer<OnNavigationState>(
                onEvent = { event ->
                    try {
                    val ns = event.state
                    val now = System.currentTimeMillis()
                    navLastUpdateMsRef.set(now)
                    when (ns) {
                        is OnNavigationState.NavigationState.Idle -> {
                            navRouteActiveRef.set(false)
                            navRouteNameRef.set("")
                            navRouteKeyRef.set("")
                            navClimbsRef.set(emptyList())
                            currentSurfaceRef.set(com.qext2.primary.model.SurfaceType.PAVED)
                            com.qext2.primary.surface.SurfaceBridge.onNavigationState(event, null)
                            Log.i(TAG, "QEXT_NAV_STATE type=Idle name= routeDistance=-- climbs=0")
                        }
                        is OnNavigationState.NavigationState.NavigatingRoute -> {
                            navRouteActiveRef.set(true)
                            navRouteNameRef.set(ns.name ?: "")
                            val routeKey = "route:${ns.name ?: ""}|dist=${"%.0f".format(ns.routeDistance)}"
                            navRouteKeyRef.set(routeKey)
                            val parsed = ns.climbs.mapIndexed { idx, c ->
                                KarooClimb(
                                    index = idx,
                                    startDistance = c.startDistance,
                                    length = c.length,
                                    totalElevation = c.totalElevation,
                                    grade = c.grade,
                                )
                            }
                            navClimbsRef.set(parsed)
                            Log.i(TAG, "QEXT_NAV_STATE type=NavigatingRoute name=${ns.name ?: ""} routeDistance=${"%.0f".format(ns.routeDistance)} climbs=${parsed.size}")
                            for (c in parsed) {
                                Log.i(TAG, "QEXT_ROUTE_CLIMB index=${c.index} start=${c.startDistance} len=${c.length} elev=${c.totalElevation} grade=${c.grade}%")
                            }
                            com.qext2.primary.surface.SurfaceBridge.onNavigationState(event, ns.name)
                        }
                        is OnNavigationState.NavigationState.NavigatingToDestination -> {
                            navRouteActiveRef.set(true)
                            val destName = ns.destination.name ?: ""
                            navRouteNameRef.set(destName)
                            val routeKey = "destination:${ns.destination.id}|name=$destName"
                            navRouteKeyRef.set(routeKey)
                            val parsed = ns.climbs.mapIndexed { idx, c ->
                                KarooClimb(
                                    index = idx,
                                    startDistance = c.startDistance,
                                    length = c.length,
                                    totalElevation = c.totalElevation,
                                    grade = c.grade,
                                )
                            }
                            navClimbsRef.set(parsed)
                            Log.i(TAG, "QEXT_NAV_STATE type=NavigatingToDestination name=$destName routeDistance=-- climbs=${parsed.size}")
                            for (c in parsed) {
                                Log.i(TAG, "QEXT_ROUTE_CLIMB index=${c.index} start=${c.startDistance} len=${c.length} elev=${c.totalElevation} grade=${c.grade}%")
                            }
                            com.qext2.primary.surface.SurfaceBridge.onNavigationState(event, destName)
                        }
                    }
                    } catch (e: Exception) {
                        Log.w(TAG, "QEXT_NAV_CALLBACK_CRASH msg=${e.message}", e)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "QEXT_NAV_CONSUMER_ERROR error=${e.message}")
            null
        }
        if (navConsumerId != null) {
            consumerIds.add(navConsumerId)
            Log.i(TAG, "QEXT_NAV_CONSUMER_OK id=$navConsumerId")
        }

        // Fallback nawierzchni z RouteGraph (miękka zależność — działa tylko jeśli zainstalowany)
        try {
            val rgConsumerId = karooSystem.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(
                    DataType.dataTypeId("karoo-routegraph", "surfacetype")
                ),
                onEvent = { event ->
                    val v = (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                    if (v != null) {
                        com.qext2.primary.surface.SurfaceBridge.onRouteGraphSurface(v.toFloat())
                        if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "ROUTEGRAPH_SURFACE value=$v")
                    }
                }
            )
            if (rgConsumerId != null) consumerIds.add(rgConsumerId)
        } catch (e: Exception) {
            Log.d(TAG, "RouteGraph surfacetype stream unavailable: ${e.message}")
        }

        tickJob = scope.launch {
            while (isActive) {
                delay(1000)
                try {
                val now = System.currentTimeMillis()

                val speedKmh = speedRef.get()
                val karooElapsedSec = elapsedSecRef.get()
                val localElapsedSec = ((now - rideStartWallMsRef.get()) / 1000L).coerceAtLeast(0L)
                val lastChosen = lastChosenElapsedRef.get()
                val lastSdk = lastSdkElapsedRef.get()

                val sdkPlausible = isSdkElapsedPlausible(karooElapsedSec, lastSdk, localElapsedSec, lastChosen)
                val elapsedSec = if (sdkPlausible) karooElapsedSec else localElapsedSec
                lastChosenElapsedRef.set(elapsedSec)
                if (karooElapsedSec > 0L) lastSdkElapsedRef.set(karooElapsedSec)
                if (elapsedSec % 5 == 0L && elapsedSec > 0L) {
                    AthleteDataStore.saveElapsedSnapshot(elapsedSec, distanceMetersRef.get())
                    val snap = statsCalc.snapshotForCrashRecovery()
                    AthleteDataStore.saveStatsCalcSnapshot(
                        "v4|${snap.count4thPowers}|${snap.sumOf4thPowers}|${snap.totalPowerSum}|${snap.totalPowerCount}|${snap.totalEnergyKj}|${snap.lastMovingSec}|${snap.lastReserve}|${snap.startReserve}|${snap.wBalKj}|${snap.batteryPctStart ?: -1}|${snap.batteryPctCurrent ?: -1}|${snap.batteryStartMs ?: -1L}|${snap.batteryIsCharging ?: "null"}|${navRouteActiveRef.get()}|${distanceToDestinationMetersRef.get()}|${movingElapsedSecRef.get()}|${carbNeededTotalGRef.get()}"
                    )
                }

                logTimeState(now, karooElapsedSec, localElapsedSec, elapsedSec, sdkPlausible)

                val fakeMode = QExt2DebugConfig.DEBUG_FAKE_RIDE_MODE
                if (fakeMode) applyFakeRideData(now, elapsedSec)

                hrBuffer.add(HrSample(
                    timestampMs = now,
                    hr = hrRef.get(),
                    power = powerRef.get(),
                    cadence = cadenceRef.get(),
                    speedKmh = speedKmh,
                    elapsedSec = elapsedSec,
                ))

                val hrResult = run {
                    hrAssessTick++
                    if (hrAssessTick % 10 == 0 || hrResultCached == null) {
                        hrAdvisor.assess(now, maxHrRef.get()).also { hrResultCached = it }
                    } else hrResultCached!!
                }

                val rideCtx = RideContext(
                    surface = currentSurfaceRef.get(),
                    decouplingPct = statsCalc.decouplingPercent(),
                    effectiveLtp = getEffectiveLtpWatts(),
                    todayFactor = AthleteDataStore.load().todayFactor,
                )
                val outputs = LabRideStateRepository.update(
                    RideSample(
                        tSec = elapsedSec.toDouble(),
                        speedKmh = speedKmh,
                        powerW = powerRef.get().toDouble(),
                        hrBpm = hrRef.get().toDouble(),
                        cadenceRpm = cadenceRef.get().toDouble(),
                        altitudeM = null,
                        distanceM = distanceMetersRef.get(),
                        gradePct = gradeRef.get(),
                        gearFront = gearFrontRef.get().takeIf { it > 0 },
                        gearRear = gearRearRef.get().takeIf { it > 0 },
                        batteryHeadunitPct = batteryPctRef.get()?.toDouble(),
                        batterySensorsPct = rearDerailleurBatteryRef.get()?.toDouble(),
                    ),
                    rideCtx
                )
                val speedOut = outputs["SPEED"]
                val powerOut = outputs["POWER"]
                val hrOut = outputs["HR"]
                val cadOut = outputs["CADENCE"]
                val gradeOut = outputs["GRADE"]
                val gearOut = outputs["GEAR"]
                val gradeBg = PrimaryRideSnapshot.gradeBackground(filteredGradeRef.get())
                val remainingKmColor = distanceToDestinationMetersRef.get() / 1000.0
                val speedKmhColor = speedRef.get()
                val remainingHoursColor = if (speedKmhColor > 5.0 && remainingKmColor > 1.0)
                    (remainingKmColor / speedKmhColor).toFloat() else -1f
                _snapshot.value = PrimaryRideSnapshot(
                    hr = hrRef.get(),
                    cadence = cadenceRef.get(),
                    power3s = powerRef.get(),
                    speedKmh = speedKmh,
                    gearFront = gearFrontRef.get(),
                    gearRear = gearRearRef.get(),
                    gradePercent = filteredGradeRef.get(),
                    hrFreshnessMs = now - hrFreshnessRef.get(),
                    cadenceFreshnessMs = now - cadenceFreshnessRef.get(),
                    powerFreshnessMs = now - powerFreshnessRef.get(),
                    speedFreshnessMs = now - speedFreshnessRef.get(),
                    gearFreshnessMs = now - gearFreshnessRef.get(),
                    gradeFreshnessMs = now - gradeFreshnessRef.get(),
                    powerColor = pacingPowerColor(
                        power = powerRef.get(),
                        effectiveLtp = getEffectiveLtpWatts(),
                        wBalancePct = statsCalc.wBalancePercent(now),
                        reserve = _statsSnapshot.value.rideReservePercent,
                        elapsedHours = elapsedSec.toFloat() / 3600f,
                        remainingHours = remainingHoursColor,
                        decouplingPct = statsCalc.decouplingPercent(),
                        hasDecoupling = statsCalc.hasDecouplingData(),
                        powerAgeMs = now - powerFreshnessRef.get(),
                    ),
                    hrColor = hrResult.color.hex,
                    cadenceColor = cadOut?.color.toAndroidColor(),
                    speedColor = speedOut?.color.toAndroidColor(),
                    gradeColor = PrimaryRideSnapshot.contrastText(gradeBg),
                    gradeBgColor = gradeBg,
                    gearColor = gearOut?.color.toAndroidColor(),
                    powerBgColor = when (com.qext2.primary.active.PacingEngine.assessPower(powerRef.get(), pacingContextRef.get())) {
                        com.qext2.primary.active.PacingEngine.PowerStatus.DANGER  -> 0x40FF4444.toInt()
                        com.qext2.primary.active.PacingEngine.PowerStatus.WARN    -> 0x40FF8C00.toInt()
                        com.qext2.primary.active.PacingEngine.PowerStatus.OPTIMAL -> 0x4044AA44.toInt()
                        else -> 0  // Color.TRANSPARENT
                    },
                    speedValue = speedOut?.value ?: "WAIT",
                    powerValue = powerOut?.value ?: "WAIT",
                    hrValue = hrOut?.value ?: "WAIT",
                    cadenceValue = cadOut?.value ?: "WAIT",
                    gradeValue = gradeOut?.value ?: "WAIT",
                    gearValue = gearOut?.value ?: "WAIT",
                    maxHr = maxHrRef.get(),
                )

                val powerWatts = powerRef.get()
                val hr = hrRef.get()
                val cadence = cadenceRef.get()
                val movingElapsedSec = movingElapsedSecRef.get()
                val powerFresh = now - powerFreshnessRef.get() < 8_000L
                // W' physics runs on BASE LTP/W' (real physiology). The combined
                // factor (temp/fatigue/mode) applies only to the pacing layer
                // (power color ceiling, climb targets) — not to depletion math.
                statsCalc.update(powerWatts, hr, movingElapsedSec, elapsedSec, powerFresh = powerFresh)
                val npWhole = statsCalc.npWatts()
                val ifWhole = ifRef.get().takeIf { it > 0f } ?: statsCalc.ifValue()
                val adjFtp = (statsCalc.ftpWatts * todayFactorRef.get().coerceIn(0.5f, 1.1f)).toInt().coerceAtLeast(50)
                val adjIf = if (adjFtp > 0 && npWhole > 0) (npWhole.toFloat() / adjFtp).coerceAtMost(2.0f) else 0f
                val vi = statsCalc.viValue()
                val sessionTss = tssRef.get().takeIf { it > 0f } ?: statsCalc.tssValue(movingElapsedSec)
                var sessionTssForReserve = sessionTss
                sessionTssRef.set(sessionTssForReserve)
                val decoupling = statsCalc.decouplingPercent()
                var decouplingForReserve = decoupling
                val wBalance = statsCalc.wBalancePercent(now)
                val carbs = statsCalc.carbsGPerH(adjIf, movingElapsedSec, vi, physioTempC(), statsCalc.bodyWeightKg)
                val fluid = statsCalc.fluidLPerH(adjIf, physioTempC())
                initCarbSession(elapsedSec)
                val dtSec = computeCarbDtSec(elapsedSec)

                val wasMoving = wasMovingRef.get()
                val isMoving = computeIsMoving(speedKmh)
                wasMovingRef.set(isMoving)
                if (isMoving) movingElapsedSecRef.set(movingElapsedSecRef.get() + 1L)

                if (isMoving) {
                    stopStartedMsRef.set(0L)
                } else if (stopStartedMsRef.get() == 0L) {
                    stopStartedMsRef.set(now)
                }
                val stopDurationSec = stopStartedMsRef.get()
                    .takeIf { it > 0L }
                    ?.let { ((now - it).coerceAtLeast(0L)) / 1000L }
                    ?: 0L

                if (ReservePolicy.shouldApplySleepRefresh(
                        sleepRefreshPending = sleepRefreshPendingRef.get(),
                        isMoving = isMoving,
                        elapsedSec = elapsedSec,
                        stopDurationSec = stopDurationSec,
                        minStopForRefreshSec = SLEEP_REFRESH_MIN_STOP_SEC,
                    )
                ) {
                    dailyTssBaseRef.set(0f)
                    AthleteDataStore.saveReserveDailyTssBase(0f)
                    sessionTssRef.set(0f)
                    sessionTssForReserve = 0f
                    AthleteDataStore.consumeSleepRefreshPending()
                    sleepRefreshPendingRef.set(false)
                    statsCalc.reset()
                    statsCalc.captureStartReserve()
                    decouplingForReserve = 0f
                    Log.i(TAG, "RSRV sleep refresh applied marker=${AthleteDataStore.loadSleepDataDateMarker()} stop=${stopDurationSec}s")
                }

                val effectiveTss = ReservePolicy.effectiveTss(dailyTssBaseRef.get(), sessionTssForReserve)
                maybePersistReserveBase(effectiveTss, now)
                val reserve = statsCalc.rideReservePercent(effectiveTss, ifWhole, decouplingForReserve, elapsedSec)

                accumulateCarbs(now, elapsedSec, isMoving, dtSec, carbs)
                val remainingMeters = distanceToDestinationMetersRef.get().coerceAtLeast(0.0)
                val hasRoute = resolveHasRoute(getEffectiveRoute(), remainingMeters)
                // Aktualizacja nawierzchni z cache (pozycja km wzdłuż trasy)
                val kmAlongRoute = (distanceMetersRef.get() / 1000.0).toFloat().coerceAtLeast(0f)
                val freshSurface = com.qext2.primary.surface.SurfaceBridge.currentSurface(kmAlongRoute)
                    ?: com.qext2.primary.model.SurfaceType.PAVED
                currentSurfaceRef.set(freshSurface)
                fuelProducer.tick(carbs, fluid, isMoving)
                // Fuel reminders (jedz/pij/sod) tylko przy aktywnej trasie.
                // Treningi/komutingi bez trasy -> bez przypomnien (mniej smietnika).
                if (hasRoute) {
                    fuelProducer.checkAndProduce(physioTempC(), AthleteDataStore.loadCarbPacketSize(), now)
                        ?.let { pendingFuelMsgRef.set(it) }
                }

                // AUTO adaptacyjne: aktualizuj modeFactor z rolling window 20 min
                val athleteData = AthleteDataStore.load()
                val ridingModeCode = AthleteDataStore.loadRidingMode().toInt()
                val effectiveModeFactor = if (ridingModeCode == 3) {
                    // AUTO — adaptacyjne
                    adaptiveTracker.update(now, powerRef.get(), getEffectiveLtpWatts())
                } else {
                    // Fixowany tryb — nie dotykamy
                    when (ridingModeCode) { 1 -> 0.88f; 2 -> 1.12f; else -> 1.00f }
                }

                // Pacing context — produkowany co sekundę
                val pacingCtx = PacingEngine.compute(
                    powerW = powerRef.get(),
                    effectiveLtp = getEffectiveLtpWatts(),
                    effectiveFtp = (athleteData.ftp * athleteData.todayFactor).coerceAtLeast(50f),
                    wBalancePct = statsCalc.wBalancePercent(now).toFloat(),
                    reservePct = _statsSnapshot.value.rideReservePercent.toFloat(),
                    decouplingPct = statsCalc.decouplingPercent(),
                    windSpeedMps = weatherWindSpeedMpsRef.get() ?: 0f,
                    isClimbing = gradeRef.get() > 2.5,
                    gradePercent = gradeRef.get(),
                    surface = currentSurfaceRef.get(),
                    todayFactor = athleteData.todayFactor,
                    modeFactor = effectiveModeFactor,
                )
                pacingContextRef.set(pacingCtx)
                AthleteDataStore.saveCarbLastElapsedSec(elapsedSec)
                val carbIntakeTotal = AthleteDataStore.loadCarbIntakeTotal()
                val carbNeededTotal = carbNeededTotalGRef.get().roundToInt().coerceAtLeast(0)
                val carbBalance = carbIntakeTotal - carbNeededTotal
                carbBalanceGRef.set(carbBalance)
                logCarbTelemetry(now, isMoving, dtSec, carbs, carbIntakeTotal, carbNeededTotal, carbBalance)
                val speedKmhNow = speedRef.get().coerceAtLeast(0.0)
                val routeClimbSourceReady = navRouteActiveRef.get()

                logFieldDiagnostics(now, elapsedSec, carbs, carbIntakeTotal, carbNeededTotal, carbBalance,
                    wBalance, tssRef.get(), reserve, speedKmhNow, hasRoute,
                    ascentDoneMRef.get(), ascentLeftMRef.get(), remainingMeters)
                if (QExt2DebugConfig.DEBUG_ACTIVE_PRODUCER_DIAG) {
                    val localTss = sessionTss
                    val sdkTss = tssRef.get()
                    val tssDecision = tssLogDecision(sdkTss)
                    Log.i(TAG, "QEXT_TSS_SOURCE sdk=${"%.1f".format(sdkTss)} local=${"%.1f".format(localTss)} " +
                            "chosen=${tssDecision.chosen} source=${tssDecision.source}")
                }

                val remainingKm = (remainingMeters / 1000.0).toFloat()
                val distanceKm = (distanceMetersRef.get() / 1000.0).toFloat()

                if (isMoving && speedKmhNow >= 1f) {
                    etaMovingSpeedHistory.addLast(now to speedKmhNow.toFloat())
                }
                val movingWindowMs = 30 * 60_000L
                while (etaMovingSpeedHistory.isNotEmpty() && etaMovingSpeedHistory.first().first < now - movingWindowMs) {
                    etaMovingSpeedHistory.removeFirst()
                }
                val movingAvgKph = if (etaMovingSpeedHistory.isNotEmpty()) {
                    etaMovingSpeedHistory.map { it.second }.average().toFloat()
                } else 0f
                val etaMs = if (hasRoute && remainingKm > 0f && movingAvgKph > 0f) {
                    (now + (remainingKm / movingAvgKph * 3600_000L).toLong()).coerceAtLeast(now + 60_000L)
                } else 0L
                lastEtaMsRef.set(etaMs)
                val deadlineTs = resolveDeadlineMs(now)
                lastDeadlineMsRef.set(deadlineTs)
                statsCalc.updateBattery(batteryPctRef.get(), batteryChargingRef.get(), now)
                val drainPerHour = statsCalc.batteryDrainPctPerHour(now)
                val batteryPctNow = batteryPctRef.get()
                val batterySourceReady = batteryPctNow != null
                val batteryDrainReady = batterySourceReady && drainPerHour != null && drainPerHour.isFinite() && drainPerHour >= 0f
                val batteryLeftSec = if (drainPerHour != null && batteryPctNow != null && drainPerHour > 0f) {
                    ((batteryPctNow / drainPerHour) * 3600f).toLong().coerceAtLeast(0L)
                } else null
                val batteryEstimateReady = batterySourceReady && batteryLeftSec != null && batteryLeftSec > 0L
                val hasActivity = isMoving
                val wPrimeModelReady = wBalance >= 0
                val etaModelReady = hasRoute && etaMs > 0L
                val rsrvModelReady = hasActivity && reserve >= 0 && reserve <= 100
                val carbModelReady = npRef.get() > 0 && elapsedSec > 60L
                val fluidModelReady = elapsedSec > 60L && hasActivity
                _statsSnapshot.value = StatsRideSnapshot(
                    npWholeWatts = npRef.get(),
                    ifWholeRide = ifRef.get(),
                    viValue = viRef.get(),
                    tssValue = tssRef.get(),
                    caloriesKcal = kcalRef.get(),
                    carbsGPerH = carbs,
                    carbBalanceG = carbBalance,
                    fluidLPerH = fluid,
                    rideReservePercent = reserve,
                    wBalancePercent = wBalance,
                    wBalanceTrend = statsCalc.wBalanceTrend(),
                    etaTimestamp = etaMs,
                    ascentDoneM = ascentDoneMRef.get(),
                    ascentLeftM = ascentLeftMRef.get(),
                    hasRoute = hasRoute,
                    routeClimbSourceReady = routeClimbSourceReady,
                    batterySourceReady = batterySourceReady,
                    batteryDrainReady = batteryDrainReady,
                    batteryEstimateReady = batteryEstimateReady,
                    wPrimeModelReady = wPrimeModelReady,
                    etaModelReady = etaModelReady,
                    rsrvModelReady = rsrvModelReady,
                    carbModelReady = carbModelReady,
                    fluidModelReady = fluidModelReady,
                    weatherFresh = weatherFreshRef.get(),
                    weatherTemperatureC = weatherTemperatureCRef.get(),
                    weatherWindSpeedMps = weatherWindSpeedMpsRef.get(),
                    weatherRain1hMm = weatherRain1hMmRef.get(),
                    weatherCondition = weatherConditionRef.get(),
                    batterySource = if (batterySourceReady) "headunit_polling" else null,
                    batteryDrainPctPerHour = drainPerHour,
                    batteryTimeLeftSec = batteryLeftSec,
                    grossElapsedSec = elapsedSec,
                    distanceKm = (distanceMetersRef.get() / 1000.0).toFloat(),
                )
                if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "HR_DECOUPLING reason=${hrResult.reasonCode} decouplingPct=${hrResult.decouplingPct} color=${hrResult.color}")
                } catch (e: Exception) {
                    Log.w(TAG, "QEXT_TICK_CRASH msg=${e.message}", e)
                }
            }
        }
    }

    fun stopStreaming() {
        stopStreamingInternal("stop_streaming")
    }

    /** Soft stop for field-visibility gating: detach streams + tick to save battery,
     *  but PRESERVE all in-memory session state (W', RSRV, TSS, carbs, HR buffer)
     *  and keep the periodic elapsed snapshot so mid-ride re-show resumes seamlessly. */
    fun stopStreamingSoft() {
        Log.i(TAG, "QEXT_AGGREGATOR_SOFT_STOP visibility")
        consumerIds.forEach { id -> karooSystem.removeConsumer(id) }
        consumerIds.clear()
        tickJob?.cancel()
        tickJob = null
    }

    private fun stopStreamingInternal(reason: String) {
        Log.i(TAG, "QEXT_AGGREGATOR_STOP reason=$reason")
        AthleteDataStore.saveElapsedSnapshot(0L, 0.0)
        Log.d(TAG, "QEXT_NAV_CONSUMER_STOP")
        Log.d(TAG, "stopStreaming: removing ${consumerIds.size} consumers")
        val committedDailyTss = ReservePolicy.effectiveTss(dailyTssBaseRef.get(), sessionTssRef.get())
        AthleteDataStore.saveReserveDailyTssBase(committedDailyTss)
        AthleteDataStore.saveReserveDailyTssBaseDate(java.time.LocalDate.now().toString())
        dailyTssBaseRef.set(committedDailyTss)
        consumerIds.forEach { id -> karooSystem.removeConsumer(id) }
        consumerIds.clear()
        tickJob?.cancel()
        tickJob = null
        carbLastElapsedSecRef.set(0L)
        carbSessionInitializedRef.set(false)
        AthleteDataStore.resetCarbSessionState()
        sessionTssRef.set(0f)
        reservePersistLastMsRef.set(0L)
        statsCalc.reset()
        fuelProducer.reset()
        pendingFuelMsgRef.set(null)
        adaptiveTracker.reset()
        hrBuffer.clear()
        hrAdvisor.reset()
        etaMovingSpeedHistory.clear()
    }

    fun updateAthleteData(data: AthleteData) {
        applyAthleteData(data, resetStats = false)
        val (h, m) = AthleteDataStore.loadDeadline()
        deadlineHourRef.set(h)
        deadlineMinuteRef.set(m)
    }

    fun updateBatteryStatus(percent: Int?, charging: Boolean?) {
        batteryPctRef.set(percent)
        batteryChargingRef.set(charging)
        if (percent != null) statsCalc.seedBatteryStartIfAbsent(percent, charging, System.currentTimeMillis())
    }

    fun updateWeather(data: com.qext2.primary.weather.WeatherData) {
        weatherSourceReadyRef.set(true)
        weatherFreshRef.set(WeatherClient.isFresh(data))
        weatherTemperatureCRef.set(data.temperatureC)
        weatherWindSpeedMpsRef.set(data.windSpeedMps)
        weatherWindDirectionDegRef.set(data.windDirectionDeg)
        weatherHumidityPctRef.set(data.humidityPct)
        weatherRain1hMmRef.set(data.rain1hMm)
        weatherConditionRef.set(data.condition)
        weatherSourceRef.set(data.source)
    }

    suspend fun fetchWeatherIfNeeded() {
        if (!WeatherClient.isKeyConfigured()) return
        val lat = AthleteDataStore.loadLocationLat() ?: return
        val lon = AthleteDataStore.loadLocationLon() ?: return
        val data = WeatherClient.fetch(karooSystem, lat, lon)
        if (data != null) updateWeather(data)
    }

    fun refreshDeadlineFromStore() {
        val (h, m) = AthleteDataStore.loadDeadline()
        deadlineHourRef.set(h)
        deadlineMinuteRef.set(m)
    }

    fun refreshCapTwilightFromStore() {
        capTwilightRef.set(AthleteDataStore.loadCapTwilight())
    }

    fun getPacingContext(): com.qext2.primary.active.PacingContext = pacingContextRef.get()

    fun updateSurface(surface: SurfaceType) {
        currentSurfaceRef.set(surface)
    }

    fun getCurrentSurface(): SurfaceType = currentSurfaceRef.get()

    fun refreshCassetteOverride() {
        cassetteOverrideRef.set(AthleteDataStore.loadCassetteOverrideEnabled())
        cassetteCogsRef.set(AthleteDataStore.loadCassetteCogs())
    }

    /**
     * Rozwiazuje liczbe zebow tylnej koronki.
     * Gdy override wlaczony i pozycja biegu (SHIFTING_REAR_GEAR, 1..N) miesci sie
     * w liscie custom kasety -> zwraca koronke z listy. W przeciwnym razie zachowuje
     * dotychczasowe zachowanie: zeby z AXS, a gdy ich brak -> sam indeks pozycji.
     */
    private fun resolveRearTeeth(pos: Int, reportedTeeth: Int): Int {
        val cogs = cassetteCogsRef.get()
        if (cassetteOverrideRef.get() && cogs.isNotEmpty() && pos in 1..cogs.size) {
            return cogs[pos - 1]
        }
        return if (reportedTeeth > 0) reportedTeeth else pos
    }

    fun getCivilDuskMs(): Long = civilDuskMsRef.get()

    fun getEtaMs(): Long = lastEtaMsRef.get()

    fun getDeadlineMs(): Long = lastDeadlineMsRef.get()

    fun getElapsedSec(): Long = computeElapsedSec()

    private fun computeElapsedSec(): Long {
        val karoo = elapsedSecRef.get()
        val local = ((System.currentTimeMillis() - rideStartWallMsRef.get()) / 1000L).coerceAtLeast(0L)
        val lastChosen = lastChosenElapsedRef.get()
        val lastSdk = lastSdkElapsedRef.get()
        val sdkPlausible = isSdkElapsedPlausible(karoo, lastSdk, local, lastChosen)
        return if (sdkPlausible) karoo else local
    }

    fun getRideStartMs(): Long = rideStartMsRef.get()

    fun getCarbBalanceG(): Int = carbBalanceGRef.get()

    fun getCarbIntakeG(): Int = AthleteDataStore.loadCarbIntakeTotal()

    fun getCarbNeededG(): Int = carbNeededTotalGRef.get().roundToInt().coerceAtLeast(0)

    fun getDistanceMeters(): Double = distanceMetersRef.get()
    fun getDistanceToDestinationMeters(): Double = distanceToDestinationMetersRef.get()
    fun getAscentLeftM(): Int = ascentLeftMRef.get()

    private val lastRouteSeenMsRef = AtomicReference(0L)
    private val lastLocationSaveMs = AtomicReference(0L)
    private val routeGraceMs = 12_000L

    fun getEffectiveRoute(): Boolean {
        if (QExt2DebugConfig.DEBUG_FAKE_RIDE_MODE) return true
        val now = System.currentTimeMillis()
        val raw = navRouteActiveRef.get()
        if (raw) {
            lastRouteSeenMsRef.set(now)
        }
        val decision = routeStateDecision(raw, lastRouteSeenMsRef.get(), now, routeGraceMs)
        return decision.effectiveRoute
    }

    fun getEffectiveLtpWatts(): Float {
        val base = baseLtpWattsRef.get()
        if (base <= 0f) return 0f
        val cf = (todayFactorRef.get() * tempFactor(weatherTemperatureCRef.get()))
            .coerceIn(0.75f, 1.10f)
        return (base * cf).coerceAtLeast(50f)
    }

    fun getModeFactor(): Float = modeFactorRef.get()

    fun refreshModeFactor() {
        val rawMode = AthleteDataStore.loadRidingMode()
        val tf = AthleteDataStore.load().todayFactor
        modeFactorRef.set(when (rawMode) {
            0 -> 0.88f
            2 -> 1.12f
            3 -> when {
                tf < 0.90f -> 0.88f
                tf > 1.02f -> 1.12f
                else -> 1.00f
            }
            else -> 1.00f
        })
    }

    fun getNavClimbs(): List<KarooClimb> = navClimbsRef.get()
    fun getRouteKey(): String = navRouteKeyRef.get()

    fun getRouteDiag(): String {
        val now = System.currentTimeMillis()
        val raw = navRouteActiveRef.get()
        if (raw) {
            lastRouteSeenMsRef.set(now)
        }
        val decision = routeStateDecision(raw, lastRouteSeenMsRef.get(), now, routeGraceMs)
        val ago = if (lastRouteSeenMsRef.get() == 0L) -1L else now - lastRouteSeenMsRef.get()
        return "rawRoute=${decision.rawRoute} effectiveRoute=${decision.effectiveRoute} " +
            "lastSeenAgo=${if (ago < 0) "never" else "${ago}ms"} source=${decision.source}"
    }

    fun getEffectiveSpeedKmh(): Double {
        if (QExt2DebugConfig.DEBUG_FAKE_RIDE_MODE) {
            val sec = getElapsedSec()
            return 22.0
        }
        return speedRef.get()
    }

    fun getEffectiveCadence(): Int {
        if (QExt2DebugConfig.DEBUG_FAKE_RIDE_MODE) return 65
        return cadenceRef.get()
    }

    fun getEffectiveHr(): Int {
        if (QExt2DebugConfig.DEBUG_FAKE_RIDE_MODE) {
            val sec = getElapsedSec(); val idx = ((sec % 30) / 10).toInt()
            return when (idx) { 0 -> 120; 1 -> 145; else -> 165 }
        }
        return hrRef.get()
    }

    fun getEffectivePower(): Int {
        if (QExt2DebugConfig.DEBUG_FAKE_RIDE_MODE) {
            val sec = getElapsedSec(); val idx = ((sec % 30) / 10).toInt()
            return when (idx) { 0 -> 120; 1 -> 240; else -> 320 }
        }
        return powerRef.get()
    }

    fun getEffectiveGrade(): Double {
        if (QExt2DebugConfig.DEBUG_FAKE_RIDE_MODE) {
            val sec = getElapsedSec(); val idx = ((sec % 30) / 10).toInt()
            return when (idx) { 0 -> 0.0; 1 -> 3.0; else -> 7.0 }
        }
        return filteredGradeRef.get()
    }

    fun getPowerFreshnessMs(): Long {
        val last = powerFreshnessRef.get()
        if (last <= 0L) return Long.MAX_VALUE
        return System.currentTimeMillis() - last
    }
    fun getCadenceFreshnessMs(): Long {
        val last = cadenceFreshnessRef.get()
        if (last <= 0L) return Long.MAX_VALUE
        return System.currentTimeMillis() - last
    }
    fun getHrFreshnessMs(): Long {
        val last = hrFreshnessRef.get()
        if (last <= 0L) return Long.MAX_VALUE
        return System.currentTimeMillis() - last
    }

    private var carbTelemetryLastLogMs = 0L

    private var fieldDiagLastLogMs = 0L
    private var timeStateLastLogMs = 0L
    private var fakeRideLogLastMs = 0L

    private fun applyFakeRideData(nowMs: Long, elapsedSec: Long) {
        val cycleSec = (elapsedSec % 30).toInt()
        val cycleIndex = cycleSec / 10
        val fakePower = when (cycleIndex) { 0 -> 120; 1 -> 240; else -> 320 }
        val fakeHr = when (cycleIndex) { 0 -> 120; 1 -> 145; else -> 165 }
        val fakeGrade = when (cycleIndex) { 0 -> 0.0; 1 -> 3.0; else -> 7.0 }

        speedRef.set(22.0)
        powerRef.set(fakePower); powerFreshnessRef.set(nowMs)
        hrRef.set(fakeHr); hrFreshnessRef.set(nowMs)
        cadenceRef.set(65); cadenceFreshnessRef.set(nowMs)
        gearFrontRef.set(40); gearRearRef.set(15); gearFreshnessRef.set(nowMs)
        filteredGradeRef.set(fakeGrade); gradeFreshnessRef.set(nowMs)
        distanceMetersRef.set(((elapsedSec * 22.0 / 3.6)).coerceAtLeast(0.0))  // distance in meters at 22km/h
        distanceToDestinationMetersRef.set(25000.0)
        ascentDoneMRef.set(((elapsedSec / 60) * 5).coerceAtLeast(0).toInt())
        ascentLeftMRef.set((400 - (elapsedSec / 60) * 5).coerceAtLeast(0).toInt())
        elevationGainReceivedRef.set(true)
        elevationRemainingReceivedRef.set(true)
        temperatureRef.set(18f)

        if (nowMs - fakeRideLogLastMs > 15_000L) {
            fakeRideLogLastMs = nowMs
            Log.i(TAG, "QEXT_FAKE_RIDE enabled=true elapsed=${elapsedSec}s power=${fakePower}W hr=${fakeHr} speed=22km/h grade=${fakeGrade}%")
        }
    }

    private fun logTimeState(nowMs: Long, karooElapsed: Long, localElapsed: Long, chosenElapsed: Long, sdkPlausible: Boolean) {
        if (timeStateLastLogMs == 0L) { timeStateLastLogMs = nowMs; return }
        if (nowMs - timeStateLastLogMs < 15_000L) return
        timeStateLastLogMs = nowMs
        val source = when {
            karooElapsed > 0L && sdkPlausible -> "SDK_ELAPSED_VALID"
            karooElapsed > 0L -> "LOCAL_FALLBACK_SDK_OUTLIER"
            !karooElapsedReceivedRef.get() -> "MISSING"
            else -> "LOCAL_FALLBACK_SDK_ZERO"
        }
        Log.i(TAG, "QEXT_TIME_STATE karooElapsed=${karooElapsed}s localElapsed=${localElapsed}s " +
                "chosenElapsed=${chosenElapsed}s source=$source")
        Log.i(TAG, "QEXT_ROUTE_STATE ${getRouteDiag()}")
    }

    private fun logFieldDiagnostics(nowMs: Long, elapsed: Long, carbsGph: Int, intake: Int, needed: Int, balance: Int,
                                     wPrime: Int, tss: Float, reserve: Int, speed: Double, route: Boolean,
                                     ascentDone: Int, ascentLeft: Int, remainingM: Double) {
        if (fieldDiagLastLogMs == 0L) { fieldDiagLastLogMs = nowMs; return }
        if (nowMs - fieldDiagLastLogMs < 15_000L) return
        fieldDiagLastLogMs = nowMs
        Log.i(TAG, "QEXT_FIELD_DIAG elapsed=${elapsed}s speed=${"%.1f".format(speed)}kmh route=$route " +
                "carbs=${carbsGph}g/h intake=${intake}g needed=${needed}g balance=${balance}g " +
                "wPrime=${wPrime}% tss=${"%.0f".format(tss)} reserve=${reserve}% " +
                "upDone=${ascentDone}m upLeft=${ascentLeft}m remain=${"%.0f".format(remainingM)}m")
    }

    private fun logCarbTelemetry(nowMs: Long, isMoving: Boolean, dtSec: Long, carbsGPerH: Int,
                                  intake: Int, needed: Int, balance: Int) {
        if (carbTelemetryLastLogMs == 0L) { carbTelemetryLastLogMs = nowMs; return }
        if (nowMs - carbTelemetryLastLogMs < 60_000L) return
        carbTelemetryLastLogMs = nowMs
        if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "CARB_TELEM moving=$isMoving dt=${dtSec}s carbs/h=$carbsGPerH " +
                "intake=${intake}g needed=${needed}g balance=${balance}g")
    }

    private fun sanitizeCarbIntake() {
        val raw = AthleteDataStore.loadCarbIntakeTotal()
        if (raw < 0 || raw > 5000) {
            Log.w(TAG, "carb intake sanitized: $raw -> 0")
            AthleteDataStore.resetCarbIntakeTotal()
        }
    }

    private fun sanitizeCarbNeeded(raw: Double): Double {
        if (raw.isNaN() || raw.isInfinite() || raw < 0.0 || raw > 5000.0) {
            Log.w(TAG, "carb needed sanitized: $raw -> 0.0")
            AthleteDataStore.saveCarbNeededTotal(0.0)
            return 0.0
        }
        return raw
    }

    private fun sanitizeCarbElapsed(raw: Long): Long {
        if (raw < 0L || raw > 86400L) {
            Log.w(TAG, "carb elapsed sanitized: $raw -> 0")
            AthleteDataStore.saveCarbLastElapsedSec(0L)
            return 0L
        }
        return raw
    }

    private val fuelProducer = com.qext2.primary.active.FuelReminderProducer(
        logger = { msg -> Log.d(TAG, "QEXT_FUEL $msg") })
    private val pendingFuelMsgRef = AtomicReference<com.qext2.primary.active.ActiveMessage?>(null)

    fun consumePendingFuelMessage(): com.qext2.primary.active.ActiveMessage? =
        pendingFuelMsgRef.getAndSet(null)

    /** Physiological ambient temperature: weather API when fresh (sensor reads low
     *  in airflow while moving); falls back to device sensor when weather stale. */
    private fun physioTempC(): Float {
        val w = weatherTemperatureCRef.get()
        return if (w != null && weatherFreshRef.get()) w else temperatureRef.get()
    }

    private fun tempFactor(tempC: Float?): Float {
        if (tempC == null) return 1.0f
        val delta = (tempC - 20f).coerceAtLeast(0f)
        return (1f - 0.007f * delta).coerceAtLeast(0.85f)
    }

    private fun applyAthleteData(data: AthleteData, resetStats: Boolean) {
        if (data.ftp > 0) statsCalc.ftpWatts = data.ftp
        statsCalc.todayFactor = data.todayFactor
        statsCalc.humidityPercent = data.humidityPercent
        sunsetTimestampRef.set(data.sunsetTimestampMs)
        maxHrRef.set(data.maxHr.coerceIn(100, 220))
        todayFactorRef.set(data.todayFactor.coerceIn(0.5f, 1.1f))
        val rawMode = AthleteDataStore.loadRidingMode()
        modeFactorRef.set(when (rawMode) {
            0 -> 0.88f
            2 -> 1.12f
            3 -> when {                          // AUTO
                data.todayFactor < 0.90f -> 0.88f
                data.todayFactor > 1.02f -> 1.12f
                else -> 1.00f
            }
            else -> 1.00f
        })
        statsCalc.bodyWeightKg = data.bodyWeightKg
        if (data.wPrimeKj > 0.0 && data.ltpWatts > 0) {
            baseLtpWattsRef.set(data.ltpWatts.toFloat())
            baseWPrimeKjRef.set(data.wPrimeKj.toFloat())
            statsCalc.setWPrimeParams(data.wPrimeKj.toFloat(), data.ltpWatts.toFloat())
        }
        if (resetStats) {
            statsCalc.reset()
            statsCalc.captureStartReserve()
        }
    }

    private fun updatePower(value: Double, source: String) {
        powerRef.set(value.toInt())
        powerFreshnessRef.set(System.currentTimeMillis())
        if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "$source=$value")
    }

    private fun maybePersistReserveBase(effectiveTss: Float, nowMs: Long) {
        val last = reservePersistLastMsRef.get()
        if (last > 0L && nowMs - last < RESERVE_PERSIST_INTERVAL_MS) return
        AthleteDataStore.saveReserveDailyTssBase(effectiveTss)
        AthleteDataStore.saveReserveDailyTssBaseDate(java.time.LocalDate.now().toString())
        reservePersistLastMsRef.set(nowMs)
    }

    private fun todayDeadlineMs(nowMs: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, deadlineHourRef.get().coerceIn(0, 23))
            set(Calendar.MINUTE, deadlineMinuteRef.get().coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    private fun resolveDeadlineMs(nowMs: Long): Long {
        val userDeadline = todayDeadlineMs(nowMs)
        if (!capTwilightRef.get()) return userDeadline
        val twilightMs = civilDuskMsRef.get().takeIf { it > 0L }
            ?: sunsetTimestampRef.get().takeIf { it > 0L }
            ?: return userDeadline
        return minOf(userDeadline, twilightMs)
    }

    private fun FieldColor?.toAndroidColor(): Int = when (this) {
        FieldColor.GREEN -> 0xFF4ADE80.toInt()
        FieldColor.AMBER -> 0xFFFACC15.toInt()
        FieldColor.ORANGE -> 0xFFFB923C.toInt()
        FieldColor.RED -> 0xFFFF5252.toInt()
        FieldColor.BLUE -> 0xFF3B82F6.toInt()
        FieldColor.GRAY -> 0xFF9CA3AF.toInt()
        FieldColor.NEUTRAL, null -> 0xFFFFFFFF.toInt()
    }

    private fun pacingPowerColor(
        power: Int, effectiveLtp: Float, wBalancePct: Int,
        reserve: Int, elapsedHours: Float, remainingHours: Float,
        decouplingPct: Float, hasDecoupling: Boolean,
        powerAgeMs: Long,
    ): Int {
        if (effectiveLtp < 50f || power < 20 || powerAgeMs > 5_000L)
            return Color.parseColor("#CBD5E1")

        // W' factor: tightens as W' depletes (short-term)
        val wFrac = wBalancePct.coerceIn(0, 100) / 100f
        val wFac = 1f + 0.20f * wFrac

        // RSRV factor: tightens when projected finish RSRV is low (long-term)
        val rsvFac = if (remainingHours > 0f && elapsedHours > 0.25f && reserve in 0..100) {
            val drainRate = (100f - reserve.toFloat()) / elapsedHours
            val projected = (reserve.toFloat() - drainRate * remainingHours).coerceIn(0f, 100f)
            1f + 0.20f * (projected / 100f)
        } else wFac  // no route or too early: fall back to W'-only

        // HR decoupling factor: heart drifting up at same power = overheating/overreaching.
        // <5% normal (1.0); 5-15% linear down to 0.90; >15% capped at 0.90.
        val decFac = if (hasDecoupling && decouplingPct > 5f)
            (1f - 0.01f * (decouplingPct - 5f)).coerceAtLeast(0.90f)
        else 1f

        // Binding constraint × riding mode × decoupling
        val ceiling = effectiveLtp * minOf(wFac, rsvFac) * modeFactorRef.get() * decFac

        return when {
            power >= ceiling.toInt()             -> Color.parseColor("#FF5252") // za mocno
            power >= (ceiling * 0.85f).toInt()   -> Color.parseColor("#4ADE80") // cel
            else                                 -> Color.WHITE                 // ponizej celu
        }
    }

    private fun computePowerColor(powerWatts: Int, ftpWatts: Int): Int {
        if (ftpWatts <= 0 || powerWatts <= 0) return 0xFFFFFFFF.toInt()
        val pct = powerWatts.toFloat() / ftpWatts
        return when {
            pct < 0.55f -> 0xFF4ADE80.toInt()  // GREEN — recovery
            pct < 0.76f -> 0xFFFFFFFF.toInt()   // WHITE — endurance
            pct < 0.91f -> 0xFFFACC15.toInt()   // AMBER — tempo
            pct < 1.06f -> 0xFFFB923C.toInt()   // ORANGE — threshold
            else -> 0xFFFF5252.toInt()           // RED — VO2max+
        }
    }

    private fun initCarbSession(elapsedSec: Long) {
        if (carbSessionInitializedRef.get()) return
        val storedLastElapsed = carbLastElapsedSecRef.get()
        val looksLikeNewRide = elapsedSec <= 30L ||
            (storedLastElapsed > 0L && elapsedSec + 120L < storedLastElapsed) ||
            (storedLastElapsed > 0L && elapsedSec > storedLastElapsed + 120L)
        if (looksLikeNewRide || (carbNeededTotalGRef.get() > 100 && elapsedSec < 120L)) {
            AthleteDataStore.resetCarbSessionState()
            carbNeededTotalGRef.set(0.0)
            carbLastElapsedSecRef.set(elapsedSec)
            Log.i(TAG, "CARB session reset needed=${carbNeededTotalGRef.get()} elapsed=$elapsedSec")
        }
        carbSessionInitializedRef.set(true)
    }

    private fun computeCarbDtSec(elapsedSec: Long): Long {
        val lastElapsed = carbLastElapsedSecRef.get()
        val rawGapSec = if (lastElapsed > 0L) elapsedSec - lastElapsed else 0L
        if (rawGapSec > 7200L) {
            AthleteDataStore.resetCarbSessionState()
            carbNeededTotalGRef.set(0.0)
            carbLastElapsedSecRef.set(elapsedSec)
            Log.d(TAG, "CARB long pause (${rawGapSec}s), session reset")
        }
        val dtSec = rawGapSec.coerceIn(0L, 30L)
        carbLastElapsedSecRef.set(elapsedSec)
        return dtSec
    }

    private fun computeIsMoving(speedKmh: Double): Boolean {
        val powerRaw = powerRef.get()
        val cadenceRaw = cadenceRef.get()
        val speedFromSensor = speedKmh > 0.5
        val fallbackMoving = !speedFromSensor && powerRaw > 0 && cadenceRaw > 0
        val rawMoving = speedKmh > 1.0 || fallbackMoving
        val wasMoving = wasMovingRef.get()
        return if (wasMoving) rawMoving || speedKmh > 0.8 else speedKmh > 1.4 || fallbackMoving
    }

    private fun accumulateCarbs(nowMs: Long, elapsedSec: Long, isMoving: Boolean, dtSec: Long, carbs: Int) {
        val recentlyActive = isMoving || (nowMs - wasActiveUntilMsRef.get() < 120_000L)
        if (isMoving) wasActiveUntilMsRef.set(nowMs)
        if (dtSec > 0L && recentlyActive && carbs > 0 && movingElapsedSecRef.get() > 60L) {
            val addNeeded = carbs.toDouble() * (dtSec.toDouble() / 3600.0)
            carbNeededTotalGRef.set((carbNeededTotalGRef.get() + addNeeded).coerceAtLeast(0.0))
            AthleteDataStore.saveCarbNeededTotal(carbNeededTotalGRef.get())
        }
    }

    private fun updateCarbBalance(elapsedSec: Long) {
        AthleteDataStore.saveCarbLastElapsedSec(elapsedSec)
        val carbIntakeTotal = AthleteDataStore.loadCarbIntakeTotal()
        val carbNeededTotal = carbNeededTotalGRef.get().roundToInt().coerceAtLeast(0)
        val carbBalance = carbIntakeTotal - carbNeededTotal
        carbBalanceGRef.set(carbBalance)
        logCarbTelemetry(System.currentTimeMillis(), false, 0L, 0, carbIntakeTotal, carbNeededTotal, carbBalance)
    }
}
