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
private const val CARB_DEBOUNCE_MS = 700L
private const val GATE_RESET_DELAY_MS = 3000L
private const val GATE_ASYNC_TIMEOUT_MS = 20_000L

class StatsActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AthleteDataStore.init(context)
        when (intent.action) {
            ACTION_CARB_ADD -> {
                val nowMs = System.currentTimeMillis()
                val lastTapMs = AthleteDataStore.loadCarbLastTapMs()
                if (nowMs - lastTapMs < CARB_DEBOUNCE_MS) {
                    Log.d(TAG, "CARB_ADD debounced (${nowMs - lastTapMs}ms)")
                    return
                }
                val clickId = intent.getLongExtra(EXTRA_CARB_CLICK_ID, 0L)
                val lastClickId = AthleteDataStore.loadCarbLastClickId()
                if (clickId == lastClickId) {
                    Log.d(TAG, "CARB_ADD duplicate clickId=$clickId")
                    return
                }
                AthleteDataStore.saveCarbLastClickId(clickId)
                val packet = AthleteDataStore.loadCarbPacketSize()
                val total = AthleteDataStore.addCarbIntake(packet)
                AthleteDataStore.markCarbTapNow()
                Log.d(TAG, "CARB_ADD packet=${packet}g total=${total}g")
            }

            ACTION_CARB_UNDO -> {
                val packet = AthleteDataStore.loadCarbPacketSize()
                val total = AthleteDataStore.undoCarbIntake(packet)
                Log.d(TAG, "CARB_UNDO packet=${packet}g total=${total}g")
            }

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
        const val ACTION_CARB_ADD = "com.qext2.primary.action.CARB_ADD"
        const val ACTION_CARB_UNDO = "com.qext2.primary.action.CARB_UNDO"
        const val ACTION_GATE_TAP = "com.qext2.primary.action.GATE_TAP"
        const val EXTRA_CARB_CLICK_ID = "carb_click_id"
    }
}
