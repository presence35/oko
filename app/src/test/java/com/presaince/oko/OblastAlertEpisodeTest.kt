package com.presaince.oko

import com.presaince.oko.engine.AlertLevel
import com.presaince.oko.engine.EpisodeTransition
import com.presaince.oko.engine.LatchedEpisode
import com.presaince.oko.engine.OblastAlert
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
}
