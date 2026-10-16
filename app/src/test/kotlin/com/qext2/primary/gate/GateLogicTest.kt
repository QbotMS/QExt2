package com.qext2.primary.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateLogicTest {

    @Test
    fun urlCarriesTimestampAndPosition() {
        val u = GateOpenClient.buildUrl("https://x.test/gate/open", 1234L, 52.1, 21.2)
        assertEquals("https://x.test/gate/open?ts=1234&lat=52.100000&lon=21.200000", u)
    }

    @Test
    fun urlWithoutPositionStillHasTimestamp() {
        assertEquals("https://x.test/gate/open?ts=5", GateOpenClient.buildUrl("https://x.test/gate/open", 5L, null, null))
    }

    @Test
    fun serverCodesMapToResults() {
        assertEquals(GateResult.Ok, GateOpenClient.mapCode(200))
        assertEquals(GateResult.TooFar, GateOpenClient.mapCode(412))
        assertEquals(GateResult.Stale, GateOpenClient.mapCode(408))
        assertEquals(GateResult.BadRequest, GateOpenClient.mapCode(400))
        assertEquals(GateResult.NoNetwork, GateOpenClient.mapCode(0))
        assertEquals(GateResult.Error, GateOpenClient.mapCode(503))
    }

    @Test
    fun requestNeverWaitsForConnection() {
        var waited: Boolean? = null
        val client = GateOpenClient(
            sdkHttpCaller = SdkHttpCaller { _, _, wait, onResult, _ -> waited = wait; onResult(200) },
            gateUrlProvider = { "https://x.test/gate/open" },
            gateTokenProvider = { "t" },
            logger = {},
        )
        client.openGate(0L, 52.0, 21.0) {}
        assertEquals(false, waited)
    }

    @Test
    fun homeParsesServerJson() {
        val h = GateHome.parse("{\"status\":\"ok\",\"lat\":52.123456,\"lon\":21.654321,\"radiusM\":300}")
        assertNotNull(h)
        assertEquals(52.123456, h!!.lat, 1e-9)
        assertEquals(300, h.radiusM)
        assertNull(GateHome.parse("{\"status\":\"forbidden\"}"))
        assertNull(GateHome.parse(null))
    }

    @Test
    fun distanceAndFormat() {
        val d = GateGeo.distanceM(52.0, 21.0, 52.0027, 21.0)   // ok. 300 m na polnoc
        assertTrue(d in 290.0..311.0)
        assertEquals("120 m", GateGeo.fmt(120))
        assertEquals("3.2 km", GateGeo.fmt(3200))
    }
}
