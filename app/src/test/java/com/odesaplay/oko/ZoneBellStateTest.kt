package com.odesaplay.oko

import com.odesaplay.oko.engine.ThreatZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The map's zone buttons used to collapse the four armed bells into two booleans with an OR, so
 * "slow armed, fast off" rendered exactly like "both armed" — and `fastYellowArmed` ships OFF by
 * default, which made the yellow button permanently ambiguous. Dimming it unless everything is
 * armed is no better: a fresh install would then show a crossed bell for a bell the user never
 * touched. Three states, one derivation, shared with the notice pill.
 */
class ZoneBellStateTest {

    private fun armed(
        slowRed: Boolean = true,
        slowYellow: Boolean = true,
        fastRed: Boolean = true,
        fastYellow: Boolean = false
    ) = ZoneArmed(slowRed, slowYellow, fastRed, fastYellow)

    @Test
    fun `both bells of a colour armed is fully armed`() {
        assertEquals(ZoneBellState.AllArmed, zoneBellState(armed(), ThreatZone.INNER, muted = false))
        val allYellow = armed(fastYellow = true)
        assertEquals(ZoneBellState.AllArmed, zoneBellState(allYellow, ThreatZone.OUTER, muted = false))
    }

    @Test
    fun `the default install shows yellow as partial, not off`() {
        // fastYellowArmed defaults to false: a yellow threat WILL still ring for slow ones.
        assertEquals(ZoneBellState.PartArmed, zoneBellState(armed(), ThreatZone.OUTER, muted = false))
        assertEquals(ZoneBellState.AllArmed, zoneBellState(armed(), ThreatZone.INNER, muted = false))
    }

    @Test
    fun `one bell of a colour armed is partial`() {
        assertEquals(
            ZoneBellState.PartArmed,
            zoneBellState(armed(slowRed = false), ThreatZone.INNER, muted = false)
        )
        assertEquals(
            ZoneBellState.PartArmed,
            zoneBellState(armed(fastRed = false), ThreatZone.INNER, muted = false)
        )
    }

    @Test
    fun `no bell of a colour armed reads off`() {
        assertEquals(
            ZoneBellState.NoneArmed,
            zoneBellState(armed(slowRed = false, fastRed = false), ThreatZone.INNER, muted = false)
        )
    }

    @Test
    fun `a live raid mute silences a fully armed colour too`() {
        // Every switch is on, yet nothing gets through — the button must show it.
        assertEquals(
            ZoneBellState.NoneArmed,
            zoneBellState(armed(fastYellow = true), ThreatZone.OUTER, muted = true)
        )
    }
}