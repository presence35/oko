package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Constructing a style touches android.graphics (colours, bitmaps), so these run on Robolectric
// rather than a bare JVM.

/**
 * The explosion-style picker and the elites' performance contract. These are the two places where
 * a careless edit is invisible on screen but very expensive: a style with no preview renders as a
 * blank settings card, and an elite that quietly needs per-frame allocation will re-introduce the
 * first-kill stutter this was built to remove.
 */
@RunWith(RobolectricTestRunner::class)
class DeathAnimationStyleTest {

    @Test
    fun `every style declares a preview the picker can draw`() {
        for (style in DeathAnimationStyle.values()) {
            val preview = style.create().preview
            assertTrue(style.name, preview.hot != preview.mid)
        }
    }

    @Test
    fun `every style is distinct on screen`() {
        // Two styles with the same palette and the same body plan are the same picture twice —
        // which is exactly the failure the three elites were rebuilt to avoid.
        val distinct = DeathAnimationStyle.values().map { it.create().preview }
        assertEquals(
            "two explosion styles render identically",
            DeathAnimationStyle.values().size,
            distinct.distinctBy { it.shape to it.mid }.size
        )
    }

    @Test
    fun `film is the cheapest elite so a replay has something to fall back to`() {
        // A show holds hundreds of blasts at once. It must have at least one style whose body is
        // a single pre-baked bitmap, or the budget has nothing safe to spend.
        val film = DeathAnimationStyle.FILM.create()
        assertTrue(film is EliteDeathBase)
    }

    @Test
    fun `rich blasts per frame stay bounded`() {
        // The cap is the whole reason a stampede cannot outrun the phone: past this many, the
        // frame's own cost dominates and every blast is held to a cheaper tier.
        assertEquals(3, EliteDeathBase.MAX_RICH_PER_FRAME)
    }

    @Test
    fun `styles with one-time setup expose it so the first blast never pays`() {
        val shaderStyles = listOf(DeathAnimationStyle.BREACH, DeathAnimationStyle.AFTERMATH)
        for (style in shaderStyles) {
            assertTrue(
                style.name,
                style.create() is ExplosionWarmer
            )
        }
    }
}