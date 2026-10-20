package com.qext2.primary.launcher

import android.content.Context
import android.provider.Settings

/**
 * 20.10.2026 (0.4.5): tlo ekranu startowego Karoo z QBota.
 * Karoo trzyma tlo jako adres obrazka w ustawieniu globalnym launcher.background.full_url
 * (zmiana dziala od razu). Zapis wymaga WRITE_SECURE_SETTINGS nadanego raz przez adb:
 *   adb shell pm grant com.qext2.primary android.permission.WRITE_SECURE_SETTINGS
 * Adres "aktualny" (z kluczem) przychodzi w ride-readiness jako homeBgUrl; QBot zwraca z niego {url} obrazka.
 */
object HomeBackground {
    private const val PREFS = "qext2_home_bg"
    private const val KEY_SRC = "src"
    private const val FULL = "launcher.background.full_url"
    private const val THUMB = "launcher.background.thumbnail_url"

    fun sourceUrl(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SRC, null)

    fun saveSourceUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SRC, url).apply()
    }

    /** Wynik: SAME (bez zmian) | SET (wpisane) | NO_PERMISSION (brak pm grant) | ERR:<typ>. */
    fun apply(ctx: Context, url: String): String = try {
        val cr = ctx.contentResolver
        if (Settings.Global.getString(cr, FULL) == url) "SAME" else {
            Settings.Global.putString(cr, FULL, url)
            Settings.Global.putString(cr, THUMB, url)
            "SET"
        }
    } catch (e: SecurityException) {
        "NO_PERMISSION"
    } catch (e: Exception) {
        "ERR:" + e.javaClass.simpleName
    }
}
