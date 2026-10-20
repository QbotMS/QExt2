package com.qext2.primary.util

import android.util.Base64
import com.qext2.primary.BuildConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * 20.10.2026: dziennik jazdy -> QBot (SETUP > QExt2 > Dzienniki jazdy).
 * Koniec jazdy: z ride_log(.1).txt bierzemy fragment od ostatniego RIDE_START, gzip + base64, zapis jako
 * pending_ride_log_<start>.json. Wysylka: POST /ride-readiness {"rideLog": {startMs, endMs, build, gz}} z tokenem
 * urzadzenia, waitForConnection=true (Karoo samo czeka na siec). Sukces (200) -> plik usuniety; inaczej zostaje
 * i idzie przy starcie uslugi, starcie i koncu kolejnej jazdy. Kazdy krok w dzienniku (RIDE_LOG_*).
 */
object RideLogUpload {
    private const val MAX_B64 = 700_000          // limit IPC Karoo (binder ~1 MB) z zapasem
    private const val SEND_GUARD_MS = 10 * 60_000L
    @Volatile private var dir: File? = null
    @Volatile private var sendingSince = 0L
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(d: File?) { dir = d }

    /** Koniec jazdy: przygotuj paczke do wysylki (nie wymaga sieci). */
    fun onRideEnd(build: Int) {
        val d = dir ?: return
        try {
            val all = listOf(File(d, "ride_log.1.txt"), File(d, "ride_log.txt"))
                .filter { it.exists() }.joinToString("") { it.readText() }
            val i = all.lastIndexOf(" RIDE_START ")
            if (i < 0) { RideFileLog.append("RIDE_LOG_SKIP brak RIDE_START"); return }
            var text = all.substring(maxOf(0, all.lastIndexOf('\n', i) + 1))
            val startMs = parseMs(text) ?: System.currentTimeMillis()
            var gz = b64gz(text)
            if (gz.length > MAX_B64) {
                // za duzo: wszystkie wpisy bramy + koncowka dziennika
                val gate = text.lineSequence().filter { it.contains("GATE_") }.joinToString("\n")
                while (gz.length > MAX_B64 && text.length > 20_000) {
                    text = text.substring(text.length / 2)
                    gz = b64gz("[OBCIETE: poczatek jazdy pominiety, wpisy bramy ponizej]\n$gate\n[KONCOWKA]\n$text")
                }
            }
            val body = JSONObject().put("rideLog", JSONObject().put("startMs", startMs)
                .put("endMs", System.currentTimeMillis()).put("build", build).put("gz", gz)).toString()
            File(d, "pending_ride_log_$startMs.json").writeText(body)
            RideFileLog.append("RIDE_LOG_QUEUED start=$startMs b64=${gz.length}")
        } catch (e: Exception) {
            RideFileLog.append("RIDE_LOG_QUEUE_FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Wyslij najstarsza oczekujaca paczke; po sukcesie kolejna. Bez tokenu w buildzie -> nic. */
    fun trySend(system: KarooSystemService) {
        val d = dir ?: return
        val tok = BuildConfig.QEXT_READINESS_TOKEN.trim()
        if (tok.isEmpty()) return
        val now = System.currentTimeMillis()
        if (sendingSince > 0 && now - sendingSince < SEND_GUARD_MS) return
        val f = d.listFiles { x -> x.name.startsWith("pending_ride_log_") && x.name.endsWith(".json") }
            ?.minByOrNull { it.name } ?: return
        val url = BuildConfig.QEXT_READINESS_URL.ifBlank { "https://qbot.cytr.us/ride-readiness" }
        val body = try { f.readBytes() } catch (_: Exception) { return }
        sendingSince = now
        RideFileLog.append("RIDE_LOG_SEND ${f.name} bytes=${body.size}")
        var id: String? = null
        try {
            id = system.addConsumer<OnHttpResponse>(
                params = OnHttpResponse.MakeHttpRequest(method = "POST", url = url,
                    headers = mapOf("Authorization" to "Bearer $tok", "X-QExt2-Token" to tok, "Content-Type" to "application/json"),
                    body = body, waitForConnection = true),
                onError = { msg ->
                    id?.let { system.removeConsumer(it) }
                    sendingSince = 0L
                    RideFileLog.append("RIDE_LOG_FAIL sdk=$msg")
                },
                onEvent = { r ->
                    val s = r.state
                    if (s is HttpResponseState.Complete) {
                        id?.let { system.removeConsumer(it) }
                        sendingSince = 0L
                        if (s.statusCode == 200) {
                            f.delete()
                            RideFileLog.append("RIDE_LOG_SENT ${f.name}")
                            trySend(system)
                        } else RideFileLog.append("RIDE_LOG_FAIL http=${s.statusCode} err=${s.error ?: "-"}")
                    }
                },
            )
        } catch (e: Exception) {
            sendingSince = 0L
            RideFileLog.append("RIDE_LOG_FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun parseMs(text: String): Long? = try { df.parse(text.take(19))?.time } catch (_: Exception) { null }

    private fun b64gz(s: String): String {
        val bo = ByteArrayOutputStream()
        GZIPOutputStream(bo).use { it.write(s.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }
}
