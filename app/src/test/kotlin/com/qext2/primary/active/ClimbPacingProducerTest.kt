package com.qext2.primary.active

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimbPacingProducerTest {

    private val cp = 250f
    private val wp = 20f   // kJ

    private fun produce(
        producer: ClimbPacingProducer,
        power: Int,
        pct: Int,
        nowMs: Long,
    ): ActiveMessage? = producer.checkAndProduce(
        power = power,
        wBalancePct = pct,
        effectiveLtpW = 240f,
        cpEffW = cp,
        wPrimeEffKj = wp,
        isWithinBounds = false,
        ascentLeftM = 0,
        grade = 0.0,
        climbIndex = -1,
        modeFactor = 1.0f,
        nowMs = nowMs,
    )

    @Test
    fun `cisza powyzej progu 55 procent`() {
        val p = ClimbPacingProducer()
        assertNull(produce(p, power = 300, pct = 60, nowMs = 1_000L))
    }

    @Test
    fun `trzymasz w martwej strefie wokol CP`() {
        val p = ClimbPacingProducer()
        val msg = produce(p, power = 253, pct = 40, nowMs = 1_000L)
        assertNotNull(msg)
        assertEquals("UWAGA!", msg!!.title)
        assertEquals("40% W'", msg.line1)
        assertEquals("TRZYMASZ!", msg.line2)
    }

    @Test
    fun `odbudowa gdy moc ponizej CP`() {
        val p = ClimbPacingProducer()
        val msg = produce(p, power = 150, pct = 50, nowMs = 1_000L)
        assertNotNull(msg)
        assertTrue(msg!!.line2!!.startsWith("odbudowa "))
    }

    @Test
    fun `bomba liczona jako Wbal przez nadwyzke nad CP`() {
        val p = ClimbPacingProducer()
        // 50% z 20 kJ = 10000 J; nadwyzka 100 W -> 100 s -> 1:40
        val msg = produce(p, power = 350, pct = 50, nowMs = 1_000L)
        assertNotNull(msg)
        assertEquals("bomba 1:40", msg!!.line2)
        assertEquals(ActiveMessageSeverity.WARNING, msg.severity)
    }

    @Test
    fun `strefa krytyczna ponizej 30 s jest CRITICAL i tyka co sekunde`() {
        val p = ClimbPacingProducer()
        // 10% z 20 kJ = 2000 J; nadwyzka 200 W -> 10 s
        val first = produce(p, power = 450, pct = 10, nowMs = 1_000L)
        assertNotNull(first)
        assertEquals("bomba 0:10", first!!.line2)
        assertEquals(ActiveMessageSeverity.CRITICAL, first.severity)
        // po 1 s kolejny komunikat przechodzi (cooldown 1 s)
        assertNotNull(produce(p, power = 450, pct = 10, nowMs = 2_000L))
    }

    @Test
    fun `przepal przy zerowym baku i mocy nad CP`() {
        val p = ClimbPacingProducer()
        val msg = produce(p, power = 320, pct = 0, nowMs = 1_000L)
        assertNotNull(msg)
        assertEquals("PRZEPA\u0141", msg!!.line2)
        assertEquals(ActiveMessageSeverity.CRITICAL, msg.severity)
    }

    @Test
    fun `cooldown 60 s dla trzymasz`() {
        val p = ClimbPacingProducer()
        assertNotNull(produce(p, power = 253, pct = 40, nowMs = 1_000L))
        assertNull(produce(p, power = 253, pct = 40, nowMs = 20_000L))
        assertNotNull(produce(p, power = 253, pct = 40, nowMs = 70_000L))
    }

    @Test
    fun `format mm ss`() {
        assertEquals("0:10", ClimbPacingProducer.formatMmSs(10f))
        assertEquals("1:40", ClimbPacingProducer.formatMmSs(100f))
        assertEquals("13:51", ClimbPacingProducer.formatMmSs(831f))
    }
}
