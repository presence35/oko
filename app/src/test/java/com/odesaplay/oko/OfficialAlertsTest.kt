package com.odesaplay.oko

import com.odesaplay.oko.engine.AlertLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialAlertsTest {

    @Test
    fun `red keys on the red toggle only`() {
        assertTrue(isOfficialLevelEnabled(AlertLevel.RED, redEnabled = true, yellowEnabled = false))
        assertFalse(isOfficialLevelEnabled(AlertLevel.RED, redEnabled = false, yellowEnabled = true))
    }

    @Test
    fun `yellow keys on the yellow toggle only`() {
        assertTrue(isOfficialLevelEnabled(AlertLevel.YELLOW, redEnabled = false, yellowEnabled = true))
        assertFalse(isOfficialLevelEnabled(AlertLevel.YELLOW, redEnabled = true, yellowEnabled = false))
    }

    @Test
    fun `no level is never announced`() {
        assertFalse(isOfficialLevelEnabled(AlertLevel.NONE, redEnabled = true, yellowEnabled = true))
    }

    /** The regression: the red||yellow master must never stand in for a level's own toggle —
     *  a muted red used to be announced and cleared because yellow was on. */
    @Test
    fun `a muted level stays muted while the other one is on`() {
        val redOff = false
        val yellowOn = true
        val master = redOff || yellowOn
        assertTrue("master is on, which is what the all-clear used to gate on", master)
        assertFalse(isOfficialLevelEnabled(AlertLevel.RED, redOff, yellowOn))
        assertTrue(isOfficialLevelEnabled(AlertLevel.YELLOW, redOff, yellowOn))
    }
}