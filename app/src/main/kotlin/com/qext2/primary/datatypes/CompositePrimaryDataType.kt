package com.qext2.primary.datatypes

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.Keep
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.R
import com.qext2.primary.engine.PrimaryRenderOptimizer
import com.qext2.primary.model.PrimaryRideSnapshot
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

private const val TAG = "QExt2Primary"

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Keep
class CompositePrimaryDataType : DataTypeImpl("qext2", "qext2-primary") {

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.d(TAG, "startStream")
        emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = emptyMap())))
        emitter.setCancellable { Log.d(TAG, "startStream cancelled") }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "startView preview=${config.preview} grid=${config.gridSize} view=${config.viewSize}")
        val optimizer = PrimaryRenderOptimizer()
        optimizer.initializeMetricsFile(context.filesDir)
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        emitter.updateView(RemoteViews(context.packageName, R.layout.field_primary_4col))

        scope.launch {
            val ext = QExt2PrimaryExtension.instance ?: return@launch
            var aggNew = true
            ext.aggregatorFlow
                .flatMapLatest { agg ->
                    aggNew = true
                    agg?.snapshot ?: flowOf(PrimaryRideSnapshot())
                }
                .collect { latest ->
                    if (aggNew) {
                        optimizer.reset()
                        aggNew = false
                    }
                    val decision = optimizer.decide(latest, latest.speedKmh)
                    if (decision.shouldRender) {
                        val nv = RemoteViews(context.packageName, R.layout.field_primary_4col)
                        setPrimaryValues(nv, latest)
                        emitter.updateView(nv)
                    }
                }
        }

        emitter.setCancellable {
            Log.d(TAG, "startView cancelled")
            optimizer.reset()
            scope.cancel()
        }
    }

    private fun setPrimaryValues(views: RemoteViews, snap: PrimaryRideSnapshot) {
        val hrText = snap.hrDisplay
        views.setTextViewText(R.id.tv_hr, hrText)
        views.setTextColor(R.id.tv_hr, snap.hrColor)
        views.setTextViewText(R.id.tv_cadence, snap.cadenceDisplay)
        views.setTextColor(R.id.tv_cadence, snap.cadenceColor)

        val powerText = snap.powerDisplay
        hideAll(views, R.id.tv_power_3, R.id.tv_power_4, R.id.tv_power_5)
        val powerId = when {
            powerText.length <= 3 -> R.id.tv_power_3
            powerText.length == 4 -> R.id.tv_power_4
            else -> R.id.tv_power_5
        }
        views.setViewVisibility(powerId, View.VISIBLE)
        views.setTextViewText(powerId, powerText)
        views.setTextColor(powerId, snap.powerColor)

        val speedText = snap.speedDisplay
        hideAll(views, R.id.tv_speed_4, R.id.tv_speed_5, R.id.tv_speed_6)
        val speedId = when {
            speedText.length <= 4 -> R.id.tv_speed_4
            speedText.length == 5 -> R.id.tv_speed_5
            else -> R.id.tv_speed_6
        }
        views.setViewVisibility(speedId, View.VISIBLE)
        views.setTextViewText(speedId, speedText)
        views.setTextColor(speedId, snap.speedColor)

        val gearText = snap.gearDisplay
        val gearParts = gearText.split("×")
        if (gearText == "WAIT" || gearText == "NO" || gearParts.size != 2) {
            views.setTextViewText(R.id.tv_gear_front, gearText)
            views.setTextViewText(R.id.tv_gear_rear, "")
            views.setTextColor(R.id.tv_gear_front, Color.WHITE)
            views.setTextColor(R.id.tv_gear_rear, Color.WHITE)
            views.setViewVisibility(R.id.tv_gear_x, View.GONE)
        } else {
            views.setTextViewText(R.id.tv_gear_front, gearParts[0])
            views.setTextViewText(R.id.tv_gear_rear, gearParts[1])
            views.setTextColor(R.id.tv_gear_front, snap.gearColor)
            views.setTextColor(R.id.tv_gear_rear, snap.gearColor)
            views.setViewVisibility(R.id.tv_gear_x, View.VISIBLE)
        }

        val gradeText = snap.gradeDisplay
        views.setTextViewText(R.id.tv_grade, gradeText)
        views.setTextColor(R.id.tv_grade, snap.gradeColor)
        views.setTextViewText(R.id.tv_grade_unit, "%")
        val showGradeUnit = gradeText != "WAIT" && gradeText != "NO" && gradeText != "INV"
        views.setViewVisibility(R.id.tv_grade_unit, if (showGradeUnit) View.VISIBLE else View.GONE)
    }

    private fun hideAll(views: RemoteViews, vararg ids: Int) {
        for (id in ids) {
            views.setViewVisibility(id, View.GONE)
        }
    }

}
