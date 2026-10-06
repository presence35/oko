package com.odesaplay.oko

import com.odesaplay.oko.service.ServiceState
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment

/**
 * A test that must not inherit persisted state calls [isolateStores] first, in `@Before`, before
 * it constructs anything that reads a store.
 *
 * **Why this exists.** A `preferencesDataStore` delegate hands out one store per file for the life
 * of the class loader, and nothing a test can reach keys it. So the file — and every key in it —
 * survives from one test method to the next: an announced-episode row, a raid-mute deadline, a
 * night-sleep snapshot. A test that reads persisted state directly, or builds [AlertService]
 * (whose `onCreate` restores that state), is otherwise coupled to whatever ran before it in the same
 * JVM, and the coupling is invisible: it shows up as a test that passes alone and fails in a full
 * run. Reopening a file the previous sandbox still holds surfaces as a flaky
 * `FileNotFoundException … being used by another process`.
 *
 * [PreferenceStores.isolate] removes the hazard at the root rather than by discipline: no test ever
 * opens the app's real store files, and a test that asserts defaults can ask for virgin files with
 * [resetStores]. The explicit [ServiceState] wipe below stays — it is cheap, it keeps this helper
 * correct for a caller that wants a clean slate mid-test, and it is what most tests have always
 * relied on.
 *
 * Deliberately **ServiceState only** for that wipe. `UserPrefs` has its own store, but clearing it
 * from a service-level fixture shifted `UserPrefsTest`'s night-sleep snapshot test — the prefs wipe
 * belongs with whoever owns that store's tests, not here.
 */
fun isolateStores() {
    PreferenceStores.isolate()
}

/**
 * For tests that assert **defaults**: brand-new files, so a key an earlier test wrote cannot be
 * mistaken for one the app never sets. Costs a file create per store, so it is not the default.
 */
fun resetStores() {
    PreferenceStores.resetSandbox()
}

/** [isolateStores] plus an empty [ServiceState], for tests that read persisted state. */
fun clearPersistedServiceState() {
    isolateStores()
    runBlocking { ServiceState(RuntimeEnvironment.getApplication()).clearAll() }
}