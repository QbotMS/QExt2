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
 * Test 20.10 (build 365): bez flagReportViewIds wyszukiwanie po id nic nie zwracalo - okno zostawalo.
 */
class DebugDialogClicker : AccessibilityService() {
    companion object {
        @Volatile var armedUntilMs = 0L
        @Volatile var lastResult: String? = null
        @Volatile var instance: DebugDialogClicker? = null
        private const val SYSUI = "com.android.systemui"

        fun arm(ms: Long = 45_000L) { armedUntilMs = System.currentTimeMillis() + ms; lastResult = null }
        fun disarm() { armedUntilMs = 0L }
        fun armed() = System.currentTimeMillis() <= armedUntilMs

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

        /** 0.4.20: zdejmuje usluge z ustawien systemu (tryb ciemny wylaczony w QBocie). Wynik: REMOVED | ABSENT | ERR:... */
        fun disableInSystem(ctx: Context): String = try {
            val cr = ctx.contentResolver
            val me = ComponentName(ctx, DebugDialogClicker::class.java).flattenToString()
            val list = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').filter { it.isNotBlank() }
            if (list.none { it.equals(me, true) }) "ABSENT" else {
                val rest = list.filterNot { it.equals(me, true) }
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, rest.joinToString(":"))
                if (rest.isEmpty()) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
                "REMOVED"
            }
        } catch (e: Exception) {
            "ERR:" + e.javaClass.simpleName
        }

        /** Aktywne sprawdzenie (DarkMenu odpytuje co 0,5 s): true = okno zatwierdzone. */
        fun pokeNow(): Boolean = instance?.tryClick(null) == true
    }

    override fun onServiceConnected() { instance = this; if (armed()) tryClick(null) }

    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !armed()) return
        if (event.packageName?.toString() != SYSUI) return
        tryClick(event.className?.toString())
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { out.add(it) }
        try { windows?.forEach { w -> w.root?.let { r -> if (r.packageName?.toString() == SYSUI) out.add(r) } } } catch (_: Exception) {}
        return out
    }

    @Synchronized
    fun tryClick(cls: String?): Boolean {
        if (!armed()) return false
        try {
            for (root in roots()) {
                if (root.packageName?.toString() != SYSUI) continue
                val always = root.findAccessibilityNodeInfosByViewId("android:id/alwaysUse").firstOrNull() ?: continue
                val allow = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull() ?: continue
                // tylko okno debugowania bezprzewodowego: tytul (EN/PL) albo klasa okna WifiDebuggingActivity
                val title = root.findAccessibilityNodeInfosByViewId("android:id/alertTitle").firstOrNull()?.text?.toString()?.lowercase() ?: ""
                val isWifiDbg = (cls ?: "").contains("WifiDebugging") || title.contains("wireless debugging") || title.contains("debugowan")
                if (!isWifiDbg) continue
                if (!always.isChecked) always.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val ok = allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastResult = if (ok) "CLICKED" else "CLICK_FAIL"
                try { com.qext2.primary.util.RideFileLog.append("DARKMENU_DIALOG $lastResult") } catch (_: Exception) {}
                if (ok) { disarm(); return true }
            }
        } catch (e: Exception) {
            lastResult = "ERR:" + e.javaClass.simpleName
        }
        return false
    }

    override fun onInterrupt() {}
}
