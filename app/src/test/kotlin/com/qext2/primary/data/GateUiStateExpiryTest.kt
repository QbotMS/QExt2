package com.qext2.primary.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GateUiStateExpiryTest {

    private fun resolve(rawState: String?, tsMs: Long, nowMs: Long): String =
        AthleteDataStore.resolveGateUiState(rawState, tsMs, nowMs)

    @Test
    fun `GATE always stays GATE`() {
        assertEquals("GATE", resolve("GATE", 1_000_000L, 1_001_000L))
        assertEquals("GATE", resolve("GATE", 0L, 1_001_000L))
        assertEquals("GATE", resolve("GATE", Long.MAX_VALUE, 0L))
    }

    @Test
    fun `fresh FURTKA OK stays`() {
        val now = 1_000_000L
        val ts = now - 2_000L
        assertEquals("FURTKA OK", resolve("FURTKA OK", ts, now))
    }

    @Test
    fun `stale FURTKA OK returns to GATE`() {
        val now = 1_000_000L
        val ts = now - 6_000L
        assertEquals("GATE", resolve("FURTKA OK", ts, now))
    }

    @Test
    fun `missing timestamp for temp state returns GATE`() {
        assertEquals("GATE", resolve("FURTKA OK", 0L, 1_000_000L))
        assertEquals("GATE", resolve("FURTKA FAIL", -1L, 1_000_000L))
    }

    @Test
    fun `future timestamp returns GATE`() {
        assertEquals("GATE", resolve("FURTKA WAIT", 2_000_000L, 1_000_000L))
    }

    @Test
    fun `null or unknown state returns GATE`() {
        assertEquals("GATE", resolve(null, 1_000_000L, 1_001_000L))
        assertEquals("GATE", resolve("UNKNOWN", 1_000_000L, 1_001_000L))
        assertEquals("GATE", resolve("", 1_000_000L, 1_001_000L))
        assertEquals("GATE", resolve("  ", 1_000_000L, 1_001_000L))
    }

    @Test
    fun `all temp states expire correctly`() {
        val now = 1_000_000L
        val tempStates = listOf("FURTKA...", "FURTKA OK", "FURTKA FAIL", "FURTKA WAIT")
        for (state in tempStates) {
            assertEquals("fresh $state", state, resolve(state, now - 1_000L, now))
            assertEquals("stale $state", "GATE", resolve(state, now - 6_000L, now))
            assertEquals("no ts $state", "GATE", resolve(state, 0L, now))
            assertEquals("future ts $state", "GATE", resolve(state, now + 1L, now))
        }
    }

    @Test
    fun `at exact boundary 5000ms is still fresh`() {
        val now = 1_000_000L
        val ts = now - 5000L
        assertEquals("FURTKA OK", resolve("FURTKA OK", ts, now))
    }

    @Test
    fun `just past 5000ms boundary returns GATE`() {
        val now = 1_000_000L
        val ts = now - 5001L
        assertEquals("GATE", resolve("FURTKA OK", ts, now))
    }
}
