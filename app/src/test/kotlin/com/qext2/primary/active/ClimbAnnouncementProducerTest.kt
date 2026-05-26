package com.qext2.primary.active

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimbAnnouncementProducerTest {

    private val producer = ClimbAnnouncementProducer()

    private fun state(
        hasRoute: Boolean = true,
        distanceToClimbM: Double = 300.0,
        climbElevationM: Int = 150,
        avgGradePercent: Double = 0.0,
        nowMs: Long = 1_000_000L,
    ) = ClimbState(
        hasRoute = hasRoute,
        distanceToClimbM = distanceToClimbM,
        climbElevationM = climbElevationM,
        avgGradePercent = avgGradePercent,
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
    fun `pre climb does not repeat`() {
        assertNotNull(producer.checkAndProduce(state(distanceToClimbM = 300.0, climbElevationM = 150)))
        assertNull(producer.checkAndProduce(state(distanceToClimbM = 300.0, climbElevationM = 150)))
    }

    @Test
    fun `climb active triggers when on grade`() {
        assertNotNull(producer.checkAndProduce(state()))
        assertNotNull(producer.checkAndProduce(state(avgGradePercent = 3.0)))
    }

    @Test
    fun `climb active does not repeat during same climb`() {
        assertNotNull(producer.checkAndProduce(state()))
        assertNotNull(producer.checkAndProduce(state(avgGradePercent = 3.0)))
        assertNull(producer.checkAndProduce(state(avgGradePercent = 4.0)))
    }

    @Test
    fun `climb finish triggers when grade drops`() {
        assertNotNull(producer.checkAndProduce(state()))
        assertNotNull(producer.checkAndProduce(state(avgGradePercent = 3.0)))
        val msg = producer.checkAndProduce(state(avgGradePercent = 0.0))
        assertNotNull(msg)
        assertEquals("PODJAZD DONE", msg!!.title)
    }

    @Test
    fun `climb finish only once`() {
        assertNotNull(producer.checkAndProduce(state()))
        assertNotNull(producer.checkAndProduce(state(avgGradePercent = 3.0)))
        assertNotNull(producer.checkAndProduce(state(avgGradePercent = 0.0)))
        assertNull(producer.checkAndProduce(state(avgGradePercent = 0.0)))
    }

    @Test
    fun `second climb can trigger again`() {
        assertNotNull(producer.checkAndProduce(state(distanceToClimbM = 300.0, climbElevationM = 100)))
        assertNotNull(producer.checkAndProduce(state(distanceToClimbM = 200.0, climbElevationM = 100, avgGradePercent = 3.0)))
        assertNotNull(producer.checkAndProduce(state(distanceToClimbM = 150.0, climbElevationM = 100, avgGradePercent = 0.0)))

        assertNotNull(producer.checkAndProduce(state(distanceToClimbM = 400.0, climbElevationM = 200)))
        assertNotNull(producer.checkAndProduce(state(distanceToClimbM = 350.0, climbElevationM = 200, avgGradePercent = 4.0)))
        assertNotNull(producer.checkAndProduce(state(distanceToClimbM = 300.0, climbElevationM = 200, avgGradePercent = 0.0)))
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
        assertNull(producer.checkAndProduce(state(hasRoute = false, avgGradePercent = 3.0)))
    }

    @Test
    fun `finish DROP_ON_INTERRUPT`() {
        assertNotNull(producer.checkAndProduce(state()))
        assertNotNull(producer.checkAndProduce(state(avgGradePercent = 3.0)))
        val msg = producer.checkAndProduce(state(avgGradePercent = 0.0))
        assertEquals(ActiveMessageResumePolicy.DROP_ON_INTERRUPT, msg!!.resumePolicy)
    }
}
