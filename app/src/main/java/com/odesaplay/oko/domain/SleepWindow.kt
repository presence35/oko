package com.odesaplay.oko

import com.odesaplay.oko.engine.NormalizedThreat
import com.odesaplay.oko.engine.OblastAlert
import java.time.Instant

/**
 * A stretch where the monitor loop did not run. Doze suspends the whole process (screen off,
 * device idle), so `delay` never fires and no tick happens between [startedAtMillis] and
 * [wokeAtMillis] — measured on a real pair of devices at 165 stalls/day and 17% of the day blind
 * on a Pixel 7, 301 and 48% on an SM-A505FN.
 *
 * A fact, not a preference: produced at the one place that can know a tick was missed
 * (`AlertService.resumeAfterSleep`) and consumed like any other engine currency. Deliberately the
 * only thing the service has to say about a sleep — the *meaning* of a window is [SleepPolicy]'s
 * business, so the service stays a transport and the UI stays stateless.
 */
data class SleepWindow(val startedAtMillis: Long, val wokeAtMillis: Long) {
    val durationMs: Long get() = (wokeAtMillis - startedAtMillis).coerceAtLeast(0L)
    val minutes: Int get() = (durationMs / 60_000L).coerceAtLeast(1L).toInt()
}

/**
 * A sleep waiting to be reported, with its evidence measured once and then frozen.
 *
 * [evidence] is null only between the tick that closes the sleep and the evaluation that measures
 * it — the tick coroutine knows a tick was missed but not what the feed says, so the two facts are
 * assembled one after the other. Freezing matters: the window is expressed against the feed's own
 * clock, so re-deriving it on a later tick would slide it forward and grow the count. See
 * [SleepPolicy.evidenceFor].
 */
data class PendingSleep(val window: SleepWindow, val evidence: SleepEvidence? = null)

/**
 * What the feed can honestly prove happened while the loop was asleep.
 *
 * Anchored on [feedNowMillis], the **newest server timestamp in the feed**, and never on the device
 * clock. That is the whole trick: `NormalizedThreat`'s timestamps are server epoch millis, and the
 * device clock can be minutes off (or jump mid-sleep), so comparing them to
 * `System.currentTimeMillis()` would shift the window by the skew — silently over- or
 * under-reporting. Comparing server timestamps to a server timestamp cannot.
 */
data class SleepEvidence(
    val feedNowMillis: Long?,
    val confirmedInWindow: Int,
    val officialOnsetInWindow: Boolean = false
) {
    /**
     * False when the feed could not be read at all. That is "cannot tell", never "all clear" — a
     * cold start (fresh install, a provider switch, a build just replaced) would otherwise look
     * like a quiet night and earn an all-good notice on the strength of having no data.
     */
    val readable: Boolean get() = feedNowMillis != null

    companion object {
        val UNKNOWN = SleepEvidence(feedNowMillis = null, confirmedInWindow = 0)
    }
}

/**
 * What to do about a sleep. Sealed so [AlertService] has no arms to get wrong: it consumes the
 * pending sleep, calls [SleepPolicy.decide] once, and executes. Every branch that could be
 * mis-ordered — muted, exempt, too short, unreadable feed — is decided in one pure, tested place,
 * the same split as [NotifyPlugin]'s verdicts.
 */
sealed interface SleepDecision {
    /** Below the reporting floor, or the feed proves nothing either way. Consume it. */
    data object Silent : SleepDecision

    /** A live raid mute: keep it pending and ask again next tick rather than dropping it. */
    data object Hold : SleepDecision

    /** The exemption has been granted; the fix landed. Consume it and take the row down. */
    data object Dismiss : SleepDecision

    data class Post(val window: SleepWindow, val evidence: SleepEvidence) : SleepDecision
}

/**
 * Turns a [SleepWindow] into a verdict. Pure, so the arithmetic that decides whether to interrupt
 * a sleeping user is unit-testable — which is the only defensible way to ship something that fires
 * while the phone is on a nightstand.
 */
object SleepPolicy {

    /**
     * Below this a gap is an ordinary hiccup (a slow sweep, an OEM freeze, a long GC) and not worth
     * a word.
     *
     * Calibrated against the measured Doze window, not chosen: on the two phones this was built
     * from, every real stall was 300-373 s (5-6.2 min) — Doze's maintenance period — while the idle
     * tick is 30 s and `AlertService.SLEEP_GAP_REBASE_MS` is 3 min. So the whole useful band is
     * 3-5 min. A 10-minute floor sounded prudent and was useless: it would have fired almost never
     * on the Pixel and a handful of times a night on the Samsung, i.e. shipped dead.
     */
    const val REPORT_AFTER_MS = 4 * 60_000L

    /**
     * How long the notice stays in the shade before the platform retires it. Long enough to cover
     * the night it describes and be seen with breakfast, short enough that yesterday's sleep is not
     * still sitting there tonight. Enforced by `Notification.setTimeoutAfter`, so it needs no
     * deadline field, no tick check and no Activity hookup — and a later sleep, which re-posts the
     * same id, starts its own clock.
     */
    const val NOTICE_TTL_MS = 12 * 60 * 60_000L

    /**
     * Order matters and is the whole policy:
     *  - an unreadable feed or a sub-floor gap is *silent*, checked before the mute so noise is
     *    never held on to;
     *  - the exemption is checked first because it is terminal — the problem it names is fixed, so
     *    the row comes down whether or not it would still be worth posting.
     */
    fun decide(
        window: SleepWindow,
        evidence: SleepEvidence,
        muted: Boolean = false,
        exemptionGranted: Boolean = false
    ): SleepDecision = when {
        exemptionGranted -> SleepDecision.Dismiss
        !evidence.readable || window.durationMs < REPORT_AFTER_MS -> SleepDecision.Silent
        muted -> SleepDecision.Hold
        else -> SleepDecision.Post(window, evidence)
    }

    /**
     * Reads the evidence out of the post-wake feed. Called once, on the tick that closes a sleep,
     * because that is the only moment the answer exists: the window is `durationMs` wide ending at
     * "server now", so a track whose [NormalizedThreat.confirmedAtMillis] falls inside it was
     * confirmed by the source while we were not running.
     *
     * Two deliberate choices in the anchor:
     *  - **Threats AND alerts.** An official alert during a sleep with an empty threat feed must not
     *    read as "nothing to report" — that is the case that matters most.
     *  - **`confirmedAt` only**, never `updatedAt`: a live track re-stamps `updatedAt` on every fix,
     *    so counting those would report every track in the sky as "happened while you slept". The
     *    first confirmation is the honest anchor for "this came into being then".
     *
     * [focusAlertSince] is the focus oblast's own onset, already resolved through the shared
     * `officialStateFor` gate by the caller — this function never re-implements official matching,
     * and never learns what an official alert is. Self-excluding: an episode already live before
     * the sleep has `since < windowStart`, so it is not claimed.
     *
     * Not counted, and never claimed: tracks that appeared *and resolved* entirely inside the
     * window. They reach no snapshot we hold, so the copy must never say "you missed N alerts" —
     * only what was confirmed while it was off.
     */
    fun evidenceFor(
        window: SleepWindow,
        threats: Collection<NormalizedThreat>,
        alerts: List<OblastAlert> = emptyList(),
        focusAlertSince: String? = null
    ): SleepEvidence {
        val feedNow = (
            threats.mapNotNull { it.updatedAtMillis ?: it.confirmedAtMillis } +
                alerts.mapNotNull { officialOnsetMillis(it.since) }
            ).maxOrNull() ?: return SleepEvidence.UNKNOWN

        val windowStart = feedNow - window.durationMs
        return SleepEvidence(
            feedNowMillis = feedNow,
            confirmedInWindow = threats.count { t ->
                val at = t.confirmedAtMillis ?: return@count false
                at in windowStart..feedNow
            },
            officialOnsetInWindow = officialOnsetMillis(focusAlertSince)
                ?.let { it in windowStart..feedNow } == true
        )
    }

    /**
     * The approximate instant an official episode came on, or null when the source gave us nothing
     * we can read.
     *
     * `since` is a source-stamped ISO string that can be re-stamped, so it is read leniently and is
     * **never** used for identity — `LatchedEpisode` owns identity precisely because a re-stamp must
     * not read as a new episode. Here it is only an onset, and only to answer "did this come on
     * while we were out" — which is why the result is a boolean and not a count.
     */
    internal fun officialOnsetMillis(since: String?): Long? =
        since?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}
