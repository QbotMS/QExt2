package com.qext2.primary.kokpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Radar QBota w KOKPICIE: odpowiedz serwera i pierwszenstwo przed lokalna prognoza. */
class QbotRadarTest {
    private val now = 1_800_000_000_000L
    private val local = RainSoon(20, 70, 1.0f, "RAIN", 0f)
    private val localFar = RainSoon(90, 60, 1.0f, "RAIN", 0f)

    @Test fun parse_brak_deszczu() {
        val (ok, r) = QbotRadar.parse("""{"status":"OK","ride":3,"new_ride":false,"rain":null}""", now)
        assertTrue(ok); assertNull(r)
    }

    @Test fun parse_deszcz() {
        val (ok, r) = QbotRadar.parse("""{"status":"OK","rain":{"kind":"radar","za_min":12,"pada":false,"mmh":2.5,"sila":"umiarkowany","dur_min":30,"eta":"99:99"}}""", now)
        assertTrue(ok)
        assertEquals(12, r!!.zaMin); assertEquals(2.5f, r.mmh, 0.01f); assertEquals("umiarkowany", r.sila)
        assertEquals(now + 12 * 60_000L, r.etaMs)   // zly format eta -> z za_min
    }

    @Test fun parse_blad() {
        assertFalse(QbotRadar.parse("""{"error":"unauthorized"}""", now).first)
        assertFalse(QbotRadar.parse("<html>", now).first)
    }

    @Test fun brak_odpowiedzi_lokalna_prognoza_bez_zmian() {
        val m = QbotRadar.merge(now, null, local, 0L, null)
        assertEquals(local, m.soon)
    }

    @Test fun stara_odpowiedz_lokalna_prognoza() {
        val r = QbotRain(10, false, 2f, "umiarkowany", 30, now + 600_000L)
        val m = QbotRadar.merge(now, null, local, now - 11 * 60_000L, r)
        assertEquals(local, m.soon)
    }

    @Test fun radar_nic_nie_widzi_lokalna_tylko_dalej_niz_60_min() {
        assertNull(QbotRadar.merge(now, null, local, now - 60_000L, null).soon)
        assertEquals(localFar, QbotRadar.merge(now, null, localFar, now - 60_000L, null).soon)
    }

    @Test fun radar_deszcz_za_x_min_odliczanie() {
        val r = QbotRain(12, false, 2.5f, "umiarkowany", 30, now + 9 * 60_000L)   // odpowiedz sprzed 3 min
        val m = QbotRadar.merge(now, null, local, now - 3 * 60_000L, r)
        assertTrue(m.soon!!.radar)
        assertEquals(9, m.soon!!.minutes)
        assertEquals(-1, m.soon!!.probPct)
        assertNull(m.now)
    }

    @Test fun radar_pada_pole_mm_i_ile_jeszcze() {
        val r = QbotRain(0, true, 4f, "silny", 30, now - 10 * 60_000L)
        val m = QbotRadar.merge(now, null, null, now - 60_000L, r)
        assertEquals(4f, m.now!!, 0.01f)
        assertEquals(0, m.soon!!.minutes)
        assertEquals(20, m.soon!!.durMin)
    }

    @Test fun epizod_minal_jak_brak() {
        val r = QbotRain(0, true, 4f, "silny", 10, now - 30 * 60_000L)
        val m = QbotRadar.merge(now, null, localFar, now - 60_000L, r)
        assertNull(m.now); assertEquals(localFar, m.soon)
    }
}
