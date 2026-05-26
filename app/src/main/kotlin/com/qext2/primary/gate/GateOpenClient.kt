package com.qext2.primary.gate

import android.util.Log
import com.qext2.primary.BuildConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse

private const val TAG = "QEXT_GATE"

fun interface SdkHttpCaller {
    fun call(
        url: String,
        headers: Map<String, String>,
        waitForConnection: Boolean,
        onResult: (code: Int) -> Unit,
        onError: (msg: String) -> Unit,
    )
}

internal class KarooSdkHttpCaller(private val system: KarooSystemService) : SdkHttpCaller {

    private var consumerId: String? = null

    override fun call(
        url: String,
        headers: Map<String, String>,
        waitForConnection: Boolean,
        onResult: (code: Int) -> Unit,
        onError: (msg: String) -> Unit,
    ) {
        cleanUp(system)
        consumerId = system.addConsumer<OnHttpResponse>(
            params = OnHttpResponse.MakeHttpRequest(
                method = "GET",
                url = url,
                headers = headers,
                body = null,
                waitForConnection = waitForConnection,
            ),
            onError = { msg ->
                cleanUp(system)
                onError(msg)
            },
            onEvent = { resp ->
                val state = resp.state
                if (state is HttpResponseState.Complete) {
                    cleanUp(system)
                    onResult(state.statusCode)
                }
            }
        )
    }

    fun cleanUp(system: KarooSystemService) {
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
    fun openGate(
        debounceLastMs: Long,
        onResult: (GateResult) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        if (now - debounceLastMs < DEBOUNCE_MS) {
            logger("QEXT_GATE_LOCAL_COOLDOWN remaining_ms=${DEBOUNCE_MS - (now - debounceLastMs)}")
            onResult(GateResult.RateLimited)
            return
        }

        val token = gateTokenProvider()
        if (token.isEmpty()) {
            logger("QEXT_GATE_FORBIDDEN missing_token")
            onResult(GateResult.Forbidden)
            return
        }

        val baseUrl = gateUrlProvider()
        val urlWithToken = "$baseUrl?token=$token"
        logger("QEXT_GATE_REQUEST url=$baseUrl")

        sdkHttpCaller.call(
            url = urlWithToken,
            headers = mapOf(
                "X-Gate-Token" to token,
                "ngrok-skip-browser-warning" to "true",
            ),
            waitForConnection = true,
            onResult = { code ->
                val result = when (code) {
                    200 -> {
                        logger("QEXT_GATE_OK")
                        GateResult.Ok
                    }
                    403 -> {
                        logger("QEXT_GATE_FORBIDDEN")
                        GateResult.Forbidden
                    }
                    429 -> {
                        logger("QEXT_GATE_RATE_LIMITED")
                        GateResult.RateLimited
                    }
                    else -> {
                        logger("QEXT_GATE_ERROR code=$code")
                        GateResult.Error
                    }
                }
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
    }
}

enum class GateResult {
    Ok,
    Forbidden,
    RateLimited,
    Error,
}
