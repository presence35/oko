package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tapped-log-row pin's lifetime is one pure function, so its contract is pinnable even though
 * the map that draws it is not: it must blink (two clearly different levels, not a smooth throb
 * that could read as a live track), stay lit for the whole flash, then fade to exactly nothing —
 * 0f being the only thing that ends the mark.
 */
class LogPinTest {

    private val armedAt = 1_000_000L

    private fun alphaAt(offsetMs: Long) = logPinAlpha(armedAt, armedAt + offsetMs)

    @Test
    fun `the blink has two visibly different levels`() {
        val on = alphaAt(100)
        val off = alphaAt(500)
        assertTrue("both phases must be visible at all", on > 0.5f && off > 0f)
        assertTrue("a throb would read as a live marker", on - off > 0.4f)
    }

    @Test
    fun `the mark is still lit at the end of the flash`() {
        assertTrue(alphaAt(4_100) > 0f)
    }

    @Test
    fun `it fades out over the fade window instead of cutting`() {
        val justAfter = alphaAt(4_300)
        val midway = alphaAt(4_800)
        assertTrue("the fade must start full", justAfter > midway)
        assertTrue("and must be part-way down, not gone", midway > 0f && midway < justAfter)
    }

    @Test
    fun `the mark expires exactly once, at the end of the fade`() {
        assertEquals(0f, alphaAt(4_200 + 1_200), 0.0001f)
        assertEquals(0f, alphaAt(60_000), 0.0001f)
    }

    @Test
    fun `a clock that steps backwards cannot strand the mark`() {
        // Wall clock can jump back (NTP); the mark must stay at its first frame, never go NaN or
        // latch at a level that never reaches 0.
        assertEquals(logPinAlpha(armedAt, armedAt), logPinAlpha(armedAt, armedAt - 5_000), 0.0001f)
    }
}
