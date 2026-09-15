package ua.ukrainedrones.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure countdown timer for the Falling Debris safety delay.
 * Holds back the audible All-Clear chime after an alert drops.
 * Zero city tracking, zero mirror variables — purely an honest countdown state.
 */
class FallingDebrisBuffer(
    private val scope: CoroutineScope,
    private val onCompleted: () -> Unit
) {
    private val _secondsRemaining = MutableStateFlow(0)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    private var countdownJob: Job? = null

    val isRunning: Boolean
        get() = _secondsRemaining.value > 0

    fun start(durationSeconds: Int = 180) {
        countdownJob?.cancel()
        if (durationSeconds <= 0) {
            _secondsRemaining.value = 0
            onCompleted()
            return
        }

        _secondsRemaining.value = durationSeconds
        countdownJob = scope.launch(Dispatchers.Default) {
            for (sec in durationSeconds downTo 1) {
                _secondsRemaining.value = sec
                delay(1000L)
            }
            _secondsRemaining.value = 0
            onCompleted()
        }
    }

    fun abort() {
        countdownJob?.cancel()
        countdownJob = null
        _secondsRemaining.value = 0
    }
}
