package com.odesaplay.oko.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SpeedCacheHeadingTest {

    private lateinit var cache: SpeedCache

    @Before
    fun setUp() {
        cache = SpeedCache()
    }

    @Test
    fun `unknown track has no heading`() {
        assertNull(cache.measuredHeading("never-recorded"))
    }

    @Test
    fun `single fix has no heading`() {
        cache.record("t1", 0L, 50.45, 30.52)
        assertNull(cache.measuredHeading("t1"))
    }

    @Test
    fun `northbound pair faces north`() {
        cache.record("t1", 0L, 50.45, 30.52)
        cache.record("t1", 60_000L, 50.459, 30.52)
        assertEquals(0.0, cache.measuredHeading("t1")!!, 2.0)
    }

    @Test
    fun `eastbound pair faces east`() {
        cache.record("t1", 0L, 50.45, 30.52)
        cache.record("t1", 60_000L, 50.45, 30.535)
        assertEquals(90.0, cache.measuredHeading("t1")!!, 3.0)
    }

    @Test
    fun `sub-two-second span has no heading`() {
        cache.record("t1", 0L, 50.45, 30.52)
        cache.record("t1", 1_000L, 50.459, 30.52)
        assertNull(cache.measuredHeading("t1"))
    }

    @Test
    fun `over-ten-minute span has no heading`() {
        cache.record("t1", 0L, 50.45, 30.52)
        cache.record("t1", 700_000L, 50.459, 30.52)
        assertNull(cache.measuredHeading("t1"))
    }

    @Test
    fun `stationary pair has no heading`() {
        cache.record("t1", 0L, 50.45, 30.52)
        cache.record("t1", 60_000L, 50.45, 30.52)
        assertNull(cache.measuredHeading("t1"))
    }

    @Test
    fun `tracks are independent`() {
        cache.record("t1", 0L, 50.45, 30.52)
        cache.record("t1", 60_000L, 50.459, 30.52)
        cache.record("t2", 0L, 50.45, 30.52)
        assertEquals(0.0, cache.measuredHeading("t1")!!, 2.0)
        assertNull(cache.measuredHeading("t2"))
    }
}
