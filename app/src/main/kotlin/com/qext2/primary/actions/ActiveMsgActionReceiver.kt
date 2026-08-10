package com.qext2.primary.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.qext2.primary.active.ActiveMessageBus

private const val TAG = "QExt2ActiveMsgAction"

/**
 * Tap na nakladce komunikatu ACTIVE = zwolnienie (dismiss).
 * Menedzer wycisza stabilny klucz komunikatu na 30 s — jesli stan trwa,
 * producent przywroci komunikat po tym czasie; zmiana stanu (inny klucz)
 * przebija sie natychmiast. Decyzja uzytkownika 2026-08-10.
 */
class ActiveMsgActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_MSG_DISMISS -> {
                val mgr = ActiveMessageBus.manager
                if (mgr == null) {
                    Log.w(TAG, "DISMISS ignored: no active manager registered")
                    return
                }
                val dismissed = mgr.dismissCurrent(System.currentTimeMillis())
                Log.i(TAG, "DISMISS tap id=${dismissed?.id ?: "none"}")
            }
        }
    }

    companion object {
        const val ACTION_MSG_DISMISS = "com.qext2.primary.action.ACTIVE_MSG_DISMISS"
    }
}
