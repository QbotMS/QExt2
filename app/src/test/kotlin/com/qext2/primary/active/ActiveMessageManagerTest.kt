package com.qext2.primary.active

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveMessageManagerTest {

    private val manager = ActiveMessageManager()

    private fun now() = System.currentTimeMillis()
    private fun msg(id: String = "t", priority: ActiveMessagePriority = ActiveMessagePriority.INFO,
                    resumePolicy: ActiveMessageResumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT,
                    expiresInMs: Long = 5000L) =
        ActiveMessage(id = id, title = "Test", line1 = "L1", line2 = null,
            severity = ActiveMessageSeverity.INFO, priority = priority,
            resumePolicy = resumePolicy, createdAtMs = now(), expiresAtMs = now() + expiresInMs)

    @Test
    fun `show stores message`() {
        assertTrue(manager.show(msg()))
        assertNotNull(manager.getCurrent(now()))
    }

    @Test
    fun `getCurrent before expiry returns message`() {
        manager.show(msg())
        assertNotNull(manager.getCurrent(now()))
    }

    @Test
    fun `at expiry getCurrent returns null`() {
        val m = msg(expiresInMs = 1L)
        manager.show(m)
        Thread.sleep(2)
        assertNull(manager.getCurrent(now()))
    }

    @Test
    fun `hideExpired clears current`() {
        val m = msg(expiresInMs = 1L)
        manager.show(m)
        Thread.sleep(2)
        val r = manager.hideExpired(now())
        assertTrue(r is ExpiryResult.Expired)
        assertNull(manager.getCurrent(now()))
    }

    @Test
    fun `clear removes current`() {
        manager.show(msg())
        manager.clear()
        assertNull(manager.getCurrent(now()))
    }

    @Test
    fun `getCurrent with no message returns null`() {
        assertNull(manager.getCurrent(now()))
    }

    @Test
    fun `hideExpired with no message returns None`() {
        assertTrue(manager.hideExpired(now()) is ExpiryResult.None)
    }

    @Test
    fun `critical interrupts info`() {
        manager.show(msg("info", priority = ActiveMessagePriority.INFO, expiresInMs = 5000L))
        assertTrue(manager.show(msg("crit", priority = ActiveMessagePriority.CRITICAL, expiresInMs = 5000L)))
        assertEquals("crit", manager.getCurrent(now())!!.id)
    }

    @Test
    fun `lower priority does not interrupt higher`() {
        manager.show(msg("warn", priority = ActiveMessagePriority.WARNING, expiresInMs = 5000L))
        assertFalse(manager.show(msg("info", priority = ActiveMessagePriority.INFO, expiresInMs = 5000L)))
        assertEquals("warn", manager.getCurrent(now())!!.id)
    }

    @Test
    fun `same priority does not interrupt`() {
        manager.show(msg("first", priority = ActiveMessagePriority.WARNING, expiresInMs = 5000L))
        assertFalse(manager.show(msg("second", priority = ActiveMessagePriority.WARNING, expiresInMs = 5000L)))
        assertEquals("first", manager.getCurrent(now())!!.id)
    }

    @Test
    fun `expired current allows new message`() {
        manager.show(msg("expired", priority = ActiveMessagePriority.CRITICAL, expiresInMs = 1L))
        Thread.sleep(2)
        assertTrue(manager.show(msg("new", priority = ActiveMessagePriority.INFO_LOW, expiresInMs = 5000L)))
        assertEquals("new", manager.getCurrent(now())!!.id)
    }

    @Test
    fun `DROP_ON_INTERRUPT is not resumed`() {
        manager.show(msg("drop", priority = ActiveMessagePriority.INFO,
            resumePolicy = ActiveMessageResumePolicy.DROP_ON_INTERRUPT, expiresInMs = 5000L))
        manager.show(msg("crit", priority = ActiveMessagePriority.CRITICAL, expiresInMs = 1L))
        Thread.sleep(2)
        val r = manager.hideExpired(now())
        assertTrue(r is ExpiryResult.Expired)
        assertNull(manager.getCurrent(now()))
    }

    @Test
    fun `RESUME_IF_STILL_VALID resumes after interrupter expires`() {
        manager.show(msg("resume", priority = ActiveMessagePriority.INFO,
            resumePolicy = ActiveMessageResumePolicy.RESUME_IF_STILL_VALID, expiresInMs = 5000L))
        manager.show(msg("crit", priority = ActiveMessagePriority.CRITICAL, expiresInMs = 1L))
        Thread.sleep(2)
        val r = manager.hideExpired(now())
        assertTrue(r is ExpiryResult.Resumed)
        assertEquals("resume", (r as ExpiryResult.Resumed).message.id)
        assertEquals("resume", manager.getCurrent(now())!!.id)
    }

    @Test
    fun `expired suspended message does not resume`() {
        manager.show(msg("resume", priority = ActiveMessagePriority.INFO,
            resumePolicy = ActiveMessageResumePolicy.RESUME_IF_STILL_VALID, expiresInMs = 1L))
        manager.show(msg("crit", priority = ActiveMessagePriority.CRITICAL, expiresInMs = 1L))
        Thread.sleep(2)
        val r = manager.hideExpired(now())
        assertTrue(r is ExpiryResult.Expired)
        assertNull(manager.getCurrent(now()))
    }

    @Test
    fun `interrupted INFO expires while CRITICAL active`() {
        manager.show(msg("info", priority = ActiveMessagePriority.INFO,
            resumePolicy = ActiveMessageResumePolicy.RESUME_IF_STILL_VALID, expiresInMs = 5L))
        manager.show(msg("crit", priority = ActiveMessagePriority.CRITICAL, expiresInMs = 15L))
        assertEquals("crit", manager.getCurrent(now())!!.id)
        Thread.sleep(20)
        val r = manager.hideExpired(now())
        assertTrue("expected Expired, got $r", r is ExpiryResult.Expired)
        assertNull(manager.getCurrent(now()))
    }

    @Test
    fun `new higher priority replaces expired current`() {
        manager.show(msg("old", priority = ActiveMessagePriority.INFO, expiresInMs = 1L))
        Thread.sleep(2)
        assertTrue(manager.show(msg("new", priority = ActiveMessagePriority.WARNING, expiresInMs = 5000L)))
        assertEquals("new", manager.getCurrent(now())!!.id)
    }
}
