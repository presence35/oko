package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.odesaplay.oko.engine.AlertLevel

class LogStoryTest {

    private val minute = 60_000L

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
        kind = kind, night = false, sirenOverride = false, vibrationLevel = 3,
        notified = notified, reason = reason, threatId = "t$atMin",
        threatType = ThreatType.SHAHED, tier = null, distanceKm = 5.0,
        locality = locality, level = level, scopeOblastId = scope, aboutMe = aboutMe
    )

    @Test
    fun `a drone just before the alarm is adopted into the session`() {
        val sessions = buildSessions(
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
        val sessions = buildSessions(
            listOf(
                entry(0, notified = true),
                entry(10, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED)
            )
        )
        assertEquals(2, sessions.size)
    }

    @Test
    fun `two alarms are never merged by the adoption window`() {
        val sessions = buildSessions(
            listOf(
                entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED),
                entry(1, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.YELLOW)
            )
        )
        assertEquals(2, sessions.size)
    }

    @Test
    fun `a session is about me when any event was evaluated against me`() {
        val mine = buildSessions(
            listOf(entry(0, aboutMe = false), entry(1, aboutMe = true))
        ).single()
        assertTrue(mine.aboutMe())
        val theirs = buildSessions(listOf(entry(0, aboutMe = false))).single()
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
        val sessions = buildSessions(
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
        val sessions = buildSessions(listOf(entry(0), entry(40)))
        assertEquals(2, sessions.size)
    }

    @Test
    fun `a second official onset always opens its own session`() {
        val sessions = buildSessions(
            listOf(
                entry(0, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED),
                entry(2, kind = DebugLogKind.OFFICIAL_ON, level = AlertLevel.RED)
            )
        )
        assertEquals(2, sessions.size)
    }

    @Test
    fun `an all-clear stays inside the alarm session`() {
        val sessions = buildSessions(
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
        val told = buildSessions(
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
        val silent = buildSessions(
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
        assertTrue(buildSessions(emptyList()).isEmpty())
    }

    @Test
    fun `sessions run newest first`() {
        val sessions = buildSessions(listOf(entry(0), entry(40), entry(80)))
        assertEquals(80 * minute, sessions.first().startMs)
        assertEquals(0 * minute, sessions.last().startMs)
    }

    @Test
    fun `session oblast is the majority place`() {
        val session = buildSessions(
            listOf(entry(0, scope = "odeska"), entry(1, scope = "odeska"), entry(2, scope = "kyivska"))
        ).single()
        assertEquals("odeska", session.oblastId())
    }

    @Test
    fun `duration spans first to last event`() {
        val session = buildSessions(listOf(entry(0), entry(7))).single()
        assertEquals(7 * minute, session.durationMs)
    }
}
