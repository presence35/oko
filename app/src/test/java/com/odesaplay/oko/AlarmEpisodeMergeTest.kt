package com.odesaplay.oko

import com.odesaplay.oko.AlarmEpisodeTally
import com.odesaplay.oko.AlarmEpisodeTally.Companion.MERGED_MEMORY_CAP
import com.odesaplay.oko.AlarmEpisodeTally.Companion.MERGE_WINDOW_MS
import com.odesaplay.oko.AlarmEpisodeTally.EpisodeSnapshot
import com.odesaplay.oko.AlarmEpisodeTally.PostedRow
import com.odesaplay.oko.AlarmEpisodeTally.ResolvedRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge rules behind the shade row, without a notification manager: a quiet five-minute blip
 * must add itself to the night's row instead of evicting it, and anything that is not the same
 * recent night starts fresh.
 */
class AlarmEpisodeMergeTest {

    private fun record(type: ThreatType = ThreatType.SHAHED) =
        ResolvedRecord(48.0, 31.0, type, null)

    private fun snapshot(count: Int, type: ThreatType = ThreatType.SHAHED) =
        EpisodeSnapshot(count, if (count == 0) emptyMap() else mapOf(type to count), List(count) { record(type) })

    private fun row(
        city: String = "Дніпро",
        alarms: Int = 1,
        totalMin: Int = 27,
        measured: Boolean = true,
        count: Int = 3,
        postedAt: Long = 1_000_000L
    ) = PostedRow(
        city = city,
        alarmCount = alarms,
        totalMin = if (measured) totalMin else 0,
        allMeasured = measured,
        count = count,
        typeCounts = mapOf(ThreatType.SHAHED to count),
        memory = List(count) { record() },
        postedAtMs = postedAt
    )

    @Test
    fun `two measured alarms for one city sum everything`() {
        val merged = AlarmEpisodeTally.mergeInto(row(), "Дніпро", snapshot(2), 5, true, 1_000_000L + 60_000L)

        assertEquals(2, merged.alarmCount)
        assertEquals(32, merged.totalMin)
        assertTrue(merged.allMeasured)
        assertEquals(5, merged.count)
        assertEquals(5, merged.typeCounts[ThreatType.SHAHED])
        assertEquals(5, merged.memory.size)
    }

    /**
     * One unmeasured alarm poisons the total: the minutes stop being a sum of measurements and
     * become a sum involving a guess, so the row keeps the count and drops the minutes instead.
     */
    @Test
    fun `one unmeasured alarm zeroes the total but keeps the count`() {
        val merged = AlarmEpisodeTally.mergeInto(row(), "Дніпро", snapshot(2), 0, false, 1_000_000L + 60_000L)

        assertEquals(2, merged.alarmCount)
        assertEquals(0, merged.totalMin)
        assertEquals(5, merged.count)
    }

    @Test
    fun `a different city starts its own row`() {
        val merged = AlarmEpisodeTally.mergeInto(row(), "Київ", snapshot(2), 5, true, 1_000_000L + 60_000L)

        assertEquals(1, merged.alarmCount)
        assertEquals(5, merged.totalMin)
        assertEquals("Київ", merged.city)
        assertEquals(2, merged.count)
    }

    @Test
    fun `a row past the window starts fresh`() {
        val merged = AlarmEpisodeTally.mergeInto(row(), "Дніпро", snapshot(2), 5, true, 1_000_000L + MERGE_WINDOW_MS)

        assertEquals(1, merged.alarmCount)
        assertEquals(5, merged.totalMin)
        assertEquals(2, merged.count)
    }

    @Test
    fun `no previous row starts fresh`() {
        val merged = AlarmEpisodeTally.mergeInto(null, "Дніпро", snapshot(2), 5, true, 1_000_000L)

        assertEquals(1, merged.alarmCount)
        assertEquals(5, merged.totalMin)
    }

    @Test
    fun `the replay buffer is capped`() {
        val big = row(count = MERGED_MEMORY_CAP, postedAt = 1_000_000L)
        val merged = AlarmEpisodeTally.mergeInto(big, "Дніпро", snapshot(10), 5, true, 1_000_000L + 60_000L)

        assertEquals(MERGED_MEMORY_CAP, merged.memory.size)
    }
}
