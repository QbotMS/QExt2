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
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.util.QExt2DebugConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.ln

private const val TAG = "QExt2ActiveStatic"
private const val MIN_RENDER_INTERVAL_MS = 300L

private class SIf10Calc(var ftp: Int = 250) {
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
class BpActiveStaticDataType : DataTypeImpl("qext2", "qext2-active-static") {

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
    private val if10Calc = SIf10Calc(ftp = athleteData.ftp)

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d(TAG, "startStream")
        emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = emptyMap())))
        emitter.setCancellable { Log.d(TAG, "startStream cancelled") }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "startView preview=${config.preview}")
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        largeCell = config.gridSize.first >= 60 || config.viewSize.first >= 400

        if (config.preview) {
            val views = RemoteViews(context.packageName, R.layout.field_active_4x2)
            setInitialValues(views)
            emitter.updateView(views)
        }

        val views = RemoteViews(context.packageName, R.layout.field_active_4x2)
        hasDistanceToDestData = false
        distanceToDestMeters = 0.0
        setInitialValues(views)
        emitter.updateView(views)

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
                    emitter.updateView(initViews)
                }
            }
        }

        emitter.setCancellable {
            Log.d(TAG, "startView cancelled")
            currentSystem?.let { s -> consumerIds.forEach { id -> s.removeConsumer(id) } }
            consumerIds.clear()
            scope.cancel()
        }
    }

    private fun subscribeAll(system: KarooSystemService, emitter: ViewEmitter, context: Context) {
        Log.d(TAG, "subscribing all data types")

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

    private fun emitUpdate(emitter: ViewEmitter, context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRenderMs < MIN_RENDER_INTERVAL_MS) return

        refreshAthleteData()
        val distText = formatDistanceKm(distanceMeters)
        val dtdText = if (hasDistanceToDestData) formatDistanceKm(distanceToDestMeters) else "--"
        val if10Text = String.format("%.2f", intensityFactor)
        val vsrText = String.format("%.1f", avgSpeed)
        val tempText = formatTemp(temperature)
        val agg = QExt2PrimaryExtension.instance?.aggregator
        val snap = agg?.statsSnapshot?.value
        val wbalText = if (snap != null && snap.wBalancePercent >= 0) snap.wBalancePercent.toString() else "NO"
        val wbalTrend = snap?.wBalanceTrend ?: "stable"
        val windText = formatWind()
        val windDir = formatWindDir()

        val signature = listOf(
            distText, dtdText, if10Text, vsrText, tempText,
            wbalText, wbalTrend, windText, windDir,
            hasDistanceToDestData.toString(),
        ).joinToString("|")
        if (signature == lastRenderSignature && now - lastRenderMs < 1_500L) return

        val views = RemoteViews(context.packageName, R.layout.field_active_4x2)
        applyTypography(views)
        views.setTextViewText(R.id.tv_active_dist, distText)
        views.setTextViewText(R.id.tv_active_dtd, dtdText)
        if (hasDistanceToDestData && distanceToDestMeters > 0) {
            val etaMs = agg?.getEtaMs() ?: 0L
            val deadlineMs = agg?.getDeadlineMs() ?: 0L
            if (etaMs > 0L && deadlineMs > 0L) {
                views.setTextColor(R.id.tv_active_dtd, when {
                    etaMs > deadlineMs -> 0xFFEF4444.toInt()
                    etaMs <= deadlineMs * 0.85 -> 0xFF22C55E.toInt()
                    else -> 0xFFFFFFFF.toInt()
                })
            }
        }
        views.setTextViewText(R.id.tv_active_if10, styleIf10Value(if10Text))
        views.setTextViewText(R.id.tv_active_vsr, vsrText)
        views.setTextViewText(R.id.tv_active_null, formatCarbBalance(QExt2PrimaryExtension.instance?.aggregator?.getCarbBalanceG() ?: 0))
        views.setTextViewText(R.id.tv_active_temp, tempText)
        views.setViewVisibility(R.id.tv_active_temp_unit, if (tempText == "NO") android.view.View.GONE else android.view.View.VISIBLE)
        views.setTextViewText(R.id.tv_active_wbal, wbalText)
        views.setViewVisibility(R.id.tv_active_wbal_unit, if (wbalText == "NO") android.view.View.GONE else android.view.View.VISIBLE)
        views.setTextColor(R.id.tv_active_wbal, when (wbalTrend) {
            "rising" -> 0xFF22C55E.toInt()
            "falling", "plummeting" -> 0xFFEF4444.toInt()
            else -> 0xFFFFFFFF.toInt()
        })
        views.setTextViewText(R.id.tv_active_wind, windText)
        views.setTextViewText(R.id.tv_active_wind_dir, windDir)
        views.setViewVisibility(R.id.tv_active_wind_dir, if (windDir.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
        emitter.updateView(views)
        lastRenderMs = now
        lastRenderSignature = signature
        if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "view updated")
    }

    private fun formatDistanceKm(meters: Double): String {
        if (meters <= 0) return "0"
        return String.format(if ((meters / 1000.0) < 100) "%.1f" else "%.0f", meters / 1000.0)
    }

    private fun handlePowerSample(power: Double, emitter: ViewEmitter, context: Context) {
        hasPowerData = true
        val watts = power.toInt()
        intensityFactor = if10Calc.update(watts)
        emitUpdate(emitter, context)
    }

    private fun extractDistanceToDestinationMeters(dp: DataPoint?): Double? {
        if (dp == null) return null
        val direct = dp.values[DataType.Field.DISTANCE_TO_DESTINATION] as? Double
        if (direct != null && direct > 0.0) return direct
        val single = dp.singleValue
        if (single != null && single > 0.0) return single
        return dp.values.values.mapNotNull { it as? Double }.filter { it > 10.0 }.maxOrNull() ?: single ?: direct
    }

    private fun formatTemp(c: Double): String {
        if (c < -50 || c > 60) return "NO"
        return round(c).toInt().toString()
    }

    private fun formatWind(): String {
        val ageMs = System.currentTimeMillis() - lastWindUpdateMs
        if (lastWindUpdateMs == 0L || ageMs > 10_000L) return "--"
        val speedMs = currentWindSpeedMs()
        if (speedMs.isNaN() || speedMs > 60.0) return "--"
        return round(speedMs).toInt().toString()
    }

    private fun formatWindDir(): String {
        val ageMs = System.currentTimeMillis() - lastWindUpdateMs
        if (lastWindUpdateMs == 0L || ageMs > 10_000L) return ""
        if (lastDirectionDeg.isNaN() || lastDirectionDeg < 0) return if (currentWindSpeedMs().isNaN()) "" else "↑"
        return arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")[((lastDirectionDeg + 22.5) % 360).toInt() / 45]
    }

    private fun currentWindSpeedMs(): Double = when {
        !lastWindSpeedMs.isNaN() -> lastWindSpeedMs
        !lastHeadwindSpeedMs.isNaN() -> kotlin.math.abs(lastHeadwindSpeedMs)
        else -> Double.NaN
    }

    private fun applyWindSample(source: String, rawValue: Double) {
        when (source) {
            "headwindDirection" -> { updateDirection(rawValue, 1); if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "wind $source=$rawValue") }
            "headwindSpeed" -> { if (kotlin.math.abs(rawValue) <= 60.0) { lastHeadwindSpeedMs = rawValue; lastWindUpdateMs = System.currentTimeMillis() }; if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "wind $source=$rawValue") }
            "headwind" -> { updateDirection(rawValue, 3); if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "wind $source=$rawValue") }
            "windDirection" -> { updateDirection(rawValue, 4); if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "wind $source=$rawValue") }
            "windSpeed" -> { if (kotlin.math.abs(rawValue) <= 60.0) { lastWindSpeedMs = kotlin.math.abs(rawValue); lastWindUpdateMs = System.currentTimeMillis() }; if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "wind $source=$rawValue") }
        }
    }

    private fun updateDirection(rawValue: Double, priority: Int) {
        if (rawValue !in 0.0..360.0) return
        val now = System.currentTimeMillis()
        if (lastDirectionDeg.isNaN() || (now - lastWindUpdateMs) > 10_000L || priority <= lastDirectionPriority) {
            lastDirectionDeg = rawValue; lastDirectionPriority = priority
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
        val m = 25f; val u = 14f
        views.setTextViewTextSize(R.id.tv_active_dist, TypedValue.COMPLEX_UNIT_SP, m)
        views.setTextViewTextSize(R.id.tv_active_dtd, TypedValue.COMPLEX_UNIT_SP, m)
        views.setTextViewTextSize(R.id.tv_active_if10, TypedValue.COMPLEX_UNIT_SP, m)
        views.setTextViewTextSize(R.id.tv_active_vsr, TypedValue.COMPLEX_UNIT_SP, m)
        views.setTextViewTextSize(R.id.tv_active_null, TypedValue.COMPLEX_UNIT_SP, m)
        views.setTextViewTextSize(R.id.tv_active_temp, TypedValue.COMPLEX_UNIT_SP, m)
        views.setTextViewTextSize(R.id.tv_active_temp_unit, TypedValue.COMPLEX_UNIT_SP, m)
        views.setTextViewTextSize(R.id.tv_active_wbal, TypedValue.COMPLEX_UNIT_SP, m)
        views.setTextViewTextSize(R.id.tv_active_wbal_unit, TypedValue.COMPLEX_UNIT_SP, u)
        views.setTextViewTextSize(R.id.tv_active_wind, TypedValue.COMPLEX_UNIT_SP, m)
        views.setTextViewTextSize(R.id.tv_active_wind_dir, TypedValue.COMPLEX_UNIT_SP, m)
        views.setTextViewTextSize(R.id.tv_active_wind_unit, TypedValue.COMPLEX_UNIT_SP, u)
    }

    private fun styleIf10Value(raw: String): CharSequence {
        if (raw.isEmpty()) return raw
        val spanned = SpannableString(raw)
        spanned.setSpan(AbsoluteSizeSpan(24, true), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spanned
    }

    private fun formatCarbBalance(balanceG: Int): String =
        if (balanceG > 0) "+${balanceG}g" else "${balanceG}g"
}
