package com.odesaplay.oko.service

import android.content.Context

/**
 * Is this package suspended — "Paused" in the app-info screen, or suspended by a work-profile
 * admin?
 *
 * Read from the OS every time, never cached. A resurrection path can run in a process the system
 * has just started for it (a job, or the wakeup alarm), where any in-memory answer would be a fresh
 * `false` — worse than no answer at all.
 *
 * Suspension stops the app's activities, hides its notifications and blocks its audio, but
 * *deliberately leaves a foreground service running*. That is how a paused Oko kept sitting in
 * "Active apps" with its watchdog resurrecting it: nothing about suspension ends an FGS, so ending
 * it is ours to do — [SuspendReceiver] at the gesture, and the monitor loop as a backstop.
 *
 * `isPackageSuspended` is API 24, below our minSdk 26, so there is no version branch and no
 * compatibility shim. Where a device cannot suspend a package at all, the answer is simply always
 * false and every call site behaves exactly as it did before.
 */
object SuspendState {

    /**
     * The live fact. A failure means "cannot tell", which must read as *not* suspended: quietly
     * suppressing monitoring over a query hiccup would be the unsafe direction.
     */
    fun isSuspended(context: Context): Boolean = try {
        context.packageManager.isPackageSuspended(context.packageName)
    } catch (_: Exception) {
        false
    }
}
