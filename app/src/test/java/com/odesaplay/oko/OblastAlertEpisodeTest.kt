package com.odesaplay.oko

import com.odesaplay.oko.engine.AlertLevel
import com.odesaplay.oko.engine.EpisodeTransition
import com.odesaplay.oko.engine.LatchedEpisode
import com.odesaplay.oko.engine.OblastAlert
import com.odesaplay.oko.engine.OfficialFrontier
import com.odesaplay.oko.engine.RestoredResolution
import com.odesaplay.oko.engine.officialAnnouncementIsOnset
import org.junit.Assert.assertEquals
import org.junit.Test

class OblastAlertEpisodeTest {

    private val latched = LatchedEpisode(AlertLevel.RED, "odeska", "s", "Odesa")
    private val active = listOf(OblastAlert("k", "n", "odeska oblast", null))

    @Test
    fun `unready empty feed holds the episode`() {
        assertEquals(EpisodeTransition.STAY, latched.resolve(false, emptyList()))
    }

    @Test
    fun `ready empty feed ends the episode`() {
        assertEquals(EpisodeTransition.ENDED, latched.resolve(true, emptyList()))
    }

    @Test
    fun `ready active feed holds the episode`() {
        assertEquals(EpisodeTransition.STAY, latched.resolve(true, active))
    }

    @Test
    fun `unready active feed holds the episode`() {
        assertEquals(EpisodeTransition.STAY, latched.resolve(false, active))
    }

    @Test
    fun `unconfirmed ended latch expires silently`() {
        assertEquals(
            RestoredResolution.EXPIRE_SILENTLY,
            latched.resolveRestored(false, EpisodeTransition.ENDED)
        )
    }

    @Test
    fun `confirmed ended latch fires the all-clear`() {
        assertEquals(
            RestoredResolution.FIRE_OFF,
            latched.resolveRestored(true, EpisodeTransition.ENDED)
        )
    }

    @Test
    fun `held latch never resolves loudly or silently`() {
        assertEquals(RestoredResolution.HOLD, latched.resolveRestored(false, EpisodeTransition.STAY))
        assertEquals(RestoredResolution.HOLD, latched.resolveRestored(true, EpisodeTransition.STAY))
    }

    // --- OfficialFrontier: one episode per region, red-then-yellow included ------------------

    private fun frontier(level: AlertLevel, token: String = "odeska", soundedRed: Boolean = false) =
        OfficialFrontier(token, level, soundedRed)

    @Test
    fun `same region at the same level is neither new nor a first red`() {
        val stored = frontier(AlertLevel.RED, soundedRed = true)
        val now = frontier(AlertLevel.RED)
        assertEquals(false, now.isNewEpisode(stored))
        assertEquals(false, now.isFirstRed(stored))
    }

    @Test
    fun `red then yellow is one episode, not a new one`() {
        val stored = frontier(AlertLevel.RED, soundedRed = true)
        val now = frontier(AlertLevel.YELLOW)
        assertEquals(false, now.isNewEpisode(stored))
        assertEquals(false, now.isFirstRed(stored))
    }

    @Test
    fun `yellow to red is the first red of the same episode`() {
        val stored = frontier(AlertLevel.YELLOW)
        val now = frontier(AlertLevel.RED)
        assertEquals(false, now.isNewEpisode(stored))
        assertEquals(true, now.isFirstRed(stored))
    }

    @Test
    fun `a red that already sounded never sounds again`() {
        val stored = frontier(AlertLevel.RED, soundedRed = true)
        val backToRed = frontier(AlertLevel.RED)
        assertEquals(false, backToRed.isFirstRed(stored))
        // The flap: a yellow tick in between must not launder the memory, because the service
        // keeps announcing the SAME stored frontier (only its live level changes).
        assertEquals(false, backToRed.isFirstRed(stored.copy(level = AlertLevel.YELLOW)))
        // Only an episode that has genuinely never gone red may sound.
        assertEquals(true, backToRed.isFirstRed(frontier(AlertLevel.YELLOW)))
    }

    @Test
    fun `a different region is a new episode, never a first red`() {
        val stored = frontier(AlertLevel.YELLOW, token = "kyivska")
        val now = frontier(AlertLevel.RED, token = "odeska")
        assertEquals(true, now.isNewEpisode(stored))
        assertEquals(false, now.isFirstRed(stored))
    }

    @Test
    fun `no stored episode is new, not a first red`() {
        val now = frontier(AlertLevel.YELLOW)
        assertEquals(true, now.isNewEpisode(null))
        assertEquals(false, now.isFirstRed(null))
    }

    @Test
    fun `remembering red sticks for the alert's life`() {
        val yellow = frontier(AlertLevel.YELLOW)
        val red = frontier(AlertLevel.RED)
        // A RED always latches the memory, whatever the previous tick was.
        assertEquals(true, red.rememberSounded(yellow).soundedRed)
        // A red with no stored episode beats nothing: the region starts red and already sounded.
        assertEquals(true, red.rememberSounded(null).soundedRed)
        // After a red, the SAME region keeps the memory even on a yellow tick.
        assertEquals(true, yellow.rememberSounded(OfficialFrontier("odeska", AlertLevel.RED, soundedRed = true)).soundedRed)
        // A first yellow carries no memory of its own.
        assertEquals(false, yellow.rememberSounded(null).soundedRed)
        // A different region starts clean rather than inheriting the old memory.
        assertEquals(false, yellow.rememberSounded(OfficialFrontier("kyivska", AlertLevel.RED, soundedRed = true)).soundedRed)
    }

    @Test
    fun `a re-stamped since is the same episode`() {
        val stored = OfficialFrontier.parse("RED|odeska|2026-10-01T18:54:00|Odesa")
        val now = OfficialFrontier.parse("YELLOW|odeska|2026-10-01T19:02:00|Odesa")
        assertEquals(false, now!!.isNewEpisode(stored))
        assertEquals(false, now.isFirstRed(stored))
    }

    @Test
    fun `a restored row is assumed already siren'd`() {
        val restoredRed = OfficialFrontier.parse("RED|odeska|s|Odesa")
        val restoredYellow = OfficialFrontier.parse("YELLOW|odeska|s|Odesa")
        assertEquals(true, restoredRed!!.soundedRed)
        // A restored yellow going red is a first red: the episode was restored, the red is new.
        assertEquals(true, frontier(AlertLevel.RED).isFirstRed(restoredYellow))
    }

    @Test
    fun `a legacy pre-level row adopts silently`() {
        val stored = OfficialFrontier.parse("odeska|2026-10-01T18:54:00|Odesa")
        val red = OfficialFrontier.parse("RED|odeska|2026-10-01T18:54:00|Odesa")
        val yellow = OfficialFrontier.parse("YELLOW|odeska|2026-10-01T18:54:00|Odesa")
        assertEquals(false, red!!.isNewEpisode(stored))
        assertEquals(false, red.isFirstRed(stored))
        assertEquals(false, yellow!!.isNewEpisode(stored))
        assertEquals(false, yellow.isFirstRed(stored))
    }

    @Test
    fun `parse of no row is no frontier`() {
        assertEquals(null, OfficialFrontier.parse(null))
        assertEquals(null, OfficialFrontier.parse(""))
        assertEquals(null, OfficialFrontier.parse("|||"))
    }

    @Test
    fun `of needs a level and a region`() {
        assertEquals(null, OfficialFrontier.of(AlertLevel.NONE, "odeska"))
        assertEquals(null, OfficialFrontier.of(AlertLevel.RED, null))
        assertEquals(null, OfficialFrontier.of(AlertLevel.RED, "  "))
        assertEquals(OfficialFrontier("odeska", AlertLevel.YELLOW), OfficialFrontier.of(AlertLevel.YELLOW, "odeska"))
    }

    @Test
    fun `only an onset or a first red sounds`() {
        val red = "RED|odeska|s|Odesa"
        val yellow = "YELLOW|odeska|s|Odesa"
        val storedRed = OfficialFrontier.parse(red)
        val storedYellow = OfficialFrontier.parse(yellow)
        assertEquals(true, officialAnnouncementIsOnset(null, OfficialFrontier.parse(yellow)))
        assertEquals(true, officialAnnouncementIsOnset(null, OfficialFrontier.parse(red)))
        // A first red sounds...
        assertEquals(true, officialAnnouncementIsOnset(storedYellow, OfficialFrontier.parse(red)))
        // ...a repeat red does not (the parsed row already remembers it)...
        assertEquals(false, officialAnnouncementIsOnset(storedRed, OfficialFrontier.parse(red)))
        // ...and carrying the memory through a yellow dip still suppresses the second red.
        val afterFirstRed = OfficialFrontier.parse(red)!!.rememberSounded(storedYellow)
        assertEquals(false, officialAnnouncementIsOnset(afterFirstRed, OfficialFrontier.parse(red)))
        // A downgrade only rewrites.
        assertEquals(false, officialAnnouncementIsOnset(storedRed, OfficialFrontier.parse(yellow)))
        assertEquals(false, officialAnnouncementIsOnset(storedRed, null))
    }
}
