package ua.ukrainedrones.source

/** A source's connection state, surfaced in the Logs Sources tab and the aggregate health. */
enum class SourceState {
    DISCONNECTED, CONNECTING, CONNECTED, DEGRADED, OFFLINE, PAUSED
}