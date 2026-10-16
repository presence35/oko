package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.odesaplay.oko.engine.AlertLevel

class LogStoryTest {

    private val minute = 60_000L

    /** Well past every event below, so a session only reads as ongoing when a test asks for it. */
    private val NOW = 200 * minute

    private fun sessions(entries: List<DebugLogEntry>, now: Long = NOW) =
        buildSessions(entries, now)

    private fun entry(
        atMin: Long,
        kind: DebugLogKind = DebugLogKind.ZONE_ENTER,
        notified: Boolean = true,
        reason: DebugLogReason = DebugLogReason.FIRED,
        scope: String? = "odeska",
        level: AlertLevel? = null,
        locality: String? = "Одеса",
        aboutMe: Boolean = true
    ) = DebugLogEntry(
        atMillis = atMin * minute,
        kind = kind, night = false, sirenOverride = false,
        notified = notified, reason = reason, threatId = "t$atMin",
        threatType = ThreatType.SHAHED, tier = null, distanceKm = 5.0,
        locality = locality, level = level, scopeOblastId = scope, aboutMe = aboutMe
    )

    @Test
    fun `a drone just before the alarm is adopted into the session`() {
        val sessions = sessions(
            listOf(
                entry(0, notified = true),
                entry(1, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED)
            )
        )
        assertEquals(1, sessions.size)
        assertEquals(2, sessions.single().size)
        assertEquals(0 * minute, sessions.single().startMs)
    }

    @Test
    fun `a drone long before the alarm stays its own session`() {
        val sessions = sessions(
            listOf(
                entry(0, notified = true),
                entry(10, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED)
            )
        )
        assertEquals(2, sessions.size)
    }

    @Test
    fun `two alarms are never merged by the adoption window`() {
        val sessions = sessions(
            listOf(
                entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED),
                entry(1, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.YELLOW)
            )
        )
        assertEquals(2, sessions.size)
    }

    @Test
    fun `a session is about me when any event was evaluated against me`() {
        val mine = sessions(
            listOf(entry(0, aboutMe = false), entry(1, aboutMe = true))
        ).single()
        assertTrue(mine.aboutMe())
        val theirs = sessions(listOf(entry(0, aboutMe = false))).single()
        assertEquals(false, theirs.aboutMe())
    }

    @Test
    fun `about-me rule keeps personal suppressions and drops foreign ones`() {
        assertTrue(aboutUser(DebugLogReason.BELL_MUTED, notified = false, inFocusOblast = false))
        assertTrue(aboutUser(DebugLogReason.TYPE_OFF, notified = false, inFocusOblast = false))
        assertTrue(aboutUser(DebugLogReason.FIRED, notified = true, inFocusOblast = false))
        assertTrue(aboutUser(DebugLogReason.OUTSIDE_ZONES, notified = false, inFocusOblast = true))
        assertEquals(false, aboutUser(DebugLogReason.OUTSIDE_ZONES, notified = false, inFocusOblast = false))
        assertEquals(false, aboutUser(DebugLogReason.STALE, notified = false, inFocusOblast = false))
        assertEquals(false, aboutUser(DebugLogReason.ADVISORY, notified = false, inFocusOblast = false))
    }

    @Test
    fun `an alarm and its quiet gap form one session`() {
        val sessions = sessions(
            listOf(
                entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED),
                entry(5),
                entry(19)
            )
        )
        assertEquals(1, sessions.size)
        assertEquals(3, sessions.single().size)
        assertEquals(AlertLevel.RED, sessions.single().official)
    }

    @Test
    fun `a gap wider than the window starts a new session`() {
        val sessions = sessions(listOf(entry(0), entry(40)))
        assertEquals(2, sessions.size)
    }

    @Test
    fun `a second official onset always opens its own session`() {
        val sessions = sessions(
            listOf(
                entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED),
                entry(2, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED)
            )
        )
        assertEquals(2, sessions.size)
    }

    @Test
    fun `an all-clear stays inside the alarm session`() {
        val sessions = sessions(
            listOf(
                entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED),
                entry(15, kind = DebugLogKind.OFFICIAL_OFF, level = AlertLevel.RED)
            )
        )
        assertEquals(1, sessions.size)
        assertEquals(2, sessions.single().size)
    }

    @Test
    fun `a session is told when any event rang`() {
        val told = sessions(
            listOf(
                entry(0, notified = false, reason = DebugLogReason.COALESCED),
                entry(1, notified = true)
            )
        ).single()
        assertTrue(told.told)
        assertNull(told.silenceReason(Strings.get(AppLanguage.EN)))
    }

    @Test
    fun `a silent session names why in plain words`() {
        val silent = sessions(
            listOf(
                entry(0, notified = false, reason = DebugLogReason.TYPE_OFF),
                entry(1, notified = false, reason = DebugLogReason.TYPE_OFF)
            )
        ).single()
        assertFalse(silent.told)
        assertEquals(
            Strings.get(AppLanguage.EN).debugReasonTypeOff,
            silent.silenceReason(Strings.get(AppLanguage.EN))
        )
    }

    @Test
    fun `an empty log produces no sessions`() {
        assertTrue(sessions(emptyList()).isEmpty())
    }

    @Test
    fun `sessions run newest first`() {
        val sessions = sessions(listOf(entry(0), entry(40), entry(80)))
        assertEquals(80 * minute, sessions.first().startMs)
        assertEquals(0 * minute, sessions.last().startMs)
    }

    @Test
    fun `session oblast is the majority place`() {
        val session = sessions(
            listOf(entry(0, scope = "odeska"), entry(1, scope = "odeska"), entry(2, scope = "kyivska"))
        ).single()
        assertEquals("odeska", session.oblastId())
    }

    @Test
    fun `duration spans first to last event`() {
        val session = sessions(listOf(entry(0), entry(7))).single()
        assertEquals(7 * minute, session.durationMs)
    }

    // --- The live raid and the action trail ---------------------------------------------

    @Test
    fun `a raid with no all-clear is still going, and its span runs to now`() {
        // The report this pins: an ongoing raid showed a finished "09:21 - 10:20, 58 min"
        // because the session ended at its last logged row.
        val session = sessions(
            listOf(entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED), entry(3)),
            now = 9 * minute
        ).single()
        assertTrue(session.ongoing)
        assertEquals(9 * minute, session.endMs)
        assertEquals(9 * minute, session.durationMs)
    }

    @Test
    fun `a closed raid is never ongoing`() {
        val session = sessions(
            listOf(
                entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED),
                entry(3, kind = DebugLogKind.OFFICIAL_OFF, level = AlertLevel.RED)
            ),
            now = 4 * minute
        ).single()
        assertFalse(session.ongoing)
        assertEquals(3 * minute, session.endMs)
    }

    @Test
    fun `a raid that went quiet longer than the window is finished, not ongoing`() {
        val session = sessions(
            listOf(entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED)),
            now = 45 * minute
        ).single()
        assertFalse(session.ongoing)
    }

    @Test
    fun `only the newest session can be ongoing`() {
        val list = sessions(
            listOf(entry(0), entry(1), entry(50)),
            now = 52 * minute
        )
        assertEquals(2, list.size)
        assertTrue(list.first().ongoing)
        assertFalse(list.last().ongoing)
    }

    @Test
    fun `a user action is not an event - it never becomes a session`() {
        val session = sessions(
            listOf(
                entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED),
                entry(1, kind = DebugLogKind.ACTION, notified = false,
                    reason = DebugLogReason.SETTING_CHANGED, locality = "Zone bells"),
                entry(2)
            ),
            now = 60 * minute
        ).single()
        assertEquals(2, session.size)
        assertTrue(session.entries.none { it.kind == DebugLogKind.ACTION })
    }

    @Test
    fun `a downgrade is one session carrying both levels`() {
        val session = sessions(
            listOf(
                entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED),
                entry(60, kind = DebugLogKind.OFFICIAL_LEVEL, notified = false,
                    reason = DebugLogReason.LEVEL_CHANGED, level = AlertLevel.YELLOW),
                entry(180, kind = DebugLogKind.OFFICIAL_OFF, level = AlertLevel.RED)
            )
        ).single()
        assertEquals(3, session.size)
        assertEquals(AlertLevel.RED, session.official)
        assertEquals(
            listOf(AlertLevel.RED, AlertLevel.YELLOW, AlertLevel.RED),
            session.levelChanges.map { it.second }
        )
    }

    @Test
    fun `a level change does not split the session it belongs to`() {
        // OFFICIAL_ON cuts a session; an un-cleared raid must also hold its own: an hour of
        // yellow is one card, not a second raid.
        val list = sessions(
            listOf(
                entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED),
                entry(30, kind = DebugLogKind.OFFICIAL_LEVEL, notified = false,
                    reason = DebugLogReason.LEVEL_CHANGED, level = AlertLevel.YELLOW)
            )
        )
        assertEquals(1, list.size)
    }

    @Test
    fun `an un-cleared raid absorbs a quiet stretch past the gap window`() {
        val list = sessions(
            listOf(
                entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED),
                entry(45)
            )
        )
        assertEquals(1, list.size)
        assertEquals(2, list.single().size)
    }

    @Test
    fun `a cleared raid still splits on the gap`() {
        val list = sessions(
            listOf(
                entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED),
                entry(2, kind = DebugLogKind.OFFICIAL_OFF, level = AlertLevel.RED),
                entry(45)
            )
        )
        assertEquals(2, list.size)
    }
}
