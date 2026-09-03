package ua.ukrainedrones.connection

import ua.ukrainedrones.Strings
import ua.ukrainedrones.ThreatType
import java.time.Instant

/** Live reconnect state for the current offline episode — transient, never persisted. */
data class ConnRetryState(
    val attempt: Int,
    val delayMs: Long,
    val nextAtMs: Long,
    val networkValidated: Boolean
)

/** Kind of a line in the current offline episode's transient reconnect log. */
enum class ConnEventKind {
    CONNECTION_LOST, RETRY_SCHEDULED, NO_NETWORK, DEGRADED,
    MILESTONE_3, MILESTONE_5, MILESTONE_6, MILESTONE_10, MILESTONE_20,
    GAVE_UP, PAUSED
}

/** One line of the current offline episode's reconnect log (the in-between only). */
data class ConnEvent(
    val atMillis: Long,
    val kind: ConnEventKind,
    val attempt: Int? = null,
    val delayMs: Long? = null
) {
    fun label(s: Strings.StringSet): String = when (kind) {
        ConnEventKind.CONNECTION_LOST -> s.connEventLost
        ConnEventKind.RETRY_SCHEDULED -> String.format(s.connEventRetry, ((delayMs ?: 0L) / 1000).coerceAtLeast(1), attempt ?: 0)
        ConnEventKind.NO_NETWORK -> s.connEventNoNetwork
        ConnEventKind.DEGRADED -> s.connEventDegraded
        ConnEventKind.MILESTONE_3 -> s.connEventMin3
        ConnEventKind.MILESTONE_5 -> s.connEventMin5
        ConnEventKind.MILESTONE_6 -> s.connEventMin6
        ConnEventKind.MILESTONE_10 -> s.connEventMin10
        ConnEventKind.MILESTONE_20 -> s.connEventMin20
        ConnEventKind.GAVE_UP -> s.connEventGaveUp
        ConnEventKind.PAUSED -> s.connEventPaused
    }
}

/** A threat just disappeared from the server feed (resolved or a remove frame) — drives the map death animation. */
data class ThreatRemoved(
    val id: String,
    val lat: Double,
    val lon: Double,
    val type: ThreatType,
    val courseDeg: Double = 0.0,
    val region: String? = null,
    val district: String? = null,
    val locality: String? = null
)

/**
 * Formal Connection State Machine for the NEPTUN WebSocket telemetry feed.
 *
 * Replaces the fragmented boolean flags (connected, degraded,
 * connectInFlight, reconnectAttempt, etc.) with a single, unambiguous state.
 */
sealed interface ConnectionState {

    /** Default initial state before client is started or after explicitly stopped. */
    object Disconnected : ConnectionState

    /** Active WebSocket connection attempt in flight. */
    data class Connecting(
        val generation: Int,
        val attempt: Int,
        val nextRetryAtMs: Long,
        val networkValidated: Boolean
    ) : ConnectionState

    /** Connected and receiving live telemetry frames normally. */
    data class Connected(
        val generation: Int,
        val openedAtMs: Long,
        val lastFrameAtMs: Long
    ) : ConnectionState

    /**
     * Connected (socket is open), but no frame has arrived for >= 30 seconds.
     * Indicates a degraded link / silent stall before the 45s hard watchdog drops it.
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
     * @property reconnectStartMillis Epoch millis when reconnection began (persisted across retries & process restarts).
     * @property reason Human-readable or error message describing the drop.
     * @property attempt Current retry count in the active backoff sequence.
     */
    data class Offline(
        val since: Long,
        val reconnectStartMillis: Long,
        val reason: String? = null,
        val attempt: Int = 0
    ) : ConnectionState

    /**
     * User instructed the app to pause retries (e.g., "Ignore for 30 min").
     * Survives process death and only resumes when the timestamp expires or on manual retry.
     */
    data class Paused(
        val untilMs: Long,
        val since: Long,
        val reconnectStartMillis: Long
    ) : ConnectionState
}

/** Convenience extensions to simplify UI and service queries. */
val ConnectionState.isConnected: Boolean
    get() = this is ConnectionState.Connected || this is ConnectionState.Degraded

val ConnectionState.isDegraded: Boolean
    get() = this is ConnectionState.Degraded

val ConnectionState.isOffline: Boolean
    get() = this is ConnectionState.Offline || this is ConnectionState.Paused || this is ConnectionState.Disconnected

val ConnectionState.isPaused: Boolean
    get() = this is ConnectionState.Paused

val ConnectionState.offlineSinceOrNull: Long?
    get() = when (this) {
        is ConnectionState.Offline -> since
        is ConnectionState.Paused -> since
        else -> null
    }

val ConnectionState.reconnectStartMillisOrZero: Long
    get() = when (this) {
        is ConnectionState.Offline -> reconnectStartMillis
        is ConnectionState.Paused -> reconnectStartMillis
        else -> 0L
    }
