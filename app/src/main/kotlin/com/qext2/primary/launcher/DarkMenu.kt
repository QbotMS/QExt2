package com.qext2.primary.launcher

import android.content.Context
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec

/**
 * 20.10.2026 (0.4.6): ciemne menu Karoo po restarcie.
 * Wymuszony ciemny tryb (debug.hwui.force_dark) moze ustawic tylko powloka adb i znika po restarcie.
 * QExt2 raz na uruchomienie Karoo: wlacza debugowanie bezprzewodowe (adb_wifi_enabled, dziala tylko w sieci
 * Wi-Fi zatwierdzonej na Karoo), laczy sie z wlasnym adbd przez TLS kluczem zatwierdzonym raz przez kabel
 * (pliki <files>/adb/qext2_key.der PKCS8 + qext2_cert.der X.509), ustawia ciemny tryb, przeladowuje ekran
 * startowy i wylacza debugowanie. Najwyzej 3 proby na uruchomienie (inaczej Karoo pyta o siec co 30 min).
 */
class QAdbManager(private val key: PrivateKey, private val cert: Certificate) : AbsAdbConnectionManager() {
    init { setApi(android.os.Build.VERSION.SDK_INT) }
    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "QExt2"
}

object DarkMenu {
    private const val PREFS = "qext2_dark_menu"
    private const val CMD = "shell:setprop debug.hwui.force_dark true; am force-stop io.hammerhead.launcherapp; echo QEXT2_OK"

    /** Wynik: DONE_THIS_BOOT | GIVEUP | NO_KEY | CONNECT_FAIL | SET ... | ERR:... (blokuje - wolac z osobnego watku). */
    fun ensure(ctx: Context): String {
        val cr = ctx.contentResolver
        val boot = Settings.Global.getInt(cr, "boot_count", -1)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (boot >= 0 && p.getInt("done_boot", -2) == boot) return "DONE_THIS_BOOT"
        val att = if (p.getInt("att_boot", -2) == boot) p.getInt("att", 0) else 0
        if (att >= 3) return "GIVEUP"
        p.edit().putInt("att_boot", boot).putInt("att", att + 1).apply()
        val dir = File(ctx.getExternalFilesDir(null), "adb")
        val kf = File(dir, "qext2_key.der")
        val cf = File(dir, "qext2_cert.der")
        if (!kf.exists() || !cf.exists()) return "NO_KEY"
        val wasOn = try { Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1 } catch (e: Exception) { false }
        var m: QAdbManager? = null
        return try {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
            val cert = cf.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            if (!wasOn) Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            Thread.sleep(3000)
            m = QAdbManager(key, cert)
            if (!m.autoConnect(ctx.applicationContext, 15_000)) return "CONNECT_FAIL try=${att + 1}"
            val s = m.openStream(CMD)
            val out = s.openInputStream().bufferedReader().readText()
            try { s.close() } catch (e: Exception) { }
            if (out.contains("QEXT2_OK")) {
                p.edit().putInt("done_boot", boot).apply()
                "SET boot=$boot"
            } else "ERR:out=" + out.trim().take(60)
        } catch (e: Throwable) {
            "ERR:" + e.javaClass.simpleName + ":" + (e.message ?: "").take(80)
        } finally {
            try { m?.close() } catch (e: Exception) { }
            if (!wasOn) try { Settings.Global.putInt(cr, "adb_wifi_enabled", 0) } catch (e: Exception) { }
        }
    }
}
