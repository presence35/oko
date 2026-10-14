package com.odesaplay.oko.service

/**
 * Process-wide "Ignore 30 min" mute for offline rows. The shade's Ignore tap is served by
 * [IgnoreRetryReceiver] without starting the service — on a cold process that start alone costs
 * seconds — so the deadline cannot live in the service instance; it lives here, where both the
 * receiver (writer) and the service ticks (readers) see it.
 */
object OfflineIgnore {
    @Volatile private var mutedUntilMs = 0L

    fun ignoreFor(durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        mutedUntilMs = nowMs + durationMs
    }

    fun isMuted(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < mutedUntilMs

    internal fun reset() {
        mutedUntilMs = 0L
    }
}
