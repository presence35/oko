package com.odesaplay.oko.connection

import com.odesaplay.oko.Strings
import java.net.UnknownHostException
import java.time.Instant

/**
 * Why the feed is down — the typed currency every consumer reads.
 *
 * This replaces a free-text `reason` that nothing ever read (its only consumer discarded the
 * argument) plus a boolean bolted beside it to mean one of the cases. A cause that the state
 * carries means the notification copy, the log line and the retry-ability of the outage are all
 * derived from ONE place, so they cannot drift into contradicting each other — the report that
 * motivated it was a "tap Retry, nothing happens" dead end shown for an outage no retry can fix.
 */
sealed interface OfflineCause {

    /** No usable internet at all — the socket never had a link to try. */
    data object NoNetwork : OfflineCause

    /** The resolver would not answer for this host. Log-only: the shade row never names a cause
     *  it cannot verify — one failed lookup is also what a wifi joining or a stale resolver
     *  produces, and telling the user "DNS blocked" for that is a claim, not a fact. */
    data class DnsFailure(val host: String) : OfflineCause

    /** The host was resolved and refused us (or hung up): retrying is the whole point. */
    data class SocketFailure(val message: String?) : OfflineCause

    /** The socket was open but went quiet past the hard watchdog. */
    data object SilenceTimeout : OfflineCause

    /** A validated network went away under us. */
    data class NetworkLost(val detail: String?) : OfflineCause

    /** Classifies a failed connect. The throwable arrives nested (OkHttp wraps, the platform wraps
     *  again), so the walk over the chain is the behaviour that matters, not the direct case. */
    companion object {
        fun from(t: Throwable?, host: String): OfflineCause =
            if (t.hasCause<UnknownHostException>()) DnsFailure(host)
            else SocketFailure(t?.message)
    }

    /** One place a cause becomes words for the reconnect log. */
    fun label(s: Strings.StringSet): String = when (this) {
        NoNetwork, is NetworkLost -> s.connEventNoNetwork
        is DnsFailure -> s.connEventDnsBlocked
        is SocketFailure, SilenceTimeout -> s.connEventLost
    }

    /**
     * Deliberately NO notification copy here. The cause explains the outage to someone reading
     * the connection log; the shade row says only what is true for every cause ("server
     * unreachable, N min"). A cause-specific headline was a diagnosis the transport cannot make
     * — the OS and the browser already tell a user when their network is broken.
     */
}

private inline fun <reified T : Throwable> Throwable?.hasCause(): Boolean {
    var cur = this
    var guard = 0
    while (cur != null && guard++ < 8) {
        if (cur is T) return true
        cur = cur.cause
    }
    return false
}

/** Live reconnect state for the current offline episode — transient, never persisted. */
data class ConnRetryState(
    val attempt: Int,
    val delayMs: Long,
    val nextAtMs: Long,
    val networkValidated: Boolean
)

/** Kind of a line in the current offline episode's transient reconnect log. */
enum class ConnEventKind {
    CONNECTION_LOST, RETRY_SCHEDULED, RETRY_MANUAL, NO_NETWORK, DEGRADED,
    MILESTONE_3, MILESTONE_5, MILESTONE_6, MILESTONE_10, MILESTONE_20,
    GAVE_UP, IGNORE_MUTED, FALLBACK_ACTIVE, FALLBACK_RESTORED, SOURCE_TOGGLED
}

/** A degraded episode shorter than this is stitched back onto the previous one
 *  instead of restarting its timers. Used only by [com.odesaplay.oko.source.SourceRegistry]. */
const val EPISODE_CONTINUITY_GRACE_MS = 3_000L

/** Offline-episode milestone signal, emitted once per episode by [com.odesaplay.oko.source.SourceRegistry].
 *  Transport ([com.odesaplay.oko.connection.ResilientConnectionSupervisor]) never emits it. */
enum class ConnectionMilestone(val minutes: Int) {    M3(3),
    M5_CRITICAL(5),
    M6(6),
    M10(10),
    M20_GAVE_UP(20)
}

/** One line of the current offline episode's reconnect log (the in-between only). */
data class ConnEvent(
    val atMillis: Long,
    val kind: ConnEventKind,
    val attempt: Int? = null,
    val delayMs: Long? = null,
    val detail: String? = null
) {
    fun label(s: Strings.StringSet): String = when (kind) {
        ConnEventKind.CONNECTION_LOST -> s.connEventLost
        ConnEventKind.RETRY_SCHEDULED -> String.format(s.connEventRetry, ((delayMs ?: 0L) / 1000).coerceAtLeast(1), attempt ?: 0)
        ConnEventKind.RETRY_MANUAL -> s.connEventManualRetry
        ConnEventKind.NO_NETWORK -> s.connEventNoNetwork
        ConnEventKind.DEGRADED -> s.connEventDegraded
        ConnEventKind.MILESTONE_3 -> s.connEventMin3
        ConnEventKind.MILESTONE_5 -> s.connEventMin5
        ConnEventKind.MILESTONE_6 -> s.connEventMin6
        ConnEventKind.MILESTONE_10 -> s.connEventMin10
        ConnEventKind.MILESTONE_20 -> s.connEventMin20
        ConnEventKind.GAVE_UP -> s.connEventGaveUp
        ConnEventKind.IGNORE_MUTED -> String.format(s.connEventIgnored, detail ?: "")
        ConnEventKind.FALLBACK_ACTIVE -> String.format(s.connEventFallbackActive, detail ?: "")
        ConnEventKind.FALLBACK_RESTORED -> s.connEventFallbackRestored
        ConnEventKind.SOURCE_TOGGLED -> String.format(s.connEventSourceToggled, detail ?: "")
    }
}

/**
 * Formal Connection State Machine for the NEPTUN WebSocket telemetry feed.
 *
 * Replaces the fragmented boolean flags (connected, degraded,
 * connectInFlight, reconnectAttempt, etc.) with a single, unambiguous state.
 */
sealed interface ConnectionState {

    /** Default initial state before client is started or after explicitly stopped. */
    object Disconnected : ConnectionState

    /** Active WebSocket connection attempt in flight. Carries the episode start so the
     *  offline episode survives Offline ↔ Connecting transitions without derived-state gaps. */
    data class Connecting(
        val generation: Int,
        val attempt: Int,
        val nextRetryAtMs: Long,
        val networkValidated: Boolean,
        val reconnectStartMillis: Long
    ) : ConnectionState

    /** Connected and receiving live telemetry frames normally. */
    data class Connected(
        val generation: Int,
        val openedAtMs: Long,
        val lastFrameAtMs: Long
    ) : ConnectionState

    /**
     * Connected (socket is open), but no frame has arrived for >= [DEGRADED_STALE_MS] (4m20s).
     * Indicates a degraded link / silent stall before the hard watchdog drops it.
     */
    data class Degraded(
        val generation: Int,
        val openedAtMs: Long,
        val lastFrameAtMs: Long,
        val quietDurationMs: Long
    ) : ConnectionState

    /**
     * Socket is disconnected or unavailable.
     *
     * @property since Epoch millis when this offline episode began.
     * @property reconnectStartMillis Wall-clock stamp of the down edge (plain, no flap stitching).
     * @property cause Why the feed is down, typed — the single source for the notification copy,
     *   the reconnect-log line and whether a retry can help. Never a free-text reason: nothing
     *   downstream could act on one.
     * @property attempt Current retry count in the active backoff sequence.
     */
    data class Offline(
        val since: Long,
        val reconnectStartMillis: Long,
        val cause: OfflineCause = OfflineCause.NoNetwork,
        val attempt: Int = 0
    ) : ConnectionState
}

/** Convenience extensions to simplify UI and service queries. */
val ConnectionState.isConnected: Boolean
    get() = this is ConnectionState.Connected || this is ConnectionState.Degraded

val ConnectionState.isDegraded: Boolean
    get() = this is ConnectionState.Degraded

val ConnectionState.isOffline: Boolean
    get() = this is ConnectionState.Offline || this is ConnectionState.Disconnected

/** The typed cause while offline, null otherwise. The one accessor consumers need. */
val ConnectionState.offlineCauseOrNull: OfflineCause?
    get() = (this as? ConnectionState.Offline)?.cause

val ConnectionState.offlineSinceOrNull: Long?
    get() = when (this) {
        is ConnectionState.Offline -> since
        else -> null
    }

val ConnectionState.reconnectStartMillisOrZero: Long
    get() = when (this) {
        is ConnectionState.Offline -> reconnectStartMillis
        is ConnectionState.Connecting -> reconnectStartMillis
        else -> 0L
    }
