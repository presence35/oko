package com.odesaplay.oko.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One transition of the monitoring service, as the durable record sees it. */
data class MonitorEvent(val atMs: Long, val kind: Kind, val cause: String? = null) {
    enum class Kind {
        /** The service reached onStartCommand. */
        START,
        /** The user pressed Stop & exit. */
        STOP_BY_USER,
        /** The service was destroyed without a user stop - the OS took it. */
        STOP_OS,
        /** A resurrection path asked the OS for a foreground start and was refused. */
        RESURRECT_BLOCKED
    }
}

/**
 * Append-only lifecycle record of the monitor, persisted in [ServiceState].
 *
 * It exists because the alert log cannot answer the one question every "no alerts for hours"
 * report turns into: was monitoring off because the user chose it, or because the OS killed it?
 * [DebugLog] stops the moment the service dies (nothing is left to write it), and
 * `ApplicationExitInfo` only says the *process* died, minutes after the service did. This is
 * written by whoever is still standing - the service, the worker that found it dead, or the UI
 * at Stop & exit - so a gap in the tick log always has a neighbour that names its cause.
 *
 * Bounded twice on purpose: [KEEP_MS] must outlive the kind of incident being reported (a
 * multi-day trip with the monitor deliberately off), and [MAX_EVENTS] keeps the write small.
 */
object MonitorLifecycle {

    const val MAX_EVENTS = 64

    /** Seven days: a debug ring that forgets yesterday cannot explain last week's silence. */
    internal const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

    /** Writes are rare (a handful a day) and must not be tied to the service's lifetime. */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun encode(events: List<MonitorEvent>): String = events.joinToString("\n") { event ->
        buildString {
            append(event.atMs).append('|').append(event.kind.name)
            event.cause?.let { append('|').append(it) }
        }
    }

    fun decode(raw: String): List<MonitorEvent> = raw.lineSequence().mapNotNull { line ->
        val parts = line.split('|', limit = 3)
        if (parts.size < 2) return@mapNotNull null
        val atMs = parts[0].toLongOrNull() ?: return@mapNotNull null
        val kind = runCatching { MonitorEvent.Kind.valueOf(parts[1]) }.getOrNull()
            ?: return@mapNotNull null
        MonitorEvent(atMs, kind, parts.getOrNull(2)?.takeIf { it.isNotBlank() })
    }.toList()

    /** Drop rows older than [KEEP_MS], then the oldest beyond [MAX_EVENTS]. */
    fun prune(events: List<MonitorEvent>, nowMs: Long): List<MonitorEvent> =
        events.filter { it.atMs >= nowMs - KEEP_MS }.takeLast(MAX_EVENTS)

    fun append(existing: List<MonitorEvent>, event: MonitorEvent, nowMs: Long): List<MonitorEvent> =
        prune(existing + event, nowMs)

    fun events(context: Context): Flow<List<MonitorEvent>> =
        ServiceState(context.applicationContext).monitorEvents().map(::decode)

    /** Suspend version for paths that must land before the caller finishes, i.e. Stop & exit. */
    suspend fun record(
        context: Context,
        kind: MonitorEvent.Kind,
        cause: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val state = ServiceState(context.applicationContext)
        val updated = append(decode(state.monitorEvents().first()), MonitorEvent(nowMs, kind, cause), nowMs)
        state.setMonitorEvents(encode(updated))
    }

    /**
     * Fire-and-forget for paths with no suspend context (onDestroy, a refused background start).
     * Deliberately not tied to the service scope - that one is cancelled in the same breath.
     */
    fun recordNow(context: Context, kind: MonitorEvent.Kind, cause: String? = null) {
        val appContext = context.applicationContext
        writeScope.launch { runCatching { record(appContext, kind, cause) } }
    }
}
