package ua.ukrainedrones.connection

import android.os.SystemClock

/**
 * Monotonic uptime clock for measuring elapsed durations that are computed and consumed
 * entirely within this process. Unlike [System.currentTimeMillis], it is immune to wall-clock
 * jumps (auto-sync, manual NTP, device timezone/clock changes), so a backward clock jump can't
 * silently stall a watchdog, and a forward jump can't mass-declare everything stale.
 *
 * Never use this for server-reported timestamps (NEPTUN `updatedAt`/`confirmedAt` are epoch
 * millis and must keep comparing against a wall-clock `now`), nor for anything persisted across
 * process restarts (e.g. `reconnectStartMillis`, `ignoreUntilMs`).
 */
object Monotonic {
    @Volatile
    internal var nowProvider: () -> Long = { SystemClock.elapsedRealtime() }

    fun now(): Long = nowProvider()
}