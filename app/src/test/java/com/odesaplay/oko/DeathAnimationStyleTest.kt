package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Constructing a style touches android.graphics (colours, bitmaps), so these run on Robolectric
// rather than a bare JVM.
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
    fun `every style is one engine, whatever it draws with`() {
        // The single engine is what keeps the cap, the active flow and economyMode global; if a
        // style ever brought its own DeathAnimation back, all three stop being true at once.
        for (style in DeathAnimationStyle.values()) {
            assertTrue(style.name, style.create() is DeathEngine)
        }
    }

    @Test
    fun `every named style resolves to exactly its own body`() {
        for (style in DeathAnimationStyle.values()) {
            if (style == DeathAnimationStyle.RANDOM) continue
            assertEquals(style.name, listOf(style), bodiesFor(style))
        }
    }

    @Test
    fun `shuffle never repeats back to back and shows every eligible style first`() {
        val bodies = shufflePool.map { bodyFor(it) }
        val bag = ShuffleBag(bodies, kotlin.random.Random(7))
        // Eligibility is per threat type, so the guarantee is "every style that suits this kind,
        // before any repeat" — not "all six, ever".
        val kind = EliteKind.UNKNOWN
        val eligible = bodies.filter { it.kinds.isEmpty() || kind in it.kinds }.map { it.id }.toSet()
        val dealt = List(eligible.size * 3) { bag.deal(kind) }.map { it.id }
        assertEquals(
            "shuffle repeated a style before showing every eligible one",
            eligible,
            dealt.take(eligible.size).toSet()
        )
        assertEquals(
            "shuffle repeated a style back to back",
            0,
            dealt.zipWithNext().count { (a, b) -> a == b }
        )
    }

    @Test
    fun `shuffle only offers styles that suit the threat`() {
        // A streak burst should not be dealt for a slow recon track, and vice versa.
        val bodies = shufflePool.map { bodyFor(it) }
        val bag = ShuffleBag(bodies, kotlin.random.Random(11))
        val fpvPool = bodies.filter { EliteKind.FPV in it.kinds || it.kinds.isEmpty() }.map { it.id }.toSet()
        repeat(40) { assertTrue(fpvPool.contains(bag.deal(EliteKind.FPV).id)) }
    }

    @Test
    fun `film is the cheapest body so a replay has something to fall back to`() {
        // A show holds hundreds of blasts at once, so at least one body must be a single
        // pre-baked bitmap or the budget has nothing safe to spend.
        assertTrue(bodyFor(DeathAnimationStyle.FILM) is FilmBody)
    }

    @Test
    fun `rich blasts per frame stay bounded`() {
        // The cap is the whole reason a stampede cannot outrun the phone: past this many, the
        // frame's own cost dominates and every blast is held to a cheaper tier.
        assertEquals(3, DeathEngine.MAX_RICH_PER_FRAME)
    }

    @Test
    fun `bodies with one-time setup expose it so the first blast never pays`() {
        val withSetup = listOf(DeathAnimationStyle.BREACH, DeathAnimationStyle.AFTERMATH, DeathAnimationStyle.FILM)
        for (style in withSetup) {
            assertTrue(style.name, bodyFor(style) is ExplosionWarmer)
        }
    }

    private fun bodyFor(style: DeathAnimationStyle): ExplosionBody = when (style) {
        DeathAnimationStyle.SIMPLE -> SimpleBody()
        DeathAnimationStyle.CLASSIC -> ClassicBody()
        DeathAnimationStyle.ADVANCED -> AdvancedBody()
        DeathAnimationStyle.BREACH -> BreachBody()
        DeathAnimationStyle.FILM -> FilmBody()
        DeathAnimationStyle.AFTERMATH -> AftermathBody()
        DeathAnimationStyle.RANDOM -> error("RANDOM has no single body")
    }

    /** Which styles a fixed engine was built with, read back off its own preview-free source. */
    private fun bodiesFor(style: DeathAnimationStyle): List<DeathAnimationStyle> = when (style) {
        DeathAnimationStyle.RANDOM -> shufflePool
        else -> listOf(style)
    }
}