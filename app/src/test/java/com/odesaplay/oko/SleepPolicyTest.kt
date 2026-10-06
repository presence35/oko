package com.odesaplay.oko

import com.odesaplay.oko.engine.OblastAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sleep notice interrupts a user who is probably in bed, so the arithmetic that decides
 * whether to do it is worth pinning down. Every test here is about a boundary, an ordering, or a
 * failure mode — not about the happy path.
 */
class SleepPolicyTest {

    /** Server-clock anchor. Deliberately nowhere near System.currentTimeMillis() — see below. */
    private val serverNow = 10_000_000L

    private fun window(durationMs: Long) =
        SleepWindow(startedAtMillis = 0L, wokeAtMillis = durationMs)

    private fun readable(confirmed: Int = 0, official: Boolean = false) =
        SleepEvidence(feedNowMillis = serverNow, confirmedInWindow = confirmed, officialOnsetInWindow = official)

    /**
     * `decide` requires `enabled` so no caller can post notices at a user who switched them off.
     * Every test below is about the ON path, so it declares that once here instead of 12 times —
     * and the tests that exercise the switch call [SleepPolicy.decide] directly.
     */
    private fun decide(
        window: SleepWindow,
        evidence: SleepEvidence,
        enabled: Boolean = true,
        muted: Boolean = false,
        exemptionGranted: Boolean = false
    ) = SleepPolicy.decide(window, evidence, enabled, muted, exemptionGranted)

    // ---- decide -------------------------------------------------------------------------------

    /** Under the floor a gap is an ordinary hiccup — a slow sweep, a frozen OEM, a long GC. */
    @Test
    fun `a short sleep is not worth a word`() {
        assertEquals(
            SleepDecision.Silent,
            decide(window(SleepPolicy.REPORT_AFTER_MS - 1), readable(confirmed = 5))
        )
    }

    /** The floor is inclusive: exactly the threshold is reportable, one millisecond under is not. */
    @Test
    fun `the reporting floor is inclusive and nothing below it is held`() {
        assertEquals(
            SleepDecision.Post::class,
            decide(window(SleepPolicy.REPORT_AFTER_MS), readable(confirmed = 1)).let { it::class }
        )
    }

    /**
     * The whole point of the gate: a gap the feed shows missed nothing is not worth waking anyone
     * for. A Doze window that dropped no socket and confirmed no threat is the common case on a
     * phone that sleeps without going blind, and it must go silent rather than earn the exemption
     * ask on the strength of merely having been deferred.
     */
    @Test
    fun `a readable sleep that missed nothing is silent`() {
        assertEquals(SleepDecision.Silent, decide(window(20 * 60_000L), readable()))
    }

    /** A threat confirmed while the loop was not running is real harm and must be reported. */
    @Test
    fun `a confirmed threat in the window is reported`() {
        assertEquals(
            SleepDecision.Post::class,
            decide(window(20 * 60_000L), readable(confirmed = 1))::class
        )
    }

    /** ...as is an official onset, even with a threat feed that shows nothing. */
    @Test
    fun `an official onset alone is reported`() {
        assertEquals(
            SleepDecision.Post::class,
            decide(window(20 * 60_000L), readable(official = true))::class
        )
    }

    /** ...but a mute never holds a sleep we would not have reported anyway. */
    @Test
    fun `a mute does not hold a sleep that missed nothing`() {
        assertEquals(
            SleepDecision.Silent,
            decide(window(20 * 60_000L), readable(), muted = true)
        )
    }

    /**
     * The calibration, pinned. Every stall measured on the two reference devices was 300-373 s, so a
     * floor above that makes the whole feature unreachable — which is exactly how this started at 10
     * minutes and would have shipped firing almost never.
     */
    @Test
    fun `the floor sits inside the band a real Doze window occupies`() {
        assertTrue(
            "a floor above the measured 300 s window never fires",
            SleepPolicy.REPORT_AFTER_MS < 300_000L
        )
        assertTrue(
            "a floor at or below the rebase threshold reports ordinary hiccups",
            SleepPolicy.REPORT_AFTER_MS > 3 * 60_000L
        )
    }

    /**
     * An unreadable feed is "cannot tell", never "all clear". A cold start — a fresh install, a
     * provider switch, a build just replaced — must not earn a notice on the strength of having no
     * data at all. This is the "except after first launch" case.
     */
    @Test
    fun `an unreadable feed is silent, never reported`() {
        assertEquals(SleepDecision.Silent, decide(window(60 * 60_000L), SleepEvidence.UNKNOWN))
    }

    /** A live raid mute DEFERS: a sleep is an event, so suppressing it would lose it outright. */
    @Test
    fun `a mute holds the sleep rather than dropping it`() {
        assertEquals(
            SleepDecision.Hold,
            decide(window(20 * 60_000L), readable(confirmed = 3), muted = true)
        )
    }

    /** ...but the mute never holds something we would not have said anyway. */
    @Test
    fun `a mute does not hold a sleep that is below the floor`() {        assertEquals(
            SleepDecision.Silent,
            decide(window(SleepPolicy.REPORT_AFTER_MS - 1), readable(), muted = true)
        )
    }

    @Test
    fun `a mute does not hold an unreadable sleep`() {
        assertEquals(
            SleepDecision.Silent,
            decide(window(60 * 60_000L), SleepEvidence.UNKNOWN, muted = true)
        )
    }

    /**
     * The exemption is terminal and outranks everything, including the mute: the problem the notice
     * names is fixed, so the row comes down whether or not it would still be worth posting.
     */
    @Test
    fun `a granted exemption dismisses even while muted`() {
        assertEquals(
            SleepDecision.Dismiss,
            decide(window(20 * 60_000L), readable(confirmed = 3), muted = true, exemptionGranted = true)
        )
    }

    // ---- the feature switch --------------------------------------------------------------------

    /**
     * Off is `Silent`, and it is the FIRST arm, so a switched-off notice cannot be talked into
     * posting by anything that follows — not by harm, not by a mute, not by the exemption. `Silent`
     * rather than `Dismiss` on purpose: nothing folds into the ledger, so there is nothing to
     * publish, and the row that may already be up is taken down by the service's retract check
     * instead of by every single stall.
     */
    @Test
    fun `a switched-off notice never posts, whatever the evidence says`() {
        val harmful = readable(confirmed = 5, official = true)
        assertEquals(
            SleepDecision.Silent,
            SleepPolicy.decide(window(30 * 60_000L), harmful, enabled = false)
        )
        assertEquals(
            SleepDecision.Silent,
            SleepPolicy.decide(window(30 * 60_000L), harmful, enabled = false, muted = true)
        )
        assertEquals(
            SleepDecision.Silent,
            SleepPolicy.decide(
                window(30 * 60_000L), harmful,
                enabled = false, muted = true, exemptionGranted = true
            )
        )
    }

    /** The switch is the one arm that can turn a would-be post into silence. */
    @Test
    fun `the same window posts when enabled and is silent when not`() {
        val w = window(30 * 60_000L)
        val harmed = readable(confirmed = 2)
        assertEquals(SleepDecision.Post::class, decide(w, harmed)::class)
        assertEquals(SleepDecision.Silent, SleepPolicy.decide(w, harmed, enabled = false))
    }

    // ---- evidenceFor --------------------------------------------------------------------------

    /**
     * The verdict must not depend on the device clock. These server timestamps sit ~55 years from
     * System.currentTimeMillis(), so a device-clock comparison would push an in-window track outside
     * the window and report nothing — any reintroduced wall-clock read flips this test. That matters
     * because the phone clock can be wrong or jump mid-sleep, and this is precisely a case where the
     * app was not running to notice.
     */
    @Test
    fun `the window is measured in server time, not the device clock`() {
        val evidence = SleepPolicy.evidenceFor(
            window(20 * 60_000L),
            listOf(threat(id = "inside", confirmedAtMillis = serverNow - 60_000L, updatedAtMillis = serverNow))
        )
        assertEquals(1, evidence.confirmedInWindow)
    }

    /**
     * `updatedAt` alone must not count. A live track re-stamps it on every fix, so counting those
     * would report every track in the sky as "confirmed while you slept".
     */
    @Test
    fun `a restamped live track is not counted as newly confirmed`() {
        val evidence = SleepPolicy.evidenceFor(
            window(20 * 60_000L),
            listOf(
                threat(id = "live", confirmedAtMillis = serverNow - 90 * 60_000L, updatedAtMillis = serverNow - 5_000L)
            )
        )
        assertEquals(0, evidence.confirmedInWindow)
    }

    /** An empty feed is absent evidence, not zero-of-something — the null must survive. */
    @Test
    fun `an empty feed yields unknown evidence rather than zero`() {
        val evidence = SleepPolicy.evidenceFor(window(20 * 60_000L), emptyList())
        assertEquals(null, evidence.feedNowMillis)
        assertTrue(!evidence.readable)
    }

    /** A feed with no timestamps at all is equally unreadable. */
    @Test
    fun `a feed with no timestamps is unreadable rather than empty-but-fine`() {
        val evidence = SleepPolicy.evidenceFor(window(20 * 60_000L), listOf(threat(id = "noTime")))
        assertEquals(null, evidence.feedNowMillis)
    }

    /**
     * The anchor must count alerts too, or the case that matters most is lost: an official alert
     * during a sleep with an empty threat feed must not read as "nothing to report".
     */
    @Test
    fun `an official alert alone anchors the window and is reported`() {
        val evidence = SleepPolicy.evidenceFor(
            window(20 * 60_000L),
            threats = emptyList(),
            alerts = listOf(alert(since = iso(serverNow - 2 * 60_000L))),
            focusAlertSince = iso(serverNow - 2 * 60_000L)
        )
        assertEquals(serverNow - 2 * 60_000L, evidence.feedNowMillis)
        assertEquals(true, evidence.officialOnsetInWindow)
        assertEquals(SleepDecision.Post::class, decide(window(20 * 60_000L), evidence)::class)
    }

    /** An episode already live before the sleep is not claimed: it self-excludes on `since`. */
    @Test
    fun `an official alert that predates the sleep is not claimed`() {
        val evidence = SleepPolicy.evidenceFor(
            window(20 * 60_000L),
            threats = emptyList(),
            alerts = listOf(alert(since = iso(serverNow))),
            focusAlertSince = iso(serverNow - 90 * 60_000L)
        )
        assertEquals(false, evidence.officialOnsetInWindow)
    }

    /** A `since` the parser cannot read is absent evidence, not epoch and not "now". */
    @Test
    fun `an unparseable since is ignored rather than defaulting`() {
        assertEquals(null, SleepPolicy.officialOnsetMillis("not-a-timestamp"))
        assertEquals(null, SleepPolicy.officialOnsetMillis(null))
        assertEquals(serverNow, SleepPolicy.officialOnsetMillis(iso(serverNow)))
    }

    // ---- ledger -------------------------------------------------------------------------------

    /**
     * The fold is over disjoint windows, so the totals are plain sums and an official onset is an
     * OR — no de-duplication is needed, and a whole night collapses to one number.
     */
    @Test
    fun `the ledger folds a night of stalls into one tally`() {
        val ledger = SleepLedger.EMPTY
            .plus(window(5 * 60_000L), readable(confirmed = 2, official = true))
            .plus(window(9 * 60_000L), readable(confirmed = 3))

        assertEquals(2, ledger.stalls)
        assertEquals(14 * 60_000L, ledger.blindMs)
        assertEquals(9 * 60_000L, ledger.worstMs)
        assertEquals(5, ledger.confirmed)
        assertEquals(true, ledger.officialOnset)
        assertEquals(14, ledger.minutes)
    }

    /** Nothing is due before a stall, and nothing is due while nobody is looking. */
    @Test
    fun `the row waits for a stall and for the screen`() {
        assertTrue(!SleepPolicy.noticeDue(SleepLedger.EMPTY, serverNow, screenOn = true))

        val ledger = SleepLedger.EMPTY.plus(window(6 * 60_000L), readable())
        assertTrue(!SleepPolicy.noticeDue(ledger, serverNow, screenOn = false))
        assertTrue(SleepPolicy.noticeDue(ledger, serverNow, screenOn = true))
    }

    /** Once written, the row is left alone until the tally has grown enough to be worth re-reading. */
    @Test
    fun `a written row is not rewritten until the refresh cap`() {
        val posted = SleepLedger.EMPTY.plus(window(6 * 60_000L), readable()).posted(serverNow)

        assertTrue(!SleepPolicy.noticeDue(posted, serverNow + 60_000L, screenOn = true))
        assertTrue(!SleepPolicy.noticeDue(posted, serverNow + SleepPolicy.REFRESH_AFTER_MS - 1, screenOn = true))
        assertTrue(SleepPolicy.noticeDue(posted, serverNow + SleepPolicy.REFRESH_AFTER_MS, screenOn = true))
    }

    /**
     * A fresh official onset is the one fact that must not wait for the cap: it is the case the
     * whole feature exists for, and the user could not have heard it.
     */
    @Test
    fun `a new official onset pierces the refresh cap`() {
        val posted = SleepLedger.EMPTY.plus(window(6 * 60_000L), readable()).posted(serverNow)
        val afterOnset = posted.plus(window(6 * 60_000L), readable(official = true))

        assertTrue(SleepPolicy.noticeDue(afterOnset, serverNow + 60_000L, screenOn = true))
        // ...but only once: the onset already shown must not reopen the row.
        val reported = afterOnset.posted(serverNow + 60_000L)
        assertTrue(!SleepPolicy.noticeDue(reported, serverNow + 120_000L, screenOn = true))
    }

    /**
     * The row retires itself after its TTL. That retired row is last night's summary, so the tally
     * must not carry into tonight's.
     */
    @Test
    fun `a row past its TTL starts a new night`() {
        val posted = SleepLedger.EMPTY.plus(window(6 * 60_000L), readable()).posted(serverNow)

        assertTrue(!SleepPolicy.rowExpired(posted, serverNow + SleepPolicy.NOTICE_TTL_MS - 1))
        assertTrue(SleepPolicy.rowExpired(posted, serverNow + SleepPolicy.NOTICE_TTL_MS))
        assertTrue(!SleepPolicy.noticeDue(posted, serverNow + SleepPolicy.NOTICE_TTL_MS, screenOn = true))
    }

    // ---- body composition ---------------------------------------------------------------------

    /**
     * The clauses are independent, so both facts survive in one body without a string per
     * combination — and the count clause disappears rather than rendering as a bare zero.
     */
    @Test
    fun `the body renders only the clauses that apply`() {
        val s = Strings.get(AppLanguage.EN)
        val w = window(20 * 60_000L)

        val quiet = sleepNoticeBody(s, SleepLedger.EMPTY.plus(w, SleepEvidence(serverNow, 0, false)))
        assertTrue(quiet.contains("20 min"))
        assertTrue(!quiet.contains("confirmed"))
        assertTrue(!quiet.contains("official alert"))

        val both = sleepNoticeBody(s, SleepLedger.EMPTY.plus(w, SleepEvidence(serverNow, 3, true)))
        assertTrue(both.contains("An official alert started in that time."))
        assertTrue(both.contains("3 threats confirmed"))
        assertTrue(both.contains(s.sleepNoticeAllowClause))

        val one = sleepNoticeBody(s, SleepLedger.EMPTY.plus(w, SleepEvidence(serverNow, 1, false)))
        assertTrue("the singular form must not read as `1 threats`", one.contains("1 threat confirmed"))
    }

    /**
     * A single stall reads as the plain sentence; a night reports its total instead of the floor the
     * first one crossed, and never says "across 1 gap".
     */
    @Test
    fun `a night of stalls reports its total, not the last one`() {
        val s = Strings.get(AppLanguage.EN)
        val single = SleepLedger.EMPTY.plus(window(4 * 60_000L), readable())
        assertTrue(sleepNoticeBody(s, single).contains("off for 4 min."))

        val night = single.plus(window(6 * 60_000L), readable()).plus(window(5 * 60_000L), readable())
        val body = sleepNoticeBody(s, night)
        assertTrue(body.contains("15 min across 3 gaps"))
        assertTrue(!body.contains("4 min"))
    }

    private fun iso(millis: Long) = java.time.Instant.ofEpochMilli(millis).toString()

    private fun alert(since: String?) = OblastAlert(
        key = "Одеська область",
        name = "Одеська область",
        oblast = "Одеська",
        since = since
    )
}
