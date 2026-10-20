package com.qext2.primary.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 20.10.2026: zatwierdza systemowe okno "Allow wireless debugging on this network?"
 * (com.android.systemui/.wifi.WifiDebuggingActivity: android:id/alwaysUse + android:id/button1 "ALLOW").
 * Karoo nie zapisuje zaufanych sieci (dumpsys adb: brak wpisow sieci mimo "Always allow"), wiec okno wracalo
 * przy kazdym starcie. Dziala TYLKO gdy uzbrojone przez DarkMenu (45 s) i TYLKO dla tego jednego okna.
 */
class DebugDialogClicker : AccessibilityService() {
    companion object {
        @Volatile var armedUntilMs = 0L
        @Volatile var lastResult: String? = null
        private const val SYSUI = "com.android.systemui"

        fun arm(ms: Long = 45_000L) { armedUntilMs = System.currentTimeMillis() + ms; lastResult = null }
        fun disarm() { armedUntilMs = 0L }

        /** Wlacza usluge w ustawieniach systemu (wymaga WRITE_SECURE_SETTINGS). Zwraca true, gdy jest wlaczona. */
        fun ensureEnabled(ctx: Context): Boolean = try {
            val cr = ctx.contentResolver
            val me = ComponentName(ctx, DebugDialogClicker::class.java).flattenToString()
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val list = cur.split(':').filter { it.isNotBlank() }
            if (list.none { it.equals(me, true) }) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + me).joinToString(":"))
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            true
        } catch (e: Exception) {
            lastResult = "ENABLE_ERR:" + e.javaClass.simpleName
            false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || System.currentTimeMillis() > armedUntilMs) return
        if (event.packageName?.toString() != SYSUI) return
        val root = rootInActiveWindow ?: return
        try {
            val always = root.findAccessibilityNodeInfosByViewId("android:id/alwaysUse").firstOrNull() ?: return
            val allow = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull() ?: return
            // tylko okno debugowania bezprzewodowego: tytul o debugowaniu (EN/PL) albo klasa okna WifiDebuggingActivity
            val title = root.findAccessibilityNodeInfosByViewId("android:id/alertTitle").firstOrNull()?.text?.toString()?.lowercase() ?: ""
            val cls = event.className?.toString() ?: ""
            val isWifiDbg = cls.contains("WifiDebugging") || title.contains("wireless debugging") || title.contains("debugowan")
            if (!isWifiDbg) return
            if (!always.isChecked) always.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val ok = allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            lastResult = if (ok) "CLICKED" else "CLICK_FAIL"
            if (ok) disarm()
            try { com.qext2.primary.util.RideFileLog.append("DARKMENU_DIALOG $lastResult") } catch (_: Exception) {}
        } catch (e: Exception) {
            lastResult = "ERR:" + e.javaClass.simpleName
        }
    }

    override fun onInterrupt() {}
}
