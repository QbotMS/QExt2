package com.qext2.primary.active

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeepCooldownTrackerTest {

    @Test
    fun `dispatch failure does not trigger 10 second cooldown`() {
        val tracker = BeepCooldownTracker(successCooldownMs = 10_000L, errorCooldownMs = 2_000L)

        tracker.onFailure(1_000L)
        assertEquals(BeepSuppressionReason.ERROR_COOLDOWN, tracker.suppression(2_000L))
        assertNull(tracker.suppression(3_100L))
    }

    @Test
    fun `dispatch success triggers success cooldown`() {
        val tracker = BeepCooldownTracker(successCooldownMs = 10_000L, errorCooldownMs = 2_000L)

        tracker.onSuccess(1_000L)
        assertEquals(BeepSuppressionReason.SUCCESS_COOLDOWN, tracker.suppression(5_000L))
        assertNull(tracker.suppression(11_100L))
    }
}
