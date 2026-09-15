package ua.ukrainedrones.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

sealed class DebrisBufferState {
    object Idle : DebrisBufferState()
    data class ActiveCountdown(val secondsRemaining: Int, val totalSeconds: Int) : DebrisBufferState()
    object ClearedFinal : DebrisBufferState()
}

/**
 * State machine managing the Falling Debris Hazard safety delay.
 *
 * Invariant:
 * When an official alert or kinetic threat clears upstream, UI maps reflect CLEARED immediately,
 * but this buffer holds back the audible All-Clear chime for a configured safety duration (e.g. 180 seconds).
 *
 * If a new threat surfaces during the countdown, the buffer aborts instantaneously.
 */
class FallingDebrisBuffer(
    private val scope: CoroutineScope,
    private val onBufferCompletedCleanly: () -> Unit
) {
    private val _state = MutableStateFlow<DebrisBufferState>(DebrisBufferState.Idle)
    val state: StateFlow<DebrisBufferState> = _state.asStateFlow()

    private var countdownJob: Job? = null
    private val isBufferRunning = AtomicBoolean(false)
    private val bufferGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    fun startDebrisBuffer(durationSeconds: Int = 180) {
        val gen = bufferGeneration.incrementAndGet()
        countdownJob?.cancel()
        if (durationSeconds <= 0) {
            isBufferRunning.set(false)
            _state.value = DebrisBufferState.ClearedFinal
            onBufferCompletedCleanly()
            return
        }

        isBufferRunning.set(true)
        countdownJob = scope.launch(Dispatchers.Default) {
            var remaining = durationSeconds
            while (remaining > 0 && isActive && isBufferRunning.get() && bufferGeneration.get() == gen) {
                _state.value = DebrisBufferState.ActiveCountdown(remaining, durationSeconds)
                delay(1000L)
                remaining--
            }
            if (isActive && isBufferRunning.get() && bufferGeneration.get() == gen) {
                _state.value = DebrisBufferState.ClearedFinal
                isBufferRunning.set(false)
                onBufferCompletedCleanly()
            }
        }
    }

    fun abortBuffer(reason: String) {
        bufferGeneration.incrementAndGet()
        if (isBufferRunning.compareAndSet(true, false)) {
            countdownJob?.cancel()
            countdownJob = null
            _state.value = DebrisBufferState.Idle
        }
    }

    fun reset() {
        abortBuffer("Reset")
        _state.value = DebrisBufferState.Idle
    }

    val isRunning: Boolean
        get() = isBufferRunning.get()
}
