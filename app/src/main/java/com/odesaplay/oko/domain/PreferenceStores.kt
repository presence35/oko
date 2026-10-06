package com.odesaplay.oko

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one place a preference store comes into existence. Three stores exist: `user_prefs`,
 * `service_state`, `widget_snapshot`.
 *
 * **Why not `by preferencesDataStore(...)`.** The delegate hands out one store per file for the
 * life of the class loader, keyed by nothing a test can reach. That is correct in the app, and in
 * tests it means the file — and every key in it — survives from one test method to the next, so a
 * test that reads persisted state is coupled to whatever ran before it in the same JVM. The
 * coupling is invisible: it shows up as a test that passes alone and fails in a full run. The
 * second problem is the same file being reopened while the previous sandbox still holds it, which
 * surfaces as a flaky `FileNotFoundException … being used by another process`.
 *
 * **What this changes, and what it must not.** Stores are still keyed by file name and still shared
 * by every instance in the app: `ServiceState(context)` twice is one store over one file, exactly as
 * before. DataStore refuses two live instances over one file, so a per-instance store would have
 * been a production bug. The only new freedom is [isolate], which routes subsequent lookups to
 * throwaway files — and it exists for tests, which install it in `@Before` and drop it in `@After`.
 */
internal object PreferenceStores {
    private val production = ConcurrentHashMap<String, DataStore<Preferences>>()
    private val sequence = AtomicInteger()

    /**
     * Non-null once a test has asked for isolation, null in the app, always. Created once per JVM
     * and then left alone on purpose: giving every test method its own files costs real wall clock
     * (a cold file is created, written, locked and parsed on every store read), and it buys almost
     * nothing — a test that must not inherit state can wipe the store it reads, which is what the
     * suite already does. What isolation *does* buy is free and permanent: no test ever opens the
     * app's real `user_prefs` file.
     */
    @Volatile
    private var sandbox: ConcurrentHashMap<String, DataStore<Preferences>>? = null

    fun of(context: Context, name: String): DataStore<Preferences> {
        val throwaway = sandbox
        if (throwaway != null) {
            return throwaway.getOrPut(name) {
                create(context, "test-$name-${sequence.incrementAndGet()}")
            }
        }
        return production.getOrPut(name) { create(context, name) }
    }

    /**
     * Test-only, idempotent: from here on every store lands in a throwaway file. Call it in
     * `@Before`; calling it again changes nothing, because the sandbox is per JVM by design.
     */
    fun isolate() {
        if (sandbox == null) sandbox = ConcurrentHashMap()
    }

    /**
     * Test-only: throw the sandbox away, so the next store read starts from a brand-new file. For
     * the tests that assert *defaults* — they must not see a key an earlier test wrote, and wiping
     * the store they own is not always enough when two stores are involved.
     */
    fun resetSandbox() {
        isolate()
        sandbox = ConcurrentHashMap()
    }

    private fun create(context: Context, fileName: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // Same handler the `preferencesDataStore` delegate uses: a corrupt file is replaced with
            // empty preferences rather than throwing forever.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(fileName) }
        )
}