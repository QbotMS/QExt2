package com.qext2.primary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * AERO v5 (21.10, decyzja Michala): stale odniesienie = CdA roweru z Centrum CdA + spokojny ekran (co 5 s, do 5 W).
 * Sztuczne jazdy z AeroCoreSyntheticTest.Sim (fizyka co 0,1 s, szum miernika, wahania mocy).
 * Progi: szacunek CdA z 30 s ma blad do ~0,04 (AeroCoreSyntheticTest.stala_jazda_bez_zmian), wiec srednie, nie pojedyncze sekundy.
 */
class AeroFixedRefTest {
    private fun params() = AeroParams(117.0, 0.976, 0.0055, 0.0103, AeroParams.WIND_FACTOR, true, "test", mapOf("Grail" to 0.43))
    private fun flat(n: Int, cda: Double, course: Double = 0.0) = List(n) { AeroCoreSyntheticTest.Sec(200.0, cda, 0.0055, course) }

    private fun run(secs: List<AeroCoreSyntheticTest.Sec>, ref: Double?, windMs: Double = 0.0): List<Pair<Int, AeroCore.Out>> {
        val core = AeroCore(params()); core.fixedRef = ref
        val outs = ArrayList<Pair<Int, AeroCore.Out>>()
        AeroCoreSyntheticTest.Sim(windMs = windMs, windFrom = 20.0).run(core, secs) { k, _, o -> outs.add(k to o) }
        return outs
    }

    @Test fun stale_odniesienie_nie_przestawia_sie() {
        val outs = run(flat(300, 0.43) + flat(300, 0.37) + flat(300, 0.43), 0.43)
        assertTrue("kroki przy stalym odniesieniu", outs.none { it.second.event?.startsWith("step") == true })
        assertTrue("odniesienie sie zmienilo", outs.filter { it.second.state == 2 }.all { abs(it.second.cdaRef - 0.43f) < 1e-4 })
    }

    @Test fun zwykla_pozycja_okolo_zera_dropy_wyraznie_minus() {
        val outs = run(flat(300, 0.43) + flat(300, 0.37), 0.43)
        val normal = outs.filter { it.first in 120..299 && it.second.state == 2 }.map { it.second.dW }.average()
        val drops = outs.filter { it.first in 400..599 && it.second.state == 2 }.map { it.second.dW }.average()
        assertTrue("zwykla pozycja: srednio $normal W", abs(normal) <= 10)
        assertTrue("dropy: srednio $drops W, zwykla $normal W", drops <= -15 && drops < normal - 15)
    }

    @Test fun petla_z_wiatrem_bez_przestawiania_zera() {
        val secs = flat(240, 0.43, 0.0) + flat(240, 0.43, 90.0) + flat(240, 0.43, 180.0) + flat(240, 0.43, 270.0)
        val outs = run(secs, 0.43, windMs = 4.0)
        val m = outs.filter { it.first >= 120 && it.second.state == 2 }.map { it.second.dW }.average()
        assertTrue("petla z wiatrem 4 m/s: srednio $m W", abs(m) <= 12)
    }

    @Test fun ekran_co_5_s_i_do_5_W() {
        val d = AeroDisplay()
        val shownAt = ArrayList<Pair<Long, Int>>()
        var prev: Int? = null
        for (s in 0 until 60) {
            val t = 1_000L * s
            val o = d.feed(AeroCore.Out(state = 2, dW = -12 + (s % 7) - 3, conf = 2), t)
            assertEquals("nie wielokrotnosc 5: ${o.dW}", 0, o.dW % 5)
            if (o.dW != prev) { shownAt.add(t to o.dW); prev = o.dW }
        }
        for (k in 1 until shownAt.size) assertTrue("zmiana szybciej niz co 5 s: $shownAt", shownAt[k].first - shownAt[k - 1].first >= 5_000L)
    }

    @Test fun ekran_trzyma_wartosc_w_krotkiej_przerwie() {
        val d = AeroDisplay()
        d.feed(AeroCore.Out(state = 2, dW = -20, conf = 2), 0L)
        assertEquals(2, d.feed(AeroCore.Out(state = 0), 10_000L).state)
        assertEquals(0, d.feed(AeroCore.Out(state = 0), 20_000L).state)
    }

    @Test fun cdaByBike_z_json() {
        val p = AeroParams.fromJson("""{"riderKg":100,"extraKg":14,"crr":{"asphalt":0.0055,"gravel":0.0103},
            "cdaByBike":{"Grail":0.405,"Grizl":0.41,"Zly":5}}""")!!
        assertEquals(0.405, p.cdaByBike["Grail"]!!, 1e-9)
        assertEquals(2, p.cdaByBike.size)
    }
}
