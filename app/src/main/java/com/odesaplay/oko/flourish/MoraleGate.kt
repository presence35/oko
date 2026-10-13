package com.odesaplay.oko

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single source of truth for the master "Morale" switch: mirrors [UserPreferences.moraleMasterEnabled]
 * once per process so every flourish engine gates on the same live value instead of each hand-rolling
 * its own DataStore collector. `isOn` is the read side (entry-point gates); [ejectWhenOff] is the
 * reaction side (a flip to off tears down whatever is in flight).
 *
 * The gate itself stays dumb — it holds no engine and calls no cleanup on its own — so an engine
 * opts into the "off means eject" policy by passing its own teardown, and two engines can never
 * disagree about whether the master is on.
 */
class MoraleGate {
    // Seeded with the shipped default rather than `false`: the master is on out of the box, and a
    // gate that starts "off" until its DataStore collector delivers would briefly lie to every
    // engine that gates on it — including engines that tear down when they read "off".
    private val _on = MutableStateFlow(UserPreferences.DEFAULT.moraleMasterEnabled)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    /** Current master state. Entry points guard with this; see the class doc for the off-policy. */
    val isOn: Boolean get() = _on.value

    /** Mirror [source] into [isOn]. Bound once per process (see [moraleGate]); safe to call again. */
    fun bind(source: Flow<UserPreferences>, scope: CoroutineScope) {
        scope.launch {
            source.map { it.moraleMasterEnabled }
                .distinctUntilChanged()
                .collect { _on.value = it }
        }
    }

    /**
     * Run [clear] whenever the master is (or flips to) off — the one-liner an engine uses instead of
     * re-wiring its own collector. StateFlow replays the current value, so an engine that subscribes
     * while the master is already off tears down immediately rather than waiting for a change.
     */
    fun ejectWhenOff(scope: CoroutineScope, clear: () -> Unit) {
        scope.launch {
            on.filter { !it }.collect { clear() }
        }
    }
}

/** The process-wide gate. Bound in [AppSources.init]; consumed by every flourish engine. */
val moraleGate = MoraleGate()