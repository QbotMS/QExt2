package com.qext2.primary.health

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.qext2.primary.BuildConfig
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.launcher.DarkMenu
import com.qext2.primary.launcher.DebugDialogClicker
import com.qext2.primary.launcher.HomeBackground
import com.qext2.primary.light.LightControl
import com.qext2.primary.util.RideFileLog
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import org.json.JSONObject

/**
 * 21.10.2026 (0.4.19, sesja karoo-autotest-2110): AUTOTEST QExt2 na Karoo.
 * Raz na uruchomienie uslugi (ok. 4 min po starcie, nigdy w czasie nagrywania jazdy) sprawdza kruche punkty QExt2
 * i wysyla wynik do QBota (POST ride-readiness {"karooHealth": ...}). QBot porownuje wersje Karoo z poprzednim
 * raportem: po aktualizacji Karoo albo gdy cos przestalo dzialac -> dzwonek QBota; historia w SETUP > QExt2.
 * Testy sa NIEINWAZYJNE (niczego nie zmieniaja na Karoo; lampa: tylko polaczenie z usluga, bez zmiany trybu).
 * Wyniki: OK | FAIL | SKIP (nie dalo sie sprawdzic, np. brak Wi-Fi albo funkcja wylaczona bezpiecznikiem).
 * Opis: QBot docs/wiki/qext2/autotest-karoo.md
 */
object KarooHealth {
    private val PKGS = listOf("io.hammerhead.launcherapp", "io.hammerhead.sensorservice")
    private const val BG_KEY = "launcher.background.full_url"

    private fun pkgVersion(ctx: Context, p: String): String = try {
        val i = ctx.packageManager.getPackageInfo(p, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else 0L
        "${i.versionName ?: "?"} ($code)"
    } catch (e: PackageManager.NameNotFoundException) { "brak" } catch (e: Throwable) { "?" }

    /** Podpis wersji Karoo: system + ekran startowy + usluga czujnikow. Zmiana = aktualizacja Karoo. */
    fun systemSig(ctx: Context): String =
        Build.DISPLAY + "|" + Build.VERSION.INCREMENTAL + "|" + PKGS.joinToString("|") { pkgVersion(ctx, it) }

    fun karooInfo(ctx: Context): JSONObject {
        val pk = JSONObject()
        PKGS.forEach { pk.put(it, pkgVersion(ctx, it)) }
        return JSONObject()
            .put("display", Build.DISPLAY)
            .put("incremental", Build.VERSION.INCREMENTAL)
            .put("fingerprint", Build.FINGERPRINT)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("patch", Build.VERSION.SECURITY_PATCH)
            .put("model", Build.MODEL)
            .put("pkgs", pk)
    }

    private fun sysProp(name: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, name) as? String) ?: ""
    } catch (e: Throwable) { "" }

    /** Blokuje do ~8 s (lampa) - wolac z osobnego watku. Nigdy nie rzuca. */
    fun runTests(ctx: Context, sdkConnected: Boolean): JSONObject {
        val r = JSONObject()
        fun put(id: String, s: String, d: String) { r.put(id, JSONObject().put("s", s).put("d", d)) }
        fun safe(id: String, block: () -> Unit) {
            try { block() } catch (e: Throwable) { put(id, "FAIL", "blad testu: " + e.javaClass.simpleName) }
        }
        val cr = ctx.contentResolver

        safe("sdk") {
            if (sdkConnected) put("sdk", "OK", "polaczenie z Karoo (karoo-ext) dziala")
            else put("sdk", "FAIL", "brak polaczenia z usluga Karoo (karoo-ext)")
        }
        safe("qbot") {
            val last = AthleteDataStore.loadLastRefresh()
            val ageH = if (last > 0L) (System.currentTimeMillis() - last) / 3_600_000.0 else -1.0
            when {
                last <= 0L -> put("qbot", "FAIL", "jeszcze nigdy nie pobrano danych z QBota")
                ageH <= 6.0 -> put("qbot", "OK", String.format(java.util.Locale.US, "dane z QBota sprzed %.1f h", ageH))
                else -> put("qbot", "FAIL", String.format(java.util.Locale.US, "dane z QBota sprzed %.0f h", ageH))
            }
        }
        val perm = try {
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) { false }
        safe("perm") {
            if (perm) put("perm", "OK", "uprawnienie do ustawien systemu jest")
            else put("perm", "FAIL", "brak uprawnienia WRITE_SECURE_SETTINGS (adb pm grant) - tlo i ciemne menu nie zadzialaja")
        }
        safe("homebg") {
            val why = FeatureGuard.reason(FeatureGuard.HOMEBG)
            val src = HomeBackground.sourceUrl(ctx)
            val cur = Settings.Global.getString(cr, BG_KEY)
            when {
                why != null -> put("homebg", "SKIP", "wylaczone bezpiecznikiem ($why)")
                src == null -> put("homebg", "SKIP", "QBot nie podal jeszcze adresu tla")
                !perm -> put("homebg", "FAIL", "brak uprawnienia do zapisu ustawienia tla")
                cur.isNullOrBlank() -> put("homebg", "FAIL", "ustawienie tla zniknelo - Karoo moglo zmienic mechanizm tla")
                !cur.contains("/karoo/tlo/") -> put("homebg", "FAIL", "Karoo nadpisalo tlo innym obrazkiem")
                else -> put("homebg", "OK", "tlo z QBota ustawione")
            }
        }
        safe("darkmenu") {
            val why = FeatureGuard.reason(FeatureGuard.DARKMENU)
            val prop = sysProp("debug.hwui.force_dark")
            when {
                why != null -> put("darkmenu", "SKIP", "wylaczone bezpiecznikiem ($why)")
                DarkMenu.doneThisBoot(ctx) && prop.isNotEmpty() && prop != "true" ->
                    put("darkmenu", "FAIL", "ustawione, ale Karoo go nie trzyma (force_dark=$prop)")
                DarkMenu.doneThisBoot(ctx) -> put("darkmenu", "OK", "ustawione w tym uruchomieniu Karoo")
                !DarkMenu.onWifi(ctx) && DarkMenu.lastResult == null -> put("darkmenu", "SKIP", "brak Wi-Fi od startu - nie bylo proby")
                else -> put("darkmenu", "FAIL", "nie ustawione: " + (DarkMenu.lastResult ?: "brak proby"))
            }
        }
        safe("clicker") {
            val why = FeatureGuard.reason(FeatureGuard.DARKMENU)
            val me = ComponentName(ctx, DebugDialogClicker::class.java).flattenToString()
            val en = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').any { it.equals(me, true) }
            when {
                why != null -> put("clicker", "SKIP", "wylaczone razem z ciemnym menu ($why)")
                !en -> put("clicker", "FAIL", "usluga zatwierdzajaca okno debugowania wylaczona w systemie")
                DebugDialogClicker.instance == null -> put("clicker", "FAIL", "system nie uruchomil uslugi zatwierdzajacej okno")
                else -> put("clicker", "OK", "usluga dziala" + (DebugDialogClicker.lastResult?.let { ", ostatnio: $it" } ?: ""))
            }
        }
        safe("light") {
            val why = FeatureGuard.reason(FeatureGuard.LIGHT)
            if (why != null) { put("light", "SKIP", "wylaczone bezpiecznikiem ($why)"); return@safe }
            val lc = LightControl(ctx.applicationContext)
            lc.bind()
            var waited = 0
            while (waited < 8_000 && !lc.ready()) { Thread.sleep(250); waited += 250 }
            val d = lc.diag()
            val ok = lc.ready()
            lc.unbind()
            if (ok) put("light", "OK", "sterowanie lampa dostepne")
            else put("light", "FAIL", "nieoficjalna usluga lampy Karoo nie odpowiada ($d)")
        }
        return r
    }

    /** Raport do QBota. Odpowiedz moze niesc liste qext2Disable (wylacznik zdalny) - stosowana od razu. */
    fun send(ctx: Context, system: KarooSystemService, report: JSONObject) {
        if (com.qext2.primary.demo.RecMode.blocked()) { RideFileLog.append("KAROO_HEALTH_SKIP tryb NAGRANIE"); return }   // 0.4.20
        val tok = BuildConfig.QEXT_READINESS_TOKEN.trim()
        if (tok.isEmpty()) { RideFileLog.append("KAROO_HEALTH_SKIP brak klucza urzadzenia"); return }
        val url = BuildConfig.QEXT_READINESS_URL.ifBlank { "https://qbot.cytr.us/ride-readiness" }
        val body = JSONObject().put("karooHealth", report).toString().toByteArray()
        var id: String? = null
        try {
            id = system.addConsumer<OnHttpResponse>(
                params = OnHttpResponse.MakeHttpRequest(method = "POST", url = url,
                    headers = mapOf("Authorization" to "Bearer $tok", "X-QExt2-Token" to tok, "Content-Type" to "application/json"),
                    body = body, waitForConnection = true),
                onError = { msg -> id?.let { system.removeConsumer(it) }; RideFileLog.append("KAROO_HEALTH_FAIL sdk=$msg") },
                onEvent = { resp ->
                    val s = resp.state
                    if (s is HttpResponseState.Complete) {
                        id?.let { system.removeConsumer(it) }
                        RideFileLog.append("KAROO_HEALTH_SENT http=${s.statusCode}")
                        try {
                            val b = s.body
                            if (s.statusCode == 200 && b != null) {
                                JSONObject(String(b)).optJSONArray("qext2Disable")?.let { a ->
                                    FeatureGuard.setRemote((0 until a.length()).map { a.optString(it) })
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                },
            )
        } catch (e: Throwable) {
            RideFileLog.append("KAROO_HEALTH_FAIL ${e.javaClass.simpleName}")
        }
    }

    /** Testy + wpis w dzienniku jazdy; zwraca raport (wysylka: send() na watku glownym). Blokuje - osobny watek. */
    fun buildReport(ctx: Context, sdkConnected: Boolean): JSONObject? {
        try {
            val res = runTests(ctx, sdkConnected)
            val fails = res.keys().asSequence().filter { res.optJSONObject(it)?.optString("s") == "FAIL" }.toList()
            val oks = res.keys().asSequence().count { res.optJSONObject(it)?.optString("s") == "OK" }
            RideFileLog.append("KAROO_HEALTH ok=$oks/${res.length()} fail=${fails.joinToString(",").ifEmpty { "-" }} karoo=${Build.DISPLAY}")
            val boot = Settings.Global.getInt(ctx.contentResolver, "boot_count", -1)
            val rep = JSONObject()
                .put("boot", boot)
                .put("build", BuildConfig.VERSION_CODE)
                .put("buildName", BuildConfig.VERSION_NAME)
                .put("karoo", karooInfo(ctx))
                .put("results", res)
                .put("guard", FeatureGuard.disabledJson())
                .put("crashLoop", FeatureGuard.crashLoop)
            return rep
        } catch (e: Throwable) {
            try { RideFileLog.append("KAROO_HEALTH_CRASH ${e.javaClass.simpleName}") } catch (_: Throwable) {}
            return null
        }
    }
}
