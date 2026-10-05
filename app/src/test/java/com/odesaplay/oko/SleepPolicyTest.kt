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

    // ---- decide -------------------------------------------------------------------------------

    /** Under the floor a gap is an ordinary hiccup — a slow sweep, a frozen OEM, a long GC. */
    @Test
    fun `a short sleep is not worth a word`() {
        assertEquals(
            SleepDecision.Silent,
            SleepPolicy.decide(window(SleepPolicy.REPORT_AFTER_MS - 1), readable(confirmed = 5))
        )
    }

    /** The floor is inclusive: exactly the threshold is reportable, one millisecond under is not. */
    @Test
    fun `the reporting floor is inclusive and nothing below it is held`() {
        assertEquals(
            SleepDecision.Post::class,
            SleepPolicy.decide(window(SleepPolicy.REPORT_AFTER_MS), readable()).let { it::class }
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
        assertEquals(SleepDecision.Silent, SleepPolicy.decide(window(60 * 60_000L), SleepEvidence.UNKNOWN))
    }

    /** A live raid mute DEFERS: a sleep is an event, so suppressing it would lose it outright. */
    @Test
    fun `a mute holds the sleep rather than dropping it`() {
        assertEquals(
            SleepDecision.Hold,
            SleepPolicy.decide(window(20 * 60_000L), readable(confirmed = 3), muted = true)
        )
    }

    /** ...but the mute never holds something we would not have said anyway. */
    @Test
    fun `a mute does not hold a sleep that is below the floor`() {
        assertEquals(
            SleepDecision.Silent,
            SleepPolicy.decide(window(SleepPolicy.REPORT_AFTER_MS - 1), readable(), muted = true)
        )
    }

    @Test
    fun `a mute does not hold an unreadable sleep`() {
        assertEquals(
            SleepDecision.Silent,
            SleepPolicy.decide(window(60 * 60_000L), SleepEvidence.UNKNOWN, muted = true)
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
            SleepPolicy.decide(window(20 * 60_000L), readable(confirmed = 3), muted = true, exemptionGranted = true)
        )
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
        assertEquals(SleepDecision.Post::class, SleepPolicy.decide(window(20 * 60_000L), evidence)::class)
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

    // ---- body composition ---------------------------------------------------------------------

    /**
     * The clauses are independent, so both facts survive in one body without a string per
     * combination — and the count clause disappears rather than rendering as a bare zero.
     */
    @Test
    fun `the body renders only the clauses that apply`() {
        val s = Strings.get(AppLanguage.EN)
        val w = window(20 * 60_000L)

        val quiet = sleepNoticeBody(s, w, SleepEvidence(serverNow, 0, false))
        assertTrue(quiet.contains("20 min"))
        assertTrue(!quiet.contains("confirmed"))
        assertTrue(!quiet.contains("official alert"))

        val both = sleepNoticeBody(s, w, SleepEvidence(serverNow, 3, true))
        assertTrue(both.contains("An official alert started in that time."))
        assertTrue(both.contains("3 threats confirmed"))
        assertTrue(both.contains(s.sleepNoticeAllowClause))

        val one = sleepNoticeBody(s, w, SleepEvidence(serverNow, 1, false))
        assertTrue("the singular form must not read as `1 threats`", one.contains("1 threat confirmed"))
    }

    private fun iso(millis: Long) = java.time.Instant.ofEpochMilli(millis).toString()

    private fun alert(since: String?) = OblastAlert(
        key = "Одеська область",
        name = "Одеська область",
        oblast = "Одеська",
        since = since
    )
}
