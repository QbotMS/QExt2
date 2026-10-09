package com.qext2.primary.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TwilightTest {
    private val z = ZoneId.of("Europe/Warsaw")
    private fun t(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, z).toInstant().toEpochMilli()

    @Test fun `o 17 zmrok dzis mimo ze Karoo podaje jutrzejszy swit`() {
        val r = Twilight.next(t(9, 17, 0), dawnMs = t(10, 6, 40), duskMs = t(9, 18, 50))!!
        assertEquals(t(9, 18, 50), r.first); assertEquals(false, r.second)
    }
    @Test fun `o 5 rano swit`() {
        val r = Twilight.next(t(9, 5, 0), dawnMs = t(9, 6, 38), duskMs = t(8, 18, 52))!!
        assertEquals(t(9, 6, 38), r.first); assertEquals(true, r.second)
    }
    @Test fun `o 22 swit jutro`() {
        val r = Twilight.next(t(9, 22, 0), dawnMs = t(9, 6, 38), duskMs = t(9, 18, 50))!!
        assertEquals(t(10, 6, 38), r.first); assertEquals(true, r.second)
    }
    @Test fun `brak danych`() { assertEquals(null, Twilight.next(t(9, 12, 0), 0L, 0L)) }
}
