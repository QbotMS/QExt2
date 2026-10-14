package com.qext2.primary.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jazda bez trasy: skan kwadransow prognozy i punkt pod wiatr. */
class RouteWeatherScanTest {
    private val now = 1_800_000_000L          // 'teraz' [s]
    private val q = 900L                       // kwadrans [s]
    // sloty Open-Meteo: wartosc o T dotyczy kwadransa przed T; now jest 5 min po pelnym kwadransie
    private val base = now - 300L              // T poprzedniego slotu (przeszly)
    private val times = LongArray(10) { base + it * q }

    private fun kinds(vararg at: Pair<Int, WxKind>): Array<WxKind?> =
        arrayOfNulls<WxKind>(10).also { a -> at.forEach { (i, k) -> a[i] = k } }

    @Test fun deszcz_za_okolo_25_min_a_nie_za_0() {
        // slot 3: T = now + 40 min -> kwadrans 25..40 min
        val r = RouteWeatherClient.scanFirst(times, kinds(3 to WxKind.RAIN), now)
        assertEquals(3, r!!.first)
        assertEquals(25, r.second)
    }

    @Test fun pada_teraz_to_0_min() {
        val r = RouteWeatherClient.scanFirst(times, kinds(1 to WxKind.RAIN), now)
        assertEquals(1 to 0, r)
    }

    @Test fun przeszly_slot_ignorowany() {
        assertNull(RouteWeatherClient.scanFirst(times, kinds(0 to WxKind.RAIN), now))
    }

    @Test fun mgla_pomijana_bierze_pozniejszy_deszcz() {
        val r = RouteWeatherClient.scanFirst(times, kinds(1 to WxKind.FOG, 4 to WxKind.DRIZZLE), now)
        assertEquals(4, r!!.first)
    }

    @Test fun poza_horyzontem_2h_nic() {
        // slot 9: T = now + 130 min -> start 115 min (w horyzoncie); z limitem 60 min - nic
        assertNull(RouteWeatherClient.scanFirst(times, kinds(9 to WxKind.RAIN), now, maxMin = 60))
        assertEquals(9 to 115, RouteWeatherClient.scanFirst(times, kinds(9 to WxKind.RAIN), now))
    }

    @Test fun punkt_pod_wiatr_wiatr_z_zachodu_to_punkt_na_zachod() {
        val (la, lo) = RouteWeatherClient.upwindPoint(52.0, 21.0, 270)
        assertTrue(kotlin.math.abs(la - 52.0) < 0.01)
        assertTrue(lo < 21.0)
        val d = RouteLine.haversine(52.0, 21.0, la, lo)
        assertTrue("d=$d", d in 9_500.0..10_500.0)
    }

    @Test fun punkt_pod_wiatr_wiatr_z_polnocy_to_punkt_na_polnoc() {
        val (la, _) = RouteWeatherClient.upwindPoint(52.0, 21.0, 0)
        assertTrue(la > 52.0)
    }
}
