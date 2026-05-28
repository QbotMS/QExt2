package com.qext2.primary.active

import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.qext2.primary.R

private const val TAG = "QEXT_ACTIVE_MSG"

object ActiveMessageRenderer {

    fun bind(views: RemoteViews, message: ActiveMessage?) {
        if (message == null) {
            views.setViewVisibility(R.id.message_overlay, View.GONE)
            Log.d(TAG, "BIND visible=false")
            return
        }

        val (bgColor, textColor) = severityColors(message.severity)

        views.setViewVisibility(R.id.message_overlay, View.VISIBLE)
        views.setInt(R.id.message_overlay, "setBackgroundColor", bgColor)
        views.setTextViewText(R.id.msg_title, message.title)
        views.setTextColor(R.id.msg_title, textColor)
        views.setTextViewText(R.id.msg_line1, message.line1)
        views.setTextColor(R.id.msg_line1, textColor)
        views.setTextViewText(R.id.msg_line2, message.line2 ?: "")
        views.setTextColor(R.id.msg_line2, textColor)
        views.setViewVisibility(R.id.msg_line2, if (message.line2 != null) View.VISIBLE else View.GONE)

        Log.d(TAG, "BIND visible=true severity=${message.severity} id=${message.id}")
    }

    fun resetTracker() {}

    private fun severityColors(severity: ActiveMessageSeverity): Pair<Int, Int> = when (severity) {
        ActiveMessageSeverity.INFO -> Pair(
            Color.parseColor("#3B82F6"),
            Color.parseColor("#111827"),
        )
        ActiveMessageSeverity.WARNING -> Pair(
            Color.parseColor("#FBBF24"),
            Color.parseColor("#111827"),
        )
        ActiveMessageSeverity.CRITICAL -> Pair(
            Color.parseColor("#DC2626"),
            Color.parseColor("#FFFFFF"),
        )
    }
}
