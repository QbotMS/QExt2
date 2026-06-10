package com.qext2.primary.datatypes

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.util.TypedValue
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.Keep
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.R
import com.qext2.primary.active.ActiveMessage
import com.qext2.primary.active.ActiveClimbResolver
import com.qext2.primary.active.ActiveMessageManager
import com.qext2.primary.active.ClimbAnnouncementProducer
import com.qext2.primary.active.ClimbPacingProducer
import com.qext2.primary.active.WeatherMessageProducer
import com.qext2.primary.active.WeatherMsgState
import com.qext2.primary.active.BeepCooldownTracker
import com.qext2.primary.active.BeepSuppressionReason
import com.qext2.primary.active.ExpiryResult
import com.qext2.primary.active.ActiveMessagePriority
import com.qext2.primary.active.ActiveMessageRenderer
import com.qext2.primary.active.ActiveMessageResumePolicy
import com.qext2.primary.active.ActiveMessageSeverity
import com.qext2.primary.active.NoSdkClimbLogGate
import com.qext2.primary.active.SensorMessageProducer
import com.qext2.primary.active.SensorState
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.engine.RideDataAggregator
import com.qext2.primary.util.QExt2DebugConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.round

private const val TAG = "QExt2Active"
private const val MIN_RENDER_INTERVAL_MS = 1000L

private class IF10Calculator(var ftp: Int = 250) {
    private val raw = mutableListOf<Double>()
    private val smooth = mutableListOf<Double>()
    private var runningSum30 = 0.0
    private var fourthSum = 0.0

    fun update(power: Int): Double {
        val p = power.toDouble()
        raw.add(p)
        runningSum30 += p

        if (raw.size >= 30) {
            if (raw.size > 30) runningSum30 -= raw[raw.size - 31]
            val avg30 = runningSum30 / 30.0
            smooth.add(avg30)
            fourthSum += avg30 * avg30 * avg30 * avg30

            if (smooth.size > 570) {
                val removed = smooth.removeAt(0)
                fourthSum -= removed * removed * removed * removed
            }
            if (raw.size > 600) raw.removeAt(0)
        }

        if (smooth.isEmpty() || ftp <= 0) return 0.0
        val np = Math.pow(fourthSum / smooth.size, 0.25)
        return np / ftp
    }
}

@Keep
class CompositeActiveDataType : DataTypeImpl("qext2", "qext2-active") {

    private val consumerIds = mutableListOf<String>()
    private var distanceMeters = 0.0
    private var distanceToDestMeters = 0.0
    private var hasDistanceToDestData = false
    private var intensityFactor = 0.0
    private var avgSpeed = 0.0
    private var temperature = 0.0
    private var lastDirectionDeg = Double.NaN
    private var lastDirectionPriority = Int.MAX_VALUE
    private var lastWindSpeedMs = Double.NaN
    private var lastHeadwindSpeedMs = Double.NaN
    private var lastWindUpdateMs = 0L
    private var hasPowerData = false
    private var lastRenderMs = 0L
    private var lastRenderSignature = ""
    private var largeCell = false

    private var athleteData = AthleteDataStore.load()
    private val if10Calc = IF10Calculator(ftp = athleteData.ftp)
    private val messageManager = ActiveMessageManager(logger = { msg ->
        Log.i("QEXT_ACTIVE_ENGINE", msg)
    })
    private val sensorProducer = SensorMessageProducer(logger = { msg ->
        Log.i("QEXT_SENSOR_MSG", msg)
    })
    private val climbProducer = ClimbAnnouncementProducer(logger = { msg ->
        Log.i("QEXT_CLIMB_MSG", msg)
    })
    private val climbPacingProducer = ClimbPacingProducer(logger = { msg ->
        Log.d(TAG, "QEXT_PACING $msg")
    })
    private val weatherProducer = WeatherMessageProducer(logger = { msg ->
        Log.i("QEXT_WEATHER_MSG", msg)
    })
    private val beepCooldown = BeepCooldownTracker()
    private val noSdkClimbLogGate = NoSdkClimbLogGate()

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d(TAG, "QEXT_ACTIVE_STREAM_START")
        emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = emptyMap())))
        emitter.setCancellable { Log.d(TAG, "QEXT_ACTIVE_STREAM_STOP") }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "QEXT_ACTIVE_START preview=${config.preview}")
        QExt2PrimaryExtension.instance?.onFieldVisible()
        ActiveMessageRenderer.resetTracker()
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        largeCell = config.gridSize.first >= 60 || config.viewSize.first >= 400

        if (config.preview) {
            val views = RemoteViews(context.packageName, R.layout.field_active_4x2)
            setInitialValues(views)
        ActiveMessageRenderer.bind(views, messageManager.getCurrent(System.currentTimeMillis()))
        emitter.updateView(views)
        }

        val views = RemoteViews(context.packageName, R.layout.field_active_4x2)
        hasDistanceToDestData = false
        distanceToDestMeters = 0.0
        setInitialValues(views)
        emitter.updateView(views)

        messageManager.show(ActiveMessage(
            id = "pre_ride_calibration",
            title = "SKALIBRUJ",
            line1 = "MIERNIK MOCY",
            line2 = null,
            severity = ActiveMessageSeverity.INFO,
            priority = ActiveMessagePriority.CRITICAL,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
            createdAtMs = System.currentTimeMillis(),
            expiresAtMs = System.currentTimeMillis() + 120_000L,
        ))
        val withMsg = RemoteViews(context.packageName, R.layout.field_active_4x2)
        setInitialValues(withMsg)
        ActiveMessageRenderer.bind(withMsg, messageManager.getCurrent(System.currentTimeMillis()))
        emitter.updateView(withMsg)

        var currentSystem: KarooSystemService? = null

        scope.launch {
            val ext = QExt2PrimaryExtension.instance ?: return@launch
            ext.karooSystemFlow.collect { system ->
                currentSystem?.let { s ->
                    consumerIds.forEach { id -> s.removeConsumer(id) }
                    consumerIds.clear()
                }
                currentSystem = system
                if (system != null) {
                    subscribeAll(system, emitter, context)
                } else {
                    val initViews = RemoteViews(context.packageName, R.layout.field_active_4x2)
                    hasDistanceToDestData = false
                    distanceToDestMeters = 0.0
                    setInitialValues(initViews)
                    ActiveMessageRenderer.bind(initViews, null)
                    emitter.updateView(initViews)
                }
            }
        }

        scope.launch {
            if (!QExt2DebugConfig.DEBUG_ACTIVE_DEMO) return@launch
            Log.d(TAG, "QEXT_ACTIVE_DEMO_JOB_START")
            while (isActive) {
                kotlinx.coroutines.delay(8_000L)
                if (!isActive) break
                Log.d(TAG, "QEXT_ACTIVE_DEMO_TICK")
                val msg = ActiveMessage(
                    id = "demo_${System.currentTimeMillis()}",
                    title = "PODJAZD 3/7",
                    line1 = "1.8 km ↑142 m 7%",
                    line2 = "TEST ACTIVE",
                    severity = ActiveMessageSeverity.INFO,
                    createdAtMs = System.currentTimeMillis(),
                    expiresAtMs = System.currentTimeMillis() + 5_000L,
                )
                messageManager.show(msg)
                Log.d(TAG, "QEXT_ACTIVE_MSG_SHOW id=${msg.id}")
                emitUpdate(emitter, context, force = true)
            }
        }

        scope.launch {
            if (!QExt2DebugConfig.DEBUG_ACTIVE_SCENARIO) return@launch
            Log.d(TAG, "QEXT_ACTIVE_SCENARIO_JOB_START")
            var cycle = 0
            while (isActive) {
                val base = System.currentTimeMillis()
                val infoId = "scenario_info_${cycle}"
                val critId = "scenario_crit_${cycle}"
                val warnId = "scenario_warn_${cycle}"

                val resumeInfo = ActiveMessage(
                    id = infoId,
                    title = "PODJAZD 3/7",
                    line1 = "1.8 km ↑142 m 7%",
                    line2 = null,
                    severity = ActiveMessageSeverity.INFO,
                    priority = ActiveMessagePriority.INFO,
                    resumePolicy = ActiveMessageResumePolicy.RESUME_IF_STILL_VALID,
                    createdAtMs = base,
                    expiresAtMs = base + 10_000L,
                )
                messageManager.show(resumeInfo)
                emitUpdate(emitter, context, force = true)

                kotlinx.coroutines.delay(3_000L)
                if (!isActive) break

                val critical = ActiveMessage(
                    id = critId,
                    title = "ODPUŚĆ",
                    line1 = "HR DRIFT",
                    line2 = null,
                    severity = ActiveMessageSeverity.CRITICAL,
                    priority = ActiveMessagePriority.CRITICAL,
                    resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
                    createdAtMs = System.currentTimeMillis(),
                    expiresAtMs = System.currentTimeMillis() + 4_000L,
                )
                messageManager.show(critical)
                emitUpdate(emitter, context, force = true)

                kotlinx.coroutines.delay(5_000L)
                if (!isActive) break

                val warn = ActiveMessage(
                    id = warnId,
                    title = "DESZCZ ZA 4 KM",
                    line1 = "TEMP 6°C · SHELL",
                    line2 = null,
                    severity = ActiveMessageSeverity.WARNING,
                    priority = ActiveMessagePriority.WARNING,
                    resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
                    createdAtMs = System.currentTimeMillis(),
                    expiresAtMs = System.currentTimeMillis() + 4_000L,
                )
                messageManager.show(warn)
                emitUpdate(emitter, context, force = true)

                kotlinx.coroutines.delay(5_000L)
                cycle++
            }
        }

        scope.launch {
            Log.d(TAG, "QEXT_ACTIVE_EXPIRY_JOB_START")
            var idleTicks = 0
            while (isActive) {
                kotlinx.coroutines.delay(250L)
                if (!isActive) break
                val now = System.currentTimeMillis()
                val result = messageManager.hideExpired(now)
                when (result) {
                    is ExpiryResult.Expired -> {
                        idleTicks = 0
                        Log.d(TAG, "QEXT_ACTIVE_MSG_HIDE id=${result.message.id} reason=expired")
                        emitUpdate(emitter, context, force = true)
                    }
                    is ExpiryResult.Resumed -> {
                        idleTicks = 0
                        Log.d(TAG, "QEXT_ACTIVE_MSG_RESUME id=${result.message.id}")
                        if (QExt2DebugConfig.DEBUG_ACTIVE_PRODUCER_DIAG)
                            beepForMessage(result.message, "resume")
                        emitUpdate(emitter, context, force = true)
                    }
                    is ExpiryResult.None -> {
                        idleTicks++
                        if (idleTicks > 3) kotlinx.coroutines.delay(750L)
                    }
                }
            }
        }

        emitter.setCancellable {
            Log.d(TAG, "QEXT_ACTIVE_STOP")
            QExt2PrimaryExtension.instance?.onFieldHidden()
            messageManager.clear()
            climbProducer.reset()
            climbPacingProducer.reset()
            currentSystem?.let { s -> consumerIds.forEach { id -> s.removeConsumer(id) } }
            consumerIds.clear()
            scope.cancel()
        }
    }

    private fun subscribeAll(system: KarooSystemService, emitter: ViewEmitter, context: Context) {
        Log.d(TAG, "QEXT_ACTIVE_INIT subscribers_start")

        consumerIds.add(
            system.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.DISTANCE),
                onEvent = { event ->
                    val dp = (event.state as? StreamState.Streaming)?.dataPoint
                    val v = dp?.singleValue ?: (dp?.values?.get(DataType.Field.DISTANCE) as? Double)
                    if (v != null) { distanceMeters = v; emitUpdate(emitter, context) }
                }
            )
        )

        consumerIds.add(
            system.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.DISTANCE_TO_DESTINATION),
                onEvent = { event ->
                    val dp = (event.state as? StreamState.Streaming)?.dataPoint
                    val v = extractDistanceToDestinationMeters(dp)
                    if (v != null) {
                        distanceToDestMeters = v
                        hasDistanceToDestData = v > 0.0
                        emitUpdate(emitter, context)
                    }
                }
            )
        )

        consumerIds.add(
            system.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.AVERAGE_SPEED),
                onEvent = { event ->
                    val v = (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                    if (v != null) { avgSpeed = v * 3.6; emitUpdate(emitter, context) }
                }
            )
        )

        consumerIds.add(
            system.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.TEMPERATURE),
                onEvent = { event ->
                    val dp = (event.state as? StreamState.Streaming)?.dataPoint
                    val v = dp?.singleValue
                        ?: (dp?.values?.get(DataType.Field.TEMPERATURE) as? Double)
                    if (v != null) { temperature = v; emitUpdate(emitter, context) }
                }
            )
        )

        consumerIds.add(
            system.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.POWER),
                onEvent = { event ->
                    val dp = (event.state as? StreamState.Streaming)?.dataPoint
                    val v = dp?.singleValue ?: (dp?.values?.get(DataType.Field.POWER) as? Double)
                    if (v != null) {
                        handlePowerSample(v, emitter, context)
                    }
                }
            )
        )

        consumerIds.add(
            system.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.Type.SMOOTHED_3S_AVERAGE_POWER),
                onEvent = { event ->
                    val dp = (event.state as? StreamState.Streaming)?.dataPoint
                    val v = dp?.singleValue ?: (dp?.values?.get(DataType.Field.SMOOTHED_3S_AVERAGE_POWER) as? Double)
                    if (v != null) {
                        handlePowerSample(v, emitter, context)
                    }
                }
            )
        )

        consumerIds.add(
            system.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.dataTypeId("karoo-headwind", "headwind")),
                onEvent = { event ->
                    val v = (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                    if (v != null) {
                        applyWindSample("headwind", v)
                        emitUpdate(emitter, context)
                    }
                }
            )
        )

        consumerIds.add(
            system.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.dataTypeId("karoo-headwind", "headwindSpeed")),
                onEvent = { event ->
                    val v = (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                    if (v != null) {
                        applyWindSample("headwindSpeed", v)
                        emitUpdate(emitter, context)
                    }
                }
            )
        )

        consumerIds.add(
            system.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.dataTypeId("karoo-headwind", "headwindDirection")),
                onEvent = { event ->
                    val v = (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                    if (v != null) {
                        applyWindSample("headwindDirection", v)
                        emitUpdate(emitter, context)
                    }
                }
            )
        )

        consumerIds.add(
            system.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.dataTypeId("karoo-headwind", "windDirection")),
                onEvent = { event ->
                    val v = (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                    if (v != null) {
                        applyWindSample("windDirection", v)
                        emitUpdate(emitter, context)
                    }
                }
            )
        )

        consumerIds.add(
            system.addConsumer<OnStreamState>(
                params = OnStreamState.StartStreaming(DataType.dataTypeId("karoo-headwind", "windSpeed")),
                onEvent = { event ->
                    val v = (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                    if (v != null) {
                        applyWindSample("windSpeed", v)
                        emitUpdate(emitter, context)
                    }
                }
            )
        )

        Log.d(TAG, "all subscribers: ${consumerIds.size}")
    }

    private fun refreshAthleteData() {
        val fresh = AthleteDataStore.load()
        if (fresh.fetchTimestamp > athleteData.fetchTimestamp) {
            athleteData = fresh
            if10Calc.ftp = fresh.ftp
            Log.d(TAG, "Athlete data refreshed: FTP=${fresh.ftp}, W'max=${fresh.wPrimeKj}")
        }
    }

    private fun emitUpdate(emitter: ViewEmitter, context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRenderMs < MIN_RENDER_INTERVAL_MS) return

        if (force) Log.d(TAG, "QEXT_ACTIVE_EMIT_UPDATE force=$force")
        refreshAthleteData()

        var displayDist = distanceMeters
        var displayDtd = distanceToDestMeters
        var displayDtdHas = hasDistanceToDestData
        var displaySpeed = avgSpeed
        var displayTemp = temperature

        if (QExt2DebugConfig.DEBUG_FAKE_RIDE_MODE) {
            val agg = QExt2PrimaryExtension.instance?.aggregator
            if (agg != null) {
                val fakeSec = agg.getElapsedSec()
                displayDist = (fakeSec * 22.0 / 3.6).coerceAtLeast(0.0)
                displayDtd = agg.getDistanceToDestinationMeters()
                displayDtdHas = agg.getEffectiveRoute()
                displaySpeed = 20.0
                displayTemp = 18.0
            }
        }

        val distText = formatDistanceKm(displayDist)
        val dtdText = if (displayDtdHas) formatDistanceKm(displayDtd) else "--"
        val if10Text = String.format("%.2f", intensityFactor)
        val vsrText = String.format("%.1f", displaySpeed)
        val tempText = formatTemp(displayTemp)
        val agg = QExt2PrimaryExtension.instance?.aggregator
        val snap = agg?.statsSnapshot?.value
        val wbalText = if (snap != null && snap.wBalancePercent >= 0) snap.wBalancePercent.toString() else "NO"
        val wbalTrend = snap?.wBalanceTrend ?: "stable"
        val windText = formatWind()
        val windDir = formatWindDir()

        val signature = listOf(
            distText,
            dtdText,
            if10Text,
            vsrText,
            tempText,
            wbalText,
            wbalTrend,
            windText,
            windDir,
            hasDistanceToDestData.toString(),
        ).joinToString("|")
        if (!force && signature == lastRenderSignature && now - lastRenderMs < 1_500L) return

        if (agg != null) {
            val sensorState = SensorState(
                speedKmh = agg.getEffectiveSpeedKmh(),
                cadence = agg.getEffectiveCadence(),
                hr = agg.getEffectiveHr(),
                power = agg.getEffectivePower(),
                powerFreshnessMs = agg.getPowerFreshnessMs(),
                cadenceFreshnessMs = agg.getCadenceFreshnessMs(),
                hrFreshnessMs = agg.getHrFreshnessMs(),
                hasRoute = agg.getEffectiveRoute(),
                elapsedSec = agg.getElapsedSec(),
                nowMs = now,
            )
            val shouldClear = sensorState.speedKmh > 2.0 ||
                (sensorState.cadence > 0 && sensorState.cadenceFreshnessMs < 8_000L) ||
                (sensorState.power > 0 && sensorState.powerFreshnessMs < 8_000L)
            if (shouldClear) {
                val cur = messageManager.getCurrent(now)
                if (cur?.id == "pre_ride_calibration") messageManager.clear()
            }
            val sensorMsg = sensorProducer.checkAndProduce(sensorState)
            if (sensorMsg != null) {
                if (messageManager.show(sensorMsg)) beepForMessage(sensorMsg, "show")
            }

            val climbResolution = ActiveClimbResolver.resolve(
                nowMs = now,
                fakeMode = QExt2DebugConfig.DEBUG_FAKE_RIDE_MODE,
                hasRoute = agg.getEffectiveRoute(),
                navClimbs = agg.getNavClimbs(),
                distanceMeters = agg.getDistanceMeters(),
                distanceToDestinationMeters = agg.getDistanceToDestinationMeters(),
                ascentLeftM = agg.getAscentLeftM(),
                effectiveGrade = agg.getEffectiveGrade(),
            )
            val routeKey = agg.getRouteKey().ifBlank { "route:unknown" }
            if (agg.getNavClimbs().isNotEmpty()) {
                noSdkClimbLogGate.onSdkClimbsAvailable(routeKey)
            }
            if (climbResolution.reason == "no_sdk_climbs") {
                maybeLogNoSdkClimbs(routeKey)
            }
            val climbMsg = climbResolution.state?.let { climbProducer.checkAndProduce(it) }
            if (climbMsg != null) {
                if (messageManager.show(climbMsg)) beepForMessage(climbMsg, "show")
            }
            val climbPacingMsg = climbResolution.state?.takeIf { it.isWithinClimbBounds }?.let { cs ->
                climbPacingProducer.checkAndProduce(
                    power = agg.snapshot.value.power3s,
                    wBalancePct = agg.statsSnapshot.value.wBalancePercent,
                    effectiveLtpW = agg.getEffectiveLtpWatts(),
                    isWithinBounds = true,
                    ascentLeftM = cs.climbElevationM,
                    grade = cs.avgGradePercent,
                    climbIndex = cs.climbIndex,
                    modeFactor = agg.getModeFactor(),
                    nowMs = now,
                )
            }
            if (climbPacingMsg != null && messageManager.show(climbPacingMsg)) beepForMessage(climbPacingMsg, "pacing")
            agg.consumePendingFuelMessage()?.let { fuelMsg ->
                if (messageManager.show(fuelMsg)) beepForMessage(fuelMsg, "fuel")
            }

            val weatherMsg = weatherProducer.checkAndProduce(WeatherMsgState(
                weatherFresh = agg.statsSnapshot.value.weatherFresh,
                temperatureC = agg.statsSnapshot.value.weatherTemperatureC,
                windSpeedMps = agg.statsSnapshot.value.weatherWindSpeedMps,
                rain1hMm = agg.statsSnapshot.value.weatherRain1hMm,
                condition = agg.statsSnapshot.value.weatherCondition,
                nowMs = now,
            ))
            if (weatherMsg != null) {
                if (messageManager.show(weatherMsg)) beepForMessage(weatherMsg, "weather")
            }

            if (QExt2DebugConfig.DEBUG_ACTIVE_PRODUCER_DIAG) {
                logProducerDiag(now, agg, sensorMsg, climbMsg)
            }
        }

        val views = RemoteViews(context.packageName, R.layout.field_active_4x2)
        applyTypography(views)
        views.setTextViewText(R.id.tv_active_dist, distText)
        views.setTextViewText(R.id.tv_active_dtd, dtdText)
        if (displayDtdHas && displayDtd > 0) {
            val agg2 = QExt2PrimaryExtension.instance?.aggregator
            val etaMs = agg2?.getEtaMs() ?: 0L
            val deadlineMs = agg2?.getDeadlineMs() ?: 0L
            if (etaMs > 0L && deadlineMs > 0L) {
                val color = when {
                    etaMs > deadlineMs -> 0xFFFF5252.toInt()
                    deadlineMs - etaMs >= 30 * 60_000L -> 0xFF4ADE80.toInt()
                    deadlineMs - etaMs <= 10 * 60_000L -> 0xFFFACC15.toInt()
                    else -> 0xFFFFFFFF.toInt()
                }
                views.setTextColor(R.id.tv_active_dtd, color)
            }
        }
        views.setTextViewText(R.id.tv_active_if10, styleIf10Value(if10Text))
        views.setTextViewText(R.id.tv_active_vsr, vsrText)
        val carbBalance = QExt2PrimaryExtension.instance?.aggregator?.getCarbBalanceG() ?: 0
        views.setTextViewText(R.id.tv_active_null, formatCarbBalance(carbBalance))
        views.setTextViewText(R.id.tv_active_temp, tempText)
        views.setViewVisibility(
            R.id.tv_active_temp_unit,
            if (tempText == "NO") android.view.View.GONE else android.view.View.VISIBLE,
        )

        views.setTextViewText(R.id.tv_active_wbal, wbalText)
        views.setViewVisibility(
            R.id.tv_active_wbal_unit,
            if (wbalText == "NO") android.view.View.GONE else android.view.View.VISIBLE,
        )
        val wbalColor = when (wbalTrend) {
            "rising" -> 0xFF4ADE80.toInt()
            "falling", "plummeting" -> 0xFFFF5252.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        views.setTextColor(R.id.tv_active_wbal, wbalColor)

        views.setTextViewText(R.id.tv_active_wind, windText)
        views.setTextViewText(R.id.tv_active_wind_dir, windDir)
        views.setViewVisibility(
            R.id.tv_active_wind_dir,
            if (windDir.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE,
        )

        ActiveMessageRenderer.bind(views, messageManager.getCurrent(now))
        if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "QEXT_ACTIVE_RENDER_CALLED")
        emitter.updateView(views)
        lastRenderMs = now
        lastRenderSignature = signature
        if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "view updated")
    }

    private fun formatDistanceKm(meters: Double): String {
        if (meters <= 0) return "0"
        val km = meters / 1000.0
        return if (km < 100) String.format("%.1f", km) else String.format("%.0f", km)
    }

    private fun handlePowerSample(power: Double, emitter: ViewEmitter, context: Context) {
        try {
            hasPowerData = true
            val watts = power.toInt()
            intensityFactor = if10Calc.update(watts)
            emitUpdate(emitter, context)
        } catch (e: Exception) {
            Log.w(TAG, "QEXT_ACTIVE_POWER_CRASH msg=${e.message}", e)
        }
    }

    private fun extractDistanceToDestinationMeters(dp: DataPoint?): Double? {
        if (dp == null) return null
        val direct = dp.values[DataType.Field.DISTANCE_TO_DESTINATION] as? Double
        if (direct != null && direct > 0.0) return direct
        val single = dp.singleValue
        if (single != null && single > 0.0) return single
        val fallback = dp.values.values
            .mapNotNull { it as? Double }
            .filter { it > 10.0 }
            .maxOrNull()
        return fallback ?: single ?: direct
    }

    private fun formatTemp(c: Double): String {
        if (c < -50 || c > 60) return "NO"
        return round(c).toInt().toString()
    }

    private fun formatWind(): String {
        val ageMs = System.currentTimeMillis() - lastWindUpdateMs
        if (lastWindUpdateMs == 0L || ageMs > 10_000L) return "--"
        val speedMs = currentWindSpeedMs()
        if (speedMs.isNaN()) return "--"
        if (speedMs > 60.0) return "--"
        val speedRounded = round(speedMs / 3.6).toInt().toString()
        if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "formatWind: deg=$lastDirectionDeg windMs=$lastWindSpeedMs headwindMs=$lastHeadwindSpeedMs ms=$speedRounded")
        return speedRounded
    }

    private fun formatWindDir(): String {
        val ageMs = System.currentTimeMillis() - lastWindUpdateMs
        if (lastWindUpdateMs == 0L || ageMs > 10_000L) return ""
        if (lastDirectionDeg.isNaN() || lastDirectionDeg < 0) {
            if (currentWindSpeedMs().isNaN()) return ""
            return "↑"
        }
        val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
        val idx = ((lastDirectionDeg + 22.5) % 360).toInt() / 45
        return arrows[idx]
    }

    private fun currentWindSpeedMs(): Double {
        if (!lastWindSpeedMs.isNaN()) return lastWindSpeedMs
        if (!lastHeadwindSpeedMs.isNaN()) return kotlin.math.abs(lastHeadwindSpeedMs)
        return Double.NaN
    }

    private fun applyWindSample(source: String, rawValue: Double) {
        val absValue = kotlin.math.abs(rawValue)
        when (source) {
            "headwindDirection" -> {
                updateDirection(rawValue, priority = 1)
                if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "wind sample $source=$rawValue -> dir=$lastDirectionDeg")
            }
            "headwindSpeed" -> {
                if (absValue <= 60.0) {
                    lastHeadwindSpeedMs = rawValue
                    lastWindUpdateMs = System.currentTimeMillis()
                }
                if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "wind sample $source=$rawValue -> headwindSpeed=$lastHeadwindSpeedMs")
            }
            "headwind" -> {
                updateDirection(rawValue, priority = 3)
                if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "wind sample $source=$rawValue -> dir=$lastDirectionDeg")
            }
            "windDirection" -> {
                updateDirection(rawValue, priority = 4)
                if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "wind sample $source=$rawValue -> dir=$lastDirectionDeg")
            }
            "windSpeed" -> {
                if (absValue <= 60.0) {
                    lastWindSpeedMs = kotlin.math.abs(rawValue)
                    lastWindUpdateMs = System.currentTimeMillis()
                }
                if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "wind sample $source=$rawValue -> windSpeed=$lastWindSpeedMs")
            }
        }
    }

    private fun updateDirection(rawValue: Double, priority: Int) {
        if (rawValue !in 0.0..360.0) return
        val now = System.currentTimeMillis()
        val stale = (now - lastWindUpdateMs) > 10_000L
        val shouldReplace = lastDirectionDeg.isNaN() || stale || priority <= lastDirectionPriority
        if (shouldReplace) {
            lastDirectionDeg = rawValue
            lastDirectionPriority = priority
        }
        lastWindUpdateMs = now
    }

    private fun setInitialValues(views: RemoteViews) {
        applyTypography(views)
        views.setTextViewText(R.id.tv_active_dist, "0")
        views.setTextViewText(R.id.tv_active_dtd, "--")
        views.setTextViewText(R.id.tv_active_if10, styleIf10Value("0.00"))
        views.setTextViewText(R.id.tv_active_vsr, "0.0")
        views.setTextViewText(R.id.tv_active_null, "0g")
        views.setTextViewText(R.id.tv_active_temp, "NO")
        views.setViewVisibility(R.id.tv_active_temp_unit, android.view.View.GONE)
        views.setTextViewText(R.id.tv_active_wbal, "W")
        views.setViewVisibility(R.id.tv_active_wbal_unit, android.view.View.GONE)
        views.setTextViewText(R.id.tv_active_wind, "--")
        views.setTextViewText(R.id.tv_active_wind_unit, "ms")
        views.setViewVisibility(R.id.tv_active_wind_unit, android.view.View.VISIBLE)
        views.setViewVisibility(R.id.tv_active_wind_dir, android.view.View.GONE)
    }

    private fun applyTypography(views: RemoteViews) {
        val medium = 25f
        val windDir = medium
        val unitSmall = 14f

        views.setTextViewTextSize(R.id.tv_active_dist, TypedValue.COMPLEX_UNIT_SP, medium)
        views.setTextViewTextSize(R.id.tv_active_dtd, TypedValue.COMPLEX_UNIT_SP, medium)
        views.setTextViewTextSize(R.id.tv_active_if10, TypedValue.COMPLEX_UNIT_SP, medium)
        views.setTextViewTextSize(R.id.tv_active_vsr, TypedValue.COMPLEX_UNIT_SP, medium)
        views.setTextViewTextSize(R.id.tv_active_null, TypedValue.COMPLEX_UNIT_SP, medium)
        views.setTextViewTextSize(R.id.tv_active_temp, TypedValue.COMPLEX_UNIT_SP, medium)
        views.setTextViewTextSize(R.id.tv_active_temp_unit, TypedValue.COMPLEX_UNIT_SP, medium)
        views.setTextViewTextSize(R.id.tv_active_wbal, TypedValue.COMPLEX_UNIT_SP, medium)
        views.setTextViewTextSize(R.id.tv_active_wbal_unit, TypedValue.COMPLEX_UNIT_SP, unitSmall)
        views.setTextViewTextSize(R.id.tv_active_wind, TypedValue.COMPLEX_UNIT_SP, medium)
        views.setTextViewTextSize(R.id.tv_active_wind_dir, TypedValue.COMPLEX_UNIT_SP, windDir)
        views.setTextViewTextSize(R.id.tv_active_wind_unit, TypedValue.COMPLEX_UNIT_SP, unitSmall)
    }

    private fun styleIf10Value(raw: String): CharSequence {
        if (raw.isEmpty()) return raw
        val spanned = SpannableString(raw)
        spanned.setSpan(AbsoluteSizeSpan(24, true), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spanned
    }

    private fun formatCarbBalance(balanceG: Int): String {
        return if (balanceG > 0) "+${balanceG}g" else "${balanceG}g"
    }

    private var producerDiagLastMs = 0L

    private fun maybeLogNoSdkClimbs(routeKey: String) {
        if (!noSdkClimbLogGate.shouldLogNoSdkClimbs(routeKey)) return
        Log.i(TAG, "QEXT_CLIMB_MSG reason=no_sdk_climbs route=$routeKey climbs=0")
    }

    private fun beepForMessage(msg: ActiveMessage, reason: String) {
        if (reason == "resume") {
            if (QExt2DebugConfig.DEBUG_ACTIVE_PRODUCER_DIAG)
                Log.d(TAG, "QEXT_ACTIVE_BEEP id=${msg.id} severity=${msg.severity} backend=KAROO_SYSTEM_DISPATCH reason=resume_no_beep")
            return
        }
        if (msg.severity != ActiveMessageSeverity.WARNING && msg.severity != ActiveMessageSeverity.CRITICAL) {
            if (QExt2DebugConfig.DEBUG_ACTIVE_PRODUCER_DIAG)
                Log.d(TAG, "QEXT_ACTIVE_BEEP id=${msg.id} severity=${msg.severity} backend=KAROO_SYSTEM_DISPATCH reason=info_no_beep")
            return
        }
        val now = System.currentTimeMillis()
        when (beepCooldown.suppression(now)) {
            BeepSuppressionReason.SUCCESS_COOLDOWN -> {
                if (QExt2DebugConfig.DEBUG_ACTIVE_PRODUCER_DIAG)
                    Log.d(TAG, "QEXT_ACTIVE_BEEP id=${msg.id} severity=${msg.severity} backend=KAROO_SYSTEM_DISPATCH reason=suppressed_cooldown")
                return
            }
            BeepSuppressionReason.ERROR_COOLDOWN -> {
                if (QExt2DebugConfig.DEBUG_ACTIVE_PRODUCER_DIAG)
                    Log.d(TAG, "QEXT_ACTIVE_BEEP id=${msg.id} severity=${msg.severity} backend=KAROO_SYSTEM_DISPATCH reason=suppressed_error_cooldown")
                return
            }
            null -> {}
        }
        try {
            val system = QExt2PrimaryExtension.instance?.karooSystem
            if (system == null) {
                Log.w(TAG, "QEXT_ACTIVE_BEEP id=${msg.id} reason=dispatch_error error=no_karoo_system")
                beepCooldown.onFailure(now)
                return
            }
            val tones = if (msg.severity == ActiveMessageSeverity.CRITICAL) {
                listOf(PlayBeepPattern.Tone(5000, 180), PlayBeepPattern.Tone(null, 60), PlayBeepPattern.Tone(5000, 220))
            } else {
                listOf(PlayBeepPattern.Tone(5000, 120))
            }
            val ok = system.dispatch(PlayBeepPattern(tones))
            if (ok) {
                beepCooldown.onSuccess(now)
            } else {
                beepCooldown.onFailure(now)
            }
            if (QExt2DebugConfig.DEBUG_ACTIVE_PRODUCER_DIAG)
                Log.d(TAG, "QEXT_ACTIVE_BEEP id=${msg.id} severity=${msg.severity} backend=KAROO_SYSTEM_DISPATCH " +
                        "reason=${if (ok) "played" else "dispatch_false"}")
        } catch (e: Exception) {
            beepCooldown.onFailure(now)
            Log.w(TAG, "QEXT_ACTIVE_BEEP id=${msg.id} severity=${msg.severity} backend=KAROO_SYSTEM_DISPATCH reason=dispatch_error error=${e.message}")
        }
    }

    private fun logProducerDiag(now: Long, agg: RideDataAggregator, sensorMsg: ActiveMessage?, climbMsg: ActiveMessage?) {
        if (producerDiagLastMs == 0L) { producerDiagLastMs = now; return }
        if (now - producerDiagLastMs < 15_000L) return
        producerDiagLastMs = now
        Log.i(TAG, "QEXT_SNAPSHOT_SOURCE consumer=ACTIVE fake=${QExt2DebugConfig.DEBUG_FAKE_RIDE_MODE} " +
                "speed=${"%.1f".format(agg.getEffectiveSpeedKmh())}km/h grade=${"%.1f".format(agg.getEffectiveGrade())}% " +
                "route=${agg.getEffectiveRoute()} elapsed=${agg.getElapsedSec()}s")
        Log.i(TAG, "QEXT_ACTIVE_CLIMB_CHECK route=${agg.getEffectiveRoute()} " +
                "grade=${"%.1f".format(agg.getEffectiveGrade())}% " +
                "distance=${"%.0f".format(agg.getDistanceMeters())}m " +
                "sdkClimbs=${agg.getNavClimbs().size} " +
                "climbCandidate=${climbMsg?.title ?: "none"} " +
                "reason=${if (climbMsg != null) "candidate" else if (!agg.getEffectiveRoute()) "no_route" else "see_climb_reject_log"}")
        Log.i(TAG, "QEXT_ACTIVE_PRODUCER_DIAG " +
                "cad=${agg.getEffectiveCadence()} hr=${agg.getEffectiveHr()} pwr=${agg.getEffectivePower()} " +
                "pwrFresh=${agg.getPowerFreshnessMs()}ms cadFresh=${agg.getCadenceFreshnessMs()}ms " +
                "hrFresh=${agg.getHrFreshnessMs()}ms " +
                "sensorCandidate=${sensorMsg?.title ?: "none"} climbCandidate=${climbMsg?.title ?: "none"}")
    }
}
