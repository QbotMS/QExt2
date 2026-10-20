package com.qext2.primary.hrv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.PolarH10OfflineExerciseApi
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarExerciseEntry
import com.qext2.primary.BuildConfig
import com.qext2.primary.R
import com.qext2.primary.util.RideFileLog
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * 20.10.2026 (sesja hrv-h10-2010, QBot TODO [HRV-JAZDA] etap 1): HRV z jazdy z pasa Polar H10.
 *
 * Pas zapisuje odstepy RR do WLASNEJ pamieci (jedno nagranie naraz). W trakcie jazdy NIE ma polaczenia
 * Bluetooth z pasem; Karoo dostaje zwykle tetno przez ANT+. Dwa krotkie polaczenia (SDK Polara):
 *  - START jazdy: stare nagranie w pasie -> (nasze i bez kopii na Karoo: pobierz i zapisz) -> usun;
 *    potem "zacznij zapis RR" z id QX<czas startu ms>. Pas nie wstal? Ponowna proba co 30 s (4x).
 *  - KONIEC jazdy: stop zapisu, pobranie RR, paczka pending_rr_<id>.json na Karoo, wysylka do QBota
 *    (POST /ride-readiness {"hrvRr": {...}}, jak dziennik jazdy). Po 200 -> znacznik sent_rr_<id>.
 * Pamiec pasa czyszczona dopiero na starcie KOLEJNEJ jazdy, gdy dane sa juz w QBocie albo w pliku na Karoo.
 * Nie pobrano na koncu (pas zdjety, brak zasiegu)? Pobierze sie na starcie nastepnej jazdy.
 * Kazdy krok w dzienniku jazdy (H10_*), widocznym w QBot SETUP > QExt2 > Dzienniki jazdy.
 */
object H10Recorder {
    private const val PREFS = "qext2_h10"
    private const val K_DEV = "device_id"
    private const val K_REC = "rec_id"
    private const val READY_TIMEOUT_MS = 45_000L
    private const val SEND_GUARD_MS = 10 * 60_000L
    private const val MARKER_KEEP_MS = 60L * 24 * 3600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var app: Context? = null
    @Volatile private var dir: File? = null
    @Volatile private var api: PolarBleApi? = null
    @Volatile private var ready: CompletableDeferred<Unit>? = null
    @Volatile private var sendingSince = 0L
    @Volatile var lastBattery: Int = -1
        private set

    fun init(ctx: Context, d: File?) {
        app = ctx.applicationContext
        dir = d
        cleanupMarkers()
    }

    private fun prefs() = app!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceId(): String? = app?.let { prefs().getString(K_DEV, null) }

    fun saveDeviceId(id: String?) {
        val e = prefs().edit()
        if (id == null) e.remove(K_DEV) else e.putString(K_DEV, id)
        e.apply()
    }

    fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(ctx: Context): Boolean =
        neededPermissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun log(s: String) = RideFileLog.append(s)

    private fun api(): PolarBleApi {
        api?.let { return it }
        val a = PolarBleApiDefaultImpl.defaultImplementation(
            app!!,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
            ),
        )
        a.setApiCallback(object : PolarBleApiCallback() {
            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {
                if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING) ready?.complete(Unit)
            }
            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                log("H10_CONNECTED ${polarDeviceInfo.deviceId}")
            }
            override fun batteryLevelReceived(identifier: String, level: Int) {
                lastBattery = level
                log("H10_BATTERY $level%")
            }
            // 8.4.0-java17: klasa bazowa nie implementuje tych dwoch -- puste (nieuzywane)
            override fun disInformationReceived(identifier: String, disInfo: com.polar.androidcommunications.api.ble.model.DisInfo) {}
            override fun htsNotificationReceived(identifier: String, data: com.polar.sdk.api.model.PolarHealthThermometerData) {}
        })
        api = a
        return a
    }

    private suspend fun connect(id: String): Boolean {
        val d = CompletableDeferred<Unit>()
        ready = d
        try {
            api().connectToDevice(id)
        } catch (e: Exception) {
            if (e.javaClass.simpleName.contains("AlreadyConnected")) return true
            log("H10_CONNECT_FAIL ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        val ok = withTimeoutOrNull(READY_TIMEOUT_MS) { d.await() } != null
        if (!ok) {
            log("H10_CONNECT_TIMEOUT $id")
            disconnect(id)
        }
        return ok
    }

    private fun disconnect(id: String) {
        try { api?.disconnectFromDevice(id) } catch (_: Exception) {}
    }

    // ---------------- start / koniec jazdy ----------------

    fun onRideStart(system: KarooSystemService) {
        val id = deviceId() ?: return
        val ctx = app ?: return
        if (!hasPermissions(ctx)) {
            log("H10_NO_PERMISSION")
            alert(system, "H10: brak zgody Bluetooth", "otwórz ustawienia QExt2", ok = false)
            return
        }
        scope.launch {
            mutex.withLock {
                var started = false
                for (attempt in 1..4) {
                    if (connect(id)) {
                        try { started = startSession(id) } catch (e: Exception) {
                            log("H10_START_FAIL ${e.javaClass.simpleName}: ${e.message}")
                        }
                        disconnect(id)
                        if (started) break
                    }
                    if (attempt < 4) delay(30_000)
                }
                if (started) alert(system, "H10 zapis ✓", "pas zapisuje do pamięci", ok = true)
                else alert(system, "H10: zapis nie ruszył", "ta jazda bez HRV", ok = false)
            }
        }
    }

    private suspend fun startSession(id: String): Boolean {
        val a = api()
        val st = a.requestRecordingStatus(id)
        log("H10_STATUS ongoing=${st.ongoing} entry=${st.entryId} supported=${st.supported}")
        if (st.ongoing) {
            a.stopRecording(id)
            log("H10_STOPPED_LEFTOVER ${st.entryId}")
        }
        for (e in a.listExercises(id).toList()) {
            val ours = e.identifier.startsWith("QX")
            if (ours && !savedLocally(e.identifier)) saveExercise(id, e, endMs = null)
            a.removeExercise(id, e)
            log("H10_REMOVED ${e.identifier} ours=$ours")
        }
        val exId = "QX${System.currentTimeMillis()}"
        a.startRecording(id, exId, PolarH10OfflineExerciseApi.RecordingInterval.INTERVAL_1S,
            PolarH10OfflineExerciseApi.SampleType.RR)
        prefs().edit().putString(K_REC, exId).apply()
        log("H10_REC_START $exId")
        return true
    }

    fun onRideEnd(system: KarooSystemService) {
        val id = deviceId() ?: return
        val exId = prefs().getString(K_REC, null) ?: return
        scope.launch {
            mutex.withLock {
                var n = -1
                for (attempt in 1..3) {
                    if (connect(id)) {
                        try { n = endSession(id, exId) } catch (e: Exception) {
                            log("H10_END_FAIL ${e.javaClass.simpleName}: ${e.message}")
                        }
                        disconnect(id)
                        if (n >= 0) break
                    }
                    if (attempt < 3) delay(30_000)
                }
                prefs().edit().remove(K_REC).apply()
                when {
                    n > 0 -> {
                        alert(system, "H10 pobrane ✓", "$n uderzeń, wysyłam do QBota", ok = true)
                        trySend(system)
                    }
                    n == 0 -> alert(system, "H10: puste nagranie", "brak danych z tej jazdy", ok = false)
                    else -> alert(system, "H10: nie pobrano", "pobiorę na starcie następnej jazdy", ok = false)
                }
            }
        }
    }

    private suspend fun endSession(id: String, exId: String): Int {
        val a = api()
        val st = a.requestRecordingStatus(id)
        if (st.ongoing) a.stopRecording(id)
        val endMs = System.currentTimeMillis()
        val entries = a.listExercises(id).toList()
        val e = entries.firstOrNull { it.identifier == exId }
            ?: entries.firstOrNull { it.identifier.startsWith("QX") }
        if (e == null) {
            log("H10_END_NO_ENTRY $exId found=${entries.map { it.identifier }}")
            return 0
        }
        return saveExercise(id, e, endMs = if (e.identifier == exId) endMs else null)
    }

    /** Pobiera nagranie z pasa i zapisuje paczke na Karoo. Wyjatek = nic nie zapisano (pas NIE jest czyszczony). */
    private suspend fun saveExercise(id: String, e: PolarExerciseEntry, endMs: Long?): Int {
        val rr = api().fetchExercise(id, e).hrSamples
        val startMs = e.identifier.removePrefix("QX").toLongOrNull() ?: 0L
        val sum = rr.fold(0L) { s, v -> s + v }
        val arr = JSONArray()
        rr.forEach { arr.put(it) }
        val gz = b64gz(arr.toString())
        val body = JSONObject().put("hrvRr", JSONObject()
            .put("exerciseId", e.identifier)
            .put("recStartMs", startMs)
            .put("recEndMs", endMs ?: (startMs + sum))
            .put("endKnown", endMs != null)
            .put("deviceId", id)
            .put("battery", lastBattery)
            .put("build", BuildConfig.VERSION_CODE)
            .put("n", rr.size)
            .put("sumMs", sum)
            .put("gz", gz)).toString()
        val f = File(dir ?: throw IllegalStateException("no_dir"), "pending_rr_${e.identifier}.json")
        f.writeText(body)
        if (f.length() <= 0L) throw IllegalStateException("write_failed")
        log("H10_SAVED ${e.identifier} n=${rr.size} sum=${sum / 1000}s b64=${gz.length}")
        return rr.size
    }

    private fun savedLocally(exId: String): Boolean {
        val d = dir ?: return false
        return File(d, "pending_rr_$exId.json").exists() || File(d, "sent_rr_$exId").exists()
    }

    // ---------------- wysylka do QBota ----------------

    /** Wyslij najstarsza oczekujaca paczke; po sukcesie kolejna. Bez tokenu w buildzie -> nic. */
    fun trySend(system: KarooSystemService) {
        val d = dir ?: return
        val tok = BuildConfig.QEXT_READINESS_TOKEN.trim()
        if (tok.isEmpty()) return
        val now = System.currentTimeMillis()
        if (sendingSince > 0 && now - sendingSince < SEND_GUARD_MS) return
        val f = d.listFiles { x -> x.name.startsWith("pending_rr_") && x.name.endsWith(".json") }
            ?.minByOrNull { it.name } ?: return
        val exId = f.name.removePrefix("pending_rr_").removeSuffix(".json")
        val url = BuildConfig.QEXT_READINESS_URL.ifBlank { "https://qbot.cytr.us/ride-readiness" }
        val body = try { f.readBytes() } catch (_: Exception) { return }
        sendingSince = now
        log("H10_SEND ${f.name} bytes=${body.size}")
        var cid: String? = null
        try {
            cid = system.addConsumer<OnHttpResponse>(
                params = OnHttpResponse.MakeHttpRequest(method = "POST", url = url,
                    headers = mapOf("Authorization" to "Bearer $tok", "X-QExt2-Token" to tok, "Content-Type" to "application/json"),
                    body = body, waitForConnection = true),
                onError = { msg ->
                    cid?.let { system.removeConsumer(it) }
                    sendingSince = 0L
                    log("H10_SEND_FAIL sdk=$msg")
                },
                onEvent = { r ->
                    val s = r.state
                    if (s is HttpResponseState.Complete) {
                        cid?.let { system.removeConsumer(it) }
                        sendingSince = 0L
                        if (s.statusCode == 200) {
                            try { File(d, "sent_rr_$exId").writeText(System.currentTimeMillis().toString()) } catch (_: Exception) {}
                            f.delete()
                            log("H10_SENT $exId")
                            trySend(system)
                        } else log("H10_SEND_FAIL http=${s.statusCode} err=${s.error ?: "-"}")
                    }
                },
            )
        } catch (e: Exception) {
            sendingSince = 0L
            log("H10_SEND_FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun cleanupMarkers() {
        val d = dir ?: return
        val cut = System.currentTimeMillis() - MARKER_KEEP_MS
        d.listFiles { x -> x.name.startsWith("sent_rr_") }?.forEach { if (it.lastModified() < cut) it.delete() }
    }

    // ---------------- ustawienia QExt2 ----------------

    /** Szukanie pasa (ustawienia QExt2): pierwszy znaleziony H10 zostaje zapamietany. */
    fun search(onDone: (String?) -> Unit) {
        scope.launch {
            val found = try {
                withTimeoutOrNull(25_000) {
                    api().searchForDevice("Polar").first { it.name.contains("H10", ignoreCase = true) }
                }
            } catch (e: Exception) {
                log("H10_SEARCH_FAIL ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (found != null) {
                saveDeviceId(found.deviceId)
                log("H10_PAIRED ${found.deviceId} ${found.name}")
            }
            withContext(Dispatchers.Main) { onDone(found?.deviceId) }
        }
    }

    /** Sprawdzenie bez jazdy (ustawienia QExt2): polaczenie, stan zapisu, nagrania w pamieci, bateria. */
    fun check(onDone: (String) -> Unit) {
        val id = deviceId()
        if (id == null) { onDone("Najpierw znajdź pas."); return }
        scope.launch {
            val msg = mutex.withLock {
                if (!connect(id)) "Brak połączenia. Załóż pas (mokre elektrody) i spróbuj jeszcze raz."
                else try {
                    val st = api().requestRecordingStatus(id)
                    val ex = api().listExercises(id).toList().map { it.identifier }
                    delay(1500)
                    "Połączono z pasem $id.\nZapis teraz: ${if (st.ongoing) "trwa" else "wyłączony"}\n" +
                        "Nagrania w pamięci: ${if (ex.isEmpty()) "brak" else ex.joinToString()}\n" +
                        "Bateria pasa: ${if (lastBattery >= 0) "$lastBattery%" else "brak odczytu"}"
                } catch (e: Exception) {
                    "Połączono, ale błąd: ${e.javaClass.simpleName}"
                } finally {
                    disconnect(id)
                }
            }
            log("H10_CHECK ${msg.replace('\n', ' ')}")
            withContext(Dispatchers.Main) { onDone(msg) }
        }
    }

    // ---------------- pomocnicze ----------------

    private fun alert(system: KarooSystemService, title: String, detail: String, ok: Boolean) {
        try {
            system.dispatch(InRideAlert(
                id = "qext2-h10-${System.currentTimeMillis()}",
                icon = R.drawable.ic_qext2_menu,
                title = title,
                detail = detail,
                autoDismissMs = 6_000L,
                backgroundColor = if (ok) R.color.h10_ok else R.color.h10_warn,
                textColor = R.color.h10_text,
            ))
        } catch (e: Exception) {
            log("H10_ALERT_FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun b64gz(s: String): String {
        val bo = ByteArrayOutputStream()
        GZIPOutputStream(bo).use { it.write(s.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }
}
