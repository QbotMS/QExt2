package com.qext2.primary.gate

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.qext2.primary.BuildConfig
import com.qext2.primary.QExt2PrimaryExtension
import com.qext2.primary.data.AthleteDataStore
import com.qext2.primary.util.RideFileLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "QEXT_GATE"

/** Punkt i promien bramy z QBota (GET /gate/open?mode=home, z tokenem). */
data class GateHome(val lat: Double, val lon: Double, val radiusM: Int) {
    fun save(p: SharedPreferences) {
        p.edit().putString("home_lat", lat.toString()).putString("home_lon", lon.toString()).putInt("home_r", radiusM).apply()
    }

    companion object {
        fun fromPrefs(p: SharedPreferences): GateHome? {
            val la = p.getString("home_lat", null)?.toDoubleOrNull() ?: return null
            val lo = p.getString("home_lon", null)?.toDoubleOrNull() ?: return null
            return GateHome(la, lo, p.getInt("home_r", 300))
        }

        /** Proste parsowanie {"lat":..,"lon":..,"radiusM":..} bez org.json (testowalne na JVM). */
        fun parse(body: String?): GateHome? {
            if (body.isNullOrBlank()) return null
            fun num(key: String) = Regex("\"$key\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            val la = num("lat") ?: return null
            val lo = num("lon") ?: return null
            val r = num("radiusM")?.toInt() ?: 300
            if (la !in -90.0..90.0 || lo !in -180.0..180.0 || r <= 0) return null
            return GateHome(la, lo, r)
        }
    }
}

object GateGeo {
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * 6_371_000.0 * asin(sqrt(h))
    }

    fun fmt(m: Int): String = if (m < 1000) "$m m" else String.format(Locale.US, "%.1f km", m / 1000.0)
}

/**
 * Brama z Karoo -- przebudowa 16.10.2026 ([GATE-PRZEBUDOWA], docs/BRAMA.md).
 * Stany: GOTOWA (blisko domu) -> 1. tap UZBROJONA (3 s) -> 2. tap WYSYLAM -> OTWARTA / BLAD z powodem.
 * Daleko od domu albo bez GPS: pole szare, tap nic nie wysyla. Kazdy krok w dzienniku jazdy (GATE_*).
 */
object GateController {
    enum class State { READY, FAR, NO_GPS, ARMED, SENDING, OPENED, TOO_FAR, NO_NET, WAIT, ERROR }

    data class Ui(val state: State, val main: String, val sub: String)

    const val ARM_MS = 3_000L
    const val RESULT_MS = 5_000L
    const val SEND_TIMEOUT_MS = 20_000L
    const val FIX_MAX_AGE_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private val _ui = MutableStateFlow(Ui(State.NO_GPS, "BRAMA", "czekam na GPS"))
    val ui: StateFlow<Ui> = _ui

    @Volatile private var lat: Double? = null
    @Volatile private var lon: Double? = null
    @Volatile private var fixMs = 0L
    @Volatile private var home: GateHome? = null
    @Volatile private var homeFetching = false
    @Volatile private var homeFetchMs = 0L
    private var prefs: SharedPreferences? = null
    private var transient: State? = null
    private var transientSub = ""
    private var transientUntil = 0L
    private var sendSeq = 0

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("qext2_gate", Context.MODE_PRIVATE)
        prefs = p
        home = GateHome.fromPrefs(p)
        refresh()
    }

    fun onLocation(la: Double, lo: Double) {
        lat = la
        lon = lo
        fixMs = System.currentTimeMillis()
        handler.post { refresh() }
    }

    fun distanceM(): Int? {
        val h = home ?: return null
        val la = lat ?: return null
        val lo = lon ?: return null
        return GateGeo.distanceM(la, lo, h.lat, h.lon).toInt()
    }

    fun ensureHome() {
        if (home != null || homeFetching) return
        val now = System.currentTimeMillis()
        if (now - homeFetchMs < 60_000L) return
        val system = QExt2PrimaryExtension.instance?.karooSystem ?: return
        val token = BuildConfig.QEXT_GATE_TOKEN.trim()
        if (token.isEmpty()) {
            log("GATE_HOME_FAIL brak tokenu w buildzie")
            return
        }
        homeFetching = true
        homeFetchMs = now
        val base = BuildConfig.QEXT_GATE_URL.trim()
        KarooSdkHttpCaller(system).callWithBody(
            url = base + (if (base.contains("?")) "&" else "?") + "mode=home",
            headers = mapOf("X-Gate-Token" to token),
            onResult = { code, body ->
                homeFetching = false
                val h = if (code == 200) GateHome.parse(body) else null
                if (h != null) {
                    home = h
                    prefs?.let { h.save(it) }
                    log("GATE_HOME ok r=${h.radiusM}")
                } else {
                    log("GATE_HOME_FAIL code=$code")
                }
                handler.post { refresh() }
            },
            onError = { msg ->
                homeFetching = false
                log("GATE_HOME_FAIL sdk=$msg")
            },
        )
    }

    @Synchronized
    fun refresh() {
        val now = System.currentTimeMillis()
        val t = transient
        if (t != null && now < transientUntil) {
            emit(t, transientSub)
            return
        }
        transient = null
        val d = distanceM()
        val h = home
        when {
            fixMs == 0L || now - fixMs > FIX_MAX_AGE_MS -> emit(State.NO_GPS, "brak GPS")
            h != null && d != null && d > h.radiusM -> emit(State.FAR, "za daleko · ${GateGeo.fmt(d)}")
            else -> emit(State.READY, d?.let { GateGeo.fmt(it) } ?: "")
        }
    }

    /** Tapniecie w pole (BroadcastReceiver). */
    fun onTap() {
        handler.post {
            refresh()
            when (_ui.value.state) {
                State.SENDING -> log("GATE_TAP ignored=sending")
                State.ARMED -> send()
                State.READY -> {
                    setTransient(State.ARMED, "dotknij jeszcze raz", ARM_MS)
                    log("GATE_ARMED dist=${distanceM()}")
                }
                State.FAR, State.NO_GPS -> {
                    log("GATE_TAP blocked=${_ui.value.state} dist=${distanceM()}")
                    ensureHome()
                }
                else -> {
                    transient = null
                    refresh()
                    if (_ui.value.state == State.READY) {
                        setTransient(State.ARMED, "dotknij jeszcze raz", ARM_MS)
                        log("GATE_ARMED dist=${distanceM()}")
                    }
                }
            }
        }
    }

    private fun send() {
        // 0.4.20: tryb NAGRANIE (w domu pole jest GOTOWE) -- prawdziwa brama NIE moze sie otworzyc
        if (com.qext2.primary.demo.RecMode.blocked()) { log("GATE_SEND_SKIP tryb NAGRANIE"); result(State.ERROR, "tryb nagrania"); return }
        val system = QExt2PrimaryExtension.instance?.karooSystem
        if (system == null) {
            result(State.ERROR, "brak Karoo")
            return
        }
        val la = lat
        val lo = lon
        val seq = ++sendSeq
        setTransient(State.SENDING, "", SEND_TIMEOUT_MS + 1_000L)
        handler.postDelayed({
            if (seq == sendSeq && transient == State.SENDING) result(State.ERROR, "brak odpowiedzi")
        }, SEND_TIMEOUT_MS)
        log("GATE_SEND dist=${distanceM()}")
        AthleteDataStore.saveGateUiState("FURTKA...")
        GateOpenClient(KarooSdkHttpCaller(system), logger = { log(it) })
            .openGate(AthleteDataStore.loadGateLastRequestMs(), la, lo) { r ->
                handler.post {
                    if (seq != sendSeq || transient != State.SENDING) return@post
                    when (r) {
                        GateResult.Ok -> {
                            AthleteDataStore.saveGateLastRequestMs(System.currentTimeMillis())
                            result(State.OPENED, "")
                        }
                        GateResult.TooFar -> result(State.TOO_FAR, distanceM()?.let { GateGeo.fmt(it) } ?: "")
                        GateResult.RateLimited -> result(State.WAIT, "limit 15 s")
                        GateResult.NoNetwork -> result(State.NO_NET, "")
                        GateResult.Forbidden -> result(State.ERROR, "token")
                        GateResult.Stale -> result(State.ERROR, "zegar")
                        GateResult.BadRequest -> result(State.ERROR, "brak GPS")
                        GateResult.Error -> result(State.ERROR, "serwer / HikConnect")
                    }
                }
            }
    }

    private fun result(state: State, sub: String) {
        setTransient(state, sub, RESULT_MS)
        log("GATE_RESULT state=$state sub=$sub")
        AthleteDataStore.saveGateUiState(
            when (state) {
                State.OPENED -> "FURTKA OK"
                State.WAIT -> "FURTKA WAIT"
                else -> "FURTKA FAIL"
            }
        )
    }

    private fun setTransient(state: State, sub: String, ms: Long) {
        transient = state
        transientSub = sub
        transientUntil = System.currentTimeMillis() + ms
        emit(state, sub)
        handler.postDelayed({ refresh() }, ms + 50L)
    }

    private fun emit(state: State, sub: String) {
        val main = when (state) {
            State.READY -> "OTWÓRZ"
            State.FAR, State.NO_GPS -> "BRAMA"
            State.ARMED -> "POTWIERDŹ"
            State.SENDING -> "WYSYŁAM…"
            State.OPENED -> "OTWARTA"
            State.TOO_FAR -> "ZA DALEKO"
            State.NO_NET -> "BRAK SIECI"
            State.WAIT -> "CHWILA"
            State.ERROR -> "BŁĄD"
        }
        _ui.value = Ui(state, main, sub)
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        try {
            RideFileLog.append(msg)
        } catch (_: Exception) {
        }
    }
}
