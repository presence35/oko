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

    private fun frontier(level: AlertLevel, token: String = "odeska") = OfficialFrontier(token, level)

    @Test
    fun `same region at the same level is neither new nor escalation`() {
        val stored = frontier(AlertLevel.RED)
        val now = frontier(AlertLevel.RED)
        assertEquals(false, now.isNewEpisode(stored))
        assertEquals(false, now.isEscalation(stored))
    }

    @Test
    fun `red then yellow is one episode, not a new one`() {
        val stored = frontier(AlertLevel.RED)
        val now = frontier(AlertLevel.YELLOW)
        assertEquals(false, now.isNewEpisode(stored))
        assertEquals(false, now.isEscalation(stored))
    }

    @Test
    fun `yellow to red escalates inside the same episode`() {
        val stored = frontier(AlertLevel.YELLOW)
        val now = frontier(AlertLevel.RED)
        assertEquals(false, now.isNewEpisode(stored))
        assertEquals(true, now.isEscalation(stored))
    }

    @Test
    fun `a different region is a new episode, never an escalation`() {
        val stored = frontier(AlertLevel.YELLOW, "kyivska")
        val now = frontier(AlertLevel.RED, "odeska")
        assertEquals(true, now.isNewEpisode(stored))
        assertEquals(false, now.isEscalation(stored))
    }

    @Test
    fun `no stored episode is new, not an escalation`() {
        val now = frontier(AlertLevel.YELLOW)
        assertEquals(true, now.isNewEpisode(null))
        assertEquals(false, now.isEscalation(null))
    }

    @Test
    fun `a re-stamped since is the same episode`() {
        val stored = OfficialFrontier.parse("RED|odeska|2026-10-01T18:54:00|Odesa")
        val now = OfficialFrontier.parse("YELLOW|odeska|2026-10-01T19:02:00|Odesa")
        assertEquals(false, now!!.isNewEpisode(stored))
        assertEquals(false, now.isEscalation(stored))
    }

    @Test
    fun `a legacy pre-level row adopts silently`() {
        val stored = OfficialFrontier.parse("odeska|2026-10-01T18:54:00|Odesa")
        val red = OfficialFrontier.parse("RED|odeska|2026-10-01T18:54:00|Odesa")
        val yellow = OfficialFrontier.parse("YELLOW|odeska|2026-10-01T18:54:00|Odesa")
        assertEquals(false, red!!.isNewEpisode(stored))
        assertEquals(false, red.isEscalation(stored))
        assertEquals(false, yellow!!.isNewEpisode(stored))
        assertEquals(false, yellow.isEscalation(stored))
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
    fun `only an onset or an escalation sounds`() {
        val red = "RED|odeska|s|Odesa"
        val yellow = "YELLOW|odeska|s|Odesa"
        assertEquals(true, officialAnnouncementIsOnset(null, OfficialFrontier.parse(red)))
        assertEquals(true, officialAnnouncementIsOnset(OfficialFrontier.parse(yellow), OfficialFrontier.parse(red)))
        assertEquals(false, officialAnnouncementIsOnset(OfficialFrontier.parse(red), OfficialFrontier.parse(yellow)))
        assertEquals(false, officialAnnouncementIsOnset(OfficialFrontier.parse(red), OfficialFrontier.parse(red)))
        assertEquals(false, officialAnnouncementIsOnset(OfficialFrontier.parse(red), null))
    }
}
