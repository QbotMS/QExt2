package com.qext2.primary.gate

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.Keep
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.R
import com.qext2.primary.actions.StatsActionReceiver
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.util.RideFileLog
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "QExt2Brama"

/**
 * Pole "QExt2 BRAMA" (16.10.2026, [GATE-PRZEBUDOWA]). Cale pole jest przyciskiem; ramka pokazuje stan:
 * niebieska = gotowa, zolta = potwierdz / wysylam / chwila, zielona = otwarta, czerwona = blad, szara przerywana = za daleko / brak GPS.
 */
@Keep
class BramaDataType : DataTypeImpl("qext2", "qext2-brama") {

    override fun startStream(emitter: Emitter<StreamState>) {
        emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = emptyMap())))
        emitter.setCancellable { Log.d(TAG, "startStream cancelled") }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        AthleteDataStore.init(context)
        GateController.init(context)
        RideFileLog.append("VIEW BRAMA size=${config.viewSize.first}x${config.viewSize.second}")
        val system = QExt2PrimaryExtension.instance?.karooSystem
        val locId = try {
            system?.addConsumer<OnLocationChanged>(onEvent = { GateController.onLocation(it.lat, it.lng) })
        } catch (e: Exception) {
            Log.w(TAG, "location consumer fail ${e.message}")
            null
        }
        GateController.ensureHome()
        val tap = PendingIntent.getBroadcast(
            context,
            REQ_BRAMA,
            Intent(context, StatsActionReceiver::class.java).setAction(StatsActionReceiver.ACTION_GATE_TAP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            GateController.ui.collect { ui -> emitter.updateView(render(context, ui, tap)) }
        }
        scope.launch {
            while (isActive) {
                delay(5_000L)
                GateController.refresh()
                GateController.ensureHome()
            }
        }
        emitter.setCancellable {
            scope.cancel()
            locId?.let { id -> system?.removeConsumer(id) }
        }
    }

    private fun render(context: Context, ui: GateController.Ui, tap: PendingIntent): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.field_brama)
        rv.setOnClickPendingIntent(R.id.brama_root, tap)
        rv.setInt(R.id.brama_root, "setBackgroundResource", background(ui.state))
        rv.setTextViewText(R.id.tv_brama_main, ui.main)
        rv.setTextViewText(R.id.tv_brama_sub, ui.sub)
        val grey = ui.state == GateController.State.FAR || ui.state == GateController.State.NO_GPS
        rv.setTextColor(R.id.tv_brama_main, if (grey) Color.parseColor("#9AA3AE") else Color.WHITE)
        return rv
    }

    private fun background(s: GateController.State): Int = when (s) {
        GateController.State.READY -> R.drawable.bg_brama_ready
        GateController.State.ARMED, GateController.State.SENDING, GateController.State.WAIT -> R.drawable.bg_brama_armed
        GateController.State.OPENED -> R.drawable.bg_brama_ok
        GateController.State.ERROR, GateController.State.NO_NET, GateController.State.TOO_FAR -> R.drawable.bg_brama_error
        GateController.State.FAR, GateController.State.NO_GPS -> R.drawable.bg_brama_off
    }

    companion object {
        private const val REQ_BRAMA = 7311
    }
}
