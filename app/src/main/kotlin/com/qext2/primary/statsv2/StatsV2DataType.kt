package com.qext2.primary.statsv2

import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.Keep
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.R
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.engine.RideDataAggregator
import com.qext2.primary.model.StatsRideSnapshot
import com.qext2.primary.model.SurfaceType
import com.qext2.primary.surface.SurfaceBridge
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "QExt2StatsV2"

/**
 * STATS v2 (test) — osobne pole obok starego STATS (docs/FIELD_LOOK_PLAN.md).
 * Rysowane jako obrazek przez StatsV2Renderer. Bledy rysowania nie przewracaja reszty QExt2.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Keep
/** forceLive = wersja produkcyjna: zawsze dane z jazdy (bez demo). */
class StatsV2DataType(typeId: String = "qext2-stats-v2", private val forceLive: Boolean = false) : DataTypeImpl("qext2", typeId) {

    override fun startStream(emitter: Emitter<StreamState>) {
        emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = emptyMap())))
        emitter.setCancellable { Log.d(TAG, "startStream cancelled") }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        QExt2PrimaryExtension.instance?.onFieldVisible()
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        AthleteDataStore.init(context)
        val w = config.viewSize.first.coerceAtLeast(120)
        val h = config.viewSize.second.coerceAtLeast(160)
        Log.i(TAG, "QEXT_STATS_V2_VIEW size=${w}x$h")
        com.qext2.primary.util.RideFileLog.append("VIEW STATS_V2 type=$dataTypeId size=${w}x$h")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        emitter.updateView(RemoteViews(context.packageName, R.layout.field_stats_v2))

        scope.launch {
            if (!forceLive && AthleteDataStore.loadStatsV2Demo()) {
                Log.i(TAG, "QEXT_STATS_V2_DEMO on")
                while (isActive) {
                    val bmp = try {
                        withContext(Dispatchers.Default) { StatsV2Renderer.render(w, h, StatsV2Demo.at(System.currentTimeMillis())) }
                    } catch (e: Exception) {
                        Log.w(TAG, "QEXT_STATS_V2_RENDER_FAIL msg=${e.message}", e); com.qext2.primary.util.RideFileLog.append("RENDER_FAIL STATS_V2 msg=${e.message}")
                        null
                    }
                    if (bmp != null) {
                        val rv = RemoteViews(context.packageName, R.layout.field_stats_v2)
                        rv.setImageViewBitmap(R.id.iv_stats_v2, bmp)
                        emitter.updateView(rv)
                    }
                    delay(2000L)
                }
                return@launch
            }
            val ext = QExt2PrimaryExtension.instance ?: return@launch
            var lastData: StatsV2Data? = null
            var lastEmitMs = 0L
            ext.aggregatorFlow
                .flatMapLatest { agg -> (agg?.statsSnapshot ?: flowOf(StatsRideSnapshot())).map { agg to it } }
                .collect { (agg, snap) ->
                    val data = try {
                        toData(agg, snap)
                    } catch (e: Exception) {
                        Log.w(TAG, "QEXT_STATS_V2_DATA_FAIL msg=${e.message}")
                        null
                    } ?: return@collect
                    val now = System.currentTimeMillis()
                    if (data == lastData || now - lastEmitMs < 1000L) return@collect
                    lastData = data
                    lastEmitMs = now
                    val bmp = try {
                        withContext(Dispatchers.Default) { StatsV2Renderer.render(w, h, data) }
                    } catch (e: Exception) {
                        Log.w(TAG, "QEXT_STATS_V2_RENDER_FAIL msg=${e.message}", e); com.qext2.primary.util.RideFileLog.append("RENDER_FAIL STATS_V2 msg=${e.message}")
                        null
                    } ?: return@collect
                    val rv = RemoteViews(context.packageName, R.layout.field_stats_v2)
                    rv.setImageViewBitmap(R.id.iv_stats_v2, bmp)
                    emitter.updateView(rv)
                }
        }

        emitter.setCancellable {
            QExt2PrimaryExtension.instance?.onFieldHidden()
            scope.cancel()
        }
    }

    private fun npZone(np: Int, cp: Float): Int? {
        if (np <= 0 || cp <= 0f) return null
        val r = np / cp
        return when {
            r < 0.55f -> 1
            r < 0.75f -> 2
            r < 0.90f -> 3
            r < 1.05f -> 4
            r < 1.20f -> 5
            else -> 6
        }
    }

    /** najblizsze zdarzenie: (czas, czy swit) - swit przed wschodem, zmrok w dzien, po zmroku swit nastepnego dnia */
    private fun twilight(agg: RideDataAggregator?): Pair<Long, Boolean>? {
        val now = System.currentTimeMillis()
        val dusk = agg?.getCivilDuskMs() ?: 0L
        val dawn = agg?.getCivilDawnMs() ?: 0L
        return com.qext2.primary.util.Twilight.next(now, dawn, dusk)
    }

    private fun toData(agg: RideDataAggregator?, s: StatsRideSnapshot): StatsV2Data {
        val dtdKm = (agg?.getDistanceToDestinationMeters() ?: 0.0) / 1000.0
        val pos = agg?.getRoutePositionM()?.let { (it / 1000.0).toFloat() } ?: s.distanceKm   // E4.1
        val total = if (s.hasRoute && dtdKm > 0.05) pos + dtdKm.toFloat() else null
        val surf = if (SurfaceBridge.hasProfile()) agg?.navRemainingByType(pos.coerceAtLeast(0f)) else null
        return StatsV2Data(
            np = s.npWholeWatts.takeIf { it > 0 },
            npZone = npZone(s.npWholeWatts, s.cpEffW),
            ifv = s.ifWholeRide.takeIf { it > 0f },
            vi = s.viValue.takeIf { it > 0f },
            rsrv = if (s.rsrvModelReady) s.rideReservePercent else null,
            xss = s.xssValue.takeIf { it > 0f },
            kcal = s.caloriesKcal.takeIf { it > 0 },
            hasRoute = s.hasRoute,
            doneKm = pos,
            totalKm = total,
            etaMs = if (s.etaModelReady && s.etaTimestamp > 0L) s.etaTimestamp else null,
            avgGrossKmh = if (s.grossElapsedSec > 60L) s.distanceKm / (s.grossElapsedSec / 3600f) else null,
            movingSec = s.movingElapsedSec,
            stopsSec = (s.grossElapsedSec - s.movingElapsedSec).coerceAtLeast(0L),
            surfPaved = surf?.get(SurfaceType.PAVED),
            surfGravel = surf?.get(SurfaceType.GRAVEL),
            surfLoose = surf?.get(SurfaceType.LOOSE),
            ascDone = if (s.routeClimbSourceReady) s.ascentDoneM else null,
            ascLeft = if (s.routeClimbSourceReady) s.ascentLeftM else null,
            carbRate = if (s.carbModelReady) s.carbsGPerH else null,
            carbSpent = if (s.carbModelReady) s.choBurnedG else null,   // E6.1: "spal." = faktycznie spalone CHO
            fluidRate = if (s.fluidModelReady) s.fluidLPerH else null,
            cadAvg = s.cadenceAvg.takeIf { it > 0 },
            batDrain = if (s.batteryDrainReady) s.batteryDrainPctPerHour else null,
            batLeftSec = if (s.batteryEstimateReady) s.batteryTimeLeftSec else null,
            twilightMs = twilight(agg)?.first,
            twilightDawn = twilight(agg)?.second ?: false,
            winNp = RideWindows.snapshot().first.map { it.np },
            winEf = RideWindows.snapshot().first.map { it.ef },
            winPartial = RideWindows.snapshot().second,
            typEf = RideWindows.typEf,
            cpW = s.cpEffW.takeIf { it > 0f },
            ahead = if (s.hasRoute && SurfaceBridge.hasProfile()) (agg?.navSurfaceSegments() ?: emptyList()).sortedBy { it.kmStart }
                .filter { it.kmEnd > pos }
                .map { (it.kmEnd - maxOf(it.kmStart, pos)) to android.graphics.Color.parseColor(when (it.surface) { SurfaceType.PAVED -> "#C9D2DC"; SurfaceType.GRAVEL -> "#D9A04E"; SurfaceType.LOOSE -> "#E0563B" }) }
                else null,
            stopsKm = agg?.getLongStopsKm()?.map { it.toFloat() } ?: emptyList(),
        )
    }
}
