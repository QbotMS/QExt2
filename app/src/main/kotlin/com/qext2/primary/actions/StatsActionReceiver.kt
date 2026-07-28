package com.qext2.primary.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.gate.GateOpenClient
import com.qext2.primary.gate.GateResult
import com.qext2.primary.gate.KarooSdkHttpCaller
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "QExt2StatsAction"
private const val GATE_RESET_DELAY_MS = 3000L
private const val GATE_ASYNC_TIMEOUT_MS = 8_000L

class StatsActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AthleteDataStore.init(context)
        when (intent.action) {
            ACTION_GATE_TAP -> {
                val pending = goAsync()
                val finished = AtomicBoolean(false)
                fun safeFinish() {
                    if (finished.compareAndSet(false, true)) {
                        pending.finish()
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    if (finished.compareAndSet(false, true)) {
                        Log.w(TAG, "GATE async timeout after ${GATE_ASYNC_TIMEOUT_MS}ms")
                        AthleteDataStore.saveGateUiState("FURTKA FAIL")
                        Handler(Looper.getMainLooper()).postDelayed({
                            AthleteDataStore.saveGateUiState("GATE")
                        }, GATE_RESET_DELAY_MS)
                        pending.finish()
                    }
                }, GATE_ASYNC_TIMEOUT_MS)

                val system = QExt2PrimaryExtension.instance?.karooSystem
                if (system == null) {
                    AthleteDataStore.saveGateUiState("FURTKA FAIL")
                    safeFinish()
                    return
                }
                AthleteDataStore.saveGateUiState("FURTKA...")
                val client = GateOpenClient(KarooSdkHttpCaller(system))
                client.openGate(AthleteDataStore.loadGateLastRequestMs()) { result ->
                    if (finished.get()) return@openGate
                    val now = System.currentTimeMillis()
                    when (result) {
                        GateResult.Ok -> {
                            AthleteDataStore.saveGateLastRequestMs(now)
                            AthleteDataStore.saveGateUiState("FURTKA OK")
                        }
                        GateResult.Forbidden -> AthleteDataStore.saveGateUiState("FURTKA FAIL")
                        GateResult.RateLimited -> AthleteDataStore.saveGateUiState("FURTKA WAIT")
                        GateResult.Error -> AthleteDataStore.saveGateUiState("FURTKA FAIL")
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        AthleteDataStore.saveGateUiState("GATE")
                    }, GATE_RESET_DELAY_MS)
                    safeFinish()
                }
            }
        }
    }

    companion object {
        const val ACTION_GATE_TAP = "com.qext2.primary.action.GATE_TAP"
    }
}
