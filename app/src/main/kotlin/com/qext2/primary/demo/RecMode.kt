package com.qext2.primary.demo

import android.content.Context
import android.content.SharedPreferences
import com.qext2.primary.util.RideFileLog

/**
 * Tryb NAGRANIE (21.10.2026, sesja demo-2110): do nagrania ekranu Karoo w domu, bez roweru.
 * Pola KOKPIT 2 / STATS v2 / INFO pokazuja scenariusz z assets/demo_nagranie.json (RecDemo), a QExt2 NIC nie wysyla
 * do QBota i zapisuje w pliku jazdy pole qext2_demo=1 (QBot pomija taka jazde przy imporcie).
 * Wlaczany i wylaczany WYLACZNIE recznie w SETUP QExt2 (decyzja Michala 21.10: bez auto-wylaczania).
 * Osobny od przelacznika "KOKPIT 2 i STATS v2: dane demo" (ten zostaje bez zmian -- do projektowania pol).
 * Ustawienie NIE trafia do kopii ustawien na serwerze (AthleteDataStore.SETTINGS_KEYS) -- osobny plik preferencji.
 */
object RecMode {
    private const val PREFS = "qext2_rec"
    private const val K_ON = "rec_mode"
    private const val K_DOT = "rec_dot"
    @Volatile private var prefs: SharedPreferences? = null

    /** Jazda wystartowana w trybie NAGRANIE: zostaje true do konca tej jazdy, nawet gdy tryb wylaczono w trakcie. */
    @Volatile var rideIsRec: Boolean = false

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun on(): Boolean = prefs?.getBoolean(K_ON, false) ?: false

    fun set(v: Boolean) {
        prefs?.edit()?.putBoolean(K_ON, v)?.apply()
        RideFileLog.append("REC_MODE " + if (v) "WLACZONY" else "wylaczony")
    }

    /** Mala kropka w rogu pol, ze tryb jest wlaczony (domyslnie widoczna, mozna ukryc do kadru). */
    fun dot(): Boolean = prefs?.getBoolean(K_DOT, true) ?: true

    fun setDot(v: Boolean) { prefs?.edit()?.putBoolean(K_DOT, v)?.apply() }

    /** true = nie wysylaj nic do QBota (tryb wlaczony albo trwa jazda nagrania). */
    fun blocked(): Boolean = !RecPolicy.allowSend(on(), rideIsRec)
}
