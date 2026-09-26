package com.odesaplay.oko

import com.odesaplay.oko.engine.AlertLevel
import com.odesaplay.oko.engine.EpisodeTransition
import com.odesaplay.oko.engine.LatchedEpisode
import com.odesaplay.oko.engine.OblastAlert
import com.odesaplay.oko.engine.RestoredResolution
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
}
