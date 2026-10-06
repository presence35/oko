package com.odesaplay.oko.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil

/**
 * Pure countdown timer for the Falling Debris safety delay.
 * Holds back the audible All-Clear chime after an alert drops.
 * Zero city tracking, zero mirror variables — purely an honest countdown state.
 *
 * The countdown is a wall-clock DEADLINE, not a loop counter, which is the whole point:
 * a `for (n downTo 1) delay(1000)` freezes when the CPU sleeps (the delay never elapses),
 * so after doze the displayed time is a stale number that has quietly stopped counting. Every
 * tick here is derived from [deadlineMs] minus the current clock, so a suspended process
 * resumes with the truth, and a shortened setting can shorten a countdown already running.
 */
class FallingDebrisBuffer(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onTick: ((Int) -> Unit)? = null,
    private val onCompleted: () -> Unit
) {
    companion object {
        private val _secondsRemaining = MutableStateFlow(0)
        val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()
    }

    private var countdownJob: Job? = null

    /** Wall-clock instant the countdown ends; 0 when nothing is running. */
    private var deadlineMs = 0L

    val isRunning: Boolean
        get() = _secondsRemaining.value > 0

    fun start(durationSeconds: Int = 180) {
        countdownJob?.cancel()
        countdownJob = null
        if (durationSeconds <= 0) {
            deadlineMs = 0L
            _secondsRemaining.value = 0
            onCompleted()
            return
        }

        deadlineMs = nowMs() + durationSeconds * 1000L
        _secondsRemaining.value = durationSeconds
        // On the scope it was handed, not a dispatcher of its own: the countdown is the caller's
        // work, so it belongs to the caller's scope. (AlertService's scope is already a background
        // one.) Pinning a dispatcher here also pinned the loop to real time, which is what stopped
        // the tests below from controlling it.
        countdownJob = scope.launch {
            while (true) {
                val remaining = remainingSeconds()
                _secondsRemaining.value = remaining
                if (remaining <= 0) {
                    deadlineMs = 0L
                    onCompleted()
                    break
                }
                onTick?.invoke(remaining)
                delay(1000L)
            }
        }
    }

    /**
     * Shorten a running countdown to at most [durationSeconds], per a setting change. Only ever
     * shortens: raising the slider mid-countdown must not hand someone extra seconds of
     * silence they never chose. No-op when nothing is running — the next all-clear starts.
     */
    fun shrinkTo(durationSeconds: Int) {
        if (deadlineMs == 0L || durationSeconds <= 0) return
        val remaining = remainingSeconds()
        val target = durationSeconds.coerceAtMost(remaining)
        if (target >= remaining) return
        deadlineMs = nowMs() + target * 1000L
        _secondsRemaining.value = target
    }

    fun abort() {
        countdownJob?.cancel()
        countdownJob = null
        deadlineMs = 0L
        _secondsRemaining.value = 0
    }

    /** Whole seconds left at [at], always derived from the deadline — never from a decrementing
     *  loop. Public and clock-parameterised so the arithmetic is testable without sleeping. */
    fun remainingAt(at: Long): Int {
        if (deadlineMs == 0L) return 0
        val left = deadlineMs - at
        if (left <= 0L) return 0
        return ceil(left / 1000.0).toInt()
    }

    private fun remainingSeconds(): Int = remainingAt(nowMs())
}