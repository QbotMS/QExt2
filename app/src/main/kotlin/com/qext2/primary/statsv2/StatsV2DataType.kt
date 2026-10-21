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
        com.qext2.primary.kokpit.Kokpit2Fonts.init(context)
        com.qext2.primary.gate.GateController.init(context)
        val karoo = QExt2PrimaryExtension.instance?.karooSystem
        val locId = try {
            karoo?.addConsumer<io.hammerhead.karooext.models.OnLocationChanged>(onEvent = { com.qext2.primary.gate.GateController.onLocation(it.lat, it.lng) })
        } catch (e: Exception) { null }
        com.qext2.primary.gate.GateController.ensureHome()
        com.qext2.primary.light.LightController.init(context, karoo)
        val w = config.viewSize.first.coerceAtLeast(120)
        val h = config.viewSize.second.coerceAtLeast(160)
        Log.i(TAG, "QEXT_STATS_V2_VIEW size=${w}x$h")
        com.qext2.primary.util.RideFileLog.append("VIEW STATS_V2 type=$dataTypeId size=${w}x$h")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        emitter.updateView(rvWithTap(context))

        scope.launch {
            // 0.4.20: tryb NAGRANIE (demo.RecMode) -- scenariusz co 1 s; osobno od przelacznika "dane demo"
            if (com.qext2.primary.demo.RecDemo.active(context)) {
                while (isActive) {
                    val d = com.qext2.primary.demo.RecDemo.stats(System.currentTimeMillis())
                    val bmp = if (d == null) null else try {
                        withContext(Dispatchers.Default) { StatsV2Renderer.render(w, h, d) }
                    } catch (e: Exception) {
                        com.qext2.primary.util.RideFileLog.append("RENDER_FAIL STATS_V2 rec msg=${e.message}"); null
                    }
                    if (bmp != null) {
                        val rv = rvWithTap(context)
                        rv.setImageViewBitmap(R.id.iv_stats_v2, com.qext2.primary.demo.RecDemo.mark(bmp))
                        emitter.updateView(rv)
                    }
                    delay(1000L)
                }
                return@launch
            }
            // produkcyjne STATS v2 (forceLive): demo z tego samego przelacznika co KOKPIT 2 (SETUP)
            if (if (forceLive) AthleteDataStore.loadKokpit2Demo() else AthleteDataStore.loadStatsV2Demo()) {
                Log.i(TAG, "QEXT_STATS_V2_DEMO on")
                while (isActive) {
                    val bmp = try {
                        withContext(Dispatchers.Default) { StatsV2Renderer.render(w, h, demoData()) }
                    } catch (e: Exception) {
                        Log.w(TAG, "QEXT_STATS_V2_RENDER_FAIL msg=${e.message}", e); com.qext2.primary.util.RideFileLog.append("RENDER_FAIL STATS_V2 msg=${e.message}")
                        null
                    }
                    if (bmp != null) {
                        val rv = rvWithTap(context)
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
                    val rv = rvWithTap(context)
                    rv.setImageViewBitmap(R.id.iv_stats_v2, bmp)
                    emitter.updateView(rv)
                }
        }

        emitter.setCancellable {
            QExt2PrimaryExtension.instance?.onFieldHidden()
            scope.cancel()
            locId?.let { id -> karoo?.removeConsumer(id) }
        }
    }

    /** RemoteViews pola + dotyk komorki BRAMA (GateController przez StatsActionReceiver). */
    private fun rvWithTap(context: Context): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.field_stats_v2)
        val tap = android.app.PendingIntent.getBroadcast(
            context, 7312,
            android.content.Intent(context, com.qext2.primary.actions.StatsActionReceiver::class.java)
                .setAction(com.qext2.primary.actions.StatsActionReceiver.ACTION_GATE_TAP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        rv.setOnClickPendingIntent(R.id.stats_v2_gate_tap, tap)
        rv.setOnClickPendingIntent(R.id.stats_v2_light_tap, android.app.PendingIntent.getBroadcast(
            context, 7314,
            android.content.Intent(context, com.qext2.primary.actions.StatsActionReceiver::class.java)
                .setAction(com.qext2.primary.actions.StatsActionReceiver.ACTION_LIGHT_TAP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        ))
        return rv
    }

    private fun demoData(): StatsV2Data {
        val now = System.currentTimeMillis()
        return StatsV2Demo.at(now).copy(
            xssPlan = 120f, planDeltaMin = 14, planRefEtaMs = now + 4 * 3_600_000L,
            tempo = listOf(198 to 20.8f, 192 to 19.9f, 181 to 19.1f, 172 to 18.4f), tempoPartial = true,
            gate = com.qext2.primary.gate.GateController.ui.value, lightState = "OK",
        )
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

    internal fun toData(agg: RideDataAggregator?, s: StatsRideSnapshot): StatsV2Data {
        val dtdKm = (agg?.getDistanceToDestinationMeters() ?: 0.0) / 1000.0
        val pos = agg?.getRoutePositionM()?.let { (it / 1000.0).toFloat() } ?: s.distanceKm   // E4.1
        val total = if (s.hasRoute && dtdKm > 0.05) pos + dtdKm.toFloat() else null
        val surf = if (SurfaceBridge.hasProfile()) agg?.navRemainingByType(pos.coerceAtLeast(0f)) else null
        val pl = StatsV2Extras.plan(if (s.etaModelReady && s.etaTimestamp > 0L) s.etaTimestamp else null, s.distanceKm)
        val tp = RideWindows.tempoSnapshot()
        PlanProfile.tick(if (s.hasRoute) com.qext2.primary.surface.SurfaceBridge.lastRouteName else null)
        val pq = if (s.hasRoute) PlanProfile.at(pos) else null
        val dDrive = pq?.let { Math.round(s.movingElapsedSec / 60f - it.first) }
        val dStop = pq?.let { Math.round((s.grossElapsedSec - s.movingElapsedSec).coerceAtLeast(0L) / 60f - it.second) }
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
                .map { (it.kmEnd - maxOf(it.kmStart, pos)) to android.graphics.Color.parseColor(when (it.surface) { SurfaceType.PAVED -> "#F2F4F7"; SurfaceType.GRAVEL -> "#FFB300"; SurfaceType.LOOSE -> "#FF2A2A" }) }
                else null,
            stopsKm = agg?.getLongStopsKm()?.map { it.toFloat() } ?: emptyList(),
            xssPlan = StatsV2Extras.xssPlan,
            planDeltaMin = if (dDrive != null && dStop != null) dDrive + dStop else pl.first,
            planRefEtaMs = if (pq != null) null else pl.second,
            planDriveDelta = dDrive,
            planStopDelta = dStop,
            planFromQbot = pq != null,
            light = com.qext2.primary.light.LightController.ui(),
            np5Now = s.np5Watts.takeIf { it > 0 },
            camBat = com.qext2.primary.osmo.OsmoLink.battery.takeIf { it in 0..100 && com.qext2.primary.osmo.OsmoLink.connected },   // 0.4.26
            camOn = com.qext2.primary.osmo.OsmoLink.address() != null,
            tempo = tp.first,
            tempoPartial = tp.second,
            gate = com.qext2.primary.gate.GateController.ui.value,
            lightState = agg?.getRadarState()?.first,
        )
    }
}
