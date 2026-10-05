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

    @Test
    fun noRouteNoEta() {
        val e = EtaEngine()
        assertEquals(0L, e.tick(1_000_000_000L, true, 20.0, 0.0, false, 0.0, 0L, false, null).etaMs)
    }
}
