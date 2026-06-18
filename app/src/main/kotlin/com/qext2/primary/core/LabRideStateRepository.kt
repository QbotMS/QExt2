package com.qext2.primary.core

import android.util.Log
import com.qext2.primary.util.QExt2DebugConfig
import com.qext2.primary.model.SurfaceType
import pl.qbot.karoo.core.FieldComputers
import pl.qbot.karoo.core.FieldOutput
import pl.qbot.karoo.core.RideSample
import pl.qbot.karoo.core.RideState

private const val TAG = "QEXT_LAB_CORE"

data class RideContext(
    val surface: SurfaceType = SurfaceType.PAVED,
    val decouplingPct: Float = 0f,
    val effectiveLtp: Float = 0f,
    val todayFactor: Float = 1.0f,
)

object LabRideStateRepository {
    private val lock = Any()
    private val rideState = RideState()
    private val computers = FieldComputers()
    private var mvpOutputs: Map<String, FieldOutput> = emptyMap()

    fun update(sample: RideSample, context: RideContext = RideContext()): Map<String, FieldOutput> {
        synchronized(lock) {
            rideState.update(sample)
            val outputs = computers.mvp(rideState, context)
            mvpOutputs = outputs.associateBy { it.name }
            if (QExt2DebugConfig.DEBUG_LOGGING) {
                outputs.forEach { out ->
                    Log.i(
                        TAG,
                        "QEXT_FIELD_OUTPUT name=${out.name} value=${out.value} status=${out.status} reason=${out.reason} raw=${out.raw}"
                    )
                }
            }
            return mvpOutputs
        }
    }
}
