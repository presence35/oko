package com.odesaplay.oko.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.odesaplay.oko.AlertService
import com.odesaplay.oko.UserPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The OS pause gesture — the one event no other component can see.
 *
 * Suspension stops the app's activities, hides its notifications and blocks its audio, but leaves a
 * foreground service running, so a paused Oko stayed in "Active apps" and its watchdog kept
 * resurrecting what the user had just stopped. This is where that ends: the service stops and the
 * wakeup chain is cancelled, so "paused" means nothing of ours is left running or scheduled.
 *
 * No resurrection path needs to cooperate with this. `AlertService.startResilient` refuses on its
 * own while the package is suspended, and the monitor loop stops the service on its next tick — so
 * a broadcast the OEM swallowed, or a suspend we never saw, still cannot leave a paused monitor
 * alive. Relying on the OS to stop us is exactly what produced the bug.
 *
 * Un-pausing means "let it work again", so it puts back what the pause took away — the wakeup
 * chain, the watchdog, and monitoring — under the same `boot_restart_enabled` policy as every other
 * automatic start, so the one setting the user controls still decides.
 */
class SuspendReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_SUSPENDED -> {
                AlertService.stop(appContext)
                AlertWakeup.cancel(appContext)
            }
            Intent.ACTION_MY_PACKAGE_UNSUSPENDED -> {
                if (runBlocking { UserPrefs(appContext).preferences.first().bootRestartEnabled }) {
                    // Restore the chain the pause cancelled whether or not the start below is
                    // permitted right now — a background start Android 12+ refuses still needs a
                    // wakeup left to try again.
                    AlertWakeup.schedule(appContext)
                    AlertService.startResilient(appContext, fromUnpause = true)
                }
                AlertWatchdog.schedule(appContext)
            }
        }
    }
}
