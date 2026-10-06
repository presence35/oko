package com.odesaplay.oko.connection

import com.odesaplay.oko.connection.ResilientConnectionSupervisor.Companion.DEGRADED_STALE_MS
import com.odesaplay.oko.connection.ResilientConnectionSupervisor.Companion.SILENCE_TIMEOUT_MS
import com.odesaplay.oko.connection.ResilientConnectionSupervisor.Companion.WATCHDOG_AWAKE_CREDIT_MS
import com.odesaplay.oko.connection.ResilientConnectionSupervisor.Companion.silenceWatchdogAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The byte-silence watchdog's thresholds, tested as a pure decision rather than through a live
 *  socket: the production edge is minutes wide and the tick is hardcoded, so a wall-clock test
 *  could never reach it. */
class SilenceWatchdogTest {

    @Test
    fun `silence below the quiet edge does nothing`() {
        assertEquals(
            SilenceWatchdogAction.NONE,
            silenceWatchdogAction(DEGRADED_STALE_MS - 1, alreadyDegraded = false)
        )
    }

    @Test
    fun `silence at the quiet edge reports degraded`() {
        assertEquals(
            SilenceWatchdogAction.DEGRADE,
            silenceWatchdogAction(DEGRADED_STALE_MS, alreadyDegraded = false)
        )
    }

    @Test
    fun `a running quiet episode is not re-reported every tick`() {
        assertEquals(
            SilenceWatchdogAction.NONE,
            silenceWatchdogAction(DEGRADED_STALE_MS, alreadyDegraded = true)
        )
        assertEquals(
            SilenceWatchdogAction.NONE,
            silenceWatchdogAction(SILENCE_TIMEOUT_MS - 1, alreadyDegraded = true)
        )
    }

    @Test
    fun `silence just below the reaper is degraded, not torn down`() {
        assertEquals(
            SilenceWatchdogAction.DEGRADE,
            silenceWatchdogAction(SILENCE_TIMEOUT_MS - 1, alreadyDegraded = false)
        )
    }

    @Test
    fun `silence at the reaper tears the socket down`() {
        assertEquals(
            SilenceWatchdogAction.TEAR_DOWN,
            silenceWatchdogAction(SILENCE_TIMEOUT_MS, alreadyDegraded = false)
        )
    }

    @Test
    fun `tear-down outranks degraded once a socket blows past the reaper`() {
        assertEquals(
            SilenceWatchdogAction.TEAR_DOWN,
            silenceWatchdogAction(SILENCE_TIMEOUT_MS, alreadyDegraded = false)
        )
    }

    @Test
    fun `the reaper never fires before the quiet edge`() {
        assertTrue(SILENCE_TIMEOUT_MS > DEGRADED_STALE_MS)
    }

    /** The freeze artifact that filled the field logs: one tick after a process thaw credits a
     *  chunk of awake time the loop never observed. If that credit can reach the quiet edge, every
     *  thaw mints a fake degraded episode — so the cap must stay strictly below it. */
    @Test
    fun `a single thaw credit cannot mint a quiet episode`() {
        assertTrue(WATCHDOG_AWAKE_CREDIT_MS < DEGRADED_STALE_MS)
    }
}
