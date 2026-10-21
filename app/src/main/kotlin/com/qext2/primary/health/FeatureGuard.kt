package com.qext2.primary.health

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.qext2.primary.BuildConfig
import com.qext2.primary.util.RideFileLog
import org.json.JSONObject

/**
 * 21.10.2026 (0.4.19, sesja karoo-autotest-2110): bezpieczniki funkcji opartych na NIEOFICJALNYCH mechanizmach Karoo
 * (ciemne menu, tlo ekranu startowego, przelaczanie trybu lampy, budzenie hotspotu). Aktualizacja Karoo moze je zepsuc,
 * a wtedy nie moga wywracac reszty QExt2 (pola, nawigacja). Trzy powody wylaczenia funkcji:
 *  1. ZDALNIE - QBot w ride-readiness podaje liste qext2Disable (SETUP QBota > QExt2 > Autotest Karoo);
 *  2. BLEDY   - 3 bledy techniczne (wyjatek) pod rzad; blokada trwa do zmiany buildu QExt2 albo wersji Karoo;
 *  3. PETLA   - usluga QExt2 wystartowala >= 5 razy w 5 min (padanie jak 0.4.7) -> wszystkie ryzykowne funkcje
 *               wylaczone do restartu Karoo, reszta QExt2 dziala.
 * Opis: QBot docs/wiki/qext2/autotest-karoo.md
 */
object FeatureGuard {
    const val DARKMENU = "darkmenu"
    const val HOMEBG = "homebg"
    const val LIGHT = "light"
    const val HOTSPOT = "hotspot"
    val ALL = listOf(DARKMENU, HOMEBG, LIGHT, HOTSPOT)

    private const val PREFS = "qext2_guard"
    private const val LOOP_STARTS = 5
    private const val LOOP_WINDOW_MS = 5 * 60_000L

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var remote: Set<String> = emptySet()
    @Volatile var crashLoop = false
        private set

    /** Wolac w onCreate uslugi, po RideFileLog.init (zapis do dziennika jazdy). Nigdy nie rzuca. */
    fun init(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            remote = (p.getStringSet("remote", emptySet()) ?: emptySet()).toSet()
            // nowy build QExt2 albo nowa wersja Karoo -> stare blokady "BLEDY" kasowane (funkcja dostaje nowa szanse)
            val sig = BuildConfig.VERSION_CODE.toString() + "|" + KarooHealth.systemSig(ctx)
            if (p.getString("sig", null) != sig) {
                val e = p.edit().putString("sig", sig)
                ALL.forEach { e.remove("fail_$it"); e.remove("off_$it") }
                e.apply()
            }
            val now = System.currentTimeMillis()
            val boot = Settings.Global.getInt(ctx.contentResolver, "boot_count", -1)
            val starts = (p.getString("starts", "") ?: "").split(',')
                .mapNotNull { it.toLongOrNull() }.filter { now - it in 0 until LOOP_WINDOW_MS } + now
            val e = p.edit().putString("starts", starts.joinToString(","))
            if (starts.size >= LOOP_STARTS && boot >= 0) e.putInt("loop_boot", boot)
            e.apply()
            crashLoop = boot >= 0 && p.getInt("loop_boot", -2) == boot
            if (crashLoop) RideFileLog.append("GUARD CRASHLOOP starty=${starts.size}/5min - ciemne menu, tlo, lampa, hotspot wylaczone do restartu Karoo")
        } catch (e: Throwable) {
            try { RideFileLog.append("GUARD_INIT_FAIL ${e.javaClass.simpleName}") } catch (_: Throwable) {}
        }
    }

    fun allowed(f: String): Boolean = reason(f) == null

    /** null = funkcja wlaczona; inaczej: petla | zdalnie | bledy */
    fun reason(f: String): String? = when {
        crashLoop -> "petla"
        f in remote -> "zdalnie"
        prefs?.getBoolean("off_$f", false) == true -> "bledy"
        else -> null
    }

    /** Lista z QBota (ride-readiness qext2Disable). Nieznane nazwy pomijane. */
    fun setRemote(list: Collection<String>) {
        val s = list.filter { it in ALL }.toSet()
        if (s == remote) return
        remote = s
        prefs?.edit()?.putStringSet("remote", s)?.apply()
        try { RideFileLog.append("GUARD REMOTE off=" + s.joinToString(",").ifEmpty { "-" }) } catch (_: Throwable) {}
    }

    /** Wynik dzialania funkcji. ok=false tylko dla bledu technicznego (wyjatek), nie dla braku Wi-Fi itp. */
    fun report(f: String, ok: Boolean) {
        val p = prefs ?: return
        if (ok) {
            if (p.getInt("fail_$f", 0) != 0) p.edit().putInt("fail_$f", 0).apply()
            return
        }
        val n = p.getInt("fail_$f", 0) + 1
        val e = p.edit().putInt("fail_$f", n)
        if (n >= 3 && !p.getBoolean("off_$f", false)) {
            e.putBoolean("off_$f", true)
            try { RideFileLog.append("GUARD OFF $f po $n bledach z rzedu (do nowego buildu QExt2 lub nowej wersji Karoo)") } catch (_: Throwable) {}
        }
        e.apply()
    }

    /** {funkcja: powod} tylko dla wylaczonych - do raportu autotestu. */
    fun disabledJson(): JSONObject {
        val o = JSONObject()
        ALL.forEach { f -> reason(f)?.let { o.put(f, it) } }
        return o
    }
}
