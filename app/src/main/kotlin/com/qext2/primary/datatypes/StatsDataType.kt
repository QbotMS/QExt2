package com.qext2.primary.datatypes

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.Keep
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.R
import com.qext2.primary.actions.StatsActionReceiver
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.field.StatsAdvancedFieldPolicy
import com.qext2.primary.field.StatsFormattedValue
import com.qext2.primary.field.StatsValueFormatter
import com.qext2.primary.model.StatsRideSnapshot
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private const val TAG = "QExt2Stats"

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Keep
class StatsDataType : DataTypeImpl("qext2", "qext2-stats") {

    override fun startStream(emitter: Emitter<StreamState>) {
        emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = emptyMap())))
        emitter.setCancellable { Log.d(TAG, "startStream cancelled") }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        QExt2PrimaryExtension.instance?.onFieldVisible()
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        emitter.updateView(RemoteViews(context.packageName, R.layout.field_stats_3x3))

        scope.launch {
            val ext = QExt2PrimaryExtension.instance ?: return@launch
            combine(
                ext.aggregatorFlow.flatMapLatest { agg ->
                    agg?.statsSnapshot ?: flowOf(StatsRideSnapshot())
                },
                AthleteDataStore.gateUiTick,
            ) { snap, _ -> snap }
                .collect { snap ->
                    val next = RemoteViews(context.packageName, R.layout.field_stats_3x3)
                    bind(next, snap, context)
                    emitter.updateView(next)
                }
        }

        emitter.setCancellable {
            QExt2PrimaryExtension.instance?.onFieldHidden()
            scope.cancel()
        }
    }

    private fun bind(v: RemoteViews, snap: StatsRideSnapshot, context: Context) {
        AthleteDataStore.init(context)
        val gateIntent = PendingIntent.getBroadcast(
            context,
            REQ_GATE,
            Intent(context, StatsActionReceiver::class.java).setAction(StatsActionReceiver.ACTION_GATE_TAP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        v.setOnClickPendingIntent(R.id.stats_btn_gate, gateIntent)

        v.setTextViewText(R.id.tv_btn_gate, AthleteDataStore.loadGateUiState())

        setValue(v, R.id.tv_np, StatsValueFormatter.npW(snap.npWholeWatts).main)
        setValue(v, R.id.tv_ifeff, StatsValueFormatter.ifEff(snap.ifEffWholeRide).main)
        setValue(v, R.id.tv_vi, StatsValueFormatter.vi(snap.viValue).main)
        v.setTextColor(R.id.tv_vi, viColor(snap.viValue))
        Log.d(TAG, "QEXT_STATS_ADV field=np value=${snap.npWholeWatts} status=OK reason=sdk_or_local")
        Log.d(TAG, "QEXT_STATS_ADV field=if value=${"%.2f".format(snap.ifWholeRide)} status=OK reason=local_ftp_qbot")
        Log.d(TAG, "QEXT_STATS_ADV field=vi value=${"%.2f".format(snap.viValue)} status=OK reason=sdk_or_local")

        bindAdvanced(v, R.id.tv_xss, StatsAdvancedFieldPolicy.localXss(snap.xssValue), "xss")
        bindAdvanced(v, R.id.tv_rsrv, StatsAdvancedFieldPolicy.localRsrv(snap.rsrvModelReady, snap.rideReservePercent), "rsrv")
        v.setTextColor(R.id.tv_rsrv, rsrvColor(snap.rideReservePercent, snap.rsrvModelReady))
        bindAdvanced(v, R.id.tv_kcal, StatsAdvancedFieldPolicy.sdkCalories(snap.caloriesKcal), "kcal")

        bindAdvanced(v, R.id.tv_carb, StatsAdvancedFieldPolicy.localCarb(snap.carbModelReady, snap.carbsGPerH), "carb")
        setValue(v, R.id.tv_carb_balance, if (snap.carbModelReady) "${snap.carbNeededG}g" else "--")
        bindAdvanced(v, R.id.tv_fluid, StatsAdvancedFieldPolicy.localFluid(snap.fluidModelReady, snap.fluidLPerH), "fluid")
        setValue(v, R.id.tv_cad, if (snap.cadenceAvg > 0) snap.cadenceAvg.toString() else "--")

        bindAdvanced(v, R.id.tv_asc_done, StatsAdvancedFieldPolicy.ascentDone(snap.hasRoute, snap.routeClimbSourceReady, snap.ascentDoneM, snap.ascentLeftM), "up")
        bindAdvanced(v, R.id.tv_asc_left, StatsAdvancedFieldPolicy.ascentLeft(snap.hasRoute, snap.routeClimbSourceReady, snap.ascentDoneM, snap.ascentLeftM), "left")
        bindAdvanced(v, R.id.tv_avggross, StatsAdvancedFieldPolicy.localAvgGross(snap.distanceKm, snap.grossElapsedSec), "avg_gross")
        bindAdvanced(v, R.id.tv_eta, StatsAdvancedFieldPolicy.localEta(snap.hasRoute, snap.etaModelReady, snap.etaTimestamp), "eta")
        val stoppedSec = (snap.grossElapsedSec - snap.movingElapsedSec).coerceAtLeast(0L)
        setValue(v, R.id.tv_stops, if (snap.grossElapsedSec > 0L) String.format("%d:%02d", stoppedSec / 3600, (stoppedSec % 3600) / 60) else "--")
        val pavedLeft = snap.surfacePavedKmLeft
        val offLeft = snap.surfaceOffroadKmLeft
        if (pavedLeft >= 0f && offLeft >= 0f && (pavedLeft + offLeft) > 0.05f) {
            val total = pavedLeft + offLeft
            v.setProgressBar(R.id.pb_surface_paved, 100, ((pavedLeft / total) * 100f).toInt(), false)
            v.setProgressBar(R.id.pb_surface_offroad, 100, ((offLeft / total) * 100f).toInt(), false)
            setValue(v, R.id.tv_surface_paved_km, String.format("%.0fkm", pavedLeft))
            setValue(v, R.id.tv_surface_offroad_km, String.format("%.0fkm", offLeft))
        } else {
            v.setProgressBar(R.id.pb_surface_paved, 100, 0, false)
            v.setProgressBar(R.id.pb_surface_offroad, 100, 0, false)
            setValue(v, R.id.tv_surface_paved_km, "--")
            setValue(v, R.id.tv_surface_offroad_km, "--")
        }

        bindAdvanced(
            v,
            R.id.tv_batdrain,
            StatsAdvancedFieldPolicy.batteryDrain(
                batterySourceReady = snap.batterySourceReady,
                batteryDrainReady = snap.batteryDrainReady,
                dropPctPerHour = snap.batteryDrainPctPerHour,
                batterySource = snap.batterySource,
            ),
            "battery_drain"
        )

    }

    private fun setValue(v: RemoteViews, id: Int, text: String) {
        v.setTextViewText(id, text)
    }

    private fun bindAdvanced(v: RemoteViews, id: Int, decision: com.qext2.primary.field.AdvancedFieldDecision, name: String) {
        setValue(v, id, decision.value)
        val source = decision.source ?: "--"
        Log.d(TAG, "QEXT_STATS_ADV field=$name value=${decision.value} status=${decision.status} reason=${decision.reason} source=$source")
    }

    private fun viColor(vi: Float): Int = when {
        vi <= 0f -> Color.WHITE
        vi < 1.05f -> Color.WHITE
        vi < 1.10f -> Color.parseColor("#FACC15")
        else -> Color.parseColor("#FF5252")
    }

    private fun rsrvColor(reserve: Int, ready: Boolean): Int = when {
        !ready -> Color.WHITE
        reserve >= 40 -> Color.parseColor("#4ADE80")
        reserve >= 20 -> Color.parseColor("#FACC15")
        else -> Color.parseColor("#FF5252")
    }

    companion object {
        private const val REQ_GATE = 1002
    }
}
