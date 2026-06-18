package com.qext2.primary.active

import com.qext2.primary.model.SurfaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimalCadenceModelTest {

    // Pomocnik — oblicza zakres i sprawdza że low <= high i mieszczą się w sensownych granicach
    private fun compute(
        powerW: Int = 200,
        effectiveFtp: Float = 250f,
        gradePercent: Double = 0.0,
        surface: SurfaceType = SurfaceType.PAVED,
        todayFactor: Float = 1.0f,
        decouplingPct: Float = 0f,
    ) = OptimalCadenceModel.compute(powerW, effectiveFtp, gradePercent, surface, todayFactor, decouplingPct)

    @Test
    fun `zakres zawsze ma low niższy niż high z marginesem 8 RPM`() {
        listOf(
            compute(powerW = 100, gradePercent = 0.0, surface = SurfaceType.PAVED),
            compute(powerW = 250, gradePercent = 0.0, surface = SurfaceType.PAVED),
            compute(powerW = 200, gradePercent = 10.0, surface = SurfaceType.LOOSE, todayFactor = 0.80f, decouplingPct = 12f),
            compute(powerW = 150, gradePercent = -4.0, surface = SurfaceType.GRAVEL, todayFactor = 1.08f),
        ).forEach { r ->
            assertTrue("low=${r.low} musi być < high=${r.high}", r.low < r.high)
            assertTrue("margines min 8 RPM", r.high - r.low >= 8)
            assertTrue("low >= 48", r.low >= 48)
            assertTrue("high <= 112", r.high <= 112)
        }
    }

    @Test
    fun `plaski asfalt dobry dzien 200W daje typowy zakres szosowy`() {
        val r = compute(powerW = 200, effectiveFtp = 250f, gradePercent = 0.0,
                        surface = SurfaceType.PAVED, todayFactor = 1.0f, decouplingPct = 0f)
        // 200W / 250W = 0.80 → base ~82, high ~97
        assertTrue("low w okolicach 80–86 RPM, got ${r.low}", r.low in 78..88)
        assertTrue("high w okolicach 93–100 RPM, got ${r.high}", r.high in 90..102)
    }

    @Test
    fun `stromy gravel zly dzien obnizy zakres o ponad 15 RPM vs plasty asfalt dobry dzien`() {
        val good = compute(powerW = 200, gradePercent = 0.0,
                           surface = SurfaceType.PAVED, todayFactor = 1.0f)
        val hard = compute(powerW = 180, gradePercent = 8.0,
                           surface = SurfaceType.GRAVEL, todayFactor = 0.85f, decouplingPct = 6f)
        val diff = good.low - hard.low
        assertTrue("stromy gravel + zły dzień musi obniżyć zakres o ≥15 RPM, got diff=$diff", diff >= 15)
    }

    @Test
    fun `zjazd podnosi zakres w gore`() {
        val flat = compute(powerW = 150, gradePercent = 0.0)
        val descent = compute(powerW = 150, gradePercent = -5.0)
        assertTrue("zjazd musi podnieść low, got flat=${flat.low} descent=${descent.low}",
                   descent.low > flat.low)
    }

    @Test
    fun `luznna nawierzchnia daje nizszy zakres niz gravel i asfalt`() {
        val paved  = compute(powerW = 200, surface = SurfaceType.PAVED)
        val gravel = compute(powerW = 200, surface = SurfaceType.GRAVEL)
        val loose  = compute(powerW = 200, surface = SurfaceType.LOOSE)
        assertTrue("PAVED.low > GRAVEL.low", paved.low > gravel.low)
        assertTrue("GRAVEL.low > LOOSE.low", gravel.low > loose.low)
    }

    @Test
    fun `wysoki decoupling obniża zakres`() {
        val fresh     = compute(powerW = 220, decouplingPct = 0f)
        val drifting  = compute(powerW = 220, decouplingPct = 9f)
        val exhausted = compute(powerW = 220, decouplingPct = 14f)
        assertTrue("decoupling 9% musi obniżyć", drifting.low < fresh.low)
        assertTrue("decoupling 14% musi obniżyć bardziej", exhausted.low < drifting.low)
    }

    @Test
    fun `assess OPTIMAL gdy RPM w srodku zakresu`() {
        val r = OptimalCadenceModel.CadenceRange(75, 90)
        assertEquals(OptimalCadenceModel.CadenceStatus.OPTIMAL, OptimalCadenceModel.assess(82, r))
    }

    @Test
    fun `assess CRITICAL_LOW gdy RPM ponizej low minus 10`() {
        val r = OptimalCadenceModel.CadenceRange(75, 90)
        assertEquals(OptimalCadenceModel.CadenceStatus.CRITICAL_LOW, OptimalCadenceModel.assess(60, r))
    }

    @Test
    fun `assess LOW gdy RPM miedzy low minus 10 a low minus 5`() {
        val r = OptimalCadenceModel.CadenceRange(75, 90)
        assertEquals(OptimalCadenceModel.CadenceStatus.LOW, OptimalCadenceModel.assess(68, r))
    }

    @Test
    fun `assess HIGH gdy RPM powyżej high`() {
        val r = OptimalCadenceModel.CadenceRange(75, 90)
        assertEquals(OptimalCadenceModel.CadenceStatus.HIGH, OptimalCadenceModel.assess(95, r))
    }

    @Test
    fun `assess NO_DATA gdy RPM zero`() {
        val r = OptimalCadenceModel.CadenceRange(75, 90)
        assertEquals(OptimalCadenceModel.CadenceStatus.NO_DATA, OptimalCadenceModel.assess(0, r))
    }
}
