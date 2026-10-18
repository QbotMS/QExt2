package com.qext2.primary.info

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.Keep
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.R
import com.qext2.primary.actions.StatsActionReceiver
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.model.StatsRideSnapshot
import com.qext2.primary.statsv2.StatsV2DataType
import com.qext2.primary.statsv2.StatsV2Renderer
import com.qext2.primary.surface.SurfaceBridge
import com.qext2.primary.util.RideFileLog
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "QExt2Info"

/**
 * Pole INFO (16.10.2026, docs/INFO.md): TRASA + PRZEWYZSZENIE (przeniesione ze STATS v2) + POGODA na trasie / w kolejnych godzinach.
 * Dotkniecie pola zmienia skale wykresu pogody: 20 km / 50 km / cala reszta (bez trasy: 1 h / 3 h / 6 h).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Keep
class InfoDataType : DataTypeImpl("qext2", "qext2-info") {

    private val mapper by lazy { StatsV2DataType("qext2-info-mapper", forceLive = true) }

    override fun startStream(emitter: Emitter<StreamState>) {
        emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = emptyMap())))
        emitter.setCancellable { Log.d(TAG, "startStream cancelled") }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        QExt2PrimaryExtension.instance?.onFieldVisible()
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        AthleteDataStore.init(context)
        com.qext2.primary.kokpit.Kokpit2Fonts.init(context)
        val w = config.viewSize.first.coerceAtLeast(120)
        val h = config.viewSize.second.coerceAtLeast(160)
        RideFileLog.append("VIEW INFO size=${w}x$h")
        val tap = PendingIntent.getBroadcast(
            context, 7313,
            Intent(context, StatsActionReceiver::class.java).setAction(StatsActionReceiver.ACTION_INFO_SCALE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun rv(): RemoteViews = RemoteViews(context.packageName, R.layout.field_info).also { it.setOnClickPendingIntent(R.id.info_root, tap) }
        emitter.updateView(rv())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val ext = QExt2PrimaryExtension.instance ?: return@launch
            var lastMs = 0L
            var lastVer = -1L
            ext.aggregatorFlow
                .flatMapLatest { agg -> (agg?.statsSnapshot ?: flowOf(StatsRideSnapshot())).combine(InfoWeather.version) { s, v -> Triple(agg, s, v) }
                    // 16.10: bez jazdy snapshot nie zmienia sie -> bez zegara pogoda nie byla nigdy pobierana (wisialo 'czekam na QBota')
                    .combine(kotlinx.coroutines.flow.flow { while (true) { emit(System.currentTimeMillis()); kotlinx.coroutines.delay(20_000L) } }) { t, _ -> t } }
                .collect { (agg, snap, ver) ->
                    val now = System.currentTimeMillis()
                    val demo = AthleteDataStore.loadKokpit2Demo()
                    val d = if (demo) com.qext2.primary.statsv2.StatsV2Demo.at(now) else (try { mapper.toData(agg, snap) } catch (e: Exception) { null } ?: return@collect)
                    if (!demo) try {
                        InfoWeather.tick(
                            routeNameIn = if (snap.hasRoute) SurfaceBridge.lastRouteName else null,
                            km = d.doneKm,
                            etaMs = d.etaMs,
                            lat = AthleteDataStore.loadLocationLat(),
                            lon = AthleteDataStore.loadLocationLon(),
                            heading = agg?.getHeadingDeg(),
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "tick fail ${e.message}")
                    }
                    if (now - lastMs < 4000L && ver == lastVer) return@collect   // zmiana skali / nowa pogoda -> od razu
                    lastVer = ver
                    lastMs = now
                    val dawn = if (demo) now - 6 * 3_600_000L else agg?.getCivilDawnMs()?.takeIf { it > 0L }
                    val dusk = if (demo) now + 4 * 3_600_000L + 20 * 60_000L else agg?.getCivilDuskMs()?.takeIf { it > 0L }
                    val bmp = try {
                        withContext(Dispatchers.Default) {
                            StatsV2Renderer.renderInfo(w, h, d, if (demo) InfoDemo.wx(now, d.doneKm, d.totalKm ?: (d.doneKm + 90f)) else InfoWeather.data,
                                InfoWeather.scale, now, dawn, dusk)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "render fail ${e.message}", e)
                        RideFileLog.append("RENDER_FAIL INFO msg=${e.message}")
                        null
                    } ?: return@collect
                    val r = rv()
                    r.setImageViewBitmap(R.id.iv_info, bmp)
                    emitter.updateView(r)
                }
        }
        emitter.setCancellable {
            QExt2PrimaryExtension.instance?.onFieldHidden()
            scope.cancel()
        }
    }
}
