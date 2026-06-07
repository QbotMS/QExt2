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
            ext.aggregatorFlow
                .flatMapLatest { agg ->
                    agg?.statsSnapshot ?: flowOf(StatsRideSnapshot())
                }
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
        val carbClickId = System.currentTimeMillis()
        val carbIntent = PendingIntent.getBroadcast(
            context,
            REQ_CARB,
            Intent(context, StatsActionReceiver::class.java)
                .setAction(StatsActionReceiver.ACTION_CARB_ADD)
                .putExtra(StatsActionReceiver.EXTRA_CARB_CLICK_ID, carbClickId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val gateIntent = PendingIntent.getBroadcast(
            context,
            REQ_GATE,
            Intent(context, StatsActionReceiver::class.java).setAction(StatsActionReceiver.ACTION_GATE_TAP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        v.setOnClickPendingIntent(R.id.stats_btn_carb, carbIntent)
        v.setOnClickPendingIntent(R.id.stats_btn_gate, gateIntent)

        val carbPacket = AthleteDataStore.loadCarbPacketSize()
        val carbAdded = AthleteDataStore.loadCarbIntakeTotal()
        val lastTapMs = AthleteDataStore.loadCarbLastTapMs()
        val tapRecent = (System.currentTimeMillis() - lastTapMs) <= 1200L
        val carbBtnText = if (tapRecent) {
            "CARB +${carbPacket}g\nOK ${carbAdded}g"
        } else {
            "CARB +${carbPacket}g\nSUM ${carbAdded}g"
        }
        v.setTextViewText(R.id.tv_btn_carb, carbBtnText)
        v.setInt(R.id.stats_btn_carb, "setBackgroundColor", if (tapRecent) Color.parseColor("#16A34A") else Color.parseColor("#1F2937"))
        v.setTextViewText(R.id.tv_btn_gate, AthleteDataStore.loadGateUiState())

        setValue(v, R.id.tv_np, StatsValueFormatter.npW(snap.npWholeWatts).main)
        setValue(v, R.id.tv_if, StatsValueFormatter.ifValue(snap.ifWholeRide).main)
        setValue(v, R.id.tv_vi, StatsValueFormatter.vi(snap.viValue).main)
        v.setTextColor(R.id.tv_vi, viColor(snap.viValue))
        Log.d(TAG, "QEXT_STATS_ADV field=np value=${snap.npWholeWatts} status=OK reason=sdk_or_local")
        Log.d(TAG, "QEXT_STATS_ADV field=if value=${"%.2f".format(snap.ifWholeRide)} status=OK reason=sdk_or_local")
        Log.d(TAG, "QEXT_STATS_ADV field=vi value=${"%.2f".format(snap.viValue)} status=OK reason=sdk_or_local")

        bindAdvanced(v, R.id.tv_tss, StatsAdvancedFieldPolicy.sdkTss(snap.tssValue), "tss")
        bindAdvanced(v, R.id.tv_rsrv, StatsAdvancedFieldPolicy.localRsrv(snap.rsrvModelReady, snap.rideReservePercent), "rsrv")
        v.setTextColor(R.id.tv_rsrv, rsrvColor(snap.rideReservePercent, snap.rsrvModelReady))
        bindAdvanced(v, R.id.tv_eta, StatsAdvancedFieldPolicy.localEta(snap.hasRoute, snap.etaModelReady, snap.etaTimestamp), "eta")

        bindAdvanced(v, R.id.tv_carb, StatsAdvancedFieldPolicy.localCarb(snap.carbModelReady, snap.carbsGPerH), "carb")
        bindAdvanced(v, R.id.tv_carb_balance, StatsAdvancedFieldPolicy.localCarbBalance(snap.carbModelReady, snap.carbBalanceG), "carb_balance")
        bindAdvanced(v, R.id.tv_fluid, StatsAdvancedFieldPolicy.localFluid(snap.fluidModelReady, snap.fluidLPerH), "fluid")
        bindAdvanced(v, R.id.tv_cal, StatsAdvancedFieldPolicy.sdkCalories(snap.caloriesKcal), "kcal")

        bindAdvanced(v, R.id.tv_asc_done, StatsAdvancedFieldPolicy.ascentDone(snap.hasRoute, snap.routeClimbSourceReady, snap.ascentDoneM, snap.ascentLeftM), "up")
        bindAdvanced(v, R.id.tv_asc_left, StatsAdvancedFieldPolicy.ascentLeft(snap.hasRoute, snap.routeClimbSourceReady, snap.ascentDoneM, snap.ascentLeftM), "left")
        bindAdvanced(v, R.id.tv_avg_gross, StatsAdvancedFieldPolicy.localAvgGross(snap.distanceKm, snap.grossElapsedSec), "avg_gross")
        bindAdvanced(v, R.id.tv_wprime, StatsAdvancedFieldPolicy.localWPrime(snap.wPrimeModelReady, snap.wBalancePercent), "wprime")

        bindAdvanced(
            v,
            R.id.tv_bat_drain,
            StatsAdvancedFieldPolicy.batteryDrain(
                batterySourceReady = snap.batterySourceReady,
                batteryDrainReady = snap.batteryDrainReady,
                dropPctPerHour = snap.batteryDrainPctPerHour,
                batterySource = snap.batterySource,
            ),
            "battery_drain"
        )
        bindAdvanced(
            v,
            R.id.tv_bat_left,
            StatsAdvancedFieldPolicy.batteryLeft(
                batterySourceReady = snap.batterySourceReady,
                batteryEstimateReady = snap.batteryEstimateReady,
                leftSec = snap.batteryTimeLeftSec,
                batterySource = snap.batterySource,
            ),
            "battery_left"
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
        vi < 1.10f -> Color.parseColor("#F59E0B")
        else -> Color.parseColor("#EF4444")
    }

    private fun rsrvColor(reserve: Int, ready: Boolean): Int = when {
        !ready -> Color.WHITE
        reserve >= 40 -> Color.parseColor("#22C55E")
        reserve >= 20 -> Color.parseColor("#F59E0B")
        else -> Color.parseColor("#EF4444")
    }

    companion object {
        private const val REQ_CARB = 1001
        private const val REQ_GATE = 1002
    }
}
