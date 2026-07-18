package com.qext2.primary.datatypes

import android.content.Context
import android.widget.RemoteViews
import androidx.annotation.Keep
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.R
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Keep
class Cpe5DataType : DataTypeImpl("qext2", "qext2-cpe5") {

    override fun startStream(emitter: Emitter<StreamState>) {
        emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = emptyMap())))
        emitter.setCancellable { }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        QExt2PrimaryExtension.instance?.onFieldVisible()
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        emitter.updateView(RemoteViews(context.packageName, R.layout.field_single))
        scope.launch {
            val ext = QExt2PrimaryExtension.instance ?: return@launch
            ext.aggregatorFlow
                .flatMapLatest { agg -> agg?.statsSnapshot ?: flowOf(StatsRideSnapshot()) }
                .collect { snap ->
                    val next = RemoteViews(context.packageName, R.layout.field_single)
                    next.setTextViewText(R.id.tv_single_label, "CpE 5")
                    next.setTextViewText(R.id.tv_single_value, StatsValueFormatter.ifEff(snap.ifEff5Live).main)
                    emitter.updateView(next)
                }
        }
        emitter.setCancellable {
            QExt2PrimaryExtension.instance?.onFieldHidden()
            scope.cancel()
        }
    }
}
