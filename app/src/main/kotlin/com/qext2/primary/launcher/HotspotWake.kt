package com.qext2.primary.launcher

import android.content.Context
import android.provider.Settings
import com.qext2.primary.BuildConfig
import com.qext2.primary.util.RideFileLog
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import org.json.JSONObject

/**
 * 20.10.2026 (0.4.18): Karoo po starcie bez Wi-Fi -> prosba do QBota (przez telefon, aplikacja Hammerhead) o obudzenie
 * hotspotu iPhone'a. QBot wysyla techniczny e-mail na alias iCloud, Skrot na iPhonie wlacza hotspot, Karoo sam sie
 * laczy (siec zapisana), DarkMenu wlacza ciemne menu po polaczeniu z Wi-Fi. Max 3 prosby na uruchomienie, co 10 min.
 */
object HotspotWake {
    @Volatile private var boot = -2
    @Volatile private var count = 0
    @Volatile private var lastMs = 0L

    fun request(ctx: Context, system: KarooSystemService, reason: String) {
        val b = Settings.Global.getInt(ctx.contentResolver, "boot_count", -1)
        if (b != boot) { boot = b; count = 0; lastMs = 0L }
        val now = System.currentTimeMillis()
        if (count >= 3 || now - lastMs < 10 * 60_000L) return
        val tok = BuildConfig.QEXT_READINESS_TOKEN.trim()
        if (tok.isEmpty()) return
        count++; lastMs = now
        val url = BuildConfig.QEXT_READINESS_URL.ifBlank { "https://qbot.cytr.us/ride-readiness" }
        val body = JSONObject().put("hotspotWake", JSONObject().put("boot", b).put("reason", reason)).toString().toByteArray()
        RideFileLog.append("HOTSPOT_WAKE send n=$count reason=$reason")
        var id: String? = null
        try {
            id = system.addConsumer<OnHttpResponse>(
                params = OnHttpResponse.MakeHttpRequest(method = "POST", url = url,
                    headers = mapOf("Authorization" to "Bearer $tok", "X-QExt2-Token" to tok, "Content-Type" to "application/json"),
                    body = body, waitForConnection = true),
                onError = { msg -> id?.let { system.removeConsumer(it) }; RideFileLog.append("HOTSPOT_WAKE_FAIL sdk=$msg") },
                onEvent = { r ->
                    val s = r.state
                    if (s is HttpResponseState.Complete) {
                        id?.let { system.removeConsumer(it) }
                        RideFileLog.append("HOTSPOT_WAKE http=${s.statusCode}")
                    }
                },
            )
        } catch (e: Exception) {
            RideFileLog.append("HOTSPOT_WAKE_FAIL ${e.javaClass.simpleName}")
        }
    }
}
