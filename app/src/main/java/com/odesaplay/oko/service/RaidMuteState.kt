package com.odesaplay.oko.service

import com.odesaplay.oko.RaidMute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory mirror of [AlertService]'s "mute raid" bells state, so the UI can explain why
 * alerts are silent (and offer unmute). Process-scoped like [MonitoringStatus]; the service
 * is the single writer. Timed mutes are resolved by deadline in [RaidMute.silent], so this
 * value may outlive the mute itself — consumers always pass `now`.
 */
object RaidMuteState {
    private val _state = MutableStateFlow<RaidMute>(RaidMute.None)
    val state: StateFlow<RaidMute> = _state.asStateFlow()

    fun set(value: RaidMute) {
        _state.value = value
    }
}
