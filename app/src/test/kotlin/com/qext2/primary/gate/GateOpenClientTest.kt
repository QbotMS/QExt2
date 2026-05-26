package com.qext2.primary.gate

import org.junit.Assert.assertEquals
import org.junit.Test

class GateOpenClientTest {

    @Test
    fun maps200ToOk() {
        val client = GateOpenClient(
            sdkHttpCaller = FakeSdkHttpCaller(200),
            gateUrlProvider = { "https://example.test/gate/open" },
            gateTokenProvider = { "t" },
            logger = {},
        )
        var result: GateResult? = null
        client.openGate(debounceLastMs = 0L) { result = it }
        assertEquals(GateResult.Ok, result)
    }

    @Test
    fun maps403ToForbidden() {
        val client = GateOpenClient(
            sdkHttpCaller = FakeSdkHttpCaller(403),
            gateUrlProvider = { "https://example.test/gate/open" },
            gateTokenProvider = { "t" },
            logger = {},
        )
        var result: GateResult? = null
        client.openGate(debounceLastMs = 0L) { result = it }
        assertEquals(GateResult.Forbidden, result)
    }

    @Test
    fun maps429ToRateLimited() {
        val client = GateOpenClient(
            sdkHttpCaller = FakeSdkHttpCaller(429),
            gateUrlProvider = { "https://example.test/gate/open" },
            gateTokenProvider = { "t" },
            logger = {},
        )
        var result: GateResult? = null
        client.openGate(debounceLastMs = 0L) { result = it }
        assertEquals(GateResult.RateLimited, result)
    }

    @Test
    fun mapsSdkErrorToError() {
        val client = GateOpenClient(
            sdkHttpCaller = FakeSdkHttpCaller(errorMsg = "network failure"),
            gateUrlProvider = { "https://example.test/gate/open" },
            gateTokenProvider = { "t" },
            logger = {},
        )
        var result: GateResult? = null
        client.openGate(debounceLastMs = 0L) { result = it }
        assertEquals(GateResult.Error, result)
    }

    @Test
    fun debounceBlocksTooFrequentRequest() {
        val client = GateOpenClient(
            sdkHttpCaller = FakeSdkHttpCaller(200),
            gateUrlProvider = { "https://example.test/gate/open" },
            gateTokenProvider = { "t" },
            logger = {},
        )
        var result: GateResult? = null
        val recent = System.currentTimeMillis() - 1_000L
        client.openGate(debounceLastMs = recent) { result = it }
        assertEquals(GateResult.RateLimited, result)
    }

    @Test
    fun missingTokenReturnsForbidden() {
        val client = GateOpenClient(
            sdkHttpCaller = FakeSdkHttpCaller(200),
            gateUrlProvider = { "https://example.test/gate/open" },
            gateTokenProvider = { "" },
            logger = {},
        )
        var result: GateResult? = null
        client.openGate(debounceLastMs = 0L) { result = it }
        assertEquals(GateResult.Forbidden, result)
    }

    private class FakeSdkHttpCaller(
        private val code: Int? = null,
        private val errorMsg: String? = null,
    ) : SdkHttpCaller {
        override fun call(
            url: String,
            headers: Map<String, String>,
            waitForConnection: Boolean,
            onResult: (code: Int) -> Unit,
            onError: (msg: String) -> Unit,
        ) {
            if (errorMsg != null) {
                onError(errorMsg)
            } else if (code != null) {
                onResult(code)
            }
        }
    }
}
