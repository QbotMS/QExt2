package com.qext2.primary.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AERO v3 — odtworzenie prawdziwych jazd tym samym kodem co na Karoo (dane: eksport QBota, app/src/test/resources/aero).
 * 18.10 (Grail, glownie szuter kat. 2, 4 zmiany chwytu na km 10-12; prognoza wiatru z FIT 2,4 m/s z ~10 st.)
 * 16.10 (Grail, glownie asfalt; QBot z jazdy: wiatr 0,5 m/s z 310 st. -> „prognoza” 2,5 m/s przy wspolczynniku 0,2).
 * To NIE jest dowod trafnosci (brak znacznikow pozycji) -- to straznik regresji i liczby do dokumentacji:
 * udzial czasu z wynikiem, pewnosc, zmiany na godzine, mediana CdA (QBot Centrum CdA: 18.10 0,51, 16.10 0,45).
 */
class AeroReplayTest {
    private val p = AeroParams(103.04 + 14.0, 0.976, 0.0055, 0.01026, AeroParams.WIND_FACTOR, true, "replay")

    private fun summary(name: String, r: AeroReplay.Result): Triple<Double, Double, Double> {
        val c = r.outs.filter { it.second.state == 2 }.map { it.second.cdaNow.toDouble() }.sorted()
        val med = c[c.size / 2]
        val perH = r.steps.size / (r.state[2] / 3600.0)
        val share = r.state[2].toDouble() / r.n
        println("AERO_REPLAY $name n=${r.n} stan=${r.state.toList()} pewnosc=${r.conf.toList()} zmiany=${r.steps.size} " +
            "(%.0f/h wyniku) reset=${r.resets} CdA med=%.3f".format(perH, med))
        return Triple(share, perH, med)
    }

    @Test fun jazda_18_10_szuter() {
        val r = AeroReplay.run(AeroReplay.load("ride_2026-10-18.csv.gz"), p, 2.4, 10.0)
        val (share, perH, med) = summary("18.10", r)
        assertTrue("za malo wyniku: $share", share >= 0.30)
        assertTrue("za duzo zmian: $perH/h", perH <= 70.0)
        assertTrue("CdA: $med", med in 0.35..0.75)
        assertTrue("szuter = niska pewnosc", r.conf[2] < r.conf[1])
    }

    @Test fun jazda_16_10_asfalt() {
        val r = AeroReplay.run(AeroReplay.load("ride_2026-10-16.csv.gz"), p, 2.5, 310.0)
        val (share, perH, med) = summary("16.10", r)
        assertTrue("za malo wyniku: $share", share >= 0.20)
        assertTrue("za duzo zmian: $perH/h", perH <= 70.0)
        assertTrue("CdA: $med", med in 0.30..0.70)
        assertTrue("asfalt = glownie dobra pewnosc", r.conf[2] > r.conf[1])
    }

    @Test fun bez_parametrow_qbota_zawsze_niska_pewnosc() {
        val r = AeroReplay.run(AeroReplay.load("ride_2026-10-16.csv.gz"), AeroParams.fallback(103.0), 2.5, 310.0)
        assertTrue(r.conf[2] == 0)
    }
}
