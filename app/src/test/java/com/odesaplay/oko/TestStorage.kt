package com.odesaplay.oko

import com.odesaplay.oko.service.ServiceState
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment

/**
 * Clear the persisted [ServiceState] store before a test.
 *
 * Robolectric builds one `preferencesDataStore` instance per JVM, so its backing file — and every
 * key it holds — survives from one test method to the next: an announced-episode row, a raid-mute
 * deadline, a night-sleep snapshot. A test that reads persisted state directly, or builds
 * [AlertService] (whose `onCreate` restores that state), is otherwise coupled to whatever ran
 * before it in the same JVM, and the coupling is invisible: it shows up as a test that passes
 * alone and fails in a full run.
 *
 * Call this first thing in `@Before`, before constructing anything that reads the store.
 *
 * Deliberately **ServiceState only**. `UserPrefs` has its own store with the same hazard, but
 * every prefs test already starts from `UserPrefs.clearAll()` and clearing it from a
 * service-level fixture shifted `UserPrefsTest`'s night-sleep snapshot test — the prefs wipe
 * belongs with whoever owns that store's tests, not here.
 */
fun clearPersistedServiceState() {
    runBlocking { ServiceState(RuntimeEnvironment.getApplication()).clearAll() }
}
