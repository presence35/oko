package ua.ukrainedrones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlourishTest {

    @Test
    fun `clusterFlourish groups near records and keeps order`() {
        // ~1.1 km apart → same group at 10 km threshold.
        val a = FlourishRecord(46.48, 30.73, ThreatType.SHAHED)
        val b = FlourishRecord(46.49, 30.73, ThreatType.SHAHED)
        // ~110 km away → separate group.
        val c = FlourishRecord(47.5, 30.73, ThreatType.BALLISTIC)
        val groups = clusterFlourish(listOf(a, b, c), 10_000.0)
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].size)
        assertEquals(1, groups[1].size)
        assertEquals(a, groups[0][0])
        assertEquals(b, groups[0][1])
        assertEquals(c, groups[1][0])
    }

    @Test
    fun `clusterFlourish merges a record into the nearest group centroid`() {
        // Two clusters, then a record between them lands in whichever is closest.
        val a = FlourishRecord(46.40, 30.73, ThreatType.SHAHED)
        val b = FlourishRecord(46.45, 30.73, ThreatType.SHAHED)
        val far = FlourishRecord(47.80, 30.73, ThreatType.KAB)
        val mid = FlourishRecord(47.10, 30.73, ThreatType.FPV_LOITERING)
        // mid is ~75 km from the first group's centroid (46.425) and ~77 km from the far
        // point — inside the 80 km threshold, so it merges into the first group.
        val groups = clusterFlourish(listOf(a, b, far, mid), 80_000.0)
        assertEquals(2, groups.size)
        assertTrue(groups.any { it.contains(mid) })
        assertEquals(3, groups[0].size)
        assertEquals(1, groups[1].size)
    }

    @Test
    fun `clusterFlourish yields one group per record at a zero threshold`() {
        val r1 = FlourishRecord(46.48, 30.73, ThreatType.SHAHED)
        val r2 = FlourishRecord(46.50, 30.73, ThreatType.SHAHED)
        val groups = clusterFlourish(listOf(r1, r2), 0.0)
        assertEquals(2, groups.size)
        assertEquals(1, groups[0].size)
        assertEquals(1, groups[1].size)
    }

    @Test
    fun `selection drops only when the threat is gone and the animation is on`() {
        assertTrue(FlourishPolicy.dropSelection(selectedGone = true, animOn = true))
        assertFalse(FlourishPolicy.dropSelection(selectedGone = true, animOn = false))
        assertFalse(FlourishPolicy.dropSelection(selectedGone = false, animOn = true))
    }

    @Test
    fun `neutralized card shows only when map is visible and shelters are down`() {
        assertTrue(
            FlourishPolicy.showNeutralizedCard(
                selectedGone = true, animOn = true, mapVisible = true, shelterModeActive = false
            )
        )
        assertFalse(
            FlourishPolicy.showNeutralizedCard(
                selectedGone = true, animOn = false, mapVisible = true, shelterModeActive = false
            )
        )
        assertFalse(
            FlourishPolicy.showNeutralizedCard(
                selectedGone = true, animOn = true, mapVisible = false, shelterModeActive = false
            )
        )
        assertFalse(
            FlourishPolicy.showNeutralizedCard(
                selectedGone = true, animOn = true, mapVisible = true, shelterModeActive = true
            )
        )
    }
}