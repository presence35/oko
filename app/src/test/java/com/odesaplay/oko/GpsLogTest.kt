package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_000_000L
private const val GRACE = 60_000L

class GpsLogTest {

    // --- issue derivation -------------------------------------------------------------
    // The point of the whole change: silence is not an access failure.

    @Test
    fun `follow-me off is never a gps issue`() {
        assertEquals(
            GpsIssue.NONE,
            resolveGpsIssue(followMe = false, hasPosition = true, ageMs = 9_000_000L, accessBlocked = true)
        )
    }

    @Test
    fun `denied access is the only blocked state`() {
        assertEquals(
            GpsIssue.ACCESS_BLOCKED,
            resolveGpsIssue(followMe = true, hasPosition = true, ageMs = 0L, accessBlocked = true)
        )
    }

    @Test
    fun `a fresh fix is verified`() {
        assertEquals(
            GpsIssue.NONE,
            resolveGpsIssue(followMe = true, hasPosition = true, ageMs = GRACE - 1, accessBlocked = false)
        )
    }

    @Test
    fun `an aged fix is unverified, not blocked`() {
        assertEquals(
            GpsIssue.NOT_VERIFIED,
            resolveGpsIssue(
                followMe = true, hasPosition = true, ageMs = GpsLog.STALE_AFTER_MS,
                accessBlocked = false
            )
        )
    }

    @Test
    fun `no position at all is unverified`() {
        assertEquals(
            GpsIssue.NOT_VERIFIED,
            resolveGpsIssue(followMe = true, hasPosition = false, ageMs = null, accessBlocked = false)
        )
    }

    // --- episode commit ---------------------------------------------------------------

    @Test
    fun `repeated identical observations write nothing`() {
        assertNull(step(prev = GpsIssue.NONE, issue = GpsIssue.NONE, now = NOW))
    }

    @Test
    fun `going unverified opens an episode without writing a row`() {
        val t = step(prev = GpsIssue.NONE, issue = GpsIssue.NOT_VERIFIED, now = NOW)!!
        assertTrue(t.entries.isEmpty())
        assertEquals(GpsEventKind.UNVERIFIED, t.nextPending!!.kind)
    }

    @Test
    fun `a sub-grace blip is dropped entirely`() {
        val opened = step(prev = GpsIssue.NONE, issue = GpsIssue.NOT_VERIFIED, now = NOW)!!
        val closed = step(
            prev = GpsIssue.NOT_VERIFIED, issue = GpsIssue.NONE, now = NOW + GRACE - 1_000,
            pending = opened.nextPending
        )!!
        assertTrue(closed.entries.isEmpty())
        assertNull(closed.nextPending)
    }

    @Test
    fun `a real outage commits the episode and its recovery`() {
        val opened = step(prev = GpsIssue.NONE, issue = GpsIssue.NOT_VERIFIED, now = NOW)!!
        val closed = step(
            prev = GpsIssue.NOT_VERIFIED, issue = GpsIssue.NONE, now = NOW + 3_600_000,
            pending = opened.nextPending, accuracyM = 12
        )!!
        assertEquals(2, closed.entries.size)
        assertEquals(GpsEventKind.UNVERIFIED, closed.entries[0].kind)
        assertEquals(3600L, closed.entries[0].durationSec)
        assertEquals(GpsEventKind.VERIFIED, closed.entries[1].kind)
        assertEquals(12, closed.entries[1].accuracyM)
        assertNull(closed.nextPending)
    }

    @Test
    fun `a recovery that drifted reports the distance`() {
        val opened = step(prev = GpsIssue.NONE, issue = GpsIssue.NOT_VERIFIED, now = NOW)!!
        val closed = step(
            prev = GpsIssue.NOT_VERIFIED, issue = GpsIssue.NONE, now = NOW + 3_600_000,
            pending = opened.nextPending, driftKm = 310.4
        )!!
        assertEquals(310.4, closed.entries[1].detailKm!!, 0.01)
    }

    @Test
    fun `blocked writes once and drops any open episode`() {
        val opened = step(prev = GpsIssue.NONE, issue = GpsIssue.NOT_VERIFIED, now = NOW)!!
        val t = step(
            prev = GpsIssue.NOT_VERIFIED, issue = GpsIssue.ACCESS_BLOCKED,
            now = NOW + 3_600_000, pending = opened.nextPending
        )!!
        assertEquals(1, t.entries.size)
        assertEquals(GpsEventKind.BLOCKED, t.entries[0].kind)
        assertNull(t.nextPending)
    }

    @Test
    fun `staying blocked writes nothing further`() {
        val first = step(prev = GpsIssue.NONE, issue = GpsIssue.ACCESS_BLOCKED, now = NOW)!!
        assertEquals(1, first.entries.size)
        assertNull(step(prev = GpsIssue.ACCESS_BLOCKED, issue = GpsIssue.ACCESS_BLOCKED, now = NOW + 1_000))
    }

    @Test
    fun `the ring buffer is capped`() {
        val many = List(GpsLog.MAX_ENTRIES + 10) { GpsLogEntry(NOW - it, GpsEventKind.BLOCKED) }
        val opened = step(prev = GpsIssue.NONE, issue = GpsIssue.NOT_VERIFIED, now = NOW, entries = many)!!
        val closed = step(
            prev = GpsIssue.NOT_VERIFIED, issue = GpsIssue.NONE, now = NOW + 3_600_000,
            pending = opened.nextPending, entries = many
        )!!
        assertEquals(GpsLog.MAX_ENTRIES, closed.entries.size)
    }

    // --- persistence ------------------------------------------------------------------

    @Test
    fun `serialization round-trips every field`() {
        val entries = listOf(
            GpsLogEntry(NOW, GpsEventKind.UNVERIFIED, durationSec = 90L),
            GpsLogEntry(NOW + 90_000, GpsEventKind.VERIFIED, accuracyM = 8, detailKm = 310.4),
            GpsLogEntry(NOW + 95_000, GpsEventKind.BLOCKED)
        )
        assertEquals(entries, parseGpsLog(serializeGpsLog(entries)))
    }

    @Test
    fun `a malformed line is skipped, not fatal`() {
        val raw = listOf(
            "garbage",
            "${NOW}|UNKNOWN_KIND||",
            "${NOW + 1}|VERIFIED|120|12.5|9"
        ).joinToString("\n")
        assertEquals(1, parseGpsLog(raw).size)
    }

    private fun step(
        prev: GpsIssue?,
        issue: GpsIssue,
        now: Long,
        pending: GpsLogEntry? = null,
        entries: List<GpsLogEntry> = emptyList(),
        accuracyM: Int? = null,
        driftKm: Double? = null
    ) = commitGpsLogState(
        prevIssue = prev,
        issue = issue,
        now = now,
        pending = pending,
        entries = entries,
        maxEntries = GpsLog.MAX_ENTRIES,
        graceMs = GRACE,
        accuracyM = accuracyM,
        driftKm = driftKm
    )
}
