package com.qext2.primary.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.gate.GateController

/** Tapniecie w pole BRAMA (i stary przycisk w STATS) -> GateController (2 dotkniecia, tylko blisko domu). */
class StatsActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AthleteDataStore.init(context)
        when (intent.action) {
            ACTION_GATE_TAP -> {
                GateController.init(context)
                GateController.onTap()
            }
            ACTION_INFO_SCALE -> com.qext2.primary.info.InfoWeather.cycleScale()
        }
    }

    companion object {
        const val ACTION_GATE_TAP = "com.qext2.primary.action.GATE_TAP"
        const val ACTION_INFO_SCALE = "com.qext2.primary.action.INFO_SCALE"
    }
}
