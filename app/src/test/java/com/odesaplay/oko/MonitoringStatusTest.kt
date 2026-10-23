package com.odesaplay.oko

import com.odesaplay.oko.service.MonitoringStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The monitoring status is the one fact the "service offline" banner and every resurrection path
 * read. These tests pin the two asymmetries that matter: an unconfirmed start may not look alive,
 * and a deliberate stop may not be undone by an automatic path.
 */
class MonitoringStatusTest {

    @Before
    fun setUp() = MonitoringStatus.reset()

    @After
    fun tearDown() = MonitoringStatus.reset()

    @Test
    fun `unknown process shows no banner and asks for a start`() {
        assertFalse(MonitoringStatus.bannerVisible(nowMonoMs = 0L))
        assertTrue(MonitoringStatus.needsRestart())
        assertFalse(MonitoringStatus.isServiceUp())
    }

    @Test
    fun `a start hides the banner only inside the grace period`() {
        MonitoringStatus.startRequested(nowMonoMs = 1_000L)

        assertFalse(MonitoringStatus.bannerVisible(nowMonoMs = 1_000L + 1_000L))
        assertTrue(
            MonitoringStatus.bannerVisible(
                nowMonoMs = 1_000L + MonitoringStatus.START_GRACE_MS + 1L
            )
        )
        assertFalse(MonitoringStatus.needsRestart())
        assertTrue(MonitoringStatus.isServiceUp())
    }

    @Test
    fun `a confirmed start hides the banner`() {
        MonitoringStatus.startRequested(nowMonoMs = 0L)
        MonitoringStatus.running(nowMonoMs = 500L)

        assertFalse(MonitoringStatus.bannerVisible(nowMonoMs = 500L))
        assertFalse(MonitoringStatus.needsRestart())
        assertTrue(MonitoringStatus.isServiceUp())
    }

    @Test
    fun `an os death never overrides a deliberate stop`() {
        MonitoringStatus.stoppedByUser()
        MonitoringStatus.died(MonitoringStatus.Reason.DESTROYED, nowMonoMs = 2_000L)

        assertEquals(MonitoringStatus.State.StoppedByUser, MonitoringStatus.state.value)
        assertFalse(MonitoringStatus.bannerVisible(nowMonoMs = 2_000L))
        assertFalse(MonitoringStatus.needsRestart())
        assertFalse(MonitoringStatus.isServiceUp())
    }

    @Test
    fun `a deliberate stop is not a banner`() {
        MonitoringStatus.stoppedByUser()

        assertFalse(MonitoringStatus.bannerVisible(nowMonoMs = 0L))
        assertFalse(MonitoringStatus.needsRestart())
    }

    @Test
    fun `a fresh launch ends the session-only stop`() {
        MonitoringStatus.stoppedByUser()
        MonitoringStatus.sessionStarted()

        assertTrue(MonitoringStatus.needsRestart())
        assertFalse(MonitoringStatus.bannerVisible(nowMonoMs = 0L))
    }

    @Test
    fun `a destroyed service asks for a restart and shows the banner`() {
        MonitoringStatus.running(nowMonoMs = 0L)
        MonitoringStatus.died(MonitoringStatus.Reason.DESTROYED, nowMonoMs = 1_000L)

        assertTrue(MonitoringStatus.bannerVisible(nowMonoMs = 1_000L))
        assertTrue(MonitoringStatus.needsRestart())
        assertFalse(MonitoringStatus.isServiceUp())
    }

    @Test
    fun `the first cause survives a later generic destroy`() {
        MonitoringStatus.died(MonitoringStatus.Reason.START_REFUSED, nowMonoMs = 1_000L)
        MonitoringStatus.died(MonitoringStatus.Reason.DESTROYED, nowMonoMs = 2_000L)

        assertEquals(
            MonitoringStatus.State.Dead(MonitoringStatus.Reason.START_REFUSED, 1_000L),
            MonitoringStatus.state.value
        )
    }
}
