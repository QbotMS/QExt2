package com.qext2.primary.gate

import android.util.Log
import com.qext2.primary.BuildConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import java.util.Locale

private const val TAG = "QEXT_GATE"

/**
 * Wywolanie HTTP przez Karoo. Przebudowa 16.10.2026 ([GATE-PRZEBUDOWA]): brama NIGDY nie czeka na zasieg
 * (waitForConnection=false) -- bez sieci od razu wynik "brak zasiegu", zeby brama nie otworzyla sie z opoznieniem.
 */
fun interface SdkHttpCaller {
    fun call(
        url: String,
        headers: Map<String, String>,
        waitForConnection: Boolean,
        onResult: (code: Int) -> Unit,
        onError: (msg: String) -> Unit,
    )

    /** Jak [call], ale z trescia odpowiedzi (punkt bramy). Domyslnie bez tresci. */
    fun callWithBody(
        url: String,
        headers: Map<String, String>,
        onResult: (code: Int, body: String?) -> Unit,
        onError: (msg: String) -> Unit,
    ) = call(url, headers, false, { onResult(it, null) }, onError)
}

internal class KarooSdkHttpCaller(private val system: KarooSystemService) : SdkHttpCaller {

    private var consumerId: String? = null

    override fun call(
        url: String,
        headers: Map<String, String>,
        waitForConnection: Boolean,
        onResult: (code: Int) -> Unit,
        onError: (msg: String) -> Unit,
    ) = request(url, headers, waitForConnection, { code, _ -> onResult(code) }, onError)

    // 16.10: pogoda / plan / punkt bramy MOGA czekac na polaczenie (bez tego Karoo bez chwilowej sieci od razu dawalo code=0);
    // tylko samo otwarcie bramy (call) nigdy nie czeka.
    override fun callWithBody(
        url: String,
        headers: Map<String, String>,
        onResult: (code: Int, body: String?) -> Unit,
        onError: (msg: String) -> Unit,
    ) = request(url, headers, true, onResult, onError)

    private fun request(
        url: String,
        headers: Map<String, String>,
        waitForConnection: Boolean,
        onResult: (code: Int, body: String?) -> Unit,
        onError: (msg: String) -> Unit,
    ) {
        cleanUp()
        consumerId = system.addConsumer<OnHttpResponse>(
            params = OnHttpResponse.MakeHttpRequest(
                method = "GET",
                url = url,
                headers = headers,
                body = null,
                waitForConnection = waitForConnection,
            ),
            onError = { msg ->
                cleanUp()
                onError(msg)
            },
            onEvent = { resp ->
                val state = resp.state
                if (state is HttpResponseState.Complete) {
                    cleanUp()
                    val code = if (state.error != null && state.statusCode <= 0) 0 else state.statusCode
                    if (code == 0) try { com.qext2.primary.util.RideFileLog.append("HTTP_FAIL url=${url.substringBefore('?')} err=${state.error}") } catch (_: Exception) {}
                    onResult(code, state.body?.let { String(it, Charsets.UTF_8) })
                }
            }
        )
    }

    fun cleanUp() {
        consumerId?.let { system.removeConsumer(it) }
        consumerId = null
    }
}

class GateOpenClient(
    private val sdkHttpCaller: SdkHttpCaller,
    private val gateUrlProvider: () -> String = { BuildConfig.QEXT_GATE_URL.trim() },
    private val gateTokenProvider: () -> String = { BuildConfig.QEXT_GATE_TOKEN.trim() },
    private val logger: (String) -> Unit = { msg -> Log.d(TAG, msg) },
) {
    /**
     * Otwarcie bramy. Serwer sprawdza: token, wiek zadania (ts, maks. 20 s) i odleglosc od domu (lat/lon).
     * Kody: 200 ok, 400 brak ts/pozycji, 403 token, 408 za stare, 412 za daleko, 429 limit, 0 = brak sieci, reszta = blad.
     */
    fun openGate(
        debounceLastMs: Long,
        lat: Double? = null,
        lon: Double? = null,
        nowMs: Long = System.currentTimeMillis(),
        onResult: (GateResult) -> Unit,
    ) {
        if (nowMs - debounceLastMs < DEBOUNCE_MS) {
            logger("QEXT_GATE_LOCAL_COOLDOWN remaining_ms=${DEBOUNCE_MS - (nowMs - debounceLastMs)}")
            onResult(GateResult.RateLimited)
            return
        }
        val token = gateTokenProvider()
        if (token.isEmpty()) {
            logger("QEXT_GATE_FORBIDDEN missing_token")
            onResult(GateResult.Forbidden)
            return
        }
        val url = buildUrl(gateUrlProvider(), nowMs, lat, lon)
        logger("QEXT_GATE_REQUEST gps=${lat != null && lon != null}")
        sdkHttpCaller.call(
            url = url,
            headers = mapOf("X-Gate-Token" to token, "ngrok-skip-browser-warning" to "true"),
            waitForConnection = false,
            onResult = { code ->
                val result = mapCode(code)
                logger("QEXT_GATE_RESULT code=$code result=$result")
                onResult(result)
            },
            onError = { msg ->
                logger("QEXT_GATE_ERROR sdk_msg=$msg")
                onResult(GateResult.Error)
            }
        )
    }

    companion object {
        const val DEBOUNCE_MS = 15_000L

        fun buildUrl(base: String, nowMs: Long, lat: Double?, lon: Double?): String {
            val sep = if (base.contains("?")) "&" else "?"
            val pos = if (lat != null && lon != null) String.format(Locale.US, "&lat=%.6f&lon=%.6f", lat, lon) else ""
            return "$base${sep}ts=$nowMs$pos"
        }

        fun mapCode(code: Int): GateResult = when (code) {
            200 -> GateResult.Ok
            403 -> GateResult.Forbidden
            429 -> GateResult.RateLimited
            412 -> GateResult.TooFar
            408 -> GateResult.Stale
            400 -> GateResult.BadRequest
            0 -> GateResult.NoNetwork
            else -> GateResult.Error
        }
    }
}

enum class GateResult {
    Ok,
    Forbidden,
    RateLimited,
    Error,
    TooFar,
    Stale,
    BadRequest,
    NoNetwork,
}
