package com.odesaplay.oko.service

import com.odesaplay.oko.RaidMute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory mirror of [AlertService]'s "mute raid" state, so the UI can explain why alerts are
 * silent (and offer unmute). Process-scoped like [MonitoringStatus]; the service is the single
 * writer. The *deadline* is what persists across a service restart (ServiceState), and this
 * mirror is re-seeded from it on the next start — see [AlertService]'s `restoreRaidMute`.
 * Consumers pass `now`, so a lapsed deadline can never read as a live countdown.
 */
object RaidMuteState {
    private val _state = MutableStateFlow<RaidMute>(RaidMute.None)
    val state: StateFlow<RaidMute> = _state.asStateFlow()

    fun set(value: RaidMute) {
        _state.value = value
    }

    /** The process is going away: the mirror dies with it, the persisted deadline does not. */
    fun reset() {
        _state.value = RaidMute.None
    }
}
