package com.odesaplay.oko

import com.odesaplay.oko.domain.MonitorCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorCoverageTest {

    @Test
    fun `an exempt phone with no restriction is covered`() {
        assertEquals(
            MonitorCoverage.Covered,
            MonitorCoverage.of(exemptFromBatteryOptimization = true, backgroundRestricted = false)
        )
    }

    @Test
    fun `a missing exemption is reported as battery optimization`() {
        assertEquals(
            MonitorCoverage.Restricted(MonitorCoverage.Cause.BATTERY_OPTIMIZED),
            MonitorCoverage.of(exemptFromBatteryOptimization = false, backgroundRestricted = false)
        )
    }

    /**
     * The harsher restriction wins: a background-restricted app cannot keep a service even when it
     * is exempt, so reporting the weaker cause would send the user to a setting that cannot help.
     */
    @Test
    fun `background restriction outranks a missing exemption`() {
        assertEquals(
            MonitorCoverage.Restricted(MonitorCoverage.Cause.BACKGROUND_RESTRICTED),
            MonitorCoverage.of(exemptFromBatteryOptimization = false, backgroundRestricted = true)
        )
    }

    /** A refused query must never be what tells a user their monitoring is off. */
    @Test
    fun `an unreadable fact resolves to covered`() {
        assertEquals(
            MonitorCoverage.Covered,
            MonitorCoverage.of(exemptFromBatteryOptimization = null, backgroundRestricted = null)
        )
    }

    /**
     * The line the notice and the header glyph hang on: an app left on the default Optimized state
     * keeps its foreground service, so only the Restricted battery state means "nothing is
     * running". Warning on both would teach the user that the alarm is noise.
     */
    @Test
    fun `only the restricted battery state stops background work`() {
        assertTrue(
            MonitorCoverage.of(exemptFromBatteryOptimization = true, backgroundRestricted = true)
                .stopsBackgroundWork
        )
        assertFalse(
            MonitorCoverage.of(exemptFromBatteryOptimization = false, backgroundRestricted = false)
                .stopsBackgroundWork
        )
        assertFalse(MonitorCoverage.Covered.stopsBackgroundWork)
    }
}
