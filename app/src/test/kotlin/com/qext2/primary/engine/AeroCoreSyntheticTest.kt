package com.qext2.primary.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/**
 * AERO v3 — sztuczne jazdy (wzor: QBot tests/cda_synth.py). Znane CdA, Crr, wiatr; fizyka do przodu co 0,1 s.
 * Kryteria (QBot docs/QEXT2_AERO_V3_TASK.md pkt 6): zmiana kursu przy wietrze NIE daje falszywej zmiany > 10 W;
 * skok pozycji -15 % wykryty w <= 30 s; przejscie na szuter -> „od nowa” + niska pewnosc, nie „zmiana pozycji”.
 */
class AeroCoreSyntheticTest {

    /** Jedna sekunda scenariusza: moc rowerzysty, CdA, Crr (nawierzchnia), kurs, nachylenie, klasa nawierzchni. */
    data class Sec(val p: Double, val cda: Double, val crr: Double, val course: Double, val grade: Double = 0.0, val surf: Int = 1)

    class Sim(val mass: Double = 117.0, val windMs: Double = 0.0, val windFrom: Double = 0.0, val seed: Long = 1L,
              val powerNoise: Double = 0.03, val powerVar: Double = 0.15, val altNoise: Double = 0.03, val gradeBiasPct: Double = 0.0,
              val forecastFactor: Double = 1.0 / AeroParams.WIND_FACTOR, val windKnown: Boolean = true) {
        val rnd = java.util.Random(seed)
        var v = 7.0; var alt = 100.0; var t = 0L
        val p3 = ArrayDeque<Double>()
        fun headwind(course: Double) = windMs * cos(Math.toRadians(windFrom - course))
        fun run(core: AeroCore, secs: List<Sec>, onOut: (Int, Sec, AeroCore.Out) -> Unit = { _, _, _ -> }) {
            secs.forEachIndexed { k, s ->
                val hw = headwind(s.course)
                val pTrue = s.p * (1.0 + powerVar * rnd.nextGaussian()).coerceIn(0.4, 1.6)   // prawdziwe wahania pedalowania
                repeat(10) {
                    val va = v + hw
                    val f = pTrue * 0.976 / v - mass * 9.81 * (s.grade / 100.0 + s.crr) - 0.5 * 1.225 * s.cda * va * abs(va)
                    v = (v + f / mass * 0.1).coerceAtLeast(2.0)
                    alt += v * 0.1 * s.grade / 100.0
                }
                t += 1000
                val pm = pTrue * (1.0 + powerNoise * rnd.nextGaussian())   // blad miernika
                p3.addLast(pm); while (p3.size > 3) p3.removeFirst()
                // barometr Karoo: wygladzony, zapis co 0,2 m (FIT 18.10) -> kwantyzacja 0,2 m + maly szum
                val altMeas = Math.round((alt + altNoise * rnd.nextGaussian() + gradeBiasPct / 100.0 * 7.0 * t / 1000.0) / 0.2) * 0.2
                val o = core.step(AeroCore.In(t, p3.average(), v, 85, altMeas, s.grade, if (windKnown) hw * forecastFactor else null,
                    s.course, s.surf, 15.0))
                onOut(k, s, o)
            }
        }
    }

    private fun params() = AeroParams(117.0, 0.976, 0.0055, 0.0103, AeroParams.WIND_FACTOR, true, "test")
    private fun flat(n: Int, p: Double = 200.0, cda: Double = 0.43, crr: Double = 0.0055, course: Double = 0.0, surf: Int = 1) =
        List(n) { Sec(p, cda, crr, course, 0.0, surf) }

    private fun steps(outs: List<Pair<Int, AeroCore.Out>>) = outs.filter { it.second.event?.startsWith("step") == true }
    private fun maxAbsDw(outs: List<Pair<Int, AeroCore.Out>>, from: Int = 0) =
        outs.filter { it.first >= from && it.second.state == 2 }.maxOfOrNull { abs(it.second.dW) } ?: 0

    private fun run(sim: Sim, secs: List<Sec>): List<Pair<Int, AeroCore.Out>> {
        val core = AeroCore(params()); val outs = ArrayList<Pair<Int, AeroCore.Out>>()
        sim.run(core, secs) { k, _, o -> outs.add(k to o) }
        return outs
    }

    @Test fun stala_jazda_bez_zmian() {
        val outs = run(Sim(), flat(600))
        assertTrue("falszywe zmiany: ${steps(outs)}", steps(outs).isEmpty())
        assertTrue("pole wychodzi poza 0: ${maxAbsDw(outs)}", maxAbsDw(outs) <= 3)
        assertEquals(2, outs.last().second.conf)
        assertEquals(0.43, outs.last().second.cdaNow.toDouble(), 0.04)
    }

    @Test fun zmiana_kursu_przy_wietrze_bez_falszywej_zmiany() {
        for (w in listOf(3.0, 4.5, 6.0)) for (turn in listOf(90.0, 180.0)) {
            val secs = flat(240, course = 0.0) + flat(240, course = turn)
            val outs = run(Sim(windMs = w, windFrom = 20.0, seed = (w * 10 + turn).toLong()), secs)
            val bad = steps(outs).filter { abs(it.second.dW) > 10 }
            assertTrue("wiatr $w m/s, skret $turn: falszywe zmiany $bad", bad.isEmpty())
            assertTrue("wiatr $w, skret $turn: |dW| ${maxAbsDw(outs)} > 10", maxAbsDw(outs) <= 10)
        }
    }

    @Test fun petla_z_wiatrem() {
        val secs = List(900) { Sec(200.0, 0.43, 0.0055, (it * 0.8) % 360.0) }   // pelny obrot co 7,5 min
        val outs = run(Sim(windMs = 5.0, windFrom = 250.0, seed = 7), secs)
        assertTrue("petla: |dW| ${maxAbsDw(outs)}", maxAbsDw(outs) <= 10)
    }

    @Test fun skok_pozycji_minus_15_procent_wykryty_w_30_s() {
        val secs = flat(300) + flat(120, cda = 0.43 * 0.85) + flat(180)
        val outs = run(Sim(seed = 3), secs)
        val st = steps(outs)
        val first = st.firstOrNull { it.first >= 300 }
        assertNotNull("nie wykryto skoku: $st", first)
        assertTrue("wykryto po ${first!!.first - 300} s", first.first - 300 <= 30)
        assertTrue("zly znak: ${first.second}", first.second.dW < 0)
        // w trakcie pozycji aero pole pokazuje zysk ok. -15 % oporu powietrza (przy 200 W ~ -13..-20 W)
        val mid = outs.first { it.first == 400 }.second
        assertTrue("srodek pozycji: $mid", mid.dW in -26..-8)
        // powrot tez wykryty
        assertTrue("powrot: $st", st.any { it.first in 420..455 && it.second.dW > 0 })
        assertTrue("za duzo zmian: $st", st.size <= 3)
    }

    @Test fun skok_przy_wietrze_i_szumie() {
        val secs = flat(300, course = 90.0) + flat(120, cda = 0.43 * 0.85, course = 90.0) + flat(120, course = 90.0)
        val outs = run(Sim(windMs = 4.0, windFrom = 60.0, seed = 11, powerNoise = 0.05, powerVar = 0.3), secs)
        val first = steps(outs).firstOrNull { it.first >= 300 }
        assertNotNull(first)
        assertTrue("wykryto po ${first!!.first - 300} s", first.first - 300 <= 30)
    }

    @Test fun asfalt_na_szuter_to_nie_zmiana_pozycji() {
        val secs = flat(300) + flat(300, crr = 0.0103, surf = 2)
        val core = AeroCore(params()); val outs = ArrayList<Pair<Int, AeroCore.Out>>()
        Sim(seed = 5).run(core, secs) { k, _, o -> outs.add(k to o) }
        assertTrue("reset nawierzchni", outs.any { it.first in 300..340 && it.second.event == "nawierzchnia" })
        assertTrue("falszywa zmiana pozycji: ${steps(outs)}", steps(outs).none { it.first >= 300 })
        assertTrue("szuter = niska pewnosc", outs.filter { it.first > 400 && it.second.state == 2 }.all { it.second.conf == 1 })
    }

    @Test fun mokra_droga_skok_crr_bez_zmiany_klasy_jest_zmiana() {
        // dokument: opor drogi na szutrze rozni sie miedzy miejscami (~35 W) -- tego nie odroznimy od pozycji; na szutrze
        // pole ma tylko niska pewnosc. Test pilnuje, ze tak jest (pewnosc 1), a nie udaje pewnosci.
        val secs = flat(300, crr = 0.0103, surf = 2) + flat(200, crr = 0.0140, surf = 2)
        val outs = run(Sim(seed = 9), secs)
        assertTrue(outs.filter { it.second.state == 2 }.all { it.second.conf <= 1 })
    }

    @Test fun blad_nachylenia_dryf_barometru() {
        // dryf wysokosci 0,1 % nachylenia (stale) -- przesuniecie, ktore odejmuje sie miedzy teraz a odniesieniem
        val outs = run(Sim(gradeBiasPct = 0.1, seed = 13), flat(600))
        assertTrue("falszywe zmiany: ${steps(outs)}", steps(outs).isEmpty())
    }

    @Test fun pagorki_bilans_energii() {
        // fale +-3 % co 40 s: bilans z wysokosci (bez chwilowego nachylenia) -> brak falszywych zmian
        val secs = List(900) { Sec(220.0, 0.43, 0.0055, 0.0, if ((it / 40) % 2 == 0) 3.0 else -3.0) }
        val outs = run(Sim(seed = 17), secs)
        val bad = steps(outs).filter { abs(it.second.dW) > 10 }
        assertTrue("pagorki: $bad", bad.isEmpty())
    }

    @Test fun zmiany_mocy_interwaly() {
        // 150 W / 280 W co 60 s -- przyspieszanie liczone z energii kinetycznej
        val secs = List(900) { Sec(if ((it / 60) % 2 == 0) 150.0 else 280.0, 0.43, 0.0055, 0.0) }
        val outs = run(Sim(seed = 19), secs)
        val bad = steps(outs).filter { abs(it.second.dW) > 10 }
        assertTrue("interwaly: $bad", bad.isEmpty())
    }

    @Test fun wiatr_nieznany_skret_od_nowa() {
        val secs = flat(240, course = 0.0) + flat(240, course = 120.0)
        val core = AeroCore(params()); val outs = ArrayList<Pair<Int, AeroCore.Out>>()
        Sim(windMs = 4.0, windFrom = 0.0, windKnown = false, seed = 23).run(core, secs) { k, _, o -> outs.add(k to o) }
        assertTrue(outs.any { it.first in 240..245 && it.second.event == "kierunek" })
        assertTrue(outs.filter { it.second.state == 2 }.all { it.second.conf <= 1 })
    }

    @Test fun parametry_z_qbota() {
        val j = """{"version":1,"updatedAt":"2026-10-19T13:37:10+02:00","riderKg":103.04,"extraKg":14.0,"eta":0.976,
            "crr":{"asphalt":0.0055,"gravel":0.01026,"unknown":0.00788},"cdaDefault":0.3918}"""
        val p = AeroParams.fromJson(j)
        assertNotNull(p)
        assertEquals(117.04, p!!.massKg, 1e-6)
        assertEquals(0.01026, p.crrGravel, 1e-9)
        assertTrue(p.fromQbot)
        assertNull(AeroParams.fromJson("{}"))
        assertNull(AeroParams.fromJson("nie json"))
        assertTrue(!AeroParams.fallback(95.0).fromQbot)
        assertEquals(104.0, AeroParams.fallback(95.0).massKg, 1e-9)
    }
}
