package com.qext2.primary.core

import android.util.Log
import pl.qbot.karoo.core.FieldComputers
import pl.qbot.karoo.core.FieldOutput
import pl.qbot.karoo.core.RideSample
import pl.qbot.karoo.core.RideState

private const val TAG = "QEXT_LAB_CORE"

object LabRideStateRepository {
    private val lock = Any()
    private val rideState = RideState()
    private val computers = FieldComputers()
    private var mvpOutputs: Map<String, FieldOutput> = emptyMap()

    fun update(sample: RideSample): Map<String, FieldOutput> {
        synchronized(lock) {
            rideState.update(sample)
            val outputs = computers.mvp(rideState)
            mvpOutputs = outputs.associateBy { it.name }
            outputs.forEach { out ->
                Log.i(
                    TAG,
                    "QEXT_FIELD_OUTPUT name=${out.name} value=${out.value} status=${out.status} reason=${out.reason} raw=${out.raw}"
                )
            }
            return mvpOutputs
        }
    }
}
