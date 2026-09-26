package com.odesaplay.oko.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatedNetworkTrackerTest {

    @Test
    fun `losing one of several networks does not drain`() {
        val t = ValidatedNetworkTracker<String>()
        assertTrue(t.add("wifi"))
        assertFalse(t.add("lte"))
        assertFalse(t.remove("wifi")) // lte still up
        assertFalse(t.isEmpty())
    }

    @Test
    fun `losing the last network drains`() {
        val t = ValidatedNetworkTracker<Int>()
        t.add(1)
        t.add(2)
        assertFalse(t.remove(1))
        assertTrue(t.remove(2))
        assertTrue(t.isEmpty())
    }

    @Test
    fun `add reports restore only on empty to non-empty`() {
        val t = ValidatedNetworkTracker<String>()
        assertTrue(t.add("a"))
        assertFalse(t.add("b"))
        assertFalse(t.add("a")) // duplicate, already non-empty
    }

    @Test
    fun `clear drains the set`() {
        val t = ValidatedNetworkTracker<String>()
        t.add("a")
        t.add("b")
        t.clear()
        assertTrue(t.isEmpty())
    }
}
