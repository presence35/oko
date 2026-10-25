package com.odesaplay.oko.service

import com.odesaplay.oko.connection.Monotonic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What this process actually knows about the [AlertService] monitor.
 *
 * It used to be a boolean set by the *caller* of `start()`, which made the "service offline"
 * banner a guess: it vanished before the service existed and came back whenever it died. Here
 * only the service reports [State.Running], and a start has to be confirmed within
 * [START_GRACE_MS] or it becomes [State.Dead] - so "no banner" always means "the service told us
 * it is up", and nothing else.
 *
 * Process-scoped on purpose: a killed process remembers nothing, and a deliberate stop must not
 * outlive the session. The durable record is [MonitorLifecycle].
 */
object MonitoringStatus {

    /** How long a start may stay unconfirmed before it counts as dead. */
    internal const val START_GRACE_MS = 10_000L

    sealed interface State {
        /** Nothing has been asked of the monitor yet in this process. */
        data object Unknown : State

        /** A start was requested; the service has not confirmed it yet. */
        data class Starting(val atMs: Long) : State

        /** The service promoted its foreground notification and is monitoring. */
        data class Running(val sinceMs: Long) : State

        /** The user pressed Stop & exit: never revive on their behalf until they launch again. */
        data object StoppedByUser : State

        /** Not running, and nobody asked for that. */
        data class Dead(val reason: Reason, val atMs: Long) : State
    }

    enum class Reason { DESTROYED, START_REFUSED, START_TIMEOUT }

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timeoutJob: Job? = null

    /** A start was asked for. The banner stays hidden for the grace period, then the truth shows. */
    fun startRequested(nowMonoMs: Long = Monotonic.now()) {
        _state.value = State.Starting(nowMonoMs)
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(START_GRACE_MS)
            val current = _state.value
            if (current is State.Starting && current.atMs == nowMonoMs) {
                _state.value = State.Dead(Reason.START_TIMEOUT, Monotonic.now())
            }
        }
    }

    /** The service is really up: this is the only place that says so. */
    fun running(nowMonoMs: Long = Monotonic.now()) {
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = State.Running(nowMonoMs)
    }

    /** A deliberate launch starts a new session: a stop from the previous one no longer applies. */
    fun sessionStarted() {
        if (_state.value is State.StoppedByUser) _state.value = State.Unknown
    }

    fun stoppedByUser() {
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = State.StoppedByUser
    }

    /**
     * The service is gone. Never overrides a deliberate stop, and never replaces a cause that is
     * more precise than [Reason.DESTROYED] - a refused start calls this first, and the service's
     * own destroy lands right after it.
     */
    fun died(reason: Reason, nowMonoMs: Long = Monotonic.now()) {
        when (_state.value) {
            is State.StoppedByUser -> return
            is State.Dead -> return
            else -> Unit
        }
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = State.Dead(reason, nowMonoMs)
    }

    /** Whether the service component exists in this process - what a resurrection path may skip on.
     *  Deliberately not a tick heartbeat: a start command cannot restart a stalled loop (that lives
     *  in `onCreate`), so acting on a suspected freeze would only post a prompt that cannot help. */
    fun isServiceUp(): Boolean = when (_state.value) {
        is State.Starting, is State.Running -> true
        State.Unknown, State.StoppedByUser, is State.Dead -> false
    }

    /** The full-header "service offline" banner: known dead, a start that never landed, or a
     *  stop the user asked for while the app is still open — the one place a restart lives. */
    fun bannerVisible(nowMonoMs: Long = Monotonic.now()): Boolean = when (val current = _state.value) {
        is State.Dead, State.StoppedByUser -> true
        is State.Starting -> nowMonoMs - current.atMs > START_GRACE_MS
        State.Unknown, is State.Running -> false
    }

    /**
     * Whether returning to the foreground should re-arm the monitor. [State.StoppedByUser] is the
     * one state that must not, or "Stop & exit" would be undone by every resume.
     */
    fun needsRestart(): Boolean = when (_state.value) {
        is State.Dead, State.Unknown -> true
        is State.Starting, is State.Running, State.StoppedByUser -> false
    }

    /** Test seam: forget everything this process believed. */
    internal fun reset() {
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = State.Unknown
    }
}
