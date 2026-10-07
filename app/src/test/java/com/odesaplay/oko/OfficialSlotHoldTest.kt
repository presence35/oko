package com.odesaplay.oko

import com.odesaplay.oko.engine.ThreatZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared alert slot: a fresh official onset holds it against an outer-zone rewrite, so
 * the row the siren summoned the user for is still there when they look. The predicate is
 * pure; the wiring (stamp on post, reset on zone/cancel) is covered by AlertEpisodeClearTest.
 */
class OfficialSlotHoldTest {

    private val onsetAt = 1_000_000L

    @Test
    fun `outer rewrite during a live hold waits`() {
        assertTrue(AlertService.officialSlotHeld(ThreatZone.OUTER, true, onsetAt, onsetAt + 9_000))
    }

    @Test
    fun `inner escalation never waits`() {
        assertFalse(AlertService.officialSlotHeld(ThreatZone.INNER, true, onsetAt, onsetAt + 9_000))
    }

    @Test
    fun `lapsed hold releases`() {
        assertFalse(AlertService.officialSlotHeld(ThreatZone.OUTER, true, onsetAt, onsetAt + 61_000))
    }

    @Test
    fun `dead episode releases at once`() {
        assertFalse(AlertService.officialSlotHeld(ThreatZone.OUTER, false, onsetAt, onsetAt + 9_000))
    }

    @Test
    fun `official primaries never wait`() {
        assertFalse(AlertService.officialSlotHeld(null, true, onsetAt, onsetAt + 9_000))
    }

    @Test
    fun `no onset ever shown never holds`() {
        assertFalse(AlertService.officialSlotHeld(ThreatZone.OUTER, true, 0L, 1_000_000_000L))
    }
}
