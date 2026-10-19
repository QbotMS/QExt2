package com.qext2.primary.eta

import com.qext2.primary.model.SurfaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaEngineTest {

    private fun encode(vals: List<Pair<Double, Double>>, factor: Double = 10.0): String {
        val sb = StringBuilder()
        var pa = 0L
        var pb = 0L
        fun enc(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else (v shl 1)
            while (x >= 0x20) {
                sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
                x = x shr 5
            }
            sb.append((x.toInt() + 63).toChar())
        }
        for ((a, b) in vals) {
            val ia = Math.round(a * factor)
            val ib = Math.round(b * factor)
            enc(ia - pa); enc(ib - pb)
            pa = ia; pb = ib
        }
        return sb.toString()
    }

    private val flat = (0..200).map { it * 100.0 to 50.0 }
    private val paved: (Double) -> SurfaceType? = { SurfaceType.PAVED }

    @Test
    fun decodeRoundTrip() {
        val pts = (0..400).map { it * 50.0 to 100.0 + it * 0.5 }
        val dec = ElevationPolyline.decode(encode(pts))
        assertEquals(pts.size, dec.size)
        assertEquals(6000.0, dec[120].first, 0.11)
        assertEquals(160.0, dec[120].second, 0.11)
    }

    @Test
    fun flatPavedPlanUsesQbotTable() {
        val r = EtaPlanner.build(flat, 20000.0, paved)
        assertNotNull(r.plan)
        assertEquals(20.0 / 22.5 * 3600.0, r.plan!!.totalSec, 30.0)
    }

    @Test
    fun unknownSurfaceIsAverage() {
        val r = EtaPlanner.build(flat, 20000.0, null)
        assertEquals(20.0 / ((22.5 + 18.8) / 2.0) * 3600.0, r.plan!!.totalSec, 30.0)
    }

    @Test
    fun badProfilesFallBack() {
        assertNull(EtaPlanner.build((0..10).map { it * 2000.0 to 0.0 }, 20000.0, null).plan)
        assertEquals("length_mismatch", EtaPlanner.build(flat, 40000.0, null).reason)
        assertEquals("too_few_points", EtaPlanner.build(listOf(0.0 to 1.0), null, null).reason)
    }

    @Test
    fun correctionFollowsTodaysPace() {
        val e = EtaEngine()
        e.setRoute(encode(flat), 20000.0, true, paved)
        var t = 1_000_000_000L
        var done = 0.0
        val v = 18.0
        var out: EtaEngine.Output? = null
        while (done < 10000.0) {
            t += 1000; done += v / 3.6
            out = e.tick(t, true, v, 20000.0 - done, true, done, 0L, true, paved)
        }
        val o = out!!
        assertEquals(2, o.level)
        assertEquals(22.5 / 18.0, o.factor, 0.03)
        assertEquals(10.0 / v * 3600.0, o.remMovingSec, 60.0)
    }

    @Test
    fun shortStopConsumesPoolLongStopMovesEta() {
        val e = EtaEngine()
        e.setRoute(encode(flat), 20000.0, true, paved)
        var t = 1_000_000_000L
        var done = 0.0
        var o: EtaEngine.Output? = null
        while (done < 5000.0) {
            t += 1000; done += 20.0 / 3.6
            o = e.tick(t, true, 20.0, 20000.0 - done, true, done, 0L, true, paved)
        }
        val before = o!!.etaMs
        repeat(300) { t += 1000; o = e.tick(t, false, 0.0, 20000.0 - done, true, done, 0L, true, paved) }
        assertTrue((o!!.etaMs - before) / 60000.0 <= 5.01)
        repeat(1800) { t += 1000; o = e.tick(t, false, 0.0, 20000.0 - done, true, done, 0L, true, paved) }
        assertTrue((o!!.etaMs - before) / 60000.0 > 30.0)
    }

    @Test
    fun level3WithoutProfileUsesPrior() {
        val e = EtaEngine(loadPriorKmh = { 20.0 })
        val o = e.tick(1_000_000_000L, true, 20.0, 20000.0, true, 0.0, 0L, false, null)
        assertEquals(3, o.level)
        assertEquals(3600.0, o.remMovingSec, 1.0)
    }

    @org.junit.After
    fun resetModel() { EtaSpeedTable.resetBuiltin() }

    // --- 2026-10-19: model czasu QBota (profile jazd, plan QBota = poziom 1)

    @Test
    fun serverModelReplacesTableAndRejectsBadData() {
        val p = DoubleArray(11) { 25.0 }; val u = DoubleArray(11) { 20.0 }
        assertTrue(EtaSpeedTable.setModel("2026-10-19p", p, u, mapOf("trening" to doubleArrayOf(0.2, 5.0)), null, 2.0))
        assertEquals(25.0, EtaSpeedTable.speedKmh(0.0, SurfaceType.PAVED), 1e-9)
        assertEquals("2026-10-19p", EtaSpeedTable.version)
        assertTrue(!EtaSpeedTable.setModel("x", DoubleArray(3) { 20.0 }, u, emptyMap(), null, 2.0))
        assertTrue(!EtaSpeedTable.setModel("x", DoubleArray(11) { 99.0 }, u, emptyMap(), null, 2.0))
        assertEquals("2026-10-19p", EtaSpeedTable.version)
    }

    @Test
    fun profileStopsFromFirstKm() {
        EtaSpeedTable.setModel("v", DoubleArray(11) { 22.0 }, DoubleArray(11) { 18.0 },
            mapOf("trening" to doubleArrayOf(0.2, 5.0)), null, 2.0)
        assertEquals((0.2 * 20 + 5.0 * 1.0) * 60.0, EtaSpeedTable.shortStopsSec(20.0, 1.0, "trening"), 1e-6)
        // brak profilu -> stary wzor
        assertEquals((0.22 * 20 + 20 / 9.0 * 4.5) * 60.0, EtaSpeedTable.shortStopsSec(20.0, 1.0, null), 1e-6)
    }

    @Test
    fun profileForSeasonAndWeekday() {
        EtaSpeedTable.setModel("v", DoubleArray(11) { 22.0 }, DoubleArray(11) { 18.0 }, emptyMap(),
            DoubleArray(12) { if (it == 6) 3.1 else 1.6 }, 2.0)
        assertEquals("dluga", EtaSpeedTable.profileFor(2.4, 10, 3))
        assertEquals("trening", EtaSpeedTable.profileFor(2.4, 7, 3))
        assertEquals("trening", EtaSpeedTable.profileFor(1.0, 10, 3))
        assertEquals("krotka_weekend", EtaSpeedTable.profileFor(1.0, 10, 7))
    }

    @Test
    fun qbotPlanIsLevel1() {
        val e = EtaEngine()
        e.setRoute(encode(flat), 20000.0, true, paved)
        val items = (1..20).map { floatArrayOf(it.toFloat(), it * 3f, it * 0.5f) }   // 3 min/km ruchu, 0,5 min/km postojow
        e.setQbotPlan(items)
        val o = e.tick(1_000_000_000L, true, 20.0, 20000.0, true, 0.0, 0L, true, paved)
        assertEquals(1, o.level)
        assertEquals(60.0 * 60.0, o.remMovingSec, 60.0)
        assertEquals(10.0 * 60.0, o.stopsSec, 30.0)
        e.setQbotPlan(emptyList())   // brak planu -> poziom 2
        assertEquals(2, e.tick(1_000_001_000L, true, 20.0, 20000.0, true, 0.0, 0L, true, paved).level)
    }

    @Test
    fun qbotPlanWithWrongLengthIsIgnored() {
        val e = EtaEngine()
        e.setRoute(encode(flat), 20000.0, true, paved)
        e.setQbotPlan((1..40).map { floatArrayOf(it.toFloat(), it * 3f, 0f) })
        assertEquals(2, e.tick(1_000_000_000L, true, 20.0, 20000.0, true, 0.0, 0L, true, paved).level)
    }

    @Test
    fun noRouteNoEta() {
        val e = EtaEngine()
        assertEquals(0L, e.tick(1_000_000_000L, true, 20.0, 0.0, false, 0.0, 0L, false, null).etaMs)
    }
}
