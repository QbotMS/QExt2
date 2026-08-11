package com.qext2.primary.active

import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.qext2.primary.R
import com.qext2.primary.util.QExt2DebugConfig

private const val TAG = "QEXT_ACTIVE_MSG"

object ActiveMessageRenderer {

    // Kontrast w sloncu (jasnosc ekranu 16-24%): tresc komunikatu ZAWSZE
    // bialym tekstem na nieprzezroczystym ciemnym panelu (czytelnosc tekstu
    // przy drganiach), a waznosc niesie JASNY pasek naglowka z ciemnym
    // tytulem (widoczny katem oka). Uzasadnienie i plan powrotu:
    // docs/KONTRAST_2026-08.md (commit 2).
    private val PANEL_BG = 0xFF0D1424.toInt()
    private val BAR_TEXT = 0xFF0B0F1A.toInt()
    private val BODY_TEXT = Color.WHITE

    fun bind(views: RemoteViews, message: ActiveMessage?) {
        if (message == null) {
            views.setViewVisibility(R.id.message_overlay, View.GONE)
            if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "BIND visible=false")
            return
        }

        val barColor = severityBarColor(message.severity)

        views.setViewVisibility(R.id.message_overlay, View.VISIBLE)
        views.setInt(R.id.message_overlay, "setBackgroundColor", PANEL_BG)
        views.setInt(R.id.msg_title_bar, "setBackgroundColor", barColor)
        views.setTextViewText(R.id.msg_title, message.title)
        views.setTextColor(R.id.msg_title, BAR_TEXT)
        views.setTextViewText(R.id.msg_line1, message.line1)
        views.setTextColor(R.id.msg_line1, BODY_TEXT)
        views.setTextViewText(R.id.msg_line2, message.line2 ?: "")
        views.setTextColor(R.id.msg_line2, BODY_TEXT)
        views.setViewVisibility(R.id.msg_line2, if (message.line2 != null) View.VISIBLE else View.GONE)

        if (QExt2DebugConfig.DEBUG_LOGGING) Log.d(TAG, "BIND visible=true severity=${message.severity} id=${message.id}")
    }

    fun resetTracker() {}

    private fun severityBarColor(severity: ActiveMessageSeverity): Int = when (severity) {
        ActiveMessageSeverity.INFO -> 0xFF60A5FA.toInt()
        ActiveMessageSeverity.WARNING -> 0xFFFBBF24.toInt()
        ActiveMessageSeverity.CRITICAL -> 0xFFFF5252.toInt()
    }
}
