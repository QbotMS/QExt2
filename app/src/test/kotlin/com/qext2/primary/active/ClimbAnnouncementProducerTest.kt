package com.qext2.primary.active

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Test dopasowany do KONTRAKTU z 2026-06 (nawierzchnia+pacing):
 * - PODJAZD (pre): dystans do podjazdu w (0, 500] m, raz na climbIndex
 * - w trakcie (isWithinClimbBounds=true): producent MILCZY — komunikaty
 *   w trakcie podjazdu przejal ClimbPacingProducer; tu tylko stan wewnetrzny
 * - PODJAZD DONE (finish): po wyjsciu z granic podjazdu, raz
 * Poprzednia wersja testu (2026-05) zakladala sygnal z avgGradePercent
 * i komunikat "active" — nie kompilowala sie od dodania isWithinClimbBounds.
 * Naprawa: NAPRAWA Etap 0 (bramka testowa w CI wykryla to pierwszego dnia).
 */
class ClimbAnnouncementProducerTest {

    private val producer = ClimbAnnouncementProducer()

    private fun state(
        hasRoute: Boolean = true,
        distanceToClimbM: Double = 300.0,
        climbElevationM: Int = 150,
        avgGradePercent: Double = 0.0,
        isWithinClimbBounds: Boolean = false,
        climbIndex: Int = 0,
        nowMs: Long = 1_000_000L,
    ) = ClimbState(
        hasRoute = hasRoute,
        distanceToClimbM = distanceToClimbM,
        climbElevationM = climbElevationM,
        avgGradePercent = avgGradePercent,
        isWithinClimbBounds = isWithinClimbBounds,
        climbIndex = climbIndex,
        nowMs = nowMs,
    )

    @Test
    fun `pre climb triggers at 300m`() {
        val msg = producer.checkAndProduce(state())
        assertNotNull(msg)
        assertEquals("PODJAZD", msg!!.title)
    }

    @Test
    fun `pre climb does not trigger beyond 500m`() {
        assertNull(producer.checkAndProduce(state(distanceToClimbM = 501.0)))
    }

    @Test
    fun `pre climb does not repeat for same climb index`() {
        assertNotNull(producer.checkAndProduce(state()))
        assertNull(producer.checkAndProduce(state()))
    }

    @Test
    fun `entering climb bounds is silent`() {
        assertNotNull(producer.checkAndProduce(state()))
        // w granicach podjazdu: komunikaty przejal ClimbPacingProducer
        assertNull(producer.checkAndProduce(state(distanceToClimbM = 0.0, isWithinClimbBounds = true)))
    }

    @Test
    fun `climb finish triggers after leaving bounds`() {
        assertNotNull(producer.checkAndProduce(state()))
        assertNull(producer.checkAndProduce(state(distanceToClimbM = 0.0, isWithinClimbBounds = true)))
        val msg = producer.checkAndProduce(state(distanceToClimbM = 0.0, isWithinClimbBounds = false))
        assertNotNull(msg)
        assertEquals("PODJAZD DONE", msg!!.title)
    }

    @Test
    fun `climb finish only once`() {
        assertNotNull(producer.checkAndProduce(state()))
        assertNull(producer.checkAndProduce(state(distanceToClimbM = 0.0, isWithinClimbBounds = true)))
        assertNotNull(producer.checkAndProduce(state(distanceToClimbM = 0.0, isWithinClimbBounds = false)))
        assertNull(producer.checkAndProduce(state(distanceToClimbM = 0.0, isWithinClimbBounds = false)))
    }

    @Test
    fun `second climb can trigger again`() {
        assertNotNull(producer.checkAndProduce(state(climbIndex = 0)))
        assertNull(producer.checkAndProduce(state(distanceToClimbM = 0.0, isWithinClimbBounds = true, climbIndex = 0)))
        assertNotNull(producer.checkAndProduce(state(distanceToClimbM = 0.0, isWithinClimbBounds = false, climbIndex = 0)))

        assertNotNull(producer.checkAndProduce(state(distanceToClimbM = 400.0, climbElevationM = 200, climbIndex = 1)))
        assertNull(producer.checkAndProduce(state(distanceToClimbM = 0.0, isWithinClimbBounds = true, climbIndex = 1)))
        assertNotNull(producer.checkAndProduce(state(distanceToClimbM = 0.0, isWithinClimbBounds = false, climbIndex = 1)))
    }

    @Test
    fun `reset clears all state`() {
        assertNotNull(producer.checkAndProduce(state()))
        producer.reset()
        assertNotNull(producer.checkAndProduce(state()))
    }

    @Test
    fun `no route produces no messages`() {
        assertNull(producer.checkAndProduce(state(hasRoute = false)))
        assertNull(producer.checkAndProduce(state(hasRoute = false, isWithinClimbBounds = true)))
    }

    @Test
    fun `finish DROP_ON_INTERRUPT`() {
        assertNotNull(producer.checkAndProduce(state()))
        assertNull(producer.checkAndProduce(state(distanceToClimbM = 0.0, isWithinClimbBounds = true)))
        val msg = producer.checkAndProduce(state(distanceToClimbM = 0.0, isWithinClimbBounds = false))
        assertEquals(ActiveMessageResumePolicy.DROP_ON_INTERRUPT, msg!!.resumePolicy)
    }
}
