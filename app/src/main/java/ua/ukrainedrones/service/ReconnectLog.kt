package ua.ukrainedrones

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReconnectAttempt(
    val atMillis: Long,
    val attemptNo: Int,
    val delayMs: Long,
    val error: String?,
    val networkValidated: Boolean,
    val scheduledAt: Long
)

object ReconnectLog {
    private const val MAX_ENTRIES = 50

    private val _entries = MutableStateFlow<List<ReconnectAttempt>>(emptyList())
    val entries: StateFlow<List<ReconnectAttempt>> = _entries.asStateFlow()

    fun recordScheduled(attemptNo: Int, delayMs: Long, networkValidated: Boolean) {
        val now = System.currentTimeMillis()
        val entry = ReconnectAttempt(
            atMillis = now,
            attemptNo = attemptNo,
            delayMs = delayMs,
            error = null,
            networkValidated = networkValidated,
            scheduledAt = now + delayMs
        )
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }

    fun recordFailure(error: String?, attemptNo: Int) {
        val now = System.currentTimeMillis()
        val last = _entries.value.lastOrNull()
        if (last != null && last.error == null && last.attemptNo == attemptNo) {
            val updated = last.copy(error = error ?: "unknown", atMillis = now)
            _entries.value = _entries.value.dropLast(1) + updated
        } else {
            val entry = ReconnectAttempt(
                atMillis = now,
                attemptNo = attemptNo,
                delayMs = 0L,
                error = error ?: "unknown",
                networkValidated = true,
                scheduledAt = now
            )
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        }
    }

    fun recordSuccess() {
        _entries.value = emptyList()
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
