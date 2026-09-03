package ua.ukrainedrones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLogTest {

    private val graceMs = 30_000L
    private val maxEntries = 10

    private fun episode(at: Long, status: ConnStatus = ConnStatus.OFFLINE) =
        ConnLogEntry(at, status, null)

    @Test
    fun `first sighting never fabricates an episode`() {
        assertNull(commitLogState(null, ConnStatus.ONLINE, 1_000, null, emptyList(), maxEntries, graceMs))
        val t = commitLogState(null, ConnStatus.OFFLINE, 1_000, null, emptyList(), maxEntries, graceMs)!!
        assertTrue(t.entries.isEmpty())
        assertEquals(ConnStatus.OFFLINE, t.nextPending?.status)
    }

    @Test
    fun `unchanged status is a no-op`() {
        assertNull(
            commitLogState(ConnStatus.OFFLINE, ConnStatus.OFFLINE, 1_000, null, emptyList(), maxEntries, graceMs)
        )
    }

    @Test
    fun `drop opens a pending episode without committing`() {
        val t = commitLogState(ConnStatus.ONLINE, ConnStatus.OFFLINE, 1_000, null, emptyList(), maxEntries, graceMs)!!
        assertTrue(t.entries.isEmpty())
        assertTrue(!t.persistLog)
        assertEquals(ConnStatus.OFFLINE, t.nextPending?.status)
        assertEquals(1_000, t.persistPendingSince)
        assertEquals("OFFLINE", t.persistPendingStatus)
    }

    @Test
    fun `episode that outlasts the grace window is committed`() {
        val start = 1_000L
        val t = commitLogState(
            ConnStatus.OFFLINE, ConnStatus.ONLINE, start + graceMs + 5_000,
            episode(start), emptyList(), maxEntries, graceMs
        )!!
        assertEquals(2, t.entries.size)
        assertEquals(ConnStatus.OFFLINE, t.entries[0].status)
        assertEquals(35L, t.entries[0].durationSec)
        assertEquals(ConnStatus.ONLINE, t.entries[1].status)
        assertNull(t.nextPending)
        assertEquals(0L, t.persistPendingSince)
        assertTrue(t.persistLog)
    }

    @Test
    fun `blip under the grace window is ignored`() {
        val t = commitLogState(
            ConnStatus.OFFLINE, ConnStatus.ONLINE, 5_000,
            episode(0), emptyList(), maxEntries, graceMs
        )!!
        assertTrue(t.entries.isEmpty())
        assertNull(t.nextPending)
        assertTrue(!t.persistLog)
    }

    @Test
    fun `recovery without a prior episode adds no rows`() {
        // Can't happen in practice (recovery implies an open episode), but stays safe.
        val t = commitLogState(
            ConnStatus.OFFLINE, ConnStatus.ONLINE, 5_000,
            null, emptyList(), maxEntries, graceMs
        )!!
        assertTrue(t.entries.isEmpty())
        assertNull(t.nextPending)
    }

    @Test
    fun `ring buffer caps at max entries`() {
        var entries = emptyList<ConnLogEntry>()
        // 20 committed offline episodes, each outlasting the grace window.
        var status = ConnStatus.ONLINE
        var pending: ConnLogEntry? = null
        var now = 0L
        repeat(20) {
            val drop = commitLogState(status, ConnStatus.OFFLINE, now, pending, entries, 10, graceMs)!!
            entries = drop.entries
            pending = drop.nextPending
            status = ConnStatus.OFFLINE
            now += graceMs + 1
            val rec = commitLogState(status, ConnStatus.ONLINE, now, pending, entries, 10, graceMs)!!
            entries = rec.entries
            pending = rec.nextPending
            status = ConnStatus.ONLINE
            now += 1
        }
        assertEquals(10, entries.size)
        assertEquals(ConnStatus.ONLINE, entries.last().status)
    }
}